package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * {@link BuiltInToolTimeErrorJson} must emit the same {@code rejectedParameter} cross-tool field that
 * {@link InvokeServiceErrorJson} emits for {@code UNSUPPORTED_RELATIVE_LITERAL}, but for natural-time
 * conflict / shape codes.
 *
 * <p>Pinned-negative invariants (mirrors the {@link InvokeServiceErrorJson} regression-prevention pattern):</p>
 * <ul>
 *   <li>The classifier-style {@code rejectionReason} field is intentionally <b>not</b> emitted by this
 *       helper — it is specific to {@link InvokeServiceDatetimeLiteralDefense}'s pre-parse classifier.
 *       Other conflict codes have a different shape and should not pretend to share an enum.</li>
 *   <li>Raw values are not on the wire (PII boundary; same as {@link InvokeServiceErrorJson}).</li>
 *   <li>{@code rejectedParameter} is <b>omitted</b> (not emitted as JSON {@code null}) when the conflict
 *       spans multiple fields — distinguishes "no offending parameter" from "I forgot to populate this".</li>
 * </ul>
 */
class BuiltInToolTimeErrorJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void errorEmitsRejectedParameterWhenIdentifiable() throws Exception {
        String json = BuiltInToolTimeErrorJson.error(
                "UNSUPPORTED_CALENDAR_PHRASE",
                "calendarPhrase must name a single local day using today, yesterday, or tomorrow (v1).",
                "calendarPhrase");
        JsonNode root = MAPPER.readTree(json);

        assertEquals("error", root.get("status").asText());
        assertEquals("UNSUPPORTED_CALENDAR_PHRASE", root.get("code").asText());
        assertNotNull(root.get("message"));
        assertEquals("calendarPhrase", root.get("rejectedParameter").asText());
    }

    @Test
    void errorOmitsRejectedParameterWhenNull() throws Exception {
        String json = BuiltInToolTimeErrorJson.error(
                "TIME_PHRASE_COMBINED_INVALID",
                "Set at most one of calendarPhrase and relativeDuration.",
                null);
        JsonNode root = MAPPER.readTree(json);

        assertEquals("TIME_PHRASE_COMBINED_INVALID", root.get("code").asText());
        // Cross-cutting conflicts: the field is OMITTED, not emitted as null. Pinning the omission
        // prevents future drift where a maintainer accidentally serializes JSON null and downstream
        // consumers can't distinguish "no offending parameter" from "I forgot to populate".
        assertFalse(root.has("rejectedParameter"),
                "rejectedParameter must be OMITTED for cross-cutting conflicts, not emitted as null");
    }

    @Test
    void errorOmitsRejectedParameterWhenBlank() throws Exception {
        String json = BuiltInToolTimeErrorJson.error("CODE", "msg", "");
        JsonNode root = MAPPER.readTree(json);
        assertFalse(root.has("rejectedParameter"));

        String json2 = BuiltInToolTimeErrorJson.error("CODE", "msg", "   ");
        JsonNode root2 = MAPPER.readTree(json2);
        assertFalse(root2.has("rejectedParameter"));
    }

    @Test
    void errorDoesNotEmitRejectionReasonField() throws Exception {
        // Pinned-negative: rejectionReason belongs to InvokeServiceDatetimeLiteralDefense's classifier,
        // NOT to this cross-tool envelope. If a future maintainer adds it here without designing a
        // separate classifier, the test catches the regression. Do not reuse
        // rejectionReason outside InvokeServiceDatetimeLiteralDefense.
        String json = BuiltInToolTimeErrorJson.error("UNSUPPORTED_CALENDAR_PHRASE", "msg", "calendarPhrase");
        JsonNode root = MAPPER.readTree(json);
        assertFalse(root.has("rejectionReason"),
                "rejectionReason is classifier-specific (invoke_service defense only); must not appear "
                        + "on cross-tool natural-time error envelopes");
    }

    @Test
    void errorDoesNotEmitRawValueField() throws Exception {
        // PII boundary: same contract as InvokeServiceErrorJson. Raw user-facing values (potentially
        // forwarded from free-text) belong in the truncated server log, not the LLM-facing wire.
        String json = BuiltInToolTimeErrorJson.error("UNSUPPORTED_CALENDAR_PHRASE", "msg", "calendarPhrase");
        JsonNode root = MAPPER.readTree(json);
        assertFalse(root.has("rawValue"));
        assertFalse(root.has("rejectedRawValue"));
    }

    @Test
    void errorHandlesNullCodeAndMessage() throws Exception {
        // Defensive: the helper must never throw or return invalid JSON, even on null inputs.
        JsonNode root = MAPPER.readTree(BuiltInToolTimeErrorJson.error(null, null, null));
        assertEquals("error", root.get("status").asText());
        assertEquals("", root.get("code").asText());
        assertEquals("", root.get("message").asText());
        assertFalse(root.has("rejectedParameter"));
    }

    @Test
    void fromNaturalTimeOutcomePassesAllFields() throws Exception {
        BuiltInToolNaturalTimeWindow.Outcome outcome = BuiltInToolNaturalTimeWindow.Outcome.errorOn(
                "INVALID_TIME_SPEC_SHAPE", "calendarPhrase must be a JSON string.", "calendarPhrase");
        JsonNode root = MAPPER.readTree(BuiltInToolTimeErrorJson.fromNaturalTimeOutcome(outcome));

        assertEquals("INVALID_TIME_SPEC_SHAPE", root.get("code").asText());
        assertEquals("calendarPhrase must be a JSON string.", root.get("message").asText());
        assertEquals("calendarPhrase", root.get("rejectedParameter").asText());
    }

    @Test
    void fromNaturalTimeOutcomeOmitsRejectedParameterForCrossCuttingConflicts() throws Exception {
        BuiltInToolNaturalTimeWindow.Outcome outcome = BuiltInToolNaturalTimeWindow.Outcome.error(
                "TIME_PHRASE_VS_PRESET_CONFLICT",
                "Do not combine calendarPhrase or relativeDuration with timePreset.");
        JsonNode root = MAPPER.readTree(BuiltInToolTimeErrorJson.fromNaturalTimeOutcome(outcome));

        assertEquals("TIME_PHRASE_VS_PRESET_CONFLICT", root.get("code").asText());
        assertFalse(root.has("rejectedParameter"));
    }

    @Test
    void errorAlwaysReturnsValidJson() throws Exception {
        // Smoke: every legitimate combination produces parseable JSON.
        for (String code : new String[] {
                "INVALID_TIME_SPEC_SHAPE", "TIME_PHRASE_COMBINED_INVALID",
                "TIME_PHRASE_VS_PRESET_CONFLICT", "TIME_PHRASE_VS_EXPLICIT_BOUND_CONFLICT",
                "MISSING_OR_INVALID_USER_TIMEZONE", "UNSUPPORTED_CALENDAR_PHRASE",
                "INVALID_DURATION_GRAMMAR"}) {
            for (String rp : new String[] {null, "calendarPhrase", "relativeDuration", "startTime"}) {
                String json = BuiltInToolTimeErrorJson.error(code, "msg", rp);
                JsonNode root = MAPPER.readTree(json);
                assertNotNull(root);
                assertTrue(json.startsWith("{") && json.endsWith("}"));
            }
        }
    }
}
