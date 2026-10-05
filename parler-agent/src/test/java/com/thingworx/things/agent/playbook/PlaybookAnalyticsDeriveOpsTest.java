package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookAnalyticsDeriveOpsTest {

    @Test
    void addComputedFields_datetimeDiffMinutes() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray().put(new JSONObject()
                .put("CreatedTime", "2024-01-01T10:00:00Z")
                .put("AcknowledgeTime", "2024-01-01T10:30:00Z"));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("fields", new JSONArray().put(new JSONObject()
                        .put("as", "ackMinutes")
                        .put("expr", new JSONObject()
                                .put("op", "datetime_diff_minutes")
                                .put("left", "AcknowledgeTime")
                                .put("right", "CreatedTime"))));
        JSONObject out = PlaybookGenericDeriveOps.execute("add_computed_fields", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals(30, out.getJSONObject("output").getJSONArray("rows").getJSONObject(0).getInt("ackMinutes"));
    }

    @Test
    void addComputedFields_onNullSkip_omitsRow() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("a", 1).put("b", 2))
                .put(new JSONObject().put("a", JSONObject.NULL).put("b", 3));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("onNull", "skip")
                .put("fields", new JSONArray().put(new JSONObject()
                        .put("as", "sum")
                        .put("expr", new JSONObject().put("op", "add").put("left", "a").put("right", "b"))));
        JSONObject out = PlaybookGenericDeriveOps.execute("add_computed_fields", args, ctx);
        assertEquals(1, out.getJSONObject("output").getJSONArray("rows").length());
    }

    @Test
    void collectValues_uniqueFieldValues() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r3", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("UID", 4))
                .put(new JSONObject().put("UID", 4))
                .put(new JSONObject().put("UID", 7));
        JSONObject args = new JSONObject().put("rows", rows).put("field", "UID");
        JSONObject out = PlaybookGenericDeriveOps.execute("collect_values", args, ctx);
        JSONArray values = out.getJSONObject("output").getJSONArray("values");
        assertEquals(2, values.length());
        assertEquals(4, values.getInt(0));
        assertEquals(7, values.getInt(1));
    }

    @Test
    void joinValues_delimitedString_respectsMaxLength() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r4", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("values", new JSONArray().put("aa").put("bbb").put("c"))
                .put("delimiter", ",")
                .put("maxLength", 5);
        JSONObject out = PlaybookGenericDeriveOps.execute("join_values", args, ctx);
        JSONObject output = out.getJSONObject("output");
        assertTrue(output.getBoolean("truncated"));
        assertFalse(output.getString("value").length() > 5);
    }
}
