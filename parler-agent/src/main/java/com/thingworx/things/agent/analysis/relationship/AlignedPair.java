package com.thingworx.things.agent.analysis.relationship;

import java.util.Objects;

import com.thingworx.things.agent.analysis.stats.NumericObservation;

/** One deterministic left/right alignment (DIK-3). */
public final class AlignedPair {

    private final NumericObservation left;
    private final NumericObservation right;
    private final long deltaMillis;

    public AlignedPair(NumericObservation left, NumericObservation right, long deltaMillis) {
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
        this.deltaMillis = deltaMillis;
    }

    public NumericObservation left() {
        return left;
    }

    public NumericObservation right() {
        return right;
    }

    public long deltaMillis() {
        return deltaMillis;
    }

    public boolean bothFinite() {
        return left.isFinite() && right.isFinite();
    }
}
