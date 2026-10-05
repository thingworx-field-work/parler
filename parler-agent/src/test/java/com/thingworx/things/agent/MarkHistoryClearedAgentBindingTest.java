package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MarkHistoryClearedAgentBindingTest {

    @Test
    void nullExpected_passesRegardlessOfRowAgent() {
        assertDoesNotThrow(() -> MarkHistoryClearedAgentBinding.verifyExpectedAgentThingIfPresent(null, "OtherAgent",
                "cid", "[pfx]"));
        assertDoesNotThrow(() -> MarkHistoryClearedAgentBinding.verifyExpectedAgentThingIfPresent("", "OtherAgent",
                "cid", "[pfx]"));
        assertDoesNotThrow(() -> MarkHistoryClearedAgentBinding.verifyExpectedAgentThingIfPresent("   ", "OtherAgent",
                "cid", "[pfx]"));
    }

    @Test
    void nonNullExpected_throwsOnMismatch() {
        assertThrows(Exception.class,
                () -> MarkHistoryClearedAgentBinding.verifyExpectedAgentThingIfPresent("AgentA", "AgentB", "cid",
                        "[pfx]"));
    }

    @Test
    void nonNullExpected_passesOnMatch() {
        assertDoesNotThrow(() -> MarkHistoryClearedAgentBinding.verifyExpectedAgentThingIfPresent("AgentA", "AgentA",
                "cid", "[pfx]"));
    }
}
