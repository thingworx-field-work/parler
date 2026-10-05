package com.thingworx.things.agent.transform.time;

import java.time.Instant;
import java.util.Objects;

/** One output bucket from resampling. */
public final class BucketedValue {

    private final Instant bucketStartInclusive;
    private final Instant bucketEndExclusive;
    private final Double value;
    private final boolean synthesized;
    private final int support;

    public BucketedValue(Instant bucketStartInclusive, Instant bucketEndExclusive, Double value,
            boolean synthesized, int support) {
        this.bucketStartInclusive = Objects.requireNonNull(bucketStartInclusive, "start");
        this.bucketEndExclusive = Objects.requireNonNull(bucketEndExclusive, "end");
        this.value = value;
        this.synthesized = synthesized;
        this.support = Math.max(0, support);
    }

    public Instant bucketStartInclusive() {
        return bucketStartInclusive;
    }

    public Instant bucketEndExclusive() {
        return bucketEndExclusive;
    }

    public Double value() {
        return value;
    }

    public boolean synthesized() {
        return synthesized;
    }

    public int support() {
        return support;
    }
}
