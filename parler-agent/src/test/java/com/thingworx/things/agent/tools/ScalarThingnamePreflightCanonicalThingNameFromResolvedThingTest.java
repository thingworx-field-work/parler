package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Offline coverage of {@link ScalarThingnamePreflight#canonicalThingNameFromResolvedThing} branches that need no
 * platform {@link Thing} (fallback half of matrix #1).
 *
 * <p>Non-blank {@link Thing#getName()} success still requires Composer / staging (see
 * {@code docs/agent/thingname-preflight-coverage.md} test matrix #1 and Phase D gate D-1).
 */
class ScalarThingnamePreflightCanonicalThingNameFromResolvedThingTest {

    @Test
    void null_fallback_returns_empty() {
        assertEquals("", ScalarThingnamePreflight.canonicalThingNameFromResolvedThing(null, null));
    }

    @Test
    void null_thing_returns_fallback_verbatim() {
        assertEquals("fb", ScalarThingnamePreflight.canonicalThingNameFromResolvedThing(null, "fb"));
        assertEquals("  fb  ", ScalarThingnamePreflight.canonicalThingNameFromResolvedThing(null, "  fb  "));
    }

    @Test
    void null_thing_empty_fallback_returns_empty() {
        assertEquals("", ScalarThingnamePreflight.canonicalThingNameFromResolvedThing(null, ""));
    }
}
