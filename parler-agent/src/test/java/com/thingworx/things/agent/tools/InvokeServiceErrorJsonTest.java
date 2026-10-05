package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.tools.InvokeServiceDatetimeLiteralDefense.RejectionReason;
import org.junit.jupiter.api.Test;

/**
 * {@link InvokeServiceErrorJson#unsupportedRelativeLiteral} must emit
 * the structured wire fields {@code rejectedParameter} and {@code rejectionReason} in addition to
 * {@code status} / {@code code} / {@code message}, so the LLM can surgically retry without parsing the
 * human-readable message. Also pins the contract that the raw rejected value is NOT placed on the wire
 * (PII boundary; raw values live in the truncated INFO log only).
 *
 * <p>Also covers {@link InvokeServiceErrorJson#truncateForLog}: null safety,
 * under-max passthrough, over-max truncation with the {@code "..."} suffix.</p>
 */
class InvokeServiceErrorJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void unsupportedRelativeLiteralEmitsAllStructuredFields() throws Exception {
        UnsupportedRelativeLiteralException e = new UnsupportedRelativeLiteralException(
                "startDate", "today", RejectionReason.DAY_TOKEN, "detail message");
        String json = InvokeServiceErrorJson.unsupportedRelativeLiteral(e);
        JsonNode root = MAPPER.readTree(json);

        assertEquals("error", root.get("status").asText());
        assertEquals("UNSUPPORTED_RELATIVE_LITERAL", root.get("code").asText());
        assertEquals("detail message", root.get("message").asText());
        assertEquals("startDate", root.get("rejectedParameter").asText());
        assertEquals("DAY_TOKEN", root.get("rejectionReason").asText());

        // Boundary: the raw rejected value must NOT be on the wire (PII boundary; INFO log only).
        assertFalse(root.has("rawValue"),
                "raw rejected value must not be exposed on the wire");
        assertFalse(root.has("rejectedRawValue"),
                "raw rejected value must not be exposed on the wire");
    }

    @Test
    void unsupportedRelativeLiteralCarriesPathStyleParamName() throws Exception {
        // Bag-key scan rejections use a path-style paramName (e.g. payload.startDate) so the
        // LLM can identify the offending key inside an object-shaped JSON / VARIANT bag. The wire JSON
        // must preserve that path string verbatim.
        UnsupportedRelativeLiteralException e = new UnsupportedRelativeLiteralException(
                "payload.startDate", "today", RejectionReason.DAY_TOKEN, "detail");
        String json = InvokeServiceErrorJson.unsupportedRelativeLiteral(e);
        JsonNode root = MAPPER.readTree(json);
        assertEquals("payload.startDate", root.get("rejectedParameter").asText());
    }

    @Test
    void unsupportedRelativeLiteralEmitsEachReasonAsName() throws Exception {
        for (RejectionReason r : RejectionReason.values()) {
            UnsupportedRelativeLiteralException e = new UnsupportedRelativeLiteralException(
                    "p", "v", r, "d");
            JsonNode root = MAPPER.readTree(InvokeServiceErrorJson.unsupportedRelativeLiteral(e));
            assertEquals(r.name(), root.get("rejectionReason").asText());
        }
    }

    @Test
    void unsupportedRelativeLiteralHandlesNullDetail() throws Exception {
        UnsupportedRelativeLiteralException e = new UnsupportedRelativeLiteralException(
                "startDate", "today", RejectionReason.DAY_TOKEN, null);
        JsonNode root = MAPPER.readTree(InvokeServiceErrorJson.unsupportedRelativeLiteral(e));
        // Empty string, never null on the wire.
        assertEquals("", root.get("message").asText());
        // Other structured fields still present.
        assertEquals("startDate", root.get("rejectedParameter").asText());
        assertEquals("DAY_TOKEN", root.get("rejectionReason").asText());
    }

    @Test
    void unsupportedRelativeLiteralAlwaysReturnsValidJson() throws Exception {
        // Smoke-test: every public RejectionReason value yields a parseable JSON envelope.
        for (RejectionReason r : RejectionReason.values()) {
            UnsupportedRelativeLiteralException e = new UnsupportedRelativeLiteralException(
                    "p", "v", r, "d");
            String json = InvokeServiceErrorJson.unsupportedRelativeLiteral(e);
            assertNotNull(MAPPER.readTree(json));
            assertTrue(json.startsWith("{") && json.endsWith("}"));
        }
    }

    // --- truncateForLog -------------------------------------------------

    @Test
    void truncateForLogReturnsEmptyStringOnNull() {
        assertEquals("", InvokeServiceErrorJson.truncateForLog(null, 80));
        assertEquals("", InvokeServiceErrorJson.truncateForLog(null, 0));
    }

    @Test
    void truncateForLogPassesThroughUnderMax() {
        assertEquals("", InvokeServiceErrorJson.truncateForLog("", 10));
        assertEquals("hello", InvokeServiceErrorJson.truncateForLog("hello", 10));
        assertEquals("hello", InvokeServiceErrorJson.truncateForLog("hello", 5));
    }

    @Test
    void truncateForLogClipsOverMax() {
        String result = InvokeServiceErrorJson.truncateForLog("0123456789", 5);
        assertEquals("01234...", result);
    }
}
