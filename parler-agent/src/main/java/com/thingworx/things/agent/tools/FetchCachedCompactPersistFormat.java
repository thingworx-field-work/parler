package com.thingworx.things.agent.tools;

import org.json.JSONObject;

/**
 * Marks compact {@code fetch_cached_result} tool JSON persisted on {@link com.thingworx.things.agent.AgentMessageStream}
 * ({@code docs/agent/context-compaction.md} Slice E; {@code docs/agent/large-table-replay-control.md} §4.2).
 *
 * <p><b>Write path:</b> only invoke {@link #stampWhenApplicable} for tool bodies already known to be on the
 * fetch-cached split lane (e.g. {@link AgentToolContext#peekFetchCachedStreamJsonForToolCall} non-empty). The structural
 * detector alone is not sufficient to distinguish arbitrary tools that reuse similar field names.
 */
public final class FetchCachedCompactPersistFormat {

    /** Top-level JSON key for persisted compact fetch marker (must not collide with Infotable keys). */
    public static final String FORMAT_KEY = "$format";

    /** Marker for compact LLM-lane {@code fetch_cached_result} bodies written to Stream storage. */
    public static final String FORMAT_VALUE_COMPACT_V1 = "parler.fetch_cached_result.compact.v1";

    private FetchCachedCompactPersistFormat() {}

    /**
     * If {@code toolJson} parses as a legacy structural compact fetch success body, inserts {@link #FORMAT_KEY} /
     * {@link #FORMAT_VALUE_COMPACT_V1} when absent. Otherwise returns {@code toolJson} unchanged.
     */
    public static String stampWhenApplicable(String toolJson) {
        if (toolJson == null || toolJson.isEmpty()) {
            return toolJson;
        }
        try {
            JSONObject o = new JSONObject(toolJson);
            if (FORMAT_VALUE_COMPACT_V1.equals(o.optString(FORMAT_KEY))) {
                return toolJson;
            }
            if (!isLegacyStructuralCompactFetchSuccess(o)) {
                return toolJson;
            }
            o.put(FORMAT_KEY, FORMAT_VALUE_COMPACT_V1);
            return o.toString();
        } catch (Exception e) {
            return toolJson;
        }
    }

    /**
     * Detects compact LLM-lane {@code fetch_cached_result} success JSON (sample/meta, not full inline page) per
     * {@code large-table-replay-control.md} §4.2 suggested shape — {@code status=success}, {@code cacheId}, and
     * {@code sampleOnly} or {@code rowsOmitted}.
     */
    public static boolean isLegacyStructuralCompactFetchSuccess(JSONObject o) {
        if (o == null) {
            return false;
        }
        if (!"success".equals(o.optString("status"))) {
            return false;
        }
        String cacheId = o.optString("cacheId");
        if (cacheId == null || cacheId.isEmpty()) {
            return false;
        }
        return o.optBoolean("sampleOnly", false) || o.optBoolean("rowsOmitted", false);
    }
}
