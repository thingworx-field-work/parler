package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Outer {@code discover_services} / {@code get_service_definition} on Thing targets apply
 * {@link ScalarThingnamePreflight#gateApplicationThing} before delegation (Phase D metadata; aligns with
 * {@code invoke_service} Thing path).
 */
class MetadataDiscoveryServicesThingPreflightTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
    }

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
    }

    @Test
    void discover_services_thing_empty_entityName_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "discover_services", "{\"entityType\":\"Thing\",\"entityName\":\"\"}");
        JsonNode n = MAPPER.readTree(MetadataDiscoveryExecutor.executeDiscoverServices(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("entityName", n.path("parameterName").asText());
    }

    @Test
    void discover_services_thing_missing_entityName_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "discover_services", "{\"entityType\":\"Thing\"}");
        JsonNode n = MAPPER.readTree(MetadataDiscoveryExecutor.executeDiscoverServices(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("entityName", n.path("parameterName").asText());
    }

    @Test
    void discover_services_thing_whitespace_entityName_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "discover_services", "{\"entityType\":\"Thing\",\"entityName\":\"  \\t \"}");
        JsonNode n = MAPPER.readTree(MetadataDiscoveryExecutor.executeDiscoverServices(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("entityName", n.path("parameterName").asText());
    }

    @Test
    void discover_services_thing_non_visible_entityName_returns_identity_resolution_required() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "discover_services",
                "{\"entityType\":\"Thing\",\"entityName\":\"GhostThing\"}");
        JsonNode n = MAPPER.readTree(MetadataDiscoveryExecutor.executeDiscoverServices(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("entityName", n.path("parameterName").asText());
        assertEquals("GhostThing", n.path("suppliedValue").asText());
        assertEquals("resolve_thing", n.path("recoveryHint").path("tool").asText());
    }

    @Test
    void discover_services_thingtemplate_missing_entityName_still_missing_entity_name() throws Exception {
        ToolCall call = new ToolCall("1", "discover_services", "{\"entityType\":\"ThingTemplate\"}");
        JsonNode n = MAPPER.readTree(MetadataDiscoveryExecutor.executeDiscoverServices(call));
        assertEquals("MISSING_ENTITY_NAME", n.path("code").asText());
    }

    @Test
    void get_service_definition_thing_empty_entityName_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "get_service_definition",
                "{\"entityType\":\"Thing\",\"entityName\":\"\",\"serviceName\":\"GetData\"}");
        JsonNode n = MAPPER.readTree(MetadataDiscoveryExecutor.executeGetServiceDefinition(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("entityName", n.path("parameterName").asText());
    }

    @Test
    void get_service_definition_thing_missing_entityName_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "get_service_definition",
                "{\"entityType\":\"Thing\",\"serviceName\":\"GetData\"}");
        JsonNode n = MAPPER.readTree(MetadataDiscoveryExecutor.executeGetServiceDefinition(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("entityName", n.path("parameterName").asText());
    }

    @Test
    void get_service_definition_thing_non_visible_returns_identity_resolution_required() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "get_service_definition",
                "{\"entityType\":\"Thing\",\"entityName\":\"GhostThing\",\"serviceName\":\"GetData\"}");
        JsonNode n = MAPPER.readTree(MetadataDiscoveryExecutor.executeGetServiceDefinition(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("GhostThing", n.path("suppliedValue").asText());
    }

    @Test
    void get_service_definition_thing_identity_omits_recovery_hint_when_not_recoverable() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = false;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "get_service_definition",
                "{\"entityType\":\"Thing\",\"entityName\":\"label\",\"serviceName\":\"GetData\"}");
        JsonNode n = MAPPER.readTree(MetadataDiscoveryExecutor.executeGetServiceDefinition(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertFalse(n.has("recoveryHint"));
        assertTrue(n.path("message").asText().contains("resolve_thing is not available"));
    }
}
