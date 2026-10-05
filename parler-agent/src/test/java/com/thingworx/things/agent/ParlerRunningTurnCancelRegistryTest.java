package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ParlerRunningTurnCancelRegistryTest {

    @AfterEach
    void tearDown() {
        ParlerRunningTurnCancelRegistry.clearAllForTests();
    }

    @Test
    void tryRequestCancelIdempotent() {
        ParlerRunningTurnCancelRegistry.register("c1", "r1", "alice", "AgentX");
        assertEquals(1, ParlerRunningTurnCancelRegistry.tryRequestCancel("c1", "r1", "alice", "AgentX"));
        assertEquals(2, ParlerRunningTurnCancelRegistry.tryRequestCancel("c1", "r1", "alice", "AgentX"));
        assertTrue(ParlerRunningTurnCancelRegistry.isCancelRequested("c1", "r1", "alice", "AgentX"));
    }

    @Test
    void missingRegistrationReturnsZero() {
        assertEquals(0, ParlerRunningTurnCancelRegistry.tryRequestCancel("c2", "r2", "bob", "AgentY"));
        assertFalse(ParlerRunningTurnCancelRegistry.isCancelRequested("c2", "r2", "bob", "AgentY"));
    }
}
