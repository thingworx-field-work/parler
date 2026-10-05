package com.thingworx.things.agent.transform.time;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Pointwise rate {@code (current - previous) / elapsed}. Duplicate timestamps yield insufficient
 * samples (null rate), never division by zero.
 */
public final class RateOfChange {

    public static final class Sample {
        private final TimePoint point;
        private final Double ratePerSecond;
        private final boolean insufficient;

        Sample(TimePoint point, Double ratePerSecond, boolean insufficient) {
            this.point = point;
            this.ratePerSecond = ratePerSecond;
            this.insufficient = insufficient;
        }

        public TimePoint point() {
            return point;
        }

        public Double ratePerSecond() {
            return ratePerSecond;
        }

        public boolean insufficient() {
            return insufficient;
        }
    }

    private RateOfChange() {}

    public static List<Sample> compute(List<TimePoint> points) {
        List<TimePoint> ordered = TimeSeriesOrdering.sortedCopy(points);
        List<Sample> out = new ArrayList<>();
        if (ordered.isEmpty()) {
            return out;
        }
        out.add(new Sample(ordered.get(0), null, true));
        for (int i = 1; i < ordered.size(); i++) {
            TimePoint prev = ordered.get(i - 1);
            TimePoint cur = ordered.get(i);
            if (!prev.hasValue() || !cur.hasValue()) {
                out.add(new Sample(cur, null, true));
                continue;
            }
            Duration elapsed = Duration.between(prev.instant(), cur.instant());
            if (elapsed.isZero() || elapsed.isNegative()) {
                out.add(new Sample(cur, null, true));
                continue;
            }
            double seconds = elapsed.toNanos() / 1_000_000_000d;
            if (seconds == 0d) {
                out.add(new Sample(cur, null, true));
                continue;
            }
            out.add(new Sample(cur, (cur.value() - prev.value()) / seconds, false));
        }
        return out;
    }
}
