package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * M4 acceptance gold set (docs/operations/knowledge-retrieval-pipeline.md §5) — one method
 * per trade-off §7 dimension, the auditable surface for "every §7 dimension passes or
 * explicitly fails with diagnostics". Reuses the committed {@code dev_data} fixtures via
 * {@link DocumentKnowledgeFixtures}; the deep end-to-end behaviors stay in their dedicated
 * M1–M3 tests (referenced inline) — this set asserts the §7 outcome, not a duplicate of
 * every assertion.
 *
 * <p>The offline JUnit gate covers the deterministic resolver/scorer/ranking/scoping
 * dimensions. The round-count targets (§5: ≤4 incident-class, ≤6 within-document) and the
 * out-of-scope clarify/honest-degrade loop behavior are <em>live evidence</em>
 * (`parler-collect-live`), not offline gates — see the topic packet for their status.
 */
class DocumentKnowledgeGoldSetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RKT_OPERATING = "rk-t-operating-manual-7318042";
    private static final String FERNWICK = "fernwick-carbaq-ops-v2";
    private static final String RKT_ASSET_KEY = "RK&T CB 24 GT4 turbo-generator set";
    private static final String TROUBLE_CHUNK = "section-0080-part-a-08-trouble-causes-and-their-elimination";

    /** §7 host-bound Thing scope: the bound asset identity resolves (default-match) to its doc set —
     *  the deterministic core that host-context injection defaults as documentIds. End-to-end
     *  inject → documentIds-filter is covered by {@link DocumentKnowledgeHostContextScopeTest}. */
    @Test
    void host_bound_thing_scope_resolves_default_match() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        ResolvedDocumentSet scope = DocumentKnowledgeRuntime.resolveDocumentSetResult(
                index, RKT_ASSET_KEY, key -> DocumentKnowledgeRuntime.CustomResolution.empty());
        assertEquals(ResolverSource.DEFAULT_MATCH, scope.source());
        assertEquals(List.of(RKT_OPERATING), scope.documentIds());
    }

    /** §7 user-named Thing scope: the explicit resolver tool emits a documents[] set whose
     *  documentId is handed off as the search documentIds. End-to-end handoff →
     *  documentIds-filter is covered by {@link DocumentKnowledgeResolverHandoffTest}. */
    @Test
    @SuppressWarnings("unchecked")
    void user_named_thing_scope_emits_documents_for_handoff() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        Map<String, Object> body = DocumentKnowledgeRuntime.resolveDocumentSet(index, RKT_ASSET_KEY, false);
        assertEquals("default-match", body.get("resolverSource"));
        List<Map<String, Object>> documents = (List<Map<String, Object>>) body.get("documents");
        assertEquals(RKT_OPERATING, documents.get(0).get("documentId"));
    }

    /** §7 missing custom mapping: no override + no confident match → default-empty, falling
     *  through the §3.4 ladder rather than mis-scoping. */
    @Test
    void missing_custom_mapping_falls_through_default_empty() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        ResolvedDocumentSet scope = DocumentKnowledgeRuntime.resolveDocumentSetResult(
                index, "Unknown Asset 9000", key -> DocumentKnowledgeRuntime.CustomResolution.empty());
        assertEquals(ResolverSource.DEFAULT_EMPTY, scope.source());
        assertTrue(scope.documentIds().isEmpty());
    }

    /** §7 cross-cutting inclusion (positive, Path 1): a custom resolver returning a key doc plus
     *  an alwaysInclude cross-cutting doc unions both into the scope, with the cross-cutting flag
     *  tracked — sourced from the resolver, never from global search. */
    @Test
    void cross_cutting_alwaysInclude_unions_into_scope() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        DocumentKnowledgeRuntime.CustomDocumentSetResolver custom = key ->
                new DocumentKnowledgeRuntime.CustomResolution(
                        List.of(RKT_OPERATING, FERNWICK), java.util.Set.of(FERNWICK), java.util.Set.of());
        ResolvedDocumentSet scope = DocumentKnowledgeRuntime.resolveDocumentSetResult(index, "anything", custom);
        assertEquals(ResolverSource.CUSTOM, scope.source());
        assertTrue(scope.documentIds().contains(RKT_OPERATING));
        assertTrue(scope.documentIds().contains(FERNWICK));
        assertTrue(scope.isAlwaysInclude(FERNWICK), "cross-cutting doc must be flagged alwaysInclude");
        assertFalse(scope.isAlwaysInclude(RKT_OPERATING), "key-matched doc is not cross-cutting");
    }

    /** §7 cross-cutting inclusion (negative, anti-pollution): without a resolver-sourced
     *  alwaysInclude row, the built-in matcher never auto-adds a cross-cutting doc — no global
     *  pollution. The asset key scopes only to its own doc. */
    @Test
    void cross_cutting_not_auto_added_without_resolver_flag() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        ResolvedDocumentSet scope = DocumentKnowledgeRuntime.resolveDocumentSetResult(
                index, RKT_ASSET_KEY, key -> DocumentKnowledgeRuntime.CustomResolution.empty());
        assertTrue(scope.alwaysIncludeIds().isEmpty(), "built-in matcher must not synthesize cross-cutting docs");
        assertFalse(scope.documentIds().contains(FERNWICK), "off-asset doc must not leak into scope");
    }

    /** C2 (document-retrieval-convergence) signal de-pollution: for a trouble-class query scoped to
     *  one document, heading/marker {@code signal-*} chunks must not outrank substantive content in
     *  {@code matches[]}. Asserts the class-level distinction (no `signal-*` above the substantive
     *  `section-0080` trouble chunk; substantive content fully partitioned before signals), and is
     *  non-vacuous (signals are present in the candidate set, so the demotion is exercised). */
    @Test
    void c2_signal_chunks_do_not_outrank_substantive_content() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"trouble causes and elimination bearing lube oil pressure "
                        + "vibration alarm shutdown\",\"documentIds\":[\"" + RKT_OPERATING + "\"]}"));
        // Full-coverage limit so signals appear in the ranking at all — the demotion is strong enough
        // that with a small top-k the 153 page + 10 section chunks crowd signals out entirely.
        DocumentKnowledgeSearchScorer.RankResult ranked = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                index.searchableChunks(), index::manifestFor, request, 200);
        List<String> ids = ranked.matches().stream().map(m -> m.chunk().chunkId()).toList();

        int firstSignal = -1;
        int lastSubstantive = -1;
        int troubleIdx = -1;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id.startsWith("signal-")) {
                if (firstSignal < 0) {
                    firstSignal = i;
                }
            } else {
                lastSubstantive = i;
            }
            if (TROUBLE_CHUNK.equals(id)) {
                troubleIdx = i;
            }
        }

        assertTrue(firstSignal >= 0, () -> "test must exercise demotion: expected a signal-* candidate; got " + ids);
        assertTrue(troubleIdx >= 0, () -> "trouble chunk must surface for the trouble query; got " + ids);
        assertTrue(troubleIdx < firstSignal,
                () -> "substantive section-0080 trouble chunk must rank above every signal-*; got " + ids);
        assertTrue(lastSubstantive < firstSignal,
                () -> "all substantive content must rank before any signal-* (global partition); got " + ids);
    }

    /** §7 within-document recall: scoped to one document, a symptom paraphrase surfaces the
     *  in-document troubleshooting chunk (the oil-mist/trouble class), via BM25 + the additive
     *  scorer over the scoped chunk set. */
    @Test
    void within_document_recall_surfaces_trouble_chunk() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"what trips the turbine and how do I bring it back online\","
                        + "\"documentIds\":[\"" + RKT_OPERATING + "\"]}"));
        DocumentKnowledgeSearchScorer.RankResult ranked = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                index.searchableChunks(), index::manifestFor, request, 10);
        assertEquals("documentIds-filter", ranked.selectionMode());
        assertTrue(
                ranked.matches().stream().anyMatch(m -> TROUBLE_CHUNK.equals(m.chunk().chunkId())),
                () -> "within-document recall must surface the trouble chunk; got "
                        + ranked.matches().stream().map(m -> m.chunk().chunkId()).toList());
    }

    /** §7 weak-identity symptom: a symptom-led paraphrase with only a weak identity hint
     *  (no "RK&T"/"operating manual" tokens) must not let the off-domain fernwick-carbaq document
     *  win on shared operational vocabulary ("shutdown"/"restart"/"bearing") — BM25's IDF
     *  down-weights the common terms so cross-domain pollution does not surface the wrong doc
     *  (the M3 Tier-3 gating shape, symptom-only fernwick-carbaq). */
    @Test
    void weak_identity_symptom_not_dominated_by_fernwick() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"steps before restarting the turbo-generator after a "
                        + "bearing temperature or vibration alarm shutdown\"}"));
        DocumentKnowledgeSearchScorer.RankResult ranked = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                index.searchableChunks(), index::manifestFor, request, 10);
        assertFalse(ranked.matches().isEmpty());
        assertNotEquals(FERNWICK, ranked.matches().get(0).chunk().docId(),
                () -> "off-domain fernwick-carbaq must not top-rank a weak-identity in-domain symptom query");
        assertFalse(
                ranked.matches().stream().limit(3).allMatch(m -> FERNWICK.equals(m.chunk().docId())),
                "fernwick-carbaq must not dominate the top of a weak-identity symptom query");
        assertTrue(
                ranked.matches().stream().anyMatch(m -> RKT_OPERATING.equals(m.chunk().docId())),
                () -> "the in-domain operating manual must surface for the symptom query; got "
                        + ranked.matches().stream().map(m -> m.chunk().docId()).distinct().toList());
    }

    /** §7 out-of-scope context-free: clarify / honest-degrade is loop behavior and is recorded as
     *  LIVE evidence, not an offline gate. The offline invariant we can pin: a context-free query
     *  with no identity and no scope is NOT silently turned into a documentIds-filter (no
     *  fabricated wrong-manual scope). */
    @Test
    void out_of_scope_context_free_is_not_silently_scoped() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"what should I check first\"}"));
        DocumentKnowledgeSearchScorer.RankResult ranked = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                index.searchableChunks(), index::manifestFor, request, 8);
        assertNotEquals("documentIds-filter", ranked.selectionMode(),
                "a context-free query without supplied documentIds must not fabricate a scoped filter");
    }
}
