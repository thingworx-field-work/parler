package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Cross-tool wire envelope for natural-time error responses on curated built-ins
 * ({@code query_alert_history}, {@code query_numeric_property_history}, {@code query_stream_data},
 * {@code query_value_stream_property_history}; {@code query*log} wrappers remain deferred — see
 * {@code time-interpretation.md} §15).
 *
 * <p>Mirrors the wire-shape contract that
 * {@link InvokeServiceErrorJson} uses for {@code UNSUPPORTED_RELATIVE_LITERAL}, but
 * for natural-time conflict / shape codes (e.g. {@code TIME_PHRASE_VS_PRESET_CONFLICT},
 * {@code INVALID_TIME_SPEC_SHAPE}, {@code MISSING_OR_INVALID_USER_TIMEZONE},
 * {@code UNSUPPORTED_CALENDAR_PHRASE}). Adds the optional structured field {@code rejectedParameter}
 * (path-style — e.g. {@code "calendarPhrase"}, {@code "relativeDuration"}, {@code "startTime"}) so the
 * LLM can surgically retry without parsing the human-readable {@code message}.</p>
 *
 * <p><b>Cross-tool stability convention.</b> {@code rejectedParameter} is standardized across step-6
 * time-related errors when a single field is identifiable, and omitted when the conflict spans multiple
 * fields (e.g. mutual-exclusion conflicts). The classifier-style {@code rejectionReason} field is
 * intentionally <b>not</b> emitted by this helper — that field is specific to
 * {@link InvokeServiceDatetimeLiteralDefense}'s pre-parse classifier (3 enum values today). Other
 * conflict codes have a different shape (mutually-exclusive-fields detection, missing timezone, …) and
 * should not pretend to share an enum.</p>
 *
 * <p><b>What is NOT on the wire:</b> raw rejected values. Same PII boundary as
 * {@link InvokeServiceErrorJson}: anything sensitive belongs in the truncated server log, not in the
 * LLM-facing response.</p>
 */
public final class BuiltInToolTimeErrorJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BuiltInToolTimeErrorJson() {}

    /**
     * Build the standard time-error wire JSON. {@code rejectedParameter} may be {@code null} for
     * cross-cutting conflicts that have no single offending field — the field is then simply omitted
     * from the JSON envelope (NOT emitted as JSON {@code null}).
     *
     * <p>Public so
     * {@link com.thingworx.things.agent.AgentThing#executeCustomTool} can route
     * {@link CustomToolNaturalTimeException} through the same envelope as curated built-ins.</p>
     */
    public static String error(String code, String message, String rejectedParameter) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code == null ? "" : code);
            o.put("message", message == null ? "" : message);
            if (rejectedParameter != null && !rejectedParameter.isBlank()) {
                o.put("rejectedParameter", rejectedParameter);
            }
            return MAPPER.writeValueAsString(o);
        } catch (Exception serFail) {
            return "{\"status\":\"error\",\"code\":\"" + (code == null ? "" : code)
                    + "\",\"message\":\"serialization failed\"}";
        }
    }

    /**
     * Convenience shortcut for the common {@code BuiltInToolNaturalTimeWindow.Outcome} → wire JSON path.
     * Caller must guarantee {@code outcome.errorCode != null}.
     */
    public static String fromNaturalTimeOutcome(BuiltInToolNaturalTimeWindow.Outcome outcome) {
        return error(outcome.errorCode, outcome.errorMessage, outcome.rejectedParameter);
    }
}
