package com.thingworx.things.agent.analysis;

import java.time.Instant;
import java.util.Objects;

/**
 * Internal U4 computation window {@code [startInclusive, endExclusive)} (B3). Presentation
 * timezone is applied by callers; this type stores UTC instants only.
 */
public final class HalfOpenWindow {

    private final Instant startInclusive;
    private final Instant endExclusive;

    private HalfOpenWindow(Instant startInclusive, Instant endExclusive) {
        this.startInclusive = Objects.requireNonNull(startInclusive, "startInclusive");
        this.endExclusive = Objects.requireNonNull(endExclusive, "endExclusive");
        if (!endExclusive.isAfter(startInclusive)) {
            throw new IllegalArgumentException("endExclusive must be after startInclusive");
        }
    }

    public static HalfOpenWindow of(Instant startInclusive, Instant endExclusive) {
        return new HalfOpenWindow(startInclusive, endExclusive);
    }

    public Instant startInclusive() {
        return startInclusive;
    }

    public Instant endExclusive() {
        return endExclusive;
    }

    /** Boundary points at {@code endExclusive} belong to the next window. */
    public boolean contains(Instant instant) {
        if (instant == null) {
            return false;
        }
        return !instant.isBefore(startInclusive) && instant.isBefore(endExclusive);
    }
}
