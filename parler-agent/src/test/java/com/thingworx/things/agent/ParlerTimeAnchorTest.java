package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Deterministic value formatter guard for the one per-round time block. */
class ParlerTimeAnchorTest {

    private static final Instant FIXED = Instant.parse("2026-05-06T18:00:00Z");

    @Test
    void formatTimeValues_validZone_usesOneInstantAndStableOrder() {
        assertEquals("- now_utc: 2026-05-06T18:00:00.000Z\n"
                + "- now_local: 2026-05-06T14:00:00.000-04:00\n"
                + "- user_timezone: America/New_York",
                ParlerTimeAnchor.formatTimeValues("America/New_York", FIXED));
    }

    @Test
    void formatTimeValues_nullOrInvalidZone_keepsUtcOnly() {
        String expected = "- now_utc: 2026-05-06T18:00:00.000Z";
        assertEquals(expected, ParlerTimeAnchor.formatTimeValues(null, FIXED));
        assertEquals(expected, ParlerTimeAnchor.formatTimeValues("Not/A_Zone", FIXED));
    }

    @Test
    void formatTimeValues_nullInstant_failsExplicitly() {
        assertThrows(NullPointerException.class,
                () -> ParlerTimeAnchor.formatTimeValues("America/New_York", null));
    }
}
