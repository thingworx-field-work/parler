package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Locks the partial contract: single LLM {@link ToolDefinition} name, executor-only
 * legacy names. Extended-tool reservation vs aliases: {@code ExtendedToolsManifestTest}; alias execution is covered by
 * agent-loop integration tests.
 */
class PropertyHistoryToolRegistryAliasTest {

    @Test
    void definitions_exclude_legacy_names_and_executor_only_aliases_registered() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg);

        Set<String> defNames = reg.getAllDefinitions().stream().map(ToolDefinition::getName).collect(Collectors.toSet());
        assertTrue(defNames.contains("query_property_history"));
        assertFalse(defNames.contains("query_numeric_property_history"));
        assertFalse(defNames.contains("query_value_stream_property_history"));

        assertTrue(reg.getExecutorOnlyAliases().contains("query_numeric_property_history"));
        assertTrue(reg.getExecutorOnlyAliases().contains("query_value_stream_property_history"));

        assertTrue(reg.getExecutorOnlyAliases().contains("get_entity"));
        assertFalse(defNames.contains("get_entity"));

        assertFalse(defNames.contains("discover_properties"));
        assertFalse(defNames.contains("discover_services"));
        assertFalse(defNames.contains("get_service_definition"));
        assertTrue(reg.getExecutorOnlyAliases().contains("discover_properties"));
        assertTrue(reg.getExecutorOnlyAliases().contains("discover_services"));
        assertTrue(reg.getExecutorOnlyAliases().contains("get_service_definition"));

        assertEquals(
                Map.of(
                        "query_numeric_property_history", "query_property_history",
                        "query_value_stream_property_history", "query_property_history"),
                reg.getExecutorAliasCanonicalTargets());

        List<String> directExecutorOnly = reg.getDirectExecutorOnlyToolNames();
        assertTrue(directExecutorOnly.contains("get_entity"), directExecutorOnly::toString);
        assertTrue(directExecutorOnly.contains("discover_properties"), directExecutorOnly::toString);
        assertTrue(directExecutorOnly.contains("discover_services"), directExecutorOnly::toString);
        assertTrue(directExecutorOnly.contains("get_service_definition"), directExecutorOnly::toString);
        assertFalse(directExecutorOnly.contains("query_numeric_property_history"));
        assertFalse(directExecutorOnly.contains("query_value_stream_property_history"));
    }
}
