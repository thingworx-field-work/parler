package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

class ExplicitIsoTimeBoundsTest {

    @Test
    void bothAbsent_okForPlatformDefault() {
        ExplicitIsoTimeBounds.ParseOutcome o = ExplicitIsoTimeBounds.parseOptionalPair(null, null);
        assertTrue(o.ok);
        assertNull(o.start);
        assertNull(o.end);
    }

    @Test
    void bothPresent_valid() {
        ExplicitIsoTimeBounds.ParseOutcome o = ExplicitIsoTimeBounds.parseOptionalPair("2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z");
        assertTrue(o.ok);
        assertEquals(DateTime.parse("2026-01-01T00:00:00Z").getMillis(), o.start.getMillis());
        assertEquals(DateTime.parse("2026-01-02T00:00:00Z").getMillis(), o.end.getMillis());
    }

    @Test
    void invalidStart_neverSilentAbsent() {
        ExplicitIsoTimeBounds.ParseOutcome o = ExplicitIsoTimeBounds.parseOptionalPair("not-a-date", null);
        assertFalse(o.ok);
        assertEquals("INVALID_TIME_RANGE", o.errorCode);
    }

    @Test
    void invalidBoth_stringsRejected() {
        ExplicitIsoTimeBounds.ParseOutcome o = ExplicitIsoTimeBounds.parseOptionalPair("bad", "also-bad");
        assertFalse(o.ok);
        assertEquals("INVALID_TIME_RANGE", o.errorCode);
    }

    @Test
    void oneSidedRejected() {
        ExplicitIsoTimeBounds.ParseOutcome o = ExplicitIsoTimeBounds.parseOptionalPair("2026-01-01T00:00:00Z", null);
        assertFalse(o.ok);
        assertEquals("INVALID_TIME_RANGE", o.errorCode);
    }

    @Test
    void alertHistory_allowsStartOnly() {
        ExplicitIsoTimeBounds.ParseOutcome o =
                ExplicitIsoTimeBounds.parseAlertHistoryOptionalStrings("2026-01-01T00:00:00Z", null);
        assertTrue(o.ok);
        assertNotNull(o.start);
        assertNull(o.end);
    }

    @Test
    void alertHistory_invalidStartMessage() {
        ExplicitIsoTimeBounds.ParseOutcome o =
                ExplicitIsoTimeBounds.parseAlertHistoryOptionalStrings("not-iso", null);
        assertFalse(o.ok);
        assertEquals("INVALID_TIME_RANGE", o.errorCode);
        assertTrue(o.errorMessage.contains("startTime"));
    }

    // ParseOutcome.failedField identifies which
    // bound carries the parse failure so the wire envelope can populate rejectedParameter without
    // a carve-out for INVALID_TIME_RANGE. Cross-cutting failures (the "both or neither" rule) carry
    // failedField=null per the cross-tool convention.

    @Test
    void successCarriesNullFailedField() {
        ExplicitIsoTimeBounds.ParseOutcome o = ExplicitIsoTimeBounds.parseOptionalPair(null, null);
        assertTrue(o.ok);
        assertNull(o.failedField);

        ExplicitIsoTimeBounds.ParseOutcome o2 = ExplicitIsoTimeBounds.parseOptionalPair(
                "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z");
        assertTrue(o2.ok);
        assertNull(o2.failedField);
    }

    @Test
    void invalidStartCarriesStartTimeFailedField_default() {
        ExplicitIsoTimeBounds.ParseOutcome o = ExplicitIsoTimeBounds.parseOptionalPair("bad", null);
        assertFalse(o.ok);
        assertEquals("startTime", o.failedField);
    }

    @Test
    void invalidEndCarriesEndTimeFailedField_default() {
        ExplicitIsoTimeBounds.ParseOutcome o = ExplicitIsoTimeBounds.parseOptionalPair(
                "2026-01-01T00:00:00Z", "not-a-date");
        assertFalse(o.ok);
        assertEquals("endTime", o.failedField);
    }

    @Test
    void invalidStartPreservesAliasLabel() {
        // Preserve the exact user-supplied field name (start vs startTime).
        ExplicitIsoTimeBounds.ParseOutcome o = ExplicitIsoTimeBounds.parseOptionalPair("bad", null,
                "start", "end");
        assertFalse(o.ok);
        assertEquals("start", o.failedField);
        assertTrue(o.errorMessage.contains("start"),
                "error message should mention the alias label the user supplied");
    }

    @Test
    void invalidEndPreservesAliasLabel() {
        ExplicitIsoTimeBounds.ParseOutcome o = ExplicitIsoTimeBounds.parseOptionalPair(
                "2026-01-01T00:00:00Z", "not-a-date", "start", "end");
        assertFalse(o.ok);
        assertEquals("end", o.failedField);
    }

    @Test
    void crossCuttingOneSidedCarriesNullFailedField() {
        // The "both or neither" rule has no single offending parameter — the LLM must supply the
        // missing bound. failedField=null per the cross-tool convention for cross-cutting
        // failures (matches BuiltInToolNaturalTimeWindow.Outcome.error vs errorOn split).
        ExplicitIsoTimeBounds.ParseOutcome o = ExplicitIsoTimeBounds.parseOptionalPair(
                "2026-01-01T00:00:00Z", null);
        assertFalse(o.ok);
        assertNull(o.failedField,
                "one-sided 'both or neither' rejection is cross-cutting; failedField must be null");
    }

    @Test
    void alertHistoryFailureCarriesFailedField() {
        ExplicitIsoTimeBounds.ParseOutcome oStart =
                ExplicitIsoTimeBounds.parseAlertHistoryOptionalStrings("not-iso", null);
        assertFalse(oStart.ok);
        assertEquals("startTime", oStart.failedField);

        ExplicitIsoTimeBounds.ParseOutcome oEnd =
                ExplicitIsoTimeBounds.parseAlertHistoryOptionalStrings(null, "not-iso");
        assertFalse(oEnd.ok);
        assertEquals("endTime", oEnd.failedField);
    }

    @Test
    void blankAliasLabelFallsBackToCanonical() {
        // Defensive: blank or null label should not leak into rejectedParameter; fall back to
        // canonical "startTime" / "endTime" so downstream consumers always see a usable name.
        ExplicitIsoTimeBounds.ParseOutcome oBlank = ExplicitIsoTimeBounds.parseOptionalPair(
                "bad", null, "  ", "endTime");
        assertEquals("startTime", oBlank.failedField);

        ExplicitIsoTimeBounds.ParseOutcome oNull = ExplicitIsoTimeBounds.parseOptionalPair(
                "bad", null, null, null);
        assertEquals("startTime", oNull.failedField);
    }

    @Test
    void pickAliasLabelPicksFirstNonBlankInOrder() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // Preferred has value → preferred wins.
        assertEquals("startTime", ExplicitIsoTimeBounds.pickAliasLabel(
                mapper.readTree("{\"startTime\":\"2026-01-01T00:00:00Z\",\"start\":\"2026-02-01T00:00:00Z\"}"),
                "startTime", "start"));
        // Preferred absent → fallback wins.
        assertEquals("start", ExplicitIsoTimeBounds.pickAliasLabel(
                mapper.readTree("{\"start\":\"2026-01-01T00:00:00Z\"}"), "startTime", "start"));
        // Both absent → preferred (canonical default).
        assertEquals("startTime", ExplicitIsoTimeBounds.pickAliasLabel(
                mapper.readTree("{}"), "startTime", "start"));
        // Preferred blank → fallback wins.
        assertEquals("start", ExplicitIsoTimeBounds.pickAliasLabel(
                mapper.readTree("{\"startTime\":\"\",\"start\":\"2026-01-01T00:00:00Z\"}"),
                "startTime", "start"));
    }
}
