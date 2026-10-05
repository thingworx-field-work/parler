package com.thingworx.things.agent.analysis.stats;

import java.time.Instant;
import java.util.Objects;

/** One projected numeric observation with stable source ordinal (DIK-1). */
public final class NumericObservation {

    private final Instant instant;
    private final long sourceOrdinal;
    private final double value;
    private final boolean finite;

    public NumericObservation(Instant instant, long sourceOrdinal, Double value) {
        this.instant = instant;
        if (sourceOrdinal < 0L) {
            throw new IllegalArgumentException("sourceOrdinal must be non-negative");
        }
        this.sourceOrdinal = sourceOrdinal;
        if (value == null || Double.isNaN(value)) {
            this.value = Double.NaN;
            this.finite = false;
        } else if (!Double.isFinite(value)) {
            this.value = value;
            this.finite = false;
        } else {
            this.value = value;
            this.finite = true;
        }
    }

    public Instant instant() {
        return instant;
    }

    public long sourceOrdinal() {
        return sourceOrdinal;
    }

    public double value() {
        return value;
    }

    public boolean isFinite() {
        return finite;
    }

    public NumericObservation requireFinite() {
        if (!finite) {
            throw new IllegalStateException("observation is not finite");
        }
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NumericObservation)) {
            return false;
        }
        NumericObservation that = (NumericObservation) o;
        return sourceOrdinal == that.sourceOrdinal
                && finite == that.finite
                && Double.compare(that.value, value) == 0
                && Objects.equals(instant, that.instant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instant, sourceOrdinal, value, finite);
    }
}
