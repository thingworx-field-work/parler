package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class EntityMetadataSummaryCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void stamped_get_entity_body_is_promotable_and_summary_shrinks() throws Exception {
        String raw = CompactionTestFixtures.largeGetEntityShapedToolJson();
        ObjectNode obj = (ObjectNode) MAPPER.readTree(raw);
        assertTrue(EntityMetadataSummaryCodec.isPromotableEntityMetadata(obj, "get_entity"));
        String summary = EntityMetadataSummaryCodec.toSummaryJson(obj, "get_entity", "tc-entity");
        assertTrue(summary.length() < raw.length());
        JsonNode body = MAPPER.readTree(summary);
        assertEquals(EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_SUMMARY_V1, body.path("$format").asText());
        assertEquals("ThingTemplate", body.path("entityType").asText());
        assertEquals("Tmpl.Large", body.path("entityName").asText());
        assertEquals("Base", body.path("template").asText());
        assertEquals(45, body.withArray("properties").size());
        assertEquals("Prop0", body.withArray("properties").get(0).path("name").asText());
        assertEquals("NUMBER", body.withArray("properties").get(0).path("baseType").asText());
        assertFalse(body.withArray("properties").get(0).has("aspects"));
        assertEquals(30, body.withArray("services").size());
        assertEquals("Svc0", body.withArray("services").get(0).path("name").asText());
        assertFalse(body.withArray("services").get(0).has("parameterDefinitions"));
    }

    @Test
    void discover_properties_shape_is_not_promotable() throws Exception {
        ObjectNode obj = (ObjectNode) MAPPER.readTree(CompactionTestFixtures.discoverPropertiesShapedToolJson());
        assertFalse(EntityMetadataSummaryCodec.isPromotableEntityMetadata(obj, "discover_properties"));
    }

    @Test
    void invoke_service_event_history_shape_is_not_promotable() throws Exception {
        ObjectNode obj = (ObjectNode) MAPPER.readTree(CompactionTestFixtures.invokeServiceEventHistoryShapedToolJson());
        assertFalse(EntityMetadataSummaryCodec.isPromotableEntityMetadata(obj, "invoke_service"));
        assertFalse(EntityMetadataSummaryCodec.isPromotableEntityMetadata(obj, "query_alert_history"));
    }

    @Test
    void unstamped_verbose_shape_is_not_promotable_even_for_get_entity_tool_name() throws Exception {
        String raw = CompactionTestFixtures.largeGetEntityShapedToolJson();
        ObjectNode obj = (ObjectNode) MAPPER.readTree(raw);
        obj.remove("$format");
        assertFalse(EntityMetadataSummaryCodec.isPromotableEntityMetadata(obj, "get_entity"));
        assertNull(EntityMetadataSummaryCodec.toSummaryJson(obj, "get_entity", "tc-entity"));
    }

    @Test
    void already_summarized_is_not_promotable() throws Exception {
        ObjectNode obj = MAPPER.createObjectNode();
        obj.put("$format", EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_SUMMARY_V1);
        obj.put("entityType", "Thing");
        obj.put("entityName", "X");
        assertFalse(EntityMetadataSummaryCodec.isPromotableEntityMetadata(obj, "get_entity"));
    }
}
