package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Shape checks for ISO-style time bound keys on built-in tool JSON — present non-string values must not be treated as
 * absent.
 */
public final class ToolJsonTimeBounds {

    private ToolJsonTimeBounds() {}

    /**
     * @return {@code null} if every listed field is absent, null, or textual; otherwise a message suitable for
     *         {@code INVALID_TIME_SPEC_SHAPE} (starts with field name).
     */
    public static String validateOptionalFieldsAreTextual(JsonNode root, String... fieldNames) {
        if (root == null || fieldNames == null) {
            return null;
        }
        for (String f : fieldNames) {
            JsonNode n = root.get(f);
            if (n != null && !n.isNull() && !n.isTextual()) {
                return f + " must be a JSON string.";
            }
        }
        return null;
    }

    /**
     * Identifies <em>which</em> listed field carries the
     * non-textual value, so the executor can populate the wire {@code rejectedParameter} field on
     * {@code INVALID_TIME_SPEC_SHAPE} envelopes per the cross-tool convention. Returns {@code null} when shape is OK; otherwise the first offending field name (in the
     * order passed). Companion to {@link #validateOptionalFieldsAreTextual} — they share the same
     * traversal contract and must agree on what counts as a shape violation.
     */
    public static String firstNonTextualField(JsonNode root, String... fieldNames) {
        if (root == null || fieldNames == null) {
            return null;
        }
        for (String f : fieldNames) {
            JsonNode n = root.get(f);
            if (n != null && !n.isNull() && !n.isTextual()) {
                return f;
            }
        }
        return null;
    }
}
