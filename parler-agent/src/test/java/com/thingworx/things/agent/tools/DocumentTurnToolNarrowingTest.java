package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.taskstate.AgentTaskState;

class DocumentTurnToolNarrowingTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
        DocumentTurnToolNarrowing.resetTurnState();
    }

    @Test
    void document_skill_only_slash_narrows_large_merged_list_to_document_tools() {
        AgentToolContext.setParlerSlashSkillShortNamesForTurn(List.of(DocumentTurnToolNarrowing.DOCUMENT_SKILL_SHORT_ID));
        AgentToolContext.setAgentTaskState(new AgentTaskState("", "conv", "goal"));

        List<ToolDefinition> merged = incidentSizedToolList();
        List<ToolDefinition> narrowed = DocumentTurnToolNarrowing.filterForRoundInternal(merged, 1, true);

        assertEquals(DocumentTurnToolNarrowing.NARROWED_TOOL_NAMES.size(), narrowed.size());
        assertEquals(DocumentTurnToolNarrowing.NARROWED_TOOL_NAMES,
                narrowed.stream().map(ToolDefinition::getName).collect(Collectors.toSet()));
        assertTrue(merged.size() >= 20);
    }

    @Test
    void dynamic_document_skill_only_also_narrows() {
        AgentToolContext.setAgentTaskState(new AgentTaskState("", "conv", "goal"));
        AgentToolContext.getAgentTaskState().getDynamicSkillShortNamesInOrder()
                .add(DocumentTurnToolNarrowing.DOCUMENT_SKILL_SHORT_ID);

        List<ToolDefinition> merged = incidentSizedToolList();
        List<ToolDefinition> narrowed = DocumentTurnToolNarrowing.filterForRoundInternal(merged, 1, true);

        assertEquals(DocumentTurnToolNarrowing.NARROWED_TOOL_NAMES.size(), narrowed.size());
    }

    @Test
    void multi_skill_turn_keeps_full_tool_list() {
        AgentToolContext.setParlerSlashSkillShortNamesForTurn(
                List.of(DocumentTurnToolNarrowing.DOCUMENT_SKILL_SHORT_ID, "region_health"));
        AgentToolContext.setAgentTaskState(new AgentTaskState("", "conv", "goal"));

        List<ToolDefinition> merged = incidentSizedToolList();
        List<ToolDefinition> out = DocumentTurnToolNarrowing.filterForRoundInternal(merged, 1, true);

        assertEquals(merged.size(), out.size());
    }

    @Test
    void non_document_tool_invocation_reverts_to_full_list() {
        AgentToolContext.setParlerSlashSkillShortNamesForTurn(List.of(DocumentTurnToolNarrowing.DOCUMENT_SKILL_SHORT_ID));
        AgentToolContext.setAgentTaskState(new AgentTaskState("", "conv", "goal"));

        List<ToolDefinition> merged = incidentSizedToolList();
        DocumentTurnToolNarrowing.recordToolInvocation("invoke_service");

        List<ToolDefinition> out = DocumentTurnToolNarrowing.filterForRoundInternal(merged, 2, true);
        assertEquals(merged.size(), out.size());
    }

    @Test
    void flag_off_keeps_full_list_even_for_document_skill() {
        AgentToolContext.setParlerSlashSkillShortNamesForTurn(List.of(DocumentTurnToolNarrowing.DOCUMENT_SKILL_SHORT_ID));
        AgentToolContext.setAgentTaskState(new AgentTaskState("", "conv", "goal"));

        List<ToolDefinition> merged = incidentSizedToolList();
        List<ToolDefinition> out = DocumentTurnToolNarrowing.filterForRoundInternal(merged, 1, false);

        assertEquals(merged.size(), out.size());
    }

    @Test
    void allowed_document_tools_do_not_disable_narrowing() {
        AgentToolContext.setParlerSlashSkillShortNamesForTurn(List.of(DocumentTurnToolNarrowing.DOCUMENT_SKILL_SHORT_ID));
        AgentToolContext.setAgentTaskState(new AgentTaskState("", "conv", "goal"));

        DocumentTurnToolNarrowing.recordToolInvocation("search_document_chunks");
        DocumentTurnToolNarrowing.recordToolInvocation("get_document_chunk");
        DocumentTurnToolNarrowing.recordToolInvocation("get_agent_skill");

        List<ToolDefinition> narrowed = DocumentTurnToolNarrowing.filterForRoundInternal(
                incidentSizedToolList(), 2, true);
        assertEquals(DocumentTurnToolNarrowing.NARROWED_TOOL_NAMES.size(), narrowed.size());
    }

    @Test
    void post_first_document_tool_narrows_without_slash_skill_from_round_two() {
        AgentToolContext.setAgentTaskState(new AgentTaskState("", "conv", "goal"));
        DocumentTurnToolNarrowing.recordToolInvocation("search_document_chunks");

        List<ToolDefinition> merged = incidentSizedToolList();
        assertEquals(merged.size(), DocumentTurnToolNarrowing.filterForRoundInternal(merged, 1, true).size());
        List<ToolDefinition> narrowed = DocumentTurnToolNarrowing.filterForRoundInternal(merged, 2, true);
        assertEquals(DocumentTurnToolNarrowing.NARROWED_TOOL_NAMES.size(), narrowed.size());
    }

    @Test
    void post_first_document_tool_does_not_narrow_when_non_document_tool_ran() {
        AgentToolContext.setAgentTaskState(new AgentTaskState("", "conv", "goal"));
        DocumentTurnToolNarrowing.recordToolInvocation("search_document_chunks");
        DocumentTurnToolNarrowing.recordToolInvocation("invoke_service");

        List<ToolDefinition> merged = incidentSizedToolList();
        assertEquals(merged.size(), DocumentTurnToolNarrowing.filterForRoundInternal(merged, 2, true).size());
    }

    @Test
    void empty_narrow_result_reverts_to_full_list() {
        AgentToolContext.setParlerSlashSkillShortNamesForTurn(List.of(DocumentTurnToolNarrowing.DOCUMENT_SKILL_SHORT_ID));
        AgentToolContext.setAgentTaskState(new AgentTaskState("", "conv", "goal"));

        List<ToolDefinition> merged = List.of(
                new ToolDefinition("invoke_service", "x", Collections.emptyMap()),
                new ToolDefinition("query_entities", "x", Collections.emptyMap()));

        List<ToolDefinition> out = DocumentTurnToolNarrowing.filterForRoundInternal(merged, 1, true);
        assertEquals(2, out.size());
    }

    private static List<ToolDefinition> incidentSizedToolList() {
        String[] names = {
                "invoke_service", "fetch_cached_result", "tabulate_cached_result", "analyze_entity_set",
                "summarize_cached_result", "build_chart_from_tabular_result", "discover_thing_members",
                "describe_entity_schema", "query_entities", "query_entities_by_taxonomy", "list_asset_types",
                "resolve_asset_type", "resolve_thing", "list_entities_by_type", "get_property_values",
                "query_property_history", "query_stream_data", "query_alert_summary", "query_alert_history",
                "acknowledge_alerts", "set_property_value", "spotlight_search",
                "get_agent_skill", "search_document_chunks", "get_document_chunk", "resolve_document_set",
                "start_playbook"
        };
        List<ToolDefinition> list = new ArrayList<>();
        for (String name : names) {
            list.add(new ToolDefinition(name, name, Collections.emptyMap()));
        }
        return list;
    }
}
