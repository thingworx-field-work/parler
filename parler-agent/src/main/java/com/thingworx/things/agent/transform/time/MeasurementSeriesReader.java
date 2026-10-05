package com.thingworx.things.agent.transform.time;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.thingworx.things.agent.analysis.AnalysisBudgetAccounting;
import com.thingworx.things.agent.analysis.CompletenessPropagation;
import com.thingworx.things.agent.analysis.DerivedArtifactLineage;
import com.thingworx.things.agent.analysis.DerivedTabularPublisher;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.analysis.TimeAxisNormalizer;
import com.thingworx.things.agent.cache.TypedBatch;
import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.things.agent.cache.TypedTabularStream;
import com.thingworx.things.agent.source.ReadLimitFact;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.types.InfoTable;

/**
 * Shared input and output plumbing of the computing-enhancement measurement modes: stream a cached table
 * into observations under one row-disposition policy, and publish a derived table under the operation
 * guard. The row-disposition policy is the caller's choice: {@code counter_delta} and {@code time_weighted}
 * exclude rows without a usable value, {@code rolling_stats} keeps them as records; {@code time_weighted}
 * additionally keeps one anchor group on each side of the window.
 */
final class MeasurementSeriesReader {

    static final int MAX_OBSERVATIONS = 100_000;
    static final String INPUT_TOO_LARGE = "INPUT_TOO_LARGE";
    static final String COLUMN_NOT_FOUND = "COLUMN_NOT_FOUND";
    static final String TIMESTAMP_UNSUPPORTED = "TIMESTAMP_UNSUPPORTED";

    /** Derived tables store DATETIME cells: whole milliseconds, years 0001 to 9999. */
    private static final Instant MIN_INSTANT = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59.999Z");

    private MeasurementSeriesReader() {}

    /** One row inside the analysis window with a usable time and entity; {@code value} may be null. */
    static final class Observation {
        final String entity;
        final Instant instant;
        final long sourceOrdinal;
        final Double value;

        Observation(String entity, Instant instant, long sourceOrdinal, Double value) {
            this.entity = entity;
            this.instant = instant;
            this.sourceOrdinal = sourceOrdinal;
            this.value = value;
        }
    }

    static final class Exclusions {
        long missingTimestamp;
        long missingValue;
        long missingEntity;

        long total() {
            return missingTimestamp + missingValue + missingEntity;
        }
    }

    static final class Series {
        final List<Observation> observations;
        final Exclusions exclusions;
        final long rowsRead;
        final long bytesRead;
        final boolean inputsFullyScanned;
        final SourceDescriptor parent;

        Series(List<Observation> observations, Exclusions exclusions, long rowsRead, long bytesRead,
                boolean inputsFullyScanned, SourceDescriptor parent) {
            this.observations = observations;
            this.exclusions = exclusions;
            this.rowsRead = rowsRead;
            this.bytesRead = bytesRead;
            this.inputsFullyScanned = inputsFullyScanned;
            this.parent = parent;
        }

        /** Parent completeness, never improved by this read. */
        CompletenessStatus completeness() {
            CompletenessStatus c = parent == null || parent.completenessStatus() == null
                    ? CompletenessStatus.UNKNOWN
                    : parent.completenessStatus();
            return inputsFullyScanned ? c : CompletenessPropagation.worse(c, CompletenessStatus.UNKNOWN);
        }

        /** True when the source reached a read limit or is partial: results are not a window total. */
        boolean limited() {
            return completeness() == CompletenessStatus.PARTIAL
                    || (parent != null && parent.completenessReasons().contains(ReadLimitFact.REASON));
        }

        AnalysisBudgetAccounting accounting(OperationGuard guard) {
            return AnalysisBudgetAccounting.builder()
                    .requested(guard.budget())
                    .effective(guard.budget())
                    .consumedRows(rowsRead)
                    .consumedBytes(bytesRead)
                    .consumedWallTimeMillis(guard.elapsedMillis())
                    .build();
        }
    }

    /**
     * @param keepMissingValues false: a row without a finite numeric value is counted and excluded; true: it
     *        stays as an observation with a null value and {@code exclusions.missingValue} still counts it
     */
    static Series read(String sourceCacheId, String timeColumn, String valueColumn, String entityColumn,
            HalfOpenWindow window, boolean keepMissingValues, OperationGuard guard) throws Exception {
        try (TypedTabularStream stream = TypedTabularStream.open(sourceCacheId, null, 0)) {
            int timeIdx = requireColumn(stream.schema(), timeColumn);
            int valueIdx = requireColumn(stream.schema(), valueColumn);
            int entityIdx = entityColumn == null ? -1 : requireColumn(stream.schema(), entityColumn);
            TypedColumn timeCol = stream.schema().get(timeIdx);
            Exclusions excluded = new Exclusions();
            List<Observation> out = new ArrayList<>();
            while (!stream.exhausted()) {
                guard.check();
                TypedBatch batch = stream.readBatch(256);
                for (TypedRow row : batch.rows()) {
                    Instant instant = TimeAxisNormalizer.toUtcInstant(row, timeIdx, timeCol);
                    if (instant == null) {
                        excluded.missingTimestamp++;
                        continue;
                    }
                    if (!window.contains(instant)) {
                        continue;
                    }
                    requireSupportedInstant(instant);
                    TypedCell cell = row.cells().get(valueIdx);
                    Double value = cell != null && cell.kind() == TypedCell.Kind.NUMBER
                            && Double.isFinite(cell.numberValue()) ? cell.numberValue() : null;
                    if (value == null) {
                        excluded.missingValue++;
                        if (!keepMissingValues) {
                            continue;
                        }
                    }
                    String entity = "";
                    if (entityIdx >= 0) {
                        entity = entityText(row.cells().get(entityIdx));
                        if (entity == null) {
                            excluded.missingEntity++;
                            if (value == null) {
                                excluded.missingValue--;
                            }
                            continue;
                        }
                    }
                    if (out.size() >= MAX_OBSERVATIONS) {
                        throw new MeasurementException(INPUT_TOO_LARGE,
                                "more than " + MAX_OBSERVATIONS + " rows inside the window");
                    }
                    out.add(new Observation(entity, instant, row.sourceOrdinal(), value));
                }
            }
            return new Series(out, excluded, stream.rowsRead(), stream.bytesReadFromArtifact(),
                    stream.inputsFullyScanned(), stream.sourceDescriptor());
        }
    }

    /**
     * Single-series read that also keeps, on each side of the window, the nearest <em>timestamp group</em>
     * as an anchor: the latest instant before {@code windowStart} and the earliest instant at or after
     * {@code windowEnd}. A group is kept, not one row: identical duplicates collapse and are counted,
     * and differing values at the anchor instant are returned as two observations, plus the group's
     * largest-magnitude value when it is neither of them, so the consumer sees the conflict and validates the
     * whole group independently of row order, and treats the anchor as a continuity barrier. State per side is bounded. Rows without
     * a usable value are excluded, as for {@code counter_delta}. {@code sourceAdmission} sees the source
     * descriptor before any row is read and refuses the request by throwing.
     */
    static AnchoredSeries readWithAnchors(String sourceCacheId, String timeColumn, String valueColumn,
            HalfOpenWindow window, Consumer<SourceDescriptor> sourceAdmission, OperationGuard guard)
            throws Exception {
        try (TypedTabularStream stream = TypedTabularStream.open(sourceCacheId, null, 0)) {
            sourceAdmission.accept(stream.sourceDescriptor());
            int timeIdx = requireColumn(stream.schema(), timeColumn);
            int valueIdx = requireColumn(stream.schema(), valueColumn);
            TypedColumn timeCol = stream.schema().get(timeIdx);
            Exclusions excluded = new Exclusions();
            List<Observation> inWindow = new ArrayList<>();
            AnchorGroup left = new AnchorGroup();
            AnchorGroup right = new AnchorGroup();
            while (!stream.exhausted()) {
                guard.check();
                TypedBatch batch = stream.readBatch(256);
                for (TypedRow row : batch.rows()) {
                    Instant instant = TimeAxisNormalizer.toUtcInstant(row, timeIdx, timeCol);
                    if (instant == null) {
                        excluded.missingTimestamp++;
                        continue;
                    }
                    TypedCell cell = row.cells().get(valueIdx);
                    Double value = cell != null && cell.kind() == TypedCell.Kind.NUMBER
                            && Double.isFinite(cell.numberValue()) ? cell.numberValue() : null;
                    if (value == null) {
                        if (window.contains(instant)) {
                            excluded.missingValue++;
                        }
                        continue;
                    }
                    if (window.contains(instant)) {
                        requireSupportedInstant(instant);
                        if (inWindow.size() >= MAX_OBSERVATIONS) {
                            throw new MeasurementException(INPUT_TOO_LARGE,
                                    "more than " + MAX_OBSERVATIONS + " rows inside the window");
                        }
                        inWindow.add(new Observation("", instant, row.sourceOrdinal(), value));
                    } else if (instant.isBefore(window.startInclusive())) {
                        left.offer(instant, row.sourceOrdinal(), value, true);
                    } else {
                        right.offer(instant, row.sourceOrdinal(), value, false);
                    }
                }
            }
            List<Observation> all = new ArrayList<>(inWindow.size() + 4);
            left.appendTo(all);
            all.addAll(inWindow);
            right.appendTo(all);
            Series series = new Series(all, excluded, stream.rowsRead(), stream.bytesReadFromArtifact(),
                    stream.inputsFullyScanned(), stream.sourceDescriptor());
            return new AnchoredSeries(series, left.duplicates + right.duplicates);
        }
    }

    static final class AnchoredSeries {
        final Series series;
        /** Identical rows collapsed inside the two anchor groups; in-window duplicates stay in the series. */
        final long anchorDuplicatesCollapsed;

        AnchoredSeries(Series series, long anchorDuplicatesCollapsed) {
            this.series = series;
            this.anchorDuplicatesCollapsed = anchorDuplicatesCollapsed;
        }
    }

    /** Nearest timestamp group on one side of the window, in bounded state. */
    private static final class AnchorGroup {
        Instant instant;
        long ordinal;
        double value;
        Double conflictingValue;
        long conflictingOrdinal;
        /** Largest-magnitude value seen after the conflict was recorded: the consumer validates the whole group. */
        Double extremeValue;
        long extremeOrdinal;
        long duplicates;

        void offer(Instant candidate, long sourceOrdinal, double candidateValue, boolean latestWins) {
            if (instant == null || (latestWins ? candidate.isAfter(instant) : candidate.isBefore(instant))) {
                instant = candidate;
                ordinal = sourceOrdinal;
                value = candidateValue;
                conflictingValue = null;
                extremeValue = null;
                duplicates = 0L;
            } else if (!candidate.equals(instant)) {
                return;
            } else if (conflictingValue != null) {
                // Already a barrier. The group still has to be judged as a whole, whatever the row order, so
                // the value a domain check would refuse first is kept: the one with the largest magnitude.
                double largest = Math.max(Math.max(Math.abs(value), Math.abs(conflictingValue)),
                        extremeValue == null ? 0d : Math.abs(extremeValue));
                if (Math.abs(candidateValue) > largest) {
                    extremeValue = candidateValue;
                    extremeOrdinal = sourceOrdinal;
                }
            } else {
                if (candidateValue == value) {
                    duplicates++;
                } else {
                    // A conflicting instant is a barrier, not a reading: its rows are not duplicates.
                    conflictingValue = candidateValue;
                    conflictingOrdinal = sourceOrdinal;
                    duplicates = 0L;
                }
            }
        }

        void appendTo(List<Observation> out) {
            if (instant == null) {
                return;
            }
            requireSupportedInstant(instant);
            out.add(new Observation("", instant, ordinal, value));
            if (conflictingValue != null) {
                out.add(new Observation("", instant, conflictingOrdinal, conflictingValue));
            }
            if (extremeValue != null) {
                out.add(new Observation("", instant, extremeOrdinal, extremeValue));
            }
        }
    }

    /**
     * Publish under the guard: encoding and writing use the operation's remaining time and the last check
     * sits immediately before the artifact becomes visible.
     *
     * @return the published id and the descriptor of the published table
     */
    static Published publish(List<TypedColumn> columns, List<TypedRow> rows, Series series, String sourceCacheId,
            String routeId, OperationGuard guard) throws Exception {
        guard.check();
        InfoTable table = DerivedTabularPublisher.toInfoTable(columns, rows);
        SourceDescriptor described = DerivedArtifactLineage.forTransform(
                series.parent, sourceCacheId, table, routeId, series.inputsFullyScanned, true);
        try {
            return new Published(DerivedTabularPublisher.publish(table, described, guard), described);
        } catch (MeasurementException e) {
            throw e;
        } catch (Exception e) {
            // A writer stopped by the shortened I/O deadline is this operation running out of time.
            guard.check();
            throw e;
        }
    }

    static final class Published {
        final String cacheId;
        final SourceDescriptor descriptor;

        Published(String cacheId, SourceDescriptor descriptor) {
            this.cacheId = cacheId;
            this.descriptor = descriptor;
        }
    }

    static String plainNumber(double v) {
        return Math.rint(v) == v && Math.abs(v) < 9_007_199_254_740_992d
                ? new BigDecimal(v).toPlainString()
                : Double.toString(v);
    }

    private static int requireColumn(List<TypedColumn> schema, String name) {
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new MeasurementException(COLUMN_NOT_FOUND, "column not found in the cached table: " + name);
    }

    /**
     * An instant with a sub-millisecond part, or outside the DATETIME range, would be published as a
     * different instant than the one the result was computed from, so the request is refused.
     */
    static void requireSupportedInstant(Instant instant) {
        if (instant.getNano() % 1_000_000 != 0 || instant.isBefore(MIN_INSTANT) || instant.isAfter(MAX_INSTANT)) {
            throw new MeasurementException(TIMESTAMP_UNSUPPORTED,
                    "timestamps must have whole-millisecond precision and a year between 0001 and 9999: " + instant);
        }
    }

    private static String entityText(TypedCell cell) {
        if (cell == null || cell.isNull()) {
            return null;
        }
        String text;
        switch (cell.kind()) {
            case STRING:
                text = cell.stringValue();
                break;
            case NUMBER:
                text = plainNumber(cell.numberValue());
                break;
            case BOOLEAN:
                text = Boolean.toString(cell.booleanValue());
                break;
            default:
                text = cell.datetimeValue() == null ? null : cell.datetimeValue().toString();
        }
        return text == null || text.isBlank() ? null : text;
    }
}
