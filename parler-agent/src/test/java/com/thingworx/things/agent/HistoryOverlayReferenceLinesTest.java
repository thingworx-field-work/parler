package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class HistoryOverlayReferenceLinesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void unknownRoleNormalizesToLimit() throws Exception {
        JSONArray out = HistoryOverlayReferenceLines.parseStrict(
                MAPPER.readTree("[{\"y\":5,\"role\":\"custom-role\"}]"));
        assertEquals(1, out.length());
        assertEquals("limit", out.getJSONObject(0).getString("role"));
    }

    @Test
    void nonFiniteYFails() {
        HistoryOverlayReferenceLines.ParseException ex = assertThrows(
                HistoryOverlayReferenceLines.ParseException.class,
                () -> HistoryOverlayReferenceLines.parseStrict(
                        MAPPER.readTree("[{\"y\":\"NaN\"}]")));
        assertEquals("HISTORY_OVERLAY_INVALID_REFERENCE_LINE", ex.code);
    }

    @Test
    void tooManyLinesFails() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 13; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"y\":").append(i).append('}');
        }
        sb.append(']');
        HistoryOverlayReferenceLines.ParseException ex = assertThrows(
                HistoryOverlayReferenceLines.ParseException.class,
                () -> HistoryOverlayReferenceLines.parseStrict(MAPPER.readTree(sb.toString())));
        assertEquals("HISTORY_OVERLAY_INVALID_REFERENCE_LINE", ex.code);
    }
}
