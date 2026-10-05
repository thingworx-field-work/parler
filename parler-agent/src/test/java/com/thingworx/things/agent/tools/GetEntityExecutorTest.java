package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

class GetEntityExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void missing_entity_type_returns_error() throws Exception {
        String json = GetEntityExecutor.execute(new ToolCall("ge1", "get_entity", "{\"entityName\":\"X\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("MISSING_ENTITY_TYPE", body.path("code").asText());
    }

    @Test
    void missing_entity_name_returns_error() throws Exception {
        String json = GetEntityExecutor.execute(
                new ToolCall("ge2", "get_entity", "{\"entityType\":\"ThingTemplate\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("MISSING_ENTITY_NAME", body.path("code").asText());
    }

    @Test
    void thing_entity_type_returns_unsupported() throws Exception {
        String json = GetEntityExecutor.execute(new ToolCall("ge4", "get_entity",
                "{\"entityType\":\"Thing\",\"entityName\":\"SE.CellFab.Model.Workunit.ORD-Contacting-01\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("UNSUPPORTED_ENTITY_TYPE_FOR_GET_ENTITY", body.path("code").asText());
        assertEquals("entityType", body.path("parameterName").asText());
        assertEquals("discover_thing_members", body.path("recoveryHint").path("tool").asText());
        assertEquals("thingName", body.path("recoveryHint").path("argument").asText());
        assertTrue(body.path("supportedEntityTypes").isArray());
        assertTrue(body.path("supportedEntityTypes").toString().contains("ThingTemplate"));
    }

    @Test
    void mashup_entity_type_returns_unsupported() throws Exception {
        String json = GetEntityExecutor.execute(
                new ToolCall("ge5", "get_entity", "{\"entityType\":\"Mashup\",\"entityName\":\"MyMashup\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("UNSUPPORTED_ENTITY_TYPE_FOR_GET_ENTITY", body.path("code").asText());
    }

    @Test
    void empty_arguments_returns_missing_entity_type() throws Exception {
        String json = GetEntityExecutor.execute(new ToolCall("ge3", "get_entity", "{}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertFalse(body.path("code").asText().isEmpty());
    }
}
