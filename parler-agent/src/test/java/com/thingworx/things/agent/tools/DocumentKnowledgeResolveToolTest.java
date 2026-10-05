package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Model-facing shaping of the {@code resolve_document_set} tool body over the offline
 * fixtures (docs/operations/knowledge-retrieval-pipeline.md §3.1/§3.3): the built-in
 * matcher integrated with the runtime seam, stripped to {@code documents[]} of
 * {@code {documentId}} plus a {@code resolverSource} diagnostic.
 */
class DocumentKnowledgeResolveToolTest {

    @Test
    void default_match_returns_documents_array_and_source() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        Map<String, Object> body = DocumentKnowledgeRuntime.resolveDocumentSet(
                index, "RK&T CB 24 GT4 turbo-generator set", false);
        assertEquals("success", body.get("status"));
        assertEquals("default-match", body.get("resolverSource"));
        assertEquals(
                List.of(Map.of("documentId", "rk-t-operating-manual-7318042",
                        "alwaysInclude", false, "appliesToMany", false)),
                body.get("documents"));
    }

    @Test
    void unknown_key_returns_default_empty_and_no_documents() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        Map<String, Object> body = DocumentKnowledgeRuntime.resolveDocumentSet(
                index, "Unknown Asset 9000", false);
        assertEquals("success", body.get("status"));
        assertEquals("default-empty", body.get("resolverSource"));
        assertTrue(((List<?>) body.get("documents")).isEmpty());
    }

    @Test
    void null_key_returns_default_empty() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        Map<String, Object> body = DocumentKnowledgeRuntime.resolveDocumentSet(index, null, false);
        assertEquals("default-empty", body.get("resolverSource"));
        assertTrue(((List<?>) body.get("documents")).isEmpty());
    }
}
