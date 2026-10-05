package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure parsing / validation for {@code tabulate_cached_result} paging fields ({@code limit}, {@code offset})
 * and group-mode output caps. Extracted for offline JUnit (no ThingWorx static init); values must stay aligned
 * with {@link CachedTabularToolsExecutor} and {@code docs/agent/cached_tabular_tools.md}.
 */
public final class CachedTabularTabulatePaging {

    /** Default {@code limit} for {@code sort_topn} when the field is omitted. */
    public static final int SORT_TOPN_DEFAULT_LIMIT = 50;
    /** Upper bound for {@code sort_topn} {@code limit} and for group modes when {@code limit} is present. */
    public static final int MAX_LIMIT = 500;

    private CachedTabularTabulatePaging() {}

    /**
     * Parses {@code maxItems} and {@code offset} from the tool arguments object (query-spec §5.1).
     *
     * @return {@code int[2]} — {@code [0]} = maxItems, {@code [1]} = offset
     */
    public static int[] parseMaxItemsAndOffset(JsonNode root, int defaultMaxItems) {
        int maxItems = root.has("maxItems") ? root.get("maxItems").asInt(defaultMaxItems) : defaultMaxItems;
        int offset = root.has("offset") ? root.get("offset").asInt(0) : 0;
        if (maxItems < 1 || maxItems > MAX_LIMIT) {
            throw new IllegalArgumentException("maxItems must be between 1 and " + MAX_LIMIT + ".");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0.");
        }
        return new int[] {maxItems, offset};
    }

    /**
     * Output row cap for {@code group_count} / {@code group_aggregate}. If {@code maxItems} is absent, at most
     * {@link #MAX_LIMIT} groups are returned. If present, must satisfy {@code 1 ≤ maxItems ≤ MAX_LIMIT}.
     */
    public static int parseGroupOutputMaxItems(JsonNode root) {
        if (!root.has("maxItems")) {
            return MAX_LIMIT;
        }
        int lim = root.get("maxItems").asInt();
        if (lim < 1 || lim > MAX_LIMIT) {
            throw new IllegalArgumentException("maxItems must be between 1 and " + MAX_LIMIT + ".");
        }
        return lim;
    }
}
