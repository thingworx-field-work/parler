package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext;

class TaskProgressV1bDynamicMergeTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void merge_appends_skill_row_and_records_short_id() {
        TaskProgressV1b v = TaskProgressV1b.empty();
        AgentTaskState st = new AgentTaskState("rid", "cid", "goal");
        st.setTaskProgressV1b(v);
        AgentToolContext.setAgentTaskState(st);

        ToolCall tc = new ToolCall("call-1", "get_agent_skill", "{\"skill_name\":\"DynSkill\"}");
        String body = "```parler-task-checklist-v1\n"
                + "{ \"schemaVersion\": 1, \"requiredEvidence\": [ { \"id\": \"dyn-step\", \"description\": \"Run tool\", "
                + "\"kind\": \"evidence\", \"tool\": \"invoke_service\" } ] }\n"
                + "```";

        v.onBeforeTool(tc);
        v.onAfterTool(tc, body, null);
        TaskProgressV1bDynamicMerge.afterGetAgentSkill(tc, body);

        assertEquals(1, st.getDynamicSkillShortNamesInOrder().size());
        assertEquals("DynSkill", st.getDynamicSkillShortNamesInOrder().get(0));
        assertTrue(v.collectSkillEvidenceIds().contains("dyn-step"));
    }

    @Test
    void error_json_skips_merge() {
        TaskProgressV1b v = TaskProgressV1b.empty();
        AgentTaskState st = new AgentTaskState("rid", "cid", "goal");
        st.setTaskProgressV1b(v);
        AgentToolContext.setAgentTaskState(st);
        ToolCall tc = new ToolCall("call-2", "get_agent_skill", "{\"skill_name\":\"X\"}");
        v.onBeforeTool(tc);
        v.onAfterTool(tc, "{\"status\":\"error\",\"message\":\"no\"}", null);
        TaskProgressV1bDynamicMerge.afterGetAgentSkill(tc, "{\"status\":\"error\",\"message\":\"no\"}");
        assertTrue(st.getDynamicSkillShortNamesInOrder().isEmpty());
    }

    @Test
    void normalized_skill_name_extracted() {
        ToolCall tc = new ToolCall("c", "get_agent_skill", "{\"skill_name\": \"  TrimMe  \"}");
        assertEquals("TrimMe", TaskProgressV1bDynamicMerge.normalizedSkillNameArgument(tc));
    }

    @Test
    void second_get_agent_skill_same_skill_is_idempotent() {
        TaskProgressV1b v = TaskProgressV1b.empty();
        AgentTaskState st = new AgentTaskState("rid", "cid", "goal");
        st.setTaskProgressV1b(v);
        AgentToolContext.setAgentTaskState(st);

        String body = "```parler-task-checklist-v1\n"
                + "{ \"schemaVersion\": 1, \"requiredEvidence\": [ { \"id\": \"dyn-step\", \"description\": \"Run\", "
                + "\"kind\": \"evidence\", \"tool\": \"invoke_service\" } ] }\n"
                + "```";
        ToolCall tc1 = new ToolCall("call-1", "get_agent_skill", "{\"skill_name\":\"DynSkill\"}");
        v.onBeforeTool(tc1);
        v.onAfterTool(tc1, body, null);
        TaskProgressV1bDynamicMerge.afterGetAgentSkill(tc1, body);

        int evidenceAfterFirst = v.collectSkillEvidenceIds().size();
        assertEquals(1, st.getDynamicSkillShortNamesInOrder().size());

        ToolCall tc2 = new ToolCall("call-2", "get_agent_skill", "{\"skill_name\":\"DynSkill\"}");
        v.onBeforeTool(tc2);
        v.onAfterTool(tc2, body, null);
        TaskProgressV1bDynamicMerge.afterGetAgentSkill(tc2, body);

        assertEquals(evidenceAfterFirst, v.collectSkillEvidenceIds().size());
        assertEquals(1, st.getDynamicSkillShortNamesInOrder().size());
    }
}
