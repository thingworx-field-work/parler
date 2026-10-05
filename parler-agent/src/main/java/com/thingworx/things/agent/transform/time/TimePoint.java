package com.thingworx.things.agent.transform.time;

import java.time.Instant;
import java.util.Objects;

/**
 * Ordered observation for G3 operators: canonical UTC instant, stable source ordinal, optional
 * numeric value.
 */
public final class TimePoint {

    private final Instant instant;
    private final long sourceOrdinal;
    private final Double value;

    public TimePoint(Instant instant, long sourceOrdinal, Double value) {
        this.instant = Objects.requireNonNull(instant, "instant");
        if (sourceOrdinal < 0L) {
            throw new IllegalArgumentException("sourceOrdinal must be non-negative");
        }
        this.sourceOrdinal = sourceOrdinal;
        this.value = value;
    }

    public Instant instant() {
        return instant;
    }

    public long sourceOrdinal() {
        return sourceOrdinal;
    }

    public Double value() {
        return value;
    }

    public boolean hasValue() {
        return value != null;
    }
}
