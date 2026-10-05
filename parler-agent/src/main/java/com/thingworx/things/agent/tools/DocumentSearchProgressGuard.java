package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Detects no-progress document-retrieval loops within one turn
 * ({@code docs/operations/document-retrieval-stability.md} §8).
 *
 * <p>Two independent triggers share a per-turn instance:
 * <ul>
 *   <li><b>Fingerprint / zero-fetch</b> (§8) — catches pure {@code search_document_chunks} spam;
 *       reset by an intervening {@code get_document_chunk}.</li>
 *   <li><b>Content saturation</b> (C1, {@code docs/operations/document-retrieval-convergence.md}) —
 *       catches the paraphrased-search + interleaved-fetch loop over a coverage-gap query, where the
 *       model keeps retrieving the same substantive content without finding absent material. This
 *       tracker is <em>not</em> reset by {@code get_document_chunk}; it advances on the actual chunk
 *       content surfaced and forces a coverage-grounded finalize once progress stalls.</li>
 * </ul>
 */
public final class DocumentSearchProgressGuard {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ZERO_FETCH_THRESHOLD = 4;
    private static final int FINGERPRINT_THRESHOLD = 3;
    private static final int FINGERPRINT_K_DEFAULT = 6;

    /** Heading-only marker chunks ({@code signal-*}) are signposts, not answers — excluded from progress. */
    private static final String SIGNAL_CHUNK_PREFIX = "signal-";
    /** Consecutive document-retrieval rounds surfacing no new substantive content before forcing finalize. */
    private static final int SATURATION_STALE_THRESHOLD = 3;

    private boolean disabledForTurn;
    private int consecutiveSearches;
    private String lastFingerprint;

    // C1 content-saturation tracker. Search snippets and fetched bodies are different modalities of
    // "surfaced content": appearing in a search top-set tells the model a chunk is relevant; fetching
    // it surfaces the full body. Either being new is progress, so we keep two identity sets.
    private final Set<String> searchSurfacedKeys = new HashSet<>();
    private final Set<String> fetchedKeys = new HashSet<>();
    private int staleRetrievalRounds;
    private boolean substantiveChunkFetched;

    public synchronized void disableForTurn() {
        disabledForTurn = true;
    }

    public synchronized void resetOnGetDocumentChunk() {
        consecutiveSearches = 0;
        lastFingerprint = null;
    }

    /**
     * Records a completed {@code search_document_chunks} call. Returns {@code true} when the guard should force a
     * tool-none summary round on the next agent iteration.
     */
    public synchronized boolean recordSearchCompletion(String resultJson, int requestedLimit) {
        if (disabledForTurn) {
            return false;
        }
        consecutiveSearches++;
        String fingerprint = fingerprintFromResult(resultJson, requestedLimit);
        boolean stableFingerprint = fingerprint != null
                && fingerprint.equals(lastFingerprint);
        lastFingerprint = fingerprint;

        if (consecutiveSearches >= ZERO_FETCH_THRESHOLD) {
            return true;
        }
        if (consecutiveSearches >= FINGERPRINT_THRESHOLD && stableFingerprint) {
            return true;
        }
        return false;
    }

    /**
     * Feeds a completed {@code search_document_chunks} result into the C1 saturation tracker. Returns
     * {@code true} when the last {@link #SATURATION_STALE_THRESHOLD} document-retrieval rounds surfaced no
     * new substantive content <em>and</em> at least one substantive chunk has been fetched in full.
     */
    public synchronized boolean recordSearchSaturation(String resultJson) {
        if (disabledForTurn) {
            return false;
        }
        boolean addedNew = false;
        for (String chunkId : substantiveChunkIdsFromSearch(resultJson)) {
            if (searchSurfacedKeys.add(chunkId)) {
                addedNew = true;
            }
        }
        return advanceSaturation(addedNew);
    }

    /**
     * Feeds a completed {@code get_document_chunk} result into the C1 saturation tracker. A substantive
     * fetch (non-{@code signal-*}) both unlocks the firing gate and counts as progress the first time that
     * chunk's full body is surfaced.
     */
    public synchronized boolean recordFetchSaturation(String resultJson) {
        if (disabledForTurn) {
            return false;
        }
        String chunkId = substantiveChunkIdFromFetch(resultJson);
        boolean addedNew = false;
        if (chunkId != null) {
            substantiveChunkFetched = true;
            if (fetchedKeys.add(chunkId)) {
                addedNew = true;
            }
        }
        return advanceSaturation(addedNew);
    }

    private boolean advanceSaturation(boolean addedNew) {
        if (addedNew) {
            staleRetrievalRounds = 0;
        } else {
            staleRetrievalRounds++;
        }
        return substantiveChunkFetched && staleRetrievalRounds >= SATURATION_STALE_THRESHOLD;
    }

    static List<String> substantiveChunkIdsFromSearch(String resultJson) {
        List<String> ids = new ArrayList<>();
        if (resultJson == null || resultJson.isBlank()) {
            return ids;
        }
        try {
            JsonNode matches = MAPPER.readTree(resultJson).get("matches");
            if (matches == null || !matches.isArray()) {
                return ids;
            }
            for (JsonNode match : matches) {
                String chunkId = text(match, "chunkId");
                if (chunkId != null && !isSignalChunk(chunkId)) {
                    ids.add(chunkId);
                }
            }
        } catch (Exception ignored) {
            // unparseable result contributes no progress
        }
        return ids;
    }

    static String substantiveChunkIdFromFetch(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(resultJson);
            String status = text(root, "status");
            if (status != null && !"success".equals(status)) {
                return null;
            }
            String chunkId = text(root, "chunkId");
            if (chunkId == null || isSignalChunk(chunkId)) {
                return null;
            }
            return chunkId;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isSignalChunk(String chunkId) {
        return chunkId.startsWith(SIGNAL_CHUNK_PREFIX);
    }

    static String fingerprintFromResult(String resultJson, int requestedLimit) {
        if (resultJson == null || resultJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(resultJson);
            JsonNode matches = root.get("matches");
            if (matches == null || !matches.isArray()) {
                return "";
            }
            int k = Math.min(Math.max(requestedLimit, 1), FINGERPRINT_K_DEFAULT);
            List<String> pairs = new ArrayList<>();
            for (JsonNode match : matches) {
                if (pairs.size() >= k) {
                    break;
                }
                String docId = text(match, "docId");
                String chunkId = text(match, "chunkId");
                if (docId != null && chunkId != null) {
                    pairs.add(docId + "\u0001" + chunkId);
                }
            }
            return String.join("\u0002", pairs);
        } catch (Exception e) {
            return "";
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || !n.isTextual()) {
            return null;
        }
        String t = n.asText().trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * If the guard should fire and D1 repetition blocking did not already handle this dispatch, request forced
     * tool-none on the next loop round.
     */
    public static Optional<String> interceptSearchLoop(
            String toolName,
            String resultJson,
            int requestedLimit,
            boolean repetitionBlockedThisDispatch) {
        if (!"search_document_chunks".equals(toolName)) {
            return Optional.empty();
        }
        if (repetitionBlockedThisDispatch) {
            return Optional.empty();
        }
        String turnKey = FetchCachedReplayGuard.resolveCurrentTurnKey();
        DocumentSearchProgressGuard guard = DocumentSearchProgressGuardRegistry.acquireForTurn(turnKey);
        // Advance both trackers before deciding; saturation must observe every search.
        boolean fingerprintFired = guard.recordSearchCompletion(resultJson, requestedLimit);
        boolean saturationFired = guard.recordSearchSaturation(resultJson);
        if (saturationFired) {
            AgentToolContext.requestForcedSummaryForDocumentSearchLoop();
            AgentToolContext.requestGroundedCoverageSummary();
            return Optional.of("DOCUMENT_SEARCH_SATURATION_GUARD");
        }
        if (fingerprintFired) {
            AgentToolContext.requestForcedSummaryForDocumentSearchLoop();
            return Optional.of("DOCUMENT_SEARCH_LOOP_GUARD");
        }
        return Optional.empty();
    }

    public static void onGetDocumentChunk(String resultJson) {
        String turnKey = FetchCachedReplayGuard.resolveCurrentTurnKey();
        DocumentSearchProgressGuard guard = DocumentSearchProgressGuardRegistry.acquireForTurn(turnKey);
        // Reset the fingerprint/zero-fetch path (a fetch is intervening progress for §8) but keep advancing
        // the saturation tracker — re-fetching already-surfaced content is exactly the C1 loop to catch.
        guard.resetOnGetDocumentChunk();
        if (guard.recordFetchSaturation(resultJson)) {
            AgentToolContext.requestForcedSummaryForDocumentSearchLoop();
            AgentToolContext.requestGroundedCoverageSummary();
        }
    }

    public static void onNonDocumentTool() {
        String turnKey = FetchCachedReplayGuard.resolveCurrentTurnKey();
        DocumentSearchProgressGuardRegistry.acquireForTurn(turnKey).disableForTurn();
    }
}
