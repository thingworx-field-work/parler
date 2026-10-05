package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Locks the ordering: gate checks precede already_crossed.
 */
class ThresholdCrossingOutcomeResolverTest {

    @Test
    void gatesBeatAlreadyCrossed() {
        assertEquals(
                ThresholdCrossingOutcome.INSUFFICIENT_SUPPORT,
                ThresholdCrossingOutcomeResolver.resolve(new ThresholdCrossingOutcomeResolver.Flags()
                        .insufficientSupport(true)
                        .alreadyCrossed(true)
                        .flat(true)));
        assertEquals(
                ThresholdCrossingOutcome.INSUFFICIENT_FIT,
                ThresholdCrossingOutcomeResolver.resolve(new ThresholdCrossingOutcomeResolver.Flags()
                        .insufficientFit(true)
                        .alreadyCrossed(true)));
        assertEquals(
                ThresholdCrossingOutcome.QUALITY_BLOCKED,
                ThresholdCrossingOutcomeResolver.resolve(new ThresholdCrossingOutcomeResolver.Flags()
                        .qualityBlocked(true)
                        .alreadyCrossed(true)
                        .crossingWithinHorizon(true)));
        assertEquals(
                ThresholdCrossingOutcome.APPLICABILITY_FAILED,
                ThresholdCrossingOutcomeResolver.resolve(new ThresholdCrossingOutcomeResolver.Flags()
                        .applicabilityFailed(true)
                        .qualityBlocked(true)
                        .alreadyCrossed(true)));
    }

    @Test
    void alreadyCrossedBeatsFutureJudgmentsWhenGatesPass() {
        assertEquals(
                ThresholdCrossingOutcome.ALREADY_CROSSED,
                ThresholdCrossingOutcomeResolver.resolve(new ThresholdCrossingOutcomeResolver.Flags()
                        .alreadyCrossed(true)
                        .crossingWithinHorizon(true)
                        .outsideHorizon(true)
                        .flat(true)
                        .wrongDirection(true)));
    }

    @Test
    void postGateOrder_crossingThenOutsideThenFlatThenWrong() {
        assertEquals(
                ThresholdCrossingOutcome.CROSSING_WITHIN_HORIZON,
                ThresholdCrossingOutcomeResolver.resolve(new ThresholdCrossingOutcomeResolver.Flags()
                        .crossingWithinHorizon(true)
                        .outsideHorizon(true)));
        assertEquals(
                ThresholdCrossingOutcome.OUTSIDE_HORIZON,
                ThresholdCrossingOutcomeResolver.resolve(new ThresholdCrossingOutcomeResolver.Flags()
                        .outsideHorizon(true)
                        .flat(true)));
        assertEquals(
                ThresholdCrossingOutcome.FLAT,
                ThresholdCrossingOutcomeResolver.resolve(new ThresholdCrossingOutcomeResolver.Flags()
                        .flat(true)
                        .wrongDirection(true)));
        assertEquals(
                ThresholdCrossingOutcome.WRONG_DIRECTION,
                ThresholdCrossingOutcomeResolver.resolve(new ThresholdCrossingOutcomeResolver.Flags()
                        .wrongDirection(true)));
    }
}
