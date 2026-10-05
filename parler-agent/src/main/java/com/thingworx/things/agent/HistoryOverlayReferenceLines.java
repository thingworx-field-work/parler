package com.thingworx.things.agent;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Strict {@code yReferenceLines} parsing for {@code build_history_overlay_chart}.
 * Non-finite or malformed entries fail; unknown roles normalize to {@code limit}.
 */
public final class HistoryOverlayReferenceLines {

    private static final Set<String> REF_ROLES = Set.of(
            "usl", "ucl", "lcl", "lsl", "target", "limit", "warning");

    private HistoryOverlayReferenceLines() {}

    public static final class ParseException extends Exception {
        public final String code;

        public ParseException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * @return wire {@code y_reference_lines} array, or empty when input absent
     */
    public static JSONArray parseStrict(JsonNode yRef) throws ParseException {
        if (yRef == null || yRef.isNull()) {
            return new JSONArray();
        }
        if (!yRef.isArray()) {
            throw new ParseException("HISTORY_OVERLAY_INVALID_REFERENCE_LINE",
                    "yReferenceLines must be an array.");
        }
        if (yRef.size() > 12) {
            throw new ParseException("HISTORY_OVERLAY_INVALID_REFERENCE_LINE",
                    "At most 12 yReferenceLines allowed.");
        }
        JSONArray cleaned = new JSONArray();
        for (JsonNode item : yRef) {
            if (item == null || !item.isObject()) {
                throw new ParseException("HISTORY_OVERLAY_INVALID_REFERENCE_LINE",
                        "Each yReferenceLines entry must be an object.");
            }
            JsonNode yNode = item.get("y");
            if (yNode == null || yNode.isNull() || !yNode.isNumber()) {
                throw new ParseException("HISTORY_OVERLAY_INVALID_REFERENCE_LINE",
                        "Each yReferenceLines entry requires a finite numeric y.");
            }
            double y = yNode.asDouble();
            if (Double.isNaN(y) || Double.isInfinite(y)) {
                throw new ParseException("HISTORY_OVERLAY_INVALID_REFERENCE_LINE",
                        "Each yReferenceLines entry requires a finite numeric y.");
            }
            JSONObject one = new JSONObject();
            one.put("y", y);
            if (item.has("label") && item.get("label").isTextual()) {
                String lab = item.get("label").asText().trim();
                if (!lab.isEmpty()) {
                    one.put("label", lab);
                }
            }
            String role = "limit";
            if (item.has("role") && item.get("role").isTextual()) {
                String rs = item.get("role").asText().trim().toLowerCase(Locale.ROOT);
                if (REF_ROLES.contains(rs)) {
                    role = rs;
                }
            }
            one.put("role", role);
            cleaned.put(one);
        }
        return cleaned;
    }
}
