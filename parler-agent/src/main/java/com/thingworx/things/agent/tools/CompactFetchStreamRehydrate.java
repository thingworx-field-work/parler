package com.thingworx.things.agent.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;

/**
 * Stage-2 Stream rehydration for compact tool evidence ({@code docs/agent/context-compaction.md} §9;
 * {@code docs/agent/large-table-replay-control.md} §4.2). This includes compact {@code fetch_cached_result} rows,
 * compact numeric-history rows, and compact {@code query_property_history} value-stream history rows; accepted evidence
 * is restored as assistant prose instead of provider tool-result rows.
 *
 * <p>BP9 (U2 M4): never resurrects a live {@code ArtifactCache} entry from compact/chart evidence.
 * {@link #REHYDRATED_CACHE_LIVE_KEY} is set only when the {@code cacheId} already resolves in the
 * live JVM conversation cache at rehydrate time.
 */
public final class CompactFetchStreamRehydrate {

    private static final String FORMAT_NUMERIC_HISTORY_COMPACT_V1 = "parler.numeric_history.compact.v1";
    private static final String FORMAT_INFOTABLE_MATRIX_V1 = "parler.infotable.matrix.v1";

    /** Set on rehydrated bodies when the {@code cacheId} is not present in the live JVM conversation cache. */
    public static final String REHYDRATED_CACHE_HISTORICAL_KEY = "parlerRehydratedCacheHistorical";

    /** Optional marker when the {@code cacheId} still resolves in the live JVM conversation cache at rehydrate time. */
    public static final String REHYDRATED_CACHE_LIVE_KEY = "parlerRehydratedCacheLive";

    /**
     * Prefix for compact tool JSON restored from Stream as assistant prose (not {@code Role.TOOL}) so provider
     * serializers never emit orphan {@code tool_result} / {@code tool} rows.
     */
    public static final String STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX =
            "[Historical compact tool evidence from this conversation, restored from durable stream]\n";

    /** Reject bodies whose sample {@code rows} array is larger than this (raw full-page replay guard). */
    private static final int MAX_REHYDRATE_SAMPLE_ROWS = 200;

    private CompactFetchStreamRehydrate() {}

    /**
     * @return {@code true} when this tool row must not be surfaced as compact fetch evidence (HITL synthetic outcomes,
     *         non-JSON, empty, etc.). Does not reject valid compact fetch bodies.
     */
    public static boolean shouldSkipToolRowForStreamRehydrate(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        String t = content.trim();
        if (!t.startsWith("{")) {
            return true;
        }
        try {
            JSONObject o = new JSONObject(t);
            if ("skipped".equals(o.optString("status"))) {
                return true;
            }
            String code = o.optString("code");
            if (!code.isEmpty() && code.startsWith("HITL")) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Accept set: {@link FetchCachedCompactPersistFormat#FORMAT_VALUE_COMPACT_V1} marker, numeric-history compact
     * evidence, numeric-history matrix evidence, value-stream property-history compact (native or matrix-sealed), or
     * legacy structural compact fetch success with a {@code columns} array (pre-marker Stream rows). Rejects raw large
     * bodies and PASSWORD column metadata.
     */
    public static boolean acceptsStreamCompactFetchEvidence(JSONObject o) {
        if (o == null) {
            return false;
        }
        if (!"success".equalsIgnoreCase(o.optString("status"))) {
            return false;
        }
        if (FetchCachedCompactPersistFormat.FORMAT_VALUE_COMPACT_V1.equals(
                o.optString(FetchCachedCompactPersistFormat.FORMAT_KEY))) {
            return !containsPasswordColumn(o) && !hasPointsArray(o) && !isOversizedSampleArrays(o);
        }
        if (acceptsNumericHistoryCompactEvidence(o)) {
            return true;
        }
        if (acceptsValueStreamHistoryCompactEvidence(o)) {
            return true;
        }
        if (!FetchCachedCompactPersistFormat.isLegacyStructuralCompactFetchSuccess(o)) {
            return false;
        }
        if (!o.has("columns") || !(o.get("columns") instanceof JSONArray)) {
            return false;
        }
        return !containsPasswordColumn(o) && !hasPointsArray(o) && !isOversizedSampleArrays(o);
    }

    /**
     * @return adjusted JSON for a persisted compact fetch tool row, or {@code null} when the row must be skipped
     */
    public static String prepareRehydratedToolContent(String conversationId, String toolJson) {
        if (shouldSkipToolRowForStreamRehydrate(toolJson)) {
            return null;
        }
        JSONObject o;
        try {
            o = new JSONObject(toolJson);
        } catch (Exception e) {
            return null;
        }
        if (!acceptsStreamCompactFetchEvidence(o)) {
            return null;
        }
        JSONObject out = new JSONObject(toolJson);
        annotateCacheContinuity(conversationId, out);
        stripChartBlockPersistenceFields(out);
        return out.toString();
    }

    private static void stripChartBlockPersistenceFields(JSONObject out) {
        out.remove("chartBlock");
        out.remove("chartBlockPersisted");
        out.remove("chartBlockOmittedReason");
        out.remove("chartBlockPointCount");
        out.remove("chartBlockBytes");
        out.remove("chartBlockPointLimit");
        out.remove("chartBlockByteLimit");
    }

    private static void annotateCacheContinuity(String conversationId, JSONObject out) {
        String cacheId = out.optString("cacheId");
        if (cacheId == null || cacheId.isEmpty()) {
            return;
        }
        try {
            if (InvokeServiceExecutor.lookupCachedInfotableForConversation(conversationId, cacheId) != null) {
                out.put(REHYDRATED_CACHE_LIVE_KEY, true);
            } else {
                out.put(REHYDRATED_CACHE_HISTORICAL_KEY, true);
            }
        } catch (ArtifactCacheException e) {
            if (e.code() == ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE) {
                throw e;
            }
            // Continuity annotation is best-effort. A fault in one live-indexed payload must not
            // abort conversation rehydration or be mislabeled as an ordinary historical miss.
            out.remove(REHYDRATED_CACHE_LIVE_KEY);
            out.remove(REHYDRATED_CACHE_HISTORICAL_KEY);
        }
    }

    private static boolean containsPasswordColumn(JSONObject o) {
        JSONArray cols = o.optJSONArray("columns");
        if (cols == null) {
            return false;
        }
        for (int i = 0; i < cols.length(); i++) {
            JSONObject c = cols.optJSONObject(i);
            if (c != null && "PASSWORD".equalsIgnoreCase(c.optString("baseType"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean acceptsNumericHistoryCompactEvidence(JSONObject o) {
        String format = o.optString(FetchCachedCompactPersistFormat.FORMAT_KEY);
        if (!FORMAT_NUMERIC_HISTORY_COMPACT_V1.equals(format) && !FORMAT_INFOTABLE_MATRIX_V1.equals(format)) {
            return false;
        }
        String resultKind = o.optString("resultKind");
        if (!"NUMERIC_HISTORY_INLINE".equals(resultKind) && !"NUMERIC_HISTORY_AGGREGATES".equals(resultKind)) {
            return false;
        }
        if (!o.has("columns") || !(o.get("columns") instanceof JSONArray)) {
            return false;
        }
        return !containsPasswordColumn(o) && !hasPointsArray(o) && !isOversizedSampleArrays(o);
    }

    /**
     * Value-stream branch of {@code query_property_history}: same structural guards as numeric compact, but
     * {@code resultKind} is {@link InvokeServiceExecutor#RESULT_KIND_VALUE_STREAM_HISTORY_INLINE} and
     * {@code $format} is native compact or matrix-sealed (still value-stream result kind).
     */
    private static boolean acceptsValueStreamHistoryCompactEvidence(JSONObject o) {
        String format = o.optString(FetchCachedCompactPersistFormat.FORMAT_KEY);
        if (!InvokeServiceExecutor.PROPERTY_HISTORY_VALUE_STREAM_COMPACT_FORMAT.equals(format)
                && !FORMAT_INFOTABLE_MATRIX_V1.equals(format)) {
            return false;
        }
        if (!InvokeServiceExecutor.RESULT_KIND_VALUE_STREAM_HISTORY_INLINE.equals(o.optString("resultKind"))) {
            return false;
        }
        if (!o.has("columns") || !(o.get("columns") instanceof JSONArray)) {
            return false;
        }
        return !containsPasswordColumn(o) && !hasPointsArray(o) && !isOversizedSampleArrays(o);
    }

    private static boolean hasPointsArray(JSONObject o) {
        return o.optJSONArray("points") != null;
    }

    private static boolean isOversizedSampleArrays(JSONObject o) {
        return isOversizedArray(o, "rows")
                || isOversizedArray(o, "sampleRows")
                || isOversizedArray(o, "rootEntityList")
                || isOversizedArray(o, "sampleRootEntityList");
    }

    private static boolean isOversizedArray(JSONObject o, String key) {
        JSONArray rows = o.optJSONArray(key);
        return rows != null && rows.length() > MAX_REHYDRATE_SAMPLE_ROWS;
    }
}
