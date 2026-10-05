package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.tools.AgentToolContext;

class PlaybookInternalArtifactEmissionOrderTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void hasParlerStreamWireIds_requiresBothNonEmpty() {
        assertFalse(PlaybookInternalArtifactEmissionOrder.hasParlerStreamWireIds(null, "r"));
        assertFalse(PlaybookInternalArtifactEmissionOrder.hasParlerStreamWireIds("q", null));
        assertFalse(PlaybookInternalArtifactEmissionOrder.hasParlerStreamWireIds("", "r"));
        assertFalse(PlaybookInternalArtifactEmissionOrder.hasParlerStreamWireIds("q", ""));
        assertTrue(PlaybookInternalArtifactEmissionOrder.hasParlerStreamWireIds("q", "r"));
    }

    @Test
    void shouldEmitLiveParlerWire_requiresRemoteAndDownlinkTrue() {
        AtomicBoolean ok = new AtomicBoolean(false);
        assertFalse(PlaybookInternalArtifactEmissionOrder.shouldEmitLiveParlerWire(null, ok));
        assertFalse(PlaybookInternalArtifactEmissionOrder.shouldEmitLiveParlerWire(null, null));
        ok.set(true);
        assertFalse(PlaybookInternalArtifactEmissionOrder.shouldEmitLiveParlerWire(null, ok));
    }

    @Test
    void persistThenMaybeEmitParlerWire_appendRunsWhenLiveGateFalse() {
        List<String> trace = new ArrayList<>();
        PlaybookInternalArtifactEmissionOrder.persistThenMaybeEmitParlerWire(
                () -> trace.add("append"), () -> false, () -> trace.add("emit"));
        assertEquals(List.of("append"), trace);
    }

    @Test
    void persistThenMaybeEmitParlerWire_emitRunsAfterAppendWhenLiveGateTrue() {
        List<String> trace = new ArrayList<>();
        PlaybookInternalArtifactEmissionOrder.persistThenMaybeEmitParlerWire(
                () -> trace.add("append"), () -> true, () -> trace.add("emit"));
        assertEquals(List.of("append", "emit"), trace);
    }

    @Test
    void productionStyleGate_skipsEmitWhenDownlinkFalse() {
        List<String> trace = new ArrayList<>();
        AtomicBoolean ok = new AtomicBoolean(false);
        PlaybookInternalArtifactEmissionOrder.persistThenMaybeEmitParlerWire(
                () -> trace.add("append"),
                () -> PlaybookInternalArtifactEmissionOrder.shouldEmitLiveParlerWire(null, ok),
                () -> trace.add("emit"));
        assertEquals(List.of("append"), trace);
    }
}
