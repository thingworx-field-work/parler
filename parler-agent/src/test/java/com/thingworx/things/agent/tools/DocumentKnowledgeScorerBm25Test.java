package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * BM25 integration with the additive scorer (design §4 / M3 candidate-set rule):
 * BM25 runs over all chunks in the selected documents, so a chunk the additive scorer scores
 * 0 but BM25 finds (a term present only in its body) still surfaces, carrying a positive
 * {@code bm25Boost} on the match.
 */
class DocumentKnowledgeScorerBm25Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DocumentKnowledgeChunk chunk(String docId, String chunkId, String markdown) {
        String json = "{\"docId\":\"" + docId + "\",\"chunkId\":\"" + chunkId + "\",\"heading\":\"Maintenance\","
                + "\"pageStart\":1,\"pageEnd\":1,\"markdown\":\"" + markdown + "\"}";
        return DocumentKnowledgeChunk.parseJsonLine(json).orElseThrow();
    }

    private static DocumentKnowledgePackageManifest manifest(String docId) {
        String json = "{\"contractVersion\":\"1.0\",\"docId\":\"" + docId + "\",\"title\":\"Doc " + docId + "\","
                + "\"sourcePath\":\"s\",\"markdownPath\":\"m\",\"chunksPath\":\"chunks/chunks.jsonl\","
                + "\"sourceRepository\":\"AIDocRepository\",\"sourceRepositoryPath\":\"/p\","
                + "\"sourceHref\":\"h\",\"sourceSha256\":\"abc\",\"convertedAt\":\"2026-01-01T00:00:00Z\","
                + "\"pageCount\":1}";
        return DocumentKnowledgePackageManifest.parseJson(json).orElseThrow();
    }

    @Test
    void bm25_surfaces_a_chunk_with_zero_additive_score() throws Exception {
        DocumentKnowledgeChunk hit = chunk("doc-x", "c-hit", "the wobblegear assembly needs inspection");
        DocumentKnowledgeChunk other = chunk("doc-x", "c-other", "routine cleaning of the housing");

        // A signal whose name appears only in the hit chunk body, with no query text:
        // the additive scorer matches nothing (no signal entry, tag, heading, or query token),
        // so only BM25 (which folds signal-name tokens) can surface it.
        JsonNode args = MAPPER.readTree("{\"signals\":[{\"name\":\"wobblegear\"}]}");
        DocumentKnowledgeSearchScorer.SearchRequest request =
                DocumentKnowledgeSearchScorer.SearchRequest.from(args);

        DocumentKnowledgeSearchScorer.RankResult result = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                List.of(hit, other), docId -> Optional.of(manifest(docId)), request, 10);

        DocumentKnowledgeSearchScorer.ScoredMatch hitMatch = result.matches().stream()
                .filter(m -> m.chunk().chunkId().equals("c-hit"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("BM25 should surface a chunk the additive scorer scores 0"));
        assertTrue(hitMatch.bm25Boost() > 0, "the surfaced match carries a positive bm25Boost");
    }
}
