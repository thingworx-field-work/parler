package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
import com.thingworx.things.agent.cache.ArtifactCacheTestFixtures;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.ToolSchemaSizer;
import com.thingworx.things.agent.source.ReadLimitFact;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.transform.time.CounterDeltaCacheRunner;
import com.thingworx.things.agent.transform.time.RollingStats;
import com.thingworx.things.agent.transform.time.RollingStatsCacheRunner;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * CF-52 {@code tabulate_cached_result} {@code mode=rolling_stats}: a real cached artifact through the real
 * schema, admission and dispatch to the published table, its descriptor, the envelope, and what the model
 * sees after egress compaction.
 */
class TabulateRollingStatsModeTest {

    TabulateRollingStatsModeTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant SHIFT_START = Instant.parse("2026-03-02T06:00:00Z");
    private static final String WINDOW = "\"windowStart\":\"2026-03-02T06:00:00Z\",\"windowEnd\":\"2026-03-02T14:00:00Z\"";

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("ce2-rolling-stats");
        ComputingOperationAdmission.resetForTests();
        U4OperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        ComputingOperationAdmission.resetForTests();
        U4OperationAdmission.resetForTests();
    }

    @Test
    void unknownSource_succeedsWithScopeDisclosure_andPublishesOneRowPerRecord() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 1d, null), row(60, 3d, null), row(120, 5d, null)));
        JsonNode out = run(cid, "sum", ",\"observationWindow\":2");
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertEquals(RollingStatsCacheRunner.OUTCOME_OK, out.path("reason").asText());
        JsonNode env = out.path("analysisEnvelope");
        assertEquals("SUCCESS", env.path("status").asText(), "UNKNOWN sources compute normally");
        assertEquals("rolling_stats", env.path("operation").asText());
        assertEquals(RollingStats.METHOD_ID, env.path("method").path("id").asText());
        assertEquals("sum", env.path("metrics").path("statistic").asText());
        assertEquals("1", env.path("metrics").path("rowsNotWarmedUp").asText());
        assertTrue(env.path("budget").path("consumed").path("rows").asLong() > 0L);

        assertEquals(strings(env.path("evidence").path("warnings")), strings(out.path("warnings")));
        String scope = out.path("warnings").get(0).asText();
        assertTrue(scope.startsWith(AnalysisEnvelopeValidator.SCOPE_OBSERVED_SPAN), scope);
        assertTrue(scope.contains("window coverage is not established"), scope);
        assertEquals("UNKNOWN", out.path("completeness").path("status").asText());

        List<String> rows = rowsOf(out);
        assertEquals(List.of("|1.0|OK|1|1|false", "|4.0|OK|2|2|true", "|8.0|OK|2|2|true"), rows);
        SourceDescriptor d = InvokeServiceExecutor.lookupSourceDescriptor(out.path("findingCacheId").asText());
        assertEquals(RollingStatsCacheRunner.ROUTE_ID, d.sourceRouteId());
        assertEquals(List.of(cid), d.parentSourceCacheIds());
        assertEquals(CompletenessStatus.UNKNOWN, d.completenessStatus(), "never upgraded");
    }

    /** A small spread on a large offset keeps its standard deviation through the cache and dispatcher path. */
    @Test
    void stddevOfASmallSpreadOnALargeOffset_isNotInflated() throws Exception {
        double[][] pairs = {{0d, 2d}, {1e16, 1e16 + 2d}, {-1e16, -1e16 + 2d}, {1e9, Math.nextUp(1e9)}};
        for (double[] pair : pairs) {
            String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                    table(row(0, pair[0], null), row(60, pair[1], null)));
            JsonNode out = run(cid, "stddev", ",\"observationWindow\":2");
            assertEquals("OK", out.path("status").asText(), out.toString());
            InfoTable published = InvokeServiceExecutor.lookupCachedInfotable(out.path("findingCacheId").asText());
            double want = (pair[1] - pair[0]) / Math.sqrt(2d);
            assertEquals(want, (Double) published.getRow(1).getValue("value"), want * 1e-15,
                    pair[0] + ", " + pair[1]);
        }
    }

    /** Rows with a missing value stay as records; both no-value reasons are disclosed in warning (2). */
    @Test
    void missingValuesStayAsRecords_andBothNoValueReasonsAreCounted() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, null, null), row(60, 3d, null), row(120, 5d, null)));
        JsonNode stddev = run(cid, "stddev", ",\"observationWindow\":2");
        assertEquals(List.of("|null|BELOW_MIN_SUPPORT|0|1|false", "|null|BELOW_MIN_SAMPLE|1|2|true",
                "|1.4142135623730951|OK|2|2|true"), rowsOf(stddev));
        assertTrue(containsText(stddev.path("warnings"), "BELOW_MIN_SUPPORT=1, BELOW_MIN_SAMPLE=1"),
                stddev.toString());
        assertEquals("1", stddev.path("analysisEnvelope").path("metrics").path("recordsWithoutValue").asText());
        assertFalse(containsText(stddev.path("warnings"), "Excluded rows"), "a missing value is not an exclusion");

        JsonNode counts = run(cid, "count_values", ",\"observationWindow\":2");
        assertEquals(List.of("|0.0|OK|0|1|false", "|1.0|OK|1|2|true", "|2.0|OK|2|2|true"), rowsOf(counts));
    }

    /** Design §3.2: the read-limit reason changes the explanation and never a value. */
    @Test
    void readLimitReason_changesOnlyTheScopeText() throws Exception {
        InfoTable marked = table(row(0, 1d, null), row(60, 3d, null), row(13_200, 5d, null));
        String withReason = InvokeServiceExecutor.storePrimaryWithReadLimit(marked, ReadLimitFact.observe(marked, 3));
        String without = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 1d, null), row(60, 3d, null), row(13_200, 5d, null)));
        JsonNode a = run(withReason, "max", ",\"rollingKind\":\"ELAPSED_DURATION\",\"durationWindowSeconds\":3600");
        JsonNode b = run(without, "max", ",\"rollingKind\":\"ELAPSED_DURATION\",\"durationWindowSeconds\":3600");
        assertEquals(rowsOf(a), rowsOf(b), "identical output tables");
        assertEquals(3, rowsOf(a).size(), "no row past the last observation");
        assertEquals(List.of(ReadLimitFact.REASON), strings(a.path("completeness").path("reasons")));
        assertTrue(a.path("warnings").get(0).asText().contains("not statistics of a complete window or shift"));
        assertEquals(List.of(ReadLimitFact.REASON),
                InvokeServiceExecutor.lookupSourceDescriptor(a.path("findingCacheId").asText()).completenessReasons());
        assertEquals(List.of(), strings(b.path("completeness").path("reasons")));
        assertFalse(b.path("warnings").get(0).asText().contains("complete window or shift"));

        String partial = TabularArtifactHub.store(table(row(0, 1d, null), row(60, 3d, null)),
                SourceDescriptor.builder().completenessStatus(CompletenessStatus.PARTIAL).build());
        JsonNode p = run(partial, "max", "");
        assertEquals("SUCCESS", p.path("analysisEnvelope").path("status").asText(), p.toString());
        assertTrue(p.path("warnings").get(0).asText().contains("complete window or shift"));
    }

    /** After union_rows the reason is table-level; windows never cross entities. */
    @Test
    void unionOfTwoDevices_rollsPerEntity() throws Exception {
        InfoTable limited = table(row(0, 1d, null), row(60, 3d, null));
        String a = InvokeServiceExecutor.storePrimaryWithReadLimit(limited, ReadLimitFact.observe(limited, 2));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(30, 10d, null), row(90, 30d, null)));
        JsonNode union = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"mode\":\"union_rows\",\"sourceCacheIds\":[\"" + a + "\",\"" + b
                        + "\"],\"labelColumn\":\"src\",\"labelValues\":[\"M1\",\"M2\"]}")));
        assertEquals("success", union.path("status").asText(), union.toString());
        JsonNode out = run(union.path("cacheId").asText(), "sum", ",\"observationWindow\":2,\"entityColumn\":\"src\"");
        assertEquals(List.of("M1|1.0|OK|1|1|false", "M1|4.0|OK|2|2|true", "M2|10.0|OK|1|1|false",
                "M2|40.0|OK|2|2|true"), rowsOf(out));
        assertEquals(List.of(ReadLimitFact.REASON), strings(out.path("completeness").path("reasons")));
    }

    /** Through the real dispatcher: the largest admitted duration succeeds, as it does for rolling. */
    @Test
    void largestAdmittedElapsedDuration_succeeds_likeTheExistingRollingMode() throws Exception {
        String huge = ",\"rollingKind\":\"ELAPSED_DURATION\",\"durationWindowSeconds\":9223372036854775807";
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 1d, null), row(60, 3d, null)));
        JsonNode out = run(cid, "mean", huge);
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertEquals(List.of("|1.0|OK|1|1|false", "|2.0|OK|2|2|false"), rowsOf(out));
        assertEquals("3", out.path("analysisEnvelope").path("metrics").path("windowWork").asText());

        JsonNode legacy = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"cacheId\":\"" + cid + "\",\"mode\":\"rolling\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\","
                        + WINDOW + huge + "}")));
        assertEquals("OK", legacy.path("status").asText(), legacy.toString());
    }

    @Test
    void noRecords_andNoSupportedWindow_areInsufficientEvidence() throws Exception {
        JsonNode empty = run(InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(-7_200, 1d, null))), "mean", "");
        assertEquals(RollingStatsCacheRunner.OUTCOME_NO_READINGS, empty.path("reason").asText(), empty.toString());
        assertEquals("INSUFFICIENT_EVIDENCE", empty.path("analysisEnvelope").path("status").asText());
        assertFalse(empty.has("findingCacheId"));

        JsonNode unsupported = run(InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 1d, null), row(60, 3d, null))), "mean", ",\"minSupport\":5");
        assertEquals(RollingStatsCacheRunner.OUTCOME_NO_SUPPORTED_WINDOW, unsupported.path("reason").asText());
        assertEquals("INSUFFICIENT_EVIDENCE", unsupported.path("analysisEnvelope").path("status").asText());
        assertTrue(unsupported.has("findingCacheId"), "the table of unsupported rows is still published");
    }

    @Test
    void argumentShapeAndNumericErrors_failFast_withoutAPublishedTable() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 1d, null), row(60, 3d, null)));
        assertEquals(U4SeriesToolArgs.ARGUMENT_MISSING, run(cid, null, "").path("reason").asText());
        assertEquals(RollingStats.STATISTIC_INVALID, run(cid, "median", "").path("reason").asText());
        assertEquals(U4SeriesToolArgs.ROLLING_KIND_INVALID,
                run(cid, "sum", ",\"rollingKind\":\"SLIDING\"").path("reason").asText());
        assertEquals(U4SeriesToolArgs.WINDOW_INVALID,
                run(cid, "sum", ",\"observationWindow\":0").path("reason").asText());
        assertEquals(CounterDeltaCacheRunner.COLUMN_NOT_FOUND, MAPPER.readTree(
                CachedTabularToolsExecutor.executeTabulateCachedResult(call("{\"cacheId\":\"" + cid
                        + "\",\"mode\":\"rolling_stats\",\"statistic\":\"sum\",\"timeColumn\":\"ts\","
                        + "\"valueColumn\":\"nope\"," + WINDOW + "}"))).path("reason").asText());

        JsonNode tooLarge = run(InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 1d, null), row(60, 2e150, null))), "sum", "");
        assertEquals("ERROR", tooLarge.path("status").asText());
        assertEquals(RollingStats.VALUE_MAGNITUDE_UNSUPPORTED, tooLarge.path("reason").asText());
        assertFalse(tooLarge.has("findingCacheId"));

        JsonNode fine = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"cacheId\":\"" + isoTable("2026-03-02T06:00:00.0001Z", "2026-03-02T06:00:00.0009Z")
                        + "\",\"mode\":\"rolling_stats\",\"statistic\":\"sum\",\"timeColumn\":\"ts\","
                        + "\"valueColumn\":\"v\"," + WINDOW + "}")));
        assertEquals(CounterDeltaCacheRunner.TIMESTAMP_UNSUPPORTED, fine.path("reason").asText(), fine.toString());
    }

    /** Deadline or interrupt during output creation: a typed failure and nothing becomes visible. */
    @Test
    void failureDuringOutputCreation_neverMakesTheArtifactVisible() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 1d, null), row(60, 3d, null)));
        cache.onNextCreates(() -> Thread.currentThread().interrupt());
        JsonNode out;
        try {
            out = run(cid, "sum", "");
        } finally {
            Thread.interrupted();
        }
        assertEquals("ERROR", out.path("status").asText(), out.toString());
        assertEquals(CounterDeltaCacheRunner.CANCELLED, out.path("reason").asText());
        assertFalse(out.path("mayPublish").asBoolean(true));
        assertEquals(0, cache.publicationsSinceArmed());

        Thread.currentThread().interrupt();
        JsonNode early;
        try {
            early = run(cid, "sum", "");
        } finally {
            Thread.interrupted();
        }
        assertEquals(CounterDeltaCacheRunner.CANCELLED, early.path("reason").asText(), "cancelled before reading");
    }

    /** All four warnings and the completeness reasons survive real last-resort compaction. */
    @Test
    void scopeAndCompleteness_surviveLastResortCompaction() throws Exception {
        InfoTable t = table(row(0, null, "A"), row(60, 3d, "A"), row(120, 5d, "A"), row(180, 9d, null));
        String cid = InvokeServiceExecutor.storePrimaryWithReadLimit(t, ReadLimitFact.observe(t, 4));
        JsonNode out = run(cid, "stddev", ",\"observationWindow\":2,\"entityColumn\":\"device\"");
        assertEquals(4, out.path("warnings").size(), out.toString());
        ObjectNode padded = ((ObjectNode) out).deepCopy();
        ObjectNode filler = padded.putObject("filler");
        for (int i = 0; i < 120; i++) {
            filler.put("field_" + i, "x".repeat(110));
        }
        JsonNode seen = MAPPER.readTree(ToolResultEgressGateway.compactForLlmAppend("tabulate_cached_result",
                "call-1", MAPPER.writeValueAsString(padded),
                LoggerFactory.getLogger(TabulateRollingStatsModeTest.class)).getLlmContent());
        boolean omitted = false;
        for (JsonNode marker : seen.path("_egress").path("reducedFields")) {
            omitted |= marker.path("omitted").asBoolean();
        }
        assertTrue(omitted, "only last-resort compaction omits whole fields: " + seen.path("_egress"));
        assertEquals(strings(out.path("warnings")), strings(seen.path("warnings")));
        assertEquals(List.of(ReadLimitFact.REASON), strings(seen.path("completeness").path("reasons")));
    }

    /**
     * Shared properties follow the enabled modes; {@code rolling}'s own description, result and status
     * behaviour do not change.
     */
    @Test
    void admissionCombinations_andTheExistingRollingMode_areUnchanged() throws Exception {
        String rollingOnly = "Optional for mode=rolling: minimum support (default 1).";
        String both = "Optional for mode=rolling / mode=rolling_stats: minimum support (default 1).";
        String statsOnly = "Optional for mode=rolling_stats: minimum support (default 1).";

        assertTrue(surface().contains(both));
        assertTrue(surface().contains("counter_delta / rolling_stats: column separating series."));

        ComputingOperationAdmission.setRollingStatsEnabled(false);
        String off = surface();
        assertTrue(off.contains(rollingOnly), "rolling's shared text is exactly what it was");
        assertFalse(off.contains("rolling_stats") || off.contains("\"statistic\""));
        assertTrue(off.contains("counter_delta: column separating series."));
        JsonNode refused = run(InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 1d, null), row(60, 3d, null))), "sum", "");
        assertEquals(ComputingOperationAdmission.ROLLING_STATS_UNAVAILABLE, refused.path("reason").asText());
        assertFalse(refused.path("mayPublish").asBoolean(true));

        ComputingOperationAdmission.resetForTests();
        U4OperationAdmission.setRollingEnabled(false);
        assertTrue(surface().contains(statsOnly), "the window props stay advertised for rolling_stats alone");

        ComputingOperationAdmission.setRollingStatsEnabled(false);
        assertFalse(surface().contains("minSupport"));
        ComputingOperationAdmission.setCounterDeltaEnabled(false);
        assertFalse(surface().contains("entityColumn"));

        // The existing mode still reports INSUFFICIENT_EVIDENCE for an UNKNOWN source: U4 is untouched.
        ComputingOperationAdmission.resetForTests();
        U4OperationAdmission.resetForTests();
        JsonNode legacy = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"cacheId\":\"" + InvokeServiceExecutor.storeInfotableInConversationCache(
                        table(row(0, 1d, null), row(60, 3d, null)))
                        + "\",\"mode\":\"rolling\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\"," + WINDOW + "}")));
        assertEquals("rolling", legacy.path("analysisEnvelope").path("operation").asText(), legacy.toString());
        assertEquals("INSUFFICIENT_EVIDENCE", legacy.path("analysisEnvelope").path("status").asText());
        assertFalse(legacy.has("warnings"));
    }

    /** CE-2 schema budget: the mode may add at most 700 characters to the model-visible tool schema. */
    @Test
    void schemaGrowthStaysInsideTheSliceBudget() throws Exception {
        for (String shape : new String[] {"openai-chat-completions-v1", "anthropic-messages-v1"}) {
            ComputingOperationAdmission.setRollingStatsEnabled(true);
            int on = schemaChars(shape);
            ComputingOperationAdmission.setRollingStatsEnabled(false);
            int off = schemaChars(shape);
            assertTrue(on > off && on - off <= 700, shape + " growth=" + (on - off));
        }
    }

    private static int schemaChars(String apiShape) throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        return ToolSchemaSizer.totalSchemaChars(apiShape, new ArrayList<>(reg.getAllDefinitions()));
    }

    private static String surface() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition tabulate = reg.getAllDefinitions().stream()
                .filter(d -> "tabulate_cached_result".equals(d.getName())).findFirst().orElseThrow();
        return tabulate.getDescription() + "\n" + MAPPER.writeValueAsString(tabulate.getParametersSchema());
    }

    private static JsonNode run(String cacheId, String statistic, String extraArgs) throws Exception {
        return MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call("{\"cacheId\":\""
                + cacheId + "\",\"mode\":\"rolling_stats\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\","
                + (statistic == null ? "" : "\"statistic\":\"" + statistic + "\",") + WINDOW + extraArgs + "}")));
    }

    /** entity|value|valueStatus|support|records|warmedUp per published row. */
    private static List<String> rowsOf(JsonNode result) throws Exception {
        InfoTable t = InvokeServiceExecutor.lookupCachedInfotable(result.path("findingCacheId").asText());
        assertNotNull(t, result.toString());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.getRowCount(); i++) {
            ValueCollection r = t.getRow(i);
            Object value = r.getPrimitive("value") == null ? null : r.getPrimitive("value").getValue();
            out.add(r.getStringValue("entity") + "|" + value + "|" + r.getStringValue("valueStatus") + "|"
                    + ((Number) r.getValue("support")).intValue() + "|" + ((Number) r.getValue("records")).intValue()
                    + "|" + r.getValue("warmedUp"));
        }
        return out;
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

    private static ToolCall call(String args) {
        return new ToolCall("1", "tabulate_cached_result", args);
    }

    private static Object[] row(long seconds, Double value, String device) {
        return new Object[] {seconds, value, device};
    }

    private static InfoTable table(Object[]... rows) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("ts", BaseTypes.DATETIME));
        shape.addFieldDefinition(field("v", BaseTypes.NUMBER));
        shape.addFieldDefinition(field("device", BaseTypes.STRING));
        InfoTable table = new InfoTable(shape);
        for (Object[] r : rows) {
            ValueCollection vc = new ValueCollection();
            vc.put("ts", new DatetimePrimitive(new DateTime(SHIFT_START.plusSeconds((Long) r[0]).toEpochMilli())));
            if (r[1] != null) {
                vc.put("v", new NumberPrimitive((Double) r[1]));
            }
            if (r[2] != null) {
                vc.put("device", new StringPrimitive((String) r[2]));
            }
            table.addRow(vc);
        }
        return table;
    }

    private static String isoTable(String t1, String t2) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("ts", BaseTypes.STRING));
        shape.addFieldDefinition(field("v", BaseTypes.NUMBER));
        InfoTable table = new InfoTable(shape);
        for (String t : new String[] {t1, t2}) {
            ValueCollection vc = new ValueCollection();
            vc.put("ts", new StringPrimitive(t));
            vc.put("v", new NumberPrimitive(1d));
            table.addRow(vc);
        }
        return InvokeServiceExecutor.storeInfotableInConversationCache(table);
    }

    private static FieldDefinition field(String name, BaseTypes type) {
        FieldDefinition f = new FieldDefinition();
        f.setName(name);
        f.setBaseType(type);
        return f;
    }
}
