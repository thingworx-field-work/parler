package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class M1aEditableDescriptionMetricsTest {

    @Test
    void countsTopLevelAndSchemaDescriptions_notBusinessPropertyNames() {
        LinkedHashMap<String, Object> inner = new LinkedHashMap<>();
        inner.put("type", "string");
        inner.put("description", "Human label for description field");

        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", inner);
        properties.put("value", Map.of("type", "number", "description", "Measured value"));

        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "Root schema note",
                "properties", properties);

        assertEquals("Root schema note".length() + "Human label for description field".length()
                + "Measured value".length(),
                M1aEditableDescriptionMetrics.parametersSchemaEditableDescriptionChars(schema));
    }

    @Test
    void countsBareDescriptionSchema_andPropertyNamedDefault() {
        Map<String, Object> bare = Map.of("description", "Only annotation");
        assertEquals("Only annotation".length(),
                M1aEditableDescriptionMetrics.parametersSchemaEditableDescriptionChars(bare));

        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "default", Map.of("type", "string", "description", "Default slot label")));
        assertEquals("Default slot label".length(),
                M1aEditableDescriptionMetrics.parametersSchemaEditableDescriptionChars(schema));
    }

    @Test
    void projectionPreservesBusinessPropertyNamedDescription_andDefsDescriptions() throws Exception {
        LinkedHashMap<String, Object> inner = new LinkedHashMap<>();
        inner.put("type", "string");
        inner.put("description", "Human label");

        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", inner);

        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", "Root schema note");
        schema.put("properties", properties);
        schema.put("$defs", Map.of("row", Map.of("type", "object", "description", "Row shape")));

        Map<String, Object> projected = M1aDescriptionProjection.stripEditableDescriptions(schema);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.valueToTree(projected);
        assertTrue(node.at("/properties/description/type").asText().equals("string"));
        assertTrue(node.at("/properties/description/description").isMissingNode());
        assertTrue(node.at("/$defs/row/description").isMissingNode());
        assertTrue(node.at("/$defs/row/type").asText().equals("object"));
    }

    @Test
    void literalDefaultValue_isNotStripped() throws Exception {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "mode", Map.of("type", "string", "default", "auto", "description", "Mode selector")));
        Map<String, Object> projected = M1aDescriptionProjection.stripEditableDescriptions(schema);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.valueToTree(projected);
        assertEquals("auto", node.at("/properties/mode/default").asText());
        assertTrue(node.at("/properties/mode/description").isMissingNode());
    }
}
