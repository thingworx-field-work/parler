package com.thingworx.things.agent.time;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline-testable time interpretation for Parler ({@code docs/agent/time-interpretation.md} §4.2, §7).
 * v1 scope: positive duration grammar, relative-duration closed-open ranges, English {@code today}/{@code yesterday}/
 * {@code tomorrow} when {@link ZoneId} is known, remaining calendar-phrase rejection, anchor conflict detection.
 */
public final class ParlerTimeResolver {

    private static final Pattern DURATION = Pattern.compile("^([1-9]\\d*)([smhdw])$");
    private static final Pattern UNSUPPORTED_LONG_UNIT = Pattern.compile("^([1-9]\\d*)(mo|y)$");

    private static final Pattern WORD_TODAY = Pattern.compile("\\btoday\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_YESTERDAY = Pattern.compile("\\byesterday\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TOMORROW = Pattern.compile("\\btomorrow\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Phrases still outside structured resolution — align with {@code docs/agent/time-interpretation.md} §8 where listed.
     * {@code today}/{@code yesterday}/{@code tomorrow} are handled by {@link #tryResolveLocalCalendarDayEnglish}.
     */
    private static final Pattern[] CALENDAR_PHRASES = new Pattern[] {
            Pattern.compile("\\bthis\\s+month\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bthis\\s+week\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\blast\\s+month\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnext\\s+month\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\blast\\s+week\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnext\\s+week\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bthis\\s+(morning|afternoon|evening|night)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btonight\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:noon|midnight)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b\\d{1,2}\\s*(?::\\d{2})?\\s*(?:am|pm)\\b", Pattern.CASE_INSENSITIVE),
    };

    /**
     * Wall-clock / partial-day fragments not covered by {@link #CALENDAR_PHRASES} alone. Conservative: false positives
     * yield a clear tool error (LLM can retry); false negatives widen to a full local day (not recoverable).
     * The {@code H:mm} pattern does not validate hour/minute ranges {@code ≤23 / ≤59}.
     * <p>Set is <strong>incremental</strong> — extend as real prompts surface gaps (e.g. {@code 9 to 5} without
     * {@code between}).</p>
     */
    private static final Pattern[] LOCAL_DAY_PHRASE_EXTRA_RESIDUE = new Pattern[] {
            Pattern.compile("\\bat\\s+\\d", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"),
            Pattern.compile("\\b(after|before|since|until|from)\\s+\\d", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bbetween\\s+\\d", Pattern.CASE_INSENSITIVE),
            // Greedy `.*` — `calendarPhrase` is a short day label, not a long narrative; false positives are acceptable.
            Pattern.compile("\\b(today|yesterday|tomorrow)\\b.*\\b(morning|afternoon|evening|night)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(morning|afternoon|evening|night)\\b.*\\b(today|yesterday|tomorrow)\\b",
                    Pattern.CASE_INSENSITIVE),
    };

    private ParlerTimeResolver() {}

    /** Internal duration parse outcome (ThingWorx extension compiles with Java 11 source level). */
    static final class ParsedDuration {
        final boolean ok;
        final long seconds;
        final ParlerTimeErrorCode code;
        final String message;

        private ParsedDuration(boolean ok, long seconds, ParlerTimeErrorCode code, String message) {
            this.ok = ok;
            this.seconds = seconds;
            this.code = code;
            this.message = message;
        }

        static ParsedDuration success(long sec) {
            return new ParsedDuration(true, sec, null, null);
        }

        static ParsedDuration failure(ParlerTimeErrorCode code, String message) {
            return new ParsedDuration(false, 0, code, message);
        }
    }

    /**
     * Resolves a single English calendar-day token in {@code phrase}: {@code today}, {@code yesterday}, or
     * {@code tomorrow} (word-boundary match). Uses {@code nowUtc} projected into {@code zone} to pick the calendar
     * day, then returns closed-open {@code [local midnight, next local midnight)} in UTC.
     *
     * @return successful {@link ParlerTimeResolution}; a failure if the phrase mixes multiple day tokens or
     *         {@code zone} is null; {@code null} if none of the three words appear (caller may try other parsers or
     *         {@link #rejectIfUnsupportedCalendarPhrase}).
     */
    public static ParlerTimeResolution tryResolveLocalCalendarDayEnglish(String phrase, Instant nowUtc, ZoneId zone) {
        if (phrase == null || phrase.isBlank()) {
            return null;
        }
        if (nowUtc == null) {
            return ParlerTimeResolution.failure(ParlerTimeErrorCode.INVALID_TIME_SPEC_SHAPE, "nowUtc is required.");
        }
        String t = phrase.trim();
        boolean hasToday = WORD_TODAY.matcher(t).find();
        boolean hasYesterday = WORD_YESTERDAY.matcher(t).find();
        boolean hasTomorrow = WORD_TOMORROW.matcher(t).find();
        int n = (hasToday ? 1 : 0) + (hasYesterday ? 1 : 0) + (hasTomorrow ? 1 : 0);
        if (n == 0) {
            return null;
        }
        if (n > 1) {
            return ParlerTimeResolution.failure(ParlerTimeErrorCode.UNSUPPORTED_CALENDAR_PHRASE,
                    "Phrase refers to more than one of today / yesterday / tomorrow; ask the user for a single day or an "
                            + "explicit ISO UTC range.");
        }
        if (zone == null) {
            return ParlerTimeResolution.failure(ParlerTimeErrorCode.INVALID_TIME_SPEC_SHAPE,
                    "Resolve today / yesterday / tomorrow requires a valid user_timezone (IANA).");
        }
        LocalDate anchorDay = nowUtc.atZone(zone).toLocalDate();
        LocalDate targetDay;
        if (hasToday) {
            targetDay = anchorDay;
        } else if (hasYesterday) {
            targetDay = anchorDay.minusDays(1);
        } else {
            targetDay = anchorDay.plusDays(1);
        }
        ZonedDateTime startZ = targetDay.atStartOfDay(zone);
        ZonedDateTime endZ = targetDay.plusDays(1).atStartOfDay(zone);
        Instant startUtc = startZ.toInstant();
        Instant endUtc = endZ.toInstant();
        if (!startUtc.isBefore(endUtc)) {
            return ParlerTimeResolution.failure(ParlerTimeErrorCode.RANGE_START_AFTER_END,
                    "Calendar day window is empty or invalid for this timezone.");
        }
        return ParlerTimeResolution.okClosedOpenRange(startUtc, endUtc);
    }

    /**
     * If {@code phrase} is a calendar / wall-clock expression outside v1 structured grammar, returns a failure with
     * {@link ParlerTimeErrorCode#UNSUPPORTED_CALENDAR_PHRASE}; otherwise {@code null}. Do not map phrases (e.g.
     * {@code this month}) to approximate durations. Call {@link #tryResolveLocalCalendarDayEnglish} first for
     * {@code today}/{@code yesterday}/{@code tomorrow} when {@link ZoneId} is available.
     */
    public static ParlerTimeResolution rejectIfUnsupportedCalendarPhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return null;
        }
        String t = phrase.trim();
        for (Pattern p : CALENDAR_PHRASES) {
            if (p.matcher(t).find()) {
                return ParlerTimeResolution.failure(ParlerTimeErrorCode.UNSUPPORTED_CALENDAR_PHRASE,
                        "Calendar or wall-clock phrases are outside v1 timeSpec grammar. Ask the user for an explicit ISO "
                                + "UTC range or a supported relative duration (e.g. 30m, 7d). Do not approximate with "
                                + "fixed durations.");
            }
        }
        return null;
    }

    /**
     * After {@link #tryResolveLocalCalendarDayEnglish} returns success, reject phrases that also imply wall-clock or
     * finer calendar semantics (§8 patterns plus common clock fragments). Plain context around {@code today} /
     * {@code yesterday} / {@code tomorrow} (e.g. possessives, “show … alerts”) stays allowed.
     *
     * @return failure or {@code null} when the phrase is day-only for v1
     */
    public static ParlerTimeResolution rejectIfLocalCalendarDayPhraseHasUnsupportedResidue(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return null;
        }
        String t = phrase.trim();
        ParlerTimeResolution fromSection8 = rejectIfUnsupportedCalendarPhrase(t);
        if (fromSection8 != null) {
            return fromSection8;
        }
        for (Pattern p : LOCAL_DAY_PHRASE_EXTRA_RESIDUE) {
            if (p.matcher(t).find()) {
                return ParlerTimeResolution.failure(ParlerTimeErrorCode.UNSUPPORTED_CALENDAR_PHRASE,
                        "Phrase names a calendar day but also includes wall-clock or finer calendar wording; use an "
                                + "explicit ISO UTC range, relativeDuration, or a simple day phrase (today / yesterday "
                                + "/ tomorrow) without times.");
            }
        }
        return null;
    }

    /**
     * Relative duration range with closed-open semantics {@code [anchor - duration, anchor)} (§4.2, §4.4).
     *
     * @param nowUtc              injected clock for {@code anchorIsNow}
     * @param durationStr         {@code <positive-integer><unit>} with unit in {@code smhdw}
     * @param anchorIsNow         {@code true} when JSON used {@code anchor: "now"}
     * @param anchorUtcExplicit   non-null when JSON used {@code anchor_utc} exclusively
     */
    public static ParlerTimeResolution resolveRelativeDurationClosedOpen(Instant nowUtc, String durationStr,
            boolean anchorIsNow, Instant anchorUtcExplicit) {
        if (nowUtc == null) {
            return ParlerTimeResolution.failure(ParlerTimeErrorCode.INVALID_TIME_SPEC_SHAPE, "nowUtc is required.");
        }
        if (anchorIsNow && anchorUtcExplicit != null) {
            return ParlerTimeResolution.failure(ParlerTimeErrorCode.ANCHOR_AND_ANCHOR_UTC_CONFLICT,
                    "Do not pass both anchor: \"now\" and anchor_utc for the same relative node.");
        }
        Instant anchor = anchorIsNow ? nowUtc : anchorUtcExplicit;
        if (anchor == null) {
            return ParlerTimeResolution.failure(ParlerTimeErrorCode.INVALID_TIME_SPEC_SHAPE,
                    "Relative duration requires anchor: \"now\" or anchor_utc.");
        }

        ParsedDuration pd = parseDuration(durationStr);
        if (!pd.ok) {
            return ParlerTimeResolution.failure(pd.code, pd.message);
        }
        long seconds = pd.seconds;
        Instant start;
        Instant end = anchor;
        try {
            start = anchor.minusSeconds(seconds);
        } catch (DateTimeException e) {
            return ParlerTimeResolution.failure(ParlerTimeErrorCode.INVALID_DURATION_GRAMMAR,
                    "Resolved window exceeds supported Instant range.");
        }
        if (!start.isBefore(end)) {
            return ParlerTimeResolution.failure(ParlerTimeErrorCode.RANGE_START_AFTER_END,
                    "Resolved window start must be strictly before end for closed-open ranges.");
        }
        return ParlerTimeResolution.okClosedOpenRange(start, end);
    }

    static ParsedDuration parseDuration(String durationStr) {
        if (durationStr == null || durationStr.isBlank()) {
            return ParsedDuration.failure(ParlerTimeErrorCode.INVALID_DURATION_GRAMMAR,
                    "Duration must be a positive integer followed by s, m, h, d, or w.");
        }
        String s = durationStr.trim();
        if (UNSUPPORTED_LONG_UNIT.matcher(s).matches()) {
            return ParsedDuration.failure(ParlerTimeErrorCode.UNSUPPORTED_UNIT,
                    "Units mo and y are not supported in v1 — use explicit ISO ranges or smaller units.");
        }
        Matcher m = DURATION.matcher(s);
        if (!m.matches()) {
            return ParsedDuration.failure(ParlerTimeErrorCode.INVALID_DURATION_GRAMMAR,
                    "Expected <positive-integer><unit> with unit in s, m, h, d, w.");
        }
        long n;
        try {
            n = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return ParsedDuration.failure(ParlerTimeErrorCode.INVALID_DURATION_GRAMMAR,
                    "Duration magnitude is not a valid integer.");
        }
        String u = m.group(2);
        return unitToSecondsSafe(n, u);
    }

    static ParsedDuration unitToSecondsSafe(long n, String u) {
        try {
            long sec;
            switch (u.toLowerCase(Locale.ROOT)) {
                case "s":
                    sec = n;
                    break;
                case "m":
                    sec = Math.multiplyExact(n, 60L);
                    break;
                case "h":
                    sec = Math.multiplyExact(n, 3600L);
                    break;
                case "d":
                    sec = Math.multiplyExact(n, 86400L);
                    break;
                case "w": {
                    long days = Math.multiplyExact(n, 7L);
                    sec = Math.multiplyExact(days, 86400L);
                    break;
                }
                default:
                    throw new IllegalStateException("unexpected unit " + u);
            }
            return ParsedDuration.success(sec);
        } catch (ArithmeticException e) {
            return ParsedDuration.failure(ParlerTimeErrorCode.INVALID_DURATION_GRAMMAR,
                    "Duration magnitude exceeds supported range.");
        }
    }

    /**
     * Validates {@code smhdw} duration grammar (PoP {@code anchorOffset}, shared with relative windows).
     *
     * @return failure when invalid; {@code null} when {@code durationStr} parses successfully
     */
    public static ParlerTimeResolution validateDurationGrammar(String durationStr) {
        ParsedDuration pd = parseDuration(durationStr);
        if (!pd.ok) {
            return ParlerTimeResolution.failure(pd.code, pd.message);
        }
        return null;
    }

    /** Seconds for a string that passed {@link #validateDurationGrammar}. */
    public static long durationSeconds(String durationStr) {
        ParsedDuration pd = parseDuration(durationStr);
        if (!pd.ok) {
            throw new IllegalArgumentException("invalid duration: " + durationStr);
        }
        return pd.seconds;
    }
}
