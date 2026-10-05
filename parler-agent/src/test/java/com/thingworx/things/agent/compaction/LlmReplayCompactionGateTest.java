package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LlmReplayCompactionGateTest {

    @AfterEach
    void tearDown() {
        LlmReplayCompactionGate.clearAllTestHooks();
    }

    @Test
    void replayCompactionEffective_defaultOnWhenNotUnsafe() {
        LlmReplayCompactionGate.setUnsafeDiagnosticsDisableForcedForTest(false);
        assertTrue(LlmReplayCompactionGate.isReplayCompactionEffective());
    }

    @Test
    void replayCompactionEffective_testOverrideWins() {
        LlmReplayCompactionGate.setReplayCompactionEffectiveForTest(false);
        assertFalse(LlmReplayCompactionGate.isReplayCompactionEffective());
        LlmReplayCompactionGate.setReplayCompactionEffectiveForTest(true);
        assertTrue(LlmReplayCompactionGate.isReplayCompactionEffective());
    }

    @Test
    void replayCompactionEffective_unsafeForcedSuppresses() {
        LlmReplayCompactionGate.setUnsafeDiagnosticsDisableForcedForTest(true);
        assertFalse(LlmReplayCompactionGate.isReplayCompactionEffective());
    }

    @Test
    void replayCompactionEffective_testOverrideWinsOverUnsafeForced() {
        LlmReplayCompactionGate.setUnsafeDiagnosticsDisableForcedForTest(true);
        LlmReplayCompactionGate.setReplayCompactionEffectiveForTest(true);
        assertTrue(LlmReplayCompactionGate.isReplayCompactionEffective());
    }
}
