package com.thingworx.things.agent.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.types.BaseTypes;

/**
 * Brings the natural-time field set ({@code calendarPhrase} /
 * {@code relativeDuration}) to **custom** {@code _tool_*} services that expose a recognized DATETIME
 * range pair, so App-defined user tools speak the same time language as curated built-ins
 * ({@code query_alert_history}, {@code query_numeric_property_history}, {@code query_stream_data},
 * {@code query_value_stream_property_history}).
 *
 * <p><b>Recognized pairs (case-insensitive on parameter name; both must be {@link BaseTypes#DATETIME}):</b></p>
 * <ul>
 *   <li>{@code startDate} + {@code endDate}</li>
 *   <li>{@code startTime} + {@code endTime}</li>
 * </ul>
 *
 * <p><b>Conservative v1 contract:</b></p>
 * <ul>
 *   <li>Pair detection requires <b>both</b> sides present and <b>both</b> typed {@code DATETIME}. A single
 *       lone {@code startDate}/{@code startTime} parameter is <b>not</b> activated — those still get the
 *       {@link InvokeServiceDatetimeLiteralDefense} pre-parse defense via
 *       {@link CustomToolHarvester#jsonNodeToPrimitive}.</li>
 *   <li>If the service itself already declares a parameter named {@code calendarPhrase} or
 *       {@code relativeDuration}, augmentation is <b>skipped</b> (the service's own use wins; we never
 *       silently shadow an existing parameter). The pair still gets DATETIME defense.</li>
 *   <li>Synthetic fields are <b>stripped</b> from the JSON arguments before
 *       {@link CustomToolHarvester#buildValueCollectionForCustomTool} converts the rest — they never
 *       reach the platform service as unknown parameters.</li>
 *   <li>Both synthetic fields are <b>mutually exclusive</b> with each other and with the explicit
 *       {@code startDate}/{@code endDate} (or {@code startTime}/{@code endTime}) pair, exactly mirroring
 *       {@link BuiltInToolNaturalTimeWindow}'s {@code TIME_PHRASE_VS_EXPLICIT_BOUND_CONFLICT} /
 *       {@code TIME_PHRASE_COMBINED_INVALID} contract.</li>
 *   <li>Wire error envelope is identical to curated built-ins: structured
 *       {@code BuiltInToolTimeErrorJson} payload with {@code rejectedParameter} where applicable
 *       (typed exception caught by
 *       {@link com.thingworx.things.agent.AgentThing#executeCustomTool}).</li>
 * </ul>
 */
public final class CustomToolDateTimePairResolver {

    /**
     * B17 shared natural-time prose (~290 UTF-16 chars with typical pair names). Emitted once on the
     * harvested tool description via {@link CustomToolHarvester}; field schemas use short pointers only.
     */
    public static final String SHARED_NATURAL_TIME_TOOL_SUFFIX_TEMPLATE =
            " Natural-time fields **calendarPhrase** (today/yesterday/tomorrow) and "
                    + "**relativeDuration** (e.g. 30m, 24h) are available as alternatives to %s/%s; "
                    + "set at most one and do not combine with the explicit pair (same contract as "
                    + "query_alert_history / query_numeric_property_history — "
                    + "docs/agent/time-interpretation.md).";

    /** Canonical name of the synthetic natural-time fields injected on top of a recognized DATETIME pair. */
    public static final String CALENDAR_PHRASE = "calendarPhrase";
    public static final String RELATIVE_DURATION = "relativeDuration";

    private CustomToolDateTimePairResolver() {}

    /** Result of {@link #detect(ServiceDefinition)} — names preserve the service-declared casing. */
    public static final class PairDetection {
        public final boolean active;
        /** Service-declared name of the start bound (e.g. {@code startDate}); {@code null} when {@code !active}. */
        public final String startParam;
        /** Service-declared name of the end bound (e.g. {@code endDate}); {@code null} when {@code !active}. */
        public final String endParam;
        /**
         * {@code true} iff the service itself declares a parameter named {@code calendarPhrase} or
         * {@code relativeDuration} — augmentation must be skipped to avoid silently shadowing the
         * service's own input.
         */
        public final boolean syntheticFieldNameCollision;

        private PairDetection(boolean active, String startParam, String endParam, boolean collision) {
            this.active = active;
            this.startParam = startParam;
            this.endParam = endParam;
            this.syntheticFieldNameCollision = collision;
        }

        static PairDetection none() {
            return new PairDetection(false, null, null, false);
        }

        static PairDetection of(String start, String end, boolean collision) {
            return new PairDetection(true, start, end, collision);
        }

        /** Should the LLM-facing schema be augmented with synthetic {@code calendarPhrase} / {@code relativeDuration}? */
        public boolean shouldAugmentSchema() {
            return active && !syntheticFieldNameCollision;
        }
    }

    /**
     * Inspect the service's parameter definitions for a recognized DATETIME pair.
     * Casing follows the service-declared parameter names (preserved verbatim — Parler does not rename).
     */
    public static PairDetection detect(ServiceDefinition sd) {
        if (sd == null || sd.getParameters() == null || sd.getParameters().values() == null) {
            return PairDetection.none();
        }
        String startDate = null;
        String endDate = null;
        String startTime = null;
        String endTime = null;
        boolean collision = false;
        for (FieldDefinition fd : sd.getParameters().values()) {
            String name = fd.getName();
            if (name == null) {
                continue;
            }
            if (CALENDAR_PHRASE.equalsIgnoreCase(name) || RELATIVE_DURATION.equalsIgnoreCase(name)) {
                collision = true;
            }
            if (fd.getBaseType() != BaseTypes.DATETIME) {
                continue;
            }
            if ("startDate".equalsIgnoreCase(name) && startDate == null) {
                startDate = name;
            } else if ("endDate".equalsIgnoreCase(name) && endDate == null) {
                endDate = name;
            } else if ("startTime".equalsIgnoreCase(name) && startTime == null) {
                startTime = name;
            } else if ("endTime".equalsIgnoreCase(name) && endTime == null) {
                endTime = name;
            }
        }
        // Prefer startDate/endDate when both pairs declared — by spec ordering, not user config.
        if (startDate != null && endDate != null) {
            return PairDetection.of(startDate, endDate, collision);
        }
        if (startTime != null && endTime != null) {
            return PairDetection.of(startTime, endTime, collision);
        }
        return PairDetection.none();
    }

    /**
     * Insert synthetic {@code calendarPhrase} + {@code relativeDuration} entries into the LLM-facing
     * properties map (used by {@link CustomToolHarvester#buildParametersSchema}).
     * No-op when the pair is not active or a name collision exists.
     */
    public static void augmentSchema(Map<String, Object> properties, PairDetection pair) {
        if (properties == null || pair == null || !pair.shouldAugmentSchema()) {
            return;
        }
        // B17: keep field descriptions short; full mutual-exclusion / grammar prose lives once on the
        // tool description ({@link #sharedNaturalTimeToolSuffix}).
        if (!properties.containsKey(CALENDAR_PHRASE)) {
            Map<String, Object> cp = new LinkedHashMap<>();
            cp.put("type", "string");
            cp.put("description",
                    "Optional day token (today/yesterday/tomorrow). See tool description for natural-time contract.");
            properties.put(CALENDAR_PHRASE, cp);
        }
        if (!properties.containsKey(RELATIVE_DURATION)) {
            Map<String, Object> rd = new LinkedHashMap<>();
            rd.put("type", "string");
            rd.put("description",
                    "Optional duration ending now (e.g. 30m, 24h). See tool description for natural-time contract.");
            properties.put(RELATIVE_DURATION, rd);
        }
    }

    /** Format {@link #SHARED_NATURAL_TIME_TOOL_SUFFIX_TEMPLATE} for a detected pair. */
    public static String sharedNaturalTimeToolSuffix(PairDetection pair) {
        if (pair == null || !pair.shouldAugmentSchema()) {
            return "";
        }
        return String.format(SHARED_NATURAL_TIME_TOOL_SUFFIX_TEMPLATE, pair.startParam, pair.endParam);
    }

    /**
     * When the natural-time fields were appended to the LLM-facing
     * schema, the canonical pair fields must NOT remain in {@code required} — otherwise the
     * function-calling schema says the LLM must always supply both bounds, which makes
     * {@code {"calendarPhrase":"today"}} alone an invalid call shape and contradicts the description
     * that advertises the pair as alternatives. The pair fields are still present in {@code properties};
     * the LLM may supply them or not, and {@link #resolveInPlace} enforces the mutual-exclusion contract
     * at runtime via this class plus {@link ExplicitIsoTimeBounds#parseOptionalPair}.
     *
     * <p>No-op when augmentation did not happen ({@code !pair.shouldAugmentSchema()}). Idempotent.</p>
     *
     * <p>Extracted from {@link CustomToolHarvester#buildParametersSchema} as a pure helper so it can be
     * exercised offline without booting {@code LogUtilities}.</p>
     */
    public static void unhookPairFromRequired(List<String> required, PairDetection pair) {
        if (required == null || pair == null || !pair.shouldAugmentSchema()) {
            return;
        }
        required.remove(pair.startParam);
        required.remove(pair.endParam);
    }

    /**
     * If the LLM supplied {@code calendarPhrase} or {@code relativeDuration} on a service whose pair was
     * detected, resolve them and rewrite the canonical pair fields in {@code root} to ISO-8601 UTC instants.
     * Strips synthetic fields from {@code root} before the caller proceeds with normal DATETIME coercion.
     *
     * <p>If neither synthetic field is supplied but the pair is recognized, the explicit pair is still
     * validated as a closed-open range via {@link ExplicitIsoTimeBounds#parseOptionalPair} so a partial
     * pair (e.g. only {@code startDate} supplied) returns the structured {@code INVALID_TIME_RANGE}
     * envelope with the service-declared alias label as {@code rejectedParameter}, instead of falling
     * through to a platform-specific service error. Each side still
     * receives the {@link InvokeServiceDatetimeLiteralDefense} pre-parse defense via
     * {@link CustomToolHarvester#jsonNodeToPrimitive}; this added validation only enforces the
     * "both-or-neither" pair contract.</p>
     *
     * <p>When synthetic keys exist but are blank or null after trim
     * (e.g. {@code {"calendarPhrase":"","startDate":"…"}}), {@link BuiltInToolNaturalTimeWindow#resolveNumericPropertyHistory}
     * returns {@code skip}. That path must strip synthetic keys and still invoke {@link #validateExplicitPairOrThrow}
     * — otherwise a lone explicit bound reached the platform without structured {@code INVALID_TIME_RANGE}.</p>
     *
     * @param root  mutable {@link ObjectNode} of the LLM tool arguments
     * @param sd    the resolved {@link ServiceDefinition}
     * @param nowUtc current instant for relative-duration resolution
     * @throws CustomToolNaturalTimeException for any natural-time conflict / shape error — the typed
     *         exception preserves {@code code} / {@code message} / {@code rejectedParameter} for
     *         {@link BuiltInToolTimeErrorJson} routing in {@code AgentThing.executeCustomTool}
     */
    public static void resolveInPlace(ObjectNode root, ServiceDefinition sd, Instant nowUtc) {
        if (root == null || sd == null) {
            return;
        }
        PairDetection pair = detect(sd);
        if (!pair.active || pair.syntheticFieldNameCollision) {
            return;
        }
        if (!root.has(CALENDAR_PHRASE) && !root.has(RELATIVE_DURATION)) {
            // Pair-level explicit-bound validation. Because the pair fields are dropped from
            // `required` when augmented, "neither synthetic AND only one explicit bound supplied"
            // becomes a reachable LLM-fixable state. Surface it as the same structured INVALID_TIME_RANGE
            // envelope curated tools emit — with the service-declared alias preserved as the failed
            // field. Pure-textual case only: numeric / array / object slot shapes are caught later by
            // jsonNodeToPrimitive and surface as the existing IllegalArgumentException coercion path.
            validateExplicitPairOrThrow(root, pair);
            return;
        }
        // Reuse the same shape + conflict + resolver path as curated built-ins so wire codes stay identical.
        // resolveNumericPropertyHistory is the right shared entry point: same Outcome shape, same conflict
        // codes, same rejectedParameter convention.
        String startRaw = textOrNull(root, pair.startParam);
        String endRaw = textOrNull(root, pair.endParam);
        BuiltInToolNaturalTimeWindow.Outcome outcome =
                BuiltInToolNaturalTimeWindow.resolveNumericPropertyHistory(root, nowUtc, startRaw, endRaw);
        if (outcome.errorCode != null) {
            // Strip synthetic fields even on error so the caller does not retry coercion against them.
            root.remove(CALENDAR_PHRASE);
            root.remove(RELATIVE_DURATION);
            throw new CustomToolNaturalTimeException(outcome.errorCode, outcome.errorMessage,
                    outcome.rejectedParameter);
        }
        if (outcome.skip) {
            // BuiltInToolNaturalTimeWindow.resolveNumericPropertyHistory returns skip when both synthetic
            // fields are absent or blank after trim — including {"calendarPhrase":"","startDate":"…"} or
            // {"calendarPhrase":null,...}. Strip synthetic keys so downstream coercion does not see them,
            // then enforce the explicit pair contract: without this, blank/null
            // synthetics bypassed validateExplicitPairOrThrow and a lone explicit bound reached the platform.
            root.remove(CALENDAR_PHRASE);
            root.remove(RELATIVE_DURATION);
            validateExplicitPairOrThrow(root, pair);
            return;
        }
        DateTime start = utcDateTime(outcome.resolution.getStartUtc());
        DateTime end = utcDateTime(outcome.resolution.getEndUtc());
        // Write resolved bounds onto the canonical service parameter names. ISO-8601 UTC; the downstream
        // jsonNodeToPrimitive(..., DATETIME) parses these via DateTime.parse.
        root.put(pair.startParam, start.toString());
        root.put(pair.endParam, end.toString());
        // Synthetic fields never reach the platform service — they are not service parameters.
        root.remove(CALENDAR_PHRASE);
        root.remove(RELATIVE_DURATION);
    }

    /**
     * When no synthetic field is present, enforce the recognized pair's
     * "both-or-neither" contract via {@link ExplicitIsoTimeBounds#parseOptionalPair} so the LLM gets
     * the same structured {@code INVALID_TIME_RANGE} envelope as curated built-ins, with the
     * service-declared alias label preserved as {@link CustomToolNaturalTimeException#getRejectedParameter()}.
     * Pure-textual case only — non-textual shapes (object / array / number) flow to
     * {@link CustomToolHarvester#jsonNodeToPrimitive} which already emits coercion errors.
     */
    private static void validateExplicitPairOrThrow(ObjectNode root, PairDetection pair) {
        String startRaw = textOrNull(root, pair.startParam);
        String endRaw = textOrNull(root, pair.endParam);
        boolean startTextual = root.has(pair.startParam) && root.get(pair.startParam).isTextual();
        boolean endTextual = root.has(pair.endParam) && root.get(pair.endParam).isTextual();
        boolean startBlank = startTextual && (startRaw == null || startRaw.isBlank());
        boolean endBlank = endTextual && (endRaw == null || endRaw.isBlank());
        // Treat a blank textual slot as absent: the platform would also reject it, and the LLM repair
        // path is "supply the missing bound" — same as omitting it. This avoids a confusing mid-layer
        // INVALID_TIME_RANGE for `{"startDate":""}` when the user simply meant "do not constrain".
        String effectiveStart = (startTextual && !startBlank) ? startRaw : null;
        String effectiveEnd = (endTextual && !endBlank) ? endRaw : null;
        if (effectiveStart == null && effectiveEnd == null) {
            return;
        }
        ExplicitIsoTimeBounds.ParseOutcome po = ExplicitIsoTimeBounds.parseOptionalPair(
                effectiveStart, effectiveEnd, pair.startParam, pair.endParam);
        if (!po.ok) {
            throw new CustomToolNaturalTimeException(po.errorCode, po.errorMessage, po.failedField);
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        if (root == null || field == null) {
            return null;
        }
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        String s = n.asText();
        return s == null ? null : s.trim();
    }

    private static DateTime utcDateTime(Instant instant) {
        return new DateTime(instant.toEpochMilli(), DateTimeZone.UTC);
    }
}
