package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Light in-process BM25/IDF (design §4 / M3): IDF down-weights corpus-common terms and
 * up-weights rare discriminating tokens; boost is capped; non-matching chunks score 0.
 */
class DocumentKnowledgeBm25Test {

    private static DocumentKnowledgeChunk chunk(String chunkId, String markdown) {
        String json = "{\"docId\":\"d\",\"chunkId\":\"" + chunkId + "\",\"heading\":\"H\","
                + "\"pageStart\":1,\"pageEnd\":1,\"markdown\":\"" + markdown + "\"}";
        return DocumentKnowledgeChunk.parseJsonLine(json).orElseThrow();
    }

    @Test
    void rare_term_out_boosts_a_common_term() {
        // "pressure" is in every chunk (common ⇒ low IDF); "wobblegear" in one (rare ⇒ high IDF).
        DocumentKnowledgeChunk a = chunk("a", "pressure pressure wobblegear gizmo");
        DocumentKnowledgeChunk b = chunk("b", "pressure pressure pressure shutdown");
        DocumentKnowledgeChunk c = chunk("c", "pressure alarm valve");
        List<DocumentKnowledgeChunk> corpus = List.of(a, b, c);

        int commonBoost = DocumentKnowledgeBm25.forCorpus(corpus, List.of("pressure")).boostFor(a);
        int rareBoost = DocumentKnowledgeBm25.forCorpus(corpus, List.of("wobblegear")).boostFor(a);

        assertTrue(rareBoost > commonBoost,
                "a rare-term match (" + rareBoost + ") should out-boost a common-term match (" + commonBoost + ")");
    }

    @Test
    void chunk_without_the_term_scores_zero() {
        DocumentKnowledgeChunk a = chunk("a", "wobblegear here");
        DocumentKnowledgeChunk b = chunk("b", "nothing relevant in this body");
        DocumentKnowledgeBm25 bm25 = DocumentKnowledgeBm25.forCorpus(List.of(a, b), List.of("wobblegear"));
        assertTrue(bm25.boostFor(a) > 0);
        assertEquals(0, bm25.boostFor(b));
    }

    @Test
    void empty_corpus_or_no_query_terms_is_zero() {
        DocumentKnowledgeChunk a = chunk("a", "wobblegear");
        assertEquals(0, DocumentKnowledgeBm25.forCorpus(List.of(), List.of("wobblegear")).boostFor(a));
        assertEquals(0, DocumentKnowledgeBm25.forCorpus(List.of(a), List.of()).boostFor(a));
    }

    @Test
    void boost_is_capped() {
        List<DocumentKnowledgeChunk> corpus = new ArrayList<>();
        StringBuilder dense = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            dense.append("wobblegear ");
        }
        DocumentKnowledgeChunk hot = chunk("hot", dense.toString().trim());
        corpus.add(hot);
        for (int i = 0; i < 40; i++) {
            corpus.add(chunk("c" + i, "pressure alarm valve common operational terms here"));
        }
        assertEquals(DocumentKnowledgeBm25.BOOST_CAP,
                DocumentKnowledgeBm25.forCorpus(corpus, List.of("wobblegear")).boostFor(hot));
    }
}
