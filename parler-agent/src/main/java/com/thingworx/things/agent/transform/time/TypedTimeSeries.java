package com.thingworx.things.agent.transform.time;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.analysis.TimeAxisNormalizer;
import com.thingworx.things.agent.cache.TypedBatch;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.things.agent.cache.TypedTabularStream;
import com.thingworx.types.BaseTypes;

/** Extract ordered {@link TimePoint}s from a {@link TypedTabularStream}. */
public final class TypedTimeSeries {

    private TypedTimeSeries() {}

    /**
     * Source-order extract for G6 quality (preserves out-of-order / duplicate evidence). Skipped
     * null/unparseable timestamps are counted in {@link SeriesExtract#missingTimestampCount()}.
     */
    public static SeriesExtract extractSourceOrder(TypedTabularStream stream, String timeColumn,
            String valueColumn) throws Exception {
        if (stream == null) {
            throw new IllegalArgumentException("TIME_AXIS_MISSING");
        }
        int timeIdx = indexOf(stream.schema(), timeColumn);
        int valueIdx = valueColumn == null || valueColumn.isBlank()
                ? -1
                : indexOf(stream.schema(), valueColumn);
        if (timeIdx < 0) {
            throw new IllegalArgumentException("TIME_AXIS_MISSING");
        }
        TypedColumn timeCol = stream.schema().get(timeIdx);
        List<TimePoint> points = new ArrayList<>();
        long missing = 0L;
        while (!stream.exhausted()) {
            TypedBatch batch = stream.readBatch(256);
            for (TypedRow row : batch.rows()) {
                Instant instant = TimeAxisNormalizer.toUtcInstant(row, timeIdx, timeCol);
                if (instant == null) {
                    missing++;
                    continue;
                }
                Double value = null;
                if (valueIdx >= 0) {
                    var cell = row.cells().get(valueIdx);
                    if (cell != null && !cell.isNull()
                            && cell.kind() == com.thingworx.things.agent.cache.TypedCell.Kind.NUMBER) {
                        value = cell.numberValue();
                    }
                }
                points.add(new TimePoint(instant, row.sourceOrdinal(), value));
            }
        }
        return new SeriesExtract(points, missing, stream.inputsFullyScanned());
    }

    /** Timestamp-ordered extract for G3 transforms. */
    public static List<TimePoint> extract(TypedTabularStream stream, String timeColumn,
            String valueColumn) throws Exception {
        SeriesExtract src = extractSourceOrder(stream, timeColumn, valueColumn);
        return TimeSeriesOrdering.sortedCopy(src.points());
    }

    /** Result of a source-order series extract. */
    public static final class SeriesExtract {
        private final List<TimePoint> points;
        private final long missingTimestampCount;
        private final boolean inputsFullyScanned;

        public SeriesExtract(List<TimePoint> points, long missingTimestampCount, boolean inputsFullyScanned) {
            this.points = List.copyOf(points == null ? List.of() : points);
            this.missingTimestampCount = Math.max(0L, missingTimestampCount);
            this.inputsFullyScanned = inputsFullyScanned;
        }

        public List<TimePoint> points() {
            return points;
        }

        public long missingTimestampCount() {
            return missingTimestampCount;
        }

        public boolean inputsFullyScanned() {
            return inputsFullyScanned;
        }
    }

    private static int indexOf(List<TypedColumn> schema, String name) {
        if (name == null || name.isBlank() || schema == null) {
            return -1;
        }
        for (int i = 0; i < schema.size(); i++) {
            if (name.equals(schema.get(i).name())) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isTemporal(BaseTypes bt) {
        return bt == BaseTypes.DATETIME || bt == BaseTypes.LONG || bt == BaseTypes.NUMBER;
    }
}
