package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookTaskProgressTest {

    @Test
    void buildSnapshot_includesPlaybookSourceAndSummary() throws Exception {
        String raw = "{"
                + "\"schema\":\"parler-playbook-v1\","
                + "\"title\":\"Cross-region operational diagnosis\","
                + "\"nodes\":["
                + "{\"id\":\"taxonomy_row\",\"kind\":\"derive\",\"dependsOn\":[],"
                + "\"op\":\"pick_taxonomy_row\",\"evidence\":{\"label\":\"Resolve asset type\"}},"
                + "{\"id\":\"final_summary\",\"kind\":\"llm_summary\",\"dependsOn\":[\"taxonomy_row\"],"
                + "\"evidence\":{\"label\":\"Final summary\"}}"
                + "],\"finalNode\":\"final_summary\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookTaskProgress progress = new PlaybookTaskProgress(PlaybookIds.V1A_PLAYBOOK_ID, doc);
        progress.onNodeStarted("taxonomy_row");
        progress.onNodeCompleted("taxonomy_row", "Matched Stacking Robot");
        JSONObject summary = progress.buildSummaryCounts();
        assertEquals(PlaybookIds.V1A_PLAYBOOK_ID, summary.getString("playbookId"));
        assertEquals(2, summary.getInt("total"));
        assertEquals(1, summary.getInt("satisfied"));
        JSONArray items = progress.buildItemsArray();
        assertEquals(2, items.length());
        assertEquals("playbook", items.getJSONObject(0).getString("source"));
        assertEquals("taxonomy_row", items.getJSONObject(0).getString("id"));
        assertEquals("satisfied", items.getJSONObject(0).getString("status"));
    }

    @Test
    void onFanOutProgress_updatesParentSummary() throws Exception {
        String raw = "{"
                + "\"schema\":\"parler-playbook-v1\",\"title\":\"T\","
                + "\"nodes\":[{\"id\":\"fan\",\"kind\":\"fan_out\",\"dependsOn\":[],"
                + "\"evidence\":{\"label\":\"Fan parent\"}}],\"finalNode\":\"fan\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookTaskProgress progress = new PlaybookTaskProgress("pb", doc);
        progress.onNodeStarted("fan");
        progress.onFanOutProgress("fan", 3, 8);
        JSONArray items = progress.buildItemsArray();
        JSONObject row = items.getJSONObject(0);
        assertEquals("in-progress", row.getString("status"));
        assertEquals("3/8 items completed", row.getString("summary"));
    }

    @Test
    void summaryFromNodeResult_llmSummaryUsesFinalAnswerHint() {
        JSONObject node = new JSONObject().put("kind", "llm_summary");
        JSONObject result = new JSONObject().put("status", "ok").put("assistantText", "Hello world");
        String summary = PlaybookTaskProgress.summaryFromNodeResult(node, result);
        assertTrue(summary.contains("Final answer ready"));
    }

    @Test
    void onTurnEnd_completedMarksPendingNodesSatisfied() throws Exception {
        String raw = "{"
                + "\"schema\":\"parler-playbook-v1\",\"title\":\"T\","
                + "\"nodes\":["
                + "{\"id\":\"a\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"x\",\"evidence\":{\"label\":\"A\"}},"
                + "{\"id\":\"b\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"x\",\"evidence\":{\"label\":\"B\"}}"
                + "],\"finalNode\":\"b\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookTaskProgress progress = new PlaybookTaskProgress("pb", doc);
        progress.onTurnEnd(true);
        assertEquals("completed", progress.turnWireStatus());
        assertEquals(2, progress.buildSummaryCounts().getInt("satisfied"));
    }
}
