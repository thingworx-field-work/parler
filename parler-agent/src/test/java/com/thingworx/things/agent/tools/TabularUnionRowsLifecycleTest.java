package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * What happens to a {@code union_rows} result after the executor returns: its cache identity, the chartable
 * target, the presentation registry, and the next analysis step. Also pins that the union did not change the
 * envelopes or the completion signal of the older modes that share the formatter.
 */
class TabularUnionRowsLifecycleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "__PARLER_LAST_QUALIFYING_TABULAR_CACHE__";

    TabularUnionRowsLifecycleTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        CachedTabularToolsExecutor.unionTableReadObserverForTests = null;
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static InfoTable hours(int rows, String labelPrefix) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (String[] f : new String[][] {{"state", "STRING"}, {"hours", "NUMBER"}}) {
            FieldDefinition fd = new FieldDefinition();
            fd.setName(f[0]);
            fd.setBaseType(BaseTypes.valueOf(f[1]));
            shape.addFieldDefinition(fd);
        }
        InfoTable it = new InfoTable(shape);
        for (int i = 0; i < rows; i++) {
            ValueCollection row = new ValueCollection();
            row.put("state", new StringPrimitive(labelPrefix + (i % 3)));
            row.put("hours", new NumberPrimitive(i + 1.0));
            it.addRow(row);
        }
        return it;
    }

    private static String tabulate(String argsJson) {
        return CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t", "tabulate_cached_result", argsJson));
    }

    private static String unionArgs(String a, String b, String labelValuesJson) {
        return "{\"mode\":\"union_rows\",\"sourceCacheIds\":[\"" + a + "\",\"" + b + "\"],\"labelColumn\":\"day\","
                + "\"labelValues\":" + labelValuesJson + "}";
    }

    private static void startTurn(String conversationId) {
        AgentToolContext.setConversationId(conversationId);
        AgentToolContext.resetTabularChartRound();
    }

    /** 2, 20 and 21 output rows: INLINE, the INLINE edge and LARGE all hand the next step one derived table. */
    @ParameterizedTest
    @CsvSource({"1,1", "10,10", "10,11"})
    void nonEmptyUnion_alwaysCarriesDerivedCacheId_andFeedsTheNextAnalysisByToken(int rowsA, int rowsB)
            throws Exception {
        startTurn("union-life-" + rowsA + "-" + rowsB);
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(hours(rowsA, "s"));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(hours(rowsB, "s"));
        String body = tabulate(unionArgs(a, b, "[\"d1\",\"d2\"]"));
        JsonNode out = MAPPER.readTree(body);
        assertEquals("success", out.path("status").asText(), body);
        String derived = out.path("cacheId").asText();
        assertFalse(derived.isEmpty(), "every non-empty union has its own handle: " + body);
        assertNotEquals(a, derived);
        assertEquals(a, out.path("sourceCacheId").asText(), "sourceCacheId still names the first input");
        assertEquals(rowsA + rowsB, InvokeServiceExecutor.lookupCachedInfotable(derived).getRowCount().intValue());
        assertEquals(rowsA + rowsB > 20 ? "CACHED_TABULATE_LARGE" : "CACHED_TABULATE_INLINE",
                out.path("resultKind").asText());

        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", body);
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(derived, st.getLastCacheId(), "last_invoke, cache_id and TOKEN name the same table");
        assertTrue(st.isLastChartRescueDataCompleteEnough());
        assertEquals(List.of(derived),
                PresentationArtifactRegistry.orderedCacheIds(FetchCachedReplayGuard.resolveCurrentTurnKey()),
                "INLINE and LARGE unions both register as presentation artifacts");

        String box = tabulate("{\"mode\":\"box_summary\",\"cacheId\":\"" + TOKEN + "\",\"column\":\"hours\","
                + "\"groupBy\":\"day\"}");
        JsonNode boxOut = MAPPER.readTree(box);
        assertEquals("success", boxOut.path("status").asText(), box);
        assertEquals(derived, boxOut.path("sourceCacheId").asText(), "the analysis read the union, not input one");
        assertEquals(2, boxOut.path("groupCount").asInt(), box);
    }

    @Test
    void largeUnion_doesNotClaimTheModelSawAllRows() throws Exception {
        startTurn("union-life-large-honest");
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(hours(11, "s"));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(hours(11, "s"));
        String body = tabulate(unionArgs(a, b, "[\"d1\",\"d2\"]"));
        JsonNode out = MAPPER.readTree(body);
        assertEquals("CACHED_TABULATE_LARGE", out.path("resultKind").asText(), body);
        assertTrue(out.path("sampleOnly").asBoolean(), body);
        assertTrue(out.path("rowsOmitted").asBoolean(), body);
        assertFalse(TabularCompleteAnswerSetDetector.isCompleteAnswerSet("tabulate_cached_result", body),
                "registering the cached table is not an answer-completion signal");
    }

    @Test
    void emptyUnion_clearsTheChartableTarget_soLastInvokeCannotChartAnOlderTable() throws Exception {
        startTurn("union-life-empty");
        String prior = InvokeServiceExecutor.storeInfotableInConversationCache(hours(30, "s"));
        TabularChartRoundHooks.afterBuiltInToolResult("fetch_cached_result",
                "{\"status\":\"success\",\"cacheId\":\"" + prior + "\",\"rows\":[{\"state\":\"s0\",\"hours\":1}]}");
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertTrue(st.hasChartableLastInvokeTarget());

        String a = InvokeServiceExecutor.storeInfotableInConversationCache(hours(0, "s"));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(hours(0, "s"));
        String body = tabulate(unionArgs(a, b, "[\"d1\",\"d2\"]"));
        assertEquals("CACHED_TABULATE_EMPTY", MAPPER.readTree(body).path("resultKind").asText(), body);
        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", body);

        assertFalse(st.hasChartableLastInvokeTarget(), "the empty union is now the latest result");
        String chart = BuildChartFromTabularResultExecutor.execute(new ToolCall("c",
                "build_chart_from_tabular_result",
                "{\"source\":\"last_invoke\",\"kind\":\"bar\",\"xColumn\":\"state\",\"yColumn\":\"hours\"}"));
        assertEquals("SOURCE_RESULT_NOT_TABULAR", MAPPER.readTree(chart).path("code").asText(), chart);
    }

    /**
     * The shared formatter serves the replay aliases too. Their EMPTY, INLINE and LARGE envelopes and the loop's
     * complete-answer decision must be what they were before TR-2: none of the union packaging leaks into them.
     */
    @ParameterizedTest
    @CsvSource({"0,5,CACHED_TABULATE_EMPTY", "5,1,CACHED_TABULATE_INLINE", "25,21,CACHED_TABULATE_LARGE"})
    void legacyReplayModes_keepTheirEnvelopeAndCompletionSignal(int sourceRows, int maxItems, String expectedKind)
            throws Exception {
        startTurn("union-life-legacy-" + expectedKind);
        String src = InvokeServiceExecutor.storeInfotableInConversationCache(hours(sourceRows, "s"));
        String body = tabulate("{\"mode\":\"sort_topn\",\"cacheId\":\"" + src + "\","
                + "\"sorts\":[{\"fieldName\":\"hours\",\"isAscending\":false}],\"maxItems\":" + maxItems + "}");
        JsonNode out = MAPPER.readTree(body);
        assertEquals("success", out.path("status").asText(), body);
        assertEquals(expectedKind, out.path("resultKind").asText(), body);
        for (String added : new String[] {"returnedRows", "sampleOnly", "rowsOmitted", "unionMeta", "sourceCacheIds"}) {
            assertFalse(out.has(added), "union packaging must not leak into " + expectedKind + ": " + added);
        }
        assertEquals("CACHED_TABULATE_LARGE".equals(expectedKind), out.has("cacheId"),
                "only LARGE carried a cacheId before TR-2");
        assertFalse(TabularCompleteAnswerSetDetector.isCompleteAnswerSet("tabulate_cached_result", body),
                "the loop's complete-answer decision for the old modes is what it was before TR-2");
    }

    @Test
    void rowBudget_isDecidedFromDescriptors_beforeAnyTableIsRead() throws Exception {
        startTurn("union-life-preread");
        long half = CachedTabularToolsExecutor.MAX_SCANNED_ROWS_DECISION / 2 + 1;
        String a = storeClaiming(hours(1, "s"), half);
        String b = storeClaiming(hours(1, "s"), half);
        List<String> reads = new ArrayList<>();
        CachedTabularToolsExecutor.unionTableReadObserverForTests = reads::add;
        assertEquals("SOURCE_TOO_LARGE", MAPPER.readTree(tabulate(unionArgs(a, b, "[\"d1\",\"d2\"]")))
                .path("code").asText());
        assertEquals(List.of(), reads, "no input table was read");

        String c = InvokeServiceExecutor.storeInfotableInConversationCache(hours(1, "s"));
        String d = InvokeServiceExecutor.storeInfotableInConversationCache(hours(1, "s"));
        assertEquals("success", MAPPER.readTree(tabulate(unionArgs(c, d, "[\"d1\",\"d2\"]"))).path("status").asText());
        assertEquals(List.of(c, d), reads, "control: the observer does see reads, in call order");
    }

    private static String storeClaiming(InfoTable table, long claimedRows) throws Exception {
        return InvokeServiceExecutor.storeInfotableInConversationCache(table,
                com.thingworx.things.agent.source.SourceDescriptor.builder()
                        .sourceRouteId("invoke_service")
                        .rowsExamined(claimedRows)
                        .rowsReturned(claimedRows)
                        .completenessStatus(
                                com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.UNKNOWN)
                        .build());
    }

    /**
     * Label amplification: 40,000 rows and 120,000 cells are far inside the row and cell budgets, but a 1,000-byte
     * label written on every row is 40 MB, over the 32 MB storage budget. The second input is refused before its
     * rows are appended and nothing is cached.
     */
    @Test
    void labelAmplification_isRefusedOnBytes_beforeTheOffendingInputIsAppended() throws Exception {
        startTurn("union-life-label-bytes");
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(hours(20_000, "s"));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(hours(20_000, "s"));
        String label = "L".repeat(1_000);
        String body = tabulate(unionArgs(a, b, "[\"" + label + "\",\"" + label + "\"]"));
        JsonNode out = MAPPER.readTree(body);
        assertEquals("TABLE_TOO_LARGE_FOR_TRANSFORM", out.path("code").asText(), body.length() > 300 ? body.substring(0, 300) : body);
        assertFalse(out.has("cacheId"));
        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", body);
        assertEquals(0, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
    }

    @Test
    void expansionBudget_isSharedAcrossInputs_andAnExpiredDeadlineRefusesEvenAnEmptyTable() throws Exception {
        InfoTable t = hours(10, "s");
        TabularExpansionBudget bytes = TabularExpansionBudget.forTests(1_000_000, 60_000);
        assertTrue(bytes.chargeInfoTable(t, 100));
        assertTrue(bytes.bytes() >= 10 * 100, "the per-row label is charged on every row: " + bytes.bytes());
        long afterFirst = bytes.bytes();
        assertTrue(bytes.chargeInfoTable(t, 100));
        assertTrue(bytes.bytes() > afterFirst, "the second input adds to the same total");

        TabularExpansionBudget expired = TabularExpansionBudget.forTests(1_000_000, 0);
        Thread.sleep(2);
        assertTrue(expired.timeExceeded());
        assertFalse(expired.chargeInfoTable(hours(0, "s"), 0), "a zero-row table still meets the deadline check");
    }

    /**
     * A label of control characters is 1 raw byte and 6 encoded bytes per character. 6,000 rows with a
     * 1,000-character label are 6 MB raw and 36 MB encoded: inside every raw count, over the 32 MB storage budget.
     */
    @Test
    void escapedLabelAmplification_isRefusedBeforeTheOffendingInputIsAppended() throws Exception {
        startTurn("union-life-escaped-label");
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(hours(3_000, "s"));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(hours(3_000, "s"));
        String label = "\\u0001".repeat(1_000);
        JsonNode out = MAPPER.readTree(tabulate(unionArgs(a, b, "[\"" + label + "\",\"" + label + "\"]")));
        assertEquals("TABLE_TOO_LARGE_FOR_TRANSFORM", out.path("code").asText(), out.toString());
        assertFalse(out.has("cacheId"));
    }

    /**
     * One deadline for the whole call. The read observer spends the budget before the first read returns; the
     * second input must not be read, and nothing is cached or registered. Two empty tables are the case that
     * used to skip every check.
     */
    @Test
    void sharedDeadline_stopsBeforeTheNextRead_evenForEmptyInputs() throws Exception {
        startTurn("union-life-deadline");
        AgentToolContext.setRunInvocationContext(com.thingworx.things.agent.execution.RunInvocationContext.of(
                "inv-deadline", com.thingworx.things.agent.execution.ExecutionScope.CONVERSATION, "scope-deadline",
                com.thingworx.things.agent.execution.BudgetVector.defaultsForTabular()));
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(hours(0, "s"));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(hours(0, "s"));
        AgentToolContext.setRunInvocationContext(com.thingworx.things.agent.execution.RunInvocationContext.of(
                "inv-deadline", com.thingworx.things.agent.execution.ExecutionScope.CONVERSATION, "scope-deadline",
                com.thingworx.things.agent.execution.BudgetVector.builder().maxWallTimeMillis(50).build()));
        List<String> reads = new ArrayList<>();
        CachedTabularToolsExecutor.unionTableReadObserverForTests = id -> {
            reads.add(id);
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        String body = tabulate(unionArgs(a, b, "[\"d1\",\"d2\"]"));
        assertEquals("UNION_TIME_BUDGET_EXCEEDED", MAPPER.readTree(body).path("code").asText(), body);
        assertEquals(List.of(a), reads, "the second input was never read");
        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", body);
        assertEquals(0, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
    }

    private static InfoTable withChildren(String... childFieldAndType) throws Exception {
        DataShapeDefinition child = new DataShapeDefinition();
        for (int i = 0; i < childFieldAndType.length; i += 2) {
            FieldDefinition fd = new FieldDefinition();
            fd.setName(childFieldAndType[i]);
            fd.setBaseType(BaseTypes.valueOf(childFieldAndType[i + 1]));
            child.addFieldDefinition(fd);
        }
        InfoTable children = new InfoTable(child);
        for (int r = 0; r < 2; r++) {
            ValueCollection row = new ValueCollection();
            row.put(childFieldAndType[0], new StringPrimitive("c" + r));
            children.addRow(row);
        }
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition name = new FieldDefinition();
        name.setName("name");
        name.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(name);
        FieldDefinition nested = new FieldDefinition();
        nested.setName("children");
        nested.setBaseType(BaseTypes.INFOTABLE);
        nested.setLocalDataShape(child);
        shape.addFieldDefinition(nested);
        InfoTable table = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("name", new StringPrimitive("parent"));
        row.put("children", new com.thingworx.types.primitives.InfoTablePrimitive(children));
        table.addRow(row);
        return table;
    }

    /**
     * A native InfoTable is not a flat JSON table: a nested INFOTABLE column carries a local shape, and the cache
     * needs it to prove that the nested table has no PASSWORD column. The union output keeps it.
     */
    @Test
    void nestedInfoTableColumn_keepsItsLocalShape_soTheUnionCanBeStoredAndReadBack() throws Exception {
        startTurn("union-life-nested");
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(withChildren("x", "STRING", "y", "NUMBER"));
        String body = tabulate(unionArgs(a, a, "[\"d1\",\"d2\"]"));
        JsonNode out = MAPPER.readTree(body);
        assertEquals("success", out.path("status").asText(), body);
        InfoTable derived = InvokeServiceExecutor.lookupCachedInfotable(out.path("cacheId").asText());
        assertEquals(2, derived.getRowCount().intValue());
        assertEquals(2, ((InfoTable) derived.getRow(1).getPrimitive("children").getValue()).getRowCount().intValue());
        assertEquals(List.of("x", "y"), new ArrayList<>(derived.getDataShape().getFields().get("children")
                .getLocalDataShape().getFields().keySet()));
    }

    @Test
    void nestedInfoTableColumn_withADifferentLocalShape_isAColumnMismatch() throws Exception {
        startTurn("union-life-nested-mismatch");
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(withChildren("x", "STRING", "y", "NUMBER"));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(withChildren("x", "STRING", "z", "NUMBER"));
        JsonNode out = MAPPER.readTree(tabulate(unionArgs(a, b, "[\"d1\",\"d2\"]")));
        assertEquals("UNION_COLUMN_MISMATCH", out.path("code").asText(), out.toString());
        assertTrue(out.path("message").asText().contains(b), out.toString());
    }

    @Test
    void labelValues_mustBeStrings() throws Exception {
        startTurn("union-life-labels");
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(hours(1, "s"));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(hours(1, "s"));
        assertEquals("INVALID_LABEL_VALUES",
                MAPPER.readTree(tabulate(unionArgs(a, b, "[123,\"b\"]"))).path("code").asText());
        assertEquals("INVALID_LABEL_VALUES",
                MAPPER.readTree(tabulate(unionArgs(a, b, "[null,\"b\"]"))).path("code").asText());
        JsonNode ok = MAPPER.readTree(tabulate(unionArgs(a, b, "[\" d1 \",\"\"]")));
        assertEquals(" d1 ", ok.path("rows").get(0).path("day").asText(), "valid strings are kept as given");
    }

    @Test
    void modelSchema_doesNotRequireCacheId_whileOtherModesStillDo() throws Exception {
        JsonNode schema = MAPPER.valueToTree(TabulateCachedResultToolSchema.parametersSchema());
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        assertEquals(List.of("mode"), required, "a union call without cacheId must be schema-valid");
        assertEquals("MISSING_CACHE_ID",
                MAPPER.readTree(tabulate("{\"mode\":\"filter_count\"}")).path("code").asText());
    }
}
