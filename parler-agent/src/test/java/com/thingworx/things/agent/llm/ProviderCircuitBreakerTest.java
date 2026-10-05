package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ProviderCircuitBreakerTest {

    @Test
    void closedOpensAfterTransientThreshold() {
        MutableClock clock = new MutableClock(0L);
        ProviderCircuitBreaker cb = new ProviderCircuitBreaker(clock, 3, 1_000L);
        assertTrue(cb.admit("P1"));
        cb.recordFailure("P1", LlmProviderFailureClass.TRANSIENT_UPSTREAM);
        cb.recordFailure("P1", LlmProviderFailureClass.TRANSIENT_UPSTREAM);
        assertEquals(ProviderCircuitState.CLOSED, cb.state("P1"));
        cb.recordFailure("P1", LlmProviderFailureClass.TRANSIENT_UPSTREAM);
        assertEquals(ProviderCircuitState.OPEN, cb.state("P1"));
        assertFalse(cb.admit("P1"));
    }

    @Test
    void halfOpenAllowsOnlyOneProbe() {
        MutableClock clock = new MutableClock(0L);
        ProviderCircuitBreaker cb = new ProviderCircuitBreaker(clock, 1, 100L);
        cb.recordFailure("P1", LlmProviderFailureClass.TRANSIENT_UPSTREAM);
        assertEquals(ProviderCircuitState.OPEN, cb.state("P1"));
        clock.advance(150L);
        assertTrue(cb.admit("P1"));
        assertEquals(ProviderCircuitState.HALF_OPEN, cb.state("P1"));
        assertFalse(cb.admit("P1"), "second concurrent half-open probe denied");
        cb.recordSuccess("P1");
        assertEquals(ProviderCircuitState.CLOSED, cb.state("P1"));
        assertTrue(cb.admit("P1"));
    }

    @Test
    void authMarksConfigIneligibleWithoutTransientThrash() {
        ProviderCircuitBreaker cb = new ProviderCircuitBreaker(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 5, 1_000L);
        cb.recordFailure("P1", LlmProviderFailureClass.AUTHENTICATION_OR_CONFIG);
        assertTrue(cb.isConfigIneligible("P1"));
        assertFalse(cb.admit("P1"));
        assertEquals(ProviderCircuitState.OPEN, cb.state("P1"));
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong();

        MutableClock(long start) {
            millis.set(start);
        }

        void advance(long d) {
            millis.addAndGet(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
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
