package com.thingworx.things.agent.quality;

import java.time.Duration;
import java.util.Objects;

/**
 * Validated G6 quality thresholds. Expected cadence may be absent (UNKNOWN cadence-dependent
 * fields). Spec and control range bounds are distinct. Supplied invalid thresholds fail fast —
 * they are never silently coerced into another meaning.
 */
public final class QualityProfile {

    private final String profileDigest;
    private final Duration expectedCadence;
    private final Duration freshnessMaxAge;
    private final double flatlineEpsilon;
    private final Duration flatlineMinDuration;
    private final int flatlineMinSupport;
    private final Double specMin;
    private final Double specMax;
    private final Double controlMin;
    private final Double controlMax;
    private final double cadenceDriftRatio;
    private final QualitySeverity duplicateSeverity;
    private final QualitySeverity outOfOrderSeverity;
    private final QualitySeverity freshnessSeverity;
    private final QualitySeverity flatlineSeverity;
    private final QualitySeverity rangeSeverity;

    private QualityProfile(Builder b) {
        this.profileDigest = Objects.requireNonNull(b.profileDigest, "profileDigest");
        if (profileDigest.isBlank()) {
            throw new IllegalArgumentException("profileDigest required");
        }

        this.expectedCadence = requirePositiveDurationOrAbsent(b.expectedCadence, "expectedCadence");
        this.freshnessMaxAge = requireNonNegativeDurationOrAbsent(b.freshnessMaxAge, "freshnessMaxAge");
        this.flatlineEpsilon = requireFiniteNonNegative(b.flatlineEpsilon, "flatlineEpsilon");
        this.flatlineMinDuration = b.flatlineMinDuration == null
                ? Duration.ZERO
                : requireNonNegativeDuration(b.flatlineMinDuration, "flatlineMinDuration");
        if (b.flatlineMinSupport < 1) {
            throw new IllegalArgumentException("flatlineMinSupport must be >= 1");
        }
        this.flatlineMinSupport = b.flatlineMinSupport;

        this.specMin = requireFiniteOrAbsent(b.specMin, "specMin");
        this.specMax = requireFiniteOrAbsent(b.specMax, "specMax");
        this.controlMin = requireFiniteOrAbsent(b.controlMin, "controlMin");
        this.controlMax = requireFiniteOrAbsent(b.controlMax, "controlMax");
        requireOrderedBounds(this.specMin, this.specMax, "spec");
        requireOrderedBounds(this.controlMin, this.controlMax, "control");

        this.cadenceDriftRatio = requireFiniteAboveOne(b.cadenceDriftRatio, "cadenceDriftRatio");

        this.duplicateSeverity = b.duplicateSeverity == null ? QualitySeverity.WARNING : b.duplicateSeverity;
        this.outOfOrderSeverity = b.outOfOrderSeverity == null ? QualitySeverity.WARNING : b.outOfOrderSeverity;
        this.freshnessSeverity = b.freshnessSeverity == null ? QualitySeverity.BLOCKING : b.freshnessSeverity;
        this.flatlineSeverity = b.flatlineSeverity == null ? QualitySeverity.BLOCKING : b.flatlineSeverity;
        this.rangeSeverity = b.rangeSeverity == null ? QualitySeverity.WARNING : b.rangeSeverity;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String profileDigest() {
        return profileDigest;
    }

    public Duration expectedCadence() {
        return expectedCadence;
    }

    public boolean hasExpectedCadence() {
        return expectedCadence != null;
    }

    public Duration freshnessMaxAge() {
        return freshnessMaxAge;
    }

    public double flatlineEpsilon() {
        return flatlineEpsilon;
    }

    public Duration flatlineMinDuration() {
        return flatlineMinDuration;
    }

    public int flatlineMinSupport() {
        return flatlineMinSupport;
    }

    public Double specMin() {
        return specMin;
    }

    public Double specMax() {
        return specMax;
    }

    public Double controlMin() {
        return controlMin;
    }

    public Double controlMax() {
        return controlMax;
    }

    /**
     * Factor above 1: {@code CADENCE_DRIFT} warns when the recent median sample interval is at least this many
     * times the expected cadence, or at most its reciprocal.
     */
    public double cadenceDriftRatio() {
        return cadenceDriftRatio;
    }

    public QualitySeverity duplicateSeverity() {
        return duplicateSeverity;
    }

    public QualitySeverity outOfOrderSeverity() {
        return outOfOrderSeverity;
    }

    public QualitySeverity freshnessSeverity() {
        return freshnessSeverity;
    }

    public QualitySeverity flatlineSeverity() {
        return flatlineSeverity;
    }

    public QualitySeverity rangeSeverity() {
        return rangeSeverity;
    }

    private static Duration requirePositiveDurationOrAbsent(Duration v, String name) {
        if (v == null) {
            return null;
        }
        if (v.isZero() || v.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive when supplied");
        }
        return v;
    }

    private static Duration requireNonNegativeDurationOrAbsent(Duration v, String name) {
        if (v == null) {
            return null;
        }
        return requireNonNegativeDuration(v, name);
    }

    private static Duration requireNonNegativeDuration(Duration v, String name) {
        if (v.isNegative()) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return v;
    }

    private static double requireFiniteNonNegative(double v, String name) {
        if (!Double.isFinite(v) || v < 0d) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return v;
    }

    private static double requireFinitePositive(double v, String name) {
        if (!Double.isFinite(v) || v <= 0d) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
        return v;
    }

    private static double requireFiniteAboveOne(double v, String name) {
        if (!Double.isFinite(v) || v <= 1d) {
            throw new IllegalArgumentException(name + " must be a finite factor greater than 1");
        }
        return v;
    }

    private static Double requireFiniteOrAbsent(Double v, String name) {
        if (v == null) {
            return null;
        }
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(name + " must be finite when supplied");
        }
        return v;
    }

    private static void requireOrderedBounds(Double min, Double max, String label) {
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException(label + "Min must be <= " + label + "Max");
        }
    }

    public static final class Builder {
        private String profileDigest = "quality-profile-v1";
        private Duration expectedCadence;
        private Duration freshnessMaxAge;
        private double flatlineEpsilon = 1e-9;
        private Duration flatlineMinDuration = Duration.ofMinutes(5);
        private int flatlineMinSupport = 3;
        private Double specMin;
        private Double specMax;
        private Double controlMin;
        private Double controlMax;
        private double cadenceDriftRatio = 2.0d;
        private QualitySeverity duplicateSeverity;
        private QualitySeverity outOfOrderSeverity;
        private QualitySeverity freshnessSeverity;
        private QualitySeverity flatlineSeverity;
        private QualitySeverity rangeSeverity;

        public Builder profileDigest(String v) {
            this.profileDigest = v;
            return this;
        }

        public Builder expectedCadence(Duration v) {
            this.expectedCadence = v;
            return this;
        }

        public Builder freshnessMaxAge(Duration v) {
            this.freshnessMaxAge = v;
            return this;
        }

        public Builder flatlineEpsilon(double v) {
            this.flatlineEpsilon = v;
            return this;
        }

        public Builder flatlineMinDuration(Duration v) {
            this.flatlineMinDuration = v;
            return this;
        }

        public Builder flatlineMinSupport(int v) {
            this.flatlineMinSupport = v;
            return this;
        }

        public Builder specMin(Double v) {
            this.specMin = v;
            return this;
        }

        public Builder specMax(Double v) {
            this.specMax = v;
            return this;
        }

        public Builder controlMin(Double v) {
            this.controlMin = v;
            return this;
        }

        public Builder controlMax(Double v) {
            this.controlMax = v;
            return this;
        }

        public Builder cadenceDriftRatio(double v) {
            this.cadenceDriftRatio = v;
            return this;
        }

        public Builder duplicateSeverity(QualitySeverity v) {
            this.duplicateSeverity = v;
            return this;
        }

        public Builder outOfOrderSeverity(QualitySeverity v) {
            this.outOfOrderSeverity = v;
            return this;
        }

        public Builder freshnessSeverity(QualitySeverity v) {
            this.freshnessSeverity = v;
            return this;
        }

        public Builder flatlineSeverity(QualitySeverity v) {
            this.flatlineSeverity = v;
            return this;
        }

        public Builder rangeSeverity(QualitySeverity v) {
            this.rangeSeverity = v;
            return this;
        }

        public QualityProfile build() {
            return new QualityProfile(this);
        }
    }
}
