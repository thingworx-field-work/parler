package com.thingworx.things.agent.investigation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisBudgetAccounting;
import com.thingworx.things.agent.evidence.EvidenceAssessment;
import com.thingworx.things.agent.evidence.EvidenceMethodRef;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * Maps FRC-3 {@link RcaInvestigationResult} onto U3 {@link EvidenceAssessment} + shared budget
 * accounting for Playbook/App rendering and final-answer guards. No resident RCA tool / no new
 * model-visible {@code AnalysisOperation} (D2/D3 — G7 Playbook-only).
 */
public final class U6RcaEnvelopeFactory {

    public static final String METHOD_ID = "rca_investigation";
    public static final String METHOD_VERSION = "1";

    private U6RcaEnvelopeFactory() {}

    public static final class CompactEnvelope {
        private final EvidenceAssessment assessment;
        private final AnalysisBudgetAccounting budget;
        private final Map<String, String> metrics;
        private final List<String> summaryFacts;
        private final List<HypothesisLedgerEntry> ledger;

        CompactEnvelope(
                EvidenceAssessment assessment,
                AnalysisBudgetAccounting budget,
                Map<String, String> metrics,
                List<String> summaryFacts,
                List<HypothesisLedgerEntry> ledger) {
            this.assessment = assessment;
            this.budget = budget;
            this.metrics = metrics;
            this.summaryFacts = summaryFacts;
            this.ledger = ledger;
        }

        public EvidenceAssessment assessment() {
            return assessment;
        }

        public AnalysisBudgetAccounting budget() {
            return budget;
        }

        public Map<String, String> metrics() {
            return metrics;
        }

        public List<String> summaryFacts() {
            return summaryFacts;
        }

        public List<HypothesisLedgerEntry> ledger() {
            return ledger;
        }
    }

    public static CompactEnvelope fromInvestigation(
            RcaInvestigationResult result, String profileDigest, AnalysisBudgetAccounting budget) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(budget, "budget");
        String digest = profileDigest == null || profileDigest.isBlank() ? "u6-demo" : profileDigest.trim();

        Map<String, String> metrics = new LinkedHashMap<>(result.metrics());
        metrics.putAll(result.request().budget().searchLimitMetrics());
        metrics.put("budget.consumedRows", Long.toString(budget.consumedRows()));
        metrics.put("budget.consumedWallTimeMillis", Long.toString(budget.consumedWallTimeMillis()));
        metrics.put("budget.clamped", Boolean.toString(budget.clamped()));
        long deprioritizedN = result.ledger().stream().filter(HypothesisLedgerEntry::deprioritized).count();
        metrics.put("deprioritizedN", Long.toString(deprioritizedN));
        if (!result.reasonCodes().isEmpty()) {
            metrics.put("reasonCodes", String.join(",", result.reasonCodes()));
        }

        CompletenessStatus completeness = CompletenessStatus.COMPLETE;
        if (result.reasonCodes().contains(RcaOutcomeCodes.PARTIAL_EVENT_HISTORY)) {
            completeness = CompletenessStatus.PARTIAL;
        }

        List<String> applicability = new ArrayList<>();
        applicability.add("associational");
        applicability.add("not_tested_causal");

        EvidenceAssessment assessment = EvidenceAssessment.builder()
                .status(result.status())
                .completeness(completeness)
                .n(result.ledger().size())
                .applicability(applicability)
                .warnings(result.reasonCodes())
                .method(EvidenceMethodRef.of(METHOD_ID, METHOD_VERSION, digest))
                .build();

        List<String> summary = new ArrayList<>(result.summaryFacts());
        summary.add("method=" + METHOD_ID);
        if (deprioritizedN > 0) {
            summary.add("deprioritizedN=" + deprioritizedN);
        }
        if (result.status() == EvidenceStatus.NO_FINDING) {
            summary.add("no_supported_candidate_is_valid");
        }

        return new CompactEnvelope(
                assessment,
                budget,
                Map.copyOf(metrics),
                List.copyOf(summary),
                result.ledger());
    }
}
