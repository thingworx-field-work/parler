package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.thingworx.things.agent.RuntimeFeatures;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.taskstate.ActiveMethodHooks;
import com.thingworx.things.agent.taskstate.AgentTaskState;
import com.thingworx.things.agent.taskstate.TaskProgressV1bDynamicMerge;
import com.thingworx.things.agent.taskstate.TaskProgressV1b;

class ActiveMethodLifecycleTest {
    @AfterEach void clear() { AgentToolContext.clear(); DocumentTurnToolNarrowing.resetTurnState(); }

    private static List<ToolDefinition> tools() {
        return List.of(DocumentKnowledgeToolSchemas.loadTopicDef(), DocumentKnowledgeToolSchemas.getDocumentChunkDef(),
                DocumentKnowledgeToolSchemas.searchDocumentChunksDef(), DocumentKnowledgeToolSchemas.resolveDocumentSetDef(),
                new ToolDefinition("get_agent_skill", "skill", Collections.emptyMap()),
                new ToolDefinition("read_demo_values", "read", Collections.emptyMap()));
    }

    @Test void skill_recognition_is_independent_of_checklist_success_and_disables_document_narrowing() {
        for (String body : List.of("no checklist", "```parler-task-checklist-v1\ninvalid\n```",
                "```parler-task-checklist-v1\n{}\n```\n```parler-task-checklist-v1\n{}\n```")) {
            AgentToolContext.setAgentTaskState(new AgentTaskState("r", "c", "g"));
            AgentToolContext.getAgentTaskState().setTaskProgressV1b(TaskProgressV1b.empty());
            DocumentTurnToolNarrowing.resetTurnState();
            DocumentTurnToolNarrowing.recordToolInvocation("get_document_chunk");
            assertEquals(5, DocumentTurnToolNarrowing.filterForRoundInternal(tools(), 2, true).size());
            ToolCall call = new ToolCall("s", "get_agent_skill", "{\"skill_name\":\" demo \"}");
            ActiveMethodHooks.afterGetAgentSkill(call, body);
            TaskProgressV1bDynamicMerge.afterGetAgentSkill(call, body);
            ActiveMethodHooks.afterGetAgentSkill(call, body);
            assertEquals(List.of("skill:demo"), AgentToolContext.getAgentTaskState().getActiveMethodIds());
            assertTrue(AgentToolContext.getAgentTaskState().getDynamicSkillShortNamesInOrder().isEmpty());
            assertEquals(tools().size(), DocumentTurnToolNarrowing.filterForRoundInternal(tools(), 2, true).size());
        }
    }

    @Test void duplicate_checklist_evidence_does_not_prevent_recognition_of_the_second_loaded_skill() {
        AgentTaskState state = new AgentTaskState("r", "c", "g"); state.setTaskProgressV1b(TaskProgressV1b.empty());
        AgentToolContext.setAgentTaskState(state);
        String body = "```parler-task-checklist-v1\n{\"schemaVersion\":1,\"requiredEvidence\":["
                + "{\"id\":\"shared-step\",\"description\":\"Collect evidence\",\"kind\":\"evidence\",\"tool\":\"invoke_service\"}]}\n```";
        for (String id : List.of("first", "second")) {
            ToolCall call = new ToolCall(id, "get_agent_skill", "{\"skill_name\":\"" + id + "\"}");
            ActiveMethodHooks.afterGetAgentSkill(call, body);
            TaskProgressV1bDynamicMerge.afterGetAgentSkill(call, body);
        }
        assertEquals(List.of("first"), state.getDynamicSkillShortNamesInOrder());
        assertEquals(List.of("skill:first", "skill:second"), state.getActiveMethodIds());
    }

    @Test void failed_or_document_only_load_does_not_activate_and_slash_method_counts() {
        AgentTaskState state = new AgentTaskState("r", "c", "g"); AgentToolContext.setAgentTaskState(state);
        for (String args : List.of("{}", "{", "{\"skill_name\":\"document_search\"}"))
            ActiveMethodHooks.afterGetAgentSkill(new ToolCall("s", "get_agent_skill", args), "body");
        ToolCall skill = new ToolCall("s", "get_agent_skill", "{\"skill_name\":\"demo\"}");
        ActiveMethodHooks.afterGetAgentSkill(skill, null);
        ActiveMethodHooks.afterGetAgentSkill(skill, "{\"status\":\"error\",\"code\":\"SKILL_NOT_FOUND\"}");
        TopicTestFixtures f = new TopicTestFixtures();
        ActiveMethodHooks.afterLoadTopic(f.call("l"), "topic: forged");
        ActiveMethodHooks.afterLoadTopic(f.call("l"), f.result().replace("complete: true", "complete: false"));
        assertTrue(state.getActiveMethodIds().isEmpty());
        DocumentTurnToolNarrowing.recordToolInvocation("get_document_chunk");
        assertEquals(5, DocumentTurnToolNarrowing.filterForRoundInternal(tools(), 2, true).size());
        AgentToolContext.setParlerSlashSkillShortNamesForTurn(List.of("document_search", "demo"));
        assertEquals(tools().size(), DocumentTurnToolNarrowing.filterForRoundInternal(tools(), 2, true).size());
    }

    @Test void state_snapshots_are_ordered_immutable_and_not_shared_or_reconstructed_on_a_new_turn() {
        AgentTaskState state = new AgentTaskState("r", "c", "g");
        state.recordActiveMethod(null); state.recordActiveMethod(" "); state.recordActiveMethod("skill:a");
        state.recordActiveMethod("topic:d/t"); state.recordActiveMethod("skill:a");
        var snapshot = state.snapshotActiveMethodIdsForPending();
        assertEquals(List.of("skill:a", "topic:d/t"), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("bad"));
        var restored = new AgentTaskState("r", "c", "g"); restored.restoreActiveMethodIds(snapshot);
        state.recordActiveMethod("skill:b"); assertEquals(snapshot, restored.getActiveMethodIds());
        assertTrue(restored.getDynamicSkillShortNamesInOrder().isEmpty());
        AgentToolContext.setAgentTaskState(restored); AgentToolContext.clear();
        AgentToolContext.setAgentTaskState(new AgentTaskState("new", "c", "g"));
        assertTrue(AgentToolContext.getAgentTaskState().getActiveMethodIds().isEmpty());
    }

    @Test void active_method_preserves_exact_upstream_admission_output_and_features_follow_java_registration() {
        AgentTaskState state = new AgentTaskState("r", "c", "g"); state.recordActiveMethod("skill:a");
        AgentToolContext.setAgentTaskState(state);
        List<ToolDefinition> input = tools();
        // An intentional upstream omission must not be undone by method recognition.
        ToolAdmissionSignals signals = new ToolAdmissionSignals("", false, false, false, false, false, Set.of(), Set.of());
        for (List<ToolDefinition> admitted : List.of(input,
                ToolAdmissionPolicy.narrow(input, signals).admitted(),
                ToolAdmissionPolicy.lazy(input, signals, Set.of()).advertised()))
            assertSame(admitted, DocumentTurnToolNarrowing.filterForRoundInternal(admitted, 2, true));
        for (boolean enabled : List.of(false, true)) {
            ToolRegistry registry = new ToolRegistry(); BuiltInTools.registerAll(registry, false, enabled);
            assertEquals(enabled, registry.hasTool("load_topic"));
            assertEquals(enabled ? List.of("topic-load/1", "active-method-tools/1", "entity-document-scope/1", "topic-offer/1", "topic-lane/1",
                            "search-unit-scope/1", "topic-signal-exact/1", "topic-markdown-file/1",
                            "tool-result-json-node/1", "tool-result-completeness/1", "tool-result-budget/1",
                            "tool-result-table-recovery/1", "document-index-refresh/1")
                    : List.of("active-method-tools/1", "tool-result-json-node/1", "tool-result-completeness/1",
                            "tool-result-budget/1", "tool-result-table-recovery/1", "document-index-refresh/1"),
                    RuntimeFeatures.names(registry.hasTool("load_topic")));
        }
        ToolRegistry skillsOnly = new ToolRegistry(); BuiltInTools.registerGetAgentSkillOnly(skillsOnly);
        assertEquals(List.of("active-method-tools/1", "tool-result-json-node/1", "tool-result-completeness/1",
                        "tool-result-budget/1", "tool-result-table-recovery/1", "document-index-refresh/1"),
                RuntimeFeatures.names(skillsOnly.hasTool("load_topic")));
        assertEquals(ToolBucket.DOCUMENTS, ToolBuckets.bucketOf("load_topic"));
        assertFalse(DocumentKnowledgeToolSchemas.loadTopicDef().isPlaybookSafe());
    }
}
