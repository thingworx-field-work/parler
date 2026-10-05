package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.ChartGroupState;

/**
 * C3b-1 turn end in the order every {@code AgentThing} entry point really uses: the chart group is captured,
 * {@link AgentToolContext#clear()} runs in the {@code finally} of the {@code loop.run} block, and only then is
 * the terminal branch known and {@link ChartGroupTurnHooks#onTurnEnd} called. The first live run of C3b-1 lost
 * both the {@code final} manifest frame and {@code chartGroupsJson} because the hook read the context after
 * that clear; history then replayed every member as {@code error} / {@code TURN_INCOMPLETE}.
 */
class ChartGroupTurnHooksTest {

    @AfterEach
    void tearDown() {
        ChartGroupTurnHooks.onTurnEnd(false);
        AgentToolContext.clear();
    }

    private static ChartGroupState twoMemberGroup() {
        List<String[]> members = new ArrayList<>();
        members.add(new String[] { "a", "A" });
        members.add(new String[] { "b", "B" });
        return new ChartGroupState("g1", "T", "auto", members);
    }

    /** Declared group with member {@code a} downlinked as {@code c1}; {@code b} never built. */
    private static ChartGroupState installGroupInContext() {
        ChartGroupState group = twoMemberGroup();
        AgentToolContext.tabularChartRoundState().setChartGroup(group);
        group.nextManifest(false);
        group.assignChartId("a", "c1");
        assertTrue(group.onChartDownlinked("c1"));
        group.nextManifest(false);
        return group;
    }

    /** C3b-2a SC-10 (design §8.7): the final manifest keeps the shared-colour mapping across the real turn-end order. */
    @Test
    void sc10_sharedCategoriesSurviveTheContextClearIntoTheFinalManifest() {
        List<String[]> members = new ArrayList<>();
        members.add(new String[] { "a", "A" });
        members.add(new String[] { "b", "B" });
        ChartGroupState group = new ChartGroupState("g1", "T", "auto", members, "utilization state");
        AgentToolContext.tabularChartRoundState().setChartGroup(group);
        group.nextManifest(false);
        org.json.JSONObject chart = new org.json.JSONObject().put("kind", "pie").put("chartId", "c1").put("series",
                new org.json.JSONArray().put(new org.json.JSONObject().put("name", "s")
                        .put("x", new org.json.JSONArray().put("Setup").put("Down").put("Running"))
                        .put("y", new org.json.JSONArray().put(1).put(2).put(3))));
        group.assignChartId("a", "c1");
        group.bindMemberCategories("a", chart);
        group.nextManifest(false);
        assertTrue(group.onChartDownlinked("c1"));
        group.nextManifest(false);
        ChartGroupTurnHooks.captureBeforeContextClear();
        AgentToolContext.clear();
        ChartGroupTurnHooks.onTurnEnd(false);
        String json = AgentToolContext.takeChartGroupsJsonForFinalAssistantRow();
        json = json != null ? json : "";
        assertFalse(json.isEmpty(), "the final manifest reaches the Stream row field");
        org.json.JSONObject fin = new org.json.JSONArray(json).getJSONObject(0);
        assertTrue(fin.getBoolean("final"));
        assertEquals("utilization state", fin.getJSONObject("sharedCategories").getString("dimension"));
        assertEquals("[\"Setup\",\"Down\",\"Running\"]", fin.getJSONObject("sharedCategories").getJSONArray("keys").toString());
        assertTrue(fin.getJSONArray("members").getJSONObject(0).getBoolean("colorShared"));
        assertFalse(fin.getJSONArray("members").getJSONObject(1).has("colorShared"), "the never-built member is not colour-shared");
        assertEquals("error", fin.getJSONArray("members").getJSONObject(1).getString("state"));
    }

    @Test
    void finalManifestSurvivesTheContextClearThatPrecedesTurnEnd() {
        installGroupInContext();

        ChartGroupTurnHooks.captureBeforeContextClear();
        AgentToolContext.clear();
        assertNull(AgentToolContext.tabularChartRoundStateOrNull(), "clear() drops the round state, as in production");
        ChartGroupTurnHooks.onTurnEnd(false);

        JSONArray groups = new JSONArray(AgentToolContext.takeChartGroupsJsonForFinalAssistantRow());
        assertEquals(1, groups.length());
        JSONObject fin = groups.getJSONObject(0);
        assertTrue(fin.getBoolean("final"));
        assertEquals("g1", fin.getString("groupId"));
        JSONArray members = fin.getJSONArray("members");
        assertEquals("ready", members.getJSONObject(0).getString("state"));
        assertEquals("c1", members.getJSONObject(0).getString("chartId"));
        assertEquals("error", members.getJSONObject(1).getString("state"));
        assertEquals("MEMBER_NOT_PRODUCED", members.getJSONObject(1).getString("code"));
        JSONObject summary = fin.getJSONObject("summary");
        assertEquals(1, summary.getInt("ready"));
        assertEquals(1, summary.getInt("error"));
        assertTrue(summary.getBoolean("final"));
    }

    @Test
    void groupsJsonReachesExactlyOneFinalRow() {
        installGroupInContext();
        ChartGroupTurnHooks.captureBeforeContextClear();
        AgentToolContext.clear();
        ChartGroupTurnHooks.onTurnEnd(false);

        assertFalse(AgentToolContext.takeChartGroupsJsonForFinalAssistantRow().isEmpty());
        assertEquals("", AgentToolContext.takeChartGroupsJsonForFinalAssistantRow(),
                "the read consumes the value so a later turn on this thread cannot inherit it");
    }

    @Test
    void withoutCaptureTheHookMustNotInventAGroup() {
        installGroupInContext();
        AgentToolContext.clear();
        ChartGroupTurnHooks.onTurnEnd(false);
        assertEquals("", AgentToolContext.takeChartGroupsJsonForFinalAssistantRow());
    }

    @Test
    void aTurnWithoutAGroupReplacesAnEarlierUnconsumedCapture() {
        installGroupInContext();
        ChartGroupTurnHooks.captureBeforeContextClear();
        AgentToolContext.clear();
        // The earlier turn paused for approval or threw: its hook never ran. The next turn declares no group.
        ChartGroupTurnHooks.captureBeforeContextClear();
        AgentToolContext.clear();
        ChartGroupTurnHooks.onTurnEnd(false);
        assertEquals("", AgentToolContext.takeChartGroupsJsonForFinalAssistantRow());
    }

    @Test
    void finalRevisionIsDownlinkedOnceAndCancelConvergesToCancelled() {
        ChartGroupState group = twoMemberGroup();
        group.nextManifest(false);
        List<String> sent = new ArrayList<>();
        AtomicBoolean downlinkOk = new AtomicBoolean(true);
        ChartGroupTurnHooks.TurnEndCapture capture = new ChartGroupTurnHooks.TurnEndCapture(group, downlinkOk,
                "rid-1", "conv-1", json -> sent.add(json));

        String groupsJson = ChartGroupTurnHooks.finish(capture, true);

        assertEquals(1, sent.size(), "exactly one final frame");
        JSONObject frame = new JSONObject(sent.get(0));
        assertEquals("chart_group", frame.getString("type"));
        assertEquals("rid-1", frame.getString("request_id"));
        assertEquals("conv-1", frame.getString("conversation_id"));
        JSONObject fin = frame.getJSONObject("group");
        assertTrue(fin.getBoolean("final"));
        assertEquals("cancelled", fin.getJSONArray("members").getJSONObject(0).getString("state"));
        assertEquals(fin.toString(), new JSONArray(groupsJson).getJSONObject(0).toString(),
                "the persisted manifest is the frame that went out");
        assertNull(ChartGroupTurnHooks.finish(capture, true), "a second turn end sends nothing");
        assertEquals(1, sent.size());
    }

    @Test
    void failedFinalDownlinkClearsTheFlagButStillPersists() {
        ChartGroupState group = twoMemberGroup();
        group.nextManifest(false);
        AtomicBoolean downlinkOk = new AtomicBoolean(true);
        String groupsJson = ChartGroupTurnHooks.finish(
                new ChartGroupTurnHooks.TurnEndCapture(group, downlinkOk, "r", "c", json -> false), false);
        assertFalse(downlinkOk.get());
        assertTrue(new JSONArray(groupsJson).getJSONObject(0).getBoolean("final"));
    }

    @Test
    void noDownlinkAndBrokenDownlinkSendNothing() {
        List<String> sent = new ArrayList<>();
        ChartGroupState rest = twoMemberGroup();
        rest.nextManifest(false);
        assertTrue(new JSONArray(ChartGroupTurnHooks.finish(
                new ChartGroupTurnHooks.TurnEndCapture(rest, null, null, null, null), false))
                .getJSONObject(0).getBoolean("final"), "REST chat has no downlink and still persists");
        ChartGroupState broken = twoMemberGroup();
        broken.nextManifest(false);
        ChartGroupTurnHooks.finish(new ChartGroupTurnHooks.TurnEndCapture(broken, new AtomicBoolean(false), "r",
                "c", json -> sent.add(json)), false);
        assertTrue(sent.isEmpty());
    }
}
