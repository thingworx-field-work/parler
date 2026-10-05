package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookOptionalBranchDeriveOpsTest {

    @Test
    void emptyRowsIfSkipped_branchSkipped() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("ob1", "p", new JSONObject());
        ctx.skipNodes(Set.of("product_lookup"));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("empty_rows_if_skipped",
                new JSONObject().put("sourceNodeId", "product_lookup").put("label", "product"),
                ctx);

        assertEquals("ok", out.getString("status"));
        JSONObject output = out.getJSONObject("output");
        assertTrue(output.getBoolean("skipped"));
        assertEquals(0, output.getJSONArray("rows").length());
    }

    @Test
    void emptyRowsIfSkipped_branchRan_passesRows() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("ob2", "p", new JSONObject());
        ctx.putNodeOutput("product_lookup", new JSONObject().put("output", new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("FilterCriteria", "PRODUCT")))));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("empty_rows_if_skipped",
                new JSONObject().put("sourceNodeId", "product_lookup"),
                ctx);

        assertEquals("ok", out.getString("status"));
        assertFalse(out.getJSONObject("output").getBoolean("skipped"));
        assertEquals(1, out.getJSONObject("output").getJSONArray("rows").length());
    }

    @Test
    void buildNestedObject_nodeRefSkippedBranch_omitNull() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("ob3", "p", new JSONObject());
        ctx.skipNodes(Set.of("time_window"));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("build_nested_object",
                new JSONObject()
                        .put("sources", new JSONObject()
                                .put("equipment", new JSONArray().put(new JSONObject().put("UID", 4))))
                        .put("template", new JSONObject()
                                .put("Filters", new JSONObject().put("$map", new JSONObject()
                                        .put("over", "equipment")
                                        .put("each", new JSONObject().put("EquipmentUID", new JSONObject().put("$path", "UID")))))
                                .put("QuickTimeIntervalUID",
                                        new JSONObject().put("$nodeRef", "time_window.output.QuickTimeIntervalUID")))
                        .put("maxParents", 25)
                        .put("maxChildren", 100)
                        .put("omitNull", true),
                ctx);

        assertEquals("ok", out.getString("status"));
        JSONObject object = out.getJSONObject("output").getJSONObject("object");
        assertFalse(object.has("QuickTimeIntervalUID"));
    }
}
