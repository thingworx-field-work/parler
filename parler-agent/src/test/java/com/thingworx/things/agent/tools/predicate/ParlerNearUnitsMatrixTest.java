package com.thingworx.things.agent.tools.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.tools.CachedTabularDecisionToolException;

/** Query-spec §3.8 — NEAR units normalization (reject unknown; case-insensitive accepted aliases). */
class ParlerNearUnitsMatrixTest {

    @Test
    void kilometers_normalizes_to_k() throws Exception {
        assertEquals("K", ParlerQueryFilterParser.normalizeNearUnitsOrThrow("Kilometers"));
        assertEquals("K", ParlerQueryFilterParser.normalizeNearUnitsOrThrow("KM"));
    }

    @Test
    void miles_aliases_normalize_to_m() throws Exception {
        assertEquals("M", ParlerQueryFilterParser.normalizeNearUnitsOrThrow("miles"));
        assertEquals("M", ParlerQueryFilterParser.normalizeNearUnitsOrThrow("MILE"));
    }

    @Test
    void furlongs_rejected() {
        CachedTabularDecisionToolException ex =
                assertThrows(CachedTabularDecisionToolException.class,
                        () -> ParlerQueryFilterParser.normalizeNearUnitsOrThrow("furlongs"));
        assertTrue(ex.getMessage().contains("furlongs"));
    }

    @Test
    void empty_units_defaults_to_miles_code() throws Exception {
        assertEquals("M", ParlerQueryFilterParser.normalizeNearUnitsOrThrow(""));
        assertEquals("M", ParlerQueryFilterParser.normalizeNearUnitsOrThrow("   "));
    }
}
