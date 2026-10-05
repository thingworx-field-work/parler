package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.EventDefinition;
import com.thingworx.metadata.PropertyDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.ThingShapeDefinition;
import com.thingworx.metadata.collections.ServiceDefinitionCollection;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;

class DescribeEntitySchemaExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void missing_entity_type_returns_error() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(
                new ToolCall("d1", "describe_entity_schema", "{\"entityName\":\"X\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("MISSING_ENTITY_TYPE", body.path("code").asText());
    }

    @Test
    void missing_entity_name_returns_error() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(
                new ToolCall("d2", "describe_entity_schema", "{\"entityType\":\"ThingTemplate\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("MISSING_ENTITY_NAME", body.path("code").asText());
    }

    @Test
    void thing_entity_type_returns_unsupported() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(new ToolCall("d3", "describe_entity_schema",
                "{\"entityType\":\"Thing\",\"entityName\":\"SomeThing\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("UNSUPPORTED_ENTITY_TYPE_FOR_SCHEMA_DESCRIPTION", body.path("code").asText());
        assertEquals("discover_thing_members", body.path("recoveryHint").path("tool").asText());
        assertTrue(body.path("supportedEntityTypes").isArray());
    }

    @Test
    void data_table_entity_type_returns_unsupported() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(new ToolCall("d4", "describe_entity_schema",
                "{\"entityType\":\"DataTable\",\"entityName\":\"DT\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("UNSUPPORTED_ENTITY_TYPE_FOR_SCHEMA_DESCRIPTION", body.path("code").asText());
    }

    @Test
    void mashup_entity_type_returns_unsupported() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(new ToolCall("d5", "describe_entity_schema",
                "{\"entityType\":\"Mashup\",\"entityName\":\"M\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("UNSUPPORTED_ENTITY_TYPE_FOR_SCHEMA_DESCRIPTION", body.path("code").asText());
    }

    @Test
    void include_private_services_parameter_rejected() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(new ToolCall("d6", "describe_entity_schema",
                "{\"entityType\":\"ThingTemplate\",\"entityName\":\"T\",\"includePrivateServices\":false}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("UNSUPPORTED_TOOL_PARAMETER", body.path("code").asText());
    }

    @Test
    void subscriptions_facet_rejected() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(new ToolCall("d7", "describe_entity_schema",
                "{\"entityType\":\"ThingTemplate\",\"entityName\":\"T\",\"facet\":\"subscriptions\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("INVALID_FACET_FOR_ENTITY_TYPE", body.path("code").asText());
    }

    @Test
    void fields_facet_on_thing_template_invalid() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(new ToolCall("d8", "describe_entity_schema",
                "{\"entityType\":\"ThingTemplate\",\"entityName\":\"T\",\"facet\":\"fields\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("INVALID_FACET_FOR_ENTITY_TYPE", body.path("code").asText());
    }

    @Test
    void service_facet_without_member_name() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(new ToolCall("d9", "describe_entity_schema",
                "{\"entityType\":\"ThingTemplate\",\"entityName\":\"T\",\"facet\":\"service\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("MISSING_MEMBER_NAME", body.path("code").asText());
    }

    @Test
    void singular_property_facet_not_in_v1() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(new ToolCall("d10", "describe_entity_schema",
                "{\"entityType\":\"ThingTemplate\",\"entityName\":\"T\",\"facet\":\"property\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("INVALID_FACET_FOR_ENTITY_TYPE", body.path("code").asText());
        assertTrue(body.path("message").asText().contains("not implemented"));
    }

    @Test
    void invalid_scope_returns_error() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(new ToolCall("d11", "describe_entity_schema",
                "{\"entityType\":\"ThingTemplate\",\"entityName\":\"T\",\"facet\":\"summary\",\"scope\":\"bogus\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("INVALID_SCOPE", body.path("code").asText());
    }

    @Test
    void properties_facet_on_data_shape_invalid() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(new ToolCall("d12", "describe_entity_schema",
                "{\"entityType\":\"DataShape\",\"entityName\":\"DS\",\"facet\":\"properties\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("INVALID_FACET_FOR_ENTITY_TYPE", body.path("code").asText());
    }

    @Test
    void empty_arguments_returns_missing_entity_type() throws Exception {
        String json = DescribeEntitySchemaExecutor.execute(new ToolCall("d13", "describe_entity_schema", "{}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertFalse(body.path("code").asText().isEmpty());
    }

    @Test
    void listPageBounds_clampsOffsetPastTotal() {
        DescribeEntitySchemaExecutor.ListPageBounds p =
                DescribeEntitySchemaExecutor.computeListPageBounds(100, 10, 5);
        assertEquals(5, p.offset);
        assertEquals(5, p.endExclusive);
        assertEquals(0, p.returned);
    }

    @Test
    void listPageBounds_negativeOffset() {
        DescribeEntitySchemaExecutor.ListPageBounds p =
                DescribeEntitySchemaExecutor.computeListPageBounds(-5, 80, 20);
        assertEquals(0, p.offset);
        assertEquals(20, p.endExclusive);
        assertEquals(20, p.returned);
    }

    @Test
    void listPageBounds_offsetEqualsTotal() {
        DescribeEntitySchemaExecutor.ListPageBounds p =
                DescribeEntitySchemaExecutor.computeListPageBounds(3, 10, 3);
        assertEquals(3, p.offset);
        assertEquals(3, p.endExclusive);
        assertEquals(0, p.returned);
    }

    @Test
    void listPageBounds_maxItemsCappedAt200() {
        DescribeEntitySchemaExecutor.ListPageBounds p =
                DescribeEntitySchemaExecutor.computeListPageBounds(0, 9999, 1000);
        assertEquals(0, p.offset);
        assertEquals(200, p.endExclusive);
        assertEquals(200, p.returned);
    }

    @Test
    void ensureNonNullLocalInstanceShape_throws_platform_exception() {
        DescribeEntitySchemaExecutor.DescribeEntitySchemaPlatformException ex = assertThrows(
                DescribeEntitySchemaExecutor.DescribeEntitySchemaPlatformException.class,
                () -> DescribeEntitySchemaExecutor.ensureNonNullLocalInstanceShape(null, "ThingTemplate"));
        assertEquals("DESCRIBE_ENTITY_SCHEMA_PLATFORM_UNAVAILABLE", ex.getErrorCode());
    }

    @Test
    void unwrap_effective_property_definitions_extracts_collection() throws Exception {
        ThingShapeDefinition def = new ThingShapeDefinition();
        def.getPropertyDefinitions().put("p", new PropertyDefinition("p", "", BaseTypes.STRING));
        Object unwrapped = DescribeEntitySchemaExecutor.unwrapEffectivePropertyDefinitionsSource(def);
        assertEquals(def.getPropertyDefinitions(), unwrapped);
    }

    @Test
    void unwrap_effective_service_definitions_falls_back_to_all_services_when_public_empty() throws Exception {
        ThingShapeDefinition def = new ThingShapeDefinition();
        def.getServiceDefinitions().put("Priv", new ServiceDefinition("Priv", "d"));
        Object unwrapped = DescribeEntitySchemaExecutor.unwrapEffectiveServiceDefinitionsSource(def);
        assertTrue(unwrapped instanceof ServiceDefinitionCollection);
        assertEquals(1, ((ServiceDefinitionCollection) unwrapped).size());
    }

    @Test
    void unwrap_effective_event_definitions_extracts_collection() throws Exception {
        ThingShapeDefinition def = new ThingShapeDefinition();
        def.getEventDefinitions().put("E", new EventDefinition("E", "d"));
        Object unwrapped = DescribeEntitySchemaExecutor.unwrapEffectiveEventDefinitionsSource(def);
        assertEquals(def.getEventDefinitions(), unwrapped);
    }

    /** Unwrap must not swallow platform throws from shape definition getters. */
    static final class ThrowingPropertyShapeDef extends ThingShapeDefinition {
        @Override
        public com.thingworx.metadata.collections.PropertyDefinitionCollection getPropertyDefinitions() {
            throw new RuntimeException("simulated_platform_throw");
        }
    }

    @Test
    void unwrap_effective_property_definitions_propagates_shape_collection_throw() {
        assertThrows(RuntimeException.class,
                () -> DescribeEntitySchemaExecutor.unwrapEffectivePropertyDefinitionsSource(new ThrowingPropertyShapeDef()));
    }

    static final class ThrowingPublicServiceShapeDef extends ThingShapeDefinition {
        @Override
        public ServiceDefinitionCollection getPublicServiceDefinitions() {
            throw new RuntimeException("simulated_pub_services_throw");
        }
    }

    @Test
    void unwrap_effective_service_definitions_propagates_public_collection_throw() {
        assertThrows(RuntimeException.class,
                () -> DescribeEntitySchemaExecutor.unwrapEffectiveServiceDefinitionsSource(new ThrowingPublicServiceShapeDef()));
    }

    /** {@code effectiveMetadataShell} must use required reads for {@code ThingTemplate}. */
    static final class ThrowingEffectivePropertyShell {
        @SuppressWarnings("unused")
        public ThingShapeDefinition getEffectivePropertyDefinitions() throws Exception {
            throw new RuntimeException("simulated_effective_properties_throw");
        }
    }

    @Test
    void effectiveMetadataShell_thingTemplate_propagates_getEffectivePropertyDefinitions_throw() throws Exception {
        assertThrows(RuntimeException.class,
                () -> DescribeEntitySchemaExecutor.effectiveMetadataShell(new ThrowingEffectivePropertyShell(),
                        "ThingTemplate", "getEffectivePropertyDefinitions"));
    }

    static final class ThrowingEffectiveServiceShell {
        @SuppressWarnings("unused")
        public ThingShapeDefinition getEffectiveServiceDefinitions() throws Exception {
            throw new RuntimeException("simulated_effective_services_throw");
        }
    }

    @Test
    void effectiveMetadataShell_thingTemplate_propagates_getEffectiveServiceDefinitions_throw() throws Exception {
        assertThrows(RuntimeException.class,
                () -> DescribeEntitySchemaExecutor.effectiveMetadataShell(new ThrowingEffectiveServiceShell(),
                        "ThingTemplate", "getEffectiveServiceDefinitions"));
    }

    static final class ThrowingEffectiveEventShell {
        @SuppressWarnings("unused")
        public ThingShapeDefinition getEffectiveEventDefinitions() throws Exception {
            throw new RuntimeException("simulated_effective_events_throw");
        }
    }

    @Test
    void effectiveMetadataShell_thingTemplate_propagates_getEffectiveEventDefinitions_throw() throws Exception {
        assertThrows(RuntimeException.class,
                () -> DescribeEntitySchemaExecutor.effectiveMetadataShell(new ThrowingEffectiveEventShell(),
                        "ThingTemplate", "getEffectiveEventDefinitions"));
    }

    @Test
    void listPageBounds_largeOffsetClampedBeforeAdd() {
        DescribeEntitySchemaExecutor.ListPageBounds p = DescribeEntitySchemaExecutor.computeListPageBounds(
                (long) Integer.MAX_VALUE, (long) Integer.MAX_VALUE, 10);
        assertEquals(10, p.offset);
        assertEquals(10, p.endExclusive);
        assertEquals(0, p.returned);
    }
}
