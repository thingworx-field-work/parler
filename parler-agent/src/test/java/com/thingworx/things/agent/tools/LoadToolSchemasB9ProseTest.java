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

/**
 * B9: {@code load_tool_schemas} prose must steer to now / next step of this turn (not a later turn).
 */
class LoadToolSchemasB9ProseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void metaToolDescriptionSaysThisTurnNotNextTurn() {
        String desc = LoadToolSchemasExecutor.metaToolDescriptionPrefix() + "example_deferred_tool — when";
        assertTrue(desc.contains("now / in the next step of this turn"), desc);
        assertFalse(desc.contains("next turn"), desc);
    }

    @Test
    void executorSuccessNoteSaysThisTurnNotNextTurn() throws Exception {
        assertTrue(LoadToolSchemasExecutor.SUCCESS_NOTE.contains("this turn"));
        assertFalse(LoadToolSchemasExecutor.SUCCESS_NOTE.contains("next turn"));

        Map<String, ToolDefinition> byName = new LinkedHashMap<>();
        byName.put("example_tool", new ToolDefinition("example_tool", "d", Map.of("type", "object")));
        LoadToolSchemasExecutor.EnvelopeResult result =
                LoadToolSchemasExecutor.buildEnvelope(List.of("example_tool"), byName);
        JsonNode root = MAPPER.readTree(result.json);
        assertEquals(LoadToolSchemasExecutor.SUCCESS_NOTE, root.path("note").asText());
    }
}
