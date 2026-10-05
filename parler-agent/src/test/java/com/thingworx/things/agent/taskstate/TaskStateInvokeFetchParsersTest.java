package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolCall;

class TaskStateInvokeFetchParsersTest {

    @Test
    void invoke_infotable_zero_rows() {
        AgentTaskEvidence row = baseRow("invoke_service");
        String json = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rowCount\":0,\"rows\":[],\"columns\":[]}";
        TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, json);
        assertEquals("ok", row.getStatus());
        assertEquals(0, row.getRowCount());
        assertNull(row.getErrorCodeEnum());
    }

    @Test
    void invoke_infotable_row_count_from_rows_length() {
        AgentTaskEvidence row = baseRow("invoke_service");
        String json = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"columns\":[],\"rows\":[{\"a\":1}]}";
        TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, json);
        assertEquals(1, row.getRowCount());
    }

    @Test
    void invoke_infotable_large_with_total_rows() {
        AgentTaskEvidence row = baseRow("invoke_service");
        String json = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE_LARGE\",\"totalRows\":100,"
                + "\"sampleRows\":[{\"x\":1}],\"cacheId\":\"cid-1\"}";
        TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, json);
        assertEquals(1, row.getRowCount());
        assertEquals(100, row.getTotalCount());
        assertFalse(row.isTotalCountInferred());
        assertTrue(row.isSampleOnly());
        assertEquals("cid-1", row.getCacheId());
    }

    @Test
    void invoke_infotable_large_missing_total_rows_inferred() {
        AgentTaskEvidence row = baseRow("invoke_service");
        String json = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE_LARGE\",\"sampleRows\":[{\"x\":1},{\"x\":2}]}";
        TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, json);
        assertEquals(2, row.getRowCount());
        assertEquals(2, row.getTotalCount());
        assertTrue(row.isTotalCountInferred());
    }

    @Test
    void invoke_error_sets_protected_false() {
        AgentTaskEvidence row = baseRow("invoke_service");
        String json = "{\"status\":\"error\",\"code\":\"PROTECTED_VALUE_OMITTED\",\"message\":\"blocked\"}";
        TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, json);
        assertEquals("error", row.getStatus());
        assertEquals(TaskStateErrorCode.PROTECTED_VALUE_OMITTED, row.getErrorCodeEnum());
        assertFalse(row.isProtectedOmissions());
    }

    @Test
    void invoke_ok_with_password_omission_flag() {
        AgentTaskEvidence row = baseRow("invoke_service");
        String json = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rowCount\":1,"
                + "\"passwordFieldsOmitted\":true,\"rows\":[{}],\"columns\":[]}";
        TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, json);
        assertEquals("ok", row.getStatus());
        assertTrue(row.isProtectedOmissions());
        assertNull(row.getErrorCodeEnum());
    }

    @Test
    void fetch_cached_success_sample_only() {
        AgentTaskEvidence row = baseRow("fetch_cached_result");
        String json = "{\"status\":\"success\",\"cacheId\":\"c1\",\"offset\":0,\"returnedRows\":10,\"totalRows\":50,"
                + "\"hasMore\":true,\"columns\":[],\"rows\":[]}";
        TaskStateInvokeFetchParsers.applyFetchCachedResult(row, json);
        assertEquals("ok", row.getStatus());
        assertEquals(10, row.getRowCount());
        assertEquals(50, row.getTotalCount());
        assertFalse(row.isTotalCountInferred());
        assertTrue(row.isSampleOnly());
    }

    @Test
    void fetch_cached_missing_total_rows_inferred() {
        AgentTaskEvidence row = baseRow("fetch_cached_result");
        String json = "{\"status\":\"success\",\"cacheId\":\"c1\",\"returnedRows\":5,\"columns\":[],\"rows\":[]}";
        TaskStateInvokeFetchParsers.applyFetchCachedResult(row, json);
        assertEquals(5, row.getTotalCount());
        assertTrue(row.isTotalCountInferred());
    }

    @Test
    void scalar_result_row_count_negative_one() {
        AgentTaskEvidence row = baseRow("invoke_service");
        String json = "{\"status\":\"success\",\"resultKind\":\"STRING\",\"value\":\"x\"}";
        TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, json);
        assertEquals("ok", row.getStatus());
        assertEquals(-1, row.getRowCount());
    }

    private static AgentTaskEvidence baseRow(String tool) {
        ToolCall tc = new ToolCall("call-1", tool, "{}");
        AgentTaskState st = new AgentTaskState("r", "c", "");
        st.beginTrackedTool(tc, tool);
        return st.findRowForCompletion(tc);
    }
}
