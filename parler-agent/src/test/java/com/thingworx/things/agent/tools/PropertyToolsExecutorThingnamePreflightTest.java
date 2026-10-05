package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Phase A scalar {@code thingName} preflight for property tools (visibility-aware gate + shared envelope).
 */
class PropertyToolsExecutorThingnamePreflightTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
        AgentToolContext.clear();
    }

    @Test
    void get_property_values_whitespace_only_thingName_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "get_property_values",
                "{\"thingName\":\"   \\t  \",\"propertyNames\":[\"Temperature\"]}");
        JsonNode n = MAPPER.readTree(PropertyToolsExecutor.executeGetPropertyValues(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("thingName", n.path("parameterName").asText());
    }

    @Test
    void get_property_values_blank_thingName_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "get_property_values",
                "{\"thingName\":\"\",\"propertyNames\":[\"Temperature\"]}");
        JsonNode n = MAPPER.readTree(PropertyToolsExecutor.executeGetPropertyValues(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("thingName", n.path("parameterName").asText());
    }

    @Test
    void get_property_values_non_canonical_returns_identity_resolution_required_with_recovery_hint() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "get_property_values",
                "{\"thingName\":\"ORD Contacting 01\",\"propertyNames\":[\"Temperature\"]}");
        JsonNode n = MAPPER.readTree(PropertyToolsExecutor.executeGetPropertyValues(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("thingName", n.path("parameterName").asText());
        assertEquals("ORD Contacting 01", n.path("suppliedValue").asText());
        assertEquals("resolve_thing", n.path("recoveryHint").path("tool").asText());
    }

    @Test
    void get_property_values_identity_when_predicate_visible_but_no_platform_thing_handle() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = "Ghost"::equals;
        ToolCall call = new ToolCall("1", "get_property_values",
                "{\"thingName\":\"Ghost\",\"propertyNames\":[\"Temperature\"]}");
        JsonNode n = MAPPER.readTree(PropertyToolsExecutor.executeGetPropertyValues(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("thingName", n.path("parameterName").asText());
    }

    @Test
    void get_property_values_identity_omits_recovery_hint_when_resolve_thing_not_recoverable() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = false;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "get_property_values",
                "{\"thingName\":\"display label\",\"propertyNames\":[\"Temperature\"]}");
        JsonNode n = MAPPER.readTree(PropertyToolsExecutor.executeGetPropertyValues(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertFalse(n.has("recoveryHint"));
        assertTrue(n.path("message").asText().contains("resolve_thing is not available"));
    }

    @Test
    void query_property_history_non_canonical_returns_identity_resolution_required() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "query_property_history",
                "{\"thingName\":\"display label\",\"propertyName\":\"temperature\"}");
        JsonNode n = MAPPER.readTree(PropertyToolsExecutor.executeQueryPropertyHistory(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("thingName", n.path("parameterName").asText());
    }
}
