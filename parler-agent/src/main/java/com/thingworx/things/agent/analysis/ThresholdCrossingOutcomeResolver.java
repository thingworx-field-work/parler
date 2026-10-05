package com.thingworx.things.agent.analysis;

/**
 * §6.3.5 — singular evaluation precedence for {@code threshold_crossing} when multiple
 * descriptions could apply. Gate checks win over observed/future judgments so
 * {@code already_crossed} never masks {@code insufficient_support} / {@code insufficient_fit} /
 * quality / applicability failures.
 *
 * <p>Order (first match wins):
 * <ol>
 *   <li>{@link ThresholdCrossingOutcome#APPLICABILITY_FAILED}
 *   <li>{@link ThresholdCrossingOutcome#QUALITY_BLOCKED}
 *   <li>{@link ThresholdCrossingOutcome#INSUFFICIENT_SUPPORT}
 *   <li>{@link ThresholdCrossingOutcome#INSUFFICIENT_FIT}
 *   <li>{@link ThresholdCrossingOutcome#ALREADY_CROSSED}
 *   <li>{@link ThresholdCrossingOutcome#CROSSING_WITHIN_HORIZON}
 *   <li>{@link ThresholdCrossingOutcome#OUTSIDE_HORIZON}
 *   <li>{@link ThresholdCrossingOutcome#FLAT}
 *   <li>{@link ThresholdCrossingOutcome#WRONG_DIRECTION}
 * </ol>
 */
public final class ThresholdCrossingOutcomeResolver {

    private ThresholdCrossingOutcomeResolver() {}

    public static ThresholdCrossingOutcome resolve(Flags flags) {
        if (flags == null) {
            throw new IllegalArgumentException("flags required");
        }
        if (flags.applicabilityFailed) {
            return ThresholdCrossingOutcome.APPLICABILITY_FAILED;
        }
        if (flags.qualityBlocked) {
            return ThresholdCrossingOutcome.QUALITY_BLOCKED;
        }
        if (flags.insufficientSupport) {
            return ThresholdCrossingOutcome.INSUFFICIENT_SUPPORT;
        }
        if (flags.insufficientFit) {
            return ThresholdCrossingOutcome.INSUFFICIENT_FIT;
        }
        if (flags.alreadyCrossed) {
            return ThresholdCrossingOutcome.ALREADY_CROSSED;
        }
        if (flags.crossingWithinHorizon) {
            return ThresholdCrossingOutcome.CROSSING_WITHIN_HORIZON;
        }
        if (flags.outsideHorizon) {
            return ThresholdCrossingOutcome.OUTSIDE_HORIZON;
        }
        if (flags.flat) {
            return ThresholdCrossingOutcome.FLAT;
        }
        if (flags.wrongDirection) {
            return ThresholdCrossingOutcome.WRONG_DIRECTION;
        }
        throw new IllegalArgumentException(
                "threshold_crossing flags produced no outcome; at least one judgment flag required");
    }

    /** Mutable input for the precedence walk; hard ERROR faults stay outside this resolver. */
    public static final class Flags {
        public boolean applicabilityFailed;
        public boolean qualityBlocked;
        public boolean insufficientSupport;
        public boolean insufficientFit;
        public boolean alreadyCrossed;
        public boolean crossingWithinHorizon;
        public boolean outsideHorizon;
        public boolean flat;
        public boolean wrongDirection;

        public Flags applicabilityFailed(boolean v) {
            this.applicabilityFailed = v;
            return this;
        }

        public Flags qualityBlocked(boolean v) {
            this.qualityBlocked = v;
            return this;
        }

        public Flags insufficientSupport(boolean v) {
            this.insufficientSupport = v;
            return this;
        }

        public Flags insufficientFit(boolean v) {
            this.insufficientFit = v;
            return this;
        }

        public Flags alreadyCrossed(boolean v) {
            this.alreadyCrossed = v;
            return this;
        }

        public Flags crossingWithinHorizon(boolean v) {
            this.crossingWithinHorizon = v;
            return this;
        }

        public Flags outsideHorizon(boolean v) {
            this.outsideHorizon = v;
            return this;
        }

        public Flags flat(boolean v) {
            this.flat = v;
            return this;
        }

        public Flags wrongDirection(boolean v) {
            this.wrongDirection = v;
            return this;
        }
    }
}
