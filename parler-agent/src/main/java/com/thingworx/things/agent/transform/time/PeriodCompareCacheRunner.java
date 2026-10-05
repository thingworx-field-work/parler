package com.thingworx.things.agent.transform.time;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeBuilder;
import com.thingworx.things.agent.analysis.AnalysisMethodDescriptor;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.TypedTabularStream;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * TQJ-5 ArtifactCache-backed G3 period-over-period comparison. Assessment-only — no derived handle.
 */
public final class PeriodCompareCacheRunner {

    public static final String ROUTE_ID = "u4.period_compare";
    public static final String PROFILE_DIGEST = "demo-period-compare-v1";

    private PeriodCompareCacheRunner() {}

    public static PeriodCompareRunResult run(
            String sourceCacheId,
            String timeColumn,
            String valueColumn,
            HalfOpenWindow originalWindow,
            HalfOpenWindow currentWindow,
            Aggregation aggregation) throws Exception {
        Objects.requireNonNull(originalWindow, "originalWindow");
        Objects.requireNonNull(currentWindow, "currentWindow");
        Objects.requireNonNull(aggregation, "aggregation");
        if (sourceCacheId == null || sourceCacheId.isBlank()) {
            throw new IllegalArgumentException("sourceCacheId required");
        }
        if (timeColumn == null || timeColumn.isBlank()) {
            throw new IllegalArgumentException("TIME_AXIS_MISSING");
        }
        if (!U4OperationAdmission.periodCompareEnabled()) {
            return PeriodCompareRunResult.unavailable(U4OperationAdmission.PERIOD_COMPARE_UNAVAILABLE);
        }

        try (TypedTabularStream stream = TypedTabularStream.open(sourceCacheId, null, 0)) {
            List<TimePoint> points = TypedTimeSeries.extract(stream, timeColumn, valueColumn);
            PeriodComparator.Result result = PeriodComparator.compare(
                    points, originalWindow, currentWindow, aggregation);
            boolean scanned = stream.inputsFullyScanned();
            CompletenessStatus completeness = stream.sourceDescriptor() == null
                    || stream.sourceDescriptor().completenessStatus() == null
                            ? CompletenessStatus.UNKNOWN
                            : stream.sourceDescriptor().completenessStatus();
            if (!scanned) {
                completeness = CompletenessStatus.UNKNOWN;
            }
            AnalysisEnvelope envelope = envelope(sourceCacheId, result, completeness, scanned, points.size());
            return PeriodCompareRunResult.ok(result, envelope);
        }
    }

    private static AnalysisEnvelope envelope(
            String sourceCacheId,
            PeriodComparator.Result result,
            CompletenessStatus completeness,
            boolean inputsFullyScanned,
            long rowsRead) {
        CompletenessStatus c = completeness == null ? CompletenessStatus.UNKNOWN : completeness;
        EvidenceStatus status = (c != CompletenessStatus.COMPLETE || !inputsFullyScanned)
                ? EvidenceStatus.INSUFFICIENT_EVIDENCE
                : EvidenceStatus.SUCCESS;
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("original", stringify(result.original()));
        metrics.put("current", stringify(result.current()));
        metrics.put("delta", stringify(result.delta()));
        metrics.put("percent", result.percentUndefined() ? "undefined" : stringify(result.percent()));
        metrics.put("unequalOrPartial", Boolean.toString(result.unequalOrPartial()));
        metrics.put("originalSupport", Integer.toString(result.originalSupport()));
        metrics.put("currentSupport", Integer.toString(result.currentSupport()));
        AnalysisEnvelopeBuilder b = AnalysisEnvelopeBuilder.create()
                .status(status)
                .operation(AnalysisOperation.PERIOD_COMPARE)
                .addSourceCacheId(sourceCacheId)
                .method(AnalysisMethodDescriptor.builder()
                        .id(ROUTE_ID)
                        .version("1")
                        .profileDigest(PROFILE_DIGEST)
                        .operation(AnalysisOperation.PERIOD_COMPARE)
                        .build())
                .completeness(c)
                .n(2)
                .inputsFullyScanned(inputsFullyScanned)
                .rowsRead(rowsRead)
                .rowsOutput(0L)
                .metrics(metrics);
        if (result.percentUndefined()) {
            b.addWarning("percent_undefined");
        }
        if (result.unequalOrPartial()) {
            b.addWarning("unequal_or_partial_windows");
        }
        return b.build();
    }

    private static String stringify(Double v) {
        return v == null ? "null" : Double.toString(v);
    }

    /** Outcome of a cache-backed period compare (never publishes). */
    public static final class PeriodCompareRunResult {
        private final PeriodComparator.Result result;
        private final AnalysisEnvelope envelope;
        private final String unavailableReason;

        private PeriodCompareRunResult(PeriodComparator.Result result, AnalysisEnvelope envelope,
                String unavailableReason) {
            this.result = result;
            this.envelope = envelope;
            this.unavailableReason = unavailableReason;
        }

        static PeriodCompareRunResult ok(PeriodComparator.Result result, AnalysisEnvelope envelope) {
            return new PeriodCompareRunResult(result, envelope, null);
        }

        static PeriodCompareRunResult unavailable(String reason) {
            return new PeriodCompareRunResult(null, null, reason);
        }

        public boolean unavailable() {
            return unavailableReason != null;
        }

        public String unavailableReason() {
            return unavailableReason;
        }

        public PeriodComparator.Result result() {
            return result;
        }

        public AnalysisEnvelope envelope() {
            return envelope;
        }

        public boolean mayPublish() {
            return false;
        }
    }
}
