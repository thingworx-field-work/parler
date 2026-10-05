package com.thingworx.things.agent.analysis;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.evidence.EvidenceAssessment;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * Structural and vocabulary checks for the base {@link AnalysisEnvelope}. Rejects private
 * duplicate status/completeness enums by construction (types are U3/U2).
 */
public final class AnalysisEnvelopeValidator {

    /**
     * Leading token of the scope disclosure a {@link AnalysisOperationClass#MEASUREMENT} envelope must
     * carry to report {@code SUCCESS} under unproven completeness. Any other warning is not a substitute.
     */
    public static final String SCOPE_OBSERVED_SPAN = "SCOPE_OBSERVED_SPAN";

    private AnalysisEnvelopeValidator() {}

    public static void validateOrThrow(AnalysisEnvelope envelope) {
        List<String> errors = validate(envelope);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("invalid AnalysisEnvelope: " + String.join("; ", errors));
        }
    }

    public static List<String> validate(AnalysisEnvelope envelope) {
        List<String> errors = new ArrayList<>();
        if (envelope == null) {
            errors.add("envelope is null");
            return errors;
        }
        if (envelope.status() == null) {
            errors.add("status required");
        }
        if (envelope.operation() == null) {
            errors.add("operation required");
        }
        EvidenceAssessment evidence = envelope.evidence();
        if (evidence == null) {
            errors.add("evidence required");
            return errors;
        }
        if (evidence.status() == null) {
            errors.add("evidence.status required");
        } else if (envelope.status() != null && evidence.status() != envelope.status()) {
            errors.add("evidence.status must match envelope.status");
        }
        CompletenessStatus completeness = evidence.completeness();
        if (completeness == null) {
            errors.add("evidence.completeness required");
        }
        // U5 quantification (RELATIONSHIP/TREND) may succeed under pairwise / partial sources with
        // explicit used/dropped metrics (DIK-3 completeness decision; DIK-5 carve-out). U6 fleet
        // benchmark may succeed for a covered authorized cohort under PARTIAL/UNKNOWN completeness
        // with COHORT_PARTIAL / permissionLimited disclosed (fleet-rca §7.2 / FRC-2). Detection
        // and U4 ops still require COMPLETE for SUCCESS.
        boolean u5QuantificationCarveOut = envelope.operation() != null
                && envelope.operation().isQuantification()
                && envelope.operation().isU5();
        boolean u6FleetPartialCarveOut = envelope.operation() != null
                && envelope.operation().isU6();
        // Measurements describe the observations read, so they may succeed under UNKNOWN/PARTIAL
        // sources, but only while the envelope states that scope. A non-empty warning list is not
        // enough: UNKNOWN sources always warn about something.
        boolean measurement = envelope.operation() != null && envelope.operation().isMeasurement();
        if (envelope.status() == EvidenceStatus.SUCCESS
                && completeness != CompletenessStatus.COMPLETE
                && measurement
                && !disclosesObservedSpan(evidence)) {
            errors.add("measurement SUCCESS under unproven completeness requires a "
                    + SCOPE_OBSERVED_SPAN + " warning");
        }
        if (envelope.status() == EvidenceStatus.SUCCESS
                && completeness != CompletenessStatus.COMPLETE
                && !u5QuantificationCarveOut
                && !u6FleetPartialCarveOut
                && !measurement) {
            errors.add("SUCCESS requires proven COMPLETE completeness");
        }
        if (envelope.status() == EvidenceStatus.SUCCESS && evidence.n() <= 0L) {
            errors.add("SUCCESS requires n > 0");
        }
        // U4: NO_FINDING+COMPLETE implies empty finding population (n==0).
        // U5 detection: NO_FINDING is allowed only after sufficient eligible evidence (n>0);
        // method-specific support floors remain executor-owned (DIK-2/3/4).
        if (envelope.status() == EvidenceStatus.NO_FINDING
                && envelope.operation() != null
                && envelope.operation().isDetection()
                && evidence.n() <= 0L) {
            errors.add("U5 detection NO_FINDING requires positive support n");
        }
        if (envelope.status() == EvidenceStatus.NO_FINDING
                && completeness == CompletenessStatus.COMPLETE
                && evidence.n() > 0L
                && (envelope.operation() == null || !envelope.operation().isDetection())) {
            errors.add("NO_FINDING with COMPLETE must not carry positive n");
        }
        if (envelope.findingCacheId() != null && envelope.findingCacheId().isBlank()) {
            errors.add("findingCacheId must be null or non-blank");
        }
        if (envelope.method() != null && envelope.method().operation() != envelope.operation()) {
            errors.add("method.operation must match envelope.operation");
        }
        if (envelope.rowsRead() < 0L || envelope.rowsOutput() < 0L) {
            errors.add("rowsRead/rowsOutput must be non-negative");
        }
        AnalysisBudgetAccounting budget = envelope.budget();
        if (budget != null) {
            if (budget.consumedRows() < 0L
                    || budget.consumedBytes() < 0L
                    || budget.consumedWallTimeMillis() < 0L) {
                errors.add("budget consumed counters must be non-negative");
            }
        }
        validateU5OutcomeCode(envelope, errors);
        return errors;
    }

    private static boolean disclosesObservedSpan(EvidenceAssessment evidence) {
        for (String warning : evidence.warnings()) {
            if (warning != null && warning.startsWith(SCOPE_OBSERVED_SPAN)) {
                return true;
            }
        }
        return false;
    }

    private static void validateU5OutcomeCode(AnalysisEnvelope envelope, List<String> errors) {
        AnalysisOperation op = envelope.operation();
        if (op == null || !op.isU5()) {
            return;
        }
        EvidenceStatus status = envelope.status();
        if (status == null || status == EvidenceStatus.ERROR) {
            return;
        }
        String code = AnalysisOutcomeCodes.fromMetrics(envelope.metrics());
        if (code == null) {
            errors.add("U5 envelopes require metrics." + AnalysisOutcomeCodes.METRIC_KEY);
            return;
        }
        if (op == AnalysisOperation.THRESHOLD_CROSSING) {
            try {
                ThresholdCrossingOutcome outcome = ThresholdCrossingOutcome.fromCode(code);
                if (status != outcome.status()) {
                    errors.add("threshold_crossing outcomeCode " + code + " requires status "
                            + outcome.status() + " but was " + status);
                }
            } catch (IllegalArgumentException ex) {
                errors.add(ex.getMessage());
            }
            return;
        }
        if (op.isQuantification() && status == EvidenceStatus.NO_FINDING) {
            errors.add("quantification operation " + op.wireName()
                    + " must not use NO_FINDING (weak effects are SUCCESS)");
        }
    }
}
