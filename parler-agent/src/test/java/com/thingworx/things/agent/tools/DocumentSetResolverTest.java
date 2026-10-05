package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Behavior of the built-in conservative high-confidence matcher
 * (docs/operations/knowledge-retrieval-pipeline.md §3.3),
 * over the committed offline fixtures.
 */
class DocumentSetResolverTest {

    private static Collection<DocumentKnowledgePackageManifest> fixtureDocs() throws Exception {
        return DocumentKnowledgeFixtures.loadIndex().allManifests();
    }

    @Test
    void high_confidence_exact_match_scopes_the_right_document() throws Exception {
        ResolvedDocumentSet result = DocumentSetResolver.resolveDefault(
                List.of("RK&T CB 24 GT4 turbo-generator set"), fixtureDocs());
        assertEquals(ResolverSource.DEFAULT_MATCH, result.source());
        assertEquals(List.of("rk-t-operating-manual-7318042"), result.documentIds());
    }

    @Test
    void match_is_case_and_whitespace_insensitive() throws Exception {
        ResolvedDocumentSet result = DocumentSetResolver.resolveDefault(
                List.of("  rk&t   CB 24 GT4 Turbo-Generator Set "), fixtureDocs());
        assertEquals(ResolverSource.DEFAULT_MATCH, result.source());
        assertEquals(List.of("rk-t-operating-manual-7318042"), result.documentIds());
    }

    @Test
    void distinct_asset_identities_scope_distinct_documents() throws Exception {
        Collection<DocumentKnowledgePackageManifest> docs = fixtureDocs();
        assertEquals(List.of("fernwick-carbaq-ops-v2"),
                DocumentSetResolver.resolveDefault(List.of("CarbaQ CO2 Capture Solution"), docs).documentIds());
        assertEquals(List.of("kbm-coupling-manual-7318042"),
                DocumentSetResolver.resolveDefault(
                        List.of("KBM FLEXO-N flexible pin type coupling (KBN 21011)"), docs).documentIds());
    }

    @Test
    void no_confident_match_returns_default_empty() throws Exception {
        ResolvedDocumentSet result = DocumentSetResolver.resolveDefault(
                List.of("Some Unrelated Pump Model 9000"), fixtureDocs());
        assertEquals(ResolverSource.DEFAULT_EMPTY, result.source());
        assertTrue(result.isEmpty());
    }

    @Test
    void no_asset_identity_returns_default_empty() throws Exception {
        ResolvedDocumentSet result = DocumentSetResolver.resolveDefault(List.of(), fixtureDocs());
        assertEquals(ResolverSource.DEFAULT_EMPTY, result.source());
        assertTrue(result.isEmpty());
    }

    @Test
    void partial_substring_does_not_match_high_confidence() throws Exception {
        // "RK&T CB 24 GT4" is a prefix of a curated model but not an exact match;
        // the high-confidence default must fall through to default-empty (no mis-scope).
        ResolvedDocumentSet result = DocumentSetResolver.resolveDefault(
                List.of("RK&T CB 24 GT4"), fixtureDocs());
        assertEquals(ResolverSource.DEFAULT_EMPTY, result.source());
    }

    @Test
    void multiple_documents_sharing_an_asset_model_all_scope_default_match() {
        // Many-to-many is intended, not ambiguous: when several documents share one
        // curated assetModels value, all are in scope (a single default-match set).
        List<DocumentKnowledgePackageManifest> docs = List.of(
                manifestWithAssetModel("doc-a", "Shared Turbine X"),
                manifestWithAssetModel("doc-b", "Shared Turbine X"));
        ResolvedDocumentSet result = DocumentSetResolver.resolveDefault(List.of("Shared Turbine X"), docs);
        assertEquals(ResolverSource.DEFAULT_MATCH, result.source());
        assertEquals(List.of("doc-a", "doc-b"), result.documentIds());
    }

    private static DocumentKnowledgePackageManifest manifestWithAssetModel(String docId, String assetModel) {
        String json = "{"
                + "\"contractVersion\":\"1.0\",\"docId\":\"" + docId + "\",\"title\":\"T\","
                + "\"sourcePath\":\"s\",\"markdownPath\":\"m\",\"chunksPath\":\"chunks/chunks.jsonl\","
                + "\"sourceRepository\":\"AIDocRepository\",\"sourceRepositoryPath\":\"/p\","
                + "\"sourceHref\":\"h\",\"sourceSha256\":\"abc\",\"convertedAt\":\"2026-01-01T00:00:00Z\","
                + "\"pageCount\":1,\"assetModels\":[\"" + assetModel + "\"]}";
        return DocumentKnowledgePackageManifest.parseJson(json).orElseThrow();
    }
}
