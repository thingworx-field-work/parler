package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Light in-process BM25/IDF over a per-search scoped chunk corpus (design §4 / §2.4, M3).
 *
 * <p>Augments the additive {@link DocumentKnowledgeSearchScorer}: computes a capped
 * integer boost per chunk for the query terms, with IDF local to the candidate
 * (selected-document) chunk set — so common operational terms ("pressure", "shutdown")
 * are down-weighted and rare discriminating tokens up-weighted. No external index / Lucene;
 * cost is linear in the scoped set per search. Weight/cap are internal defaults that M4
 * (gold set) calibrates; BM25 only augments — it never participates in document selection.
 */
final class DocumentKnowledgeBm25 {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    /** Scales the raw BM25 score to integer boost points (M4 calibrates). */
    private static final double WEIGHT = 3.0;
    /**
     * Conservative cap (design §4 guardrail): BM25 must AUGMENT, not dominate.
     * Kept below the additive scorer's hard identity/signal constants (signal +50, heading
     * +35, tag +25, troubleshooting +15) so BM25 reorders within-band and surfaces
     * lexically-found chunks without overturning strong additive signals. M4 calibrates.
     */
    static final int BOOST_CAP = 10;

    private final IdentityHashMap<DocumentKnowledgeChunk, Integer> boostByChunk;

    private DocumentKnowledgeBm25(IdentityHashMap<DocumentKnowledgeChunk, Integer> boostByChunk) {
        this.boostByChunk = boostByChunk;
    }

    /**
     * Builds a BM25 model over the corpus (treating each chunk's {@code markdown()} as a
     * BM25 document) and precomputes the per-chunk boost for {@code queryTerms}.
     */
    static DocumentKnowledgeBm25 forCorpus(List<DocumentKnowledgeChunk> corpus, List<String> queryTerms) {
        IdentityHashMap<DocumentKnowledgeChunk, Integer> boosts = new IdentityHashMap<>();
        List<String> terms = normalizeTerms(queryTerms);
        if (corpus == null || corpus.isEmpty() || terms.isEmpty()) {
            return new DocumentKnowledgeBm25(boosts);
        }
        Set<String> termSet = new HashSet<>(terms);
        int n = corpus.size();
        IdentityHashMap<DocumentKnowledgeChunk, Map<String, Integer>> tfByChunk = new IdentityHashMap<>();
        IdentityHashMap<DocumentKnowledgeChunk, Integer> lenByChunk = new IdentityHashMap<>();
        Map<String, Integer> df = new HashMap<>();
        long totalLen = 0;
        for (DocumentKnowledgeChunk chunk : corpus) {
            List<String> tokens = tokenizeText(chunk.markdown());
            lenByChunk.put(chunk, tokens.size());
            totalLen += tokens.size();
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                if (termSet.contains(token)) {
                    tf.merge(token, 1, Integer::sum);
                }
            }
            tfByChunk.put(chunk, tf);
            for (String term : tf.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }
        double avgdl = (double) totalLen / n;
        Map<String, Double> idf = new HashMap<>();
        for (String term : terms) {
            int d = df.getOrDefault(term, 0);
            idf.put(term, Math.log((n - d + 0.5) / (d + 0.5) + 1.0));
        }
        for (DocumentKnowledgeChunk chunk : corpus) {
            Map<String, Integer> tf = tfByChunk.get(chunk);
            int len = lenByChunk.get(chunk);
            double score = 0.0;
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                double t = e.getValue();
                double denom = t + K1 * (1 - B + B * (avgdl > 0 ? len / avgdl : 0));
                if (denom > 0) {
                    score += idf.getOrDefault(e.getKey(), 0.0) * (t * (K1 + 1)) / denom;
                }
            }
            int boost = (int) Math.round(score * WEIGHT);
            boost = Math.max(0, Math.min(BOOST_CAP, boost));
            if (boost > 0) {
                boosts.put(chunk, boost);
            }
        }
        return new DocumentKnowledgeBm25(boosts);
    }

    /** @return the precomputed BM25 boost for {@code chunk} (0 if none) */
    int boostFor(DocumentKnowledgeChunk chunk) {
        Integer boost = boostByChunk.get(chunk);
        return boost != null ? boost : 0;
    }

    private static List<String> normalizeTerms(List<String> terms) {
        if (terms == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String term : terms) {
            if (term != null) {
                String s = term.trim().toLowerCase(Locale.ROOT);
                if (s.length() >= 2) {
                    out.add(s);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> tokenizeText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part != null && part.length() >= 2) {
                tokens.add(part);
            }
        }
        return tokens;
    }
}
