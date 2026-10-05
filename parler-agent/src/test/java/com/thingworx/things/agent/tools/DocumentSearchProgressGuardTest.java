package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class DocumentSearchProgressGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
        DocumentSearchProgressGuardRegistry.removeTurn(FetchCachedReplayGuard.resolveCurrentTurnKey());
    }

    @Test
    void four_consecutive_searches_without_get_chunk_trigger_guard() throws Exception {
        DocumentSearchProgressGuard guard = new DocumentSearchProgressGuard();
        for (int i = 1; i <= 3; i++) {
            String result = MAPPER.writeValueAsString(java.util.Map.of(
                    "matches", java.util.List.of(java.util.Map.of(
                            "docId", "a", "chunkId", "c" + i))));
            assertFalse(guard.recordSearchCompletion(result, 5),
                    "search " + i + " should not trigger zero-fetch guard yet");
        }
        String stable = MAPPER.writeValueAsString(java.util.Map.of(
                "matches", java.util.List.of(java.util.Map.of(
                        "docId", "a", "chunkId", "c4"))));
        assertTrue(guard.recordSearchCompletion(stable, 5));
    }

    @Test
    void three_stable_fingerprints_trigger_guard_before_fourth_search() throws Exception {
        DocumentSearchProgressGuard guard = new DocumentSearchProgressGuard();
        String result = MAPPER.writeValueAsString(java.util.Map.of(
                "matches", java.util.List.of(java.util.Map.of(
                        "docId", "a", "chunkId", "c1"))));
        assertFalse(guard.recordSearchCompletion(result, 5));
        assertFalse(guard.recordSearchCompletion(result, 5));
        assertTrue(guard.recordSearchCompletion(result, 5));
    }

    @Test
    void get_document_chunk_resets_guard() throws Exception {
        DocumentSearchProgressGuard guard = new DocumentSearchProgressGuard();
        String result = MAPPER.writeValueAsString(java.util.Map.of("matches", java.util.List.of()));
        guard.recordSearchCompletion(result, 5);
        guard.recordSearchCompletion(result, 5);
        guard.resetOnGetDocumentChunk();
        assertFalse(guard.recordSearchCompletion(result, 5));
    }

    @Test
    void non_document_tool_disables_guard_for_turn() throws Exception {
        DocumentSearchProgressGuard guard = new DocumentSearchProgressGuard();
        String result = MAPPER.writeValueAsString(java.util.Map.of("matches", java.util.List.of()));
        guard.disableForTurn();
        assertFalse(guard.recordSearchCompletion(result, 5));
        assertFalse(guard.recordSearchCompletion(result, 5));
        assertFalse(guard.recordSearchCompletion(result, 5));
        assertFalse(guard.recordSearchCompletion(result, 5));
    }

    @Test
    void intercept_does_not_stack_when_repetition_already_blocked() throws Exception {
        String result = MAPPER.writeValueAsString(java.util.Map.of("matches", java.util.List.of()));
        assertTrue(DocumentSearchProgressGuard.interceptSearchLoop(
                "search_document_chunks", result, 5, true).isEmpty());
        assertFalse(AgentToolContext.consumeDocumentSearchLoopForcedSummary());
    }

    // ---- C1 content-saturation tracker (document-retrieval-convergence) ----

    private static String searchOf(String... chunkIds) throws Exception {
        java.util.List<java.util.Map<String, Object>> matches = new java.util.ArrayList<>();
        for (String id : chunkIds) {
            matches.add(java.util.Map.of("docId", "rk-t", "chunkId", id));
        }
        return MAPPER.writeValueAsString(java.util.Map.of("matches", matches));
    }

    private static String fetchOf(String chunkId) throws Exception {
        return MAPPER.writeValueAsString(java.util.Map.of(
                "status", "success", "chunkId", chunkId, "markdown", "body of " + chunkId));
    }

    @Test
    void paraphrased_search_with_interleaved_fetch_triggers_saturation() throws Exception {
        // Reproduces fd710253: the fingerprint/zero-fetch path is evaded (paraphrased queries +
        // get_document_chunk resets), but content saturation still fires after K stale rounds.
        DocumentSearchProgressGuard guard = new DocumentSearchProgressGuard();
        String section = "section-0080-part-a-08-trouble-causes-and-their-elimination";

        assertFalse(guard.recordSearchSaturation(searchOf(section)));  // round 1: new search content
        assertFalse(guard.recordFetchSaturation(fetchOf(section)));    // round 2: new full body
        assertFalse(guard.recordSearchSaturation(searchOf(section)));  // round 3: stale=1
        assertFalse(guard.recordFetchSaturation(fetchOf(section)));    // round 4: stale=2
        assertTrue(guard.recordSearchSaturation(searchOf(section)),    // round 5: stale=3 -> fire
                "saturation must fire once retrieval stalls after a substantive fetch");
    }

    @Test
    void new_substantive_content_resets_saturation() throws Exception {
        DocumentSearchProgressGuard guard = new DocumentSearchProgressGuard();
        String a = "section-0080-part-a";
        String b = "section-0125-part-b";

        guard.recordSearchSaturation(searchOf(a));
        guard.recordFetchSaturation(fetchOf(a));
        assertFalse(guard.recordSearchSaturation(searchOf(a)));   // stale=1
        assertFalse(guard.recordSearchSaturation(searchOf(b)));   // new content -> stale reset to 0
        assertFalse(guard.recordSearchSaturation(searchOf(a, b))); // stale=1
        assertFalse(guard.recordFetchSaturation(fetchOf(a)));      // stale=2
        assertTrue(guard.recordSearchSaturation(searchOf(a, b)));  // stale=3 -> fire
    }

    @Test
    void saturation_requires_a_substantive_fetch_before_firing() throws Exception {
        // Pure paraphrased-search loop over the same chunk, no get_document_chunk: the saturation gate
        // stays closed (snippets alone must not force a finalize). The zero-fetch path handles this case.
        DocumentSearchProgressGuard guard = new DocumentSearchProgressGuard();
        String section = "section-0080-part-a";
        for (int i = 0; i < 6; i++) {
            assertFalse(guard.recordSearchSaturation(searchOf(section)),
                    "search-only loop must not fire saturation without a substantive fetch");
        }
    }

    @Test
    void signal_chunks_do_not_count_as_progress() throws Exception {
        DocumentSearchProgressGuard guard = new DocumentSearchProgressGuard();
        // A search whose top-set is only heading markers surfaces no substantive content...
        assertFalse(guard.recordSearchSaturation(searchOf("signal-0080", "signal-0058")));
        // ...and fetching a signal chunk does not unlock the firing gate.
        assertFalse(guard.recordFetchSaturation(fetchOf("signal-0080")));
        assertFalse(guard.recordFetchSaturation(fetchOf("signal-0080")));
        assertFalse(guard.recordFetchSaturation(fetchOf("signal-0080")),
                "signal-only fetches must never satisfy the substantive-fetch gate");
    }

    @Test
    void signal_heading_and_its_section_are_not_double_progress() throws Exception {
        // signal-0080 (heading) and section-0080 (its content) must not each count as independent
        // progress: only the substantive section advances the tracker.
        DocumentSearchProgressGuard guard = new DocumentSearchProgressGuard();
        String section = "section-0080-part-a";
        guard.recordSearchSaturation(searchOf("signal-0080", section)); // only section is new
        guard.recordFetchSaturation(fetchOf(section));                  // new body, stale=0
        assertFalse(guard.recordSearchSaturation(searchOf("signal-0080", section))); // stale=1
        assertFalse(guard.recordFetchSaturation(fetchOf(section)));     // stale=2
        assertTrue(guard.recordSearchSaturation(searchOf("signal-0080", section))); // stale=3 -> fire
    }

    @Test
    void disabled_turn_suppresses_saturation() throws Exception {
        DocumentSearchProgressGuard guard = new DocumentSearchProgressGuard();
        guard.disableForTurn();
        String section = "section-0080-part-a";
        guard.recordSearchSaturation(searchOf(section));
        for (int i = 0; i < 6; i++) {
            assertFalse(guard.recordFetchSaturation(fetchOf(section)));
        }
    }

    // ---- Forced-summary / coverage composition ----

    @Test
    void coverage_rides_document_search_forced_summary() {
        AgentToolContext.requestForcedSummaryForDocumentSearchLoop();
        AgentToolContext.requestGroundedCoverageSummary();
        AgentToolContext.ForcedSummaryDecision d = AgentToolContext.resolveForcedSummaryDecision(false);
        assertTrue(d.forceSummary());
        assertTrue(d.coverageGuidance());
    }

    @Test
    void coverage_rides_repetition_blocked_forced_summary() {
        // The P1-B regression: when repetition blocking already forces a summary, a pending saturation
        // coverage request must still ride that finalize round (not be silently dropped).
        AgentToolContext.requestForcedSummaryForDocumentSearchLoop();
        AgentToolContext.requestGroundedCoverageSummary();
        AgentToolContext.ForcedSummaryDecision d = AgentToolContext.resolveForcedSummaryDecision(true);
        assertTrue(d.forceSummary());
        assertTrue(d.coverageGuidance(),
                "saturation coverage must ride the repetition-blocked forced summary");
    }

    @Test
    void repetition_only_forces_plain_summary_without_coverage() {
        AgentToolContext.ForcedSummaryDecision d = AgentToolContext.resolveForcedSummaryDecision(true);
        assertTrue(d.forceSummary());
        assertFalse(d.coverageGuidance());
    }

    @Test
    void no_forced_summary_preserves_a_stray_coverage_flag() {
        AgentToolContext.requestGroundedCoverageSummary();
        AgentToolContext.ForcedSummaryDecision d = AgentToolContext.resolveForcedSummaryDecision(false);
        assertFalse(d.forceSummary());
        assertFalse(d.coverageGuidance());
        // Coverage must not be consumed without a finalize round to carry it.
        assertTrue(AgentToolContext.consumeGroundedCoverageSummary());
    }

    @Test
    void static_path_sets_forced_summary_and_coverage_flags_on_saturation() throws Exception {
        // Drives the real entry points (interceptSearchLoop / onGetDocumentChunk) through the per-turn
        // registry; asserts both the forced-summary and the grounded-coverage flags are raised.
        String section = "section-0080-part-a";
        DocumentSearchProgressGuard.interceptSearchLoop("search_document_chunks", searchOf(section), 5, false);
        DocumentSearchProgressGuard.onGetDocumentChunk(fetchOf(section));
        DocumentSearchProgressGuard.interceptSearchLoop("search_document_chunks", searchOf(section), 5, false);
        DocumentSearchProgressGuard.onGetDocumentChunk(fetchOf(section));
        assertFalse(AgentToolContext.consumeGroundedCoverageSummary(),
                "coverage must not fire before the stale threshold is reached");
        // Fifth retrieval round stalls -> saturation fires.
        DocumentSearchProgressGuard.interceptSearchLoop("search_document_chunks", searchOf(section), 5, false);
        assertTrue(AgentToolContext.consumeDocumentSearchLoopForcedSummary());
        assertTrue(AgentToolContext.consumeGroundedCoverageSummary());
    }
}
