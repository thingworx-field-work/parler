package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolCall;

/** Regression: v1b.2 no-evidence classification for STRING tool results (e.g. {@code get_agent_skill}). */
class TaskProgressV1bNoEvidenceApplyTest {

    @Test
    void get_agent_skill_plain_text_satisfied_on_ad_hoc_row() {
        TaskProgressV1b v = TaskProgressV1b.empty();
        ToolCall tc = new ToolCall("t1", "get_agent_skill", "{\"skill_name\":\"S\"}");
        v.onBeforeTool(tc);
        v.onAfterTool(tc, "plain skill body", null);
        // Inspect via wire path: last ad-hoc should be satisfied
        org.json.JSONArray items = v.buildItemsArrayForWire();
        boolean found = false;
        for (int i = 0; i < items.length(); i++) {
            org.json.JSONObject o = items.getJSONObject(i);
            if ("get_agent_skill".equals(o.optString("tool"))) {
                assertEquals("satisfied", o.optString("status"));
                found = true;
            }
        }
        if (!found) {
            throw new AssertionError("expected ad-hoc get_agent_skill row");
        }
    }

    @Test
    void empty_body_satisfied() {
        TaskProgressV1b v = TaskProgressV1b.empty();
        ToolCall tc = new ToolCall("t2", "get_agent_skill", "{\"skill_name\":\"S\"}");
        v.onBeforeTool(tc);
        v.onAfterTool(tc, "", null);
        org.json.JSONArray items = v.buildItemsArrayForWire();
        for (int i = 0; i < items.length(); i++) {
            org.json.JSONObject o = items.getJSONObject(i);
            if ("get_agent_skill".equals(o.optString("tool"))) {
                assertEquals("satisfied", o.optString("status"));
                return;
            }
        }
        throw new AssertionError("expected get_agent_skill row");
    }
}
