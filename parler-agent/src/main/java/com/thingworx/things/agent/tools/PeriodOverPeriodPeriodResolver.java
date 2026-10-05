package com.thingworx.things.agent.tools;

import java.time.DateTimeException;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.things.agent.time.ParlerTimeResolution;
import com.thingworx.things.agent.time.ParlerTimeResolver;

/**
 * Resolves one PoP period window from shared {@code anchorTime} plus optional {@code anchorOffset}
 * (see {@code docs/agent/history-overlay-chart.md}).
 */
public final class PeriodOverPeriodPeriodResolver {

    public static final class Outcome {
        public final Instant startUtc;
        public final Instant endUtc;
        public final String resolvedTimeZone;
        public final String errorCode;
        public final String errorMessage;
        public final String rejectedParameter;

        private Outcome(Instant startUtc, Instant endUtc, String resolvedTimeZone, String errorCode,
                String errorMessage, String rejectedParameter) {
            this.startUtc = startUtc;
            this.endUtc = endUtc;
            this.resolvedTimeZone = resolvedTimeZone;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.rejectedParameter = rejectedParameter;
        }

        public static Outcome ok(Instant startUtc, Instant endUtc, String resolvedTimeZone) {
            return new Outcome(startUtc, endUtc, resolvedTimeZone, null, null, null);
        }

        public static Outcome error(String code, String message, String rejectedParameter) {
            return new Outcome(null, null, null, code, message, rejectedParameter);
        }

        public boolean isError() {
            return errorCode != null;
        }
    }

    private PeriodOverPeriodPeriodResolver() {}

    /**
     * Derives {@code periodAnchor = sharedAnchor - anchorOffset}, then resolves the period spec
     * against that anchor (not the raw shared anchor).
     */
    public static Outcome resolve(JsonNode periodNode, Instant sharedAnchor) {
        if (periodNode == null || sharedAnchor == null) {
            return Outcome.error("POP_INVALID_PERIOD", "Period and anchor are required.", null);
        }

        String offsetRaw = text(periodNode, "anchorOffset");
        long offsetSeconds = 0L;
        if (offsetRaw != null && !offsetRaw.isBlank()) {
            ParlerTimeResolution grammar = ParlerTimeResolver.validateDurationGrammar(offsetRaw.trim());
            if (grammar != null) {
                return Outcome.error(grammar.getErrorCode().name(), grammar.getErrorMessage(), "anchorOffset");
            }
            offsetSeconds = ParlerTimeResolver.durationSeconds(offsetRaw.trim());
        }

        boolean hasExplicit = hasExplicitBounds(periodNode);
        if (hasExplicit && offsetRaw != null && !offsetRaw.isBlank()) {
            return Outcome.error("POP_OFFSET_WITH_EXPLICIT",
                    "Do not combine anchorOffset with startTime/endTime; use anchorOffset with relativeDuration.",
                    "anchorOffset");
        }

        Instant periodAnchor;
        try {
            periodAnchor = sharedAnchor.minusSeconds(offsetSeconds);
        } catch (DateTimeException e) {
            return Outcome.error("POP_INVALID_ANCHOR_OFFSET",
                    "anchorOffset shifts the shared anchor outside supported range.", "anchorOffset");
        }

        BuiltInToolNaturalTimeWindow.Outcome window =
                BuiltInToolNaturalTimeWindow.resolveNumericPropertyHistory(periodNode, periodAnchor, null, null);
        if (window.errorCode != null) {
            return Outcome.error(window.errorCode, window.errorMessage, window.rejectedParameter);
        }

        String appliedTz = null;
        if (!window.skip) {
            if (window.kind == BuiltInToolNaturalTimeWindow.AppliedKind.CALENDAR_DAY_ENGLISH) {
                appliedTz = AgentToolContext.getUserIanaTimezone();
            }
            return Outcome.ok(window.resolution.getStartUtc(), window.resolution.getEndUtc(), appliedTz);
        }

        String startStr = firstNonBlank(text(periodNode, "startTime"), text(periodNode, "start"));
        String endStr = firstNonBlank(text(periodNode, "endTime"), text(periodNode, "end"));
        ExplicitIsoTimeBounds.ParseOutcome iso =
                ExplicitIsoTimeBounds.parseOptionalPair(startStr, endStr, "startTime", "endTime");
        if (!iso.ok) {
            return Outcome.error(iso.errorCode, iso.errorMessage, iso.failedField);
        }
        if (iso.start == null || iso.end == null) {
            return Outcome.error("POP_INVALID_PERIOD",
                    "Each period requires relativeDuration (with optional anchorOffset), calendarPhrase, or startTime/endTime.",
                    null);
        }
        return Outcome.ok(
                Instant.ofEpochMilli(iso.start.getMillis()),
                Instant.ofEpochMilli(iso.end.getMillis()),
                null);
    }

    private static boolean hasExplicitBounds(JsonNode periodNode) {
        return nonBlank(text(periodNode, "startTime")) || nonBlank(text(periodNode, "start"))
                || nonBlank(text(periodNode, "endTime")) || nonBlank(text(periodNode, "end"));
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        return n.asText();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
