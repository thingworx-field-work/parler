package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FetchCachedReplayGuardTest {

    @Test
    void incrementsPerCacheIdWithinSameTurnKey() {
        String tk = "c\u0001r1";
        FetchCachedReplayGuard.beginTurn(tk);
        assertEquals(1, FetchCachedReplayGuard.incrementAndGetOrdinal(tk, "cache-a"));
        assertEquals(2, FetchCachedReplayGuard.incrementAndGetOrdinal(tk, "cache-a"));
        assertEquals(3, FetchCachedReplayGuard.incrementAndGetOrdinal(tk, "cache-a"));
        assertEquals(1, FetchCachedReplayGuard.incrementAndGetOrdinal(tk, "cache-b"));
    }

    @Test
    void beginTurnResetsCountsForSameKey() {
        String tk = "c2\u0001r2";
        FetchCachedReplayGuard.beginTurn(tk);
        assertEquals(1, FetchCachedReplayGuard.incrementAndGetOrdinal(tk, "x"));
        assertEquals(2, FetchCachedReplayGuard.incrementAndGetOrdinal(tk, "x"));
        FetchCachedReplayGuard.beginTurn(tk);
        assertEquals(1, FetchCachedReplayGuard.incrementAndGetOrdinal(tk, "x"));
    }

    @Test
    void endTurnRemovesCounterMap() {
        String tk = "c3\u0001r3";
        FetchCachedReplayGuard.beginTurn(tk);
        assertEquals(1, FetchCachedReplayGuard.incrementAndGetOrdinal(tk, "z"));
        FetchCachedReplayGuard.endTurn(tk);
        assertEquals(1, FetchCachedReplayGuard.incrementAndGetOrdinal(tk, "z"));
    }
}
