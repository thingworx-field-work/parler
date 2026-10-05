package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Slice B: pending Parler chart deque + chart wire emitted counter (see {@link AgentToolContext}). */
class AgentToolContextParlerChartQueueTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void drainPendingParlerChartBlocks_emptyWhenNone() {
        assertTrue(AgentToolContext.drainPendingParlerChartBlocks().isEmpty());
    }

    @Test
    void drainPendingParlerChartBlocks_fifoOrder() {
        AgentToolContext.addPendingParlerChartBlock(new JSONObject().put("k", 1));
        AgentToolContext.addPendingParlerChartBlock(new JSONObject().put("k", 2));
        List<JSONObject> d = AgentToolContext.drainPendingParlerChartBlocks();
        assertEquals(2, d.size());
        assertEquals(1, d.get(0).getInt("k"));
        assertEquals(2, d.get(1).getInt("k"));
        assertTrue(AgentToolContext.drainPendingParlerChartBlocks().isEmpty());
    }

    @Test
    void parlerChartWireEmittedCount_incrementsAndResetClears() {
        assertEquals(0, AgentToolContext.parlerChartWireEmittedCountForTurnPerf());
        AgentToolContext.markParlerChartWireEmitted();
        AgentToolContext.markParlerChartWireEmitted();
        assertEquals(2, AgentToolContext.parlerChartWireEmittedCountForTurnPerf());
        AgentToolContext.resetChartArtifactObservabilityForTurn();
        assertEquals(0, AgentToolContext.parlerChartWireEmittedCountForTurnPerf());
    }
}
