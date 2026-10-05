package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.LlmJsonSchemaCompat;
import com.thingworx.things.agent.llm.ToolCall;

class DocumentKnowledgeToolSchemasTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void search_schema_passes_llm_compat_check() {
        LlmJsonSchemaCompat.assertArraysDeclareItems("search_document_chunks",
                DocumentKnowledgeToolSchemas.searchDocumentChunksParametersSchema());
    }

    @Test
    void search_empty_input_returns_empty_success_warning() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false, true);
        String json = reg.executeTool(new ToolCall("w1", "search_document_chunks", "{}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("success", body.path("status").asText());
        assertEquals(0, body.path("matches").size());
        assertEquals(false, body.path("degraded").asBoolean());
        assertEquals("EMPTY_SEARCH_INPUT", body.path("warnings").get(0).path("code").asText());
    }

    @Test
    void search_without_repository_returns_degraded_empty_success() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false, true);
        String json = reg.executeTool(new ToolCall("w2", "search_document_chunks",
                "{\"query\":\"Chiller High Pressure Shutdown\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("success", body.path("status").asText());
        assertTrue(body.path("degraded").asBoolean());
        assertEquals("DOCUMENT_REPOSITORY_NOT_CONFIGURED", body.path("warnings").get(0).path("code").asText());
    }

    @Test
    void get_without_repository_returns_structured_error() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false, true);
        String json = reg.executeTool(new ToolCall("w3", "get_document_chunk",
                "{\"docId\":\"fernwick-carbaq-ops-v2\",\"chunkId\":\"missing\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("DOCUMENT_REPOSITORY_NOT_CONFIGURED", body.path("code").asText());
    }

    @Test
    void snippet_truncation_is_silent_length_only() {
        String longText = "x".repeat(500);
        String truncated = DocumentKnowledgeTextBounds.truncateSnippet(longText, 400);
        assertEquals(400, truncated.length());
        assertTrue(truncated.endsWith("…"));
    }
}
