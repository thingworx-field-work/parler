package com.thingworx.things.agent.transform.time;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Observation-count and elapsed-duration rolling aggregates. */
public final class RollingOperator {

    public enum WindowKind {
        OBSERVATION_COUNT,
        ELAPSED_DURATION
    }

    private RollingOperator() {}

    public static List<BucketedValue> rollingMean(List<TimePoint> points, WindowKind kind,
            int observationWindow, Duration durationWindow, int minSupport) {
        Objects.requireNonNull(kind, "kind");
        if (minSupport < 1) {
            throw new IllegalArgumentException("WINDOW_INVALID");
        }
        List<TimePoint> ordered = TimeSeriesOrdering.sortedCopy(points);
        List<BucketedValue> out = new ArrayList<>();
        if (kind == WindowKind.OBSERVATION_COUNT) {
            if (observationWindow < 1) {
                throw new IllegalArgumentException("WINDOW_INVALID");
            }
            Deque<TimePoint> q = new ArrayDeque<>();
            for (TimePoint p : ordered) {
                q.addLast(p);
                while (q.size() > observationWindow) {
                    q.removeFirst();
                }
                Aggregate a = meanOf(q);
                if (a.support >= minSupport) {
                    out.add(new BucketedValue(p.instant(), p.instant(), a.mean, false, a.support));
                } else {
                    out.add(new BucketedValue(p.instant(), p.instant(), null, false, a.support));
                }
            }
            return out;
        }
        if (durationWindow == null || durationWindow.isZero() || durationWindow.isNegative()) {
            throw new IllegalArgumentException("WINDOW_INVALID");
        }
        Deque<TimePoint> q = new ArrayDeque<>();
        for (TimePoint p : ordered) {
            q.addLast(p);
            while (!q.isEmpty()
                    && Duration.between(q.peekFirst().instant(), p.instant()).compareTo(durationWindow) > 0) {
                q.removeFirst();
            }
            Aggregate a = meanOf(q);
            if (a.support >= minSupport) {
                out.add(new BucketedValue(p.instant(), p.instant(), a.mean, false, a.support));
            } else {
                out.add(new BucketedValue(p.instant(), p.instant(), null, false, a.support));
            }
        }
        return out;
    }

    private static Aggregate meanOf(Deque<TimePoint> q) {
        double s = 0d;
        int n = 0;
        for (TimePoint p : q) {
            if (p.hasValue()) {
                s += p.value();
                n++;
            }
        }
        return n == 0 ? new Aggregate(null, 0) : new Aggregate(s / n, n);
    }

    private static final class Aggregate {
        final Double mean;
        final int support;

        Aggregate(Double mean, int support) {
            this.mean = mean;
            this.support = support;
        }
    }
}
