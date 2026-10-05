package com.thingworx.things.agent.transform.time;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import org.joda.time.DateTime;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.analysis.AnalysisBudgetAccounting;
import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeBuilder;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeValidator;
import com.thingworx.things.agent.analysis.AnalysisMethodDescriptor;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.ComputingOperationAdmission;
import com.thingworx.things.agent.analysis.DerivedArtifactLineage;
import com.thingworx.things.agent.analysis.DerivedTabularPublisher;
import com.thingworx.things.agent.analysis.TimeAxisNormalizer;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.ReadLimitFact;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.transform.time.CalendarBucketLabeler.Bucket;
import com.thingworx.things.agent.transform.time.CalendarBucketLabeler.Granularity;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * CF-03 ArtifactCache-backed runner for {@code tabulate_cached_result} {@code mode=calendar_bucket}. It labels
 * rows and aggregates nothing. Source rows come from the ordinary decoded table, not from the typed stream,
 * which reads LOCATION, TAGS and nested INFOTABLE cells as null: every source cell in the output is the cell an
 * ordinary cache read returns.
 */
public final class CalendarBucketCacheRunner {

    public static final String ROUTE_ID = "ce.calendar_bucket";
    public static final String PROFILE_DIGEST = "ce-demo-calendar-bucket-v1";
    public static final int MAX_ROWS = MeasurementSeriesReader.MAX_OBSERVATIONS;
    public static final String INPUT_TOO_LARGE = MeasurementSeriesReader.INPUT_TOO_LARGE;
    public static final String COLUMN_NOT_FOUND = MeasurementSeriesReader.COLUMN_NOT_FOUND;
    public static final String TIMESTAMP_UNSUPPORTED = MeasurementSeriesReader.TIMESTAMP_UNSUPPORTED;
    public static final String COLUMN_NAME_CONFLICT = "COLUMN_NAME_CONFLICT";
    public static final String CACHE_MISS = "CACHE_MISS";
    public static final String TIME_BUDGET_EXCEEDED = OperationGuard.TIME_BUDGET_EXCEEDED;
    public static final String CANCELLED = OperationGuard.CANCELLED;

    public static final String OUTCOME_OK = "OK";
    public static final String OUTCOME_NO_ROWS = "NO_ROWS";
    public static final String OUTCOME_NO_ASSIGNED_ROW = "NO_ASSIGNED_ROW";

    public static final List<String> BUCKET_COLUMNS =
            List.of("bucketStart", "bucketEnd", "bucketLabel", "bucketSeconds");

    /** The abort check runs once per 1,024 rows. */
    private static final int ABORT_CHECK_MASK = 0x3FF;
    private static final long STANDARD_DAY_SECONDS = 86_400L;

    private CalendarBucketCacheRunner() {}

    public static MeasurementRunResult run(String sourceCacheId, String timeColumn, ZoneId zone,
            Granularity granularity) throws Exception {
        return run(sourceCacheId, timeColumn, zone, granularity, System::nanoTime);
    }

    /** Test seam: {@code nanoClock} replaces the wall clock of the operation deadline. */
    static MeasurementRunResult run(String sourceCacheId, String timeColumn, ZoneId zone, Granularity granularity,
            LongSupplier nanoClock) throws Exception {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(granularity, "granularity");
        if (!ComputingOperationAdmission.calendarBucketEnabled()) {
            return MeasurementRunResult.unavailable(ComputingOperationAdmission.CALENDAR_BUCKET_UNAVAILABLE);
        }
        OperationGuard guard = OperationGuard.forCurrentInvocation(nanoClock);
        guard.check();
        InfoTable source = TabularArtifactHub.lookup(sourceCacheId);
        if (source == null) {
            throw new MeasurementException(CACHE_MISS, "no cached table for cacheId " + sourceCacheId);
        }
        SourceDescriptor parent = TabularArtifactHub.lookupDescriptor(sourceCacheId);
        guard.check();
        if (source.getRowCount() > MAX_ROWS) {
            throw new MeasurementException(INPUT_TOO_LARGE, "more than " + MAX_ROWS + " rows in the cached table");
        }
        List<FieldDefinition> fields = source.getDataShape().getFields().getOrderedFieldsByOrdinal();
        BaseTypes timeType = null;
        for (FieldDefinition f : fields) {
            if (BUCKET_COLUMNS.contains(f.getName())) {
                throw new MeasurementException(COLUMN_NAME_CONFLICT,
                        "the cached table already has a column named " + f.getName());
            }
            if (f.getName().equals(timeColumn)) {
                timeType = f.getBaseType();
            }
        }
        if (timeType == null && !hasField(fields, timeColumn)) {
            throw new MeasurementException(COLUMN_NOT_FOUND, "column not found in the cached table: " + timeColumn);
        }

        InfoTable out = new InfoTable(outputShape(fields));
        Tally tally = new Tally();
        Map<Long, Bucket> bySecond = new HashMap<>();
        int n = source.getRowCount();
        for (int i = 0; i < n; i++) {
            if ((i & ABORT_CHECK_MASK) == 0) {
                guard.check();
            }
            ValueCollection srcRow = source.getRow(i);
            ValueCollection row = new ValueCollection();
            for (FieldDefinition f : fields) {
                if (srcRow != null && srcRow.containsKey(f.getName())) {
                    row.put(f.getName(), srcRow.getPrimitive(f.getName()));
                }
            }
            Instant instant = srcRow == null ? null : instantOf(srcRow.getPrimitive(timeColumn), timeType);
            if (instant == null) {
                tally.unassigned++;
            } else {
                Bucket bucket;
                try {
                    bucket = CalendarBucketLabeler.bucketOf(zone, instant, granularity);
                } catch (DateTimeException e) {
                    // Instant accepts years beyond LocalDateTime's range. Keep those range failures in the
                    // same typed refusal as unsupported published edges, without rounding source instants.
                    throw new MeasurementException(TIMESTAMP_UNSUPPORTED,
                            "calendar bucket edges must have a year between 0001 and 9999: " + instant);
                }
                MeasurementSeriesReader.requireSupportedInstant(bucket.start());
                MeasurementSeriesReader.requireSupportedInstant(bucket.end());
                row.put("bucketStart", new DatetimePrimitive(new DateTime(bucket.start().toEpochMilli())));
                row.put("bucketEnd", new DatetimePrimitive(new DateTime(bucket.end().toEpochMilli())));
                row.put("bucketLabel", new StringPrimitive(bucket.label()));
                row.put("bucketSeconds", new NumberPrimitive((double) bucket.seconds()));
                tally.add(bucket, granularity);
            }
            out.addRow(row);
        }

        // Rows are never dropped, so there is a table whenever the source has rows.
        String findingId = null;
        SourceDescriptor described = parent;
        if (n > 0) {
            guard.check();
            described = DerivedArtifactLineage.forTransform(parent, sourceCacheId, out, ROUTE_ID, true, true);
            try {
                findingId = DerivedTabularPublisher.publish(out, described, guard);
            } catch (MeasurementException e) {
                throw e;
            } catch (Exception e) {
                // A writer stopped by the shortened I/O deadline is this operation running out of time.
                guard.check();
                throw e;
            }
        } else {
            guard.check();
        }
        return new MeasurementRunResult(findingId,
                envelope(sourceCacheId, findingId, parent, guard, n, tally, zone, granularity), described, null);
    }

    private static boolean hasField(List<FieldDefinition> fields, String name) {
        for (FieldDefinition f : fields) {
            if (f.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Same rules as the typed readers: DATETIME, epoch-millisecond number, ISO-8601 text. */
    private static Instant instantOf(IPrimitiveType<?, ?> primitive, BaseTypes baseType) {
        Object v = primitive == null ? null : primitive.getValue();
        if (v == null) {
            return null;
        }
        TypedCell cell;
        if (v instanceof DateTime) {
            cell = TypedCell.ofDatetime(Instant.ofEpochMilli(((DateTime) v).getMillis()));
        } else if (v instanceof Number) {
            double epochMillis = ((Number) v).doubleValue();
            // A cast turns NaN into 0 and would invent 1970-01-01: a non-finite number is no usable time.
            if (!Double.isFinite(epochMillis)) {
                return null;
            }
            cell = TypedCell.ofNumber(epochMillis);
        } else {
            cell = TypedCell.ofString(String.valueOf(v));
        }
        return TimeAxisNormalizer.toUtcInstant(cell, baseType);
    }

    private static DataShapeDefinition outputShape(List<FieldDefinition> fields) {
        DataShapeDefinition shape = new DataShapeDefinition();
        int ordinal = 0;
        for (FieldDefinition f : fields) {
            FieldDefinition copy = new FieldDefinition();
            copy.setName(f.getName());
            copy.setBaseType(f.getBaseType());
            copy.setOrdinal(ordinal++);
            // A nested INFOTABLE column keeps its local shape, which the cache needs to admit the table.
            if (f.getLocalDataShape() != null) {
                copy.setLocalDataShape(f.getLocalDataShape());
            }
            shape.addFieldDefinition(copy);
        }
        BaseTypes[] types = {BaseTypes.DATETIME, BaseTypes.DATETIME, BaseTypes.STRING, BaseTypes.NUMBER};
        for (int i = 0; i < BUCKET_COLUMNS.size(); i++) {
            FieldDefinition added = new FieldDefinition();
            added.setName(BUCKET_COLUMNS.get(i));
            added.setBaseType(types[i]);
            added.setOrdinal(ordinal++);
            shape.addFieldDefinition(added);
        }
        return shape;
    }

    /** Buckets are counted by interval start; a recurring local date has one label and several intervals. */
    private static final class Tally {
        final Map<Long, String> labelByStart = new HashMap<>();
        final Map<String, Integer> intervalsByLabel = new HashMap<>();
        final Set<Long> nonStandardDays = new HashSet<>();
        long assigned;
        long unassigned;
        Instant firstStart;
        Instant lastEnd;

        void add(Bucket b, Granularity granularity) {
            assigned++;
            long key = b.start().toEpochMilli();
            if (labelByStart.putIfAbsent(key, b.label()) == null) {
                intervalsByLabel.merge(b.label(), 1, Integer::sum);
                if (granularity == Granularity.DAY && b.seconds() != STANDARD_DAY_SECONDS) {
                    nonStandardDays.add(key);
                }
            }
            if (firstStart == null || b.start().isBefore(firstStart)) {
                firstStart = b.start();
            }
            if (lastEnd == null || b.end().isAfter(lastEnd)) {
                lastEnd = b.end();
            }
        }

        long recurringLabels() {
            return intervalsByLabel.values().stream().filter(c -> c > 1).count();
        }
    }

    private static boolean limited(SourceDescriptor parent) {
        return parent != null && (parent.completenessStatus() == CompletenessStatus.PARTIAL
                || parent.completenessReasons().contains(ReadLimitFact.REASON));
    }

    private static AnalysisEnvelope envelope(String sourceCacheId, String findingId, SourceDescriptor parent,
            OperationGuard guard, int rows, Tally tally, ZoneId zone, Granularity granularity) {
        String outcome = tally.assigned > 0 ? OUTCOME_OK : rows == 0 ? OUTCOME_NO_ROWS : OUTCOME_NO_ASSIGNED_ROW;
        CompletenessStatus completeness = parent == null || parent.completenessStatus() == null
                ? CompletenessStatus.UNKNOWN
                : parent.completenessStatus();
        AnalysisEnvelopeBuilder b = AnalysisEnvelopeBuilder.create()
                .status(tally.assigned > 0 ? EvidenceStatus.SUCCESS : EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .operation(AnalysisOperation.CALENDAR_BUCKET)
                .addSourceCacheId(sourceCacheId)
                .findingCacheId(findingId)
                .method(AnalysisMethodDescriptor.builder()
                        .id(CalendarBucketLabeler.METHOD_ID)
                        .version("1")
                        .profileDigest(PROFILE_DIGEST)
                        .operation(AnalysisOperation.CALENDAR_BUCKET)
                        .build())
                .completeness(completeness)
                .n((int) tally.assigned)
                .inputsFullyScanned(true)
                .rowsRead(rows)
                .rowsOutput(rows)
                .budget(AnalysisBudgetAccounting.builder()
                        .requested(guard.budget())
                        .effective(guard.budget())
                        .consumedRows(rows)
                        .consumedBytes(0L)
                        .consumedWallTimeMillis(guard.elapsedMillis())
                        .build())
                .metrics(metrics(outcome, rows, tally, zone, granularity));
        for (String warning : warnings(tally, zone, granularity, limited(parent))) {
            b.addWarning(warning);
        }
        return b.build();
    }

    /** At most three entries in a fixed order; the first is the unconditional scope disclosure. */
    static List<String> warnings(Tally tally, ZoneId zone, Granularity granularity, boolean limited) {
        List<String> out = new ArrayList<>();
        out.add(AnalysisEnvelopeValidator.SCOPE_OBSERVED_SPAN + ": a bucket label describes the instant in the "
                + "row's own time column only. A bucket with rows is not thereby fully observed; a bucket without "
                + "rows is absent, not 0."
                + (limited ? " The source reached a read limit or is partial, so counts and sums per bucket are "
                        + "not that " + granularity.wireName() + "'s total." : ""));
        if (tally.unassigned > 0) {
            out.add(tally.unassigned + " row(s) have no usable time and carry no bucket; they are kept.");
        }
        StringBuilder echo = new StringBuilder("Buckets are local " + granularity.wireName() + "s in "
                + zone.getId() + ".");
        if (!tally.nonStandardDays.isEmpty()) {
            echo.append(' ').append(tally.nonStandardDays.size())
                    .append(" day bucket(s) are not 86400 s long (clock change).");
        }
        if (tally.recurringLabels() > 0) {
            echo.append(' ').append(tally.recurringLabels()).append(" label(s) cover more than one interval; one "
                    + "row's bucketSeconds is not the label's total length.");
        }
        out.add(echo.toString());
        return out;
    }

    private static Map<String, String> metrics(String outcome, int rows, Tally tally, ZoneId zone,
            Granularity granularity) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("method", CalendarBucketLabeler.METHOD_ID);
        m.put("outcome", outcome);
        m.put("scope", "observed_span");
        m.put("timeZone", zone.getId());
        m.put("calendarBucket", granularity.wireName());
        m.put("rows", Integer.toString(rows));
        m.put("assignedRows", Long.toString(tally.assigned));
        m.put("unassignedRows", Long.toString(tally.unassigned));
        m.put("distinctBuckets", Integer.toString(tally.labelByStart.size()));
        m.put("distinctLabels", Integer.toString(tally.intervalsByLabel.size()));
        m.put("recurringLabels", Long.toString(tally.recurringLabels()));
        if (tally.firstStart != null) {
            m.put("firstBucketStart", tally.firstStart.toString());
            m.put("lastBucketEnd", tally.lastEnd.toString());
        }
        if (granularity == Granularity.DAY) {
            m.put("nonStandardDayBuckets", Integer.toString(tally.nonStandardDays.size()));
        }
        m.put("tzdbVersion", tzdbVersion(zone));
        // The ordinary cache read does not meter bytes; consumed bytes are not a 0-byte claim.
        m.put("bytesMetered", "false");
        return m;
    }

    private static String tzdbVersion(ZoneId zone) {
        try {
            return ZoneRulesProvider.getVersions(zone.getId()).lastKey();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
