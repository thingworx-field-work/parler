package com.thingworx.things.agent.tools;

import java.time.Instant;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import com.thingworx.things.agent.time.ParlerTimeResolution;

/**
 * Offline-testable resolution of {@code query_alert_history} bounds: explicit ISO instants and/or
 * {@code timePreset} shortcuts (see {@code docs/operations/alert-solution.md} §11).
 */
public final class AlertHistoryTimeRange {

    /** When both {@code startTime} and {@code endTime} are omitted and no preset: window length in days. */
    public static final int DEFAULT_WINDOW_DAYS = 7;

    public enum Preset {
        /** No preset (use explicit ISO and/or default window). */
        NONE,
        LAST_1H,
        LAST_24H,
        LAST_7D
    }

    /** How the window was chosen (for tool result metadata). */
    public enum ResolutionSource {
        EXPLICIT_ISO,
        PRESET,
        IMPLICIT_DEFAULT_WINDOW,
        /** Local calendar day via {@code calendarPhrase} + {@link com.thingworx.things.agent.time.ParlerTimeResolver#tryResolveLocalCalendarDayEnglish}. */
        NATURAL_LANGUAGE_CALENDAR_DAY,
        /** Closed-open span ending at {@code now} via {@link com.thingworx.things.agent.time.ParlerTimeResolver#resolveRelativeDurationClosedOpen}. */
        NATURAL_LANGUAGE_RELATIVE_DURATION
    }

    public final DateTime start;
    public final DateTime end;
    public final ResolutionSource source;
    /** Non-null when {@link #source} is {@link ResolutionSource#PRESET}. */
    public final Preset appliedPreset;

    private AlertHistoryTimeRange(DateTime start, DateTime end, ResolutionSource source, Preset appliedPreset) {
        this.start = start;
        this.end = end;
        this.source = source;
        this.appliedPreset = appliedPreset != null ? appliedPreset : Preset.NONE;
    }

    /**
     * @param startTimeIso optional ISO-8601 start
     * @param endTimeIso optional ISO-8601 end (default now if blank)
     * @param preset parsed preset; must be {@link Preset#NONE} if either bound is explicitly provided
     * @throws IllegalArgumentException invalid combination or unknown preset string (caller maps to tool error)
     */
    public static AlertHistoryTimeRange resolve(String startTimeIso, String endTimeIso, Preset preset,
            int defaultWindowDays) {
        ExplicitIsoTimeBounds.ParseOutcome po =
                ExplicitIsoTimeBounds.parseAlertHistoryOptionalStrings(startTimeIso, endTimeIso);
        if (!po.ok) {
            throw new IllegalArgumentException(po.errorMessage);
        }
        return resolveParsed(po.start, po.end, preset, defaultWindowDays);
    }

    /**
     * Same window logic as string {@link #resolve} after ISO parse — shared with
     * {@link ExplicitIsoTimeBounds#parseAlertHistoryOptionalStrings}.
     */
    public static AlertHistoryTimeRange resolveParsed(DateTime startDtOrNull, DateTime endDtOrNull, Preset preset,
            int defaultWindowDays) {
        boolean hasStart = startDtOrNull != null;
        boolean hasEnd = endDtOrNull != null;
        if (preset != null && preset != Preset.NONE && (hasStart || hasEnd)) {
            throw new IllegalArgumentException("timePreset cannot be combined with startTime or endTime");
        }
        DateTime endDt = hasEnd ? endDtOrNull : DateTime.now();
        DateTime startDt;
        ResolutionSource src;
        Preset applied = Preset.NONE;

        if (hasStart) {
            startDt = startDtOrNull;
            src = ResolutionSource.EXPLICIT_ISO;
        } else if (preset != null && preset != Preset.NONE) {
            applied = preset;
            switch (preset) {
                case LAST_1H:
                    startDt = endDt.minusHours(1);
                    break;
                case LAST_24H:
                    startDt = endDt.minusHours(24);
                    break;
                case LAST_7D:
                    startDt = endDt.minusDays(7);
                    break;
                case NONE:
                default:
                    startDt = endDt.minusDays(defaultWindowDays);
                    applied = Preset.NONE;
                    break;
            }
            src = ResolutionSource.PRESET;
        } else {
            startDt = endDt.minusDays(defaultWindowDays);
            src = ResolutionSource.IMPLICIT_DEFAULT_WINDOW;
        }

        if (startDt.isAfter(endDt)) {
            throw new IllegalArgumentException("startTime must be before or equal to endTime");
        }
        return new AlertHistoryTimeRange(startDt, endDt, src, applied);
    }

    /**
     * Builds a range from a successful {@link ParlerTimeResolution} (UTC instants → Joda at the ThingWorx boundary).
     *
     * @param source {@link ResolutionSource#NATURAL_LANGUAGE_CALENDAR_DAY} or
     *               {@link ResolutionSource#NATURAL_LANGUAGE_RELATIVE_DURATION}
     */
    public static AlertHistoryTimeRange fromParlerResolution(ParlerTimeResolution r, ResolutionSource source) {
        if (r == null || !r.isSuccess()) {
            throw new IllegalArgumentException("resolution must be successful");
        }
        if (source != ResolutionSource.NATURAL_LANGUAGE_CALENDAR_DAY
                && source != ResolutionSource.NATURAL_LANGUAGE_RELATIVE_DURATION) {
            throw new IllegalArgumentException("source must be a natural-language resolution kind");
        }
        DateTime startDt = utcDateTime(r.getStartUtc());
        DateTime endDt = utcDateTime(r.getEndUtc());
        if (startDt.isAfter(endDt)) {
            throw new IllegalArgumentException("startTime must be before or equal to endTime");
        }
        return new AlertHistoryTimeRange(startDt, endDt, source, Preset.NONE);
    }

    private static DateTime utcDateTime(Instant instant) {
        return new DateTime(instant.toEpochMilli(), DateTimeZone.UTC);
    }

    /**
     * @param raw trimmed or null/blank → {@link Preset#NONE}
     */
    /** Wire / metadata string for {@link #appliedPreset} when {@link #source} is {@link ResolutionSource#PRESET}. */
    public static String wirePresetName(Preset p) {
        if (p == null || p == Preset.NONE) {
            return null;
        }
        switch (p) {
            case LAST_1H:
                return "last_1h";
            case LAST_24H:
                return "last_24h";
            case LAST_7D:
                return "last_7d";
            case NONE:
            default:
                return null;
        }
    }

    public static Preset parsePreset(String raw) {
        if (raw == null || raw.isBlank()) {
            return Preset.NONE;
        }
        String s = raw.trim();
        switch (s.toLowerCase()) {
            case "last_1h":
                return Preset.LAST_1H;
            case "last_24h":
                return Preset.LAST_24H;
            case "last_7d":
                return Preset.LAST_7D;
            default:
                throw new IllegalArgumentException("Unknown timePreset: " + raw);
        }
    }
}
