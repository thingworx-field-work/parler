package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.joda.time.DateTime;

/**
 * Optional explicit ISO-8601 bounds for tools that accept {@code startTime}/{@code endTime} (and aliases). Non-blank
 * strings must parse; absent pairs may both be absent for platform defaults — never treat parse failure as “no bound”.
 *
 * <p><b>Joda at boundary:</b> uses {@link DateTime#parse}; aligns with §4.1 “Joda only at ThingWorx boundaries” until a
 * wider {@link java.time.Instant} migration.</p>
 *
 * <p>{@link ParseOutcome} carries an optional {@code failedField} that identifies which input parameter
 * caused the parse failure, preserving the user-supplied alias label (e.g. {@code start} vs {@code startTime}).
 * This keeps {@code INVALID_TIME_RANGE} inside the cross-tool {@code rejectedParameter} envelope contract.
 * The "both or neither" mutual-exclusion error is intentionally cross-cutting (no single offending field —
 * {@code failedField} stays {@code null}); the LLM repair path is "supply the missing bound", which the
 * message text already states.</p>
 */
public final class ExplicitIsoTimeBounds {

    private ExplicitIsoTimeBounds() {}

    public static final class ParseOutcome {
        public final boolean ok;
        public final DateTime start;
        public final DateTime end;
        public final String errorCode;
        public final String errorMessage;
        /**
         * When set, names the user-supplied parameter (alias label preserved — e.g. {@code start}
         * vs {@code startTime}) that triggered the failure. {@code null} for cross-cutting failures
         * ({@code "both or neither"} mutual-exclusion). Surfaced as the wire {@code rejectedParameter}
         * field via {@link BuiltInToolTimeErrorJson#error(String, String, String)} so the cross-tool
         * envelope contract has no carve-out for {@code INVALID_TIME_RANGE}.
         */
        public final String failedField;

        private ParseOutcome(boolean ok, DateTime start, DateTime end, String errorCode, String errorMessage,
                String failedField) {
            this.ok = ok;
            this.start = start;
            this.end = end;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.failedField = failedField;
        }

        static ParseOutcome success(DateTime start, DateTime end) {
            return new ParseOutcome(true, start, end, null, null, null);
        }

        static ParseOutcome failure(String code, String message) {
            return new ParseOutcome(false, null, null, code, message, null);
        }

        static ParseOutcome failureOn(String code, String message, String failedField) {
            return new ParseOutcome(false, null, null, code, message, failedField);
        }
    }

    /**
     * Numeric history semantics: both bounds absent/blank → success with {@code null}/{@code null} (caller applies
     * platform default window). Both required together if either is present.
     *
     * <p>Backward-compatible overload: defaults the failed-field labels to {@code "startTime"} / {@code "endTime"}.
     * Callers that know which alias the user supplied should call
     * {@link #parseOptionalPair(String, String, String, String)} so the wire {@code rejectedParameter}
     * preserves the exact alias label.</p>
     *
     * @param startStr from {@code startTime} or {@code start} alias (already resolved to one string or null)
     * @param endStr   from {@code endTime} or {@code end}
     */
    public static ParseOutcome parseOptionalPair(String startStr, String endStr) {
        return parseOptionalPair(startStr, endStr, "startTime", "endTime");
    }

    /**
     * Alias-aware overload: when the caller has selected one of multiple aliases (e.g. {@code start}
     * vs {@code startTime}), pass the chosen label here so {@link ParseOutcome#failedField} preserves it.
     */
    public static ParseOutcome parseOptionalPair(String startStr, String endStr, String startLabel,
            String endLabel) {
        String sLabel = (startLabel == null || startLabel.isBlank()) ? "startTime" : startLabel;
        String eLabel = (endLabel == null || endLabel.isBlank()) ? "endTime" : endLabel;
        DateTime start = null;
        DateTime end = null;
        if (startStr != null && !startStr.isBlank()) {
            try {
                start = DateTime.parse(startStr.trim());
            } catch (IllegalArgumentException e) {
                return ParseOutcome.failureOn("INVALID_TIME_RANGE",
                        sLabel + " is not valid ISO-8601; use a parseable instant or omit for platform default.",
                        sLabel);
            }
        }
        if (endStr != null && !endStr.isBlank()) {
            try {
                end = DateTime.parse(endStr.trim());
            } catch (IllegalArgumentException e) {
                return ParseOutcome.failureOn("INVALID_TIME_RANGE",
                        eLabel + " is not valid ISO-8601; use a parseable instant or omit for platform default.",
                        eLabel);
            }
        }
        if (start == null ^ end == null) {
            // Cross-cutting: the LLM must supply the missing bound. failedField stays null per the
            // cross-tool convention for "no single offending parameter" — the message already
            // spells out the missing-bound contract.
            return ParseOutcome.failure("INVALID_TIME_RANGE",
                    "Provide both " + sLabel + " and " + eLabel + " (ISO-8601), or neither for platform default window. Aliases: start, end.");
        }
        return ParseOutcome.success(start, end);
    }

    /**
     * {@code query_alert_history} semantics: each of {@code startTime} and {@code endTime} may be independently
     * absent (see {@link AlertHistoryTimeRange#resolveParsed}); non-blank values must parse as ISO-8601.
     *
     * <p>Parse failures carry {@code failedField} ({@code "startTime"} or {@code "endTime"}).</p>
     */
    public static ParseOutcome parseAlertHistoryOptionalStrings(String startStr, String endStr) {
        DateTime start = null;
        DateTime end = null;
        if (startStr != null && !startStr.isBlank()) {
            try {
                start = DateTime.parse(startStr.trim());
            } catch (IllegalArgumentException e) {
                return ParseOutcome.failureOn("INVALID_TIME_RANGE",
                        "startTime is not valid ISO-8601; use a parseable instant or omit.",
                        "startTime");
            }
        }
        if (endStr != null && !endStr.isBlank()) {
            try {
                end = DateTime.parse(endStr.trim());
            } catch (IllegalArgumentException e) {
                return ParseOutcome.failureOn("INVALID_TIME_RANGE",
                        "endTime is not valid ISO-8601; use a parseable instant or omit.",
                        "endTime");
            }
        }
        return ParseOutcome.success(start, end);
    }

    /**
     * Picks the user-supplied alias label for a start-bound when a tool accepts multiple
     * names (e.g. {@code startTime} preferred, {@code start} fallback). Returns the canonical label of
     * whichever alias has a non-blank value; if neither, returns the first label (the conventional default).
     * The companion value resolution must use {@code firstNonBlank} in the same order so label and value
     * agree.
     */
    public static String pickAliasLabel(JsonNode root, String preferred, String fallback) {
        if (root != null && preferred != null) {
            JsonNode n = root.get(preferred);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return preferred;
            }
        }
        if (root != null && fallback != null) {
            JsonNode n = root.get(fallback);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return fallback;
            }
        }
        return preferred != null ? preferred : fallback;
    }
}
