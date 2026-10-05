package com.thingworx.things.agent.transform.time;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Canonical order: timestamp ascending, then source ordinal. Duplicates remain visible (not
 * dropped).
 */
public final class TimeSeriesOrdering {

    private TimeSeriesOrdering() {}

    public static final Comparator<TimePoint> CANONICAL = Comparator
            .comparing(TimePoint::instant)
            .thenComparingLong(TimePoint::sourceOrdinal);

    public static List<TimePoint> sortedCopy(List<TimePoint> points) {
        List<TimePoint> out = new ArrayList<>(points == null ? List.of() : points);
        out.sort(CANONICAL);
        return out;
    }

    public static int countDuplicateTimestamps(List<TimePoint> ordered) {
        if (ordered == null || ordered.size() < 2) {
            return 0;
        }
        int dups = 0;
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).instant().equals(ordered.get(i - 1).instant())) {
                dups++;
            }
        }
        return dups;
    }
}
