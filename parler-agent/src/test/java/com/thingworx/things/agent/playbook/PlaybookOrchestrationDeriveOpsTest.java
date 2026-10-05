package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookOrchestrationDeriveOpsTest {

    @Test
    void normalizeResolvedThings_allResolved() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r1", "test", new JSONObject());
        JSONArray children = new JSONArray()
                .put(child("AMU CNC Mill", successMatch("TDD.FSU.CNCMill", "AMU CNC Mill")))
                .put(child("Other", successMatch("T.Other", "Other")));
        ctx.putNodeOutput("resolve_eq", new JSONObject().put("status", "ok").put("children", children));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("normalize_resolved_things",
                new JSONObject().put("fanOutNodeId", "resolve_eq").put("maxRows", 25), ctx);

        assertEquals("ok", out.getString("status"));
        assertEquals(2, out.getJSONObject("output").getJSONArray("rows").length());
        assertEquals(0, out.getJSONObject("output").getJSONArray("gaps").length());
        assertEquals(2, out.getJSONObject("output").getInt("totalCount"));
    }

    @Test
    void normalizeResolvedThings_oneAmbiguous_gapMode() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r2", "test", new JSONObject());
        JSONArray children = new JSONArray()
                .put(child("Good", successMatch("T.Good", "Good")))
                .put(child("Bad", ambiguous()));
        ctx.putNodeOutput("resolve_eq", new JSONObject().put("status", "ok").put("children", children));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("normalize_resolved_things",
                new JSONObject().put("fanOutNodeId", "resolve_eq").put("maxRows", 25), ctx);

        assertEquals("ok", out.getString("status"));
        assertEquals(1, out.getJSONObject("output").getJSONArray("rows").length());
        assertEquals(1, out.getJSONObject("output").getJSONArray("gaps").length());
    }

    @Test
    void normalizeResolvedThings_clarifyMode_stops() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r3", "test", new JSONObject());
        JSONArray children = new JSONArray().put(child("Bad", ambiguous()));
        ctx.putNodeOutput("resolve_eq", new JSONObject().put("status", "ok").put("children", children));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("normalize_resolved_things",
                new JSONObject().put("fanOutNodeId", "resolve_eq").put("onUnresolved", "clarify").put("maxRows", 25),
                ctx);

        assertEquals("needs_clarification", out.getString("status"));
        assertEquals(2, out.optJSONArray("candidates").length());
    }

    @Test
    void normalizeResolvedThings_minResolvedRows_preTruncation() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r4", "test", new JSONObject());
        JSONArray children = new JSONArray()
                .put(child("Only", successMatch("T.Only", "Only")));
        ctx.putNodeOutput("resolve_eq", new JSONObject().put("status", "ok").put("children", children));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("normalize_resolved_things",
                new JSONObject().put("fanOutNodeId", "resolve_eq").put("minResolvedRows", 2).put("maxRows", 25), ctx);

        assertEquals("needs_clarification", out.getString("status"));
        assertTrue(out.getString("message").contains("at least 2"));
    }

    @Test
    void extractFromToolOutput_fanOutChildren_uidPreset() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r5", "test", new JSONObject());
        JSONObject item = new JSONObject().put("input", "AMU CNC Mill").put("name", "TDD.FSU.CNCMill");
        JSONObject toolOut = new JSONObject().put("properties", new JSONArray()
                .put(new JSONObject().put("name", "UID").put("value", 42))
                .put(new JSONObject().put("name", "State").put("value", "Running")));
        JSONArray children = new JSONArray()
                .put(new JSONObject().put("item", item).put("status", "ok").put("toolOutput", toolOut));
        ctx.putNodeOutput("uid_reads", new JSONObject().put("status", "ok").put("children", children));

        JSONObject args = new JSONObject()
                .put("sourceNodeId", "uid_reads")
                .put("mode", "fan_out_children")
                .put("arrayPath", "properties")
                .put("maxRows", 25)
                .put("where", new JSONObject().put("field", "name").put("op", "eq").put("right", "UID"))
                .put("fields", new JSONArray()
                        .put(new JSONObject().put("from", "value").put("as", "UID").put("type", "number"))
                        .put(new JSONObject().put("from", "$parent.item.input").put("as", "input"))
                        .put(new JSONObject().put("from", "$parent.item.name").put("as", "thingName")));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("extract_from_tool_output", args, ctx);

        assertEquals("ok", out.getString("status"));
        JSONObject row = out.getJSONObject("output").getJSONArray("rows").getJSONObject(0);
        assertEquals(42, row.getDouble("UID"), 0.001);
        assertEquals("AMU CNC Mill", row.getString("input"));
        assertEquals("TDD.FSU.CNCMill", row.getString("thingName"));
    }

    @Test
    void extractFromToolOutput_singleMode_getPropertyValues() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r6", "test", new JSONObject());
        JSONObject toolOut = new JSONObject().put("properties", new JSONArray()
                .put(new JSONObject().put("name", "UID").put("value", 7)));
        ctx.putNodeOutput("read_uid", new JSONObject().put("status", "ok").put("toolOutput", toolOut));

        JSONObject args = new JSONObject()
                .put("sourceNodeId", "read_uid")
                .put("mode", "single")
                .put("arrayPath", "properties")
                .put("maxRows", 10)
                .put("where", new JSONObject().put("field", "name").put("op", "eq").put("right", "UID"))
                .put("fields", new JSONArray().put(new JSONObject().put("from", "value").put("as", "UID")));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("extract_from_tool_output", args, ctx);
        assertEquals(1, out.getJSONObject("output").getJSONArray("rows").length());
    }

    @Test
    void extractFromToolOutput_missingUid_recordsStructuredGap() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r7", "test", new JSONObject());
        JSONObject item = new JSONObject().put("input", "AMU CNC Mill").put("name", "TDD.FSU.CNCMill");
        JSONObject toolOut = new JSONObject().put("properties", new JSONArray()
                .put(new JSONObject().put("name", "State").put("value", "Running")));
        JSONArray children = new JSONArray()
                .put(new JSONObject().put("item", item).put("status", "ok").put("toolOutput", toolOut));
        ctx.putNodeOutput("uid_reads", new JSONObject().put("status", "ok").put("children", children));

        JSONObject args = uidExtractArgs(false);
        JSONObject out = PlaybookOrchestrationDeriveOps.execute("extract_from_tool_output", args, ctx);

        assertEquals("ok", out.getString("status"));
        assertEquals(0, out.getJSONObject("output").getJSONArray("rows").length());
        JSONObject gap = out.getJSONObject("output").getJSONArray("gaps").getJSONObject(0);
        assertEquals("no_where_match", gap.getString("code"));
        assertEquals("AMU CNC Mill", gap.getString("input"));
    }

    @Test
    void extractFromToolOutput_missingUid_clarifyMode() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r8", "test", new JSONObject());
        JSONObject item = new JSONObject().put("input", "AMU CNC Mill").put("name", "TDD.FSU.CNCMill");
        JSONObject toolOut = new JSONObject().put("properties", new JSONArray()
                .put(new JSONObject().put("name", "State").put("value", "Running")));
        JSONArray children = new JSONArray()
                .put(new JSONObject().put("item", item).put("status", "ok").put("toolOutput", toolOut));
        ctx.putNodeOutput("uid_reads", new JSONObject().put("status", "ok").put("children", children));

        JSONObject args = uidExtractArgs(true);
        JSONObject out = PlaybookOrchestrationDeriveOps.execute("extract_from_tool_output", args, ctx);

        assertEquals("needs_clarification", out.getString("status"));
    }

    private static JSONObject uidExtractArgs(boolean clarify) {
        JSONObject args = new JSONObject()
                .put("sourceNodeId", "uid_reads")
                .put("mode", "fan_out_children")
                .put("arrayPath", "properties")
                .put("maxRows", 25)
                .put("where", new JSONObject().put("field", "name").put("op", "eq").put("right", "UID"))
                .put("fields", new JSONArray()
                        .put(new JSONObject().put("from", "value").put("as", "UID").put("type", "number"))
                        .put(new JSONObject().put("from", "$parent.item.input").put("as", "input")));
        if (clarify) {
            args.put("onUnresolved", "clarify");
        }
        return args;
    }

    private static JSONObject child(String input, JSONObject toolOutput) {
        return new JSONObject()
                .put("item", new JSONObject().put("input", input))
                .put("status", "ok")
                .put("toolOutput", toolOutput);
    }

    private static JSONObject successMatch(String name, String displayName) {
        return new JSONObject()
                .put("status", "success")
                .put("resultKind", "THING_RESOLVED_INLINE")
                .put("matches", new JSONArray().put(new JSONObject().put("name", name).put("displayName", displayName)));
    }

    private static JSONObject ambiguous() {
        return new JSONObject()
                .put("status", "success")
                .put("resultKind", "THING_RESOLVED_INLINE")
                .put("matches", new JSONArray()
                        .put(new JSONObject().put("name", "T1"))
                        .put(new JSONObject().put("name", "T2")));
    }
}
