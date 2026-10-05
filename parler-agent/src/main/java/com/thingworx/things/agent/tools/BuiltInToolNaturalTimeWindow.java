package com.thingworx.things.agent.tools;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.things.agent.time.ParlerTimeResolution;
import com.thingworx.things.agent.time.ParlerTimeResolver;

/**
 * Optional natural-language time bounds for curated built-ins (§13): {@code calendarPhrase} (local
 * {@code today}/{@code yesterday}/{@code tomorrow}) and {@code relativeDuration} (closed-open span ending at
 * {@code nowUtc}). Mutually exclusive with explicit ISO bounds; alert history also rejects mixing with
 * {@code timePreset}.
 */
public final class BuiltInToolNaturalTimeWindow {

    public enum AppliedKind {
        CALENDAR_DAY_ENGLISH,
        RELATIVE_DURATION
    }

    public static final class Outcome {
        public final boolean skip;
        /** Non-null when {@link #skip} is false and {@link #errorCode} is null. */
        public final ParlerTimeResolution resolution;
        public final AppliedKind kind;
        public final String errorCode;
        public final String errorMessage;
        /**
         * Optional path-style identifier of the parameter
         * the LLM should fix on retry — e.g. {@code "calendarPhrase"} for shape errors on that field, or
         * {@code "relativeDuration"} for resolver errors. {@code null} when the conflict spans multiple
         * fields (e.g. {@code TIME_PHRASE_VS_PRESET_CONFLICT}, {@code TIME_PHRASE_COMBINED_INVALID},
         * {@code TIME_PHRASE_VS_EXPLICIT_BOUND_CONFLICT}) — those have no single offending field. The
         * executor surfaces this as the wire JSON {@code rejectedParameter} field via
         * {@link BuiltInToolTimeErrorJson}, mirroring the {@code invoke_service}
         * {@code UNSUPPORTED_RELATIVE_LITERAL} envelope shape.
         */
        public final String rejectedParameter;

        private Outcome(boolean skip, ParlerTimeResolution resolution, AppliedKind kind, String errorCode,
                String errorMessage, String rejectedParameter) {
            this.skip = skip;
            this.resolution = resolution;
            this.kind = kind;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.rejectedParameter = rejectedParameter;
        }

        public static Outcome skip() {
            return new Outcome(true, null, null, null, null, null);
        }

        public static Outcome ok(ParlerTimeResolution resolution, AppliedKind kind) {
            return new Outcome(false, resolution, kind, null, null, null);
        }

        /**
         * Multi-field / cross-cutting error: no single offending parameter (e.g. mutual-exclusion
         * conflicts). {@code rejectedParameter} stays {@code null}.
         */
        public static Outcome error(String code, String message) {
            return new Outcome(false, null, null, code, message, null);
        }

        /**
         * Single-field error: the LLM can surgically retry by fixing the named parameter. {@code
         * rejectedParameter} is the field name as it appears in the tool argument schema (e.g.
         * {@code "calendarPhrase"}, {@code "relativeDuration"}, {@code "startTime"}).
         */
        public static Outcome errorOn(String code, String message, String rejectedParameter) {
            return new Outcome(false, null, null, code, message, rejectedParameter);
        }
    }

    private BuiltInToolNaturalTimeWindow() {}

    public static Outcome resolveAlertHistory(JsonNode root, Instant nowUtc, String startTime, String endTime,
            boolean presetActive) {
        return resolve(root, nowUtc, presetActive, startTime, endTime);
    }

    public static Outcome resolveNumericPropertyHistory(JsonNode root, Instant nowUtc, String startStr,
            String endStr) {
        return resolve(root, nowUtc, false, startStr, endStr);
    }

    private static Outcome resolve(JsonNode root, Instant nowUtc, boolean presetActive, String startRaw,
            String endRaw) {
        if (root == null || nowUtc == null) {
            return Outcome.error("INVALID_TIME_SPEC_SHAPE", "Tool arguments and clock are required.");
        }
        // Shape errors identify the specific field via rejectedParameter.
        Outcome shapeErr = requireTextualOrAbsent(root, "calendarPhrase");
        if (shapeErr != null) {
            return shapeErr;
        }
        shapeErr = requireTextualOrAbsent(root, "relativeDuration");
        if (shapeErr != null) {
            return shapeErr;
        }

        JsonNode cn = root.get("calendarPhrase");
        JsonNode rn = root.get("relativeDuration");
        String cal = (cn == null || cn.isNull()) ? null : cn.asText().trim();
        String rel = (rn == null || rn.isNull()) ? null : rn.asText().trim();
        boolean hasCal = cal != null && !cal.isEmpty();
        boolean hasRel = rel != null && !rel.isEmpty();
        boolean explicitStart = startRaw != null && !startRaw.isBlank();
        boolean explicitEnd = endRaw != null && !endRaw.isBlank();
        boolean hasExplicit = explicitStart || explicitEnd;

        if (!hasCal && !hasRel) {
            return Outcome.skip();
        }
        // Mutual-exclusion / cross-field conflicts: no single offending parameter — rejectedParameter
        // stays null because no single field is identifiable.
        if (hasCal && hasRel) {
            return Outcome.error("TIME_PHRASE_COMBINED_INVALID",
                    "Set at most one of calendarPhrase and relativeDuration.");
        }
        if (presetActive && (hasCal || hasRel)) {
            return Outcome.error("TIME_PHRASE_VS_PRESET_CONFLICT",
                    "Do not combine calendarPhrase or relativeDuration with timePreset.");
        }
        if (hasExplicit && (hasCal || hasRel)) {
            return Outcome.error("TIME_PHRASE_VS_EXPLICIT_BOUND_CONFLICT",
                    "Do not combine calendarPhrase or relativeDuration with startTime/endTime (or start/end aliases).");
        }

        if (hasCal) {
            ZoneId zone = resolveUserZoneOrNull();
            if (zone == null) {
                // The missing piece is the host timezone, but the LLM-fixable parameter is
                // calendarPhrase (the field that depends on the timezone) — drop or restate.
                return Outcome.errorOn("MISSING_OR_INVALID_USER_TIMEZONE",
                        "calendarPhrase requires a valid host user_timezone (IANA), e.g. America/New_York.",
                        "calendarPhrase");
            }
            ParlerTimeResolution r = ParlerTimeResolver.tryResolveLocalCalendarDayEnglish(cal, nowUtc, zone);
            if (r != null) {
                if (!r.isSuccess()) {
                    return Outcome.errorOn(r.getErrorCode().name(), r.getErrorMessage(), "calendarPhrase");
                }
                ParlerTimeResolution residue = ParlerTimeResolver.rejectIfLocalCalendarDayPhraseHasUnsupportedResidue(cal);
                if (residue != null) {
                    return Outcome.errorOn(residue.getErrorCode().name(), residue.getErrorMessage(),
                            "calendarPhrase");
                }
                return Outcome.ok(r, AppliedKind.CALENDAR_DAY_ENGLISH);
            }
            ParlerTimeResolution rej = ParlerTimeResolver.rejectIfUnsupportedCalendarPhrase(cal);
            if (rej != null) {
                return Outcome.errorOn(rej.getErrorCode().name(), rej.getErrorMessage(), "calendarPhrase");
            }
            return Outcome.errorOn("UNSUPPORTED_CALENDAR_PHRASE",
                    "calendarPhrase must name a single local day using today, yesterday, or tomorrow (v1).",
                    "calendarPhrase");
        }

        ParlerTimeResolution relRes =
                ParlerTimeResolver.resolveRelativeDurationClosedOpen(nowUtc, rel, true, null);
        if (!relRes.isSuccess()) {
            return Outcome.errorOn(relRes.getErrorCode().name(), relRes.getErrorMessage(),
                    "relativeDuration");
        }
        return Outcome.ok(relRes, AppliedKind.RELATIVE_DURATION);
    }

    /** @return {@code null} if absent or valid textual; otherwise an error outcome carrying {@code field}
     *         as {@code rejectedParameter} so the LLM can identify which slot to fix. */
    private static Outcome requireTextualOrAbsent(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            return Outcome.errorOn("INVALID_TIME_SPEC_SHAPE", field + " must be a JSON string.", field);
        }
        return null;
    }

    private static ZoneId resolveUserZoneOrNull() {
        String iana = AgentToolContext.getUserIanaTimezone();
        if (iana == null || iana.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(iana.trim());
        } catch (DateTimeException ignored) {
            return null;
        }
    }
}
