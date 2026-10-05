package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentTaskStateRendererTest {

    @Test
    void zero_row_invoke_renders_rows_not_not_found() {
        AgentTaskState st = new AgentTaskState("r1", "c1", "get data");
        ToolCallArgs tc = new ToolCallArgs("call-1", "invoke_service", "{\"entityType\":\"Thing\","
                + "\"entityName\":\"T.DT\",\"serviceName\":\"GetDataTableEntries\"}");
        st.beginTrackedTool(tc.toCall(), "invoke_service");
        AgentTaskEvidence row = st.findRowForCompletion(tc.toCall());
        row.setStatus("ok");
        row.setResultKind("INFOTABLE");
        row.setRowCount(0);
        row.setTotalCount(0);
        row.setTotalCountInferred(false);
        row.setCompletenessStatus(
                com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.COMPLETE);
        st.finishRow(row);
        String md = AgentTaskStateRenderer.render(st);
        assertTrue(md.contains("0 rows"));
        assertFalse(md.toLowerCase().contains("not found"));
        assertTrue(md.contains("EvidenceAssessment: status=NO_FINDING completeness=COMPLETE"));
        assertFalse(md.contains("Final-answer evidence rule:"));
        assertFalse(md.contains("Treat Recent Tool Evidence as authoritative for this turn."));
    }

    @Test
    void sentinel_in_goal_allowed_once_not_in_evidence_from_parser() {
        AgentTaskState st = new AgentTaskState("r", "c", "prefix topsecret-password-value suffix");
        com.thingworx.things.agent.llm.ToolCall tc =
                new ToolCallArgs("x", "invoke_service", "{\"entityType\":\"Thing\",\"entityName\":\"X\"}").toCall();
        st.beginTrackedTool(tc, "invoke_service");
        AgentTaskEvidence row = st.findRowForCompletion(tc);
        String json = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rowCount\":1,"
                + "\"rows\":[{\"pw\":\"topsecret-password-value\"}],\"columns\":[]}";
        TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, json);
        st.finishRow(row);
        String md = AgentTaskStateRenderer.render(st);
        int n = 0;
        int i = 0;
        while ((i = md.indexOf("topsecret-password-value", i)) >= 0) {
            n++;
            i += "topsecret-password-value".length();
        }
        assertEquals(1, n);
    }

    @Test
    void evidence_line_truncated_for_long_target_path() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            longName.append('A');
        }
        AgentTaskState st = new AgentTaskState("r", "c", "");
        com.thingworx.things.agent.llm.ToolCall tc = new com.thingworx.things.agent.llm.ToolCall("c1", "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"" + longName + "\",\"serviceName\":\"S\"}");
        st.beginTrackedTool(tc, "invoke_service");
        AgentTaskEvidence row = st.findRowForCompletion(tc);
        row.setStatus("ok");
        row.setRowCount(0);
        st.finishRow(row);
        String md = AgentTaskStateRenderer.render(st);
        assertTrue(md.contains("... truncated"));
    }

    @Test
    void error_row_renders_extended_error_code_name() {
        AgentTaskState st = new AgentTaskState("r", "c", "");
        com.thingworx.things.agent.llm.ToolCall tc =
                new com.thingworx.things.agent.llm.ToolCall("c1", "invoke_service", "{}");
        st.beginTrackedTool(tc, "invoke_service");
        AgentTaskEvidence row = st.findRowForCompletion(tc);
        row.setStatus("error");
        row.setErrorCode(TaskStateErrorCode.CACHE_MISS);
        st.finishRow(row);
        String md = AgentTaskStateRenderer.render(st);
        assertTrue(md.contains("CACHE_MISS"));
    }

    @Test
    void omits_oldest_evidence_when_over_max_rows() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        for (int i = 0; i < 31; i++) {
            com.thingworx.things.agent.llm.ToolCall tc =
                    new com.thingworx.things.agent.llm.ToolCall("id" + i, "invoke_service", "{}");
            st.beginTrackedTool(tc, "invoke_service");
            AgentTaskEvidence row = st.findRowForCompletion(tc);
            row.setStatus("ok");
            row.setRowCount(i);
            st.finishRow(row);
        }
        String md = AgentTaskStateRenderer.render(st);
        assertTrue(md.contains("Older evidence omitted: 1 rows"));
        assertFalse(md.contains("- e1 "));
        assertFalse(md.contains("Final-answer evidence rule:"));
        assertTrue(TaskStateLlmInjector.FRAMING_PREFIX.length() + md.length()
                        <= AgentTaskState.DEFAULT_MAX_TASK_STATE_CHARS,
                "the complete framed provider row remains within its existing block cap");
    }

    @Test
    void maxConfiguredEvidenceFixture_measuresCompleteFramedRowAt6500Chars() {
        AgentTaskState st = new AgentTaskState("r", "c", "g".repeat(400));
        for (int i = 0; i < 31; i++) {
            com.thingworx.things.agent.llm.ToolCall tc = new com.thingworx.things.agent.llm.ToolCall(
                    "id" + i,
                    "invoke_service",
                    "{\"entityType\":\"Thing\",\"entityName\":\"" + "N".repeat(400)
                            + "\",\"serviceName\":\"S\"}");
            st.beginTrackedTool(tc, "invoke_service");
            AgentTaskEvidence row = st.findRowForCompletion(tc);
            row.setStatus("ok");
            row.setRowCount(i);
            st.finishRow(row);
        }

        String rendered = AgentTaskStateRenderer.render(st);
        int emittedChars = TaskStateLlmInjector.FRAMING_PREFIX.length() + rendered.length();
        assertEquals(6500, emittedChars,
                "30 capped evidence lines, capped goal, assessment, omission note, and framing");
        assertTrue(emittedChars <= AgentTaskState.DEFAULT_MAX_TASK_STATE_CHARS);
    }

    /** Minimal {@link com.thingworx.things.agent.llm.ToolCall} stand-in for tests — package-private accessor pattern */
    private static final class ToolCallArgs {
        private final String id;
        private final String fn;
        private final String args;

        ToolCallArgs(String id, String fn, String args) {
            this.id = id;
            this.fn = fn;
            this.args = args;
        }

        com.thingworx.things.agent.llm.ToolCall toCall() {
            return new com.thingworx.things.agent.llm.ToolCall(id, fn, args);
        }
    }
}
