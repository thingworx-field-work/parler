package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * M1 schema-first verification (docs/operations/knowledge-retrieval-pipeline.md §2):
 * the resolver / Tier-1 / BM25 join key already exists in the committed corpus, so M1
 * is "adopt the existing {@code documentProfile} object" with no new field. This test
 * makes schema-first visible rather than skipped.
 */
class DocumentMetadataSchemaTest {

    @Test
    void all_fixtures_carry_the_assetmodels_join_key() throws Exception {
        List<String> docIds = DocumentKnowledgeFixtures.docIds();
        assertFalse(docIds.isEmpty(), "no document-knowledge fixtures discovered");
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        assertEquals(docIds.size(), index.searchedDocuments());
        for (String docId : docIds) {
            DocumentKnowledgePackageManifest manifest = index.manifestFor(docId).orElseThrow();
            boolean hasJoinKey = !manifest.assetModels().isEmpty()
                    || !manifest.documentProfile().assetModels().isEmpty();
            assertTrue(hasJoinKey, "missing assetModels join key for " + docId);
        }
    }
}
