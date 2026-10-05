package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeValidator;
import com.thingworx.things.agent.analysis.ComputingOperationAdmission;
import com.thingworx.things.agent.analysis.DerivedArtifactLineage;
import com.thingworx.things.agent.cache.ArtifactCacheTestFixtures;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.source.ReadLimitFact;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.source.SourceDescriptorSupport;
import com.thingworx.things.agent.transform.time.TimeWeightedCacheRunner;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;

/**
 * CF-01 {@code tabulate_cached_result} {@code mode=time_weighted}: a real numeric property-history cache
 * through the real schema, admission and dispatch to the published segment table, its descriptor, the
 * envelope, and what the model sees after egress compaction.
 */
class TabulateTimeWeightedModeTest {

    TabulateTimeWeightedModeTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HISTORY_ROUTE = "query_numeric_property_history";
    private static final String OVERLAY_ROUTE = "build_history_overlay_chart";
    /** The shared design case: analysis window 06:00-14:00. */
    private static final Instant SHIFT_START = Instant.parse("2026-03-02T06:00:00Z");
    private static final String SHIFT = window(0, 28_800);
    private static final String SHIFT_STEP = ",\"integrationMethod\":\"step_hold\",\"maxGapSeconds\":15600,"
            + "\"timeUnit\":\"hours\"";
    private static final double TOLERANCE = 1e-12;

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("ce3-time-weighted");
        ComputingOperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        ComputingOperationAdmission.resetForTests();
    }

    /** Acceptance 2: the read-limit reason changes one number, the last-value hold, and says so. */
    @Test
    void sharedCase_readLimitCancelsTheTailHold_otherwiseItIsALabelledEstimate() throws Exception {
        for (String route : new String[] {HISTORY_ROUTE, OVERLAY_ROUTE}) {
            JsonNode limited = run(history(route, Limit.READ_LIMIT, everyTenMinutes(0, 23)), SHIFT, SHIFT_STEP);
            JsonNode partial = run(history(route, Limit.PARTIAL, everyTenMinutes(0, 23)), SHIFT, SHIFT_STEP);
            JsonNode plain = run(history(route, Limit.NONE, everyTenMinutes(0, 23)), SHIFT, SHIFT_STEP);

            for (JsonNode out : List.of(limited, partial)) {
                assertEquals("OK", out.path("status").asText(), out.toString());
                JsonNode m = out.path("analysisEnvelope").path("metrics");
                assertEquals(11d / 3d, m.path("observedIntegral").asDouble(), TOLERANCE, route);
                assertEquals("0", m.path("estimatedIntegral").asText());
                assertEquals("13200", m.path("observedSeconds").asText());
                assertEquals("0", m.path("estimatedSeconds").asText());
                assertEquals("15600", m.path("unknownSeconds").asText());
                assertEquals("15600", m.path("cancelledTailHoldSeconds").asText());
                assertEquals(11d / 24d, m.path("coverage").asDouble(), TOLERANCE);
                assertEquals("false", m.path("containsEstimate").asText());
                assertEquals("SUCCESS", out.path("analysisEnvelope").path("status").asText());
                assertTrue(out.path("warnings").get(0).asText().contains("not a window or shift total"));
                assertTrue(containsText(out.path("warnings"), "hold after the last reading was cancelled for 15600 s"),
                        out.path("warnings").toString());
            }
            assertEquals(List.of(ReadLimitFact.REASON), strings(limited.path("completeness").path("reasons")));
            assertEquals("PARTIAL", partial.path("completeness").path("status").asText());

            JsonNode m = plain.path("analysisEnvelope").path("metrics");
            assertEquals(11d / 3d, m.path("observedIntegral").asDouble(), TOLERANCE);
            assertEquals(13d / 3d, m.path("estimatedIntegral").asDouble(), TOLERANCE);
            assertEquals(8d, m.path("integral").asDouble(), 8d * TOLERANCE);
            assertEquals(1d, m.path("timeWeightedMean").asDouble(), TOLERANCE);
            assertEquals("15600", m.path("estimatedSeconds").asText());
            assertEquals("0", m.path("unknownSeconds").asText());
            assertEquals("value × hours", m.path("integralUnit").asText());
            assertEquals("observed_span", m.path("scope").asText());
            assertEquals("UNKNOWN", plain.path("completeness").path("status").asText(), "never upgraded");
            assertTrue(containsText(plain.path("warnings"), "Contains an estimate: 15600 s"),
                    plain.path("warnings").toString());
            assertFalse(plain.path("warnings").get(0).asText().contains("not a window or shift total"));

            SourceDescriptor d = InvokeServiceExecutor.lookupSourceDescriptor(limited.path("findingCacheId").asText());
            assertEquals(TimeWeightedCacheRunner.ROUTE_ID, d.sourceRouteId());
            assertEquals(List.of(ReadLimitFact.REASON), d.completenessReasons(), "the parent's reason is copied");
            assertNull(d.subjectThingName(), "a derived table claims no subject");
        }
    }

    /** Acceptance 3: which end is uncovered follows from the timestamps, never from the read direction. */
    @Test
    void lateFirstReading_leavesTheHeadUnknown_withAndWithoutTheReason() throws Exception {
        for (Limit limit : Limit.values()) {
            JsonNode out = run(history(HISTORY_ROUTE, limit, everyTenMinutes(15_600, 23)), SHIFT, SHIFT_STEP);
            JsonNode m = out.path("analysisEnvelope").path("metrics");
            assertEquals(11d / 3d, m.path("observedIntegral").asDouble(), TOLERANCE, out.toString());
            assertEquals("0", m.path("estimatedSeconds").asText(), "the reading at 14:00 is the right anchor");
            assertEquals("15600", m.path("unknownSeconds").asText());
            assertEquals("0", m.path("cancelledTailHoldSeconds").asText());
            List<String> segments = segmentsOf(out);
            assertTrue(segments.get(0).endsWith("|UNKNOWN|null|null"), segments.get(0));
        }
    }

    /** Acceptance 5 through the cache path: anchors, their timestamp groups, and a window without any record. */
    @Test
    void anchors_supportTheWindowEdges_throughTheArtifactPath() throws Exception {
        String trapezoid10 = ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":10,\"timeUnit\":\"seconds\"";
        for (Limit limit : Limit.values()) {
            JsonNode anchorsOnly = run(history(HISTORY_ROUTE, limit, row(-100, 7d), row(0, 0d), row(10, 10d),
                    row(200, 7d)), window(2, 8), trapezoid10);
            JsonNode m = anchorsOnly.path("analysisEnvelope").path("metrics");
            assertEquals("SUCCESS", anchorsOnly.path("analysisEnvelope").path("status").asText(),
                    anchorsOnly.toString());
            assertEquals(TimeWeightedCacheRunner.OUTCOME_OK, anchorsOnly.path("reason").asText());
            assertEquals("30", m.path("integral").asText());
            assertEquals("5", m.path("timeWeightedMean").asText());
            assertEquals("6", m.path("observedSeconds").asText());
            assertEquals("0", m.path("readings").asText(), "no record inside the window");
        }

        JsonNode prefix = run(history(HISTORY_ROUTE, Limit.NONE, row(-10, 2d), row(100, 9d)), window(0, 60),
                ",\"integrationMethod\":\"step_hold\",\"maxGapSeconds\":30,\"timeUnit\":\"seconds\"");
        assertEquals(List.of(segment(0, 20, "HELD", "40.0", "20.0"), segment(20, 60, "UNKNOWN", "null", "null")),
                segmentsOf(prefix), "the anchor's validity does not restart at the window edge");

        // Anchor timestamp groups: identical rows collapse; a conflict is a barrier and is not skipped.
        JsonNode collapsed = run(history(HISTORY_ROUTE, Limit.NONE, row(-10, 0d), row(-10, 0d), row(10, 20d),
                row(10, 20d)), window(0, 10), ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":20,"
                        + "\"timeUnit\":\"seconds\"");
        assertEquals("150", collapsed.path("analysisEnvelope").path("metrics").path("integral").asText());
        assertEquals("2", collapsed.path("analysisEnvelope").path("metrics").path("duplicateRowsCollapsed").asText());

        JsonNode conflict = run(history(HISTORY_ROUTE, Limit.NONE, row(-20, 5d), row(-10, 0d), row(-10, 1d),
                row(5, 10d), row(10, 20d), row(10, 21d), row(20, 30d)), window(0, 10),
                ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":100,\"timeUnit\":\"seconds\"");
        assertEquals(TimeWeightedCacheRunner.OUTCOME_NO_COVERED_SEGMENT, conflict.path("reason").asText(),
                conflict.toString());
        assertEquals("INSUFFICIENT_EVIDENCE", conflict.path("analysisEnvelope").path("status").asText());
        assertEquals("2", conflict.path("analysisEnvelope").path("metrics").path("conflictInstants").asText());
        assertFalse(conflict.path("analysisEnvelope").path("metrics").has("integral"), "absent, not 0");
        assertEquals(List.of(segment(0, 10, "UNKNOWN", "null", "null")), segmentsOf(conflict));
    }

    /** Acceptance 6 and 7: rows without a usable value are no readings; the outcomes without covered time. */
    @Test
    void excludedRows_andOutcomesWithoutCoveredTime() throws Exception {
        String step = ",\"integrationMethod\":\"step_hold\",\"maxGapSeconds\":30,\"timeUnit\":\"seconds\"";
        JsonNode withNull = run(history(HISTORY_ROUTE, Limit.NONE, row(0, 2d), row(20, null), row(40, 4d),
                row(60, 4d)), window(0, 60), step);
        JsonNode m = withNull.path("analysisEnvelope").path("metrics");
        assertEquals("1", m.path("rowsMissingValue").asText(), withNull.toString());
        assertEquals(List.of(segment(0, 30, "HELD", "60.0", "30.0"), segment(30, 40, "UNKNOWN", "null", "null"),
                segment(40, 60, "OBSERVED", "80.0", "20.0")), segmentsOf(withNull),
                "0 to 40 s is judged against maxGapSeconds as if the null row were not there");
        assertTrue(containsText(withNull.path("warnings"), "missingValue=1"));

        JsonNode single = run(history(HISTORY_ROUTE, Limit.NONE, row(5, 2d)), window(0, 60),
                ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":30,\"timeUnit\":\"seconds\"");
        assertEquals(TimeWeightedCacheRunner.OUTCOME_NO_COVERED_SEGMENT, single.path("reason").asText());
        assertEquals("60", single.path("analysisEnvelope").path("metrics").path("unknownSeconds").asText());
        assertTrue(single.path("mayPublish").asBoolean(), "the all-UNKNOWN table is still a table");

        JsonNode none = run(history(HISTORY_ROUTE, Limit.NONE, row(20, null)), window(0, 60), step);
        assertEquals(TimeWeightedCacheRunner.OUTCOME_NO_READINGS, none.path("reason").asText(), none.toString());
        assertEquals("INSUFFICIENT_EVIDENCE", none.path("analysisEnvelope").path("status").asText());

        JsonNode heldZero = run(history(HISTORY_ROUTE, Limit.NONE, row(0, 0d)), window(0, 10),
                ",\"integrationMethod\":\"step_hold\",\"maxGapSeconds\":10,\"timeUnit\":\"seconds\"");
        JsonNode hz = heldZero.path("analysisEnvelope").path("metrics");
        assertEquals("0", hz.path("estimatedIntegral").asText());
        assertEquals("10", hz.path("estimatedSeconds").asText());
        assertEquals("true", hz.path("containsEstimate").asText());
        assertTrue(containsText(heldZero.path("warnings"), "Contains an estimate: 10 s"),
                "an estimate of 0 is still an estimate: " + heldZero.path("warnings"));
    }

    /** Acceptance 8: admission rests on the declared subject identity; an empty parent list proves nothing. */
    @Test
    void sourcesWithoutADeclaredSingleSeries_areRefused_andNothingIsPublished() throws Exception {
        String gap200 = ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":200,\"timeUnit\":\"seconds\"";
        String first = history(HISTORY_ROUTE, Limit.NONE, row(0, 1d), row(10, 1d));
        String second = history(HISTORY_ROUTE, Limit.NONE, row(100, 1d), row(110, 1d));
        JsonNode union = tabulate("{\"mode\":\"union_rows\",\"sourceCacheIds\":[\"" + first + "\",\"" + second
                + "\"],\"labelColumn\":\"src\",\"labelValues\":[\"A\",\"B\"]}");
        assertEquals("success", union.path("status").asText(), union.toString());
        assertRefused(run(union.path("cacheId").asText(), window(0, 120), gap200), "union_rows");

        // The min per timestamp keeps all four raw readings, under route invoke_service with no parents:
        // accepted, the 90 s between the two fragments would be integrated as OBSERVED.
        JsonNode grouped = tabulate("{\"cacheId\":\"" + union.path("cacheId").asText() + "\",\"mode\":\"group_metric\","
                + "\"groupBy\":[\"timestamp\"],\"measures\":[{\"name\":\"value\",\"op\":\"min\",\"column\":\"value\"}]}");
        assertEquals("success", grouped.path("status").asText(), grouped.toString());
        String groupedId = grouped.path("cacheId").asText();
        assertFalse(groupedId.isEmpty(), grouped.toString());
        SourceDescriptor groupedDescriptor = InvokeServiceExecutor.lookupSourceDescriptor(groupedId);
        assertTrue(groupedDescriptor == null || groupedDescriptor.parentSourceCacheIds().isEmpty(),
                "the laundering case: no recorded parent");
        assertRefused(run(groupedId, window(0, 120), gap200), "union_rows then group_metric");

        assertRefused(run(InvokeServiceExecutor.storeInfotableInConversationCache(seriesTable(row(0, 1d), row(10, 1d))),
                window(0, 120), gap200), "plain invoke_service result");
        InfoTable stream = seriesTable(row(0, 1d), row(10, 1d));
        assertRefused(run(TabularArtifactHub.store(stream, SourceDescriptorSupport.forPrimaryStore(stream,
                "query_stream_entries")), window(0, 120), gap200), "stream rows");

        // filter_rows publishes a derived table only above the inline threshold.
        String longSeries = history(HISTORY_ROUTE, Limit.NONE,
                everyTenMinutes(0, InvokeServiceExecutor.largeTableRowThreshold() + 5));
        JsonNode filtered = tabulate("{\"cacheId\":\"" + longSeries + "\",\"mode\":\"filter_rows\","
                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"value\",\"value\":-1}}");
        assertEquals("success", filtered.path("status").asText(), filtered.toString());
        assertEquals(List.of(longSeries), InvokeServiceExecutor.lookupSourceDescriptor(
                filtered.path("cacheId").asText()).parentSourceCacheIds(), filtered.toString());
        assertRefused(run(filtered.path("cacheId").asText(), window(0, 120), gap200), "filter_rows of a series");

        InfoTable anonymous = NumericHistoryCacheWriter.newTable();
        NumericHistoryCacheWriter.addRow(anonymous, SHIFT_START.toString(), 1d);
        assertRefused(run(NumericHistoryCacheWriter.store(anonymous, HISTORY_ROUTE, null).cacheId(), window(0, 120),
                gap200), "history table stored without a subject");
        assertRefused(run(TabularArtifactHub.store(seriesTable(row(0, 1d))), window(0, 120), gap200),
                "cache without a descriptor");

        // Requested columns must be the declared roles.
        InfoTable extra = extraColumnTable();
        String withExtra = NumericHistoryCacheWriter.storeWithRoles(extra, HISTORY_ROUTE, "timestamp", "value",
                "Pump1", "power", null).cacheId();
        assertRefused(tabulate("{\"cacheId\":\"" + withExtra + "\",\"mode\":\"time_weighted\",\"timeColumn\":"
                + "\"timestamp\",\"valueColumn\":\"other\"," + window(0, 120) + gap200 + "}"), "other value column");
        assertRefused(tabulate("{\"cacheId\":\"" + withExtra + "\",\"mode\":\"time_weighted\",\"timeColumn\":"
                + "\"otherTime\",\"valueColumn\":\"value\"," + window(0, 120) + gap200 + "}"), "other time column");

        JsonNode ok = run(first, window(0, 10), gap200);
        assertEquals("OK", ok.path("status").asText(), ok.toString());
        assertRefused(tabulate("{\"cacheId\":\"" + ok.path("findingCacheId").asText() + "\",\"mode\":\"time_weighted\","
                + "\"timeColumn\":\"segmentStart\",\"valueColumn\":\"integral\"," + window(0, 10) + gap200 + "}"),
                "this mode's own output");

        JsonNode entity = run(first, window(0, 10), gap200 + ",\"entityColumn\":\"src\"");
        assertEquals("ERROR", entity.path("status").asText());
        assertEquals(TimeWeightedCachedResultExecutor.ARGUMENT_UNSUPPORTED, entity.path("reason").asText());
    }

    /**
     * Acceptance 8, comparing series: the output tables are united and grouped with the advertised
     * group_metric recipe. Ten covered seconds at 2 plus ten unknown seconds give a mean of 2, not 1.
     */
    @Test
    void severalSeries_areComparedByUnitingTheOutputTables() throws Exception {
        Map<String, Double> means = new LinkedHashMap<>();
        for (String unit : new String[] {"seconds", "minutes", "hours"}) {
            String args = ",\"integrationMethod\":\"step_hold\",\"maxGapSeconds\":10,\"timeUnit\":\"" + unit + "\"";
            JsonNode halfCovered = run(history(HISTORY_ROUTE, Limit.NONE, row(0, 2d)), window(0, 20), args);
            JsonNode allUnknown = run(history(HISTORY_ROUTE, Limit.NONE, row(-500, 9d)), window(0, 20), args);
            assertEquals(TimeWeightedCacheRunner.OUTCOME_NO_COVERED_SEGMENT, allUnknown.path("reason").asText());
            JsonNode union = tabulate("{\"mode\":\"union_rows\",\"sourceCacheIds\":[\""
                    + halfCovered.path("findingCacheId").asText() + "\",\"" + allUnknown.path("findingCacheId").asText()
                    + "\"],\"labelColumn\":\"series\",\"labelValues\":[\"P1\",\"P2\"]}");
            assertEquals("success", union.path("status").asText(), union.toString());
            JsonNode grouped = tabulate("{\"cacheId\":\"" + union.path("cacheId").asText()
                    + "\",\"mode\":\"group_metric\",\"groupBy\":[\"series\"],\"measures\":["
                    + "{\"name\":\"integralSum\",\"op\":\"sum\",\"column\":\"integral\"},"
                    + "{\"name\":\"durationSum\",\"op\":\"sum\",\"column\":\"supportedDuration\"}],"
                    + "\"derived\":[{\"name\":\"mean\",\"op\":\"ratio\",\"numerator\":\"integralSum\","
                    + "\"denominator\":\"durationSum\"}]}");
            assertEquals("success", grouped.path("status").asText(), grouped.toString());
            for (JsonNode r : grouped.path("rows")) {
                String series = r.path("series").asText();
                if ("P1".equals(series)) {
                    means.put(unit, r.path("mean").asDouble());
                } else {
                    assertEquals("P2", series, grouped.toString());
                    assertTrue(r.path("mean").isNull() || r.path("mean").isMissingNode(),
                            "an all-unknown series has no mean, not 0: " + grouped);
                }
            }
        }
        assertEquals(3, means.size(), means.toString());
        means.forEach((unit, mean) -> assertEquals(2d, mean, 2d * TOLERANCE, unit));
    }

    /** Acceptance 9 through the tool: the unit scales the integral and leaves the mean alone. */
    @Test
    void timeUnit_scalesTheIntegralOnly() throws Exception {
        String cid = history(HISTORY_ROUTE, Limit.NONE, row(0, 2d), row(3_600, 4d));
        Map<String, JsonNode> byUnit = new LinkedHashMap<>();
        for (String unit : new String[] {"seconds", "minutes", "hours"}) {
            byUnit.put(unit, run(cid, window(0, 3_600), ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":3600,"
                    + "\"timeUnit\":\"" + unit + "\"").path("analysisEnvelope").path("metrics"));
        }
        assertEquals("10800", byUnit.get("seconds").path("integral").asText());
        assertEquals("180", byUnit.get("minutes").path("integral").asText());
        assertEquals("3", byUnit.get("hours").path("integral").asText());
        byUnit.values().forEach(m -> assertEquals("3", m.path("timeWeightedMean").asText()));
    }

    /** Acceptance 10. */
    @Test
    void argumentAndShapeErrors_failFast_andPublishNothing() throws Exception {
        String cid = history(HISTORY_ROUTE, Limit.NONE, row(0, 2d), row(60, 4d));
        String ok = ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":60,\"timeUnit\":\"seconds\"";
        assertError(U4SeriesToolArgs.ARGUMENT_MISSING,
                run(cid, window(0, 60), ",\"maxGapSeconds\":60,\"timeUnit\":\"seconds\""));
        assertError(U4SeriesToolArgs.ARGUMENT_MISSING,
                run(cid, window(0, 60), ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":60"));
        assertError(U4SeriesToolArgs.ARGUMENT_MISSING,
                run(cid, window(0, 60), ",\"integrationMethod\":\"trapezoid\",\"timeUnit\":\"seconds\""));
        assertError(TimeWeightedIntegral.INTEGRATION_METHOD_INVALID,
                run(cid, window(0, 60), ok.replace("trapezoid", "linear")));
        assertError(TimeWeightedIntegral.TIME_UNIT_INVALID, run(cid, window(0, 60), ok.replace("seconds", "days")));
        for (String gap : new String[] {"0", "-5", "1.5", "\"60\"", "9007199254740993"}) {
            assertError(TimeWeightedIntegral.MAX_GAP_INVALID,
                    run(cid, window(0, 60), ok.replace("\"maxGapSeconds\":60", "\"maxGapSeconds\":" + gap)));
        }
        assertError(U4SeriesToolArgs.WINDOW_INVALID, run(cid,
                "\"windowStart\":\"2026-03-02T07:00:00Z\",\"windowEnd\":\"2026-03-02T06:00:00Z\"", ok));
        // A column that is not the declared role is refused before the table is even opened for columns.
        assertError(TimeWeightedCacheRunner.SOURCE_CONTINUITY_UNKNOWN, tabulate("{\"cacheId\":\"" + cid
                + "\",\"mode\":\"time_weighted\",\"timeColumn\":\"timestamp\",\"valueColumn\":\"nope\","
                + window(0, 60) + ok + "}"));

        assertError(TimeWeightedCacheRunner.TIMESTAMP_UNSUPPORTED, run(cid,
                "\"windowStart\":\"2026-03-02T06:00:00.0005Z\",\"windowEnd\":\"2026-03-02T06:01:00Z\"", ok));
        assertError(TimeWeightedCacheRunner.TIMESTAMP_UNSUPPORTED, run(cid,
                "\"windowStart\":\"2026-03-02T06:00:00Z\",\"windowEnd\":\"2026-03-02T06:01:00.000000001Z\"", ok));
        assertError(TimeWeightedCacheRunner.TIMESTAMP_UNSUPPORTED, run(cid,
                "\"windowStart\":\"2026-03-02T06:00:00Z\",\"windowEnd\":\"+10000-01-01T00:00:00Z\"", ok));

        assertError(TimeWeightedCacheRunner.TIMESTAMP_UNSUPPORTED, run(isoHistory("2026-03-02T06:00:00.0001Z", 1d,
                "2026-03-02T06:00:30Z", 2d), window(0, 60), ok));
        assertError(TimeWeightedIntegral.VALUE_MAGNITUDE_UNSUPPORTED, run(isoHistory("2026-03-02T06:00:00Z", 1e151,
                "2026-03-02T06:00:30Z", 2d), window(0, 60), ok));
    }

    /** A representable integral survives the dispatcher path: no intermediate underflows it to 0. */
    @Test
    void tinyValues_keepTheirIntegral_throughTheArtifactPath() throws Exception {
        JsonNode clipped = run(history(HISTORY_ROUTE, Limit.NONE, row(0, 0d), row(10, 1e-320)), window(2, 8),
                ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":10,\"timeUnit\":\"seconds\"");
        JsonNode m = clipped.path("analysisEnvelope").path("metrics");
        double reference = new java.math.BigDecimal(1e-320).multiply(java.math.BigDecimal.valueOf(3)).doubleValue();
        assertEquals(reference, Double.parseDouble(m.path("integral").asText()), 4 * Double.MIN_VALUE,
                clipped.toString());
        assertTrue(Double.parseDouble(m.path("timeWeightedMean").asText()) > 0d, m.toString());
        InfoTable published = InvokeServiceExecutor.lookupCachedInfotable(clipped.path("findingCacheId").asText());
        assertEquals(reference, (Double) published.getRow(0).getValue("integral"), 4 * Double.MIN_VALUE);

        JsonNode unclipped = run(history(HISTORY_ROUTE, Limit.NONE, row(0, 0d), row(1_000, Double.MIN_VALUE)),
                window(0, 1_000),
                ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":1000,\"timeUnit\":\"seconds\"");
        assertEquals(500 * Double.MIN_VALUE, Double.parseDouble(
                unclipped.path("analysisEnvelope").path("metrics").path("integral").asText()), 0d,
                unclipped.toString());
    }

    /** Clipped end values that round to 0 keep their area, and the mean does not move with the unit. */
    @Test
    void clippedAreasAndTheUnitIndependentMean_throughTheArtifactPath() throws Exception {
        String ramp = history(HISTORY_ROUTE, Limit.NONE, row(0, 0d), row(1_000, Double.MIN_VALUE));
        String trapezoid = ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":1000,\"timeUnit\":\"seconds\"";
        long[][] windows = {{0, 400, 80}, {100, 400, 75}};
        for (long[] w : windows) {
            JsonNode out = run(ramp, window(w[0], w[1]), trapezoid);
            assertEquals(w[2] * Double.MIN_VALUE, Double.parseDouble(
                    out.path("analysisEnvelope").path("metrics").path("integral").asText()), 0d, out.toString());
            InfoTable published = InvokeServiceExecutor.lookupCachedInfotable(out.path("findingCacheId").asText());
            assertEquals(w[2] * Double.MIN_VALUE, (Double) published.getRow(0).getValue("integral"), 0d);
        }

        String constant = history(HISTORY_ROUTE, Limit.NONE, row(0, 1e-320), row(1, 1e-320));
        String floor = history(HISTORY_ROUTE, Limit.NONE, row(0, Double.MIN_VALUE), row(1, Double.MIN_VALUE));
        for (String unit : new String[] {"seconds", "minutes", "hours"}) {
            String step = ",\"integrationMethod\":\"step_hold\",\"maxGapSeconds\":1,\"timeUnit\":\"" + unit + "\"";
            JsonNode m = run(constant, window(0, 1), step).path("analysisEnvelope").path("metrics");
            assertEquals(1e-320, Double.parseDouble(m.path("timeWeightedMean").asText()), 0d, unit + " " + m);
            JsonNode f = run(floor, window(0, 1), step).path("analysisEnvelope").path("metrics");
            assertEquals(Double.MIN_VALUE, Double.parseDouble(f.path("timeWeightedMean").asText()), 0d,
                    unit + " " + f);
        }
    }

    /** Large segments that cancel keep their exact residual through the cache and dispatcher path. */
    @Test
    void cancellingSegments_keepTheirResidual_throughTheArtifactPath() throws Exception {
        for (double a : new double[] {1e16, 1e60, 1e150}) {
            for (String unit : new String[] {"seconds", "hours"}) {
                JsonNode out = run(history(HISTORY_ROUTE, Limit.NONE, row(0, a), row(1, 1d), row(2, -a)),
                        window(0, 2), ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":1,\"timeUnit\":\""
                                + unit + "\"");
                JsonNode m = out.path("analysisEnvelope").path("metrics");
                double perUnit = "seconds".equals(unit) ? 1d : 3_600d;
                assertEquals(1d / perUnit, Double.parseDouble(m.path("integral").asText()), 1e-15 / perUnit,
                        a + " " + out);
                assertEquals("0.5", m.path("timeWeightedMean").asText(), a + " " + unit);
                assertEquals("2", m.path("observedSeconds").asText());
            }
        }
    }

    /**
     * An anchor timestamp group is judged as a whole: a value outside the admitted magnitude refuses the
     * request wherever it stands among the group's rows, also after the group already became a conflict.
     */
    @Test
    void oversizedValueInAnAnchorGroup_isRefusedInEveryRowOrder_onBothSides() throws Exception {
        String args = ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":20,\"timeUnit\":\"seconds\"";
        double[][] orders = {{1, 2, 1e151}, {1, 1e151, 2}, {1e151, 1, 2}, {2, 1, 1, 1e151}, {1, 2, -1e151, 3}};
        for (long anchorSecond : new long[] {-1, 10, 12}) {
            for (double[] order : orders) {
                List<Object[]> rows = new ArrayList<>(List.of(row(2, 4d), row(8, 4d)));
                for (double v : order) {
                    rows.add(row(anchorSecond, v));
                }
                JsonNode out = run(history(HISTORY_ROUTE, Limit.NONE, rows.toArray(new Object[0][])), window(0, 10),
                        args);
                assertError(TimeWeightedIntegral.VALUE_MAGNITUDE_UNSUPPORTED, out);
            }
        }
        // A nearer valid group replaces the oversized one, which is then genuinely outside the calculation.
        for (boolean fartherFirst : new boolean[] {true, false}) {
            List<Object[]> farther = List.of(row(-5, 1d), row(-5, 2d), row(-5, 1e151), row(15, 1d), row(15, 2d),
                    row(15, 1e151));
            List<Object[]> nearer = List.of(row(-1, 4d), row(2, 4d), row(8, 4d), row(10, 4d));
            List<Object[]> rows = new ArrayList<>(fartherFirst ? farther : nearer);
            rows.addAll(fartherFirst ? nearer : farther);
            JsonNode out = run(history(HISTORY_ROUTE, Limit.NONE, rows.toArray(new Object[0][])), window(0, 10), args);
            assertEquals("OK", out.path("status").asText(), out.toString());
            assertEquals("40", out.path("analysisEnvelope").path("metrics").path("integral").asText());
            assertEquals("0", out.path("analysisEnvelope").path("metrics").path("conflictInstants").asText());
        }
    }

    /** Acceptance 10: a whole-millisecond window edge between two readings is published as that edge. */
    @Test
    void windowEdgesBetweenReadings_arePublishedToTheMillisecond() throws Exception {
        String cid = history(HISTORY_ROUTE, Limit.NONE, row(0, 0d), row(10, 10d));
        JsonNode out = run(cid, "\"windowStart\":\"2026-03-02T06:00:01.234Z\",\"windowEnd\":\"2026-03-02T06:00:08.765Z\"",
                ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":10,\"timeUnit\":\"seconds\"");
        InfoTable published = InvokeServiceExecutor.lookupCachedInfotable(out.path("findingCacheId").asText());
        assertNotNull(published, out.toString());
        assertEquals(1, published.getRowCount());
        assertEquals(Instant.parse("2026-03-02T06:00:01.234Z").toEpochMilli(),
                ((DateTime) published.getRow(0).getValue("segmentStart")).getMillis());
        assertEquals(Instant.parse("2026-03-02T06:00:08.765Z").toEpochMilli(),
                ((DateTime) published.getRow(0).getValue("segmentEnd")).getMillis());
        assertEquals("7.531", out.path("analysisEnvelope").path("metrics").path("observedSeconds").asText());
        assertEquals(1.234, (Double) published.getRow(0).getValue("startValue"), 1e-12);
        assertEquals(8.765, (Double) published.getRow(0).getValue("endValue"), 1e-12);
    }

    /** Acceptance 10: the window-edge precision rule is local to this mode. */
    @Test
    void subMillisecondWindowEdges_stillWorkForTheOtherSeriesModes() throws Exception {
        String cid = history(HISTORY_ROUTE, Limit.NONE, row(0, 1d), row(10, 2d), row(20, 4d));
        String edges = "\"windowStart\":\"2026-03-02T05:59:59.9995Z\",\"windowEnd\":\"2026-03-02T06:01:00.0000001Z\"";
        for (String mode : new String[] {"rolling", "counter_delta", "rolling_stats"}) {
            JsonNode out = tabulate("{\"cacheId\":\"" + cid + "\",\"mode\":\"" + mode + "\",\"timeColumn\":\"timestamp\","
                    + "\"valueColumn\":\"value\"," + edges
                    + ("rolling_stats".equals(mode) ? ",\"statistic\":\"sum\"" : "") + "}");
            assertEquals("OK", out.path("status").asText(), mode + ": " + out);
        }
    }

    /** Acceptance 11 through the real dispatcher. */
    @Test
    void interruptDuringOutputCreation_returnsCancelled_andNothingBecomesVisible() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = history(HISTORY_ROUTE, Limit.NONE, row(0, 2d), row(60, 4d));
        cache.onNextCreates(() -> Thread.currentThread().interrupt());
        JsonNode out;
        try {
            out = run(cid, window(0, 60), ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":60,"
                    + "\"timeUnit\":\"seconds\"");
        } finally {
            Thread.interrupted();
        }
        assertError(TimeWeightedCacheRunner.CANCELLED, out);
        assertEquals(0, cache.publicationsSinceArmed(), "no derived artifact became visible");
    }

    /** Acceptance 12. */
    @Test
    void admissionOff_withdrawsTheModeAndItsProperties_andRejectsDirectCalls() throws Exception {
        String cid = history(HISTORY_ROUTE, Limit.NONE, row(0, 2d), row(60, 4d));
        String on = advertisedSurface();
        assertTrue(on.contains("time_weighted"));
        assertTrue(on.contains("integrationMethod"));
        assertTrue(on.contains("\"timeUnit\""));
        assertTrue(on.contains("counter_delta / time_weighted: longest usable interval"), on);

        ComputingOperationAdmission.setTimeWeightedEnabled(false);
        String off = advertisedSurface();
        assertFalse(Pattern.compile("\\btime_weighted\\b").matcher(off).find());
        assertFalse(off.contains("integrationMethod"));
        assertFalse(off.contains("\"timeUnit\""));
        assertTrue(off.contains("\"maxGapSeconds\""), "still advertised for counter_delta");
        assertTrue(off.contains("counter_delta: longest usable interval"), off);
        JsonNode out = run(cid, window(0, 60), ",\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":60,"
                + "\"timeUnit\":\"seconds\"");
        assertError(ComputingOperationAdmission.TIME_WEIGHTED_UNAVAILABLE, out);

        ComputingOperationAdmission.setTimeWeightedEnabled(true);
        ComputingOperationAdmission.setCounterDeltaEnabled(false);
        String onlyThisMode = advertisedSurface();
        assertTrue(onlyThisMode.contains("\"maxGapSeconds\""));
        assertTrue(onlyThisMode.contains("time_weighted: longest usable interval"), onlyThisMode);
        assertFalse(Pattern.compile("\\bcounter_delta\\b").matcher(onlyThisMode).find());
    }

    /** CE-3 schema budget: the mode may add at most 700 characters to the model-visible tool schema. */
    @Test
    void schemaGrowthStaysInsideTheSliceBudget() throws Exception {
        for (String shape : new String[] {"openai-chat-completions-v1", "anthropic-messages-v1"}) {
            ComputingOperationAdmission.setTimeWeightedEnabled(true);
            int on = schemaChars(shape);
            ComputingOperationAdmission.setTimeWeightedEnabled(false);
            int off = schemaChars(shape);
            System.out.println("TimeWeighted schema growth " + shape + ": " + (on - off) + " (total " + on + ")");
            assertTrue(on > off && on - off <= 700, shape + " growth=" + (on - off));
        }
    }

    /** Acceptance 13: completeness and every warning reach the model through last-resort compaction. */
    @Test
    void warningsAndCompleteness_surviveLastResortCompaction() throws Exception {
        JsonNode out = run(history(HISTORY_ROUTE, Limit.READ_LIMIT, row(0, 2d), row(0, 2d), row(20, null),
                row(40, 4d), row(50, 5d), row(50, 6d)), window(0, 120),
                ",\"integrationMethod\":\"step_hold\",\"maxGapSeconds\":30,\"timeUnit\":\"seconds\"");
        assertEquals(4, out.path("warnings").size(), out.path("warnings").toString());
        assertTrue(out.path("warnings").get(0).asText().startsWith(AnalysisEnvelopeValidator.SCOPE_OBSERVED_SPAN));
        JsonNode seen = lastResortCompacted(out);
        assertEquals(strings(out.path("warnings")), strings(seen.path("warnings")));
        assertEquals(out.path("completeness"), seen.path("completeness"));
        assertEquals(List.of(ReadLimitFact.REASON), strings(seen.path("completeness").path("reasons")));
    }

    private enum Limit {
        NONE,
        READ_LIMIT,
        PARTIAL
    }

    private static void assertRefused(JsonNode out, String what) {
        assertEquals("ERROR", out.path("status").asText(), what + ": " + out);
        assertEquals(TimeWeightedCacheRunner.SOURCE_CONTINUITY_UNKNOWN, out.path("reason").asText(), what);
        assertFalse(out.has("findingCacheId"), what);
        assertFalse(out.path("mayPublish").asBoolean(true), what);
    }

    private static void assertError(String reason, JsonNode out) {
        assertEquals("ERROR", out.path("status").asText(), out.toString());
        assertEquals(reason, out.path("reason").asText(), out.toString());
        assertFalse(out.has("findingCacheId"));
        assertFalse(out.path("mayPublish").asBoolean(true));
    }

    private static JsonNode run(String cacheId, String window, String extraArgs) throws Exception {
        return tabulate("{\"cacheId\":\"" + cacheId + "\",\"mode\":\"time_weighted\",\"timeColumn\":\"timestamp\","
                + "\"valueColumn\":\"value\"," + window + extraArgs + "}");
    }

    private static JsonNode tabulate(String args) throws Exception {
        return MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("1", "tabulate_cached_result", args)));
    }

    private static String window(long startSeconds, long endSeconds) {
        return "\"windowStart\":\"" + SHIFT_START.plusSeconds(startSeconds) + "\",\"windowEnd\":\""
                + SHIFT_START.plusSeconds(endSeconds) + "\"";
    }

    private static Object[] row(long secondsFromShiftStart, Double value) {
        return new Object[] {secondsFromShiftStart, value};
    }

    private static Object[][] everyTenMinutes(long firstSecond, int count) {
        Object[][] rows = new Object[count][];
        for (int i = 0; i < count; i++) {
            rows[i] = row(firstSecond + i * 600L, 1d);
        }
        return rows;
    }

    /** A cache as the numeric property-history read writes it: declared roles and subject identity. */
    private static String history(String route, Limit limit, Object[]... rows) throws Exception {
        InfoTable table = NumericHistoryCacheWriter.newTable();
        for (Object[] r : rows) {
            NumericHistoryCacheWriter.addRow(table, SHIFT_START.plusSeconds((Long) r[0]).toString(), (Double) r[1]);
        }
        UnaryOperator<SourceDescriptor> decorate = limit == Limit.READ_LIMIT
                ? d -> SourceDescriptorSupport.withReadLimitFact(d, ReadLimitFact.observe(table, rows.length))
                : limit == Limit.PARTIAL
                        ? d -> DerivedArtifactLineage.withCompleteness(d, CompletenessStatus.PARTIAL)
                        : null;
        String cacheId = NumericHistoryCacheWriter.store(table, route, "Pump1", "power", decorate).cacheId();
        SourceDescriptor stored = InvokeServiceExecutor.lookupSourceDescriptor(cacheId);
        assertEquals("Pump1", stored.subjectThingName(), "fixture: the identity survives the decoration");
        assertEquals(limit == Limit.READ_LIMIT, stored.completenessReasons().contains(ReadLimitFact.REASON));
        return cacheId;
    }

    private static String isoHistory(String t1, double v1, String t2, double v2) throws Exception {
        InfoTable table = NumericHistoryCacheWriter.newTable();
        NumericHistoryCacheWriter.addRow(table, t1, v1);
        NumericHistoryCacheWriter.addRow(table, t2, v2);
        return NumericHistoryCacheWriter.store(table, HISTORY_ROUTE, "Pump1", "power", null).cacheId();
    }

    /** Same column names as a history cache, but stored by an ordinary producer. */
    private static InfoTable seriesTable(Object[]... rows) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("timestamp", BaseTypes.DATETIME));
        shape.addFieldDefinition(field("value", BaseTypes.NUMBER));
        InfoTable table = new InfoTable(shape);
        for (Object[] r : rows) {
            ValueCollection vc = new ValueCollection();
            vc.put("timestamp",
                    new DatetimePrimitive(new DateTime(SHIFT_START.plusSeconds((Long) r[0]).toEpochMilli())));
            vc.put("value", new NumberPrimitive((Double) r[1]));
            table.addRow(vc);
        }
        return table;
    }

    private static InfoTable extraColumnTable() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("timestamp", BaseTypes.DATETIME));
        shape.addFieldDefinition(field("otherTime", BaseTypes.DATETIME));
        shape.addFieldDefinition(field("value", BaseTypes.NUMBER));
        shape.addFieldDefinition(field("other", BaseTypes.NUMBER));
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < 2; i++) {
            ValueCollection vc = new ValueCollection();
            DatetimePrimitive t = new DatetimePrimitive(new DateTime(SHIFT_START.plusSeconds(i * 10L).toEpochMilli()));
            vc.put("timestamp", t);
            vc.put("otherTime", t);
            vc.put("value", new NumberPrimitive(1d));
            vc.put("other", new NumberPrimitive(1d));
            table.addRow(vc);
        }
        return table;
    }

    private static FieldDefinition field(String name, BaseTypes type) {
        FieldDefinition f = new FieldDefinition();
        f.setName(name);
        f.setBaseType(type);
        return f;
    }

    private static String segment(long startSeconds, long endSeconds, String coverage, String integral,
            String supportedDuration) {
        return new DateTime(SHIFT_START.plusSeconds(startSeconds).toEpochMilli()).getMillis() + "|"
                + new DateTime(SHIFT_START.plusSeconds(endSeconds).toEpochMilli()).getMillis() + "|" + coverage + "|"
                + integral + "|" + supportedDuration;
    }

    private static List<String> segmentsOf(JsonNode result) throws Exception {
        InfoTable t = InvokeServiceExecutor.lookupCachedInfotable(result.path("findingCacheId").asText());
        assertNotNull(t, result.toString());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.getRowCount(); i++) {
            ValueCollection r = t.getRow(i);
            out.add(((DateTime) r.getValue("segmentStart")).getMillis() + "|"
                    + ((DateTime) r.getValue("segmentEnd")).getMillis() + "|" + r.getStringValue("coverage") + "|"
                    + value(r, "integral") + "|" + value(r, "supportedDuration"));
        }
        return out;
    }

    private static Object value(ValueCollection row, String column) {
        return row.getPrimitive(column) == null ? null : row.getPrimitive(column).getValue();
    }

    private static JsonNode lastResortCompacted(JsonNode result) throws Exception {
        ObjectNode padded = ((ObjectNode) result).deepCopy();
        ObjectNode filler = padded.putObject("filler");
        for (int i = 0; i < 120; i++) {
            filler.put("field_" + i, "x".repeat(110));
        }
        ToolResultEgressGateway.EgressResult egress = ToolResultEgressGateway.compactForLlmAppend(
                "tabulate_cached_result", "call-1", MAPPER.writeValueAsString(padded),
                LoggerFactory.getLogger(TabulateTimeWeightedModeTest.class));
        JsonNode seen = MAPPER.readTree(egress.getLlmContent());
        boolean omittedSomething = false;
        for (JsonNode marker : seen.path("_egress").path("reducedFields")) {
            omittedSomething |= marker.path("omitted").asBoolean();
        }
        assertTrue(omittedSomething, "only last-resort compaction omits whole fields: " + seen.path("_egress"));
        return seen;
    }

    private static boolean containsText(JsonNode array, String text) {
        for (JsonNode n : array) {
            if (n.asText().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static int schemaChars(String apiShape) throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        return com.thingworx.things.agent.llm.ToolSchemaSizer.totalSchemaChars(apiShape,
                new ArrayList<>(reg.getAllDefinitions()));
    }

    private static String advertisedSurface() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition tabulate = reg.getAllDefinitions().stream()
                .filter(d -> "tabulate_cached_result".equals(d.getName())).findFirst().orElseThrow();
        return tabulate.getDescription() + "\n" + MAPPER.writeValueAsString(tabulate.getParametersSchema());
    }
}
