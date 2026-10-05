package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisBudgetAccounting;
import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeBuilder;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.AnalysisOutcomeCodes;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * Maps FRC-2 {@link FleetBenchmarkResult} onto the U4-owned {@link AnalysisEnvelope}. Chart intent
 * and compact summary only — does not advertise {@code fleet_benchmark} (D3 App-invoked packaging).
 */
public final class U6FleetEnvelopeFactory {

    public static final String CHART_INTENT = "u6.fleet_benchmark.distribution";
    public static final String METHOD_ID = "fleet_benchmark";

    private U6FleetEnvelopeFactory() {}

    public static AnalysisEnvelope fromBenchmark(FleetBenchmarkResult result, String profileDigest) {
        return fromBenchmark(result, profileDigest, null);
    }

    public static AnalysisEnvelope fromBenchmark(
            FleetBenchmarkResult result, String profileDigest, AnalysisBudgetAccounting budget) {
        Objects.requireNonNull(result, "result");
        String digest = profileDigest == null || profileDigest.isBlank() ? "u6-demo" : profileDigest.trim();
        U6FleetCapability capability = U6FleetCapabilityCatalog.find(METHOD_ID)
                .orElseThrow(() -> new IllegalStateException("fleet_benchmark capability missing"));

        CohortCoverageCounts coverage = result.publishedCoverage();
        CompletenessStatus completeness = result.sourceCompleteness() == null
                ? CompletenessStatus.UNKNOWN
                : result.sourceCompleteness();

        Map<String, String> metrics = new LinkedHashMap<>();
        String primaryOutcome = primaryOutcome(result);
        metrics.put(AnalysisOutcomeCodes.METRIC_KEY, primaryOutcome);
        metrics.put("requestedAuthorizedN", Integer.toString(coverage.requestedAuthorizedN()));
        metrics.put("returnedN", Integer.toString(coverage.returnedN()));
        metrics.put("valuedN", Integer.toString(coverage.valuedN()));
        metrics.put("comparableN", Integer.toString(coverage.comparableN()));
        metrics.put("permissionLimited", Boolean.toString(coverage.permissionLimited()));
        metrics.put("batchSourcePartial", Boolean.toString(result.batchSourcePartial()));
        metrics.put("focusOutsideTopN", Boolean.toString(result.focusOutsideTopN()));
        metrics.put("zeroDispersion", Boolean.toString(result.zeroDispersion()));
        if (result.distribution() != null) {
            metrics.put("median", Double.toString(result.distribution().median()));
            metrics.put("mad", Double.toString(result.distribution().mad()));
            metrics.put("q1", Double.toString(result.distribution().q1()));
            metrics.put("q3", Double.toString(result.distribution().q3()));
        }
        if (result.focusPosition() != null) {
            metrics.put("focusCompetitionRank", Integer.toString(result.focusPosition().competitionRank()));
            metrics.put(
                    "focusStatisticalPercentile",
                    Double.toString(result.focusPosition().statisticalPercentile()));
        }
        if (result.focusGatedStatus() != null) {
            // Authorized focus excluded from comparable set — status, not FOCUS_NOT_IN_COHORT.
            metrics.put("focusMemberStatus", result.focusGatedStatus().name());
        }
        if (!result.reasonCodes().isEmpty()) {
            metrics.put("reasonCodes", String.join(",", result.reasonCodes()));
        }
        if (budget != null) {
            metrics.put("budget.consumedRows", Long.toString(budget.consumedRows()));
            metrics.put("budget.consumedWallTimeMillis", Long.toString(budget.consumedWallTimeMillis()));
            metrics.put("budget.clamped", Boolean.toString(budget.clamped()));
        }

        List<String> summary = new ArrayList<>();
        summary.add("operation=" + AnalysisOperation.FLEET_BENCHMARK.wireName());
        summary.add("comparableN=" + coverage.comparableN());
        summary.add("outcome=" + primaryOutcome);
        if (coverage.permissionLimited() || result.batchSourcePartial()) {
            summary.add("coverage=among_authorized_comparable_members_covered");
        }
        if (result.focusOutsideTopN()) {
            summary.add("focusOutsideTopN=true");
        }
        if (result.zeroDispersion()) {
            summary.add("robustZ=undefined_mad_zero");
        }

        long n = coverage.comparableN();
        AnalysisEnvelopeBuilder b = AnalysisEnvelopeBuilder.create()
                .status(result.status())
                .operation(AnalysisOperation.FLEET_BENCHMARK)
                .method(capability.toMethodDescriptor(digest))
                .completeness(completeness)
                .n(n)
                .metrics(metrics)
                .summaryFacts(summary)
                .chartIntent(CHART_INTENT)
                .inputsFullyScanned(!result.batchSourcePartial())
                .rowsRead(coverage.returnedN())
                .rowsOutput(result.topWithFocus().size());
        if (budget != null) {
            b.budget(budget);
        }
        if (result.reasonCodes().contains(FleetOutcomeCodes.COHORT_PARTIAL)) {
            b.addWarning(FleetOutcomeCodes.COHORT_PARTIAL);
        }
        if (result.reasonCodes().contains(FleetOutcomeCodes.FOCUS_NOT_IN_COHORT)) {
            b.addWarning(FleetOutcomeCodes.FOCUS_NOT_IN_COHORT);
        }
        if (result.reasonCodes().contains(FleetOutcomeCodes.NO_COMPARABLE_MEMBERS)) {
            b.addApplicability(FleetOutcomeCodes.NO_COMPARABLE_MEMBERS);
        }
        return b.build();
    }

    private static String primaryOutcome(FleetBenchmarkResult result) {
        if (result.reasonCodes().contains(FleetOutcomeCodes.NO_COMPARABLE_MEMBERS)) {
            return FleetOutcomeCodes.NO_COMPARABLE_MEMBERS;
        }
        if (result.reasonCodes().contains(FleetOutcomeCodes.FOCUS_NOT_IN_COHORT)
                && result.distribution() == null) {
            return FleetOutcomeCodes.FOCUS_NOT_IN_COHORT;
        }
        if (result.reasonCodes().contains(FleetOutcomeCodes.COHORT_PARTIAL)) {
            return FleetOutcomeCodes.COHORT_PARTIAL;
        }
        return "SUCCESS";
    }
}
