package com.thingworx.things.agent.playbook;

import org.json.JSONObject;

/**
 * Deterministic value ordering for generic row ops ({@code sort}, {@code top_n}) — section 8.3–8.4 of
 * {@code docs/agent/playbook-generic-ops-foundation.md}: nulls last for both directions, numbers
 * compared numerically, otherwise {@link String#compareTo} on {@link String#valueOf}.
 * Row predicates use {@link PlaybookPredicateValueOps#compare} (nulls-first total order); keep the two distinct.
 */
public final class PlaybookGenericRowOrdering {

    private PlaybookGenericRowOrdering() {}

    /**
     * Compare two field values for a single sort key. Null / JSON-null / missing treated as null;
     * nulls sort last for both ascending and descending.
     */
    public static int compareFieldValues(Object a, Object b, boolean ascending) {
        boolean aNull = isNullish(a);
        boolean bNull = isNullish(b);
        if (aNull && bNull) {
            return 0;
        }
        if (aNull) {
            return 1;
        }
        if (bNull) {
            return -1;
        }
        int core = compareNonNull(a, b);
        return ascending ? core : -core;
    }

    private static boolean isNullish(Object v) {
        return v == null || v == JSONObject.NULL;
    }

    private static int compareNonNull(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }
}
