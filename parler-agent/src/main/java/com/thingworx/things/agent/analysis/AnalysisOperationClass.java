package com.thingworx.things.agent.analysis;

/**
 * Operation class for {@link AnalysisOperation}. U5 §6.3.1 — detection vs quantification.
 * Quantification never uses {@code NO_FINDING} for a weak/near-zero effect. U6 fleet ops use
 * {@link #FLEET} (quantification-like for status; never a causal {@code NO_FINDING} stand-in).
 */
public enum AnalysisOperationClass {
    DETECTION,
    QUANTIFICATION,
    /** U4 time/quality/join families — not U5 detection/quantification. */
    TRANSFORM,
    /** U6 G5 fleet/cohort benchmarking (FRC-0+). */
    FLEET,
    /**
     * Computing-enhancement measurements over observed data (CF-05+). A measurement describes the
     * observations it read; it may succeed under unproven source completeness only when it discloses
     * that scope (see {@link AnalysisEnvelopeValidator}).
     */
    MEASUREMENT
}
