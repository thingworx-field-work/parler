package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.source.ReadLimitFact;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.transform.time.CounterDelta;
import com.thingworx.things.agent.transform.time.CounterDeltaCacheRunner;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * CF-05 {@code tabulate_cached_result} {@code mode=counter_delta}: a real cached artifact through the real
 * schema, admission and dispatch to the published table, its descriptor, the envelope, and what the model
 * sees after egress compaction.
 */
class TabulateCounterDeltaModeTest {

    TabulateCounterDeltaModeTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The shared design case: analysis window 06:00-14:00, readings at 06:00 and 09:40. */
    private static final Instant SHIFT_START = Instant.parse("2026-03-02T06:00:00Z");
    private static final String WINDOW = "\"windowStart\":\"2026-03-02T06:00:00Z\",\"windowEnd\":\"2026-03-02T14:00:00Z\"";
    private static final String NO_RATE = "No maxRatePerSecond was given";

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("ce1-counter-delta");
        ComputingOperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        ComputingOperationAdmission.resetForTests();
    }

    @Test
    void unknownSource_succeedsWithScopeDisclosure_andPublishesTheSegmentTable() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 100d, null), row(60, 130d, null), row(120, 90d, null)));
        JsonNode out = run(cid, "");
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertEquals(CounterDeltaCacheRunner.OUTCOME_OK, out.path("reason").asText());
        assertTrue(out.path("mayPublish").asBoolean());

        JsonNode env = out.path("analysisEnvelope");
        assertEquals("SUCCESS", env.path("status").asText(), "UNKNOWN sources compute normally");
        assertEquals("counter_delta", env.path("operation").asText());
        assertEquals("UNKNOWN", env.path("evidence").path("completeness").asText());
        assertEquals(CounterDelta.METHOD_ID, env.path("method").path("id").asText());
        JsonNode m = env.path("metrics");
        assertEquals("30", m.path("knownDelta").asText());
        assertEquals("0", m.path("lowerBoundDelta").asText());
        assertEquals("observed_span", m.path("scope").asText());
        assertEquals("exact_integer", m.path("arithmetic").asText());
        assertEquals("none", m.path("assumptions").asText());
        assertEquals("1", m.path("segments.NORMAL").asText());
        assertEquals("1", m.path("segments.UNCERTAIN_NEGATIVE_JUMP").asText());

        assertEquals(strings(env.path("evidence").path("warnings")), strings(out.path("warnings")),
                "root warnings are the envelope's list");
        assertTrue(out.path("warnings").get(0).asText().startsWith(AnalysisEnvelopeValidator.SCOPE_OBSERVED_SPAN));
        assertEquals("UNKNOWN", out.path("completeness").path("status").asText());

        InfoTable published = InvokeServiceExecutor.lookupCachedInfotable(out.path("findingCacheId").asText());
        assertNotNull(published);
        assertEquals(2, published.getRowCount());
        assertEquals("NORMAL", published.getRow(0).getStringValue("classification"));
        assertEquals(30d, (Double) published.getRow(0).getValue("delta"), 0d);
        assertEquals("UNCERTAIN_NEGATIVE_JUMP", published.getRow(1).getStringValue("classification"));
        assertTrue(published.getRow(1).getPrimitive("delta") == null
                || published.getRow(1).getPrimitive("delta").getValue() == null, "no number for an unexplained jump");
        SourceDescriptor d = InvokeServiceExecutor.lookupSourceDescriptor(out.path("findingCacheId").asText());
        assertEquals(CounterDeltaCacheRunner.ROUTE_ID, d.sourceRouteId());
        assertEquals(List.of(cid), d.parentSourceCacheIds());
        assertEquals(CompletenessStatus.UNKNOWN, d.completenessStatus(), "never upgraded");
    }

    /** Design §3.2: the read-limit reason changes the explanation, never a classification, delta or total. */
    @Test
    void readLimitReason_changesOnlyTheScopeText() throws Exception {
        InfoTable marked = table(row(0, 600d, null), row(13_200, 820d, null));
        String withReason = InvokeServiceExecutor.storePrimaryWithReadLimit(marked, ReadLimitFact.observe(marked, 2));
        String without = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 600d, null), row(13_200, 820d, null)));
        JsonNode a = run(withReason, "");
        JsonNode b = run(without, "");
        for (JsonNode out : List.of(a, b)) {
            JsonNode m = out.path("analysisEnvelope").path("metrics");
            assertEquals("220", m.path("knownDelta").asText(), out.toString());
            assertEquals("0", m.path("uncoveredHeadSeconds").asText());
            assertEquals("15600", m.path("uncoveredTailSeconds").asText(), "09:40 to 14:00 is not covered");
            assertEquals("UNKNOWN", out.path("completeness").path("status").asText());
        }
        assertEquals(segmentsOf(a), segmentsOf(b), "identical output tables");
        assertEquals(List.of(ReadLimitFact.REASON), strings(a.path("completeness").path("reasons")));
        assertTrue(a.path("warnings").get(0).asText().contains("not a window or shift total"));
        assertEquals(List.of(ReadLimitFact.REASON),
                InvokeServiceExecutor.lookupSourceDescriptor(a.path("findingCacheId").asText()).completenessReasons(),
                "the derived table keeps the parent's reason");
        assertEquals(List.of(), strings(b.path("completeness").path("reasons")));
        assertFalse(b.path("warnings").get(0).asText().contains("not a window or shift total"));
    }

    @Test
    void lateFirstReading_leavesTheHeadUncovered_andPartialSourcesAreDisclosed() throws Exception {
        String cid = TabularArtifactHub.store(table(row(15_600, 600d, null), row(28_740, 820d, null)),
                SourceDescriptor.builder().completenessStatus(CompletenessStatus.PARTIAL).build());
        JsonNode out = run(cid, "");
        JsonNode m = out.path("analysisEnvelope").path("metrics");
        assertEquals("220", m.path("knownDelta").asText(), out.toString());
        assertEquals("15600", m.path("uncoveredHeadSeconds").asText());
        assertEquals("60", m.path("uncoveredTailSeconds").asText());
        assertEquals("SUCCESS", out.path("analysisEnvelope").path("status").asText());
        assertEquals("PARTIAL", out.path("completeness").path("status").asText());
        assertTrue(out.path("warnings").get(0).asText().contains("not a window or shift total"));
    }

    /** After union_rows the reason is a table-level fact, so the scope text covers every partition. */
    @Test
    void unionOfTwoDevices_onePastItsReadLimit_isComputedPerEntity() throws Exception {
        InfoTable limited = table(row(0, 600d, null), row(13_200, 820d, null));
        String a = InvokeServiceExecutor.storePrimaryWithReadLimit(limited, ReadLimitFact.observe(limited, 2));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 10d, null), row(28_000, 50d, null)));
        JsonNode union = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"mode\":\"union_rows\",\"sourceCacheIds\":[\"" + a + "\",\"" + b
                        + "\"],\"labelColumn\":\"src\",\"labelValues\":[\"M1\",\"M2\"]}")));
        assertEquals("success", union.path("status").asText(), union.toString());
        JsonNode out = run(union.path("cacheId").asText(), ",\"entityColumn\":\"src\"");
        JsonNode m = out.path("analysisEnvelope").path("metrics");
        assertEquals("2", m.path("partitions").asText(), out.toString());
        assertEquals("260", m.path("knownDelta").asText());
        assertFalse(m.has("firstReading"), "no table-wide first/last time across entities");
        assertEquals(List.of(ReadLimitFact.REASON), strings(out.path("completeness").path("reasons")));
        assertEquals(List.of("M1", "M2"), List.of(segmentsOf(out).get(0).split("\\|")[0],
                segmentsOf(out).get(1).split("\\|")[0]));
    }

    /**
     * Original integers through the real store, codec and stream path: 2^53+1 is cached as exactly 2^53.
     * The column is LONG-typed; {@code LongPrimitive} cannot load in the local unit-test classpath, so the
     * value is handed over through the same long-to-double conversion the codec applies to a LONG cell.
     */
    @Test
    void longReadingsRoundedByTheCache_areRejected_notReportedAsOne() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                longTable(9_007_199_254_740_991L, 9_007_199_254_740_993L));
        JsonNode out = run(cid, "");
        assertEquals("ERROR", out.path("status").asText(), out.toString());
        assertEquals(CounterDelta.DOMAIN_UNSUPPORTED, out.path("reason").asText());
        assertFalse(out.has("findingCacheId"));

        JsonNode exact = run(InvokeServiceExecutor.storeInfotableInConversationCache(
                longTable(9_007_199_254_740_000L, 9_007_199_254_740_991L)), "");
        assertEquals("991", exact.path("analysisEnvelope").path("metrics").path("knownDelta").asText());
    }

    @Test
    void conflictingReadings_breakContinuity_throughTheArtifactPath() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(table(
                row(0, 100d, null), row(30, 200d, null), row(30, 10d, null), row(60, 150d, null)));
        JsonNode out = run(cid, ",\"resetBaseline\":0");
        assertEquals(CounterDeltaCacheRunner.OUTCOME_NO_KNOWN_SEGMENT, out.path("reason").asText(), out.toString());
        assertEquals("INSUFFICIENT_EVIDENCE", out.path("analysisEnvelope").path("status").asText());
        List<String> segments = segmentsOf(out);
        assertEquals(2, segments.size());
        for (String s : segments) {
            assertTrue(s.contains("BROKEN_BY_CONFLICT") && s.endsWith("|null"), "no unqualified 50: " + s);
        }
        assertEquals("1", out.path("analysisEnvelope").path("metrics").path("conflictInstants").asText());

        // A null value is only a missing observation: its neighbours may still form a segment.
        JsonNode spanned = run(InvokeServiceExecutor.storeInfotableInConversationCache(table(
                row(0, 100d, null), row(30, null, null), row(60, 150d, null))), "");
        assertEquals("50", spanned.path("analysisEnvelope").path("metrics").path("knownDelta").asText());
        assertEquals("1", spanned.path("analysisEnvelope").path("metrics").path("rowsMissingValue").asText());
    }

    /** N2: without a rate bound the reset limitation is disclosed even when every segment is NORMAL. */
    @Test
    void noRateLimitation_reachesEnvelopeRootAndTheModel_evenWhenAllSegmentsAreNormal() throws Exception {
        for (String rule : new String[] {"", ",\"resetBaseline\":0"}) {
            String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                    table(row(0, 100d, null), row(60, 130d, null)));
            JsonNode out = run(cid, rule);
            JsonNode m = out.path("analysisEnvelope").path("metrics");
            assertEquals("30", m.path("knownDelta").asText(), out.toString());
            assertFalse(m.has("segments.RESET") || m.has("segments.POSSIBLE_HIDDEN_RESET"));
            assertTrue(containsText(out.path("analysisEnvelope").path("evidence").path("warnings"), NO_RATE), rule);
            assertTrue(containsText(out.path("warnings"), NO_RATE), rule);
            assertTrue(containsText(lastResortCompacted(out).path("warnings"), NO_RATE), rule);
        }
        JsonNode withRate = run(InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 100d, null), row(60, 130d, null))), ",\"maxRatePerSecond\":1");
        assertEquals("30", withRate.path("analysisEnvelope").path("metrics").path("knownDelta").asText());
        assertFalse(containsText(withRate.path("warnings"), NO_RATE), withRate.toString());
    }

    /** All four warnings and the completeness reasons survive real last-resort compaction. */
    @Test
    void scopeAndCompleteness_surviveLastResortCompaction() throws Exception {
        InfoTable t = table(row(0, 500d, "A"), row(60, 20d, "A"), row(120, 10d, "A"), row(10_000, 50d, "A"),
                row(180, 40d, null));
        String cid = InvokeServiceExecutor.storePrimaryWithReadLimit(t, ReadLimitFact.observe(t, 5));
        JsonNode out = run(cid, ",\"entityColumn\":\"device\",\"resetBaseline\":0,\"maxGapSeconds\":600");
        assertEquals(4, out.path("warnings").size(), out.toString());
        JsonNode seen = lastResortCompacted(out);
        assertEquals(strings(out.path("warnings")), strings(seen.path("warnings")), "whole sentences, all four");
        assertEquals("UNKNOWN", seen.path("completeness").path("status").asText());
        assertEquals(List.of(ReadLimitFact.REASON), strings(seen.path("completeness").path("reasons")));
    }

    @Test
    void admissionOff_withdrawsTheModeFromTheSchema_andRejectsDirectCalls() throws Exception {
        assertTrue(TabulateCachedResultToolSchema.advertisedModes().contains("counter_delta"));
        assertTrue(advertisedSurface().contains("counterModulus"));
        ComputingOperationAdmission.setCounterDeltaEnabled(false);
        assertFalse(TabulateCachedResultToolSchema.advertisedModes().contains("counter_delta"));
        String surface = advertisedSurface();
        for (String token : new String[] {"counter_delta", "counterModulus", "maxRatePerSecond", "resetBaseline"}) {
            assertFalse(Pattern.compile("\\b" + token + "\\b").matcher(surface).find(), token);
        }
        // entityColumn is shared with rolling_stats: it stays while that mode is on, and goes with both.
        assertTrue(surface.contains("entityColumn"));
        ComputingOperationAdmission.setRollingStatsEnabled(false);
        assertFalse(advertisedSurface().contains("entityColumn"));
        // maxGapSeconds is shared with time_weighted in the same way.
        assertTrue(advertisedSurface().contains("maxGapSeconds"));
        ComputingOperationAdmission.setTimeWeightedEnabled(false);
        assertFalse(advertisedSurface().contains("maxGapSeconds"));
        JsonNode out = run(InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 100d, null), row(60, 130d, null))), "");
        assertEquals("ERROR", out.path("status").asText());
        assertEquals(ComputingOperationAdmission.COUNTER_DELTA_UNAVAILABLE, out.path("reason").asText());
        assertFalse(out.path("mayPublish").asBoolean(true));
    }

    /** I1: a cancelled operation returns no success and publishes nothing; the interrupt flag stays set. */
    @Test
    void interruptedThread_isCancelled_withoutAPublishedTable() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 100d, null), row(60, 130d, null)));
        Thread.currentThread().interrupt();
        JsonNode out;
        boolean stillInterrupted;
        try {
            out = run(cid, "");
        } finally {
            stillInterrupted = Thread.interrupted();
        }
        assertTrue(stillInterrupted, "cancellation belongs to the caller; the flag is not swallowed");
        assertEquals("ERROR", out.path("status").asText(), out.toString());
        assertEquals(CounterDeltaCacheRunner.CANCELLED, out.path("reason").asText());
        assertFalse(out.has("findingCacheId"));
        assertFalse(out.path("mayPublish").asBoolean(true));
    }

    /** I1, publication boundary, through the real dispatcher: an interrupt during output creation. */
    @Test
    void interruptDuringOutputCreation_returnsCancelled_andNothingBecomesVisible() throws Exception {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.HookedCache cache =
                com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 100d, null), row(60, 130d, null)));
        cache.onNextCreates(() -> Thread.currentThread().interrupt());
        JsonNode out;
        try {
            out = run(cid, "");
        } finally {
            Thread.interrupted();
        }
        assertEquals("ERROR", out.path("status").asText(), out.toString());
        assertEquals(CounterDeltaCacheRunner.CANCELLED, out.path("reason").asText());
        assertFalse(out.path("mayPublish").asBoolean(true));
        assertFalse(out.has("findingCacheId"));
        assertEquals(0, cache.publicationsSinceArmed(), "no derived artifact became visible");
    }

    @Test
    void envelopeCarriesRequestedEffectiveAndConsumedBudgetFacts() throws Exception {
        JsonNode budget = run(InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 100d, null), row(60, 130d, null))), "").path("analysisEnvelope").path("budget");
        assertEquals(2, budget.path("consumed").path("rows").asInt(), budget.toString());
        assertTrue(budget.path("consumed").path("bytes").asLong() > 0L);
        assertTrue(budget.path("effective").path("maxWallTimeMillis").asLong() > 0L);
        assertEquals(budget.path("requested"), budget.path("effective"));
    }

    /** I2: the published endpoints are the instants the delta was computed from. */
    @Test
    void millisecondTimestamps_roundTripThroughTheSegmentTable_andFinerOnesAreRefused() throws Exception {
        String wide = "\"windowStart\":\"1600-01-01T00:00:00Z\",\"windowEnd\":\"2100-01-01T00:00:00Z\"";
        JsonNode ok = runIso(isoTable("2026-01-01T00:00:00.001Z", 100d, "2026-01-01T00:00:00.009Z", 101d), wide, "");
        assertEquals("1", ok.path("analysisEnvelope").path("metrics").path("knownDelta").asText(), ok.toString());
        InfoTable published = InvokeServiceExecutor.lookupCachedInfotable(ok.path("findingCacheId").asText());
        assertEquals(Instant.parse("2026-01-01T00:00:00.001Z").toEpochMilli(),
                ((DateTime) published.getRow(0).getValue("segmentStart")).getMillis());
        assertEquals(Instant.parse("2026-01-01T00:00:00.009Z").toEpochMilli(),
                ((DateTime) published.getRow(0).getValue("segmentEnd")).getMillis());
        assertEquals(0.008d, (Double) published.getRow(0).getValue("elapsedSeconds"), 1e-12);

        JsonNode fine = runIso(isoTable("2026-01-01T00:00:00.0001Z", 100d, "2026-01-01T00:00:00.0009Z", 101d), wide, "");
        assertEquals("ERROR", fine.path("status").asText(), fine.toString());
        assertEquals(CounterDeltaCacheRunner.TIMESTAMP_UNSUPPORTED, fine.path("reason").asText());
        assertFalse(fine.has("findingCacheId"));

        // More than 292 years apart: the gap rule decides, not a long overflow.
        JsonNode longGap = runIso(isoTable("1700-01-02T00:00:00Z", 100d, "2026-01-01T00:00:00Z", 130d), wide,
                ",\"maxGapSeconds\":60");
        assertEquals(CounterDeltaCacheRunner.OUTCOME_NO_KNOWN_SEGMENT, longGap.path("reason").asText(),
                longGap.toString());
        assertEquals("1", longGap.path("analysisEnvelope").path("metrics").path("segments.GAP_EXCEEDED").asText());
    }

    /** I3: the cap is checked on the JSON number as written, before it narrows to a double. */
    @Test
    void ruleValuesAreRangeCheckedBeforeNarrowing() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 100d, null), row(60, 130d, null)));
        for (String accepted : new String[] {"9007199254740991", "9007199254740992"}) {
            JsonNode out = run(cid, ",\"counterModulus\":" + accepted + ",\"maxRatePerSecond\":1");
            assertEquals("OK", out.path("status").asText(), accepted + " " + out);
        }
        for (String refused : new String[] {
                ",\"counterModulus\":9007199254740993,\"maxRatePerSecond\":1",
                ",\"counterModulus\":9007199254740992.0,\"maxRatePerSecond\":1",
                ",\"maxRatePerSecond\":9007199254740993",
                ",\"resetBaseline\":9007199254740993",
                ",\"maxGapSeconds\":9007199254740993",
                ",\"maxGapSeconds\":0"}) {
            JsonNode out = run(cid, refused);
            assertEquals("ERROR", out.path("status").asText(), refused + " " + out);
            assertEquals(CounterDelta.RULE_INVALID, out.path("reason").asText(), refused);
            assertFalse(out.has("findingCacheId"), refused);
        }
        assertEquals("OK", run(cid, ",\"maxGapSeconds\":9007199254740992").path("status").asText());
    }

    /** I4 / contract §6.3: the rate bounds comparisons only; modulus and baseline take part in subtraction. */
    @Test
    void arithmeticRegime_ignoresTheRate_butNotModulusOrBaseline() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 100d, null), row(60, 130d, null)));
        assertEquals("exact_integer", run(cid, ",\"maxRatePerSecond\":0.5")
                .path("analysisEnvelope").path("metrics").path("arithmetic").asText());
        assertEquals("float64", run(cid, ",\"resetBaseline\":0.5")
                .path("analysisEnvelope").path("metrics").path("arithmetic").asText());
        assertEquals("float64", run(cid, ",\"counterModulus\":1000.5,\"maxRatePerSecond\":1")
                .path("analysisEnvelope").path("metrics").path("arithmetic").asText());
    }

    private static JsonNode runIso(String cacheId, String window, String extraArgs) throws Exception {
        return MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call("{\"cacheId\":\"" + cacheId
                + "\",\"mode\":\"counter_delta\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\"," + window
                + extraArgs + "}")));
    }

    /** A STRING time column, as the numeric-history cache writes it: the only path to sub-millisecond text. */
    private static String isoTable(String t1, double v1, String t2, double v2) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("ts", BaseTypes.STRING));
        shape.addFieldDefinition(field("v", BaseTypes.NUMBER));
        InfoTable table = new InfoTable(shape);
        Object[][] rows = {{t1, v1}, {t2, v2}};
        for (Object[] r : rows) {
            ValueCollection vc = new ValueCollection();
            vc.put("ts", new StringPrimitive((String) r[0]));
            vc.put("v", new NumberPrimitive((Double) r[1]));
            table.addRow(vc);
        }
        return InvokeServiceExecutor.storeInfotableInConversationCache(table);
    }

    /** CE-1 schema budget: the mode may add at most 1,000 characters to the model-visible tool schema. */
    @Test
    void schemaGrowthStaysInsideTheSliceBudget() throws Exception {
        for (String shape : new String[] {"openai-chat-completions-v1", "anthropic-messages-v1"}) {
            ComputingOperationAdmission.setCounterDeltaEnabled(true);
            int on = schemaChars(shape);
            ComputingOperationAdmission.setCounterDeltaEnabled(false);
            int off = schemaChars(shape);
            assertTrue(on > off && on - off <= 1_000, shape + " growth=" + (on - off));
        }
    }

    private static int schemaChars(String apiShape) throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        return com.thingworx.things.agent.llm.ToolSchemaSizer.totalSchemaChars(apiShape,
                new ArrayList<>(reg.getAllDefinitions()));
    }

    @Test
    void argumentAndRuleErrors_failFast() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                table(row(0, 100d, null), row(60, 130d, null)));
        assertEquals(U4SeriesToolArgs.ARGUMENT_MISSING, MAPPER.readTree(
                CachedTabularToolsExecutor.executeTabulateCachedResult(call("{\"cacheId\":\"" + cid
                        + "\",\"mode\":\"counter_delta\",\"timeColumn\":\"ts\"," + WINDOW + "}")))
                .path("reason").asText(), "valueColumn is required for this mode");
        assertEquals(CounterDeltaCacheRunner.COLUMN_NOT_FOUND, MAPPER.readTree(
                CachedTabularToolsExecutor.executeTabulateCachedResult(call("{\"cacheId\":\"" + cid
                        + "\",\"mode\":\"counter_delta\",\"timeColumn\":\"ts\",\"valueColumn\":\"nope\"," + WINDOW
                        + "}"))).path("reason").asText());
        assertEquals(CounterDelta.RULE_INVALID, run(cid, ",\"counterModulus\":100").path("reason").asText());
        assertEquals(CounterDelta.RULE_INVALID,
                run(cid, ",\"counterModulus\":1000,\"maxRatePerSecond\":1,\"resetBaseline\":0").path("reason").asText());
        assertEquals(CounterDelta.RULE_INVALID, run(cid, ",\"maxRatePerSecond\":\"fast\"").path("reason").asText());
        assertEquals(CounterDelta.RULE_INVALID, run(cid, ",\"maxGapSeconds\":1.5").path("reason").asText());
        assertEquals(CounterDelta.RULE_CONTRADICTED,
                run(cid, ",\"counterModulus\":120,\"maxRatePerSecond\":1").path("reason").asText());
    }

    @Test
    void noReadingsInTheWindow_isInsufficientEvidence_withoutAPublishedTable() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(table(row(-7_200, 100d, null)));
        JsonNode out = run(cid, "");
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertEquals(CounterDeltaCacheRunner.OUTCOME_NO_READINGS, out.path("reason").asText());
        assertEquals("INSUFFICIENT_EVIDENCE", out.path("analysisEnvelope").path("status").asText());
        assertFalse(out.has("findingCacheId"));
        assertFalse(out.path("mayPublish").asBoolean(true));
    }

    private static JsonNode run(String cacheId, String extraArgs) throws Exception {
        return MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call("{\"cacheId\":\"" + cacheId
                + "\",\"mode\":\"counter_delta\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\"," + WINDOW + extraArgs
                + "}")));
    }

    /**
     * A counter_delta result is far below the egress soft cap, so last resort is forced the way the
     * upstream read-limit tests force it: by a large ordinary field. Priority fields must survive it.
     */
    private static JsonNode lastResortCompacted(JsonNode result) throws Exception {
        ObjectNode padded = ((ObjectNode) result).deepCopy();
        ObjectNode filler = padded.putObject("filler");
        for (int i = 0; i < 120; i++) {
            filler.put("field_" + i, "x".repeat(110));
        }
        ToolResultEgressGateway.EgressResult egress = ToolResultEgressGateway.compactForLlmAppend(
                "tabulate_cached_result", "call-1", MAPPER.writeValueAsString(padded),
                LoggerFactory.getLogger(TabulateCounterDeltaModeTest.class));
        JsonNode seen = MAPPER.readTree(egress.getLlmContent());
        boolean omittedSomething = false;
        for (JsonNode marker : seen.path("_egress").path("reducedFields")) {
            omittedSomething |= marker.path("omitted").asBoolean();
        }
        assertTrue(omittedSomething, "only last-resort compaction omits whole fields: " + seen.path("_egress"));
        return seen;
    }

    private static List<String> segmentsOf(JsonNode result) throws Exception {
        InfoTable t = InvokeServiceExecutor.lookupCachedInfotable(result.path("findingCacheId").asText());
        assertNotNull(t, result.toString());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.getRowCount(); i++) {
            ValueCollection r = t.getRow(i);
            Object delta = r.getPrimitive("delta") == null ? null : r.getPrimitive("delta").getValue();
            out.add(r.getStringValue("entity") + "|" + r.getValue("segmentStart") + "|" + r.getValue("segmentEnd")
                    + "|" + r.getStringValue("classification") + "|" + delta);
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

    private static String advertisedSurface() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition tabulate = reg.getAllDefinitions().stream()
                .filter(d -> "tabulate_cached_result".equals(d.getName())).findFirst().orElseThrow();
        return tabulate.getDescription() + "\n" + MAPPER.writeValueAsString(tabulate.getParametersSchema());
    }

    private static ToolCall call(String args) {
        return new ToolCall("1", "tabulate_cached_result", args);
    }

    private static Object[] row(long secondsFromShiftStart, Double value, String device) {
        return new Object[] {secondsFromShiftStart, value, device};
    }

    /** Columns ts (DATETIME), v (NUMBER) and device (STRING); a null device stays null. */
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

    private static InfoTable longTable(long first, long second) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("ts", BaseTypes.DATETIME));
        shape.addFieldDefinition(field("v", BaseTypes.LONG));
        InfoTable table = new InfoTable(shape);
        long[] values = {first, second};
        for (int i = 0; i < values.length; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("ts", new DatetimePrimitive(new DateTime(SHIFT_START.plusSeconds(i * 60L).toEpochMilli())));
            vc.put("v", new NumberPrimitive((double) values[i]));
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
}
