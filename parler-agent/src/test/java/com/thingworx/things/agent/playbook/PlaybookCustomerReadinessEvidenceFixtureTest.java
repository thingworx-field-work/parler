package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Slice C acceptance: packaged fixtures produce compact evidence via real projection helpers (not
 * hand-seeded echo lines).
 */
class PlaybookCustomerReadinessEvidenceFixtureTest {

    @Test
    void crossAssetPairHealth_pairAssetsProjection_includesResolvedNamesWithinBudget() throws Exception {
        PlaybookDocument doc = load("/playbook-packaging-fixture/cross_asset_pair_health/playbook.json");
        PlaybookRunContext ctx = new PlaybookRunContext("ev1", doc.playbookId(), new JSONObject());
        JSONArray assets = new JSONArray()
                .put(new JSONObject().put("name", "Plant.LineX-RobotA").put("slot", "A"))
                .put(new JSONObject().put("name", "Plant.LineX-RobotB").put("slot", "B"));
        JSONObject pairOut = new JSONObject().put("status", "ok").put("output", new JSONObject().put("assets", assets));
        PlaybookNodeEvidence.attach(pairOut, PlaybookNodeEvidence.pairAssetsLines(assets));
        ctx.putNodeOutput("pair_assets", pairOut);
        int maxBytes = 8000;
        String evidence = PlaybookEvidenceFormatter.format(doc, ctx, new JSONArray().put("pair_assets"), maxBytes);
        assertFalse(evidence.isBlank());
        assertTrue(evidence.contains("Plant.LineX-RobotA"), evidence);
        assertTrue(evidence.contains("Plant.LineX-RobotB"), evidence);
        assertTrue(evidence.getBytes(StandardCharsets.UTF_8).length <= maxBytes);
    }

    @Test
    void alarmEvents_rawToolOutput_projectsConfiguredRootFields() throws Exception {
        PlaybookDocument doc = load("/playbook-34-35-fixture/alarm_events/playbook.json");
        JSONObject alarmNode = doc.nodesById().get("alarm_events");
        alarmNode.put("evidence", new JSONObject()
                .put("includeToolOutputRootFields", new JSONArray().put("rowCount").put("appliedStartTime")));
        PlaybookRunContext ctx = new PlaybookRunContext("ev2", doc.playbookId(), new JSONObject());
        ctx.putNodeOutput("alarm_events", new JSONObject()
                .put("status", "ok")
                .put("toolOutput", new JSONObject()
                        .put("status", "success")
                        .put("rowCount", 5)
                        .put("appliedStartTime", "2026-06-01T00:00:00Z")));
        int maxBytes = 4000;
        String evidence = PlaybookEvidenceFormatter.format(doc, ctx, new JSONArray().put("alarm_events"), maxBytes);
        assertTrue(evidence.contains("rowCount=5"), evidence);
        assertTrue(evidence.contains("appliedStartTime=2026-06-01T00:00:00Z"), evidence);
        assertTrue(evidence.getBytes(StandardCharsets.UTF_8).length <= maxBytes);
    }

    @Test
    void kpiValues_timeWindowDerive_projectsEvidenceLines() throws Exception {
        PlaybookDocument doc = load("/playbook-34-35-fixture/kpi_values/playbook.json");
        PlaybookRunContext ctx = new PlaybookRunContext("ev3", doc.playbookId(),
                new JSONObject().put("timeWindow", "today"));
        ctx.putNodeOutput("quick_intervals", new JSONObject().put("status", "ok").put("output", new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("name", "Today").put("uid", 1)))));
        JSONObject args = doc.nodesById().get("time_window").getJSONObject("args");
        JSONObject out = PlaybookDeriveOps.execute("resolve_time_window_for_playbook", args, ctx);
        ctx.putNodeOutput("time_window", out);
        int maxBytes = 4000;
        String evidence = PlaybookEvidenceFormatter.format(doc, ctx, new JSONArray().put("time_window"), maxBytes);
        assertTrue(out.has("evidenceLines"));
        assertTrue(evidence.contains("time_window") || evidence.contains("window"), evidence);
        assertTrue(evidence.getBytes(StandardCharsets.UTF_8).length <= maxBytes);
    }

    private static PlaybookDocument load(String resource) throws Exception {
        try (InputStream in = PlaybookCustomerReadinessEvidenceFixtureTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing " + resource);
            return PlaybookDocument.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
