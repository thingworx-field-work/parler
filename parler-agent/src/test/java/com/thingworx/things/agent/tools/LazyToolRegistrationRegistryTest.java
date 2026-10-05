package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class LazyToolRegistrationRegistryTest {

    @Test
    void registeredNamesPersistAcrossRoundsUntilTurnRemoved() {
        String turn = "conv-1::rid-1";
        try {
            LazyToolRegistrationRegistry.acquireForTurn(turn).add("query_entities");
            LazyToolRegistrationRegistry.acquireForTurn(turn).add("invoke_service");

            Set<String> snap = LazyToolRegistrationRegistry.snapshotForTurn(turn);
            assertEquals(Set.of("query_entities", "invoke_service"), snap);
        } finally {
            LazyToolRegistrationRegistry.removeTurn(turn);
        }
        assertTrue(LazyToolRegistrationRegistry.snapshotForTurn(turn).isEmpty(), "removed turn has no registrations");
    }

    @Test
    void distinctTurnsAreIsolated() {
        try {
            LazyToolRegistrationRegistry.acquireForTurn("A").add("a_tool");
            LazyToolRegistrationRegistry.acquireForTurn("B").add("b_tool");
            assertEquals(Set.of("a_tool"), LazyToolRegistrationRegistry.snapshotForTurn("A"));
            assertEquals(Set.of("b_tool"), LazyToolRegistrationRegistry.snapshotForTurn("B"));
        } finally {
            LazyToolRegistrationRegistry.removeTurn("A");
            LazyToolRegistrationRegistry.removeTurn("B");
        }
    }

    @Test
    void nullOrEmptyTurnKeyIsSafe() {
        assertTrue(LazyToolRegistrationRegistry.snapshotForTurn(null).isEmpty());
        assertTrue(LazyToolRegistrationRegistry.snapshotForTurn("").isEmpty());
        // acquire with null returns a usable (detached) set, never throws.
        LazyToolRegistrationRegistry.acquireForTurn(null).add("x");
        LazyToolRegistrationRegistry.removeTurn(null);
    }
}
