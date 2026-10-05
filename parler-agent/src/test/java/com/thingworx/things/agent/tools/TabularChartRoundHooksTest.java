package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TabularChartRoundHooksTest {

    TabularChartRoundHooksTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void afterToolResult_unknownToolName_invokeShapedInfotable_recordsQualifying() {
        AgentToolContext.setConversationId("tcrh-generic");
        AgentToolContext.resetTabularChartRound();
        String json = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rows\":[{\"UtilizationState\":\"Down\",\"Duration\":1}]}";
        TabularChartRoundHooks.afterBuiltInToolResult("my_custom_extended_tool", json);
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(1, st.getQualifyingTabularSuccessCount());
        assertTrue(st.hasChartableLastInvokeTarget());
        assertTrue(st.isLastChartRescueDataCompleteEnough());
    }

    @Test
    void afterToolResult_sampleOnly_true_marksIncompleteForRescue() {
        AgentToolContext.setConversationId("tcrh-sample");
        AgentToolContext.resetTabularChartRound();
        String json = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"sampleOnly\":true,\"rows\":[{\"a\":1}]}";
        TabularChartRoundHooks.afterBuiltInToolResult("ext_rows", json);
        assertFalse(AgentToolContext.tabularChartRoundState().isLastChartRescueDataCompleteEnough());
    }

    @Test
    void infotableJsonLooksComplete_whenTruncationFlagsAbsent_true() {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.createObjectNode()
                .put("status", "success")
                .put("resultKind", "INFOTABLE");
        assertTrue(TabularChartRoundHooks.infotableJsonLooksCompleteForChartRescue(root));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String inlineAnalyzeEntitySetSuccessJson() {
        return "{\"status\":\"success\",\"resultKind\":\"ENTITY_SET_INLINE\",\"cacheId\":\"aes-cache-test\","
                + "\"operation\":\"difference\",\"totalRows\":1,\"matchedKeys\":1,\"answerSetComplete\":true,"
                + "\"columns\":[{\"name\":\"name\",\"baseType\":\"STRING\"}],\"rows\":[{\"name\":\"only-left\"}]}";
    }

    @Test
    void analyze_entity_set_does_not_increment_qualifying_tabular_or_last_invoke() {
        AgentToolContext.setConversationId("tcrh-aes-no-qual");
        AgentToolContext.resetTabularChartRound();
        TabularChartRoundHooks.afterBuiltInToolResult("analyze_entity_set", inlineAnalyzeEntitySetSuccessJson());
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(0, st.getQualifyingTabularSuccessCount());
        assertFalse(st.hasChartableLastInvokeTarget());
    }

    @Test
    void last_invoke_after_analyze_entity_set_alone_is_not_tabular() throws Exception {
        AgentToolContext.setConversationId("tcrh-aes-last-inv");
        AgentToolContext.resetTabularChartRound();
        TabularChartRoundHooks.afterBuiltInToolResult("analyze_entity_set", inlineAnalyzeEntitySetSuccessJson());
        String[] err = new String[1];
        JsonNode args = MAPPER.readTree("{\"source\":\"last_invoke\",\"xColumn\":\"name\",\"yColumn\":\"n\",\"kind\":\"bar\"}");
        TabularChartSourceResolver.Resolved r = TabularChartSourceResolver.resolveOrError(args, err);
        assertNull(r);
        assertTrue(err[0].contains("no_qualifying_tabular_tool"), err[0]);
    }

    // ---- JSON root rows: business single table inside a resultKind=JSON envelope ----

    private static final String SUMMARY_ROWS = "["
            + "{\"utilizationState\":\"Down\",\"sumDurationSeconds\":51368,\"sumDurationMinutes\":856.13,\"percentage\":59.45,\"count\":3},"
            + "{\"utilizationState\":\"Unavailable\",\"sumDurationSeconds\":34251,\"sumDurationMinutes\":570.85,\"percentage\":39.64,\"count\":1},"
            + "{\"utilizationState\":\"Running\",\"sumDurationSeconds\":780,\"sumDurationMinutes\":13,\"percentage\":0.9,\"count\":2}]";

    private static String summaryBusiness(String leadingFields) {
        return "{" + leadingFields + "\"rows\":" + SUMMARY_ROWS + ",\"stats\":{\"utilizationPercent\":1.5},\"evidenceGaps\":[]}";
    }

    private static String jsonEnvelope(String businessJson, boolean stringResult) throws Exception {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("status", "success");
        env.put("resultKind", "JSON");
        if (stringResult) {
            env.put("result", businessJson);
        } else {
            env.set("result", MAPPER.readTree(businessJson));
        }
        return MAPPER.writeValueAsString(env);
    }

    private static TabularChartRoundState feedFreshRound(String conversationId, String envelope) {
        AgentToolContext.setConversationId(conversationId);
        AgentToolContext.resetTabularChartRound();
        TabularChartRoundHooks.afterBuiltInToolResult("any_extended_tool", envelope);
        return AgentToolContext.tabularChartRoundState();
    }

    @Test
    void jsonRootRows_stringResult_registersInlineRows() throws Exception {
        TabularChartRoundState st = feedFreshRound("tcrh-json-string",
                jsonEnvelope(summaryBusiness("\"status\":\"success\","), true));
        assertEquals(1, st.getQualifyingTabularSuccessCount());
        assertTrue(st.hasChartableLastInvokeTarget());
        assertTrue(st.isLastChartRescueDataCompleteEnough());
        assertNull(st.getLastCacheId());
        assertEquals(3, st.getLastInlineRows().size());
        assertEquals("Down", st.getLastInlineRows().get(0).get("utilizationState").asText());
        assertEquals(51368, st.getLastInlineRows().get(0).get("sumDurationSeconds").asInt());
    }

    @Test
    void jsonRootRows_objectResult_registersInlineRows() throws Exception {
        TabularChartRoundState st = feedFreshRound("tcrh-json-object",
                jsonEnvelope(summaryBusiness("\"status\":\"success\","), false));
        assertEquals(1, st.getQualifyingTabularSuccessCount());
        assertEquals(3, st.getLastInlineRows().size());
        assertEquals("Running", st.getLastInlineRows().get(2).get("utilizationState").asText());
    }

    @Test
    void jsonRootRows_missingBusinessStatus_registers() throws Exception {
        TabularChartRoundState st = feedFreshRound("tcrh-json-no-status", jsonEnvelope(summaryBusiness(""), true));
        assertEquals(1, st.getQualifyingTabularSuccessCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"empty\"", "\"error\"", "\"partial\"", "\"Success\"", "null", "true", "1"})
    void jsonRootRows_businessStatusOtherThanSuccess_notRegisteredEvenWithRows(String statusLiteral) throws Exception {
        TabularChartRoundState st = feedFreshRound("tcrh-json-status-" + statusLiteral.length(),
                jsonEnvelope(summaryBusiness("\"status\":" + statusLiteral + ","), true));
        assertEquals(0, st.getQualifyingTabularSuccessCount());
        assertFalse(st.hasChartableLastInvokeTarget());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"hasMore\":true", "\"truncated\":true", "\"offset\":1", "\"totalRows\":5,\"returnedRows\":3"})
    void jsonRootRows_explicitPartialResultSignal_notRegistered(String signal) throws Exception {
        TabularChartRoundState st = feedFreshRound("tcrh-json-partial-" + signal.length(),
                jsonEnvelope(summaryBusiness("\"status\":\"success\"," + signal + ","), true));
        assertEquals(0, st.getQualifyingTabularSuccessCount());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"offset\":0|1",
            "\"offset\":0.0|1",
            "\"offset\":1|0",
            "\"offset\":0.5|0",
            "\"offset\":9223372036854775808|0",
            "\"offset\":-1|1",
            "\"totalRows\":3,\"returnedRows\":3|1",
            "\"totalRows\":3.0,\"returnedRows\":3|1",
            "\"totalRows\":3.5,\"returnedRows\":3|0",
            "\"totalRows\":18446744073709551619,\"returnedRows\":3|0",
            "\"totalRows\":3,\"returnedRows\":18446744073709551619|0",
            "\"totalRows\":9223372036854775808,\"returnedRows\":9223372036854775808|1"})
    void jsonRootRows_numericPagingFieldsCompareByMagnitude(String pagingFields, int expectedQualifyingCount)
            throws Exception {
        TabularChartRoundState st = feedFreshRound("tcrh-json-numeric-" + pagingFields.hashCode(),
                jsonEnvelope(summaryBusiness("\"status\":\"success\"," + pagingFields + ","), true));
        assertEquals(expectedQualifyingCount, st.getQualifyingTabularSuccessCount(), pagingFields);
    }

    @Test
    void jsonRootRows_fullSinglePage_registers() throws Exception {
        TabularChartRoundState st = feedFreshRound("tcrh-json-full-page", jsonEnvelope(summaryBusiness(
                "\"status\":\"success\",\"totalRows\":3,\"returnedRows\":3,\"offset\":0,\"limit\":50,\"hasMore\":false,"), true));
        assertEquals(1, st.getQualifyingTabularSuccessCount());
    }

    @Test
    void jsonRootRows_stringTypedPagingFieldsAreNotCoerced() throws Exception {
        TabularChartRoundState st = feedFreshRound("tcrh-json-string-flags", jsonEnvelope(summaryBusiness(
                "\"status\":\"success\",\"hasMore\":\"true\",\"offset\":\"3\",\"totalRows\":\"9\",\"returnedRows\":3,"), true));
        assertEquals(1, st.getQualifyingTabularSuccessCount());
    }

    private static String rowsOfSize(int n) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < n; i++) {
            rows.addObject().put("state", "s" + i).put("value", i + 1);
        }
        return "{\"status\":\"success\",\"rows\":" + rows + "}";
    }

    private static String rowsOfSizeWithPaging(int n) throws Exception {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < n; i++) {
            rows.addObject().put("state", "s" + i).put("value", i + 1);
        }
        ObjectNode business = MAPPER.createObjectNode();
        business.put("status", "success");
        business.put("hasMore", false);
        business.put("totalRows", n);
        business.put("returnedRows", n);
        business.set("rows", rows);
        return MAPPER.writeValueAsString(business);
    }

    @Test
    void jsonRootRows_atInlineThreshold_registers_aboveThreshold_without_paging_notRegistered() throws Exception {
        int limit = InvokeServiceExecutor.LARGE_TABLE_ROW_THRESHOLD;
        TabularChartRoundState atLimit = feedFreshRound("tcrh-json-at-limit", jsonEnvelope(rowsOfSize(limit), true));
        assertEquals(1, atLimit.getQualifyingTabularSuccessCount());
        assertEquals(limit, atLimit.getLastInlineRows().size());
        TabularChartRoundState aboveLimit = feedFreshRound("tcrh-json-above-limit",
                jsonEnvelope(rowsOfSize(limit + 1), true));
        assertEquals(0, aboveLimit.getQualifyingTabularSuccessCount());
    }

    private static String pagedBusiness(int n, String pagingFieldsJson) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < n; i++) {
            rows.addObject().put("state", "s" + i).put("value", i + 1);
        }
        String fields = pagingFieldsJson.isEmpty() ? "" : pagingFieldsJson + ",";
        return "{\"status\":\"success\"," + fields + "\"rows\":" + rows + "}";
    }

    @Test
    void jsonRootRows_promotedTier_registersCacheBacked_sameIdentityEverywhere() throws Exception {
        int n = InvokeServiceExecutor.LARGE_TABLE_ROW_THRESHOLD + 5;
        String envelope = jsonEnvelope(rowsOfSizeWithPaging(n), true);
        AgentToolContext.setConversationId("tcrh-json-promoted");
        AgentToolContext.resetTabularChartRound();
        String outJson = TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", envelope);
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        String envelopeId = MAPPER.readTree(outJson).path("cacheId").asText();
        assertFalse(envelopeId.isEmpty());
        assertEquals(1, st.getQualifyingTabularSuccessCount());
        assertEquals(envelopeId, st.getLastCacheId(), "last_invoke, cache_id and the envelope name one table");
        assertNull(st.getLastInlineRows(), "the inline-rows path of the 20-row tier is not extended to promotion");
        assertEquals(n, InvokeServiceExecutor.lookupCachedInfotable(envelopeId).getRowCount().intValue());
    }

    /**
     * Promotion truth table (design 7.2 rule 2). Positive evidence is hasMore:false, truncated:false, or numeric
     * totalRows equal to returnedRows. Every paging field present must be well typed and every count present must
     * equal the delivered row count; returnedRows alone is never evidence.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "'\"hasMore\":false'                                          | 1",
            "'\"truncated\":false'                                        | 1",
            "'\"totalRows\":25,\"returnedRows\":25'                        | 1",
            "'\"hasMore\":false,\"totalRows\":25,\"returnedRows\":25'      | 1",
            "'\"hasMore\":false,\"returnedRows\":null'                     | 1",
            "''                                                           | 0",
            "'\"returnedRows\":25'                                        | 0",
            "'\"totalRows\":25'                                           | 0",
            "'\"hasMore\":false,\"returnedRows\":40'                       | 0",
            "'\"hasMore\":false,\"totalRows\":100'                         | 0",
            "'\"hasMore\":\"true\",\"truncated\":false'                    | 0",
            "'\"hasMore\":false,\"truncated\":\"no\"'                      | 0",
            "'\"hasMore\":false,\"returnedRows\":\"25\"'                   | 0",
            "'\"hasMore\":false,\"offset\":\"0\"'                          | 0",
            "'\"hasMore\":true,\"truncated\":false'                        | 0",
            "'\"hasMore\":false,\"offset\":5'                              | 0",
    })
    void jsonRootRows_promotionTruthTable(String pagingFields, int expectedQualifyingCount) throws Exception {
        TabularChartRoundState st = feedFreshRound("tcrh-json-truth-" + Math.abs(pagingFields.hashCode()),
                jsonEnvelope(pagedBusiness(25, pagingFields), true));
        assertEquals(expectedQualifyingCount, st.getQualifyingTabularSuccessCount(), pagingFields);
    }

    @Test
    void jsonRootRows_inlineTier_keepsItsWeakerRule_whenPagingFieldsAreOdd() throws Exception {
        // 20 rows or fewer: absence of paging fields and a lone returnedRows keep passing, as before TR-1.
        assertEquals(1, feedFreshRound("tcrh-json-inline-weak-1",
                jsonEnvelope(pagedBusiness(5, ""), true)).getQualifyingTabularSuccessCount());
        assertEquals(1, feedFreshRound("tcrh-json-inline-weak-2",
                jsonEnvelope(pagedBusiness(5, "\"returnedRows\":5"), true)).getQualifyingTabularSuccessCount());
    }

    @Test
    void jsonRootRows_promoted25Rows_resolveAsChartSource_byLastInvokeAndByCacheId() throws Exception {
        AgentToolContext.setConversationId("tcrh-json-promoted-e2e");
        AgentToolContext.resetTabularChartRound();
        String out = TabularChartRoundHooks.afterBuiltInToolResult("invoke_service",
                jsonEnvelope(rowsOfSizeWithPaging(25), true));
        String cacheId = MAPPER.readTree(out).path("cacheId").asText();
        for (String source : new String[] {"\"source\":\"last_invoke\"",
                "\"source\":\"cache_id\",\"cacheId\":\"" + cacheId + "\""}) {
            String[] err = new String[1];
            TabularChartSourceResolver.Resolved resolved = TabularChartSourceResolver.resolveOrError(
                    MAPPER.readTree("{" + source + ",\"kind\":\"bar\",\"xColumn\":\"state\",\"yColumn\":\"value\"}"),
                    err);
            assertNotNull(resolved, source + " -> " + err[0]);
            assertEquals(25, resolved.table.getRowCount().intValue(), "all 25 rows, not the 20-row sample");
        }
        // 25 distinct states exceed the bar category cap; the real builder must say so rather than chart a sample.
        String chart = BuildChartFromTabularResultExecutor.execute(
                new com.thingworx.things.agent.llm.ToolCall("c", "build_chart_from_tabular_result",
                        "{\"source\":\"last_invoke\",\"kind\":\"bar\",\"xColumn\":\"state\",\"yColumn\":\"value\"}"));
        assertEquals(25, MAPPER.readTree(chart).path("details").path("categoryCount").asInt(), chart);
    }

    @Test
    void jsonRootRows_approvedReplay_storesOnce_andRegistersOnce() throws Exception {
        int n = InvokeServiceExecutor.LARGE_TABLE_ROW_THRESHOLD + 5;
        String envelope = jsonEnvelope(rowsOfSizeWithPaging(n), true);
        AgentToolContext.setConversationId("tcrh-json-replay");
        AgentToolContext.resetTabularChartRound();
        String approved = TabularChartRoundHooks.augmentJsonSourceHandle("invoke_service", envelope);
        String firstId = MAPPER.readTree(approved).path("cacheId").asText();
        assertFalse(firstId.isEmpty());
        assertEquals(0, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount(),
                "the approval pass does not touch round state");
        String continued = TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", approved);
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(firstId, MAPPER.readTree(continued).path("cacheId").asText(), "the handle is reused");
        assertEquals(firstId, st.getLastCacheId());
        assertEquals(1, st.getQualifyingTabularSuccessCount());
        assertEquals(approved, continued, "the second pass does not rewrite the body");
    }

    @Test
    void jsonRootRows_promotionStoreFailure_keepsPriorTable_andReturnsOriginalJson() throws Exception {
        AgentToolContext.setConversationId("tcrh-json-store-fail");
        AgentToolContext.resetTabularChartRound();
        TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", jsonEnvelope(rowsOfSize(3), true));
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(1, st.getQualifyingTabularSuccessCount());
        JsonNode priorRows = st.getLastInlineRows();
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        String envelope = jsonEnvelope(rowsOfSizeWithPaging(25), true);
        String out = TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", envelope);
        assertEquals(envelope, out, "a failed store hands back the original bounded JSON");
        assertEquals(1, st.getQualifyingTabularSuccessCount(), "nothing was registered for the failed promotion");
        assertEquals(priorRows, st.getLastInlineRows(), "the prior legal table is still the target");
        assertNull(st.getLastCacheId());
    }

    /**
     * The real sparse shape of design section 4: one very long column name on row 0 and thousands of empty rows.
     * About 62,000 characters and 10,000 cells, so it passes the character cap and the cell budget, but the cache
     * codec repeats the name on every row: at least 320 MB. It must be refused before any table is built.
     */
    @Test
    void jsonRootRows_sparseLongColumnName_refusedOnExpansionBytes_beforeAnyTableIsBuilt() throws Exception {
        int rowCount = 10_000;
        ArrayNode rows = MAPPER.createArrayNode();
        rows.addObject().put("n".repeat(32_000), 1);
        for (int r = 1; r < rowCount; r++) {
            rows.addObject();
        }
        ObjectNode business = MAPPER.createObjectNode();
        business.put("status", "success");
        business.put("hasMore", false);
        business.set("rows", rows);
        String businessJson = MAPPER.writeValueAsString(business);
        assertTrue(businessJson.length() < 65_536, "under the JSON character cap: " + businessJson.length());
        assertFalse(TabularChartRoundHooks.withinPromotionExpansionBudget(rows));
        TabularChartRoundState st = feedFreshRound("tcrh-json-sparse-long-name", jsonEnvelope(businessJson, true));
        assertEquals(0, st.getQualifyingTabularSuccessCount());
    }

    /**
     * Escaping amplification: a 5,000-character column name of control characters is 5,000 raw bytes and 30,000
     * encoded bytes. 48,049 characters of input and 6,000 cells, about 30 MB by raw UTF-8 (inside the 32 MB
     * budget) but at least 180 MB as the codec writes it. Refused before any table is built.
     */
    @Test
    void jsonRootRows_escapedColumnName_refusedOnTheCodecBound_notOnRawUtf8() throws Exception {
        ArrayNode rows = MAPPER.createArrayNode();
        rows.addObject().put(String.valueOf((char) 1).repeat(5_000), 0);
        for (int r = 1; r < 6_000; r++) {
            rows.addObject();
        }
        ObjectNode business = MAPPER.createObjectNode();
        business.put("status", "success");
        business.put("hasMore", false);
        business.set("rows", rows);
        String businessJson = MAPPER.writeValueAsString(business);
        assertTrue(businessJson.length() < 65_536, "under the JSON character cap: " + businessJson.length());
        assertFalse(TabularChartRoundHooks.withinPromotionExpansionBudget(rows));
        assertEquals(0, feedFreshRound("tcrh-json-escaped-name", jsonEnvelope(businessJson, true))
                .getQualifyingTabularSuccessCount());
    }

    /** A complete 25-row JSON envelope padded to exactly {@code totalChars} characters. */
    private static String completeEnvelopeOfLength(int totalChars) throws Exception {
        String probe = envelopeWithPadding(0);
        String out = envelopeWithPadding(totalChars - probe.length());
        assertEquals(totalChars, out.length());
        return out;
    }

    private static String envelopeWithPadding(int padChars) throws Exception {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < 25; i++) {
            rows.addObject().put("state", "s" + i).put("value", i + 1);
        }
        ((ObjectNode) rows.get(0)).put("description", "d".repeat(padChars));
        ObjectNode business = MAPPER.createObjectNode();
        business.put("status", "success");
        business.put("hasMore", false);
        business.set("rows", rows);
        return jsonEnvelope(MAPPER.writeValueAsString(business), false);
    }

    /**
     * Extended tools format through the direct-service formatter, which has no LARGE_JSON classifier, so the hook
     * itself keeps promotion inside the middle band: at the invoke character cap a complete table is promoted, one
     * character above it is not, on the normal path and on the approved-HITL path alike.
     */
    @Test
    void jsonRootRows_aboveTheInvokeCharCap_isNeverPromoted_whateverToolProducedIt() throws Exception {
        int cap = InvokeServiceExecutor.INVOKE_SERVICE_RESULT_CHAR_CAP;
        String atCap = completeEnvelopeOfLength(cap);
        String overCap = completeEnvelopeOfLength(cap + 1);

        TabularChartRoundState st = feedFreshRound("tcrh-json-band-at", atCap);
        assertEquals(1, st.getQualifyingTabularSuccessCount(), "at the cap: still the middle band");
        assertNotNull(st.getLastCacheId());

        AgentToolContext.setConversationId("tcrh-json-band-over");
        AgentToolContext.resetTabularChartRound();
        assertEquals(overCap, TabularChartRoundHooks.afterBuiltInToolResult("any_extended_tool", overCap));
        assertEquals(0, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
        assertEquals(overCap, TabularChartRoundHooks.augmentJsonSourceHandle("any_extended_tool", overCap),
                "the approved path stores nothing either");
    }

    @Test
    void jsonRootRows_approvedBodyAtTheCap_stillRegistersOnTheSecondPass_thoughItsHandleMadeItLonger()
            throws Exception {
        String atCap = completeEnvelopeOfLength(InvokeServiceExecutor.INVOKE_SERVICE_RESULT_CHAR_CAP);
        AgentToolContext.setConversationId("tcrh-json-band-carried");
        AgentToolContext.resetTabularChartRound();
        String approved = TabularChartRoundHooks.augmentJsonSourceHandle("any_extended_tool", atCap);
        assertTrue(approved.length() > InvokeServiceExecutor.INVOKE_SERVICE_RESULT_CHAR_CAP);
        TabularChartRoundHooks.afterBuiltInToolResult("any_extended_tool", approved);
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(1, st.getQualifyingTabularSuccessCount(), "the carried handle is not counted against the band");
        assertEquals(MAPPER.readTree(approved).path("cacheId").asText(), st.getLastCacheId());
    }

    /**
     * One deadline for the whole promotion. The observer spends the 50 ms budget between qualification and table
     * construction; neither path may then build, store or register.
     */
    @Test
    void jsonRootRows_deadlinePassedAfterQualification_abandonsThePromotion_onBothPaths() throws Exception {
        AgentToolContext.setConversationId("tcrh-json-deadline");
        AgentToolContext.resetTabularChartRound();
        AgentToolContext.setRunInvocationContext(com.thingworx.things.agent.execution.RunInvocationContext.of(
                "inv-tr1-deadline", com.thingworx.things.agent.execution.ExecutionScope.CONVERSATION,
                "scope-tr1-deadline",
                com.thingworx.things.agent.execution.BudgetVector.builder().maxWallTimeMillis(50).build()));
        int[] observed = {0};
        TabularChartRoundHooks.promotionBuildObserverForTests = () -> {
            observed[0]++;
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            String envelope = jsonEnvelope(rowsOfSizeWithPaging(25), true);
            assertEquals(envelope, TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", envelope));
            assertEquals(0, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
            assertEquals(envelope, TabularChartRoundHooks.augmentJsonSourceHandle("invoke_service", envelope));
            assertEquals(2, observed[0], "both paths reached the build boundary and stopped there");

            TabularChartRoundHooks.promotionBuildObserverForTests = null;
            String promoted = TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", envelope);
            assertTrue(MAPPER.readTree(promoted).hasNonNull("cacheId"), "control: without the delay it promotes");
        } finally {
            TabularChartRoundHooks.promotionBuildObserverForTests = null;
        }
    }

    @Test
    void jsonRootRows_overCellBudget_notPromoted() throws Exception {
        // 5,001 rows x 400 columns = 2,000,400 cells, just over the 2,000,000-cell budget. Only row 0 is wide;
        // the budget multiplies by row 0's column count, exactly as infoTableFromJsonRows takes its columns.
        ArrayNode rows = MAPPER.createArrayNode();
        ObjectNode wide = rows.addObject();
        for (int c = 0; c < 400; c++) {
            wide.put("c" + c, 0);
        }
        for (int r = 1; r < 5_001; r++) {
            rows.addObject();
        }
        assertFalse(TabularChartRoundHooks.withinPromotionExpansionBudget(rows));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"rows\":[]", "\"rows\":[{\"a\":1},2]", "\"rows\":[{\"a\":1},null]", "\"rows\":{\"a\":1}",
            "\"rows\":\"[{\\\"a\\\":1}]\"", "\"table\":[{\"a\":1}]"})
    void jsonRootRows_rowsNotNonEmptyObjectArray_notRegistered(String rowsFragment) throws Exception {
        TabularChartRoundState st = feedFreshRound("tcrh-json-shape-" + rowsFragment.length(),
                jsonEnvelope("{\"status\":\"success\"," + rowsFragment + "}", true));
        assertEquals(0, st.getQualifyingTabularSuccessCount());
    }

    @Test
    void jsonRootRows_resultNotDecodableToObject_notRegistered() throws Exception {
        ObjectNode broken = MAPPER.createObjectNode().put("status", "success").put("resultKind", "JSON")
                .put("result", "{\"status\":\"success\",\"rows\":[{\"a\":1}");
        assertEquals(0, feedFreshRound("tcrh-json-broken", MAPPER.writeValueAsString(broken))
                .getQualifyingTabularSuccessCount());
        ObjectNode arrayResult = MAPPER.createObjectNode().put("status", "success").put("resultKind", "JSON");
        arrayResult.set("result", MAPPER.readTree("[{\"a\":1}]"));
        assertEquals(0, feedFreshRound("tcrh-json-array", MAPPER.writeValueAsString(arrayResult))
                .getQualifyingTabularSuccessCount());
        ObjectNode numberResult = MAPPER.createObjectNode().put("status", "success").put("resultKind", "JSON")
                .put("result", 42);
        assertEquals(0, feedFreshRound("tcrh-json-number", MAPPER.writeValueAsString(numberResult))
                .getQualifyingTabularSuccessCount());
    }

    @Test
    void jsonRootRows_largeJsonEnvelope_notRegistered() throws Exception {
        String envelope = "{\"status\":\"success\",\"resultKind\":\"LARGE_JSON\",\"cacheId\":\"c1\","
                + "\"result\":" + summaryBusiness("\"status\":\"success\",") + "}";
        assertEquals(0, feedFreshRound("tcrh-json-large", envelope).getQualifyingTabularSuccessCount());
    }

    @Test
    void jsonRootRows_compositeResultWithoutRootRows_notRegistered() throws Exception {
        String overview = "{\"status\":\"partial\",\"machineCoverage\":{\"included\":false},"
                + "\"stateSummary\":{\"included\":true,\"status\":\"success\","
                + "\"rows\":[{\"utilizationState\":\"Running\",\"sumDurationSeconds\":1200}]},"
                + "\"stats\":{},\"evidenceGaps\":[\"Machine coverage unavailable\"]}";
        TabularChartRoundState st = feedFreshRound("tcrh-json-overview", jsonEnvelope(overview, true));
        assertEquals(0, st.getQualifyingTabularSuccessCount());
        String rootSuccessNestedRows = "{\"status\":\"success\",\"stateSummary\":{\"status\":\"success\","
                + "\"rows\":[{\"utilizationState\":\"Running\",\"sumDurationSeconds\":1200}]}}";
        assertEquals(0, feedFreshRound("tcrh-json-nested", jsonEnvelope(rootSuccessNestedRows, true))
                .getQualifyingTabularSuccessCount());
    }

    @Test
    void unsupportedJson_leavesPriorQualifyingSnapshotUnchanged() throws Exception {
        AgentToolContext.setConversationId("tcrh-json-keeps-prior");
        AgentToolContext.resetTabularChartRound();
        TabularChartRoundHooks.afterBuiltInToolResult("tool_one",
                "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rows\":[{\"a\":1}]}");
        TabularChartRoundHooks.afterBuiltInToolResult("tool_two",
                jsonEnvelope(summaryBusiness("\"status\":\"success\",\"hasMore\":true,"), true));
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(1, st.getQualifyingTabularSuccessCount());
        assertEquals(1, st.getLastInlineRows().size());
        assertEquals(1, st.getLastInlineRows().get(0).get("a").asInt());
    }

    @Test
    void jsonRootRows_laterQualifyingJson_becomesLatestLastInvoke() throws Exception {
        AgentToolContext.setConversationId("tcrh-json-latest-wins");
        AgentToolContext.resetTabularChartRound();
        TabularChartRoundHooks.afterBuiltInToolResult("tool_one",
                "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rows\":[{\"a\":1}]}");
        TabularChartRoundHooks.afterBuiltInToolResult("tool_two", jsonEnvelope(summaryBusiness(""), true));
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(2, st.getQualifyingTabularSuccessCount());
        assertEquals(3, st.getLastInlineRows().size());
    }

    @Test
    void jsonRootRows_inlineRegistration_clearsConversationTokenMirror() throws Exception {
        AgentToolContext.setConversationId("tcrh-json-mirror");
        AgentToolContext.resetTabularChartRound();
        TabularCacheHandleMirror.recordQualifyingCacheId("cache-from-earlier-turn");
        assertEquals("cache-from-earlier-turn", TabularCacheHandleMirror.resolveConversationMirror());
        TabularChartRoundHooks.afterBuiltInToolResult("any_extended_tool",
                jsonEnvelope(summaryBusiness("\"status\":\"success\","), true));
        assertNull(TabularCacheHandleMirror.resolveConversationMirror());
    }

    @Test
    void lastInvoke_withoutQualifyingSource_messageSteersToSingleTableTool() throws Exception {
        AgentToolContext.setConversationId("tcrh-no-source-message");
        AgentToolContext.resetTabularChartRound();
        String[] err = new String[1];
        JsonNode args = MAPPER.readTree("{\"source\":\"last_invoke\",\"xColumn\":\"x\",\"yColumn\":\"y\",\"kind\":\"pie\"}");
        assertNull(TabularChartSourceResolver.resolveOrError(args, err));
        JsonNode e = MAPPER.readTree(err[0]);
        assertEquals("SOURCE_RESULT_NOT_TABULAR", e.get("code").asText());
        assertEquals("no_qualifying_tabular_tool", e.get("details").get("reason").asText());
        String message = e.get("message").asText();
        assertTrue(message.contains("single table"), message);
        assertTrue(message.contains("INFOTABLE") && message.contains("JSON"), message);
        assertTrue(message.contains("text table"), message);
        assertTrue(message.contains("Do not guess service names"), message);
        assertNotNull(message);
    }


    // ---- D1 distribution operators (chart-enhancement design §7.4): SC-1 / SC-2 through hook → state → resolver ----

    private static com.thingworx.types.InfoTable numericSource(String column, double... values) {
        com.thingworx.metadata.DataShapeDefinition shape = new com.thingworx.metadata.DataShapeDefinition();
        com.thingworx.metadata.FieldDefinition fd = new com.thingworx.metadata.FieldDefinition();
        fd.setName(column);
        fd.setBaseType(com.thingworx.types.BaseTypes.NUMBER);
        shape.addFieldDefinition(fd);
        com.thingworx.types.InfoTable src = new com.thingworx.types.InfoTable(shape);
        for (double v : values) {
            com.thingworx.types.collections.ValueCollection row = new com.thingworx.types.collections.ValueCollection();
            row.put(column, new com.thingworx.types.primitives.NumberPrimitive(v));
            src.addRow(row);
        }
        return src;
    }

    /** Runs a real tabulate call and feeds its body through the hook exactly as the tool loop does. */
    private static JsonNode tabulateThroughHook(String cacheId, String argsWithoutCache) throws Exception {
        String args = "{\"cacheId\":\"" + cacheId + "\"," + argsWithoutCache + "}";
        String body = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new com.thingworx.things.agent.llm.ToolCall("d1", "tabulate_cached_result", args));
        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", body);
        return MAPPER.readTree(body);
    }

    private static String inlineTableBody(String cacheId) {
        return "{\"status\":\"success\",\"resultKind\":\"INFOTABLE_LARGE\",\"cacheId\":\"" + cacheId
                + "\",\"columns\":[{\"name\":\"v\"}]}";
    }

    @Test
    void sc1_distributionOutputBecomesLastInvokeAndPresentationArtifact_forBothModes() throws Exception {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
        try {
            for (String mode : new String[] {"bin_numeric", "box_summary"}) {
                AgentToolContext.setConversationId("sc1-" + mode);
                AgentToolContext.resetTabularChartRound();
                String turnKey = FetchCachedReplayGuard.resolveCurrentTurnKey();
                PresentationArtifactRegistry.removeTurn(turnKey);
                String tableA = InvokeServiceExecutor.storeInfotableInConversationCache(numericSource("v", 1, 2, 3, 4, 5, 6));
                TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", inlineTableBody(tableA));
                assertEquals(tableA, AgentToolContext.tabularChartRoundState().getLastCacheId(), "A is the target first");
                String modeArgs = "bin_numeric".equals(mode)
                        ? "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0,3,6]"
                        : "\"mode\":\"box_summary\",\"column\":\"v\"";
                JsonNode out = tabulateThroughHook(tableA, modeArgs);
                assertEquals("success", out.get("status").asText(), out.toString());
                String tableB = out.get("cacheId").asText();
                assertFalse(tableB.isEmpty());
                assertEquals(tableA, out.get("sourceCacheId").asText(), "the envelope names the input as sourceCacheId");
                assertTrue(out.get("columns").size() >= 10, "real output columns");
                assertEquals(out.get("rows").size(), out.get("totalRows").asInt());
                TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
                assertEquals(2, st.getQualifyingTabularSuccessCount());
                assertEquals(tableB, st.getLastCacheId(), mode + ": last_invoke targets the derived output B, not A");
                assertTrue(st.isLastChartRescueDataCompleteEnough());
                String[] err = new String[1];
                JsonNode chartArgs = MAPPER.readTree("{\"source\":\"last_invoke\",\"xColumn\":\"binIndex\",\"yColumn\":\"count\",\"kind\":\"bar\"}");
                TabularChartSourceResolver.Resolved viaLast = TabularChartSourceResolver.resolveOrError(chartArgs, err);
                assertNotNull(viaLast, err[0]);
                assertEquals(tableB, viaLast.cacheId, "last_invoke resolves to B");
                JsonNode explicitArgs = MAPPER.readTree("{\"source\":\"cache_id\",\"cacheId\":\"" + tableB
                        + "\",\"xColumn\":\"binIndex\",\"yColumn\":\"count\",\"kind\":\"bar\"}");
                TabularChartSourceResolver.Resolved viaId = TabularChartSourceResolver.resolveOrError(explicitArgs, err);
                assertNotNull(viaId, err[0]);
                assertEquals(viaLast.table.getRowCount(), viaId.table.getRowCount(), "explicit cache_id hits the same B");
                assertTrue(PresentationArtifactRegistry.orderedCacheIds(turnKey).contains(tableB),
                        mode + ": B is registered as a presentation artifact (future direct parent table)");
                PresentationArtifactRegistry.removeTurn(turnKey);
            }
        } finally {
            com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        }
    }

    @Test
    void sc2_distributionEmptyClearsLastInvoke_whileExplicitCacheIdAndOldEmptySemanticsAreUnchanged() throws Exception {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
        try {
            for (String mode : new String[] {"bin_numeric", "box_summary"}) {
                AgentToolContext.setConversationId("sc2-" + mode);
                AgentToolContext.resetTabularChartRound();
                String tableA = InvokeServiceExecutor.storeInfotableInConversationCache(numericSource("v", 100, 200));
                TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", inlineTableBody(tableA));
                TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
                assertTrue(st.hasChartableLastInvokeTarget());
                String chartSeqBefore = st.nextChartId();
                String modeArgs = "bin_numeric".equals(mode)
                        ? "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0,5,10]"
                        : "\"mode\":\"box_summary\",\"column\":\"v\",\"filters\":{\"type\":\"GT\",\"fieldName\":\"v\",\"value\":1000}";
                JsonNode out = tabulateThroughHook(tableA, modeArgs);
                assertEquals("success", out.get("status").asText(), out.toString());
                assertTrue(out.get("resultKind").asText().endsWith("_EMPTY"), out.get("resultKind").asText());
                assertFalse(st.hasChartableLastInvokeTarget(), mode + " EMPTY clears the chartable target");
                assertNull(st.getLastCacheId());
                assertNull(st.getLastInlineRows());
                assertEquals(2, st.getQualifyingTabularSuccessCount(), "the success count still increments (no reset())");
                assertEquals("c2", st.nextChartId(), "the chart sequence is untouched (" + chartSeqBefore + " was c1)");
                String[] err = new String[1];
                JsonNode chartArgs = MAPPER.readTree("{\"source\":\"last_invoke\",\"xColumn\":\"v\",\"yColumn\":\"v\",\"kind\":\"bar\"}");
                assertNull(TabularChartSourceResolver.resolveOrError(chartArgs, err));
                assertTrue(err[0].contains("SOURCE_RESULT_NOT_TABULAR"), err[0]);
                assertFalse(err[0].contains("no_qualifying_tabular_tool"), "the count is above zero, so the missing-target branch is taken");
                JsonNode explicitArgs = MAPPER.readTree("{\"source\":\"cache_id\",\"cacheId\":\"" + tableA
                        + "\",\"xColumn\":\"v\",\"yColumn\":\"v\",\"kind\":\"bar\"}");
                TabularChartSourceResolver.Resolved viaId = TabularChartSourceResolver.resolveOrError(explicitArgs, err);
                assertNotNull(viaId, err[0]);
                assertEquals(2, viaId.table.getRowCount(), "an explicit cache_id for A still charts A");
            }
            // Control: the existing group_metric EMPTY keeps the previous table as the last_invoke target.
            AgentToolContext.setConversationId("sc2-control");
            AgentToolContext.resetTabularChartRound();
            String tableA = InvokeServiceExecutor.storeInfotableInConversationCache(numericSource("v", 1, 2));
            TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", inlineTableBody(tableA));
            JsonNode gm = tabulateThroughHook(tableA, "\"mode\":\"group_metric\",\"groupBy\":[\"v\"],"
                    + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}],"
                    + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"v\",\"value\":1000}");
            assertEquals("CACHED_GROUP_METRIC_EMPTY", gm.get("resultKind").asText(), gm.toString());
            TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
            assertTrue(st.hasChartableLastInvokeTarget(), "unchanged: group_metric EMPTY leaves A as the target");
            assertEquals(tableA, st.getLastCacheId());
        } finally {
            com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        }
    }
}
