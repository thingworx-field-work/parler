package com.thingworx.things.agent.taskstate;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolCall;

/** Deferred matching when multiple checklist rows share structural {@code match} but differ on {@code resultKind}. */
class TaskProgressV1bResultKindDeferTest {

    @Test
    void distinctResultKinds_deferredThenResolvedByEvidence() {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        JSONArray req = new JSONArray();
        JSONObject mTab = new JSONObject();
        mTab.put("targetName", "T1");
        mTab.put("resultKind", "tabular");
        JSONObject rowTab = new JSONObject();
        rowTab.put("id", "e_tab");
        rowTab.put("description", "tabular");
        rowTab.put("kind", "evidence");
        rowTab.put("tool", "invoke_service");
        rowTab.put("match", mTab);
        JSONObject mChart = new JSONObject();
        mChart.put("targetName", "T1");
        mChart.put("resultKind", "chart");
        JSONObject rowChart = new JSONObject();
        rowChart.put("id", "e_chart");
        rowChart.put("description", "chart");
        rowChart.put("kind", "evidence");
        rowChart.put("tool", "invoke_service");
        rowChart.put("match", mChart);
        req.put(rowTab);
        req.put(rowChart);
        root.put("requiredEvidence", req);

        TaskProgressV1b v = TaskProgressV1b.fromChecklist(root);
        String args = "{\"entityType\":\"Thing\",\"entityName\":\"T1\",\"serviceName\":\"Run\"}";
        ToolCall tc = new ToolCall("call-def", "invoke_service", args);
        v.onBeforeTool(tc);

        JSONArray mid = v.buildItemsArrayForWire();
        Assertions.assertEquals("in-progress", mid.getJSONObject(0).getString("status"));
        Assertions.assertEquals("in-progress", mid.getJSONObject(1).getString("status"));

        AgentTaskEvidence ev = new AgentTaskEvidence(
                "ev1", 1, "call-def", "call-def", "invoke_service", "ok", System.currentTimeMillis());
        ev.setResultKind("tabular");
        v.onAfterTool(tc, "{\"status\":\"success\",\"resultKind\":\"tabular\"}", ev);

        JSONArray done = v.buildItemsArrayForWire();
        Assertions.assertEquals("satisfied", done.getJSONObject(0).getString("status"));
        Assertions.assertEquals("pending", done.getJSONObject(1).getString("status"));
    }

    @Test
    void sameResultKindRequirement_bindsFirstRowOnly() {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        JSONArray req = new JSONArray();
        JSONObject m = new JSONObject();
        m.put("targetName", "T1");
        m.put("resultKind", "tabular");
        JSONObject rowA = new JSONObject();
        rowA.put("id", "a");
        rowA.put("description", "first");
        rowA.put("kind", "evidence");
        rowA.put("tool", "invoke_service");
        rowA.put("match", m);
        JSONObject rowB = new JSONObject();
        rowB.put("id", "b");
        rowB.put("description", "second");
        rowB.put("kind", "evidence");
        rowB.put("tool", "invoke_service");
        rowB.put("match", new JSONObject(m.toString()));
        req.put(rowA);
        req.put(rowB);
        root.put("requiredEvidence", req);

        TaskProgressV1b v = TaskProgressV1b.fromChecklist(root);
        String args = "{\"entityType\":\"Thing\",\"entityName\":\"T1\",\"serviceName\":\"Run\"}";
        ToolCall tc = new ToolCall("c2", "invoke_service", args);
        v.onBeforeTool(tc);

        JSONArray arr = v.buildItemsArrayForWire();
        Assertions.assertEquals("in-progress", arr.getJSONObject(0).getString("status"));
        Assertions.assertEquals("pending", arr.getJSONObject(1).getString("status"));
    }
}
