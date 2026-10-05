package com.thingworx.things.agent.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class ParlerTimeResolverTest {

    private static final Instant NOW = Instant.parse("2026-05-06T18:00:00Z");

    @Test
    void durationGrammar_acceptsCommonUnits() {
        assertEquals(30 * 60L, ParlerTimeResolver.parseDuration("30m").seconds);
        assertEquals(7 * 86400L, ParlerTimeResolver.parseDuration("7d").seconds);
        assertEquals(7 * 86400L, ParlerTimeResolver.parseDuration("1w").seconds);
    }

    @Test
    void durationGrammar_rejectsUnsupportedUnits() {
        ParlerTimeResolver.ParsedDuration r = ParlerTimeResolver.parseDuration("30mo");
        assertFalse(r.ok);
        assertEquals(ParlerTimeErrorCode.UNSUPPORTED_UNIT, r.code);
        ParlerTimeResolver.ParsedDuration r2 = ParlerTimeResolver.parseDuration("1y");
        assertFalse(r2.ok);
        assertEquals(ParlerTimeErrorCode.UNSUPPORTED_UNIT, r2.code);
    }

    @Test
    void durationGrammar_rejectsInvalid() {
        assertFalse(ParlerTimeResolver.parseDuration("0m").ok);
        assertFalse(ParlerTimeResolver.parseDuration("-5m").ok);
        assertFalse(ParlerTimeResolver.parseDuration("").ok);
        assertFalse(ParlerTimeResolver.parseDuration("  ").ok);
        assertEquals(ParlerTimeErrorCode.INVALID_DURATION_GRAMMAR, ParlerTimeResolver.parseDuration("").code);
    }

    @Test
    void relativeClosedOpen_lastThirtyMinutesBeforeNow() {
        ParlerTimeResolution r = ParlerTimeResolver.resolveRelativeDurationClosedOpen(NOW, "30m", true, null);
        assertTrue(r.isSuccess());
        assertEquals(NOW.minusSeconds(30 * 60L), r.getStartUtc());
        assertEquals(NOW, r.getEndUtc());
    }

    @Test
    void anchorConflict_bothNowAndExplicitUtc() {
        ParlerTimeResolution r =
                ParlerTimeResolver.resolveRelativeDurationClosedOpen(NOW, "1h", true, Instant.parse("2020-01-01T00:00:00Z"));
        assertFalse(r.isSuccess());
        assertEquals(ParlerTimeErrorCode.ANCHOR_AND_ANCHOR_UTC_CONFLICT, r.getErrorCode());
    }

    @Test
    void unsupportedCalendarPhrase_thisMonthAndWallClock() {
        ParlerTimeResolution r = ParlerTimeResolver.rejectIfUnsupportedCalendarPhrase("show data for this month");
        assertNotNull(r);
        assertFalse(r.isSuccess());
        assertEquals(ParlerTimeErrorCode.UNSUPPORTED_CALENDAR_PHRASE, r.getErrorCode());

        ParlerTimeResolution r2 = ParlerTimeResolver.rejectIfUnsupportedCalendarPhrase("around 8am");
        assertNotNull(r2);
        assertEquals(ParlerTimeErrorCode.UNSUPPORTED_CALENDAR_PHRASE, r2.getErrorCode());

        assertNull(ParlerTimeResolver.rejectIfUnsupportedCalendarPhrase("last 30m"));
        assertNull(ParlerTimeResolver.rejectIfUnsupportedCalendarPhrase(null));
        assertNull(ParlerTimeResolver.rejectIfUnsupportedCalendarPhrase("yesterday's alerts"));
    }

    @Test
    void localCalendarDay_yesterdayTodayTomorrow_useZoneAndNowUtc() {
        ZoneId ny = ZoneId.of("America/New_York");
        LocalDate anchor = NOW.atZone(ny).toLocalDate();
        Instant yStart = anchor.minusDays(1).atStartOfDay(ny).toInstant();
        Instant yEnd = anchor.atStartOfDay(ny).toInstant();
        ParlerTimeResolution y = ParlerTimeResolver.tryResolveLocalCalendarDayEnglish("show yesterday only", NOW, ny);
        assertTrue(y.isSuccess());
        assertEquals(yStart, y.getStartUtc());
        assertEquals(yEnd, y.getEndUtc());

        Instant tStart = anchor.atStartOfDay(ny).toInstant();
        Instant tEnd = anchor.plusDays(1).atStartOfDay(ny).toInstant();
        ParlerTimeResolution td = ParlerTimeResolver.tryResolveLocalCalendarDayEnglish("today metrics", NOW, ny);
        assertTrue(td.isSuccess());
        assertEquals(tStart, td.getStartUtc());
        assertEquals(tEnd, td.getEndUtc());

        Instant tmStart = anchor.plusDays(1).atStartOfDay(ny).toInstant();
        Instant tmEnd = anchor.plusDays(2).atStartOfDay(ny).toInstant();
        ParlerTimeResolution tm = ParlerTimeResolver.tryResolveLocalCalendarDayEnglish("Tomorrow maintenance", NOW, ny);
        assertTrue(tm.isSuccess());
        assertEquals(tmStart, tm.getStartUtc());
        assertEquals(tmEnd, tm.getEndUtc());
    }

    @Test
    void localCalendarDay_requiresTimezone() {
        ParlerTimeResolution r = ParlerTimeResolver.tryResolveLocalCalendarDayEnglish("just yesterday", NOW, null);
        assertFalse(r.isSuccess());
        assertEquals(ParlerTimeErrorCode.INVALID_TIME_SPEC_SHAPE, r.getErrorCode());
    }

    @Test
    void localCalendarDay_rejectsMultipleDayKeywords() {
        ParlerTimeResolution r =
                ParlerTimeResolver.tryResolveLocalCalendarDayEnglish("compare yesterday and today", NOW, ZoneId.of("UTC"));
        assertFalse(r.isSuccess());
        assertEquals(ParlerTimeErrorCode.UNSUPPORTED_CALENDAR_PHRASE, r.getErrorCode());
    }

    @Test
    void unsupportedCalendarPhrase_matchesSection8DaypartsAndTonight() {
        assertNotNull(ParlerTimeResolver.rejectIfUnsupportedCalendarPhrase("metrics this morning"));
        assertNotNull(ParlerTimeResolver.rejectIfUnsupportedCalendarPhrase("check alerts tonight"));
    }

    @Test
    void durationGrammar_hugeMagnitudeReturnsStableError_notUnchecked() {
        ParlerTimeResolver.ParsedDuration r = ParlerTimeResolver.parseDuration("9000000000000000w");
        assertFalse(r.ok);
        assertEquals(ParlerTimeErrorCode.INVALID_DURATION_GRAMMAR, r.code);
        ParlerTimeResolution win =
                ParlerTimeResolver.resolveRelativeDurationClosedOpen(NOW, "99999999999999999999w", true, null);
        assertFalse(win.isSuccess());
        assertEquals(ParlerTimeErrorCode.INVALID_DURATION_GRAMMAR, win.getErrorCode());
    }

    @Test
    void relativeRange_anchorMinusDurationOverflowReturnsStableError() {
        ParlerTimeResolution r =
                ParlerTimeResolver.resolveRelativeDurationClosedOpen(NOW, "1s", false, Instant.MIN);
        assertFalse(r.isSuccess());
        assertEquals(ParlerTimeErrorCode.INVALID_DURATION_GRAMMAR, r.getErrorCode());
    }

    @Test
    void localCalendarDayResidue_rejectsAtHourWithoutAmPm() {
        ParlerTimeResolution r = ParlerTimeResolver.rejectIfLocalCalendarDayPhraseHasUnsupportedResidue("today at 8");
        assertNotNull(r);
        assertFalse(r.isSuccess());
        assertEquals(ParlerTimeErrorCode.UNSUPPORTED_CALENDAR_PHRASE, r.getErrorCode());
    }

    @Test
    void localCalendarDayResidue_acceptsPlainDayPhrases() {
        assertNull(ParlerTimeResolver.rejectIfLocalCalendarDayPhraseHasUnsupportedResidue("today"));
        assertNull(ParlerTimeResolver.rejectIfLocalCalendarDayPhraseHasUnsupportedResidue("yesterday's KPIs"));
    }

    @Test
    void localCalendarDayResidue_rejectsDayTokenPlusDaypart() {
        ParlerTimeResolution r = ParlerTimeResolver.rejectIfLocalCalendarDayPhraseHasUnsupportedResidue("today morning");
        assertNotNull(r);
        assertFalse(r.isSuccess());
        assertEquals(ParlerTimeErrorCode.UNSUPPORTED_CALENDAR_PHRASE, r.getErrorCode());

        ParlerTimeResolution r2 =
                ParlerTimeResolver.rejectIfLocalCalendarDayPhraseHasUnsupportedResidue("afternoon yesterday");
        assertNotNull(r2);
        assertFalse(r2.isSuccess());
    }

    @Test
    void localCalendarDayResidue_rejectsPartialDayPrepositions() {
        ParlerTimeResolution r = ParlerTimeResolver.rejectIfLocalCalendarDayPhraseHasUnsupportedResidue("today after 8");
        assertNotNull(r);
        assertFalse(r.isSuccess());
        assertEquals(ParlerTimeErrorCode.UNSUPPORTED_CALENDAR_PHRASE, r.getErrorCode());

        ParlerTimeResolution r2 =
                ParlerTimeResolver.rejectIfLocalCalendarDayPhraseHasUnsupportedResidue("since 8 today");
        assertNotNull(r2);
        assertFalse(r2.isSuccess());

        ParlerTimeResolution r3 =
                ParlerTimeResolver.rejectIfLocalCalendarDayPhraseHasUnsupportedResidue("between 8 and 10 today");
        assertNotNull(r3);
        assertFalse(r3.isSuccess());
    }
}
