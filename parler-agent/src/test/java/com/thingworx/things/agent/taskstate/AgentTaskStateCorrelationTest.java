package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolCall;

class AgentTaskStateCorrelationTest {

    @Test
    void multiple_tools_unique_correlation_keys() {
        AgentTaskState st = new AgentTaskState("r", "c", "goal");
        ToolCall a = new ToolCall("id-a", "invoke_service", "{}");
        ToolCall b = new ToolCall("id-b", "invoke_service", "{}");
        st.beginTrackedTool(a, "invoke_service");
        st.beginTrackedTool(b, "invoke_service");
        assertEquals(2, st.getEvidenceRows().size());
        assertNotEquals(
                st.getEvidenceRows().get(0).getCorrelationKey(),
                st.getEvidenceRows().get(1).getCorrelationKey());
    }

    @Test
    void synthetic_key_when_no_provider_id() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        ToolCall t = new ToolCall("", "fetch_cached_result", "{\"cacheId\":\"x\"}");
        st.beginTrackedTool(t, "fetch_cached_result");
        AgentTaskEvidence row = st.findRowForCompletion(t);
        assertNotNull(row);
        assertTrue(row.getCorrelationKey().startsWith("seq:"));
    }

    @Test
    void hitl_seed_preserves_gated_call_id() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        ToolCall gated = new ToolCall("gated-call-9", "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"T1\",\"serviceName\":\"S\"}");
        String json = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rowCount\":0,\"rows\":[],\"columns\":[]}";
        st.addSeededInvokeServiceRow(gated, json);
        assertEquals(1, st.getEvidenceRows().size());
        AgentTaskEvidence e = st.getEvidenceRows().get(0);
        assertEquals("gated-call-9", e.getCorrelationKey());
        assertEquals("gated-call-9", e.getToolCallId());
        assertEquals(0, e.getRowCount());
    }
}
