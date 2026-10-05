package com.thingworx.things.agent.transform.time;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeBuilder;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeValidator;
import com.thingworx.things.agent.analysis.AnalysisMethodDescriptor;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.ComputingOperationAdmission;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.transform.time.MeasurementSeriesReader.AnchoredSeries;
import com.thingworx.things.agent.transform.time.MeasurementSeriesReader.Exclusions;
import com.thingworx.things.agent.transform.time.MeasurementSeriesReader.Observation;
import com.thingworx.things.agent.transform.time.MeasurementSeriesReader.Series;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral.Coverage;
import com.thingworx.types.BaseTypes;

/** CF-01 ArtifactCache-backed runner for {@code tabulate_cached_result} {@code mode=time_weighted}. */
public final class TimeWeightedCacheRunner {

    public static final String ROUTE_ID = "ce.time_weighted";
    public static final String PROFILE_DIGEST = "ce-demo-time-weighted-v1";
    public static final int MAX_READINGS = MeasurementSeriesReader.MAX_OBSERVATIONS;
    public static final String INPUT_TOO_LARGE = MeasurementSeriesReader.INPUT_TOO_LARGE;
    public static final String COLUMN_NOT_FOUND = MeasurementSeriesReader.COLUMN_NOT_FOUND;
    public static final String TIMESTAMP_UNSUPPORTED = MeasurementSeriesReader.TIMESTAMP_UNSUPPORTED;
    public static final String TIME_BUDGET_EXCEEDED = OperationGuard.TIME_BUDGET_EXCEEDED;
    public static final String CANCELLED = OperationGuard.CANCELLED;
    public static final String SOURCE_CONTINUITY_UNKNOWN = "SOURCE_CONTINUITY_UNKNOWN";

    public static final String OUTCOME_OK = "OK";
    public static final String OUTCOME_NO_READINGS = "NO_READINGS";
    public static final String OUTCOME_NO_COVERED_SEGMENT = "NO_COVERED_SEGMENT";

    static final List<TypedColumn> OUT_COLUMNS = List.of(
            new TypedColumn("segmentStart", BaseTypes.DATETIME),
            new TypedColumn("segmentEnd", BaseTypes.DATETIME),
            new TypedColumn("startValue", BaseTypes.NUMBER),
            new TypedColumn("endValue", BaseTypes.NUMBER),
            new TypedColumn("seconds", BaseTypes.NUMBER),
            new TypedColumn("supportedDuration", BaseTypes.NUMBER),
            new TypedColumn("integral", BaseTypes.NUMBER),
            new TypedColumn("coverage", BaseTypes.STRING));

    private TimeWeightedCacheRunner() {}

    public static MeasurementRunResult run(String sourceCacheId, String timeColumn, String valueColumn,
            HalfOpenWindow window, TimeWeightedIntegral.Config config) throws Exception {
        return run(sourceCacheId, timeColumn, valueColumn, window, config, System::nanoTime);
    }

    /** Test seam: {@code nanoClock} replaces the wall clock of the operation deadline. */
    static MeasurementRunResult run(String sourceCacheId, String timeColumn, String valueColumn,
            HalfOpenWindow window, TimeWeightedIntegral.Config config, LongSupplier nanoClock) throws Exception {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(config, "config");
        if (!ComputingOperationAdmission.timeWeightedEnabled()) {
            return MeasurementRunResult.unavailable(ComputingOperationAdmission.TIME_WEIGHTED_UNAVAILABLE);
        }
        // Segments are clipped to the window and its edges are published as DATETIME cells.
        MeasurementSeriesReader.requireSupportedInstant(window.startInclusive());
        MeasurementSeriesReader.requireSupportedInstant(window.endExclusive());
        OperationGuard guard = OperationGuard.forCurrentInvocation(nanoClock);
        guard.check();
        AnchoredSeries anchored = MeasurementSeriesReader.readWithAnchors(sourceCacheId, timeColumn, valueColumn,
                window, parent -> requireSingleSeriesSource(parent, timeColumn, valueColumn), guard);
        Series series = anchored.series;
        List<TimeWeightedIntegral.Reading> readings = new ArrayList<>(series.observations.size());
        for (Observation o : series.observations) {
            readings.add(new TimeWeightedIntegral.Reading(o.instant, o.sourceOrdinal, o.value));
        }
        TimeWeightedIntegral.Result result =
                TimeWeightedIntegral.compute(readings, window, config, !series.limited(), guard::check);

        // The segments always tile the window, so there is always a table, all-UNKNOWN included.
        MeasurementSeriesReader.Published published = MeasurementSeriesReader.publish(
                OUT_COLUMNS, rows(result.segments()), series, sourceCacheId, ROUTE_ID, guard);
        AnalysisEnvelope envelope = envelope(sourceCacheId, published.cacheId, series, guard, result, config,
                result.duplicateRowsCollapsed() + anchored.anchorDuplicatesCollapsed);
        return new MeasurementRunResult(published.cacheId, envelope, published.descriptor, null);
    }

    /**
     * This is the first method that integrates across intervals, and {@code maxGapSeconds} cannot prove that
     * two readings belong to one continuous series. Admission therefore rests on what the producer declared:
     * the subject identity, which only a numeric property-history read of one Thing property sets and which
     * no derivation carries over. An empty parent list alone is no evidence.
     */
    static void requireSingleSeriesSource(SourceDescriptor source, String timeColumn, String valueColumn) {
        boolean admitted = source != null
                && source.subjectThingName() != null
                && source.subjectPropertyName() != null
                && source.parentSourceCacheIds().isEmpty()
                && timeColumn.equals(source.timeColumn())
                && valueColumn.equals(source.valueColumn());
        if (!admitted) {
            throw new MeasurementException(SOURCE_CONTINUITY_UNKNOWN,
                    "time_weighted integrates one property-history series only: read it with the numeric "
                            + "property history tool and pass that cacheId with its own time and value columns");
        }
    }

    private static AnalysisEnvelope envelope(String sourceCacheId, String findingId, Series series,
            OperationGuard guard, TimeWeightedIntegral.Result result, TimeWeightedIntegral.Config config,
            long duplicates) {
        boolean covered = result.coveredMillis() > 0L;
        String outcome = covered ? OUTCOME_OK
                : result.hasEvidence() ? OUTCOME_NO_COVERED_SEGMENT : OUTCOME_NO_READINGS;
        int coveredSegments = result.segments().size() - result.unknownSegments();
        AnalysisEnvelopeBuilder b = AnalysisEnvelopeBuilder.create()
                .status(covered ? EvidenceStatus.SUCCESS : EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .operation(AnalysisOperation.TIME_WEIGHTED)
                .addSourceCacheId(sourceCacheId)
                .findingCacheId(findingId)
                .method(AnalysisMethodDescriptor.builder()
                        .id(TimeWeightedIntegral.METHOD_ID)
                        .version("1")
                        .profileDigest(PROFILE_DIGEST)
                        .operation(AnalysisOperation.TIME_WEIGHTED)
                        .build())
                .completeness(series.completeness())
                .n(coveredSegments)
                .inputsFullyScanned(series.inputsFullyScanned)
                .rowsRead(series.rowsRead)
                .rowsOutput(result.segments().size())
                .budget(series.accounting(guard))
                .metrics(metrics(result, series.exclusions, config, outcome, duplicates));
        for (String warning : warnings(result, series.exclusions, series.limited(), duplicates)) {
            b.addWarning(warning);
        }
        return b.build();
    }

    /**
     * At most four entries in a fixed order; the first is the unconditional scope disclosure. Whether the
     * result contains an estimate is judged by held time: a held value of 0, or holds that cancel out, give
     * an estimated integral of 0 over a non-zero estimated duration.
     */
    static List<String> warnings(TimeWeightedIntegral.Result result, Exclusions excluded, boolean limited,
            long duplicates) {
        List<String> out = new ArrayList<>();
        out.add(AnalysisEnvelopeValidator.SCOPE_OBSERVED_SPAN + ": the integral covers "
                + seconds(result.coveredMillis()) + " s of the window; " + seconds(result.unknownMillis())
                + " s are unknown and contribute nothing."
                + (limited ? " The source reached a read limit or is partial, so this is not a window or shift total."
                        : ""));
        if (result.unknownMillis() > 0L) {
            out.add(result.unknownSegments() + " unknown segment(s), " + seconds(result.unknownMillis())
                    + " s in total, carry no integral.");
        }
        if (result.containsEstimate() || result.cancelledTailHoldMillis() > 0L) {
            StringBuilder w = new StringBuilder();
            if (result.containsEstimate()) {
                w.append("Contains an estimate: ").append(seconds(result.estimatedMillis()))
                        .append(" s are a last-value hold with no later reading confirming the value; "
                                + "estimatedIntegral=")
                        .append(MeasurementSeriesReader.plainNumber(result.estimatedIntegral())).append('.');
            }
            if (result.cancelledTailHoldMillis() > 0L) {
                w.append(w.length() > 0 ? " " : "").append("The hold after the last reading was cancelled for ")
                        .append(seconds(result.cancelledTailHoldMillis()))
                        .append(" s because the source reached a read limit or is partial: unread data may follow.");
            }
            out.add(w.toString());
        }
        if (excluded.total() > 0 || duplicates > 0 || result.conflictInstants() > 0) {
            out.add("Excluded rows: missingTimestamp=" + excluded.missingTimestamp + ", missingValue="
                    + excluded.missingValue + "; duplicate rows collapsed=" + duplicates
                    + "; conflicting instants (anchors included)=" + result.conflictInstants() + ".");
        }
        return out;
    }

    private static Map<String, String> metrics(TimeWeightedIntegral.Result result, Exclusions excluded,
            TimeWeightedIntegral.Config config, String outcome, long duplicates) {
        boolean covered = result.coveredMillis() > 0L;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("method", TimeWeightedIntegral.METHOD_ID);
        m.put("outcome", outcome);
        m.put("scope", "observed_span");
        m.put("integrationMethod", config.method().wireName());
        m.put("maxGapSeconds", Long.toString(config.maxGapSeconds()));
        m.put("timeUnit", config.unit().wireName());
        m.put("integralUnit", "value × " + config.unit().wireName());
        // No covered time: the integral and the mean are absent, not 0.
        if (covered) {
            m.put("observedIntegral", MeasurementSeriesReader.plainNumber(result.observedIntegral()));
            m.put("estimatedIntegral", MeasurementSeriesReader.plainNumber(result.estimatedIntegral()));
            m.put("integral", MeasurementSeriesReader.plainNumber(result.integral()));
            m.put("timeWeightedMean", MeasurementSeriesReader.plainNumber(result.timeWeightedMean()));
        }
        m.put("observedSeconds", seconds(result.observedMillis()));
        m.put("estimatedSeconds", seconds(result.estimatedMillis()));
        m.put("unknownSeconds", seconds(result.unknownMillis()));
        m.put("windowSeconds", seconds(result.windowMillis()));
        m.put("coverage", MeasurementSeriesReader.plainNumber(
                (double) result.coveredMillis() / result.windowMillis()));
        m.put("containsEstimate", Boolean.toString(result.containsEstimate()));
        m.put("cancelledTailHoldSeconds", seconds(result.cancelledTailHoldMillis()));
        m.put("readings", Integer.toString(result.readingsInWindow()));
        for (Coverage c : Coverage.values()) {
            int n = 0;
            for (TimeWeightedIntegral.Segment s : result.segments()) {
                if (s.coverage() == c) {
                    n++;
                }
            }
            if (n > 0) {
                m.put("segments." + c.name(), Integer.toString(n));
            }
        }
        m.put("rowsMissingTimestamp", Long.toString(excluded.missingTimestamp));
        m.put("rowsMissingValue", Long.toString(excluded.missingValue));
        m.put("duplicateRowsCollapsed", Long.toString(duplicates));
        m.put("conflictInstants", Long.toString(result.conflictInstants()));
        return m;
    }

    /** Exact to the millisecond, so the three durations add up to the window. */
    static String seconds(long millis) {
        BigDecimal s = BigDecimal.valueOf(millis, 3).stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
    }

    private static List<TypedRow> rows(List<TimeWeightedIntegral.Segment> segments) {
        List<TypedRow> rows = new ArrayList<>(segments.size());
        long ord = 0L;
        for (TimeWeightedIntegral.Segment s : segments) {
            rows.add(new TypedRow(ord++, List.of(
                    TypedCell.ofDatetime(s.start()),
                    TypedCell.ofDatetime(s.end()),
                    numberOrNull(s.startValue()),
                    numberOrNull(s.endValue()),
                    TypedCell.ofNumber(s.millis() / 1_000d),
                    numberOrNull(s.supportedDuration()),
                    numberOrNull(s.integral()),
                    TypedCell.ofString(s.coverage().name()))));
        }
        return rows;
    }

    private static TypedCell numberOrNull(Double v) {
        return v == null ? TypedCell.ofNull() : TypedCell.ofNumber(v);
    }
}
