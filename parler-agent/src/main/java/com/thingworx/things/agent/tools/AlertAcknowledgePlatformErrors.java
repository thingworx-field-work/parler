package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Normalizes platform exceptions from {@code AlertFunctions} acknowledge paths (summary probe for
 * {@code specific_alerts}, plus ack services) into stable tool JSON
 * ({@code status:error}, {@code code}, {@code message}) per {@code docs/operations/alert-solution.md} §11.
 */
public final class AlertAcknowledgePlatformErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlertAcknowledgePlatformErrors() {}

    public static String normalizedPlatformJson(Throwable t) throws JsonProcessingException {
        String raw = t == null ? "" : String.valueOf(t.getMessage());
        if (raw == null || raw.isEmpty()) {
            raw = t != null ? t.getClass().getSimpleName() : "unknown";
        }
        String lower = raw.toLowerCase();
        String code = "PLATFORM_ALERT_SERVICE_ERROR";
        if (containsAny(lower, "not authorized", "unauthorized", "forbidden", "access denied", "permission")) {
            code = "PLATFORM_ALERT_PERMISSION";
        } else if (containsAny(lower, "not found", "does not exist", "unknown entity", "invalid thing")) {
            code = "PLATFORM_ALERT_NOT_FOUND";
        } else if (containsAny(lower, "property", "invalid") && containsAny(lower, "not exist", "missing")) {
            code = "PLATFORM_ALERT_BAD_INPUT";
        }
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "error");
        o.put("code", code);
        o.put("message", raw);
        return MAPPER.writeValueAsString(o);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (n != null && haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
