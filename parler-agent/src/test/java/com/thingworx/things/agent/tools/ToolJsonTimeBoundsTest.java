package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ToolJsonTimeBoundsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nullWhenAbsentOrTextual() throws Exception {
        assertNull(ToolJsonTimeBounds.validateOptionalFieldsAreTextual(MAPPER.readTree("{}"), "startTime"));
        assertNull(ToolJsonTimeBounds.validateOptionalFieldsAreTextual(
                MAPPER.readTree("{\"startTime\":\"2026-01-01T00:00:00Z\"}"), "startTime"));
    }

    @Test
    void rejectsNonTextualBounds() throws Exception {
        String msg = ToolJsonTimeBounds.validateOptionalFieldsAreTextual(
                MAPPER.readTree("{\"startTime\":{\"iso\":\"x\"}}"), "startTime");
        assertEquals("startTime must be a JSON string.", msg);

        String msg2 = ToolJsonTimeBounds.validateOptionalFieldsAreTextual(
                MAPPER.readTree("{\"end\":[\"2026-01-01\"]}"), "end");
        assertEquals("end must be a JSON string.", msg2);
    }

    // firstNonTextualField identifies WHICH listed field is bad so the
    // executor can populate rejectedParameter on the cross-tool wire envelope.

    @Test
    void firstNonTextualFieldNullWhenAllTextualOrAbsent() throws Exception {
        assertNull(ToolJsonTimeBounds.firstNonTextualField(MAPPER.readTree("{}"), "startTime", "endTime"));
        assertNull(ToolJsonTimeBounds.firstNonTextualField(
                MAPPER.readTree("{\"startTime\":\"2026-01-01T00:00:00Z\",\"endTime\":\"2026-01-02T00:00:00Z\"}"),
                "startTime", "endTime"));
        // Explicit JSON null on a slot is OK (treated as absent).
        assertNull(ToolJsonTimeBounds.firstNonTextualField(
                MAPPER.readTree("{\"startTime\":null}"), "startTime"));
    }

    @Test
    void firstNonTextualFieldReturnsOffendingFieldName() throws Exception {
        assertEquals("startTime", ToolJsonTimeBounds.firstNonTextualField(
                MAPPER.readTree("{\"startTime\":{\"iso\":\"x\"}}"), "startTime", "endTime"));
        assertEquals("endTime", ToolJsonTimeBounds.firstNonTextualField(
                MAPPER.readTree("{\"endTime\":[\"2026-01-01\"]}"), "startTime", "endTime"));
        assertEquals("timePreset", ToolJsonTimeBounds.firstNonTextualField(
                MAPPER.readTree("{\"timePreset\":42}"), "startTime", "endTime", "timePreset"));
    }

    @Test
    void firstNonTextualFieldUsesDeclarationOrderForPriority() throws Exception {
        // When multiple fields are bad, the first listed wins — gives the executor a deterministic
        // path-style rejectedParameter to surface (the LLM can fix one at a time).
        String first = ToolJsonTimeBounds.firstNonTextualField(
                MAPPER.readTree("{\"startTime\":42,\"endTime\":[\"x\"]}"), "startTime", "endTime");
        assertEquals("startTime", first);
        String firstReversed = ToolJsonTimeBounds.firstNonTextualField(
                MAPPER.readTree("{\"startTime\":42,\"endTime\":[\"x\"]}"), "endTime", "startTime");
        assertEquals("endTime", firstReversed);
    }

    @Test
    void firstNonTextualFieldAndValidateMessageAgreeOnViolation() throws Exception {
        // The two methods must agree on what counts as a shape violation — they share the same
        // traversal contract. Without this invariant, an executor could surface rejectedParameter
        // pointing at a field whose error message it never produced (or vice versa).
        com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(
                "{\"startTime\":\"ok\",\"endTime\":[\"bad\"],\"timePreset\":\"ok\"}");
        String name = ToolJsonTimeBounds.firstNonTextualField(root, "startTime", "endTime", "timePreset");
        String msg = ToolJsonTimeBounds.validateOptionalFieldsAreTextual(root, "startTime", "endTime",
                "timePreset");
        assertEquals("endTime", name);
        assertEquals("endTime must be a JSON string.", msg);
    }
}
