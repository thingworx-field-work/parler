package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Host-context server-injected document scope (design §3.2): {@code search_document_chunks}
 * auto-defaults {@code documentIds} from the turn-scoped store ONLY when the model omits the
 * field (a default, not a hard filter), short-circuits empty-intent searches, and surfaces
 * the {@code documentScopeSource} diagnostic. Exercises the search side via the offline
 * fixtures + the {@link AgentToolContext} store (the turn-path resolve glue is not unit-tested).
 */
class DocumentKnowledgeHostContextScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AGENT = "DocKnowledgeHostScopeAgent";

    private static DocumentKnowledgeSettings settings() {
        return new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
    }

    @AfterEach
    void tearDown() {
        AgentToolContext.setInjectedDocumentScope(null, null);
        DocumentKnowledgeIndexCache.clearForTests();
    }

    private static JsonNode search(String argsJson) throws Exception {
        DocumentKnowledgeSettings settings = settings();
        DocumentKnowledgeIndexCache.loadForTests(settings, AGENT, DocumentKnowledgeFixtures.allFixturesReader());
        String json = DocumentKnowledgeRuntime.executeSearchDocumentChunks(
                new ToolCall("s1", "search_document_chunks", argsJson), settings, AGENT);
        return MAPPER.readTree(json);
    }

    @Test
    void injected_scope_defaults_documentids_when_model_omits_them() throws Exception {
        AgentToolContext.setInjectedDocumentScope(List.of("rk-t-operating-manual-7318042"), "default-match");
        JsonNode body = search("{\"query\":\"trip\"}");
        assertEquals("success", body.path("status").asText());
        assertEquals("documentIds-filter", body.path("selectionMode").asText());
        assertEquals("host-context-resolver", body.path("documentScopeSource").asText());
        assertEquals("default-match", body.path("documentScopeResolverSource").asText());
        assertEquals(List.of("rk-t-operating-manual-7318042"), strings(body.path("selectedDocIds")));
    }

    @Test
    void model_supplied_documentids_suppress_injection() throws Exception {
        AgentToolContext.setInjectedDocumentScope(List.of("rk-t-operating-manual-7318042"), "default-match");
        JsonNode body = search("{\"query\":\"shutdown\",\"documentIds\":[\"fernwick-carbaq-ops-v2\"]}");
        assertEquals("documentIds-filter", body.path("selectionMode").asText());
        assertFalse(body.has("documentScopeSource"), "model documentIds must suppress host-context injection");
        assertEquals(List.of("fernwick-carbaq-ops-v2"), strings(body.path("selectedDocIds")));
    }

    @Test
    void model_supplied_empty_documentids_also_suppress_injection() throws Exception {
        AgentToolContext.setInjectedDocumentScope(List.of("rk-t-operating-manual-7318042"), "default-match");
        JsonNode body = search("{\"query\":\"shutdown\",\"documentIds\":[]}");
        assertFalse(body.has("documentScopeSource"),
                "an explicit empty documentIds array is a model override, not an injection trigger");
    }

    @Test
    void injected_scope_without_retrieval_intent_short_circuits_empty() throws Exception {
        AgentToolContext.setInjectedDocumentScope(List.of("rk-t-operating-manual-7318042"), "default-match");
        JsonNode body = search("{}");
        assertEquals("success", body.path("status").asText());
        assertTrue(body.path("warnings").toString().contains("EMPTY_SEARCH_INPUT"));
        assertFalse(body.has("documentScopeSource"), "injected scope must narrow, not create, a search");
    }

    @Test
    void no_injected_scope_is_a_normal_unscoped_search() throws Exception {
        JsonNode body = search("{\"query\":\"Chiller High Pressure Shutdown\"}");
        assertEquals("success", body.path("status").asText());
        assertFalse(body.has("documentScopeSource"));
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
