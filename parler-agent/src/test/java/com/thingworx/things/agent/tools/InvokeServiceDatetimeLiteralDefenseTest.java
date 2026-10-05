package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thingworx.things.agent.tools.InvokeServiceDatetimeLiteralDefense.RejectionReason;
import com.thingworx.things.agent.tools.InvokeServiceDatetimeLiteralDefense.RejectionResult;
import org.junit.jupiter.api.Test;

class InvokeServiceDatetimeLiteralDefenseTest {

    @Test
    void allowsIsoStrings() {
        assertNull(InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull("2026-05-06T12:00:00.000Z"));
    }

    @Test
    void rejectsNowMinusStyle() {
        assertNotNull(InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull("now-5m"));
    }

    @Test
    void rejectsBareDuration() {
        assertNotNull(InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull("30m"));
    }

    @Test
    void rejectsThisMorningCalendar() {
        assertNotNull(InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull("this morning"));
    }

    @Test
    void rejectsTodayAsRawDatetime() {
        // Raw `today` must NOT slip through to a
        // generic INVALID_PARAMETERS — it is first-class App User language but never a valid DATETIME instant.
        String detail = InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull("today");
        assertNotNull(detail);
        assertTrue(detail.toLowerCase().contains("calendarphrase"),
                "rejection message should steer the model toward the calendarPhrase field; was: " + detail);
    }

    @Test
    void rejectsYesterdayAndTomorrow() {
        assertNotNull(InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull("yesterday"));
        assertNotNull(InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull("tomorrow"));
    }

    @Test
    void throwIfRejectedCarriesParamRawAndReason() {
        UnsupportedRelativeLiteralException e = assertThrows(UnsupportedRelativeLiteralException.class,
                () -> InvokeServiceDatetimeLiteralDefense.throwIfRejected("startDate", "today"));
        assertEquals("startDate", e.getParamName());
        assertEquals("today", e.getRawValue());
        assertEquals(RejectionReason.DAY_TOKEN, e.getRejectionReason());
        assertNotNull(e.getDetail());
        assertEquals(e.getDetail(), e.getMessage(),
                "typed exception message must equal detail (no extra wrapping prefix)");
    }

    @Test
    void throwIfRejectedNoOpOnIso() {
        // Should not throw on a clean ISO string.
        InvokeServiceDatetimeLiteralDefense.throwIfRejected("startDate", "2026-05-06T12:00:00.000Z");
    }

    @Test
    void datetimeLikeNameRecognisesCommonKeys() {
        // §8 list — case-insensitive.
        assertTrue(InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName("startDate"));
        assertTrue(InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName("ENDTIME"));
        assertTrue(InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName("eventTime"));
        assertTrue(InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName("from"));
        assertTrue(InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName("to"));
        // The §8 list explicitly includes startTime / endTime.
        assertTrue(InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName("startTime"));
        assertTrue(InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName("endTime"));
        assertFalse(InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName("name"));
        assertFalse(InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName(""));
        assertFalse(InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName(null));
    }

    @Test
    void classifyTagsCalendarPhrase() {
        // §8 calendar / wall-clock phrases get CALENDAR_OR_WALL_CLOCK.
        RejectionResult r = InvokeServiceDatetimeLiteralDefense.classify("this morning");
        assertTrue(r.isRejected());
        assertEquals(RejectionReason.CALENDAR_OR_WALL_CLOCK, r.reason);
    }

    @Test
    void classifyTagsInformalRelative() {
        RejectionResult r = InvokeServiceDatetimeLiteralDefense.classify("now-5m");
        assertTrue(r.isRejected());
        assertEquals(RejectionReason.INFORMAL_RELATIVE, r.reason);
    }

    @Test
    void classifyTagsBareDurationAsInformalRelative() {
        RejectionResult r = InvokeServiceDatetimeLiteralDefense.classify("30m");
        assertTrue(r.isRejected());
        assertEquals(RejectionReason.INFORMAL_RELATIVE, r.reason);
    }

    @Test
    void classifyTagsDayToken() {
        for (String token : new String[] {"today", "yesterday", "tomorrow"}) {
            RejectionResult r = InvokeServiceDatetimeLiteralDefense.classify(token);
            assertTrue(r.isRejected(), "expected rejection for " + token);
            assertEquals(RejectionReason.DAY_TOKEN, r.reason,
                    "expected DAY_TOKEN classification for " + token + "; got " + r.reason);
        }
    }

    @Test
    void classifyPassesIso() {
        RejectionResult r = InvokeServiceDatetimeLiteralDefense.classify("2026-05-06T12:00:00.000Z");
        assertFalse(r.isRejected());
        assertNull(r.reason);
        assertNull(r.detail);
    }

    @Test
    void classifyPassesBlank() {
        assertFalse(InvokeServiceDatetimeLiteralDefense.classify(null).isRejected());
        assertFalse(InvokeServiceDatetimeLiteralDefense.classify("").isRejected());
        assertFalse(InvokeServiceDatetimeLiteralDefense.classify("   ").isRejected());
    }

    // §8 lists `(last|past) N <unit>` and `N <unit> ago`; an earlier `INFORMAL_RELATIVE` only matched
    // `last`. These tests pin the §8 alignment.

    @Test
    void rejectsPastNRelative() {
        // §8 wording: ^(last|past)\s+[0-9]+\s*[smhdw]$
        RejectionResult r = InvokeServiceDatetimeLiteralDefense.classify("past 30m");
        assertTrue(r.isRejected(), "past 30m must be classified as a relative literal");
        assertEquals(RejectionReason.INFORMAL_RELATIVE, r.reason);

        assertNotNull(InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull("past 7d"));
        assertNotNull(InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull("past 2 h"));
    }

    @Test
    void rejectsAgoMultiwordRelative() {
        // §8 wording: ^[0-9]+\s+(seconds?|minutes?|hours?|days?|weeks?)\s+ago$
        for (String s : new String[] {
                "5 minutes ago", "1 minute ago", "10 seconds ago", "2 hours ago",
                "7 days ago", "1 week ago"}) {
            RejectionResult r = InvokeServiceDatetimeLiteralDefense.classify(s);
            assertTrue(r.isRejected(), "expected rejection for: " + s);
            assertEquals(RejectionReason.INFORMAL_RELATIVE, r.reason,
                    "expected INFORMAL_RELATIVE for: " + s + "; got " + r.reason);
        }
    }

    @Test
    void agoPatternIsAnchored() {
        // The ^…$ anchors must prevent matching arbitrary substrings; an ISO instant containing
        // a digit followed by a unit should not trip the new ago pattern.
        assertNull(InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull(
                "2026-05-06T00:00:00.000Z"));
        // "ago" without leading "<n> <unit>" is not the §8 form and should not trip the new pattern by itself
        // (other rules may still hit it, but ago-form should not).
        RejectionResult r = InvokeServiceDatetimeLiteralDefense.classify("ago");
        assertFalse(r.isRejected(), "bare 'ago' is not the §8 ago-form");
    }

    @Test
    void throwIfRejectedTagsPastForm() {
        UnsupportedRelativeLiteralException e = assertThrows(UnsupportedRelativeLiteralException.class,
                () -> InvokeServiceDatetimeLiteralDefense.throwIfRejected("startTime", "past 30m"));
        assertEquals(RejectionReason.INFORMAL_RELATIVE, e.getRejectionReason());
        assertEquals("startTime", e.getParamName());
        assertEquals("past 30m", e.getRawValue());
    }

    // `(last|past)` was too broad (`^(last|past)\s+\d`); strings like
    // `last 2 alerts` and `past 2 months` matched even though they have no valid unit. The tightened
    // pattern (`^(last|past)\s+[1-9]\d*\s*[smhdw]$`) must (a) still match all valid forms (b) NOT match
    // strings without a valid unit suffix or with trailing garbage. These tests pin both directions.

    @Test
    void lastPastValidUnitsStillRejected() {
        // Sanity: the valid-unit matrix continues to work after tightening.
        for (String s : new String[] {
                "last 2h", "last 30m", "last 5 d", "last 1w",
                "past 30m", "past 7d", "past 2 h", "past 1s"}) {
            RejectionResult r = InvokeServiceDatetimeLiteralDefense.classify(s);
            assertTrue(r.isRejected(), "expected rejection (valid duration shape) for: " + s);
            assertEquals(RejectionReason.INFORMAL_RELATIVE, r.reason);
        }
    }

    @Test
    void lastPastWithoutValidUnitNotMatchedByLastPastBranch() {
        // `last 2 alerts` and `past 2 months` MUST NOT match the (last|past) branch — they have no
        // duration unit. The classify() method may still reject these via OTHER branches if the input
        // happens to contain another rejected token (e.g. "today"); but for strings whose only
        // suspicious feature is "last/past <number> <noun>" with a non-unit noun, classify must pass.
        for (String s : new String[] {
                "last 2 alerts", "past 2 months", "last 5 hours and counting", "past 30 minutes ago"}) {
            // Note: "past 30 minutes ago" intentionally does NOT match the new ago-form (the new pattern
            // requires the input to BE the ago-form, not contain it; and the (last|past) branch now
            // requires a single valid unit char). Both branches fail — string passes.
            RejectionResult r = InvokeServiceDatetimeLiteralDefense.classify(s);
            assertFalse(r.isRejected(),
                    "expected pass-through (no valid unit anchor) for: " + s + "; got reason=" + r.reason);
        }
    }

    @Test
    void lastPastSpecificCounterExamplesPassThrough() {
        // `last 2 alerts` and `past 2 months` should
        // pass classify() so Joda's parser handles them (it will fail with a generic INVALID_PARAMETERS).
        // The earlier broad pattern would have masked them as UNSUPPORTED_RELATIVE_LITERAL.
        assertNull(InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull("last 2 alerts"));
        assertNull(InvokeServiceDatetimeLiteralDefense.rejectBeforeJodaParseOrNull("past 2 months"));
    }
}
