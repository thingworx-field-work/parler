package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * U1B E10 / E9/B1: shared history row-limit precedence for property-history executors.
 * Published argument is {@code maxItems}; retained aliases {@code maxRows} / {@code maxPoints}.
 * When multiple are present: {@code maxItems} &gt; {@code maxRows} &gt; {@code maxPoints}. Pure — no I/O.
 *
 * @see docs/agent/nearterm/cache-correctness-foundation.md §B3.4
 */
public final class HistoryRowLimitPrecedence {

    /** Default before clamp when neither published field nor aliases are present. */
    public static final int DEFAULT_LIMIT = 1000;

    private HistoryRowLimitPrecedence() {}

    /**
     * Resolved limit plus echo fields for success extras ({@code maxItemsRequested} /
     * {@code maxItemsEffective}).
     */
    public static final class Resolved {
        public final int requested;
        public final int effective;
        /** {@code maxItems}, {@code maxRows}, {@code maxPoints}, or {@code default}. */
        public final String sourceField;

        Resolved(int requested, int effective, String sourceField) {
            this.requested = requested;
            this.effective = effective;
            this.sourceField = sourceField;
        }
    }

    /**
     * Resolve the requested history row limit from tool JSON, then clamp to {@code [1, maxCap]}.
     *
     * <ul>
     *   <li>{@code maxItems} present → that field (wins over aliases)</li>
     *   <li>else {@code maxRows} present → that field (wins over {@code maxPoints})</li>
     *   <li>else {@code maxPoints} present → that field</li>
     *   <li>neither → {@link #DEFAULT_LIMIT}</li>
     * </ul>
     */
    public static int resolveHistoryRowLimit(JsonNode root, int maxCap) {
        return resolve(root, maxCap).effective;
    }

    /** Full resolve with requested/effective echo values. */
    public static Resolved resolve(JsonNode root, int maxCap) {
        int selected = DEFAULT_LIMIT;
        String source = "default";
        if (root != null && root.has("maxItems")) {
            selected = root.get("maxItems").asInt(DEFAULT_LIMIT);
            source = "maxItems";
        } else if (root != null && root.has("maxRows")) {
            selected = root.get("maxRows").asInt(DEFAULT_LIMIT);
            source = "maxRows";
        } else if (root != null && root.has("maxPoints")) {
            selected = root.get("maxPoints").asInt(DEFAULT_LIMIT);
            source = "maxPoints";
        }
        int cap = maxCap < 1 ? 1 : maxCap;
        int effective = Math.min(Math.max(1, selected), cap);
        return new Resolved(selected, effective, source);
    }
}
