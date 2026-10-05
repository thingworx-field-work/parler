package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** C3b-2a SC-9 (design §8.7): the frame order through the emitter's sender seam. */
class ParlerToolArtifactWireEmitterGroupTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    private static JSONObject pie(String chartId, String... labels) {
        JSONArray x = new JSONArray();
        JSONArray y = new JSONArray();
        for (String l : labels) {
            x.put(l);
            y.put(10);
        }
        JSONObject series = new JSONObject().put("name", "share").put("x", x).put("y", y);
        return new JSONObject().put("kind", "pie").put("chartId", chartId).put("series", new JSONArray().put(series));
    }

    @Test
    void sc9_mappingRevisionPrecedesEachMembersChartFrameAndReadyFollowsIt() {
        AgentToolContext.setConversationId("sc9");
        List<String[]> members = new ArrayList<>();
        members.add(new String[] {"a", "A"});
        members.add(new String[] {"b", "B"});
        ChartGroupState group = new ChartGroupState("g1", "T", "auto", members, "utilization state");
        AgentToolContext.tabularChartRoundState().setChartGroup(group);
        List<JSONObject> frames = new ArrayList<>();
        AtomicBoolean ok = new AtomicBoolean(true);
        // Declaration: revision 1 with an empty key list.
        ParlerToolArtifactWireEmitter.sendPendingParlerChartWires(json -> frames.add(new JSONObject(json)), "r", "c", ok);
        // Member a builds: keys appended, chart queued; the emitter runs after that tool result.
        JSONObject chartA = pie("c1", "Setup", "Down", "Running");
        group.assignChartId("a", "c1");
        group.bindMemberCategories("a", chartA);
        AgentToolContext.addPendingParlerChartBlock(chartA);
        ParlerToolArtifactWireEmitter.sendPendingParlerChartWires(json -> frames.add(new JSONObject(json)), "r", "c", ok);
        // Member b builds with a subset in another order.
        JSONObject chartB = pie("c2", "Running", "Down");
        group.assignChartId("b", "c2");
        group.bindMemberCategories("b", chartB);
        AgentToolContext.addPendingParlerChartBlock(chartB);
        ParlerToolArtifactWireEmitter.sendPendingParlerChartWires(json -> frames.add(new JSONObject(json)), "r", "c", ok);
        assertTrue(ok.get());
        List<String> types = new ArrayList<>();
        for (JSONObject f : frames) {
            types.add(f.getString("type"));
        }
        assertEquals(List.of("chart_group", "chart_group", "chart", "chart_group", "chart_group", "chart", "chart_group"), types,
                "declaration; then per member: mapping revision → chart → ready revision");
        assertEquals(7, frames.size(), "≤ 2 + 2 × members with the final revision still to come");
        int lastRevision = 0;
        for (JSONObject f : frames) {
            if ("chart_group".equals(f.getString("type"))) {
                int rev = f.getJSONObject("group").getInt("revision");
                assertTrue(rev > lastRevision, "revisions strictly increase");
                lastRevision = rev;
            }
        }
        JSONObject beforeA = frames.get(1).getJSONObject("group");
        assertEquals("[\"Setup\",\"Down\",\"Running\"]", beforeA.getJSONObject("sharedCategories").getJSONArray("keys").toString());
        assertEquals("pending", beforeA.getJSONArray("members").getJSONObject(0).getString("state"), "the mapping revision precedes ready");
        assertTrue(beforeA.getJSONArray("members").getJSONObject(0).getBoolean("colorShared"));
        JSONObject readyA = frames.get(3).getJSONObject("group");
        assertEquals("ready", readyA.getJSONArray("members").getJSONObject(0).getString("state"));
        assertEquals("c1", readyA.getJSONArray("members").getJSONObject(0).getString("chartId"));
        JSONObject beforeB = frames.get(4).getJSONObject("group");
        assertEquals("[\"Setup\",\"Down\",\"Running\"]", beforeB.getJSONObject("sharedCategories").getJSONArray("keys").toString(),
                "B's Running / Down reuse slots 2 / 1; nothing is reordered");
        assertEquals("utilization state", beforeB.getJSONObject("sharedCategories").getString("dimension"));
        // A chart that arrives with no group declared sends only the chart frame (C3b-1 behaviour unchanged).
        AgentToolContext.clear();
        AgentToolContext.setConversationId("sc9-plain");
        List<JSONObject> plain = new ArrayList<>();
        AgentToolContext.addPendingParlerChartBlock(pie("c9", "x"));
        ParlerToolArtifactWireEmitter.sendPendingParlerChartWires(json -> plain.add(new JSONObject(json)), "r", "c", new AtomicBoolean(true));
        assertEquals(1, plain.size());
        assertEquals("chart", plain.get(0).getString("type"));
    }
}
