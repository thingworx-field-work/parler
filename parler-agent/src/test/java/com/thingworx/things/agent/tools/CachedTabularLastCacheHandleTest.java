package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CachedTabularLastCacheHandleTest {

    @Test
    void token_detectedExact() {
        assertTrue(CachedTabularLastCacheHandle.isToken(CachedTabularLastCacheHandle.TOKEN));
    }

    @Test
    void token_detectedWithWhitespace() {
        assertTrue(CachedTabularLastCacheHandle.isToken("  " + CachedTabularLastCacheHandle.TOKEN + "  "));
    }

    @Test
    void token_rejectsNullAndUuid() {
        assertFalse(CachedTabularLastCacheHandle.isToken(null));
        assertFalse(CachedTabularLastCacheHandle.isToken("550e8400-e29b-41d4-a716-446655440000"));
        assertFalse(CachedTabularLastCacheHandle.isToken("__PARLER_LAST_QUALIFYING_TABULAR_CACHE__extra"));
    }
}
