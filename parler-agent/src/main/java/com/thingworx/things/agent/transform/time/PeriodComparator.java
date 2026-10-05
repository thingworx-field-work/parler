package com.thingworx.things.agent.transform.time;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.analysis.HalfOpenWindow;

/**
 * Period-over-period comparison: same aggregation on two concrete windows; zero denominator yields
 * structured undefined percent (not infinity).
 */
public final class PeriodComparator {

    public static final class Result {
        private final Double original;
        private final Double current;
        private final Double delta;
        private final Double percent;
        private final boolean percentUndefined;
        private final boolean unequalOrPartial;
        private final int originalSupport;
        private final int currentSupport;

        Result(Double original, Double current, Double delta, Double percent,
                boolean percentUndefined, boolean unequalOrPartial, int originalSupport,
                int currentSupport) {
            this.original = original;
            this.current = current;
            this.delta = delta;
            this.percent = percent;
            this.percentUndefined = percentUndefined;
            this.unequalOrPartial = unequalOrPartial;
            this.originalSupport = originalSupport;
            this.currentSupport = currentSupport;
        }

        public Double original() {
            return original;
        }

        public Double current() {
            return current;
        }

        public Double delta() {
            return delta;
        }

        public Double percent() {
            return percent;
        }

        public boolean percentUndefined() {
            return percentUndefined;
        }

        public boolean unequalOrPartial() {
            return unequalOrPartial;
        }

        public int originalSupport() {
            return originalSupport;
        }

        public int currentSupport() {
            return currentSupport;
        }
    }

    private PeriodComparator() {}

    public static Result compare(List<TimePoint> points, HalfOpenWindow originalWindow,
            HalfOpenWindow currentWindow, Aggregation aggregation) {
        Objects.requireNonNull(originalWindow, "originalWindow");
        Objects.requireNonNull(currentWindow, "currentWindow");
        Objects.requireNonNull(aggregation, "aggregation");
        List<TimePoint> ordered = TimeSeriesOrdering.sortedCopy(points);
        List<TimePoint> orig = filter(ordered, originalWindow);
        List<TimePoint> cur = filter(ordered, currentWindow);
        Resampler.AggregateResult a = Resampler.aggregate(orig, aggregation);
        Resampler.AggregateResult b = Resampler.aggregate(cur, aggregation);
        long origMillis = java.time.Duration.between(
                originalWindow.startInclusive(), originalWindow.endExclusive()).toMillis();
        long curMillis = java.time.Duration.between(
                currentWindow.startInclusive(), currentWindow.endExclusive()).toMillis();
        boolean unequal = origMillis != curMillis;
        boolean partial = a.support == 0 || b.support == 0;
        Double delta = (a.value != null && b.value != null) ? b.value - a.value : null;
        boolean percentUndefined = a.value == null || a.value == 0d || b.value == null;
        Double percent = null;
        if (!percentUndefined) {
            percent = (delta / a.value) * 100d;
        }
        return new Result(a.value, b.value, delta, percent, percentUndefined, unequal || partial,
                a.support, b.support);
    }

    private static List<TimePoint> filter(List<TimePoint> ordered, HalfOpenWindow window) {
        List<TimePoint> out = new ArrayList<>();
        for (TimePoint p : ordered) {
            if (window.contains(p.instant())) {
                out.add(p);
            }
        }
        return out;
    }
}
