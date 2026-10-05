package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * End-to-end handoff for the explicit resolver-tool path (design §3.4 rung 2):
 * {@code resolve_document_set} -> {@code search_document_chunks} carrying the resolved
 * ids via the schema-backed {@code documentIds} argument -> {@code selectionMode:
 * documentIds-filter}. The resolved set must be
 * applicable through the advertised search schema, not an undeclared property.
 */
class DocumentKnowledgeResolverHandoffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AGENT = "DocKnowledgeResolverHandoffAgent";

    private static DocumentKnowledgeSettings settings() {
        return new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
    }

    @AfterEach
    void tearDown() {
        DocumentKnowledgeIndexCache.clearForTests();
    }

    @Test
    void resolve_then_search_with_documentids_filters_to_resolved_set() throws Exception {
        DocumentKnowledgeSettings settings = settings();
        DocumentKnowledgeIndexCache.loadForTests(settings, AGENT, DocumentKnowledgeFixtures.allFixturesReader());

        // 1) resolve the document set for a curated asset identity
        String resolveJson = DocumentKnowledgeRuntime.executeResolveDocumentSet(
                new ToolCall("r1", "resolve_document_set",
                        "{\"key\":\"RK&T CB 24 GT4 turbo-generator set\"}"),
                settings, AGENT);
        JsonNode resolve = MAPPER.readTree(resolveJson);
        assertEquals("default-match", resolve.path("resolverSource").asText());
        JsonNode documents = resolve.path("documents");
        assertTrue(documents.size() >= 1);
        String resolvedDocId = documents.get(0).path("documentId").asText();
        assertEquals("rk-t-operating-manual-7318042", resolvedDocId);

        // 2) carry the resolved id into search via the schema-backed documentIds arg
        String searchJson = DocumentKnowledgeRuntime.executeSearchDocumentChunks(
                new ToolCall("s1", "search_document_chunks",
                        "{\"query\":\"trip\",\"documentIds\":[\"" + resolvedDocId + "\"]}"),
                settings, AGENT);
        JsonNode search = MAPPER.readTree(searchJson);
        assertEquals("success", search.path("status").asText());
        assertEquals("documentIds-filter", search.path("selectionMode").asText());
        assertEquals(1, search.path("selectedDocIds").size());
        assertEquals(resolvedDocId, search.path("selectedDocIds").get(0).asText());
    }
}
