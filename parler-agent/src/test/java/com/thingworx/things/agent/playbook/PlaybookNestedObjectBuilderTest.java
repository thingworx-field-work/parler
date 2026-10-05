package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookNestedObjectBuilderTest {

    @Test
    void buildNestedObject_kpiFiltersPayload() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("c1", "p", new JSONObject());
        ctx.putNodeOutput("time_window", new JSONObject().put("output", new JSONObject()
                .put("QuickTimeIntervalUID", 3)
                .put("StartTime", JSONObject.NULL)
                .put("EndTime", JSONObject.NULL)));

        JSONObject args = new JSONObject()
                .put("sources", new JSONObject()
                        .put("equipment", new JSONArray()
                                .put(new JSONObject().put("UID", 4))
                                .put(new JSONObject().put("UID", 7)))
                        .put("criteria", new JSONArray()
                                .put(new JSONObject().put("FilterCriteria", "PRODUCT").put("UIDValue", 8))
                                .put(new JSONObject().put("FilterCriteria", "SHIFT").put("UIDValue", 4))))
                .put("template", new JSONObject()
                        .put("Filters", new JSONObject().put("$map", new JSONObject()
                                .put("over", "equipment")
                                .put("each", new JSONObject()
                                        .put("EquipmentUID", new JSONObject().put("$path", "UID"))
                                        .put("FilterCriterias", new JSONObject().put("$src", "criteria")))))
                        .put("QuickTimeIntervalUID",
                                new JSONObject().put("$nodeRef", "time_window.output.QuickTimeIntervalUID"))
                        .put("StartTime", new JSONObject().put("$nodeRef", "time_window.output.StartTime"))
                        .put("EndTime", new JSONObject().put("$nodeRef", "time_window.output.EndTime")))
                .put("maxParents", 25)
                .put("maxChildren", 100)
                .put("minParents", 1)
                .put("omitNull", true);

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("build_nested_object", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject object = out.getJSONObject("output").getJSONObject("object");
        assertEquals(3, object.getInt("QuickTimeIntervalUID"));
        assertFalse(object.has("StartTime"));
        assertFalse(object.has("EndTime"));
        JSONArray filters = object.getJSONArray("Filters");
        assertEquals(2, filters.length());
        assertEquals(4, filters.getJSONObject(0).getInt("EquipmentUID"));
        assertEquals(2, filters.getJSONObject(0).getJSONArray("FilterCriterias").length());
        assertEquals("PRODUCT",
                filters.getJSONObject(0).getJSONArray("FilterCriterias").getJSONObject(0).getString("FilterCriteria"));
    }

    @Test
    void buildNestedObject_minParents_twoRequired_oneMapped_stops() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("c5", "p", new JSONObject());
        JSONObject args = new JSONObject()
                .put("sources", new JSONObject()
                        .put("equipment", new JSONArray().put(new JSONObject().put("UID", 4))))
                .put("template", new JSONObject()
                        .put("Filters", new JSONObject().put("$map", new JSONObject()
                                .put("over", "equipment")
                                .put("each", new JSONObject().put("EquipmentUID", new JSONObject().put("$path", "UID"))))))
                .put("maxParents", 25)
                .put("maxChildren", 100)
                .put("minParents", 2);

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("build_nested_object", args, ctx);
        assertEquals("needs_clarification", out.getString("status"));
        assertTrue(out.getString("message").contains("produced 1"));
    }

    @Test
    void buildNestedObject_topLevelLiteral() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("c6", "p", new JSONObject());
        JSONObject args = new JSONObject()
                .put("sources", new JSONObject()
                        .put("equipment", new JSONArray().put(new JSONObject().put("UID", 4))))
                .put("template", new JSONObject()
                        .put("Filters", new JSONObject().put("$map", new JSONObject()
                                .put("over", "equipment")
                                .put("each", new JSONObject().put("EquipmentUID", new JSONObject().put("$path", "UID")))))
                        .put("QuickTimeIntervalUID", new JSONObject().put("$literal", 3)))
                .put("maxParents", 25)
                .put("maxChildren", 100);

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("build_nested_object", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals(3, out.getJSONObject("output").getJSONObject("object").getInt("QuickTimeIntervalUID"));
    }

    @Test
    void buildNestedObject_nodeRef_missingLeaf_omitNull() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("c7", "p", new JSONObject());
        ctx.putNodeOutput("time_window", new JSONObject().put("output", new JSONObject()
                .put("QuickTimeIntervalUID", 3)));

        JSONObject args = new JSONObject()
                .put("sources", new JSONObject()
                        .put("equipment", new JSONArray().put(new JSONObject().put("UID", 4))))
                .put("template", new JSONObject()
                        .put("Filters", new JSONObject().put("$map", new JSONObject()
                                .put("over", "equipment")
                                .put("each", new JSONObject().put("EquipmentUID", new JSONObject().put("$path", "UID")))))
                        .put("QuickTimeIntervalUID",
                                new JSONObject().put("$nodeRef", "time_window.output.QuickTimeIntervalUID"))
                        .put("StartTime", new JSONObject().put("$nodeRef", "time_window.output.StartTime")))
                .put("maxParents", 25)
                .put("maxChildren", 100)
                .put("omitNull", true);

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("build_nested_object", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject object = out.getJSONObject("output").getJSONObject("object");
        assertEquals(3, object.getInt("QuickTimeIntervalUID"));
        assertFalse(object.has("StartTime"));
    }

    @Test
    void buildNestedObject_minParents_zeroMap_stops() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("c2", "p", new JSONObject());
        JSONObject args = new JSONObject()
                .put("sources", new JSONObject()
                        .put("equipment", new JSONArray()))
                .put("template", new JSONObject()
                        .put("Filters", new JSONObject().put("$map", new JSONObject()
                                .put("over", "equipment")
                                .put("each", new JSONObject().put("EquipmentUID", new JSONObject().put("$path", "UID"))))))
                .put("maxParents", 25)
                .put("maxChildren", 100)
                .put("minParents", 1);

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("build_nested_object", args, ctx);
        assertEquals("needs_clarification", out.getString("status"));
    }

    @Test
    void jsonStringify_serializesObject() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("c3", "p", new JSONObject());
        JSONObject payload = new JSONObject().put("Filters", new JSONArray()).put("QuickTimeIntervalUID", 3);
        ctx.putNodeOutput("filters_payload", new JSONObject().put("output", new JSONObject().put("object", payload)));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("json_stringify",
                new JSONObject()
                        .put("value", new JSONObject().put("$ref", "filters_payload.output.object"))
                        .put("maxBytes", 12000),
                ctx);

        assertEquals("ok", out.getString("status"));
        JSONObject output = out.getJSONObject("output");
        assertTrue(output.getString("value").contains("\"QuickTimeIntervalUID\":3"));
        assertTrue(output.getInt("byteCount") > 0);
    }

    @Test
    void jsonStringify_exceedsMaxBytes() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("c4", "p", new JSONObject());
        ctx.putNodeOutput("big", new JSONObject().put("output", new JSONObject().put("object",
                new JSONObject().put("x", "abcdefghij".repeat(100)))));

        PlaybookRunException ex = assertThrows(PlaybookRunException.class,
                () -> PlaybookOrchestrationDeriveOps.execute("json_stringify",
                        new JSONObject()
                                .put("value", new JSONObject().put("$ref", "big.output.object"))
                                .put("maxBytes", 50),
                        ctx));
        assertEquals("JSON_STRINGIFY_TOO_LARGE", ex.failureCode());
    }
}
