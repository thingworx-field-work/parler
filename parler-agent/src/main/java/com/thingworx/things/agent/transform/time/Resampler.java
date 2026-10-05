package com.thingworx.things.agent.transform.time;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.analysis.HalfOpenWindow;

/**
 * Deterministic resampling into half-open buckets with explicit missing policy.
 */
public final class Resampler {

    private Resampler() {}

    public static List<BucketedValue> resample(List<TimePoint> points, HalfOpenWindow window,
            BucketAssigner assigner, Aggregation aggregation, MissingPolicy missingPolicy) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(assigner, "assigner");
        Objects.requireNonNull(aggregation, "aggregation");
        MissingPolicy policy = missingPolicy == null ? MissingPolicy.NULL : missingPolicy;
        if (policy == MissingPolicy.ZERO
                && aggregation != Aggregation.COUNT
                && aggregation != Aggregation.SUM) {
            throw new IllegalArgumentException("MISSING_POLICY_NOT_ALLOWED");
        }

        List<TimePoint> ordered = TimeSeriesOrdering.sortedCopy(points);
        List<BucketedValue> out = new ArrayList<>();
        HalfOpenWindow bucket = assigner.bucketOf(window.startInclusive());
        // Align: if bucket starts before window, advance until overlapping
        while (bucket.endExclusive().compareTo(window.startInclusive()) <= 0) {
            bucket = assigner.nextBucket(bucket);
        }
        if (bucket.startInclusive().isBefore(window.startInclusive())) {
            // clip first bucket start conceptually — still report bucket bounds from assigner
        }

        while (bucket.startInclusive().isBefore(window.endExclusive())) {
            Instant bStart = bucket.startInclusive();
            Instant bEnd = bucket.endExclusive();
            if (!bEnd.isAfter(window.startInclusive())) {
                bucket = assigner.nextBucket(bucket);
                continue;
            }
            if (!bStart.isBefore(window.endExclusive())) {
                break;
            }
            List<TimePoint> inBucket = new ArrayList<>();
            for (TimePoint p : ordered) {
                if (p.instant().compareTo(bStart) >= 0 && p.instant().isBefore(bEnd)
                        && window.contains(p.instant())) {
                    inBucket.add(p);
                }
            }
            AggregateResult agg = aggregate(inBucket, aggregation);
            if (agg.support == 0) {
                if (policy == MissingPolicy.ZERO) {
                    out.add(new BucketedValue(bStart, bEnd, 0d, true, 0));
                } else {
                    out.add(new BucketedValue(bStart, bEnd, null, true, 0));
                }
            } else {
                out.add(new BucketedValue(bStart, bEnd, agg.value, false, agg.support));
            }
            bucket = assigner.nextBucket(bucket);
        }
        return out;
    }

    static AggregateResult aggregate(List<TimePoint> inBucket, Aggregation aggregation) {
        if (inBucket == null || inBucket.isEmpty()) {
            return AggregateResult.empty();
        }
        switch (aggregation) {
            case COUNT:
                return new AggregateResult((double) inBucket.size(), inBucket.size());
            case SUM: {
                double s = 0d;
                int n = 0;
                for (TimePoint p : inBucket) {
                    if (p.hasValue()) {
                        s += p.value();
                        n++;
                    }
                }
                return n == 0 ? AggregateResult.empty() : new AggregateResult(s, n);
            }
            case MEAN: {
                double s = 0d;
                int n = 0;
                for (TimePoint p : inBucket) {
                    if (p.hasValue()) {
                        s += p.value();
                        n++;
                    }
                }
                return n == 0 ? AggregateResult.empty() : new AggregateResult(s / n, n);
            }
            case MIN: {
                Double min = null;
                int n = 0;
                for (TimePoint p : inBucket) {
                    if (p.hasValue()) {
                        min = min == null ? p.value() : Math.min(min, p.value());
                        n++;
                    }
                }
                return n == 0 ? AggregateResult.empty() : new AggregateResult(min, n);
            }
            case MAX: {
                Double max = null;
                int n = 0;
                for (TimePoint p : inBucket) {
                    if (p.hasValue()) {
                        max = max == null ? p.value() : Math.max(max, p.value());
                        n++;
                    }
                }
                return n == 0 ? AggregateResult.empty() : new AggregateResult(max, n);
            }
            case FIRST: {
                TimePoint first = inBucket.get(0);
                return first.hasValue()
                        ? new AggregateResult(first.value(), 1)
                        : AggregateResult.empty();
            }
            case LAST: {
                TimePoint last = inBucket.get(inBucket.size() - 1);
                return last.hasValue()
                        ? new AggregateResult(last.value(), 1)
                        : AggregateResult.empty();
            }
            default:
                throw new IllegalStateException("unknown aggregation");
        }
    }

    static final class AggregateResult {
        final Double value;
        final int support;

        AggregateResult(Double value, int support) {
            this.value = value;
            this.support = support;
        }

        static AggregateResult empty() {
            return new AggregateResult(null, 0);
        }
    }
}
