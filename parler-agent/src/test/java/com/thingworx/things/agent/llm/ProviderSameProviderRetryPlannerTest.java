package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** SPR-3: same-provider retry plan against injectable clock/RNG; no second rate gate. */
class ProviderSameProviderRetryPlannerTest {

    @Test
    void rateLimitedRetriesWithRetryAfterHint() {
        MutableClock clock = new MutableClock(1_000L);
        ProviderRetryBudget budget = new ProviderRetryBudget(2, 5, 60_000L, 60_000L, clock);
        budget.recordAttemptStarted();

        LlmProviderFailureSignal signal = LlmProviderFailureSignal.forHttpStatus(
                429, true, Map.of("Retry-After", "1"), false);
        ProviderSameProviderRetryPlan plan = ProviderRouteCoordinator.planSameProviderRetry(
                signal, budget, false, () -> 0.0d);

        assertEquals(ProviderSameProviderRetryPlan.Action.RETRY_SAME, plan.action());
        assertEquals(LlmProviderFailureClass.RATE_LIMITED, plan.failureClass());
        assertEquals(1_000L, plan.delayMs());
        assertTrue(plan.telemetryToken().contains("RATE_LIMITED"));
    }

    @Test
    void transient5xxRetriesWithJitterOnlyAffectingDelay() {
        ProviderRetryBudget budget = new ProviderRetryBudget(
                3, 5, 60_000L, 60_000L, Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC));
        budget.recordAttemptStarted();
        ProviderSameProviderRetryPlan a = ProviderRouteCoordinator.planSameProviderRetry(
                LlmProviderFailureSignal.forHttpStatus(503, false), budget, false, () -> 0.25d);
        ProviderSameProviderRetryPlan b = ProviderRouteCoordinator.planSameProviderRetry(
                LlmProviderFailureSignal.forHttpStatus(503, false), budget, false, () -> 0.75d);
        assertEquals(ProviderSameProviderRetryPlan.Action.RETRY_SAME, a.action());
        assertEquals(ProviderSameProviderRetryPlan.Action.RETRY_SAME, b.action());
        assertTrue(a.delayMs() < b.delayMs());
    }

    @Test
    void authInvalidCancelPartialAreTerminal() {
        ProviderRetryBudget budget = freshBudget();
        budget.recordAttemptStarted();
        assertEquals(
                ProviderSameProviderRetryPlan.Action.STOP_TERMINAL,
                ProviderRouteCoordinator.planSameProviderRetry(
                        LlmProviderFailureSignal.forHttpStatus(401, false), budget, false, () -> 0.5d)
                        .action());
        assertEquals(
                ProviderSameProviderRetryPlan.Action.STOP_TERMINAL,
                ProviderRouteCoordinator.planSameProviderRetry(
                        LlmProviderFailureSignal.forHttpStatus(400, false), budget, false, () -> 0.5d)
                        .action());
        assertEquals(
                ProviderSameProviderRetryPlan.Action.STOP_TERMINAL,
                ProviderRouteCoordinator.planSameProviderRetry(
                        LlmProviderFailureSignal.forHttpStatus(503, false), budget, true, () -> 0.5d)
                        .action());
        assertEquals(
                LlmProviderFailureClass.CANCELLED,
                ProviderRouteCoordinator.planSameProviderRetry(
                        LlmProviderFailureSignal.forHttpStatus(503, false), budget, true, () -> 0.5d)
                        .failureClass());
        assertEquals(
                ProviderSameProviderRetryPlan.Action.STOP_TERMINAL,
                ProviderRouteCoordinator.planSameProviderRetry(
                        LlmProviderFailureSignal.forHttpStatus(503, true), budget, false, () -> 0.5d)
                        .action());
    }

    @Test
    void modelUnavailableIsFallbackCandidateNotSameProviderRetry() {
        ProviderRetryBudget budget = freshBudget();
        budget.recordAttemptStarted();
        ProviderSameProviderRetryPlan plan = ProviderRouteCoordinator.planSameProviderRetry(
                LlmProviderFailureSignal.forHttpStatus(404, false), budget, false, () -> 0.5d);
        assertEquals(ProviderSameProviderRetryPlan.Action.FALLBACK_CANDIDATE, plan.action());
        assertEquals(LlmProviderFailureClass.MODEL_UNAVAILABLE, plan.failureClass());
    }

    /**
     * After local same-provider retry budget, fallback-eligible classes hand off
     * {@code FALLBACK_CANDIDATE} when route attempt/wall/wait budget remains (§7.2).
     */
    @Test
    void sameProviderCapYieldsFallbackCandidateForTransientClasses() {
        for (LlmProviderFailureSignal signal : new LlmProviderFailureSignal[] {
                LlmProviderFailureSignal.forHttpStatus(503, false),
                LlmProviderFailureSignal.forHttpStatus(429, true, Map.of("Retry-After", "1"), false),
                LlmProviderFailureSignal.forTimeoutBeforeResponse(false)
        }) {
            ProviderRetryBudget budget = new ProviderRetryBudget(
                    1, 5, 60_000L, 60_000L, Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC));
            budget.recordAttemptStarted();
            budget.recordSameProviderRetry();
            ProviderSameProviderRetryPlan plan = ProviderRouteCoordinator.planSameProviderRetry(
                    signal, budget, false, () -> 0.1d);
            assertEquals(
                    ProviderSameProviderRetryPlan.Action.FALLBACK_CANDIDATE,
                    plan.action(),
                    () -> "expected FALLBACK_CANDIDATE for " + plan.failureClass());
            assertTrue(plan.failureClass().fallbackEligible());
        }
    }

    @Test
    void exhaustedWhenTotalAttemptsConsumed() {
        ProviderRetryBudget budget = new ProviderRetryBudget(
                3, 1, 60_000L, 60_000L, Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC));
        budget.recordAttemptStarted();
        ProviderSameProviderRetryPlan plan = ProviderRouteCoordinator.planSameProviderRetry(
                LlmProviderFailureSignal.forHttpStatus(503, false), budget, false, () -> 0.1d);
        assertEquals(ProviderSameProviderRetryPlan.Action.STOP_EXHAUSTED, plan.action());
    }

    @Test
    void exhaustedWhenWallTimeElapsed() {
        MutableClock clock = new MutableClock(0L);
        ProviderRetryBudget budget = new ProviderRetryBudget(3, 5, 60_000L, 100L, clock);
        budget.recordAttemptStarted();
        clock.advance(150L);
        ProviderSameProviderRetryPlan plan = ProviderRouteCoordinator.planSameProviderRetry(
                LlmProviderFailureSignal.forHttpStatus(503, false), budget, false, () -> 0.1d);
        assertEquals(ProviderSameProviderRetryPlan.Action.STOP_EXHAUSTED, plan.action());
    }

    /**
     * Oversized Retry-After must not shrink into an early RETRY_SAME. With remaining
     * route budget, hand off FALLBACK_CANDIDATE (§7.3 / D10).
     */
    @Test
    void oversizedRetryAfterYieldsFallbackCandidateNotEarlyRetry() {
        ProviderRetryBudget budget = new ProviderRetryBudget(
                3, 5, 1_000L, 60_000L, Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC));
        budget.recordAttemptStarted();
        budget.recordWaitMs(900L); // prior rate-gate wait ⇒ 100ms remaining
        LlmProviderFailureSignal signal = LlmProviderFailureSignal.forHttpStatus(
                429, true, Map.of("Retry-After", "2"), false); // 2000ms hint
        ProviderSameProviderRetryPlan plan = ProviderRouteCoordinator.planSameProviderRetry(
                signal, budget, false, () -> 0.0d);
        assertEquals(ProviderSameProviderRetryPlan.Action.FALLBACK_CANDIDATE, plan.action());
        assertEquals(LlmProviderFailureClass.RATE_LIMITED, plan.failureClass());
        assertEquals(0L, plan.delayMs());
    }

    @Test
    void oversizedRetryAfterWithNoRemainingWaitIsExhausted() {
        ProviderRetryBudget budget = new ProviderRetryBudget(
                3, 5, 1_000L, 60_000L, Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC));
        budget.recordAttemptStarted();
        budget.recordWaitMs(1_000L); // wait fully consumed
        LlmProviderFailureSignal signal = LlmProviderFailureSignal.forHttpStatus(
                429, true, Map.of("Retry-After", "2"), false);
        ProviderSameProviderRetryPlan plan = ProviderRouteCoordinator.planSameProviderRetry(
                signal, budget, false, () -> 0.0d);
        assertEquals(ProviderSameProviderRetryPlan.Action.STOP_EXHAUSTED, plan.action());
    }

    private static ProviderRetryBudget freshBudget() {
        return new ProviderRetryBudget(
                2, 5, 60_000L, 60_000L, Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC));
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;
        private final ZoneOffset zone = ZoneOffset.UTC;

        MutableClock(long start) {
            this.millis = new AtomicLong(start);
        }

        void advance(long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }
}
