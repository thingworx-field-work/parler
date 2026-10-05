package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** Non-positive caps never become uncapped (defense in depth). */
class ProviderRetryBudgetBoundsTest {

    @Test
    void rejectsNonPositiveWaitWallAndAttempts() {
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRetryBudget(1, 3, 0L, 60_000L, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRetryBudget(1, 3, -1L, 60_000L, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRetryBudget(1, 3, 30_000L, 0L, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRetryBudget(1, 0, 30_000L, 60_000L, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRetryBudget(-1, 3, 30_000L, 60_000L, clock));
    }

    @Test
    void remainingBudgetsAreFiniteAndShrink() {
        ProviderRetryBudget budget = new ProviderRetryBudget(
                1, 3, 1_000L, 5_000L, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        assertEquals(1_000L, budget.remainingWaitMs());
        assertEquals(5_000L, budget.remainingWallMs());
        budget.recordWaitMs(400L);
        assertEquals(600L, budget.remainingWaitMs());
        assertTrue(budget.remainingWaitMs() < Long.MAX_VALUE);
        assertTrue(budget.remainingWallMs() < Long.MAX_VALUE);
    }
}
