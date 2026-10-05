package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.HistoryOverlayChartBuilder;
import com.thingworx.things.agent.HistorySeriesComposerSupport;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.source.ReadLimitFact;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptorSupport;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * TR-0 (design tabular-reach 7.10 and its frozen entry): a platform read that returns at least as many rows as its
 * effective limit says so on the tool result and on the table it caches. The observation is weak on purpose: it
 * never changes a status, it is stated by the reader only, and nothing is read twice.
 */
class ReadLimitMarkingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    ReadLimitMarkingTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static InfoTable rows(int n) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (String[] f : new String[][] {{"timestamp", "DATETIME"}, {"speed", "NUMBER"}, {"state", "STRING"}}) {
            FieldDefinition fd = new FieldDefinition();
            fd.setName(f[0]);
            fd.setBaseType(BaseTypes.valueOf(f[1]));
            shape.addFieldDefinition(fd);
        }
        InfoTable t = new InfoTable(shape);
        for (int i = 0; i < n; i++) {
            ValueCollection r = new ValueCollection();
            r.put("timestamp", new DatetimePrimitive(new org.joda.time.DateTime(1_790_000_000_000L + i * 1000L)));
            r.put("speed", new NumberPrimitive(i % 7));
            r.put("state", new StringPrimitive(i % 2 == 0 ? "Running" : "Down"));
            t.addRow(r);
        }
        return t;
    }

    private static List<String> reasonsOf(String cacheId) {
        SourceDescriptor d = InvokeServiceExecutor.lookupSourceDescriptor(cacheId);
        assertNotNull(d, cacheId);
        return d.completenessReasons();
    }

    /** Effective limits, not only 5000: the history default, a small caller limit, the hard cap, and "unknown". */
    @ParameterizedTest
    @CsvSource({"999,1000,false", "1000,1000,true", "1001,1000,true", "6,7,false", "7,7,true", "4999,5000,false",
            "5000,5000,true", "5002,5000,true", "500,500,true", "12,0,false", "12,-1,false"})
    void observe_firesAtOrAboveTheEffectiveLimitOfThatRead(int returned, int limit, boolean expected) throws Exception {
        ReadLimitFact fact = ReadLimitFact.observe(rows(returned), limit);
        assertEquals(expected, fact.reached());
        if (expected) {
            assertEquals(returned, fact.returnedRows());
            assertEquals(limit, fact.effectiveLimit());
            assertTrue(fact.note().contains("may be incomplete") && fact.note().contains("reaching or exceeding"),
                    "the wording claims an observation, not truncation: " + fact.note());
            assertFalse(fact.note().toLowerCase().contains("truncated"));
        }
        assertFalse(ReadLimitFact.observe(null, limit).reached());
    }

    @Test
    void descriptorDecoration_addsOnlyTheReason_andKeepsStatusCountsRolesAndIdentity() throws Exception {
        InfoTable t = rows(30);
        SourceDescriptor base = SourceDescriptorSupport.withSubjectIdentity(
                SourceDescriptorSupport.withColumnRoles(SourceDescriptorSupport.forPrimaryStore(t, "query_stream_data"),
                        "timestamp", "speed", List.of("timestamp", "speed", "state")),
                "Pump-01", "speed");
        SourceDescriptor marked = SourceDescriptorSupport.withReadLimitFact(base, ReadLimitFact.observe(t, 30));
        assertEquals(List.of(ReadLimitFact.REASON), marked.completenessReasons());
        assertEquals(SourceDescriptor.CompletenessStatus.UNKNOWN, marked.completenessStatus(), "a reason, never a status");
        assertEquals(base.rowsReturned(), marked.rowsReturned());
        assertEquals(base.rowsExamined(), marked.rowsExamined());
        assertEquals(base.rowsAvailable(), marked.rowsAvailable());
        assertEquals(base.sourceRouteId(), marked.sourceRouteId());
        assertEquals(base.subjectThingName(), marked.subjectThingName());
        assertEquals(base.subjectPropertyName(), marked.subjectPropertyName());
        assertEquals("timestamp", marked.timeColumn());
        assertEquals("speed", marked.valueColumn());
        assertEquals(List.of(ReadLimitFact.REASON),
                SourceDescriptorSupport.withReadLimitFact(marked, ReadLimitFact.observe(t, 30)).completenessReasons(),
                "not duplicated");
        assertTrue(base == SourceDescriptorSupport.withReadLimitFact(base, ReadLimitFact.none()));
        assertTrue(base == SourceDescriptorSupport.withReadLimitFact(base, ReadLimitFact.observe(t, 31)));
        assertTrue(base == SourceDescriptorSupport.withReadLimitFact(base, null));
    }

    @Test
    void streamResult_aboveTheInlineThreshold_hintAndCachedDescriptorAgree() throws Exception {
        AgentToolContext.setConversationId("tr0-stream-large");
        InfoTable t = rows(30);
        ObjectNode extras = MAPPER.createObjectNode().put("maxItemsRequested", 30).put("maxItemsEffective", 30);
        JsonNode out = MAPPER.readTree(InvokeServiceExecutor.formatBuiltinInfotableResult(t, extras,
                ReadLimitFact.observe(t, 30)));
        assertEquals("INFOTABLE_LARGE", out.path("resultKind").asText(), out.toString());
        assertTrue(out.path(ReadLimitFact.FIELD_REACHED).asBoolean());
        assertTrue(out.path(ReadLimitFact.FIELD_NOTE).asText().contains("30"));
        assertEquals(List.of(ReadLimitFact.REASON), reasonsOf(out.path("cacheId").asText()));
        assertEquals(SourceDescriptor.CompletenessStatus.UNKNOWN,
                InvokeServiceExecutor.lookupSourceDescriptor(out.path("cacheId").asText()).completenessStatus());
    }

    @Test
    void streamResult_inline_getsTheHintOnly_andIsNotCachedForTheSakeOfTheMark() throws Exception {
        AgentToolContext.setConversationId("tr0-stream-inline");
        InfoTable t = rows(7);
        JsonNode out = MAPPER.readTree(InvokeServiceExecutor.formatBuiltinInfotableResult(t,
                MAPPER.createObjectNode().put("maxItemsEffective", 7), ReadLimitFact.observe(t, 7)));
        assertEquals("INFOTABLE", out.path("resultKind").asText(), out.toString());
        assertFalse(out.has("cacheId"), "an inline result stays uncached");
        assertTrue(out.path(ReadLimitFact.FIELD_REACHED).asBoolean());
    }

    @Test
    void nonNumericHistory_storesTheReasonInTheSameCallThatCachesTheTable() throws Exception {
        AgentToolContext.setConversationId("tr0-valuestream");
        InfoTable t = rows(1000);
        JsonNode marked = MAPPER.readTree(InvokeServiceExecutor.formatPropertyHistoryValueStreamCompact(t, null,
                MAPPER.createObjectNode().put("maxItemsEffective", 1000), 20, ReadLimitFact.observe(t, 1000)));
        assertTrue(marked.path(ReadLimitFact.FIELD_REACHED).asBoolean(), marked.toString());
        assertEquals(List.of(ReadLimitFact.REASON), reasonsOf(marked.path("cacheId").asText()));

        InfoTable under = rows(999);
        JsonNode plain = MAPPER.readTree(InvokeServiceExecutor.formatPropertyHistoryValueStreamCompact(under, null,
                MAPPER.createObjectNode().put("maxItemsEffective", 1000), 20, ReadLimitFact.observe(under, 1000)));
        assertFalse(plain.has(ReadLimitFact.FIELD_REACHED));
        assertFalse(plain.has(ReadLimitFact.FIELD_NOTE));
        assertEquals(List.of(), reasonsOf(plain.path("cacheId").asText()), "no reason is not a claim of completeness");
        assertEquals(SourceDescriptor.CompletenessStatus.UNKNOWN,
                InvokeServiceExecutor.lookupSourceDescriptor(plain.path("cacheId").asText()).completenessStatus());
    }

    @Test
    void resultsWithoutTheFact_areByteForByteWhatTheOldOverloadsProduce() throws Exception {
        AgentToolContext.setConversationId("tr0-unchanged");
        InfoTable small = rows(5);
        ObjectNode extras = MAPPER.createObjectNode().put("tool", "query_stream_data").put("maxItemsEffective", 500);
        assertEquals(InvokeServiceExecutor.formatBuiltinInfotableResult(small, extras),
                InvokeServiceExecutor.formatBuiltinInfotableResult(small, extras, ReadLimitFact.observe(small, 500)));
        assertEquals(InvokeServiceExecutor.formatBuiltinInfotableResult(small, extras),
                InvokeServiceExecutor.formatBuiltinInfotableResult(small, extras, null));
    }

    @Test
    void numericHistory_compactResultCarriesTheHint_andPointsTruncatedKeepsItsMeaning() throws Exception {
        ObjectNode full = MAPPER.createObjectNode();
        full.put("status", "success");
        full.put("pointsTruncated", true);
        full.put("maxItemsRequested", 1000).put("maxItemsEffective", 1000);
        InvokeServiceExecutor.putReadLimitEvidence(full, ReadLimitFact.observe(rows(1000), 1000));
        List<ObjectNode> points = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            points.add(MAPPER.createObjectNode().put("timestamp", "2026-09-01T00:00:" + String.format("%02d", i) + "Z")
                    .put("value", i));
        }
        ObjectNode compact = PropertyToolsExecutor.compactNumericHistoryResult(full, points, List.of(),
                new StoredSeriesCache("c-1", NumericHistoryCacheWriter.canonicalColumns(), "timestamp", "value"),
                true, true);
        assertTrue(compact.path("pointsTruncated").asBoolean());
        assertTrue(compact.path(ReadLimitFact.FIELD_REACHED).asBoolean(), compact.toString());
        assertTrue(compact.path(ReadLimitFact.FIELD_NOTE).asText().contains("1000"));

        ObjectNode under = MAPPER.createObjectNode().put("status", "success").put("pointsTruncated", false);
        ObjectNode compactUnder = PropertyToolsExecutor.compactNumericHistoryResult(under, points, List.of(),
                new StoredSeriesCache("c-2", NumericHistoryCacheWriter.canonicalColumns(), "timestamp", "value"),
                false, true);
        assertFalse(compactUnder.has(ReadLimitFact.FIELD_REACHED));
    }

    @Test
    void numericHistory_cacheWriteKeepsRolesAndIdentity_andAddsTheReason() throws Exception {
        AgentToolContext.setConversationId("tr0-numeric-cache");
        List<ObjectNode> points = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            points.add(MAPPER.createObjectNode().put("timestamp", "2026-09-01T00:00:" + String.format("%02d", i) + "Z")
                    .put("value", i));
        }
        StoredSeriesCache marked = PropertyToolsExecutor.cacheNumericHistorySeries(points, null, "speed",
                ReadLimitFact.observe(rows(1000), 1000));
        SourceDescriptor d = InvokeServiceExecutor.lookupSourceDescriptor(marked.cacheId());
        assertEquals(List.of(ReadLimitFact.REASON), d.completenessReasons());
        assertEquals("timestamp", marked.timeColumn());
        assertEquals("timestamp", d.timeColumn(), "roles survive the decoration");
        assertEquals("value", d.valueColumn());
        StoredSeriesCache plain = PropertyToolsExecutor.cacheNumericHistorySeries(points, null, "speed",
                ReadLimitFact.none());
        assertEquals(List.of(), reasonsOf(plain.cacheId()));
    }

    /** The overlay filters invalid rows before it caches: the fact is taken from the platform's table, per series. */
    @Test
    void overlay_factIsTakenBeforeFiltering_andStaysWithItsOwnSeries() throws Exception {
        AgentToolContext.setConversationId("tr0-overlay");
        int limit = HistorySeriesComposerSupport.MAX_HISTORY_ROWS;
        InfoTable history = rows(limit);
        history.getRow(17).put("speed", new NumberPrimitive(Double.NaN));
        ReadLimitFact fact = ReadLimitFact.observe(history, limit);
        List<HistorySeriesComposerSupport.HistoryPoint> kept =
                PropertyToolsExecutor.extractPopNumericHistoryPoints(history, "speed").points;
        assertEquals(limit - 1, kept.size(), "one invalid value was dropped after the read");
        assertTrue(fact.reached(), "the read itself reached its limit");

        List<HistorySeriesComposerSupport.HistoryPoint> few = List.of(
                new HistorySeriesComposerSupport.HistoryPoint(Instant.parse("2026-09-01T00:00:00Z"), 1.0),
                new HistorySeriesComposerSupport.HistoryPoint(Instant.parse("2026-09-01T00:01:00Z"), 2.0));
        List<HistoryOverlayChartBuilder.ResolvedSeriesInput> series = List.of(
                new HistoryOverlayChartBuilder.ResolvedSeriesInput("Pump-01", "Pump-01", "speed", null, null, "UTC",
                        kept, fact),
                new HistoryOverlayChartBuilder.ResolvedSeriesInput("Pump-02", "Pump-02", "speed", null, null, "UTC",
                        few, ReadLimitFact.observe(rows(2), limit)));
        JSONArray caches = BuildHistoryOverlayChartExecutor.publishSeriesCaches(series);
        assertEquals(2, caches.length());
        JSONObject first = caches.getJSONObject(0);
        JSONObject second = caches.getJSONObject(1);
        assertTrue(first.optBoolean(ReadLimitFact.FIELD_REACHED), first.toString());
        assertFalse(second.has(ReadLimitFact.FIELD_REACHED), "only the series whose own read reached the limit");
        assertEquals(List.of(ReadLimitFact.REASON), reasonsOf(first.getString("cacheId")));
        assertEquals(List.of(), reasonsOf(second.getString("cacheId")));
        assertEquals(limit - 1, first.getInt("totalRows"), "fewer cached points than the limit, still marked");

        JSONObject ok = new JSONObject();
        BuildHistoryOverlayChartExecutor.putReadLimitEvidence(ok, series);
        assertEquals(limit, ok.getInt("maxItemsRequested"));
        assertEquals(limit, ok.getInt("maxItemsEffective"));
        assertTrue(ok.getBoolean(ReadLimitFact.FIELD_REACHED));
        assertEquals("[\"Pump-01\"]", ok.getJSONArray("readLimitReachedSeries").toString());
        String overlayNote = ok.getString(ReadLimitFact.FIELD_NOTE);
        assertEquals(ReadLimitFact.seriesNote(limit), overlayNote);
        assertTrue(overlayNote.contains("may be incomplete") && overlayNote.contains(String.valueOf(limit)));
        assertFalse(overlayNote.contains("Pump-01"), "series are named in their own field, so the note is bounded");
        assertFalse(overlayNote.toLowerCase(java.util.Locale.ROOT).contains("truncated"));

        JSONObject none = new JSONObject();
        BuildHistoryOverlayChartExecutor.putReadLimitEvidence(none, series.subList(1, 2));
        assertFalse(none.has(ReadLimitFact.FIELD_REACHED));
        assertEquals(limit, none.getInt("maxItemsEffective"), "the echo is always there");
    }

    private static String tabulate(String argsJson) {
        return CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t", "tabulate_cached_result", argsJson));
    }

    @Test
    void derivations_keepTheReason_unionInBothOrders_andStatusStaysUnknown() throws Exception {
        AgentToolContext.setConversationId("tr0-derive");
        InfoTable t = rows(30);
        String marked = InvokeServiceExecutor.storePrimaryWithReadLimit(t, ReadLimitFact.observe(t, 30));
        String plain = InvokeServiceExecutor.storeInfotableInConversationCache(rows(30));
        for (String[] order : new String[][] {{marked, plain}, {plain, marked}}) {
            JsonNode out = MAPPER.readTree(tabulate("{\"mode\":\"union_rows\",\"sourceCacheIds\":[\"" + order[0]
                    + "\",\"" + order[1] + "\"],\"labelColumn\":\"src\",\"labelValues\":[\"a\",\"b\"]}"));
            assertEquals("success", out.path("status").asText(), out.toString());
            assertEquals(List.of(ReadLimitFact.REASON), reasonsOf(out.path("cacheId").asText()),
                    "the mark survives whichever input carries it");
            assertEquals("UNKNOWN", out.path("completeness").path("status").asText());
            assertEquals(ReadLimitFact.REASON, out.path("completeness").path("reasons").get(0).asText());
            assertFalse(out.path("counts").has("totalAvailable"));
        }
        JsonNode both = MAPPER.readTree(tabulate("{\"mode\":\"union_rows\",\"sourceCacheIds\":[\"" + marked + "\",\""
                + marked + "\"],\"labelColumn\":\"src\"}"));
        assertEquals(1, both.path("completeness").path("reasons").size(), "no duplicates");

        for (String mode : new String[] {"\"mode\":\"bin_numeric\",\"column\":\"speed\",\"binCount\":3",
                "\"mode\":\"box_summary\",\"column\":\"speed\",\"groupBy\":\"state\""}) {
            JsonNode out = MAPPER.readTree(tabulate("{" + mode + ",\"cacheId\":\"" + marked + "\"}"));
            assertEquals("success", out.path("status").asText(), out.toString());
            assertEquals(ReadLimitFact.REASON, out.path("completeness").path("reasons").get(0).asText(), mode);
            assertEquals("UNKNOWN", out.path("completeness").path("status").asText());
            assertEquals(List.of(ReadLimitFact.REASON), reasonsOf(out.path("cacheId").asText()));
        }
    }

    /** Paths that state no read-limit fact keep their semantics, whatever their size. */
    @Test
    void tablesThatStateNoFact_areNeverMarkedBySize() throws Exception {
        AgentToolContext.setConversationId("tr0-unmarked");
        assertEquals(List.of(), reasonsOf(InvokeServiceExecutor.storeInfotableInConversationCache(rows(5000))));
        JsonNode service = MAPPER.readTree(InvokeServiceExecutor.formatBuiltinInfotableResult(rows(5000), null));
        assertFalse(service.has(ReadLimitFact.FIELD_REACHED));
        assertEquals(List.of(), reasonsOf(service.path("cacheId").asText()));

        AgentToolContext.resetTabularChartRound();
        StringBuilder jsonRows = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            jsonRows.append(i == 0 ? "" : ",").append("{\"state\":\"s").append(i).append("\",\"value\":").append(i).append('}');
        }
        ObjectNode env = MAPPER.createObjectNode().put("status", "success").put("resultKind", "JSON");
        env.put("result", "{\"status\":\"success\",\"hasMore\":false,\"rows\":[" + jsonRows + "]}");
        JsonNode promoted = MAPPER.readTree(TabularChartRoundHooks.afterBuiltInToolResult("invoke_service",
                MAPPER.writeValueAsString(env)));
        assertEquals(List.of(), reasonsOf(promoted.path("cacheId").asText()), "a promoted JSON table is untouched");
        assertFalse(promoted.has(ReadLimitFact.FIELD_REACHED));
    }

    @Test
    void theHintSurvivesEgressCompaction() throws Exception {
        AgentToolContext.setConversationId("tr0-egress");
        InfoTable t = rows(600);
        String stream = InvokeServiceExecutor.formatBuiltinInfotableResult(t,
                MAPPER.createObjectNode().put("tool", "query_stream_data").put("maxItemsEffective", 600),
                ReadLimitFact.observe(t, 600));
        String valueStream = InvokeServiceExecutor.formatPropertyHistoryValueStreamCompact(t, null,
                MAPPER.createObjectNode().put("tool", "query_property_history"), 20, ReadLimitFact.observe(t, 600));
        for (String[] c : new String[][] {{"query_stream_data", stream}, {"query_property_history", valueStream}}) {
            String llm = ToolResultEgressGateway.compactForLlmAppend(c[0], "call-1", c[1],
                    LoggerFactory.getLogger(ReadLimitMarkingTest.class)).getLlmContent();
            JsonNode seen = MAPPER.readTree(llm);
            assertTrue(seen.path(ReadLimitFact.FIELD_REACHED).asBoolean(), c[0] + ": " + llm.substring(0, Math.min(400, llm.length())));
            assertTrue(seen.path(ReadLimitFact.FIELD_NOTE).asText().contains("may be incomplete"), c[0]);
        }
    }

    /** 80 STRING columns with 117-character names: a storable table whose schema alone overflows last resort. */
    private static InfoTable wideRows(int n) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        List<String> names = new ArrayList<>();
        for (int c = 0; c < 80; c++) {
            String name = "field_" + c + "_" + "x".repeat(110);
            names.add(name);
            FieldDefinition fd = new FieldDefinition();
            fd.setName(name);
            fd.setBaseType(BaseTypes.STRING);
            shape.addFieldDefinition(fd);
        }
        InfoTable t = new InfoTable(shape);
        for (int i = 0; i < n; i++) {
            ValueCollection r = new ValueCollection();
            for (String name : names) {
                r.put(name, new StringPrimitive("v"));
            }
            t.addRow(r);
        }
        return t;
    }

    private static JsonNode lastResortCompacted(String tool, String raw) throws Exception {
        ToolResultEgressGateway.EgressResult egress = ToolResultEgressGateway.compactForLlmAppend(tool, "call-1", raw,
                LoggerFactory.getLogger(ReadLimitMarkingTest.class));
        JsonNode seen = MAPPER.readTree(egress.getLlmContent());
        boolean omittedSomething = false;
        for (JsonNode marker : seen.path("_egress").path("reducedFields")) {
            omittedSomething |= marker.path("omitted").asBoolean();
        }
        assertTrue(omittedSomething, tool + ": only last-resort compaction omits whole fields; "
                + seen.path("_egress"));
        return seen;
    }

    /**
     * The wide schema is a priority field and fills last-resort compaction by itself, which used to
     * drop both read-limit fields from what the model sees while the descriptor still had the reason.
     */
    @Test
    void theHintSurvivesLastResortCompaction_ofAWideTable() throws Exception {
        AgentToolContext.setConversationId("tr0-egress-wide");
        InfoTable t = wideRows(30);
        String stream = InvokeServiceExecutor.formatBuiltinInfotableResult(t,
                MAPPER.createObjectNode().put("tool", "query_stream_data").put("maxItemsEffective", 30),
                ReadLimitFact.observe(t, 30));
        String valueStream = InvokeServiceExecutor.formatPropertyHistoryValueStreamCompact(t, null,
                MAPPER.createObjectNode().put("tool", "query_property_history"), 20, ReadLimitFact.observe(t, 30));
        for (String[] c : new String[][] {{"query_stream_data", stream}, {"query_property_history", valueStream}}) {
            JsonNode raw = MAPPER.readTree(c[1]);
            assertEquals(List.of(ReadLimitFact.REASON), reasonsOf(raw.path("cacheId").asText()), c[0]);
            JsonNode seen = lastResortCompacted(c[0], c[1]);
            assertTrue(seen.path(ReadLimitFact.FIELD_REACHED).asBoolean(), c[0]);
            assertEquals(raw.path(ReadLimitFact.FIELD_NOTE).asText(), seen.path(ReadLimitFact.FIELD_NOTE).asText(),
                    c[0] + ": the whole sentence, not an excerpt");
        }
    }

    /** The same wide table without a fact: last resort is reached and nothing about a read limit appears. */
    @Test
    void lastResortCompaction_ofAnUnmarkedWideTable_gainsNoReadLimitField() throws Exception {
        AgentToolContext.setConversationId("tr0-egress-wide-unmarked");
        String plain = InvokeServiceExecutor.formatBuiltinInfotableResult(wideRows(30),
                MAPPER.createObjectNode().put("tool", "invoke_service"));
        JsonNode seen = lastResortCompacted("invoke_service", plain);
        assertFalse(seen.has(ReadLimitFact.FIELD_REACHED));
        assertFalse(seen.has(ReadLimitFact.FIELD_NOTE));
        assertFalse(seen.has(ReadLimitFact.FIELD_REACHED_SERIES));
    }

    /** The overlay's evidence under last resort: flag, whole note and every affected label (the cap is six). */
    @Test
    void overlayEvidenceSurvivesLastResortCompaction_withEveryAffectedSeries() throws Exception {
        int limit = HistorySeriesComposerSupport.MAX_HISTORY_ROWS;
        ReadLimitFact reached = ReadLimitFact.observe(rows(7), 7);
        List<HistoryOverlayChartBuilder.ResolvedSeriesInput> series = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < HistoryOverlayChartBuilder.HISTORY_OVERLAY_MAX_SERIES; i++) {
            String label = "Line-" + i + "-" + "y".repeat(60);
            labels.add(label);
            series.add(new HistoryOverlayChartBuilder.ResolvedSeriesInput(label, "Pump-" + i, "speed", null, null,
                    "UTC", List.of(), reached));
        }
        JSONObject ok = new JSONObject();
        ok.put("status", "success");
        BuildHistoryOverlayChartExecutor.putReadLimitEvidence(ok, series);
        JSONArray filler = new JSONArray();
        for (int i = 0; i < 80; i++) {
            filler.put("field_" + i + "_" + "x".repeat(110));
        }
        ok.put("columns", filler);
        ok.put("trailing", "z".repeat(400));

        JsonNode seen = lastResortCompacted("build_history_overlay_chart", ok.toString());
        assertTrue(seen.path(ReadLimitFact.FIELD_REACHED).asBoolean());
        assertEquals(ReadLimitFact.seriesNote(limit), seen.path(ReadLimitFact.FIELD_NOTE).asText());
        List<String> seenLabels = new ArrayList<>();
        seen.path(ReadLimitFact.FIELD_REACHED_SERIES).forEach(n -> seenLabels.add(n.asText()));
        assertEquals(labels, seenLabels, "all six, although last resort samples other arrays to five");
        assertFalse(seen.path("_egress").path("reducedFields").has(ReadLimitFact.FIELD_REACHED_SERIES));
    }

    /** The retention is bounded: both notes fit the last-resort text cap, and a longer list is ordinary data. */
    @Test
    void retainedEvidenceIsBounded() throws Exception {
        assertTrue(ReadLimitFact.observe(rows(3), 3).note().length() < 450);
        assertTrue(ReadLimitFact.seriesNote(Long.MAX_VALUE).length() < 450);

        ObjectNode business = MAPPER.createObjectNode().put("status", "success");
        com.fasterxml.jackson.databind.node.ArrayNode many = business.putArray(ReadLimitFact.FIELD_REACHED_SERIES);
        for (int i = 0; i < 400; i++) {
            many.add("entry-" + i + "-" + "q".repeat(40));
        }
        JsonNode seen = MAPPER.readTree(ToolResultEgressGateway.compactForLlmAppend("invoke_service", "call-1",
                business.toString(), LoggerFactory.getLogger(ReadLimitMarkingTest.class)).getLlmContent());
        assertTrue(seen.path(ReadLimitFact.FIELD_REACHED_SERIES).size() <= 20,
                "an array longer than the overlay's series cap is sampled like any other");
    }

    private static ObjectNode bigObject() {
        ObjectNode big = MAPPER.createObjectNode();
        for (int i = 0; i < 300; i++) {
            big.put("item" + i, "x".repeat(400));
        }
        return big;
    }

    private static String compacted(ObjectNode root) {
        return ToolResultEgressGateway.compactForLlmAppend("invoke_service", "call-1", root.toString(),
                LoggerFactory.getLogger(ReadLimitMarkingTest.class)).getLlmContent();
    }

    /**
     * Retention is by name and value shape. The same value under a neutral name of the same length is
     * the control, so the read-limit names must change nothing but the name when the shape is not the evidence's.
     */
    @Test
    void aValueOfAnotherShapeUnderAReadLimitName_compactsLikeOrdinaryData() throws Exception {
        com.fasterxml.jackson.databind.node.ArrayNode sixObjects = MAPPER.createArrayNode();
        for (int i = 0; i < 6; i++) {
            sixObjects.add(bigObject());
        }
        com.fasterxml.jackson.databind.node.ArrayNode nested = MAPPER.createArrayNode();
        nested.add(MAPPER.createArrayNode().add("x".repeat(9000))).add("label");
        com.fasterxml.jackson.databind.node.ArrayNode sevenLabels = MAPPER.createArrayNode();
        for (int i = 0; i < 7; i++) {
            sevenLabels.add("label-" + i + "-" + "y".repeat(3000));
        }
        Object[][] cases = {
                {ReadLimitFact.FIELD_NOTE, "controlNoteXYZ".substring(0, 13), bigObject()},
                {ReadLimitFact.FIELD_NOTE, "controlNoteXYZ".substring(0, 13), MAPPER.createArrayNode().add(bigObject())},
                {ReadLimitFact.FIELD_REACHED, "controlReachedXY", bigObject()},
                {ReadLimitFact.FIELD_REACHED, "controlReachedXY", MAPPER.getNodeFactory().textNode("t".repeat(50_000))},
                {ReadLimitFact.FIELD_REACHED_SERIES, "controlReachedSeriesXY", sixObjects},
                {ReadLimitFact.FIELD_REACHED_SERIES, "controlReachedSeriesXY", nested},
                {ReadLimitFact.FIELD_REACHED_SERIES, "controlReachedSeriesXY", sevenLabels},
        };
        for (Object[] c : cases) {
            String name = (String) c[0];
            String control = (String) c[1];
            assertEquals(name.length(), control.length(), "same-length control name");
            JsonNode value = (JsonNode) c[2];
            ObjectNode marked = MAPPER.createObjectNode().put("status", "success");
            marked.set(name, value);
            ObjectNode plain = MAPPER.createObjectNode().put("status", "success");
            plain.set(control, value);
            String seen = compacted(marked);
            assertEquals(compacted(plain).replace(control, name), seen, name + " / " + value.getNodeType());
            assertTrue(seen.length() < marked.toString().length() / 4,
                    name + ": " + seen.length() + " of " + marked.toString().length());
        }
    }

    /** The evidence shapes themselves are still capped: a very long text note or label is an excerpt. */
    @Test
    void evidenceShapedValuesStayUnderTheTextCap() throws Exception {
        ObjectNode root = MAPPER.createObjectNode().put("status", "success");
        root.put(ReadLimitFact.FIELD_REACHED, true);
        root.put(ReadLimitFact.FIELD_NOTE, "n".repeat(200_000));
        com.fasterxml.jackson.databind.node.ArrayNode labels = root.putArray(ReadLimitFact.FIELD_REACHED_SERIES);
        for (int i = 0; i < 6; i++) {
            labels.add("l".repeat(100_000));
        }
        com.fasterxml.jackson.databind.node.ArrayNode columns = root.putArray("columns");
        for (int i = 0; i < 80; i++) {
            columns.add("field_" + i + "_" + "x".repeat(110));
        }
        JsonNode seen = MAPPER.readTree(compacted(root));
        assertTrue(seen.path(ReadLimitFact.FIELD_REACHED).asBoolean());
        assertTrue(seen.path(ReadLimitFact.FIELD_NOTE).asText().length() <= 515);
        assertEquals(6, seen.path(ReadLimitFact.FIELD_REACHED_SERIES).size());
        for (JsonNode label : seen.path(ReadLimitFact.FIELD_REACHED_SERIES)) {
            assertTrue(label.asText().length() <= 515);
        }
    }

    /**
     * Eligibility belongs to the value as the tool wrote it. With the budget already spent by a wide
     * schema, an ineligible list that sampling cuts down to five text labels must still be omitted like the
     * neutral-name control, not kept because the sample happens to look like evidence.
     */
    @Test
    void anIneligibleSeriesListIsNotPromotedBySampling_whenTheBudgetIsAlreadySpent() throws Exception {
        String name = ReadLimitFact.FIELD_REACHED_SERIES;
        String control = "controlReachedSeriesXY";
        assertEquals(name.length(), control.length());

        com.fasterxml.jackson.databind.node.ArrayNode sevenLabels = MAPPER.createArrayNode();
        for (int i = 0; i < 7; i++) {
            sevenLabels.add("label" + i + "y".repeat(6000));
        }
        com.fasterxml.jackson.databind.node.ArrayNode fiveLabelsAndAnObject = MAPPER.createArrayNode();
        for (int i = 0; i < 5; i++) {
            fiveLabelsAndAnObject.add("label" + i + "y".repeat(6000));
        }
        fiveLabelsAndAnObject.add(MAPPER.createObjectNode().put("business", "v".repeat(6000)));

        for (JsonNode value : List.of(sevenLabels, fiveLabelsAndAnObject)) {
            String[] seen = new String[2];
            int k = 0;
            for (String field : new String[] {name, control}) {
                ObjectNode root = MAPPER.createObjectNode().put("status", "success");
                com.fasterxml.jackson.databind.node.ArrayNode columns = root.putArray("columns");
                for (int i = 0; i < 80; i++) {
                    columns.add("field_" + i + "_" + "x".repeat(110));
                }
                root.set(field, value);
                seen[k++] = compacted(root);
            }
            JsonNode marked = MAPPER.readTree(seen[0]);
            assertFalse(marked.has(name), "omitted like the control: " + seen[0].length() + " chars");
            assertTrue(marked.path("_egress").path("reducedFields").path(name).path("omitted").asBoolean());
            assertEquals(seen[1].replace(control, name), seen[0]);
        }
    }
}
