package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import com.thingworx.things.agent.time.ParlerTimeResolution;

import org.junit.jupiter.api.Test;

class AlertHistoryTimeRangeTest {

    @Test
    void parsePresetCaseInsensitive() {
        assertEquals(AlertHistoryTimeRange.Preset.LAST_24H, AlertHistoryTimeRange.parsePreset("LAST_24H"));
        assertEquals(AlertHistoryTimeRange.Preset.NONE, AlertHistoryTimeRange.parsePreset("  "));
        assertEquals("last_7d", AlertHistoryTimeRange.wirePresetName(AlertHistoryTimeRange.Preset.LAST_7D));
    }

    @Test
    void unknownPresetThrows() {
        assertThrows(IllegalArgumentException.class, () -> AlertHistoryTimeRange.parsePreset("last_year"));
    }

    @Test
    void implicitDefaultSevenDaysWhenBothBlank() {
        org.joda.time.DateTime end = new org.joda.time.DateTime(2026, 4, 17, 12, 0, 0, 0);
        String endIso = end.toString();
        org.joda.time.DateTime endParsed = org.joda.time.DateTime.parse(endIso.trim());
        AlertHistoryTimeRange r = AlertHistoryTimeRange.resolve(null, endIso, AlertHistoryTimeRange.Preset.NONE, 7);
        assertEquals(AlertHistoryTimeRange.ResolutionSource.IMPLICIT_DEFAULT_WINDOW, r.source);
        assertEquals(endParsed, r.end);
        assertEquals(endParsed.minusDays(7), r.start);
    }

    @Test
    void presetLast24hWhenBothBoundsOmitted() {
        AlertHistoryTimeRange r = AlertHistoryTimeRange.resolve(null, null, AlertHistoryTimeRange.Preset.LAST_24H, 7);
        assertEquals(AlertHistoryTimeRange.ResolutionSource.PRESET, r.source);
        assertEquals(AlertHistoryTimeRange.Preset.LAST_24H, r.appliedPreset);
        long hours = (r.end.getMillis() - r.start.getMillis()) / 3600000L;
        assertEquals(24L, hours);
    }

    @Test
    void presetCannotCombineWithExplicitStart() {
        assertThrows(IllegalArgumentException.class,
                () -> AlertHistoryTimeRange.resolve("2026-04-01T00:00:00Z", null,
                        AlertHistoryTimeRange.Preset.LAST_24H, 7));
    }

    @Test
    void presetCannotCombineWithExplicitEnd() {
        assertThrows(IllegalArgumentException.class,
                () -> AlertHistoryTimeRange.resolve(null, "2026-04-17T12:00:00Z",
                        AlertHistoryTimeRange.Preset.LAST_24H, 7));
    }

    @Test
    void explicitStartBeforeEnd() {
        AlertHistoryTimeRange r = AlertHistoryTimeRange.resolve("2026-04-01T00:00:00Z", "2026-04-10T00:00:00Z",
                AlertHistoryTimeRange.Preset.NONE, 7);
        assertEquals(AlertHistoryTimeRange.ResolutionSource.EXPLICIT_ISO, r.source);
    }

    @Test
    void invalidExplicitStartThrowsAlignedWithExplicitIsoTimeBounds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AlertHistoryTimeRange.resolve("not-a-date", null, AlertHistoryTimeRange.Preset.NONE, 7));
        assertTrue(ex.getMessage().contains("startTime"));
    }

    @Test
    void resolveParsed_matchesResolveForExplicitPair() {
        org.joda.time.DateTime s = org.joda.time.DateTime.parse("2026-04-01T00:00:00Z");
        org.joda.time.DateTime e = org.joda.time.DateTime.parse("2026-04-10T00:00:00Z");
        AlertHistoryTimeRange a = AlertHistoryTimeRange.resolveParsed(s, e, AlertHistoryTimeRange.Preset.NONE, 7);
        AlertHistoryTimeRange b =
                AlertHistoryTimeRange.resolve("2026-04-01T00:00:00Z", "2026-04-10T00:00:00Z",
                        AlertHistoryTimeRange.Preset.NONE, 7);
        assertEquals(a.start.getMillis(), b.start.getMillis());
        assertEquals(a.end.getMillis(), b.end.getMillis());
        assertEquals(a.source, b.source);
    }

    @Test
    void startAfterEndThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> AlertHistoryTimeRange.resolve("2026-04-10T00:00:00Z", "2026-04-01T00:00:00Z",
                        AlertHistoryTimeRange.Preset.NONE, 7));
    }

    @Test
    void presetLast1hWhenBothBoundsOmitted() {
        AlertHistoryTimeRange r = AlertHistoryTimeRange.resolve(null, null, AlertHistoryTimeRange.Preset.LAST_1H, 7);
        assertEquals(AlertHistoryTimeRange.ResolutionSource.PRESET, r.source);
        long hours = (r.end.getMillis() - r.start.getMillis()) / 3600000L;
        assertEquals(1L, hours);
    }

    @Test
    void fromParlerResolution_relativeDuration() {
        Instant start = Instant.parse("2026-05-01T10:00:00Z");
        Instant end = Instant.parse("2026-05-01T11:00:00Z");
        ParlerTimeResolution pr = ParlerTimeResolution.okClosedOpenRange(start, end);
        AlertHistoryTimeRange r = AlertHistoryTimeRange.fromParlerResolution(pr,
                AlertHistoryTimeRange.ResolutionSource.NATURAL_LANGUAGE_RELATIVE_DURATION);
        assertEquals(AlertHistoryTimeRange.ResolutionSource.NATURAL_LANGUAGE_RELATIVE_DURATION, r.source);
        assertEquals(start.toEpochMilli(), r.start.getMillis());
        assertEquals(end.toEpochMilli(), r.end.getMillis());
    }
}
