package com.thingworx.things.agent.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.transform.time.Aggregation;
import com.thingworx.things.agent.transform.time.DemoResampleAppProfile;
import com.thingworx.things.agent.transform.time.DemoRollingAppProfile;
import com.thingworx.things.agent.transform.time.RollingOperator.WindowKind;

/**
 * Shared fail-fast parsing for U4 series tool args at the untrusted tool-arg boundary.
 */
final class U4SeriesToolArgs {

    static final String ARGUMENT_MISSING = "ARGUMENT_MISSING";
    static final String WINDOW_INVALID = "WINDOW_INVALID";
    static final String TIME_AXIS_MISSING = "TIME_AXIS_MISSING";
    static final String AGGREGATION_INVALID = "AGGREGATION_INVALID";
    static final String ROLLING_KIND_INVALID = "ROLLING_KIND_INVALID";

    private U4SeriesToolArgs() {}

    static String text(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) {
            return null;
        }
        String v = args.get(field).asText();
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String requireCacheId(JsonNode args) {
        String cacheId = text(args, "cacheId");
        if (cacheId == null) {
            cacheId = text(args, "sourceCacheId");
        }
        if (cacheId == null) {
            throw new IllegalArgumentException(ARGUMENT_MISSING + ": cacheId required");
        }
        return cacheId;
    }

    static HalfOpenWindow requireWindow(JsonNode args) {
        return requireNamedWindow(args, "windowStart", "windowEnd");
    }

    static HalfOpenWindow requireNamedWindow(JsonNode args, String startField, String endField) {
        String startText = text(args, startField);
        String endText = text(args, endField);
        if (startText == null || endText == null) {
            throw new IllegalArgumentException(
                    ARGUMENT_MISSING + ": " + startField + " and " + endField + " required");
        }
        Instant start;
        Instant end;
        try {
            start = Instant.parse(startText);
            end = Instant.parse(endText);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    WINDOW_INVALID + ": " + startField + "/" + endField + " must be ISO-8601 instants");
        }
        try {
            return HalfOpenWindow.of(start, end);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(WINDOW_INVALID + ": " + e.getMessage());
        }
    }

    static String requireTimeColumn(JsonNode args) {
        String timeColumn = text(args, "timeColumn");
        if (timeColumn == null) {
            throw new IllegalArgumentException(TIME_AXIS_MISSING);
        }
        return timeColumn;
    }

    static Aggregation resolveAggregation(JsonNode args) {
        String raw = text(args, "aggregation");
        if (raw == null) {
            return DemoResampleAppProfile.aggregation();
        }
        try {
            return Aggregation.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(AGGREGATION_INVALID + ": got " + raw);
        }
    }

    static WindowKind resolveRollingKind(JsonNode args) {
        String raw = text(args, "rollingKind");
        if (raw == null) {
            return DemoRollingAppProfile.DEFAULT_KIND;
        }
        try {
            return WindowKind.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(ROLLING_KIND_INVALID + ": got " + raw);
        }
    }

    static int resolveObservationWindow(JsonNode args) {
        if (args == null || !args.has("observationWindow") || args.get("observationWindow").isNull()) {
            return DemoRollingAppProfile.DEFAULT_OBSERVATION_WINDOW;
        }
        if (!args.get("observationWindow").canConvertToInt()) {
            throw new IllegalArgumentException(WINDOW_INVALID + ": observationWindow must be an integer");
        }
        int v = args.get("observationWindow").asInt();
        if (v < 1) {
            throw new IllegalArgumentException(WINDOW_INVALID + ": observationWindow must be >= 1");
        }
        return v;
    }

    static Duration resolveDurationWindow(JsonNode args) {
        if (args == null || !args.has("durationWindowSeconds") || args.get("durationWindowSeconds").isNull()) {
            return DemoRollingAppProfile.DEFAULT_DURATION_WINDOW;
        }
        if (!args.get("durationWindowSeconds").canConvertToLong()) {
            throw new IllegalArgumentException(WINDOW_INVALID + ": durationWindowSeconds must be a number");
        }
        long sec = args.get("durationWindowSeconds").asLong();
        if (sec <= 0L) {
            throw new IllegalArgumentException(WINDOW_INVALID + ": durationWindowSeconds must be > 0");
        }
        return Duration.ofSeconds(sec);
    }

    static int resolveMinSupport(JsonNode args) {
        if (args == null || !args.has("minSupport") || args.get("minSupport").isNull()) {
            return DemoRollingAppProfile.DEFAULT_MIN_SUPPORT;
        }
        if (!args.get("minSupport").canConvertToInt()) {
            throw new IllegalArgumentException(WINDOW_INVALID + ": minSupport must be an integer");
        }
        int v = args.get("minSupport").asInt();
        if (v < 1) {
            throw new IllegalArgumentException(WINDOW_INVALID + ": minSupport must be >= 1");
        }
        return v;
    }

    static String reasonFrom(String message) {
        if (message == null) {
            return ARGUMENT_MISSING;
        }
        if (message.startsWith(AGGREGATION_INVALID)) {
            return AGGREGATION_INVALID;
        }
        if (message.startsWith(ROLLING_KIND_INVALID)) {
            return ROLLING_KIND_INVALID;
        }
        if (message.startsWith(WINDOW_INVALID)) {
            return WINDOW_INVALID;
        }
        if (message.startsWith(TIME_AXIS_MISSING) || TIME_AXIS_MISSING.equals(message)) {
            return TIME_AXIS_MISSING;
        }
        if (message.startsWith(ARGUMENT_MISSING)) {
            return ARGUMENT_MISSING;
        }
        return ARGUMENT_MISSING;
    }
}
