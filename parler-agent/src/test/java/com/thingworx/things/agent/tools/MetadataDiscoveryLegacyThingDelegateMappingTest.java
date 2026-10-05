package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.relationships.RelationshipTypes;

/**
 * Offline regression tests for Thing-targeted legacy discovery delegation: pure JSON remaps from
 * {@code discover_thing_members} inner payloads to {@code discover_properties} / {@code discover_services} /
 * {@code get_service_definition} wire shapes and legacy error codes — no {@code EntityUtilities.findEntity}.
 */
class MetadataDiscoveryLegacyThingDelegateMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
    }

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
    }

    private static ServiceTargetEntityTypeResolution thingResolution() {
        return ServiceTargetEntityTypeResolution.ok(RelationshipTypes.ThingworxRelationshipTypes.Thing, "Thing", false,
                null, null);
    }

    @Test
    void discover_properties_success_maps_items_and_property_source() throws Exception {
        ObjectNode inner = MAPPER.createObjectNode();
        inner.put("status", "success");
        inner.put("entityType", "Thing");
        inner.put("entityName", "ResolvedThing");
        inner.put("facet", "properties");
        ArrayNode items = MAPPER.createArrayNode();
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", "Temperature");
        row.put("baseType", "NUMBER");
        items.add(row);
        inner.set("items", items);
        inner.put("hasMore", true);
        inner.put("offset", 0);
        inner.put("totalMatched", 50);
        inner.put("returned", 1);

        String out = MetadataDiscoveryExecutor.discoverPropertiesLegacyResponseFromInner(inner.toString(), "TIn");
        JsonNode body = MAPPER.readTree(out);
        assertEquals("success", body.path("status").asText());
        assertEquals("Thing", body.path("entityType").asText());
        assertEquals("ResolvedThing", body.path("entityName").asText());
        assertEquals("discover_thing_members", body.path("propertySource").asText());
        assertEquals(1, body.path("properties").size());
        assertEquals("Temperature", body.path("properties").get(0).path("name").asText());
        assertTrue(body.path("hasMore").asBoolean());
        assertEquals(50, body.path("totalMatched").asInt());
    }

    @Test
    void discover_properties_thing_not_found_maps_to_identity_resolution() throws Exception {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("status", "error");
        err.put("code", "THING_NOT_FOUND_OR_NOT_VISIBLE");
        err.put("message", "Thing not found");
        String out = MetadataDiscoveryExecutor.discoverPropertiesLegacyResponseFromInner(err.toString(), "LooseName");
        JsonNode body = MAPPER.readTree(out);
        assertEquals("error", body.path("status").asText());
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", body.path("code").asText());
        assertEquals("LooseName", body.path("suppliedValue").asText());
    }

    @Test
    void discover_services_success_maps_items_to_services_array() throws Exception {
        ObjectNode inner = MAPPER.createObjectNode();
        inner.put("status", "success");
        inner.put("entityName", "T1");
        ArrayNode items = MAPPER.createArrayNode();
        ObjectNode svcRow = MAPPER.createObjectNode();
        svcRow.put("name", "GetData");
        svcRow.put("description", "d");
        items.add(svcRow);
        inner.set("items", items);
        inner.put("hasMore", false);
        inner.put("offset", 0);
        inner.put("totalMatched", 1);
        inner.put("returned", 1);

        String out = MetadataDiscoveryExecutor.discoverServicesThingResponseFromInner(inner.toString(),
                thingResolution(), "T1");
        JsonNode body = MAPPER.readTree(out);
        assertEquals("success", body.path("status").asText());
        assertEquals("Thing", body.path("entityType").asText());
        assertEquals(1, body.path("services").size());
        assertEquals("GetData", body.path("services").get(0).path("name").asText());
    }

    @Test
    void discover_services_thing_not_found_maps_to_identity_resolution_required() throws Exception {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("status", "error");
        err.put("code", "THING_NOT_FOUND_OR_NOT_VISIBLE");
        err.put("message", "no such thing");
        String out = MetadataDiscoveryExecutor.discoverServicesThingResponseFromInner(err.toString(), thingResolution(),
                "X");
        JsonNode body = MAPPER.readTree(out);
        assertEquals("error", body.path("status").asText());
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", body.path("code").asText());
        assertEquals("entityName", body.path("parameterName").asText());
        assertEquals("X", body.path("suppliedValue").asText());
    }

    @Test
    void get_service_definition_success_flattens_service_and_invoke_example() throws Exception {
        ObjectNode inner = MAPPER.createObjectNode();
        inner.put("status", "success");
        ObjectNode svc = MAPPER.createObjectNode();
        svc.put("name", "RestartThing");
        svc.put("description", "restart");
        svc.set("parameters", MAPPER.createArrayNode());
        ObjectNode res = MAPPER.createObjectNode();
        res.put("baseType", "NOTHING");
        svc.set("result", res);
        inner.set("service", svc);
        ObjectNode invoke = MAPPER.createObjectNode();
        invoke.put("tool", "invoke_service");
        inner.set("invokeExample", invoke);

        String out = MetadataDiscoveryExecutor.getServiceDefinitionThingResponseFromInner(inner.toString(),
                thingResolution(), "T1", "RestartThing");
        JsonNode body = MAPPER.readTree(out);
        assertEquals("success", body.path("status").asText());
        assertEquals("RestartThing", body.path("serviceName").asText());
        assertEquals("invoke_service", body.path("invokeExample").path("tool").asText());
        assertTrue(body.has("parameters"));
        assertTrue(body.has("result"));
    }

    @Test
    void get_service_definition_service_not_found_maps_to_service_not_found() throws Exception {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("status", "error");
        err.put("code", "SERVICE_NOT_FOUND_OR_NOT_VISIBLE");
        err.put("message", "missing svc");
        String out = MetadataDiscoveryExecutor.getServiceDefinitionThingResponseFromInner(err.toString(),
                thingResolution(), "T1", "NoSuch");
        JsonNode body = MAPPER.readTree(out);
        assertEquals("error", body.path("status").asText());
        assertEquals("SERVICE_NOT_FOUND", body.path("code").asText());
        assertTrue(body.path("message").asText().contains("NoSuch"));
    }

    @Test
    void get_service_definition_thing_not_found_maps_to_identity_resolution_required() throws Exception {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("status", "error");
        err.put("code", "THING_NOT_FOUND_OR_NOT_VISIBLE");
        err.put("message", "nope");
        String out = MetadataDiscoveryExecutor.getServiceDefinitionThingResponseFromInner(err.toString(),
                thingResolution(), "T1", "GetData");
        JsonNode body = MAPPER.readTree(out);
        assertEquals("error", body.path("status").asText());
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", body.path("code").asText());
        assertEquals("entityName", body.path("parameterName").asText());
        assertEquals("T1", body.path("suppliedValue").asText());
    }

    @Test
    void get_service_definition_thing_not_found_prefers_inner_supplied_value() throws Exception {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("status", "error");
        err.put("code", "THING_NOT_FOUND_OR_NOT_VISIBLE");
        err.put("message", "nope");
        err.put("suppliedValue", "InnerLabel");
        String out = MetadataDiscoveryExecutor.getServiceDefinitionThingResponseFromInner(err.toString(),
                thingResolution(), "T1", "GetData");
        JsonNode body = MAPPER.readTree(out);
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", body.path("code").asText());
        assertEquals("InnerLabel", body.path("suppliedValue").asText());
    }
}
