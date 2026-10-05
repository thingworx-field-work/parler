package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

class MetadataDiscoveryDiscoverPropertiesTest {

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
    void missing_thing_name_returns_error() throws Exception {
        String json = MetadataDiscoveryExecutor.executeDiscoverProperties(
                new ToolCall("dp1", "discover_properties", "{}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("THINGNAME_VALUE_REQUIRED", body.path("code").asText());
    }

    @Test
    void legacy_entity_type_entity_name_shape_returns_thingname_value_required() throws Exception {
        String json = MetadataDiscoveryExecutor.executeDiscoverProperties(new ToolCall("dp2", "discover_properties",
                "{\"entityType\":\"ThingTemplate\",\"entityName\":\"SomeTemplate\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("THINGNAME_VALUE_REQUIRED", body.path("code").asText());
    }

    @Test
    void identity_resolution_envelope_matches_thingname_tool_family() throws Exception {
        String json = MetadataDiscoveryExecutor.identityResolutionRequiredForDiscoverProperties("ORD-Contacting-01");
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", body.path("code").asText());
        assertEquals("thingName", body.path("parameterName").asText());
        assertEquals("THINGNAME", body.path("expectedBaseType").asText());
        assertEquals("ORD-Contacting-01", body.path("suppliedValue").asText());
        assertEquals("resolve_thing", body.path("recoveryHint").path("tool").asText());
        assertEquals("text", body.path("recoveryHint").path("argument").asText());
        assertTrue(body.path("message").asText().contains("resolve_thing"));
    }
}
