package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Phase 5 (thing-member-discovery): compatibility coverage for the three legacy discovery tools that delegate to
 * {@link DiscoverThingMembersExecutor} on Thing paths. Pins stable error envelopes via {@link ToolRegistry#executeTool}
 * on code paths that do not require a live {@code EntityUtilities} stack (offline Gradle cannot load
 * {@code ThingWorxEntityManager}; {@link ScalarThingnamePreflight#modelVisibleThingNamePredicateForTests} and related
 * test seams supply predicate / recoverability behavior without platform {@code Thing} construction). With
 * {@code legacy-discovery-executor-only}, the three names default to {@linkplain ToolRegistry#registerExecutorOnly
 * executor-only} for merged LLM advertisement while remaining executable.
 */
class ThingMemberDiscoveryPhase5WireCompatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void registry_default_hides_legacy_metadata_tools_from_llm_definitions() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        assertFalse(reg.hasTool("discover_properties"));
        assertFalse(reg.hasTool("discover_services"));
        assertFalse(reg.hasTool("get_service_definition"));
        assertTrue(reg.hasTool("discover_thing_members"));
        assertTrue(reg.getExecutorOnlyAliases().contains("discover_properties"));
        assertTrue(reg.getExecutorOnlyAliases().contains("discover_services"));
        assertTrue(reg.getExecutorOnlyAliases().contains("get_service_definition"));
    }

    @Test
    void registry_advertise_legacy_restores_two_service_tools_to_llm_definitions() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, true);
        assertFalse(reg.hasTool("discover_properties"));
        assertTrue(reg.hasTool("discover_services"));
        assertTrue(reg.hasTool("get_service_definition"));
        assertTrue(reg.getExecutorOnlyAliases().contains("discover_properties"));
        assertFalse(reg.getExecutorOnlyAliases().contains("discover_services"));
        assertFalse(reg.getExecutorOnlyAliases().contains("get_service_definition"));
    }

    @Test
    void discover_properties_missing_thing_name_envelope_via_registry() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg);
        String json = reg.executeTool(new ToolCall("w1", "discover_properties", "{}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("THINGNAME_VALUE_REQUIRED", body.path("code").asText());
        assertEquals("thingName", body.path("parameterName").asText());
        assertTrue(body.path("message").asText().length() > 0);
    }

    @Test
    void discover_services_missing_entity_type_envelope_via_registry() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg);
        String json = reg.executeTool(new ToolCall("w2", "discover_services", "{\"entityName\":\"X\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("MISSING_ENTITY_TYPE", body.path("code").asText());
    }

    @Test
    void get_service_definition_missing_service_name_envelope_via_registry() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg);
        String json = reg.executeTool(new ToolCall("w3", "get_service_definition",
                "{\"entityType\":\"Thing\",\"entityName\":\"SomeThing\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("MISSING_SERVICE_NAME", body.path("code").asText());
    }
}
