package com.thingworx.things.agent.playbook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shared value emptiness and ordering for V1b {@code condition} predicates and future
 * generic-derive row predicates. Keeps one implementation of {@code is_empty} / comparison
 * semantics (see {@code docs/agent/playbook-generic-ops-foundation.md} §7).
 */
public final class PlaybookPredicateValueOps {

    private PlaybookPredicateValueOps() {}

    /** True for null, JSON null, blank string, empty array, or empty object. */
    public static boolean isEmpty(Object v) {
        if (v == null || v == JSONObject.NULL) {
            return true;
        }
        if (v instanceof String) {
            return ((String) v).isBlank();
        }
        if (v instanceof JSONArray) {
            return ((JSONArray) v).length() == 0;
        }
        if (v instanceof JSONObject) {
            return ((JSONObject) v).length() == 0;
        }
        return false;
    }

    /**
     * Total order for predicate comparisons: null sorts before non-null; two numbers use
     * numeric compare; otherwise {@link String#compareTo} on {@link String#valueOf}.
     * (Sort and {@code top_n} keys use nulls-last via {@link PlaybookGenericRowOrdering}; do not
     * conflate the two conventions.)
     */
    public static int compare(Object left, Object right) {
        if (left == null || left == JSONObject.NULL) {
            return right == null || right == JSONObject.NULL ? 0 : -1;
        }
        if (right == null || right == JSONObject.NULL) {
            return 1;
        }
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }
}
