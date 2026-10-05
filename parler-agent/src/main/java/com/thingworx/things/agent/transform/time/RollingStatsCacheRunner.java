package com.thingworx.things.agent.transform.time;

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
import com.thingworx.things.agent.transform.time.MeasurementSeriesReader.Exclusions;
import com.thingworx.things.agent.transform.time.MeasurementSeriesReader.Observation;
import com.thingworx.things.agent.transform.time.MeasurementSeriesReader.Series;
import com.thingworx.things.agent.transform.time.RollingOperator.WindowKind;
import com.thingworx.things.agent.transform.time.RollingStats.ValueStatus;
import com.thingworx.types.BaseTypes;

/** CF-52 ArtifactCache-backed runner for {@code tabulate_cached_result} {@code mode=rolling_stats}. */
public final class RollingStatsCacheRunner {

    public static final String ROUTE_ID = "ce.rolling_stats";
    public static final String PROFILE_DIGEST = "ce-demo-rolling-stats-v1";

    public static final String OUTCOME_OK = "OK";
    public static final String OUTCOME_NO_READINGS = "NO_READINGS";
    public static final String OUTCOME_NO_SUPPORTED_WINDOW = "NO_SUPPORTED_WINDOW";

    static final List<TypedColumn> OUT_COLUMNS = List.of(
            new TypedColumn("entity", BaseTypes.STRING),
            new TypedColumn("timestamp", BaseTypes.DATETIME),
            new TypedColumn("windowStart", BaseTypes.DATETIME),
            new TypedColumn("windowEnd", BaseTypes.DATETIME),
            new TypedColumn("value", BaseTypes.NUMBER),
            new TypedColumn("valueStatus", BaseTypes.STRING),
            new TypedColumn("support", BaseTypes.INTEGER),
            new TypedColumn("records", BaseTypes.INTEGER),
            new TypedColumn("warmedUp", BaseTypes.BOOLEAN));

    private RollingStatsCacheRunner() {}

    public static MeasurementRunResult run(String sourceCacheId, String timeColumn, String valueColumn,
            String entityColumn, HalfOpenWindow window, RollingStats.Config config) throws Exception {
        return run(sourceCacheId, timeColumn, valueColumn, entityColumn, window, config, System::nanoTime);
    }

    /** Test seam: {@code nanoClock} replaces the wall clock of the operation deadline. */
    static MeasurementRunResult run(String sourceCacheId, String timeColumn, String valueColumn,
            String entityColumn, HalfOpenWindow window, RollingStats.Config config, LongSupplier nanoClock)
            throws Exception {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(config, "config");
        if (!ComputingOperationAdmission.rollingStatsEnabled()) {
            return MeasurementRunResult.unavailable(ComputingOperationAdmission.ROLLING_STATS_UNAVAILABLE);
        }
        OperationGuard guard = OperationGuard.forCurrentInvocation(nanoClock);
        guard.check();
        // A row without a usable value is still a record of the window: it is kept, with a null value.
        Series series = MeasurementSeriesReader.read(
                sourceCacheId, timeColumn, valueColumn, entityColumn, window, true, guard);
        List<RollingStats.Record> records = new ArrayList<>(series.observations.size());
        for (Observation o : series.observations) {
            records.add(new RollingStats.Record(o.entity, o.instant, o.sourceOrdinal, o.value));
        }
        RollingStats.Result result = RollingStats.compute(records, config, guard::check);

        String findingId = null;
        SourceDescriptor described = series.parent;
        if (!result.rows().isEmpty()) {
            MeasurementSeriesReader.Published published = MeasurementSeriesReader.publish(
                    OUT_COLUMNS, rows(result.rows()), series, sourceCacheId, ROUTE_ID, guard);
            findingId = published.cacheId;
            described = published.descriptor;
        } else {
            guard.check();
        }
        return new MeasurementRunResult(findingId,
                envelope(sourceCacheId, findingId, series, guard, result, config), described, null);
    }

    private static AnalysisEnvelope envelope(String sourceCacheId, String findingId, Series series,
            OperationGuard guard, RollingStats.Result result, RollingStats.Config config) {
        int withValue = result.count(ValueStatus.OK);
        String outcome = withValue > 0 ? OUTCOME_OK
                : result.rows().isEmpty() ? OUTCOME_NO_READINGS : OUTCOME_NO_SUPPORTED_WINDOW;
        AnalysisEnvelopeBuilder b = AnalysisEnvelopeBuilder.create()
                .status(withValue > 0 ? EvidenceStatus.SUCCESS : EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .operation(AnalysisOperation.ROLLING_STATS)
                .addSourceCacheId(sourceCacheId)
                .findingCacheId(findingId)
                .method(AnalysisMethodDescriptor.builder()
                        .id(RollingStats.METHOD_ID)
                        .version("1")
                        .profileDigest(PROFILE_DIGEST)
                        .operation(AnalysisOperation.ROLLING_STATS)
                        .build())
                .completeness(series.completeness())
                .n(withValue)
                .inputsFullyScanned(series.inputsFullyScanned)
                .rowsRead(series.rowsRead)
                .rowsOutput(result.rows().size())
                .budget(series.accounting(guard))
                .metrics(metrics(result, series.exclusions, config, outcome));
        for (String warning : warnings(result, series.exclusions, series.limited())) {
            b.addWarning(warning);
        }
        return b.build();
    }

    /**
     * At most four entries in a fixed order. The first is unconditional and says that window coverage is
     * not established: {@code warmedUp} is a frame-availability marker, never a coverage claim.
     */
    static List<String> warnings(RollingStats.Result result, Exclusions excluded, boolean limited) {
        List<String> out = new ArrayList<>();
        out.add(AnalysisEnvelopeValidator.SCOPE_OBSERVED_SPAN
                + ": windows hold only records that were read; window coverage is not established."
                + (limited ? " The source reached a read limit or is partial, so these are not statistics of a "
                        + "complete window or shift." : ""));
        int belowSupport = result.count(ValueStatus.BELOW_MIN_SUPPORT);
        int belowSample = result.count(ValueStatus.BELOW_MIN_SAMPLE);
        if (belowSupport + belowSample > 0) {
            out.add((belowSupport + belowSample) + " row(s) carry no value: BELOW_MIN_SUPPORT=" + belowSupport
                    + ", BELOW_MIN_SAMPLE=" + belowSample + ".");
        }
        if (result.notWarmedUp() > 0) {
            out.add(result.notWarmedUp() + " row(s) are not warmed up: their window frame starts before the "
                    + "first record, or holds fewer records than requested.");
        }
        // Rows with a missing value stay as records, so they are not an exclusion here.
        long excludedRows = excluded.missingTimestamp + excluded.missingEntity;
        if (excludedRows > 0) {
            out.add("Excluded rows: missingTimestamp=" + excluded.missingTimestamp + ", missingEntity="
                    + excluded.missingEntity + ".");
        }
        return out;
    }

    private static Map<String, String> metrics(RollingStats.Result result, Exclusions excluded,
            RollingStats.Config config, String outcome) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("method", RollingStats.METHOD_ID);
        m.put("outcome", outcome);
        m.put("scope", "observed_span");
        m.put("statistic", config.statistic().wireName());
        m.put("rollingKind", config.kind().name());
        if (config.kind() == WindowKind.OBSERVATION_COUNT) {
            m.put("observationWindow", Integer.toString(config.observationWindow()));
        } else {
            m.put("durationWindowSeconds", Long.toString(config.durationWindow().getSeconds()));
        }
        m.put("minSupport", Integer.toString(config.minSupport()));
        m.put("partitions", Integer.toString(result.partitions()));
        m.put("records", Integer.toString(result.rows().size()));
        m.put("rowsWithValue", Integer.toString(result.count(ValueStatus.OK)));
        m.put("rowsBelowMinSupport", Integer.toString(result.count(ValueStatus.BELOW_MIN_SUPPORT)));
        m.put("rowsBelowMinSample", Integer.toString(result.count(ValueStatus.BELOW_MIN_SAMPLE)));
        m.put("rowsNotWarmedUp", Integer.toString(result.notWarmedUp()));
        m.put("recordsWithoutValue", Long.toString(excluded.missingValue));
        m.put("rowsMissingTimestamp", Long.toString(excluded.missingTimestamp));
        m.put("rowsMissingEntity", Long.toString(excluded.missingEntity));
        m.put("windowWork", Long.toString(result.windowWork()));
        return m;
    }

    private static List<TypedRow> rows(List<RollingStats.Row> rows) {
        List<TypedRow> out = new ArrayList<>(rows.size());
        long ord = 0L;
        for (RollingStats.Row r : rows) {
            out.add(new TypedRow(ord++, List.of(
                    TypedCell.ofString(r.entity()),
                    TypedCell.ofDatetime(r.timestamp()),
                    TypedCell.ofDatetime(r.windowStart()),
                    TypedCell.ofDatetime(r.windowEnd()),
                    r.value() == null ? TypedCell.ofNull() : TypedCell.ofNumber(r.value()),
                    TypedCell.ofString(r.valueStatus().name()),
                    TypedCell.ofNumber(r.support()),
                    TypedCell.ofNumber(r.records()),
                    TypedCell.ofBoolean(r.warmedUp()))));
        }
        return out;
    }
}
