package com.thingworx.things.agent.transform.time;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
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
import com.thingworx.things.agent.transform.time.CounterDelta.Classification;
import com.thingworx.things.agent.transform.time.MeasurementSeriesReader.Exclusions;
import com.thingworx.things.agent.transform.time.MeasurementSeriesReader.Observation;
import com.thingworx.things.agent.transform.time.MeasurementSeriesReader.Series;
import com.thingworx.types.BaseTypes;

/** CF-05 ArtifactCache-backed runner for {@code tabulate_cached_result} {@code mode=counter_delta}. */
public final class CounterDeltaCacheRunner {

    public static final String ROUTE_ID = "ce.counter_delta";
    public static final String PROFILE_DIGEST = "ce-demo-counter-delta-v1";
    public static final int MAX_READINGS = MeasurementSeriesReader.MAX_OBSERVATIONS;
    public static final String INPUT_TOO_LARGE = MeasurementSeriesReader.INPUT_TOO_LARGE;
    public static final String COLUMN_NOT_FOUND = MeasurementSeriesReader.COLUMN_NOT_FOUND;
    public static final String TIMESTAMP_UNSUPPORTED = MeasurementSeriesReader.TIMESTAMP_UNSUPPORTED;
    public static final String TIME_BUDGET_EXCEEDED = OperationGuard.TIME_BUDGET_EXCEEDED;
    public static final String CANCELLED = OperationGuard.CANCELLED;

    public static final String OUTCOME_OK = "OK";
    public static final String OUTCOME_NO_READINGS = "NO_READINGS";
    public static final String OUTCOME_NO_KNOWN_SEGMENT = "NO_KNOWN_SEGMENT";

    static final List<TypedColumn> OUT_COLUMNS = List.of(
            new TypedColumn("entity", BaseTypes.STRING),
            new TypedColumn("segmentStart", BaseTypes.DATETIME),
            new TypedColumn("segmentEnd", BaseTypes.DATETIME),
            new TypedColumn("startReading", BaseTypes.NUMBER),
            new TypedColumn("endReading", BaseTypes.NUMBER),
            new TypedColumn("delta", BaseTypes.NUMBER),
            new TypedColumn("classification", BaseTypes.STRING),
            new TypedColumn("lowerBound", BaseTypes.BOOLEAN),
            new TypedColumn("elapsedSeconds", BaseTypes.NUMBER));

    private CounterDeltaCacheRunner() {}

    public static MeasurementRunResult run(String sourceCacheId, String timeColumn, String valueColumn,
            String entityColumn, HalfOpenWindow window, CounterDelta.Rules rules) throws Exception {
        return run(sourceCacheId, timeColumn, valueColumn, entityColumn, window, rules, System::nanoTime);
    }

    /** Test seam: {@code nanoClock} replaces the wall clock of the operation deadline. */
    static MeasurementRunResult run(String sourceCacheId, String timeColumn, String valueColumn,
            String entityColumn, HalfOpenWindow window, CounterDelta.Rules rules, LongSupplier nanoClock)
            throws Exception {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(rules, "rules");
        if (!ComputingOperationAdmission.counterDeltaEnabled()) {
            return MeasurementRunResult.unavailable(ComputingOperationAdmission.COUNTER_DELTA_UNAVAILABLE);
        }
        OperationGuard guard = OperationGuard.forCurrentInvocation(nanoClock);
        guard.check();
        // A counter reading without a usable value is no reading: such rows are excluded, not kept.
        Series series = MeasurementSeriesReader.read(
                sourceCacheId, timeColumn, valueColumn, entityColumn, window, false, guard);
        List<CounterDelta.Reading> readings = new ArrayList<>(series.observations.size());
        for (Observation o : series.observations) {
            readings.add(new CounterDelta.Reading(o.entity, o.instant, o.sourceOrdinal, o.value));
        }
        CounterDelta.Result result = CounterDelta.compute(readings, rules, guard::check);

        String findingId = null;
        SourceDescriptor described = series.parent;
        if (!result.segments().isEmpty()) {
            MeasurementSeriesReader.Published published = MeasurementSeriesReader.publish(
                    OUT_COLUMNS, rows(result.segments()), series, sourceCacheId, ROUTE_ID, guard);
            findingId = published.cacheId;
            described = published.descriptor;
        } else {
            // No table to publish: the operation still has to finish inside its budget.
            guard.check();
        }
        AnalysisEnvelope envelope = envelope(sourceCacheId, findingId, series, guard, readings.size(), result,
                rules, window);
        return new MeasurementRunResult(findingId, envelope, described, null);
    }

    private static AnalysisEnvelope envelope(String sourceCacheId, String findingId, Series series,
            OperationGuard guard, int readingCount, CounterDelta.Result result, CounterDelta.Rules rules,
            HalfOpenWindow window) {
        int withDelta = result.segmentsWithDelta();
        String outcome = withDelta > 0 ? OUTCOME_OK
                : readingCount == 0 ? OUTCOME_NO_READINGS : OUTCOME_NO_KNOWN_SEGMENT;

        AnalysisEnvelopeBuilder b = AnalysisEnvelopeBuilder.create()
                .status(withDelta > 0 ? EvidenceStatus.SUCCESS : EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .operation(AnalysisOperation.COUNTER_DELTA)
                .addSourceCacheId(sourceCacheId)
                .findingCacheId(findingId)
                .method(AnalysisMethodDescriptor.builder()
                        .id(CounterDelta.METHOD_ID)
                        .version("1")
                        .profileDigest(PROFILE_DIGEST)
                        .operation(AnalysisOperation.COUNTER_DELTA)
                        .build())
                .completeness(series.completeness())
                .n(withDelta)
                .inputsFullyScanned(series.inputsFullyScanned)
                .rowsRead(series.rowsRead)
                .rowsOutput(result.segments().size())
                .budget(series.accounting(guard))
                .metrics(metrics(result, series.exclusions, rules, window, readingCount, outcome));
        for (String warning : warnings(result, series.exclusions, rules, series.limited())) {
            b.addWarning(warning);
        }
        return b.build();
    }

    /**
     * At most four entries in a fixed order. The first is unconditional: it is the scope disclosure the
     * envelope validator requires, and it is a time-span statement only. The reset limitation of a
     * request without a rate bound is a different disclosure and must not hide behind it.
     */
    static List<String> warnings(CounterDelta.Result result, Exclusions excluded, CounterDelta.Rules rules,
            boolean limited) {
        List<String> out = new ArrayList<>();
        out.add(AnalysisEnvelopeValidator.SCOPE_OBSERVED_SPAN
                + ": increments are computed only between readings that were read; totals cover the observed span."
                + (limited ? " The source reached a read limit or is partial, so this is not a window or shift total."
                        : ""));
        if (result.segmentsWithoutDelta() > 0) {
            StringJoiner byClass = new StringJoiner(", ");
            for (Classification c : Classification.values()) {
                if (!c.carriesDelta() && result.count(c) > 0) {
                    byClass.add(c.name() + "=" + result.count(c));
                }
            }
            out.add(result.segmentsWithoutDelta() + " segment(s) carry no increment: " + byClass + ".");
        }
        boolean noRate = rules.maxRatePerSecond() == null;
        if (result.lowerBoundSegments() > 0 || noRate) {
            StringBuilder w = new StringBuilder();
            if (result.lowerBoundSegments() > 0) {
                w.append(result.lowerBoundSegments()).append(" segment(s) are lower bounds and are summed in "
                        + "lowerBoundDelta only, never in knownDelta.");
            }
            if (noRate) {
                w.append(w.length() > 0 ? " " : "").append("No maxRatePerSecond was given, so a reset between two "
                        + "readings cannot be ruled out.");
            }
            out.add(w.toString());
        }
        if (excluded.total() > 0 || result.conflictInstants() > 0) {
            out.add("Excluded rows: missingTimestamp=" + excluded.missingTimestamp + ", missingValue="
                    + excluded.missingValue + ", missingEntity=" + excluded.missingEntity
                    + "; conflicting instants=" + result.conflictInstants() + ".");
        }
        return out;
    }

    private static Map<String, String> metrics(CounterDelta.Result result, Exclusions excluded,
            CounterDelta.Rules rules, HalfOpenWindow window, int readingCount, String outcome) {
        boolean exact = result.arithmetic() == CounterDelta.Arithmetic.EXACT_INTEGER;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("method", CounterDelta.METHOD_ID);
        m.put("outcome", outcome);
        m.put("scope", "observed_span");
        m.put("arithmetic", result.arithmetic().wireName());
        m.put("knownDelta", number(result.knownDelta(), exact));
        m.put("lowerBoundDelta", number(result.lowerBoundDelta(), exact));
        m.put("assumptions", assumptions(rules));
        m.put("partitions", Integer.toString(result.partitions()));
        m.put("readings", Integer.toString(readingCount));
        for (Classification c : Classification.values()) {
            if (result.count(c) > 0) {
                m.put("segments." + c.name(), Integer.toString(result.count(c)));
            }
        }
        m.put("rowsMissingTimestamp", Long.toString(excluded.missingTimestamp));
        m.put("rowsMissingValue", Long.toString(excluded.missingValue));
        m.put("rowsMissingEntity", Long.toString(excluded.missingEntity));
        m.put("duplicateRowsCollapsed", Long.toString(result.duplicateRowsCollapsed()));
        m.put("conflictInstants", Long.toString(result.conflictInstants()));
        // One partition only: the earliest/latest time of a multi-entity table says nothing about
        // any single entity (design §3.2.2 item 4).
        if (result.firstReading() != null) {
            m.put("firstReading", result.firstReading().toString());
            m.put("lastReading", result.lastReading().toString());
            m.put("uncoveredHeadSeconds", Long.toString(
                    Duration.between(window.startInclusive(), result.firstReading()).getSeconds()));
            m.put("uncoveredTailSeconds", Long.toString(
                    Duration.between(result.lastReading(), window.endExclusive()).getSeconds()));
        }
        return m;
    }

    static String assumptions(CounterDelta.Rules rules) {
        StringJoiner j = new StringJoiner("; ");
        if (rules.counterModulus() != null) {
            j.add("counterModulus=" + number(rules.counterModulus(), false));
        }
        if (rules.maxRatePerSecond() != null) {
            j.add("maxRatePerSecond=" + number(rules.maxRatePerSecond(), false));
        }
        if (rules.maxGapSeconds() != null) {
            j.add("maxGapSeconds=" + rules.maxGapSeconds());
        }
        if (rules.resetBaseline() != null) {
            j.add("resetBaseline=" + number(rules.resetBaseline(), false));
        }
        return j.length() == 0 ? "none" : j.toString();
    }

    private static String number(double v, boolean exact) {
        return exact ? new java.math.BigDecimal(v).toPlainString() : MeasurementSeriesReader.plainNumber(v);
    }

    private static List<TypedRow> rows(List<CounterDelta.Segment> segments) {
        List<TypedRow> rows = new ArrayList<>(segments.size());
        long ord = 0L;
        for (CounterDelta.Segment s : segments) {
            rows.add(new TypedRow(ord++, List.of(
                    TypedCell.ofString(s.entity()),
                    TypedCell.ofDatetime(s.start()),
                    TypedCell.ofDatetime(s.end()),
                    numberOrNull(s.startReading()),
                    numberOrNull(s.endReading()),
                    numberOrNull(s.delta()),
                    TypedCell.ofString(s.classification().name()),
                    TypedCell.ofBoolean(s.lowerBound()),
                    TypedCell.ofNumber(s.elapsedSeconds()))));
        }
        return rows;
    }

    private static TypedCell numberOrNull(Double v) {
        return v == null ? TypedCell.ofNull() : TypedCell.ofNumber(v);
    }
}
