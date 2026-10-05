package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Resolves {@code QueryAlertHistory} {@code oldestFirst} from optional {@code order} enum and/or legacy
 * {@code oldestFirst} boolean (see {@code docs/operations/alert-solution.md} §11).
 */
public final class AlertHistorySortOrder {

    private AlertHistorySortOrder() {}

    /**
     * @param root tool arguments JSON node
     * @return platform {@code oldestFirst} flag
     * @throws IllegalArgumentException unknown {@code order}, or {@code order} disagrees with explicit
     *         {@code oldestFirst}
     */
    public static boolean resolveOldestFirst(JsonNode root) {
        String order = text(root, "order");
        boolean hasOldestFirstKey = root.has("oldestFirst") && !root.get("oldestFirst").isNull();
        boolean fromBool = root.path("oldestFirst").asBoolean(false);

        if (order == null || order.isEmpty()) {
            return fromBool;
        }
        boolean fromOrder = parseOrderEnum(order);
        if (hasOldestFirstKey && fromOrder != fromBool) {
            throw new IllegalArgumentException("order disagrees with oldestFirst; omit one of them");
        }
        return fromOrder;
    }

    private static boolean parseOrderEnum(String raw) {
        String s = raw.trim();
        if ("oldest_first".equalsIgnoreCase(s)) {
            return true;
        }
        if ("newest_first".equalsIgnoreCase(s)) {
            return false;
        }
        throw new IllegalArgumentException("Unknown order: " + raw + " (use oldest_first or newest_first)");
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        String t = n.asText();
        return t == null || t.isBlank() ? null : t.trim();
    }
}
