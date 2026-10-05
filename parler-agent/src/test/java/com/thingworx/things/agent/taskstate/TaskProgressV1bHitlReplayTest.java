package com.thingworx.things.agent.taskstate;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext;

/** Regression tests for HITL v1b replay and failed-turn snapshot helpers. */
class TaskProgressV1bHitlReplayTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void replayApprovedInvoke_satisfiesChecklistRow() {
        JSONObject root = checklistOneInvoke("step1", "Run service");
        TaskProgressV1b v = TaskProgressV1b.fromChecklist(root);
        AgentTaskState st = new AgentTaskState("rid", "cid", "goal");
        st.setTaskProgressV1b(v);
        AgentToolContext.setAgentTaskState(st);

        ToolCall gated = new ToolCall(
                "g1",
                "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"E1\",\"serviceName\":\"S\"}");
        String ok = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rowCount\":2}";
        st.addSeededInvokeServiceRow(gated, ok);
        TaskProgressV1bHitlReplay.replayApprovedInvokeService(gated, ok);

        JSONObject row = v.buildItemsArrayForWire().getJSONObject(0);
        Assertions.assertEquals("satisfied", row.getString("status"));
        Assertions.assertTrue(row.optString("summary", "").contains("2") || row.optString("summary", "").contains("rows"));
    }

    @Test
    void replayTerminalDecision_marksRowFailed() {
        JSONObject root = checklistOneInvoke("step1", "Run service");
        TaskProgressV1b v = TaskProgressV1b.fromChecklist(root);
        AgentTaskState st = new AgentTaskState("rid", "cid", "goal");
        st.setTaskProgressV1b(v);
        AgentToolContext.setAgentTaskState(st);

        ToolCall gated = new ToolCall(
                "g2",
                "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"E1\",\"serviceName\":\"S\"}");
        st.addSeededHitlDecisionRow(gated, TaskStateErrorCode.HITL_CANCELLED);
        TaskProgressV1bHitlReplay.replayTerminalDecision(gated);

        JSONObject row = v.buildItemsArrayForWire().getJSONObject(0);
        Assertions.assertEquals("failed", row.getString("status"));
    }

    @Test
    void emitFailedTurnEnd_setsFailedWireStatus() {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        JSONArray req = new JSONArray();
        JSONObject syn = new JSONObject();
        syn.put("id", "syn");
        syn.put("description", "Wrap up");
        syn.put("kind", "synthesis");
        req.put(syn);
        root.put("requiredEvidence", req);

        TaskProgressV1b v = TaskProgressV1b.fromChecklist(root);
        AgentTaskState st = new AgentTaskState("rid", "cid", "goal");
        st.setTaskProgressV1b(v);
        AgentToolContext.setAgentTaskState(st);

        TaskProgressWireEmitter.emitFailedTurnEnd();
        Assertions.assertEquals("failed", v.getTurnWireStatus());
        JSONObject synRow = v.buildItemsArrayForWire().getJSONObject(0);
        Assertions.assertEquals("failed", synRow.getString("status"));
    }

    private static JSONObject checklistOneInvoke(String id, String desc) {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        JSONArray req = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("description", desc);
        item.put("kind", "evidence");
        item.put("tool", "invoke_service");
        req.put(item);
        root.put("requiredEvidence", req);
        return root;
    }
}
