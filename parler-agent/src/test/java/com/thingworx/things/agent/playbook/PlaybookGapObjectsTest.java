package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

class PlaybookGapObjectsTest {

    @Test
    void normalizeForCollection_wrapsStringGaps() {
        JSONArray in = new JSONArray().put("group_by: truncated keys");
        JSONArray out = PlaybookGapObjects.normalizeForCollection(in);
        assertEquals(1, out.length());
        JSONObject row = out.getJSONObject(0);
        assertEquals(PlaybookGapObjects.CODE_GAP_NOTE, row.getString("code"));
        assertEquals("group_by: truncated keys", row.getString("message"));
    }

    @Test
    void normalizeForCollection_migratesLegacyReasonKey() {
        JSONArray in = new JSONArray().put(new JSONObject().put("reason", "not_found").put("input", "x"));
        JSONArray out = PlaybookGapObjects.normalizeForCollection(in);
        JSONObject row = out.getJSONObject(0);
        assertEquals("not_found", row.getString("code"));
        assertFalse(row.has("reason"));
        assertEquals("x", row.getString("input"));
    }

    @Test
    void normalizeForCollection_stripsLegacyKindAndType() {
        JSONArray in = new JSONArray().put(new JSONObject()
                .put("code", "CHILD_FAILED")
                .put("message", "child failed")
                .put("kind", "gap")
                .put("type", "soft"));
        JSONObject row = PlaybookGapObjects.normalizeForCollection(in).getJSONObject(0);
        assertEquals("CHILD_FAILED", row.getString("code"));
        assertFalse(row.has("kind"));
        assertFalse(row.has("type"));
    }

    @Test
    void collect_gaps_preservesCodeFromRenamedProducer() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("cgCd", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject producerGap = PlaybookGapObjects.structured("REGION_QUERY_FAILED", "Region query failed for EU");
        producerGap.put("detail", "EU");
        ctx.putNodeOutput("src", new JSONObject().put("output", new JSONObject().put("gaps",
                new JSONArray().put(producerGap))));
        JSONObject args = new JSONObject()
                .put("refs", new JSONArray().put("src.output.gaps"))
                .put("maxItems", 5);
        JSONObject out = PlaybookGenericDeriveOps.execute("collect_gaps", args, ctx);
        JSONObject merged = out.getJSONObject("output").getJSONArray("gaps").getJSONObject(0);
        assertEquals("REGION_QUERY_FAILED", merged.getString("code"));
        assertEquals("Region query failed for EU", merged.getString("message"));
        assertEquals("EU", merged.getString("detail"));

        JSONArray collection = PlaybookGapObjects.normalizeForCollection(out.getJSONObject("output").getJSONArray("gaps"));
        assertEquals("REGION_QUERY_FAILED", collection.getJSONObject(0).getString("code"));
    }

    @Test
    void buildRunOutcome_includesRunLevelFieldsAndNormalizedNodeGaps() {
        PlaybookRunContext ctx = new PlaybookRunContext("run1", "pb1", new JSONObject());
        ctx.putNodeOutput("n1", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("gaps", new JSONArray().put("truncated rows")))
                .put("evidenceLines", new JSONArray().put("ok line")));
        JSONObject diag = PlaybookGapObjects.buildRunOutcome(ctx, PlaybookRunResult.Status.COMPLETED,
                "done", null, 2, 1, 1500);
        assertEquals("pb1", diag.getString("playbookId"));
        assertEquals("completed", diag.getString("status"));
        assertEquals("done", diag.getString("message"));
        assertEquals(2, diag.getInt("toolCallCount"));
        assertEquals(1, diag.getInt("llmCallCount"));
        assertEquals(1500, diag.getLong("elapsedMs"));
        JSONObject node = diag.getJSONArray("nodes").getJSONObject(0);
        assertEquals("n1", node.getString("nodeId"));
        JSONArray gaps = node.getJSONArray("gaps");
        assertEquals(PlaybookGapObjects.CODE_GAP_NOTE, gaps.getJSONObject(0).getString("code"));
        assertTrue(node.getJSONArray("evidenceLines").getString(0).contains("ok"));
    }

    @Test
    void playbookRunResult_carriesRunOutcomeFromRunner() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunResult result = PlaybookRunner.run(doc, "t", new JSONObject(), "", "conv-1", List.of(),
                (tool, args, infotables) -> {
                    throw new UnsupportedOperationException();
                },
                new LlmClient() {
                    @Override
                    public LlmResponse chat(LlmChatRequest request) {
                        return new LlmResponse("ok", Collections.emptyList(), LlmResponse.FinishReason.STOP,
                                1, 1, 1, 1, 0, 0, 0, "r1", 0L);
                    }

                    @Override
                    public LlmUsageWireIds usageWireIds() {
                        return LlmUsageWireIds.forProviderThing("t", "p", "m", "gpt");
                    }

                    @Override
                    public boolean healthCheck() {
                        return true;
                    }
                },
                0.0, 400);
        assertEquals(PlaybookRunResult.Status.COMPLETED, result.status());
        assertTrue(result.runOutcome() != null);
        assertEquals("completed", result.runOutcome().getString("status"));
        assertTrue(result.runOutcome().has("nodes"));
    }
}
