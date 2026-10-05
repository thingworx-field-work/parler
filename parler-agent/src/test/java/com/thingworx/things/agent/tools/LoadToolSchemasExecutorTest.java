package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.thingworx.things.agent.llm.ToolDefinition;

class LoadToolSchemasExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, ToolDefinition> byName() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, ToolDefinition> m = new LinkedHashMap<>();
        m.put("query_entities", new ToolDefinition("query_entities", "Query entities", schema));
        m.put("invoke_service", new ToolDefinition("invoke_service", "Invoke a service", schema));
        return m;
    }

    @Test
    void buildEnvelope_resolvesKnownReturnsSchemasAndRegistersNames() throws Exception {
        LoadToolSchemasExecutor.EnvelopeResult r =
                LoadToolSchemasExecutor.buildEnvelope(List.of("query_entities", "missing_tool"), byName());
        assertEquals(List.of("query_entities"), r.resolvedNames);

        JsonNode root = JSON.readTree(r.json);
        assertEquals("ok", root.path("status").asText());
        assertEquals(1, root.path("loaded").size());
        assertEquals("query_entities", root.path("loaded").get(0).path("name").asText());
        assertTrue(root.path("loaded").get(0).has("input_schema"));
        assertEquals("object", root.path("loaded").get(0).path("input_schema").path("type").asText());
        assertEquals(1, root.path("not_found").size());
        assertEquals("missing_tool", root.path("not_found").get(0).asText());
    }

    @Test
    void buildEnvelope_allUnknown_yieldsNoResolved() throws Exception {
        LoadToolSchemasExecutor.EnvelopeResult r =
                LoadToolSchemasExecutor.buildEnvelope(List.of("nope1", "nope2"), byName());
        assertTrue(r.resolvedNames.isEmpty());
        JsonNode root = JSON.readTree(r.json);
        assertEquals(0, root.path("loaded").size());
        assertEquals(2, root.path("not_found").size());
    }

    @Test
    void parseNames_readsNamesArray() {
        assertEquals(List.of("a", "b"), LoadToolSchemasExecutor.parseNames("{\"names\":[\"a\",\"b\",\" \"]}"));
    }

    @Test
    void parseNames_malformedOrMissing_yieldsEmpty() {
        assertTrue(LoadToolSchemasExecutor.parseNames(null).isEmpty());
        assertTrue(LoadToolSchemasExecutor.parseNames("").isEmpty());
        assertTrue(LoadToolSchemasExecutor.parseNames("{not json").isEmpty());
        assertTrue(LoadToolSchemasExecutor.parseNames("{\"other\":1}").isEmpty());
        assertFalse(LoadToolSchemasExecutor.parseNames("{\"names\":[\"x\"]}").isEmpty());
    }
}
