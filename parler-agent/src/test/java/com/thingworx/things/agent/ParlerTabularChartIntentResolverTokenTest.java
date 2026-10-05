package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Offline-safe: intent token allowlist only (no ThingWorx {@link com.thingworx.types.InfoTable}). */
class ParlerTabularChartIntentResolverTokenTest {

    @Test
    void knownIntentsRecognized() {
        assertTrue(ParlerTabularChartIntentResolver.isKnownIntentToken("rank"));
        assertTrue(ParlerTabularChartIntentResolver.isKnownIntentToken("TIME_TREND"));
        assertFalse(ParlerTabularChartIntentResolver.isKnownIntentToken("kpi"));
        assertFalse(ParlerTabularChartIntentResolver.isKnownIntentToken(""));
    }
}
