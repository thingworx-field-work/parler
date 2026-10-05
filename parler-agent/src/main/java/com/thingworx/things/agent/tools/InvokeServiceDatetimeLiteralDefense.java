package com.thingworx.things.agent.tools;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.thingworx.things.agent.time.ParlerTimeResolution;
import com.thingworx.things.agent.time.ParlerTimeResolver;

/**
 * §13 step 3 (slice): before Joda {@link org.joda.time.DateTime#parse} on {@code invoke_service} DATETIME parameters,
 * reject obvious relative / informal calendar text ({@code docs/agent/time-interpretation.md} §7
 * {@code UNSUPPORTED_RELATIVE_LITERAL}). Conservative — extend patterns as telemetry warrants.
 *
 * <p>Rejections use a typed exception path:</p>
 * <ul>
 *   <li>{@link RejectionReason} classifier — telemetry consumers can group rejections without parsing the
 *       human message.</li>
 *   <li>There is no string-prefix error channel: code throws {@link UnsupportedRelativeLiteralException} via
 *       {@link #throwIfRejected(String, String)}.</li>
 * </ul>
 *
 * <p>{@link #INFORMAL_RELATIVE} covers {@code now}, {@code now+/-N}, {@code last N…}, bare {@code <n><unit>},
 * and the three first-class day tokens ({@code today|yesterday|tomorrow}); raw DATETIME slots are never a valid
 * place for those — surface {@code UNSUPPORTED_RELATIVE_LITERAL} so the LLM is steered to the curated
 * {@code calendarPhrase} field for built-in tools.</p>
 *
 * <p>{@link #DATETIME_LIKE_NAMES} mirrors the §8 list (with {@code startTime} / {@code endTime} added — these
 * are the names actually used by ThingWorx services and the curated built-ins). Used to scope the JSON / VARIANT slot fallback (§5 step 3).</p>
 */
public final class InvokeServiceDatetimeLiteralDefense {

    /**
     * Stable classifier on the typed rejection so telemetry queries can group without parsing the human
     * message.
     *
     * <p><b>Stability policy (extend liberally).</b> The enum is
     * internal to Parler: it is not persisted, not part of the AlwaysOn wire contract version, and not
     * referenced as a discriminator by any out-of-tree consumer at this time. New pattern families surfaced
     * by telemetry (e.g. relative-anchor-with-iso forms like {@code now-2026-05-06}) should be added as
     * <em>new</em> values rather than collapsed under {@link #INFORMAL_RELATIVE} — collapsing would defeat
     * the classifier's purpose of grouping for analysis.</p>
     *
     * <p><b>Consumer guidance.</b> Any code that switches on this enum (including telemetry pipelines) must
     * treat unknown / future values as opaque (group under {@code "other"} / a default branch). Adding a
     * new value is therefore source-additive, not breaking. The wire JSON emits the {@code name()} string
     * directly under {@code rejectionReason}; downstream wire consumers should follow the same opaque
     * default-branch policy.</p>
     */
    public enum RejectionReason {
        /** §8 calendar / wall-clock phrase: {@code this morning}, {@code 8am}, {@code this month}, etc. */
        CALENDAR_OR_WALL_CLOCK,
        /**
         * {@code now}, {@code now+/-N}, {@code last N…}, {@code past N…}, {@code N <unit> ago}, bare
         * {@code <n><unit>} (e.g. {@code 30m}). The catch-all family for relative durations / anchors that
         * are not first-class day tokens.
         */
        INFORMAL_RELATIVE,
        /** {@code today} / {@code yesterday} / {@code tomorrow} as a raw DATETIME instant. */
        DAY_TOKEN
    }

    /**
     * Outcome of {@link #classify(String)}. {@code reason == null} (and {@code detail == null}) means
     * "proceed with {@code DateTime.parse}".
     */
    public static final class RejectionResult {
        public final RejectionReason reason;
        public final String detail;

        private RejectionResult(RejectionReason reason, String detail) {
            this.reason = reason;
            this.detail = detail;
        }

        public static RejectionResult pass() {
            return new RejectionResult(null, null);
        }

        public static RejectionResult fail(RejectionReason reason, String detail) {
            return new RejectionResult(reason, detail);
        }

        public boolean isRejected() {
            return reason != null;
        }
    }

    /**
     * Conservative pre-parse rejection patterns (telemetry-driven):
     * <ul>
     *   <li>{@code now}, {@code now+/-N} — anchor / arithmetic forms.</li>
     *   <li>{@code (last|past) N <unit>} — windowed look-back forms. Requires an explicit unit suffix and a
     *       strict end anchor; a looser {@code ^(last|past)\s+\d} would match anything starting with
     *       {@code "last 2"} or {@code "past 2"}, including {@code "last 2 alerts"} or {@code "past 2 months"}
     *       (no valid unit). Matches the strict form in spec §8. {@link RejectionReason#INFORMAL_RELATIVE}.</li>
     *   <li>{@code N <unit> ago} — multi-word relative form (matches §8 wording
     *       {@code [0-9]+\s+(seconds?|minutes?|hours?|days?|weeks?)\s+ago}).</li>
     *   <li>Bare {@code <n><unit>} (e.g. {@code 30m}) — duration smuggled into a DATETIME slot.</li>
     *   <li>The three first-class day tokens ({@code today|yesterday|tomorrow}) — classified separately as
     *       {@link RejectionReason#DAY_TOKEN}.</li>
     * </ul>
     */
    private static final Pattern INFORMAL_RELATIVE = Pattern.compile(
            "(?i)(^now\\b"
                    + "|^(last|past)\\s+[1-9]\\d*\\s*[smhdw]$"
                    + "|\\bnow\\s*[-+]"
                    + "|^[1-9]\\d*[smhdw]$"
                    + "|^[1-9]\\d*\\s+(seconds?|minutes?|hours?|days?|weeks?)\\s+ago$"
                    + "|\\b(today|yesterday|tomorrow)\\b)");

    /** Subset of {@link #INFORMAL_RELATIVE} that classifies as {@link RejectionReason#DAY_TOKEN}. */
    private static final Pattern DAY_TOKEN_ONLY = Pattern.compile("(?i)\\b(today|yesterday|tomorrow)\\b");

    /**
     * §8 DATETIME-like parameter names (case-insensitive). Used as the fallback signal for untyped
     * JSON / VARIANT slots: when the declared {@code BaseType} is not {@code DATETIME} but the parameter
     * name matches one of these, apply the same pre-parse defense to the raw text. This is §5 step 3's
     * "Else if parameters are untyped JSON / VARIANT bags, apply §8 name heuristics on known DATETIME-like
     * keys as a fallback" path — not a license to coerce the value, only to reject obvious relative text.
     *
     * <p>{@code startTime} / {@code endTime} are included because they are the actual names used by
     * ThingWorx history services and the curated built-ins ({@code query_alert_history},
     * {@code query_numeric_property_history}).</p>
     */
    private static final Set<String> DATETIME_LIKE_NAMES = Set.of(
            "startdate", "enddate", "starttime", "endtime",
            "timestamp", "from", "to", "at", "date", "time", "eventtime", "since", "until");

    private InvokeServiceDatetimeLiteralDefense() {}

    /**
     * Pure pattern test (no exception) — classifies the input.
     *
     * @return {@link RejectionResult#pass()} when the caller should proceed with {@code DateTime.parse};
     *         otherwise a {@link RejectionResult} carrying the {@link RejectionReason} and detail string.
     */
    public static RejectionResult classify(String raw) {
        if (raw == null || raw.isBlank()) {
            return RejectionResult.pass();
        }
        String t = raw.trim();
        ParlerTimeResolution cal = ParlerTimeResolver.rejectIfUnsupportedCalendarPhrase(t);
        if (cal != null && !cal.isSuccess()) {
            return RejectionResult.fail(RejectionReason.CALENDAR_OR_WALL_CLOCK, cal.getErrorMessage());
        }
        if (INFORMAL_RELATIVE.matcher(t).find()) {
            RejectionReason reason = DAY_TOKEN_ONLY.matcher(t).find()
                    ? RejectionReason.DAY_TOKEN
                    : RejectionReason.INFORMAL_RELATIVE;
            return RejectionResult.fail(reason,
                    "DATETIME parameters require ISO-8601 instants (UTC …Z recommended). "
                            + "Do not pass raw relative text (e.g. now-5m, last 2h, today, yesterday) "
                            + "or bare durations; use a curated Parler tool with calendarPhrase / "
                            + "relativeDuration where available.");
        }
        return RejectionResult.pass();
    }

    /**
     * Convenience kept for callers / tests that only need the detail string.
     *
     * @return {@code null} if the caller should proceed with {@code DateTime.parse}; otherwise the detail
     *         string (without any wire-error prefix)
     */
    public static String rejectBeforeJodaParseOrNull(String raw) {
        RejectionResult r = classify(raw);
        return r.isRejected() ? r.detail : null;
    }

    /**
     * Throwing wrapper used by the executor wiring. Carries {@code paramName}, {@code rawValue}, and
     * {@link RejectionReason} on the typed exception so the catch site can emit a telemetry-friendly INFO
     * log keyed on the reason.
     *
     * @throws UnsupportedRelativeLiteralException when {@link #classify(String)} returns a rejection
     */
    public static void throwIfRejected(String paramName, String rawValue) {
        RejectionResult r = classify(rawValue);
        if (r.isRejected()) {
            throw new UnsupportedRelativeLiteralException(paramName, rawValue, r.reason, r.detail);
        }
    }

    /**
     * Whether {@code paramName} matches the §8 DATETIME-like name list (case-insensitive). Used by
     * {@link InvokeServiceArgumentCoercion} to extend the defense to untyped JSON / VARIANT slots that
     * conventionally carry instants (see class Javadoc and §5 step 3 fallback path).
     */
    public static boolean isDatetimeLikeName(String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return false;
        }
        return DATETIME_LIKE_NAMES.contains(paramName.trim().toLowerCase(Locale.ROOT));
    }
}
