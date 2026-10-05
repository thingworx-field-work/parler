package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class BuiltInToolNaturalTimeWindowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void skipWhenNoPhraseFields() throws Exception {
        JsonNode root = MAPPER.readTree("{\"thingName\":\"T\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, Instant.parse("2026-05-06T12:00:00Z"), null,
                        null, false);
        assertTrue(o.skip);
        assertNull(o.errorCode);
    }

    @Test
    void calendarTodayUtcMidnightWindow() throws Exception {
        AgentToolContext.setUserIanaTimezone("UTC");
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"today\"}");
        Instant now = Instant.parse("2026-05-06T15:30:00Z");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, now, null, null, false);
        assertFalse(o.skip);
        assertNull(o.errorCode);
        assertEquals(BuiltInToolNaturalTimeWindow.AppliedKind.CALENDAR_DAY_ENGLISH, o.kind);
        assertEquals(Instant.parse("2026-05-06T00:00:00Z"), o.resolution.getStartUtc());
        assertEquals(Instant.parse("2026-05-07T00:00:00Z"), o.resolution.getEndUtc());
    }

    @Test
    void calendarAllowsPossessiveAndContextWords() throws Exception {
        AgentToolContext.setUserIanaTimezone("UTC");
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"today's metrics\"}");
        Instant now = Instant.parse("2026-05-06T15:30:00Z");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, now, null, null, false);
        assertFalse(o.skip);
        assertNull(o.errorCode);

        JsonNode root2 = MAPPER.readTree("{\"calendarPhrase\":\"show yesterday alerts\"}");
        BuiltInToolNaturalTimeWindow.Outcome o2 =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root2, now, null, null, false);
        assertFalse(o2.skip);
        assertNull(o2.errorCode);
    }

    @Test
    void calendarRejectsDayTokenPlusDaypart() throws Exception {
        AgentToolContext.setUserIanaTimezone("UTC");
        Instant now = Instant.parse("2026-05-06T15:30:00Z");
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"today morning\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, now, null, null, false);
        assertEquals("UNSUPPORTED_CALENDAR_PHRASE", o.errorCode);

        JsonNode root2 = MAPPER.readTree("{\"calendarPhrase\":\"evening tomorrow\"}");
        BuiltInToolNaturalTimeWindow.Outcome o2 =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root2, now, null, null, false);
        assertEquals("UNSUPPORTED_CALENDAR_PHRASE", o2.errorCode);
    }

    @Test
    void calendarRejectsPartialDayPrepositionPhrases() throws Exception {
        AgentToolContext.setUserIanaTimezone("UTC");
        Instant now = Instant.parse("2026-05-06T15:30:00Z");
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"today after 8\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, now, null, null, false);
        assertEquals("UNSUPPORTED_CALENDAR_PHRASE", o.errorCode);

        JsonNode root2 = MAPPER.readTree("{\"calendarPhrase\":\"since 8 today\"}");
        BuiltInToolNaturalTimeWindow.Outcome o2 =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root2, now, null, null, false);
        assertEquals("UNSUPPORTED_CALENDAR_PHRASE", o2.errorCode);
    }

    @Test
    void calendarRejectsMixedWallClockResidue() throws Exception {
        AgentToolContext.setUserIanaTimezone("UTC");
        Instant now = Instant.parse("2026-05-06T15:30:00Z");
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"today at 8am\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, now, null, null, false);
        assertEquals("UNSUPPORTED_CALENDAR_PHRASE", o.errorCode);

        JsonNode root2 = MAPPER.readTree("{\"calendarPhrase\":\"today at 8\"}");
        BuiltInToolNaturalTimeWindow.Outcome o2 =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root2, now, null, null, false);
        assertEquals("UNSUPPORTED_CALENDAR_PHRASE", o2.errorCode);

        JsonNode root3 = MAPPER.readTree("{\"calendarPhrase\":\"today 14:30 trend\"}");
        BuiltInToolNaturalTimeWindow.Outcome o3 =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root3, now, null, null, false);
        assertEquals("UNSUPPORTED_CALENDAR_PHRASE", o3.errorCode);
    }

    @Test
    void relativeDurationThirtyMinutes() throws Exception {
        JsonNode root = MAPPER.readTree("{\"relativeDuration\":\"30m\"}");
        Instant now = Instant.parse("2026-05-06T12:00:00Z");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveNumericPropertyHistory(root, now, null, null);
        assertFalse(o.skip);
        assertNull(o.errorCode);
        assertEquals(BuiltInToolNaturalTimeWindow.AppliedKind.RELATIVE_DURATION, o.kind);
        assertEquals(now.minusSeconds(30 * 60), o.resolution.getStartUtc());
        assertEquals(now, o.resolution.getEndUtc());
    }

    @Test
    void presetConflict() throws Exception {
        JsonNode root = MAPPER.readTree("{\"relativeDuration\":\"1h\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, Instant.now(), null, null, true);
        assertFalse(o.skip);
        assertEquals("TIME_PHRASE_VS_PRESET_CONFLICT", o.errorCode);
        assertNotNull(o.errorMessage);
    }

    @Test
    void explicitBoundConflict() throws Exception {
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"today\"}");
        AgentToolContext.setUserIanaTimezone("UTC");
        BuiltInToolNaturalTimeWindow.Outcome o = BuiltInToolNaturalTimeWindow.resolveNumericPropertyHistory(root,
                Instant.now(), "2026-01-01T00:00:00Z", null);
        assertEquals("TIME_PHRASE_VS_EXPLICIT_BOUND_CONFLICT", o.errorCode);
    }

    @Test
    void bothPhraseKindsInvalid() throws Exception {
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"today\",\"relativeDuration\":\"1h\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, Instant.now(), null, null, false);
        assertEquals("TIME_PHRASE_COMBINED_INVALID", o.errorCode);
    }

    @Test
    void calendarRequiresTimezone() throws Exception {
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"today\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, Instant.now(), null, null, false);
        assertEquals("MISSING_OR_INVALID_USER_TIMEZONE", o.errorCode);
    }

    @Test
    void nonTextRelativeDuration_shapeError() throws Exception {
        JsonNode root = MAPPER.readTree("{\"relativeDuration\":{\"duration\":\"30m\"}}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveNumericPropertyHistory(root, Instant.now(), null, null);
        assertEquals("INVALID_TIME_SPEC_SHAPE", o.errorCode);
        // Shape errors identify the offending field via rejectedParameter.
        assertEquals("relativeDuration", o.rejectedParameter);
    }

    @Test
    void nonTextCalendarPhrase_shapeError() throws Exception {
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":[\"today\"]}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, Instant.now(), null, null, false);
        assertEquals("INVALID_TIME_SPEC_SHAPE", o.errorCode);
        assertEquals("calendarPhrase", o.rejectedParameter);
    }

    // Pin rejectedParameter on every error path.
    // Single-field errors carry the offending field; cross-cutting conflicts carry null.

    @Test
    void calendarPhraseRejectedParameterOnUnsupportedDayPart() throws Exception {
        AgentToolContext.setUserIanaTimezone("UTC");
        Instant now = Instant.parse("2026-05-06T15:30:00Z");
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"today morning\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, now, null, null, false);
        assertEquals("UNSUPPORTED_CALENDAR_PHRASE", o.errorCode);
        assertEquals("calendarPhrase", o.rejectedParameter);
    }

    @Test
    void calendarPhraseRejectedParameterOnGenericUnsupported() throws Exception {
        AgentToolContext.setUserIanaTimezone("UTC");
        Instant now = Instant.parse("2026-05-06T15:30:00Z");
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"someday\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, now, null, null, false);
        assertEquals("UNSUPPORTED_CALENDAR_PHRASE", o.errorCode);
        assertEquals("calendarPhrase", o.rejectedParameter);
    }

    @Test
    void calendarPhraseRejectedParameterOnMissingTimezone() throws Exception {
        // No setUserIanaTimezone — host has none.
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"today\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, Instant.now(), null, null, false);
        assertEquals("MISSING_OR_INVALID_USER_TIMEZONE", o.errorCode);
        // The fixable parameter is calendarPhrase (the field that depends on the timezone) —
        // surfacing it lets the LLM pivot to relativeDuration or ISO bounds.
        assertEquals("calendarPhrase", o.rejectedParameter);
    }

    @Test
    void relativeDurationRejectedParameterOnInvalidGrammar() throws Exception {
        JsonNode root = MAPPER.readTree("{\"relativeDuration\":\"not a duration\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveNumericPropertyHistory(root, Instant.now(), null, null);
        assertNotNull(o.errorCode, "expected a resolver error for invalid grammar");
        assertEquals("relativeDuration", o.rejectedParameter,
                "single-field resolver errors should identify relativeDuration");
    }

    @Test
    void crossCuttingConflictsCarryNullRejectedParameter() throws Exception {
        // Mutual exclusion / conflict errors have NO single offending field — rejectedParameter must be
        // null because no single field is identifiable.
        JsonNode bothSet = MAPPER.readTree("{\"calendarPhrase\":\"today\",\"relativeDuration\":\"1h\"}");
        BuiltInToolNaturalTimeWindow.Outcome o1 =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(bothSet, Instant.now(), null, null, false);
        assertEquals("TIME_PHRASE_COMBINED_INVALID", o1.errorCode);
        assertNull(o1.rejectedParameter);

        JsonNode rel = MAPPER.readTree("{\"relativeDuration\":\"1h\"}");
        BuiltInToolNaturalTimeWindow.Outcome o2 =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(rel, Instant.now(), null, null, true);
        assertEquals("TIME_PHRASE_VS_PRESET_CONFLICT", o2.errorCode);
        assertNull(o2.rejectedParameter);

        AgentToolContext.setUserIanaTimezone("UTC");
        JsonNode cal = MAPPER.readTree("{\"calendarPhrase\":\"today\"}");
        BuiltInToolNaturalTimeWindow.Outcome o3 =
                BuiltInToolNaturalTimeWindow.resolveNumericPropertyHistory(cal, Instant.now(),
                        "2026-01-01T00:00:00Z", null);
        assertEquals("TIME_PHRASE_VS_EXPLICIT_BOUND_CONFLICT", o3.errorCode);
        assertNull(o3.rejectedParameter);
    }

    @Test
    void okOutcomeCarriesNullRejectedParameter() throws Exception {
        AgentToolContext.setUserIanaTimezone("UTC");
        JsonNode root = MAPPER.readTree("{\"calendarPhrase\":\"today\"}");
        Instant now = Instant.parse("2026-05-06T15:30:00Z");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, now, null, null, false);
        assertNull(o.errorCode);
        assertNull(o.rejectedParameter);
    }

    @Test
    void skipOutcomeCarriesNullRejectedParameter() throws Exception {
        JsonNode root = MAPPER.readTree("{\"thingName\":\"T\"}");
        BuiltInToolNaturalTimeWindow.Outcome o =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, Instant.parse("2026-05-06T12:00:00Z"),
                        null, null, false);
        assertTrue(o.skip);
        assertNull(o.rejectedParameter);
    }
}
