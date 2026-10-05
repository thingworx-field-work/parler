package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookMergeRowSetsDeriveOpsTest {

    private static JSONObject mergeArgs(JSONArray sources, int maxSources, int maxRows) {
        return new JSONObject()
                .put("sources", sources)
                .put("maxSources", maxSources)
                .put("maxRows", maxRows);
    }

    @Test
    void mergeRowSets_concatenatesInSourceOrder() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("mrs1", "p", new JSONObject());
        ctx.putNodeOutput("a", new JSONObject().put("output", new JSONObject()
                .put("rows", new JSONArray()
                        .put(new JSONObject().put("k", "a1"))
                        .put(new JSONObject().put("k", "a2")))));
        ctx.putNodeOutput("b", new JSONObject().put("output", new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("k", "b1")))));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("merge_row_sets",
                mergeArgs(new JSONArray()
                        .put(new JSONObject().put("$ref", "a.output.rows"))
                        .put(new JSONObject().put("$ref", "b.output.rows")),
                        8, 100),
                ctx);

        assertEquals("ok", out.getString("status"));
        JSONObject output = out.getJSONObject("output");
        assertEquals(3, output.getInt("totalCount"));
        assertEquals(3, output.getInt("returned"));
        assertEquals(2, output.getJSONArray("sourceCounts").getInt(0));
        assertEquals(1, output.getJSONArray("sourceCounts").getInt(1));
        JSONArray rows = output.getJSONArray("rows");
        assertEquals("a1", rows.getJSONObject(0).getString("k"));
        assertEquals("a2", rows.getJSONObject(1).getString("k"));
        assertEquals("b1", rows.getJSONObject(2).getString("k"));
    }

    @Test
    void mergeRowSets_emptySource_emitsZeroCountAndGap() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("mrs2", "p", new JSONObject());
        ctx.putNodeOutput("empty", new JSONObject().put("output", new JSONObject().put("rows", new JSONArray())));
        ctx.putNodeOutput("one", new JSONObject().put("output", new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("v", 1)))));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("merge_row_sets",
                mergeArgs(new JSONArray()
                        .put(new JSONObject().put("$ref", "empty.output.rows"))
                        .put(new JSONObject().put("$ref", "one.output.rows")),
                        8, 100),
                ctx);

        JSONObject output = out.getJSONObject("output");
        assertEquals(1, output.getInt("totalCount"));
        assertEquals(1, output.getInt("returned"));
        assertEquals(0, output.getJSONArray("sourceCounts").getInt(0));
        assertEquals(1, output.getJSONArray("sourceCounts").getInt(1));
        boolean hasEmptyGap = false;
        JSONArray gaps = output.getJSONArray("gaps");
        for (int i = 0; i < gaps.length(); i++) {
            if (gaps.getString(i).contains("source[0] empty")) {
                hasEmptyGap = true;
            }
        }
        assertTrue(hasEmptyGap);
    }

    @Test
    void mergeRowSets_truncatesToMaxRows() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("mrs3", "p", new JSONObject());
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 5; i++) {
            rows.put(new JSONObject().put("i", i));
        }
        ctx.putNodeOutput("src", new JSONObject().put("output", new JSONObject().put("rows", rows)));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("merge_row_sets",
                mergeArgs(new JSONArray().put(new JSONObject().put("$ref", "src.output.rows")), 8, 3),
                ctx);

        JSONObject output = out.getJSONObject("output");
        assertEquals(5, output.getInt("totalCount"));
        assertEquals(3, output.getInt("returned"));
        assertEquals(3, output.getJSONArray("rows").length());
        boolean hasTruncGap = false;
        for (int i = 0; i < output.getJSONArray("gaps").length(); i++) {
            if (output.getJSONArray("gaps").getString(i).contains("truncated output rows")) {
                hasTruncGap = true;
            }
        }
        assertTrue(hasTruncGap);
    }

    @Test
    void mergeRowSets_sourceSpecWithExtraKey_rejected() {
        PlaybookRunContext ctx = new PlaybookRunContext("mrs4", "p", new JSONObject());
        assertThrows(PlaybookRunException.class, () -> PlaybookOrchestrationDeriveOps.execute("merge_row_sets",
                mergeArgs(new JSONArray().put(new JSONObject()
                        .put("$ref", "a.output.rows")
                        .put("extra", 1)), 8, 100),
                ctx));
    }
}
