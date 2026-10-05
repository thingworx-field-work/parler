package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class ConsecutiveIdenticalToolCallTrackerTest {

    @BeforeEach
    @AfterEach
    void resetPerfCounter() {
        AgentToolContext.resetLlmTurnPerformanceFlagsForAgentLoop();
    }

    @Test
    void argumentKeyOrderDoesNotChangeHashBucket() {
        ConsecutiveIdenticalToolCallTracker t = new ConsecutiveIdenticalToolCallTracker();
        JsonNode a1 = ConsecutiveIdenticalToolCallTracker.parseArgumentsForHashing("{\"b\":1,\"a\":2}");
        JsonNode a2 = ConsecutiveIdenticalToolCallTracker.parseArgumentsForHashing("{\"a\":2,\"b\":1}");
        assertEquals(Optional.empty(), t.interceptThirdIdentical("my_tool", a1));
        t.recordCompletion("my_tool", a1, "{\"ok\":true}");
        assertEquals(Optional.empty(), t.interceptThirdIdentical("my_tool", a2));
        t.recordCompletion("my_tool", a2, "{\"ok\":true}");
        Optional<String> blocked = t.interceptThirdIdentical("my_tool", a1);
        assertTrue(blocked.isPresent());
        assertTrue(blocked.get().contains("REPETITION_BLOCKED"));
        assertEquals(1, AgentToolContext.getRepetitionBlockedCountForTurnPerf());
    }

    @Test
    void divergentToolResultResetsIdenticalStreak() {
        ConsecutiveIdenticalToolCallTracker t = new ConsecutiveIdenticalToolCallTracker();
        JsonNode args = ConsecutiveIdenticalToolCallTracker.parseArgumentsForHashing("{}");
        t.recordCompletion("x", args, "A");
        t.recordCompletion("x", args, "B");
        assertEquals(Optional.empty(), t.interceptThirdIdentical("x", args));
        t.recordCompletion("x", args, "B");
        assertTrue(t.interceptThirdIdentical("x", args).isPresent());
    }

    @Test
    void resultKeyOrderDoesNotResetIdenticalStreak() {
        ConsecutiveIdenticalToolCallTracker t = new ConsecutiveIdenticalToolCallTracker();
        JsonNode args = ConsecutiveIdenticalToolCallTracker.parseArgumentsForHashing("{}");
        assertEquals(Optional.empty(), t.interceptThirdIdentical("discover_x", args));
        t.recordCompletion("discover_x", args, "{\"b\":1,\"a\":2}");
        assertEquals(Optional.empty(), t.interceptThirdIdentical("discover_x", args));
        t.recordCompletion("discover_x", args, "{\"a\":2,\"b\":1}");
        assertTrue(t.interceptThirdIdentical("discover_x", args).isPresent());
    }

    @Test
    void removeTurnThenAcquireReturnsNewTrackerInstance() {
        String k = FetchCachedReplayGuard.turnKey("cid-test", "rid-test");
        ConsecutiveIdenticalToolCallTracker first = ConsecutiveIdenticalToolCallRegistry.acquireForTurn(k);
        JsonNode args = ConsecutiveIdenticalToolCallTracker.parseArgumentsForHashing("{}");
        first.recordCompletion("tool", args, "{}");
        ConsecutiveIdenticalToolCallRegistry.removeTurn(k);
        ConsecutiveIdenticalToolCallTracker second = ConsecutiveIdenticalToolCallRegistry.acquireForTurn(k);
        assertNotSame(first, second);
    }

    @Test
    void registryReturnsSameTrackerForSameTurnKey() {
        String k = "turn-key-test";
        ConsecutiveIdenticalToolCallTracker a = ConsecutiveIdenticalToolCallRegistry.acquireForTurn(k);
        ConsecutiveIdenticalToolCallTracker b = ConsecutiveIdenticalToolCallRegistry.acquireForTurn(k);
        assertSame(a, b);
        ConsecutiveIdenticalToolCallRegistry.removeTurn(k);
    }
}
