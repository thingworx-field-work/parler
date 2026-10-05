package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.types.BaseTypes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CustomToolDateTimePairResolver}
 * pair detection, schema augmentation, and in-place value resolution. Cross-tool envelope shape and
 * rejected-parameter conventions match {@link BuiltInToolNaturalTimeWindow} (verified via
 * {@link CustomToolNaturalTimeException}). Also covers the blank/null synthetic-field bypass.
 *
 * <p>Tests are pure JUnit (no platform / LogUtilities dependency); ThingWorx {@link ServiceDefinition} and
 * {@link FieldDefinition} are pure data carriers and offline-constructible.</p>
 */
class CustomToolDateTimePairResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-05-06T18:00:00Z");

    @AfterEach
    void clearTz() {
        AgentToolContext.setUserIanaTimezone(null);
    }

    // ── pair detection ────────────────────────────────────────────────────

    @Test
    void detect_startDate_endDate_pair() {
        ServiceDefinition sd = svc("_tool_exportHistory",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME),
                param("payload", BaseTypes.STRING));
        CustomToolDateTimePairResolver.PairDetection pair = CustomToolDateTimePairResolver.detect(sd);
        assertTrue(pair.active);
        assertTrue(pair.shouldAugmentSchema());
        assertEquals("startDate", pair.startParam);
        assertEquals("endDate", pair.endParam);
        assertFalse(pair.syntheticFieldNameCollision);
    }

    @Test
    void detect_startTime_endTime_pair() {
        ServiceDefinition sd = svc("_tool_getWindow",
                param("startTime", BaseTypes.DATETIME),
                param("endTime", BaseTypes.DATETIME));
        CustomToolDateTimePairResolver.PairDetection pair = CustomToolDateTimePairResolver.detect(sd);
        assertTrue(pair.active);
        assertEquals("startTime", pair.startParam);
        assertEquals("endTime", pair.endParam);
    }

    @Test
    void detect_prefersStartDate_endDate_whenBothPairsDeclared() {
        // If a service declares both pairs (rare but legal), resolver standardizes on startDate/endDate.
        ServiceDefinition sd = svc("_tool_dual",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME),
                param("startTime", BaseTypes.DATETIME),
                param("endTime", BaseTypes.DATETIME));
        CustomToolDateTimePairResolver.PairDetection pair = CustomToolDateTimePairResolver.detect(sd);
        assertEquals("startDate", pair.startParam);
        assertEquals("endDate", pair.endParam);
    }

    @Test
    void detect_singleDatetime_doesNotActivate() {
        // Lone DATETIME parameter — defense-only path; no pair augmentation.
        ServiceDefinition sd = svc("_tool_at", param("at", BaseTypes.DATETIME));
        assertFalse(CustomToolDateTimePairResolver.detect(sd).active);
    }

    @Test
    void detect_pairWrongType_doesNotActivate() {
        // STRING-typed startDate/endDate must not be treated as a DATETIME pair (we never coerce STRING).
        ServiceDefinition sd = svc("_tool_strings",
                param("startDate", BaseTypes.STRING),
                param("endDate", BaseTypes.STRING));
        assertFalse(CustomToolDateTimePairResolver.detect(sd).active);
    }

    @Test
    void detect_serviceDeclaresCalendarPhrase_marksCollision() {
        // Service has its own calendarPhrase parameter — never silently shadow it.
        ServiceDefinition sd = svc("_tool_collide",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME),
                param("calendarPhrase", BaseTypes.STRING));
        CustomToolDateTimePairResolver.PairDetection pair = CustomToolDateTimePairResolver.detect(sd);
        assertTrue(pair.active);
        assertTrue(pair.syntheticFieldNameCollision);
        assertFalse(pair.shouldAugmentSchema());
    }

    @Test
    void detect_serviceDeclaresRelativeDuration_marksCollision() {
        ServiceDefinition sd = svc("_tool_collide2",
                param("startTime", BaseTypes.DATETIME),
                param("endTime", BaseTypes.DATETIME),
                param("relativeDuration", BaseTypes.STRING));
        CustomToolDateTimePairResolver.PairDetection pair = CustomToolDateTimePairResolver.detect(sd);
        assertTrue(pair.syntheticFieldNameCollision);
    }

    @Test
    void detect_emptyOrNullService_returnsNone() {
        assertFalse(CustomToolDateTimePairResolver.detect(null).active);
        assertFalse(CustomToolDateTimePairResolver.detect(new ServiceDefinition("_tool_x", "")).active);
    }

    // ── schema augmentation ───────────────────────────────────────────────

    @Test
    void augmentSchema_addsBothSyntheticFields() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("startDate", Map.of("type", "string"));
        properties.put("endDate", Map.of("type", "string"));
        CustomToolDateTimePairResolver.augmentSchema(properties,
                CustomToolDateTimePairResolver.PairDetection.of("startDate", "endDate", false));
        assertTrue(properties.containsKey("calendarPhrase"));
        assertTrue(properties.containsKey("relativeDuration"));
        // Both synthetic fields are typed as JSON string for the LLM.
        @SuppressWarnings("unchecked")
        Map<String, Object> cp = (Map<String, Object>) properties.get("calendarPhrase");
        assertEquals("string", cp.get("type"));
        assertNotNull(cp.get("description"));
    }

    @Test
    void augmentSchema_skipsWhenInactive() {
        Map<String, Object> properties = new LinkedHashMap<>();
        CustomToolDateTimePairResolver.augmentSchema(properties, CustomToolDateTimePairResolver.PairDetection.none());
        assertFalse(properties.containsKey("calendarPhrase"));
        assertFalse(properties.containsKey("relativeDuration"));
    }

    @Test
    void augmentSchema_skipsWhenServiceCollision() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("startDate", Map.of("type", "string"));
        properties.put("endDate", Map.of("type", "string"));
        CustomToolDateTimePairResolver.augmentSchema(properties,
                CustomToolDateTimePairResolver.PairDetection.of("startDate", "endDate", true));
        assertFalse(properties.containsKey("calendarPhrase"),
                "must not silently shadow service-declared calendarPhrase");
        assertFalse(properties.containsKey("relativeDuration"));
    }

    @Test
    void augmentSchema_doesNotOverwriteExistingSyntheticEntry() {
        // Idempotency: if augmentSchema is called twice, the existing entry survives.
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> existing = Map.of("type", "string", "description", "preserved");
        properties.put("calendarPhrase", existing);
        CustomToolDateTimePairResolver.augmentSchema(properties,
                CustomToolDateTimePairResolver.PairDetection.of("startDate", "endDate", false));
        assertSame(existing, properties.get("calendarPhrase"));
    }

    // ── resolveInPlace: success ────────────────────────────────────────────

    @Test
    void resolve_calendarPhrase_today_populatesPairAndStripsSynthetic() throws Exception {
        AgentToolContext.setUserIanaTimezone("America/New_York");
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"calendarPhrase\":\"today\"}");
        CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW);
        // Synthetic field stripped — must not reach the platform service as an unknown parameter.
        assertFalse(root.has("calendarPhrase"));
        assertFalse(root.has("relativeDuration"));
        // Canonical pair populated with ISO-8601 (DateTime.toString() shape — parseable by Joda).
        assertTrue(root.has("startDate"));
        assertTrue(root.has("endDate"));
        assertTrue(root.get("startDate").isTextual());
        assertTrue(root.get("endDate").isTextual());
    }

    @Test
    void resolve_relativeDuration_30m_populatesEndAtNow() throws Exception {
        ServiceDefinition sd = svc("_tool_window",
                param("startTime", BaseTypes.DATETIME),
                param("endTime", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"relativeDuration\":\"30m\"}");
        CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW);
        assertFalse(root.has("relativeDuration"));
        assertEquals(NOW.toString().substring(0, 19),
                root.get("endTime").asText().substring(0, 19),
                "endTime should match nowUtc up to seconds");
    }

    @Test
    void resolve_neitherSynthetic_noop() throws Exception {
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"startDate\":\"2026-05-01T00:00:00Z\",\"endDate\":\"2026-05-02T00:00:00Z\"}");
        CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW);
        // Untouched — synthetic fields absent, explicit pair preserved verbatim.
        assertEquals("2026-05-01T00:00:00Z", root.get("startDate").asText());
        assertEquals("2026-05-02T00:00:00Z", root.get("endDate").asText());
    }

    @Test
    void resolve_inactivePair_noop() throws Exception {
        ServiceDefinition sd = svc("_tool_at", param("at", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"calendarPhrase\":\"today\"}");
        CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW);
        // No DATETIME pair — synthetic field passes through (the LLM would also have no schema affordance).
        assertTrue(root.has("calendarPhrase"));
    }

    @Test
    void resolve_collision_noop() throws Exception {
        // When the service itself declares calendarPhrase, the resolver skips augmentation AND skips
        // resolution — the LLM-supplied calendarPhrase value flows to the service unmodified.
        ServiceDefinition sd = svc("_tool_collide",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME),
                param("calendarPhrase", BaseTypes.STRING));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"calendarPhrase\":\"today\"}");
        CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW);
        // calendarPhrase preserved; service decides what it means.
        assertEquals("today", root.get("calendarPhrase").asText());
        assertFalse(root.has("startDate"));
    }

    // ── resolveInPlace: errors ─────────────────────────────────────────────

    @Test
    void resolve_calendarPhrase_andRelativeDuration_combinedInvalid() throws Exception {
        AgentToolContext.setUserIanaTimezone("America/New_York");
        ServiceDefinition sd = svc("_tool_x",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"calendarPhrase\":\"today\",\"relativeDuration\":\"30m\"}");
        CustomToolNaturalTimeException e = assertThrows(CustomToolNaturalTimeException.class,
                () -> CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW));
        assertEquals("TIME_PHRASE_COMBINED_INVALID", e.getCode());
        // Cross-cutting conflict: no single offending field.
        assertNull(e.getRejectedParameter());
        // Synthetic fields stripped even on error so retry coercion does not see them.
        assertFalse(root.has("calendarPhrase"));
        assertFalse(root.has("relativeDuration"));
    }

    @Test
    void resolve_calendarPhrase_vs_explicitBound_conflict() throws Exception {
        AgentToolContext.setUserIanaTimezone("America/New_York");
        ServiceDefinition sd = svc("_tool_x",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"calendarPhrase\":\"today\",\"startDate\":\"2026-05-01T00:00:00Z\"}");
        CustomToolNaturalTimeException e = assertThrows(CustomToolNaturalTimeException.class,
                () -> CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW));
        assertEquals("TIME_PHRASE_VS_EXPLICIT_BOUND_CONFLICT", e.getCode());
        assertNull(e.getRejectedParameter());
    }

    @Test
    void resolve_calendarPhrase_missingTimezone_carriesRejectedParameter() throws Exception {
        // Ensure no host timezone is present — this exercises the MISSING_OR_INVALID_USER_TIMEZONE path.
        AgentToolContext.setUserIanaTimezone(null);
        ServiceDefinition sd = svc("_tool_x",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"calendarPhrase\":\"today\"}");
        CustomToolNaturalTimeException e = assertThrows(CustomToolNaturalTimeException.class,
                () -> CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW));
        assertEquals("MISSING_OR_INVALID_USER_TIMEZONE", e.getCode());
        // Single-field error: LLM can pivot off calendarPhrase.
        assertEquals("calendarPhrase", e.getRejectedParameter());
    }

    @Test
    void resolve_calendarPhrase_nonTextual_invalidShape() throws Exception {
        AgentToolContext.setUserIanaTimezone("America/New_York");
        ServiceDefinition sd = svc("_tool_x",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"calendarPhrase\":[\"today\"]}");
        CustomToolNaturalTimeException e = assertThrows(CustomToolNaturalTimeException.class,
                () -> CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW));
        assertEquals("INVALID_TIME_SPEC_SHAPE", e.getCode());
        assertEquals("calendarPhrase", e.getRejectedParameter());
    }

    @Test
    void resolve_relativeDuration_grammarError_carriesRejectedParameter() throws Exception {
        ServiceDefinition sd = svc("_tool_x",
                param("startTime", BaseTypes.DATETIME),
                param("endTime", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"relativeDuration\":\"30mo\"}");
        CustomToolNaturalTimeException e = assertThrows(CustomToolNaturalTimeException.class,
                () -> CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW));
        // Grammar-error code comes from ParlerTimeResolver (UNSUPPORTED_UNIT), but the rejectedParameter
        // is always relativeDuration so the LLM knows which field to fix.
        assertEquals("relativeDuration", e.getRejectedParameter());
    }

    // ── pair-level explicit-bound validation when no synthetic ──

    @Test
    void resolve_explicitPair_bothPresent_passesThrough_startDateAlias() throws Exception {
        // Both bounds supplied as parseable ISO-8601 — resolver no-ops; downstream coercion takes over.
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"startDate\":\"2026-05-01T00:00:00Z\",\"endDate\":\"2026-05-02T00:00:00Z\"}");
        CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW);
        // Untouched.
        assertEquals("2026-05-01T00:00:00Z", root.get("startDate").asText());
        assertEquals("2026-05-02T00:00:00Z", root.get("endDate").asText());
    }

    @Test
    void resolve_explicitPair_onlyStartDate_supplied_throwsInvalidTimeRange_withAlias() throws Exception {
        // Pair fields are not required, so the LLM may legally supply
        // only one bound. The resolver must surface the structured INVALID_TIME_RANGE envelope with the
        // service-declared alias as rejectedParameter — NOT a platform service error.
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"startDate\":\"2026-05-01T00:00:00Z\"}");
        CustomToolNaturalTimeException e = assertThrows(CustomToolNaturalTimeException.class,
                () -> CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW));
        assertEquals("INVALID_TIME_RANGE", e.getCode());
        // "both-or-neither" is cross-cutting; the message names both labels with the service-declared
        // alias preserved (`startDate` / `endDate`, not the curated-tool default `startTime`/`endTime`).
        assertNull(e.getRejectedParameter());
        assertTrue(e.getMessage().contains("startDate"));
        assertTrue(e.getMessage().contains("endDate"));
    }

    @Test
    void resolve_explicitPair_onlyEndTime_supplied_throwsInvalidTimeRange_withStartTimeAlias() throws Exception {
        // Same as above but with the startTime/endTime alias and only endTime supplied.
        ServiceDefinition sd = svc("_tool_window",
                param("startTime", BaseTypes.DATETIME),
                param("endTime", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"endTime\":\"2026-05-02T00:00:00Z\"}");
        CustomToolNaturalTimeException e = assertThrows(CustomToolNaturalTimeException.class,
                () -> CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW));
        assertEquals("INVALID_TIME_RANGE", e.getCode());
        assertNull(e.getRejectedParameter());
        assertTrue(e.getMessage().contains("startTime"));
        assertTrue(e.getMessage().contains("endTime"));
    }

    @Test
    void resolve_explicitPair_onlyStartDate_unparseable_throwsInvalidTimeRange_withFieldAlias() throws Exception {
        // Garbage bound: rejectedParameter must be the offending field (alias preserved).
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"startDate\":\"not-a-date\"}");
        CustomToolNaturalTimeException e = assertThrows(CustomToolNaturalTimeException.class,
                () -> CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW));
        assertEquals("INVALID_TIME_RANGE", e.getCode());
        // Single-field error: failedField propagated through to the wire envelope.
        assertEquals("startDate", e.getRejectedParameter());
    }

    @Test
    void resolve_explicitPair_neitherSupplied_noop() throws Exception {
        // The pair is recognized but neither bound supplied: no service contract to validate. (The
        // platform service decides whether absent bounds mean "default window" or "must supply".)
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME),
                param("payload", BaseTypes.STRING));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"payload\":\"x\"}");
        CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW);
        assertFalse(root.has("startDate"));
        assertFalse(root.has("endDate"));
        assertEquals("x", root.get("payload").asText());
    }

    @Test
    void resolve_explicitPair_blankStartDate_treatedAsAbsent() throws Exception {
        // Blank textual slot is treated as absent — same LLM repair path as omitting it. Endpoint must
        // therefore reach the "both absent" no-op (not a confusing mid-layer INVALID_TIME_RANGE).
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"startDate\":\"\",\"endDate\":\"\"}");
        CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW);
        // Resolver does not mutate blanks; downstream DATETIME coercion will reject blanks as bad input
        // (which is the platform's contract, not the time-interpretation layer's).
        assertEquals("", root.get("startDate").asText());
        assertEquals("", root.get("endDate").asText());
    }

    // ── blank/null synthetic + partial explicit — skip-path validation ──

    @Test
    void resolve_skip_blankCalendarPhrase_withOnlyStartDate_throwsInvalidTimeRange() throws Exception {
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"calendarPhrase\":\"\",\"startDate\":\"2026-05-01T00:00:00Z\"}");
        CustomToolNaturalTimeException e = assertThrows(CustomToolNaturalTimeException.class,
                () -> CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW));
        assertEquals("INVALID_TIME_RANGE", e.getCode());
        assertNull(e.getRejectedParameter());
        assertFalse(root.has("calendarPhrase"));
    }

    @Test
    void resolve_skip_nullCalendarPhrase_withOnlyStartDate_throwsInvalidTimeRange() throws Exception {
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"calendarPhrase\":null,\"startDate\":\"2026-05-01T00:00:00Z\"}");
        CustomToolNaturalTimeException e = assertThrows(CustomToolNaturalTimeException.class,
                () -> CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW));
        assertEquals("INVALID_TIME_RANGE", e.getCode());
        assertNull(e.getRejectedParameter());
        assertFalse(root.has("calendarPhrase"));
    }

    @Test
    void resolve_skip_blankRelativeDuration_withOnlyEndDate_throwsInvalidTimeRange() throws Exception {
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"relativeDuration\":\"\",\"endDate\":\"2026-05-02T00:00:00Z\"}");
        CustomToolNaturalTimeException e = assertThrows(CustomToolNaturalTimeException.class,
                () -> CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW));
        assertEquals("INVALID_TIME_RANGE", e.getCode());
        assertFalse(root.has("relativeDuration"));
    }

    @Test
    void resolve_skip_blankCalendarPhrase_withBothExplicitBounds_stripsSynthetic_noThrow() throws Exception {
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"calendarPhrase\":\"\",\"startDate\":\"2026-05-01T00:00:00Z\","
                        + "\"endDate\":\"2026-05-02T00:00:00Z\"}");
        CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW);
        assertFalse(root.has("calendarPhrase"));
        assertEquals("2026-05-01T00:00:00Z", root.get("startDate").asText());
        assertEquals("2026-05-02T00:00:00Z", root.get("endDate").asText());
    }

    // ── `required` field unhook when augmenting ────────────────

    @Test
    void unhookPairFromRequired_active_removesBothPairFields() {
        CustomToolDateTimePairResolver.PairDetection pair =
                CustomToolDateTimePairResolver.detect(svc("_tool_export",
                        param("startDate", BaseTypes.DATETIME),
                        param("endDate", BaseTypes.DATETIME),
                        param("payload", BaseTypes.STRING)));
        List<String> required = new ArrayList<>(Arrays.asList("startDate", "endDate", "payload"));
        CustomToolDateTimePairResolver.unhookPairFromRequired(required, pair);
        // Pair fields removed; non-pair required field preserved.
        assertEquals(List.of("payload"), required);
    }

    @Test
    void unhookPairFromRequired_active_startTimeAlias_removesAliasNamesNotDefaults() {
        CustomToolDateTimePairResolver.PairDetection pair =
                CustomToolDateTimePairResolver.detect(svc("_tool_window",
                        param("startTime", BaseTypes.DATETIME),
                        param("endTime", BaseTypes.DATETIME)));
        List<String> required = new ArrayList<>(Arrays.asList("startTime", "endTime"));
        CustomToolDateTimePairResolver.unhookPairFromRequired(required, pair);
        assertTrue(required.isEmpty());
    }

    @Test
    void unhookPairFromRequired_inactive_noop() {
        // No pair detected → required list untouched.
        CustomToolDateTimePairResolver.PairDetection pair =
                CustomToolDateTimePairResolver.detect(svc("_tool_x",
                        param("at", BaseTypes.DATETIME),
                        param("payload", BaseTypes.STRING)));
        List<String> required = new ArrayList<>(Arrays.asList("at", "payload"));
        CustomToolDateTimePairResolver.unhookPairFromRequired(required, pair);
        assertEquals(List.of("at", "payload"), required);
    }

    @Test
    void unhookPairFromRequired_collisionService_noop() {
        // Service declares its own calendarPhrase param → augmentation skipped → required untouched.
        CustomToolDateTimePairResolver.PairDetection pair =
                CustomToolDateTimePairResolver.detect(svc("_tool_collide",
                        param("startDate", BaseTypes.DATETIME),
                        param("endDate", BaseTypes.DATETIME),
                        param("calendarPhrase", BaseTypes.STRING)));
        List<String> required = new ArrayList<>(Arrays.asList("startDate", "endDate", "calendarPhrase"));
        CustomToolDateTimePairResolver.unhookPairFromRequired(required, pair);
        // Collision → no augmentation → no unhook. Service's contract is preserved verbatim.
        assertEquals(List.of("startDate", "endDate", "calendarPhrase"), required);
    }

    @Test
    void unhookPairFromRequired_idempotent() {
        // Calling twice does not corrupt the list.
        CustomToolDateTimePairResolver.PairDetection pair =
                CustomToolDateTimePairResolver.detect(svc("_tool_export",
                        param("startDate", BaseTypes.DATETIME),
                        param("endDate", BaseTypes.DATETIME)));
        List<String> required = new ArrayList<>(Arrays.asList("startDate", "endDate"));
        CustomToolDateTimePairResolver.unhookPairFromRequired(required, pair);
        CustomToolDateTimePairResolver.unhookPairFromRequired(required, pair);
        assertTrue(required.isEmpty());
    }

    @Test
    void unhookPairFromRequired_nullSafety() {
        // Null inputs are no-ops.
        CustomToolDateTimePairResolver.unhookPairFromRequired(null, null);
        CustomToolDateTimePairResolver.unhookPairFromRequired(new ArrayList<>(), null);
        CustomToolDateTimePairResolver.unhookPairFromRequired(null,
                CustomToolDateTimePairResolver.detect(svc("_tool_x",
                        param("startDate", BaseTypes.DATETIME),
                        param("endDate", BaseTypes.DATETIME))));
    }

    @Test
    void resolve_explicitPair_loneNonTextual_noop_letsCoercionReportIt() throws Exception {
        // Scope: pair-level validation is pure-textual only. A non-textual lone bound
        // (e.g. an array passed where DATETIME is expected) flows to jsonNodeToPrimitive which already
        // surfaces the coercion error path — not the time-interpretation envelope.
        ServiceDefinition sd = svc("_tool_export",
                param("startDate", BaseTypes.DATETIME),
                param("endDate", BaseTypes.DATETIME));
        ObjectNode root = (ObjectNode) MAPPER.readTree("{\"startDate\":[\"2026-05-01T00:00:00Z\"]}");
        // No throw — delegates to coercion downstream.
        CustomToolDateTimePairResolver.resolveInPlace(root, sd, NOW);
        assertTrue(root.get("startDate").isArray());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static ServiceDefinition svc(String name, FieldDefinition... params) {
        ServiceDefinition sd = new ServiceDefinition(name, "test service");
        for (FieldDefinition fd : params) {
            sd.getParameters().addFieldDefinition(fd);
        }
        return sd;
    }

    private static FieldDefinition param(String name, BaseTypes baseType) {
        return new FieldDefinition(name, "", baseType);
    }
}
