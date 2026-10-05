package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;
import com.thingworx.things.agent.tools.ToolExecutor;
import com.thingworx.things.agent.tools.TabularChartRoundHooks;
import com.thingworx.things.agent.tools.PendingApprovalRecord;
import com.thingworx.things.agent.tools.PendingApprovalStore;
import com.thingworx.things.agent.tools.ParlerHitlStreamScopedEnqueue;
import com.thingworx.things.agent.tools.TabularCacheHandleMirror;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.BuildChartFromTabularResultExecutor;
import com.thingworx.things.agent.tools.BuildChartFromTabularResultToolSchema;

/** Exercises the actual loop/egress/enqueue boundary and an explicit same-request continuation on a fresh context. */
class AgentLoopHitlContinuationChartSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SUMMARY_BUSINESS = "{\"status\":\"success\",\"rows\":["
            + "{\"utilizationState\":\"Down\",\"sumDurationSeconds\":51368},"
            + "{\"utilizationState\":\"Unavailable\",\"sumDurationSeconds\":34251},"
            + "{\"utilizationState\":\"Running\",\"sumDurationSeconds\":780}],"
            + "\"stats\":{\"utilizationPercent\":1.5},\"evidenceGaps\":[]}";

    private static final String INFOTABLE_SUMMARY = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rows\":["
            + "{\"utilizationState\":\"Down\",\"sumDurationSeconds\":51368},"
            + "{\"utilizationState\":\"Unavailable\",\"sumDurationSeconds\":34251},"
            + "{\"utilizationState\":\"Running\",\"sumDurationSeconds\":780}]}";

    private static final String PIE_ARGS = "{\"source\":\"last_invoke\",\"kind\":\"pie\",\"xColumn\":\"utilizationState\","
            + "\"yColumn\":\"sumDurationSeconds\",\"pieSliceMode\":\"all_nonzero\"}";

    AgentLoopHitlContinuationChartSourceTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @BeforeEach
    void installLlmCallRecorderTestSink() {
        LlmCallRecorder.resetForTest();
    }

    @AfterEach
    void tearDown() {
        LlmCallRecorder.resetForTest();
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
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

    private static LlmResponse textResponse(String text) {
        return new LlmResponse(text, Collections.emptyList(), LlmResponse.FinishReason.STOP,
                1, 2, 1, 2, 0, 0, 0, "rid", 0L);
    }

    private static LlmResponse toolCallsResponse(List<ToolCall> calls) {
        return new LlmResponse("", calls, LlmResponse.FinishReason.TOOL_CALLS,
                3, 4, 3, 4, 0, 0, 0, "rid2", 0L);
    }

    private static List<ToolDefinition> chartToolOnly() {
        return List.of(new ToolDefinition("build_chart_from_tabular_result", "chart",
                BuildChartFromTabularResultToolSchema.parametersSchema(), true));
    }

    private static final String NON_TABULAR = "{\"status\":\"success\",\"resultKind\":\"STRING\",\"result\":\"ok\"}";
    private static final String REJECTED = "{\"status\":\"error\",\"code\":\"HITL_REJECTED\"}";

    /** Real loop appends compacted results and the production enqueue captures the source before throwing. */
    private static PendingApprovalRecord pauseWithResults(String conversationId, List<String> rawResults)
            throws Exception {
        AgentToolContext.setConversationId(conversationId);
        AgentToolContext.setParlerStreamIds("request-" + conversationId, conversationId);
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user("show the state breakdown as a pie chart")));
        AgentToolContext.setParlerActiveMessages(messages);
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < rawResults.size(); i++) {
            calls.add(new ToolCall("summary-" + i, "state_summary", "{\"sequence\":" + i + "}"));
        }
        calls.add(new ToolCall("gated", "invoke_service", "{}"));
        ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(toolCallsResponse(calls));
        ToolExecutor executor = tc -> {
            if ("invoke_service".equals(tc.getFunctionName())) {
                ParlerHitlStreamScopedEnqueue.enqueueOrThrow("principal", "AgentJUnit", tc, null, null, null, null, null);
                throw new AssertionError("approval must pause");
            }
            String raw = rawResults.get(Integer.parseInt(tc.getId().substring("summary-".length())));
            TabularChartRoundHooks.afterBuiltInToolResult(tc.getFunctionName(), raw);
            return raw;
        };
        AgentLoop.AgentResult result = new AgentLoop(llm, executor, null, 0, 8192, 8, 60_000L)
                .run(messages, chartToolOnly());
        assertEquals(AgentLoop.AgentResult.Status.AWAITING_APPROVAL, result.getStatus());
        PendingApprovalRecord pending = PendingApprovalStore.remove(result.getApprovalPendingId());
        assertNotNull(pending);
        assertNotNull(pending.getTabularChartRoundSnapshot());
        return pending;
    }

    private static List<ChatMessage> bindContinuation(PendingApprovalRecord pending, String completedResult) {
        AgentToolContext.clear(); // A new approval worker must not rely on the original thread's state.
        AgentToolContext.setConversationId(pending.getConversationId());
        List<ChatMessage> messages = new ArrayList<>(pending.getMessagesCopy());
        // Mirrors AgentThing.runParlerApprovalContinuation: the handle is added before the result is
        // persisted, so the durable row and the next model request carry it.
        String persisted = TabularChartRoundHooks.augmentJsonSourceHandle(
                pending.getGatedToolCall().getFunctionName(), completedResult);
        HitlSyntheticToolResultAppender.appendDurable(messages, pending.getGatedToolCall().getId(), persisted,
                "", "", "AgentJUnit", pending.getGatedToolCall().getFunctionName());
        ParlerHitlContinuationContext.bindForGatedToolExecution(null, pending, null, null, messages);
        return messages;
    }

    private static JsonNode runChart(List<ChatMessage> messages, PendingApprovalRecord pending, String completedResult)
            throws Exception {
        AgentToolContext.resetTabularChartRound(); // The reset before loop entry must not destroy the captured source.
        AgentToolContext.drainPendingParlerChartBlocks();
        ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(toolCallsResponse(List.of(new ToolCall("chart", "build_chart_from_tabular_result", PIE_ARGS))));
        llm.enqueue(textResponse("the chart shows the breakdown"));
        AtomicReference<String> toolResult = new AtomicReference<>();
        ToolExecutor executor = tc -> {
            String result = BuildChartFromTabularResultExecutor.execute(tc);
            toolResult.set(result);
            return result;
        };
        AgentLoop loop = new AgentLoop(llm, executor, null, 0, 8192, 8, 60_000L);
        AgentLoop.AgentResult result = pending == null ? loop.run(messages, chartToolOnly())
                : loop.runAfterApproval(messages, chartToolOnly(), null, pending, completedResult);
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, result.getStatus(), result.getErrorCode());
        assertNotNull(toolResult.get());
        return MAPPER.readTree(toolResult.get());
    }

    private static JsonNode resumeAndChart(PendingApprovalRecord pending, String completedResult) throws Exception {
        return runChart(bindContinuation(pending, completedResult), pending, completedResult);
    }

    private static void assertThreeSlicePieEmitted(JsonNode chartResult) throws Exception {
        assertEquals("success", chartResult.path("status").asText(), chartResult.toString());
        assertEquals("CHART_EMITTED", chartResult.path("code").asText(), chartResult.toString());
        assertEquals(3, chartResult.path("pointCount").asInt());
        List<JSONObject> blocks = AgentToolContext.drainPendingParlerChartBlocks();
        assertEquals(1, blocks.size());
        JsonNode chart = MAPPER.readTree(blocks.get(0).toString());
        assertEquals("pie", chart.path("kind").asText());
        assertEquals(1, chart.path("series").size());
        JsonNode x = chart.path("series").get(0).path("x");
        JsonNode y = chart.path("series").get(0).path("y");
        assertEquals(3, x.size());
        assertEquals(3, y.size());
        java.util.Map<String, Double> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 3; i++) values.put(x.get(i).asText(), y.get(i).asDouble());
        assertEquals(java.util.Map.of("Down", 51368.0, "Unavailable", 34251.0, "Running", 780.0), values);
    }

    private static void assertNoSource(JsonNode result) {
        assertEquals("SOURCE_RESULT_NOT_TABULAR", result.path("code").asText(), result.toString());
        assertEquals("no_qualifying_tabular_tool", result.path("details").path("reason").asText());
        assertTrue(AgentToolContext.drainPendingParlerChartBlocks().isEmpty());
    }

    @Test
    void approvedJsonReplacesEarlierSource_andIsRecordedOnlyOnce() throws Exception {
        String earlier = SUMMARY_BUSINESS.replace("51368", "1").replace("34251", "2").replace("780", "3");
        PendingApprovalRecord pending = pauseWithResults("hitl-latest", List.of(jsonEnvelope(earlier, true)));
        assertThreeSlicePieEmitted(resumeAndChart(pending, jsonEnvelope(SUMMARY_BUSINESS, true)));
        assertEquals(2, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
    }

    @Test
    void compactedStringHistory_doesNotLoseLatestExactSource() throws Exception {
        ObjectNode business = (ObjectNode) MAPPER.readTree(SUMMARY_BUSINESS);
        business.put("notes", "x".repeat(3000));
        String earlier = SUMMARY_BUSINESS.replace("51368", "1");
        PendingApprovalRecord pending = pauseWithResults("hitl-long-string",
                List.of(jsonEnvelope(earlier, true), jsonEnvelope(business.toString(), true)));
        ChatMessage latest = pending.getMessagesCopy().stream()
                .filter(m -> "summary-1".equals(m.getToolCallId())).findFirst().orElseThrow();
        assertTrue(latest.getContent().contains("_egress"), "prior result must pass through real egress");
        assertThreeSlicePieEmitted(resumeAndChart(pending, NON_TABULAR));
        assertEquals(2, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
    }

    @Test
    void sampledOverLimitObjectHistory_neverBecomesQualifying() throws Exception {
        ObjectNode business = MAPPER.createObjectNode().put("status", "success");
        com.fasterxml.jackson.databind.node.ArrayNode rows = business.putArray("rows");
        for (int i = 0; i < 60; i++) rows.addObject().put("utilizationState", "state-" + i).put("sumDurationSeconds", i + 1);
        PendingApprovalRecord pending = pauseWithResults("hitl-sampled", List.of(jsonEnvelope(business.toString(), false)));
        ChatMessage prior = pending.getMessagesCopy().stream().filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .findFirst().orElseThrow();
        assertEquals(20, MAPPER.readTree(prior.getContent()).path("result").path("rows").size());
        assertNoSource(resumeAndChart(pending, NON_TABULAR));
    }

    @Test
    void approvedNativeInfotable_continuationCharts() throws Exception {
        assertThreeSlicePieEmitted(resumeAndChart(pauseWithResults("hitl-native", List.of()), INFOTABLE_SUMMARY));
    }

    @Test
    void priorNativeInfotable_nonTabularApprovalKeepsSource() throws Exception {
        assertThreeSlicePieEmitted(resumeAndChart(pauseWithResults("hitl-prior-native", List.of(INFOTABLE_SUMMARY)), NON_TABULAR));
    }

    @Test
    void rejectedApproval_withoutEarlierSource_reportsNoSource() throws Exception {
        assertNoSource(resumeAndChart(pauseWithResults("hitl-rejected", List.of()), REJECTED));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"HITL_REJECTED", "SERVICE_FAILED", "HITL_CANCELLED"})
    void unsuccessfulApproval_preservesPriorSourceAndMirror(String code) throws Exception {
        PendingApprovalRecord pending = pauseWithResults("hitl-failed-" + code,
                List.of(jsonEnvelope(SUMMARY_BUSINESS, false)));
        TabularCacheHandleMirror.recordQualifyingCacheId("mirror-from-summarize");
        assertThreeSlicePieEmitted(resumeAndChart(pending, "{\"status\":\"error\",\"code\":\"" + code + "\"}"));
        assertEquals("mirror-from-summarize", TabularCacheHandleMirror.resolveConversationMirror());
        assertEquals(1, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
    }

    @Test
    void approvedInlineJson_clearsStaleConversationMirror() throws Exception {
        PendingApprovalRecord pending = pauseWithResults("hitl-inline-mirror", List.of());
        TabularCacheHandleMirror.recordQualifyingCacheId("earlier-cache");
        assertThreeSlicePieEmitted(resumeAndChart(pending, jsonEnvelope(SUMMARY_BUSINESS, true)));
        assertNull(TabularCacheHandleMirror.resolveConversationMirror());
    }

    @Test
    void approvedCachedInfotable_updatesMirrorForFollowingTurn() throws Exception {
        PendingApprovalRecord pending = pauseWithResults("hitl-cache-mirror", List.of());
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(
                ParlerTabularChartBuilder.infoTableFromJsonRows(MAPPER.readTree(SUMMARY_BUSINESS).get("rows")));
        assertThreeSlicePieEmitted(resumeAndChart(pending,
                "{\"status\":\"success\",\"resultKind\":\"INFOTABLE_LARGE\",\"cacheId\":\"" + cid + "\"}"));
        AgentToolContext.resetTabularChartRound();
        assertEquals(cid, TabularCacheHandleMirror.resolveConversationMirror());
    }

    @Test
    void ordinaryRun_ignoresOldOrUnanchoredToolHistory() throws Exception {
        PendingApprovalRecord pending = pauseWithResults("hitl-new-request", List.of(jsonEnvelope(SUMMARY_BUSINESS, true)));
        List<ChatMessage> messages = bindContinuation(pending, NON_TABULAR);
        messages.add(ChatMessage.user("a new question"));
        assertNoSource(runChart(messages, null, null));
        List<ChatMessage> unanchored = new ArrayList<>(List.of(ChatMessage.toolResult("old", INFOTABLE_SUMMARY, "invoke_service")));
        assertNoSource(runChart(unanchored, null, null));
    }

    @Test
    void snapshot_isDefensive_andSurvivesInterruptedSiblingAttachment() throws Exception {
        PendingApprovalRecord pending = pauseWithResults("hitl-snapshot", List.of(jsonEnvelope(SUMMARY_BUSINESS, true)));
        ((ObjectNode) AgentToolContext.tabularChartRoundState().getLastInlineRows().get(0)).put("sumDurationSeconds", -1);
        pending = pending.withInterruptedBatchSiblings(List.of(new ToolCall("sibling", "another_tool", "{}")));
        assertThreeSlicePieEmitted(resumeAndChart(pending, NON_TABULAR));
        ((ObjectNode) AgentToolContext.tabularChartRoundState().getLastInlineRows().get(0)).put("sumDurationSeconds", -2);
        assertThreeSlicePieEmitted(resumeAndChart(pending, NON_TABULAR));
    }

    @Test
    void repeatedApprovalPause_keepsLatestSourceAcrossWorkers() throws Exception {
        PendingApprovalRecord first = pauseWithResults("hitl-repeat", List.of());
        String approved = jsonEnvelope(SUMMARY_BUSINESS, true);
        List<ChatMessage> messages = bindContinuation(first, approved);
        ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(toolCallsResponse(List.of(new ToolCall("second-gate", "invoke_service", "{\"next\":true}"))));
        ToolExecutor executor = tc -> {
            ParlerHitlStreamScopedEnqueue.enqueueOrThrow("principal", "AgentJUnit", tc, null, null, null, null, null);
            throw new AssertionError("approval must pause");
        };
        AgentLoop.AgentResult result = new AgentLoop(llm, executor, null, 0, 8192, 8, 60_000L)
                .runAfterApproval(messages, chartToolOnly(), null, first, approved);
        assertEquals(AgentLoop.AgentResult.Status.AWAITING_APPROVAL, result.getStatus());
        PendingApprovalRecord second = PendingApprovalStore.remove(result.getApprovalPendingId());
        assertNotNull(second);
        assertThreeSlicePieEmitted(resumeAndChart(second, NON_TABULAR));
        assertEquals(1, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
    }

    /**
     * Chart ids are scoped to the user request. A chart built before an approval pause and one built after it
     * belong to the same request, so the second must not reuse the first id — the client keys chart artifacts
     * by chartId, and a duplicate makes two different charts collide.
     */
    @Test
    void chartIdsContinueAcrossAnApprovalPause_withinOneRequest() throws Exception {
        AgentToolContext.setConversationId("hitl-chart-id");
        AgentToolContext.setParlerStreamIds("request-hitl-chart-id", "hitl-chart-id");
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user("pie charts for both machines")));
        AgentToolContext.setParlerActiveMessages(messages);

        ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(toolCallsResponse(List.of(
                new ToolCall("summary-a", "state_summary", "{}"),
                new ToolCall("chart-a", "build_chart_from_tabular_result", PIE_ARGS),
                new ToolCall("gated", "invoke_service", "{}"))));
        AtomicReference<String> firstChart = new AtomicReference<>();
        ToolExecutor executor = tc -> {
            if ("invoke_service".equals(tc.getFunctionName())) {
                ParlerHitlStreamScopedEnqueue.enqueueOrThrow("principal", "AgentJUnit", tc, null, null, null, null, null);
                throw new AssertionError("approval must pause");
            }
            if ("build_chart_from_tabular_result".equals(tc.getFunctionName())) {
                String out = BuildChartFromTabularResultExecutor.execute(tc);
                firstChart.set(out);
                return out;
            }
            String raw = jsonEnvelope(SUMMARY_BUSINESS, true);
            TabularChartRoundHooks.afterBuiltInToolResult(tc.getFunctionName(), raw);
            return raw;
        };
        AgentLoop.AgentResult paused = new AgentLoop(llm, executor, null, 0, 8192, 8, 60_000L)
                .run(messages, chartToolOnly());
        assertEquals(AgentLoop.AgentResult.Status.AWAITING_APPROVAL, paused.getStatus());

        assertNotNull(firstChart.get());
        assertEquals("c1", MAPPER.readTree(firstChart.get()).path("chartId").asText());

        PendingApprovalRecord pending = PendingApprovalStore.remove(paused.getApprovalPendingId());
        assertNotNull(pending);
        assertNotNull(pending.getTabularChartRoundSnapshot());

        JsonNode second = resumeAndChart(pending, jsonEnvelope(SUMMARY_BUSINESS, true));
        assertEquals("CHART_EMITTED", second.path("code").asText(), second.toString());
        assertEquals("c2", second.path("chartId").asText(),
                "the post-approval chart must continue the request's numbering, not restart at c1");
    }

    /** A brand new user request is its own scope and starts over at c1. */
    @Test
    void aNewRequestRestartsChartNumbering() throws Exception {
        AgentToolContext.setConversationId("chart-id-scope");
        AgentToolContext.resetTabularChartRound();
        assertEquals("c1", AgentToolContext.nextParlerChartId());
        assertEquals("c2", AgentToolContext.nextParlerChartId());

        AgentToolContext.resetTabularChartRound();
        assertEquals("c1", AgentToolContext.nextParlerChartId());
    }

    /** Restoring a pending snapshot twice must not consume ids from the shared record. */
    @Test
    void restoringTheSameSnapshotTwiceYieldsTheSameNextChartId() throws Exception {
        AgentToolContext.setConversationId("chart-id-snapshot-copy");
        AgentToolContext.resetTabularChartRound();
        AgentToolContext.nextParlerChartId();
        AgentToolContext.nextParlerChartId();
        com.thingworx.things.agent.tools.TabularChartRoundState.Snapshot snap =
                AgentToolContext.tabularChartRoundState().snapshot();

        AgentToolContext.clear();
        AgentToolContext.resetTabularChartRound();
        AgentToolContext.tabularChartRoundState().restore(snap);
        assertEquals("c3", AgentToolContext.nextParlerChartId());

        AgentToolContext.clear();
        AgentToolContext.resetTabularChartRound();
        AgentToolContext.tabularChartRoundState().restore(snap);
        assertEquals("c3", AgentToolContext.nextParlerChartId());
    }

    /**
     * An approved table must reach the model with the same handle a normally dispatched one gets. Without it,
     * the very sequence this batch is about — invoke_service(A) → approval → invoke_service(B) → approval —
     * leaves A unreferenceable once B takes over last_invoke, and the model has to re-query it.
     */
    @Test
    void approvedJsonResult_carriesItsHandleIntoHistoryAndTheNextModelRequest() throws Exception {
        PendingApprovalRecord pending = pauseWithResults("hitl-approved-handle", List.of());
        String approvedA = jsonEnvelope(SUMMARY_BUSINESS, true);

        List<ChatMessage> messages = bindContinuation(pending, approvedA);

        // The durable tool row the continuation replays carries the handle.
        String toolRow = null;
        for (ChatMessage m : messages) {
            if (m.getRole() == ChatMessage.Role.TOOL && m.getContent() != null
                    && m.getContent().contains("utilizationState")) {
                toolRow = m.getContent();
            }
        }
        assertNotNull(toolRow, "approved tool row missing from continuation history");
        String handleA = MAPPER.readTree(toolRow).path("cacheId").asText(null);
        assertNotNull(handleA, "approved tool row must carry the chart source handle: " + toolRow);

        // And the model sees that same row on the next request.
        ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(textResponse("both breakdowns are ready"));
        AgentLoop.AgentResult result = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L)
                .runAfterApproval(messages, chartToolOnly(), null, pending, approvedA);
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, result.getStatus());
        assertTrue(llm.seenRequests().stream()
                        .flatMap(r -> r.getMessages().stream())
                        .filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                        .anyMatch(m -> m.getContent() != null && m.getContent().contains(handleA)),
                "the approved handle must be visible to the model");

        // A later table takes over last_invoke; the earlier approved one is still chartable by its handle.
        TabularChartRoundHooks.afterBuiltInToolResult("state_summary",
                jsonEnvelope(SUMMARY_BUSINESS.replace("51368", "11").replace("34251", "22").replace("780", "33"), true));

        AgentToolContext.drainPendingParlerChartBlocks();
        String args = "{\"source\":\"cache_id\",\"cacheId\":\"" + handleA + "\",\"kind\":\"pie\","
                + "\"xColumn\":\"utilizationState\",\"yColumn\":\"sumDurationSeconds\",\"pieSliceMode\":\"all_nonzero\"}";
        JsonNode chart = MAPPER.readTree(BuildChartFromTabularResultExecutor.execute(
                new ToolCall("chart-earlier", "build_chart_from_tabular_result", args)));
        assertEquals("CHART_EMITTED", chart.path("code").asText(), chart.toString());
        assertThreeSlicePieValues(AgentToolContext.drainPendingParlerChartBlocks());
    }

    /** Augmenting twice must not store the table twice or hand back a different handle. */
    @Test
    void augmentingAnAlreadyHandledResultIsANoOp() throws Exception {
        AgentToolContext.setConversationId("hitl-approved-handle-idempotent");
        AgentToolContext.resetTabularChartRound();
        String once = TabularChartRoundHooks.augmentJsonSourceHandle("invoke_service",
                jsonEnvelope(SUMMARY_BUSINESS, true));
        String twice = TabularChartRoundHooks.augmentJsonSourceHandle("invoke_service", once);
        assertEquals(once, twice);

        // The continuation hook also runs over the augmented body; it records the source once and adds nothing.
        String afterHook = TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", once);
        assertEquals(once, afterHook);
        assertEquals(1, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
        assertNull(AgentToolContext.tabularChartRoundState().getLastCacheId(),
                "round state stays on the inline path so last_invoke survives cache eviction");
    }

    private static void assertThreeSlicePieValues(List<JSONObject> blocks) throws Exception {
        assertEquals(1, blocks.size());
        JsonNode chart = MAPPER.readTree(blocks.get(0).toString());
        JsonNode x = chart.path("series").get(0).path("x");
        JsonNode y = chart.path("series").get(0).path("y");
        java.util.Map<String, Double> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < x.size(); i++) {
            values.put(x.get(i).asText(), y.get(i).asDouble());
        }
        assertEquals(java.util.Map.of("Down", 51368.0, "Unavailable", 34251.0, "Running", 780.0), values);
    }

    private static final class ScriptedLlmClient implements LlmClient {
        private final Queue<LlmResponse> scripted = new ArrayDeque<>();
        private final List<LlmChatRequest> seen = new ArrayList<>();
        private final LlmUsageWireIds ids = new LlmUsageWireIds("pt", "tpl", "openai-chat-completions-v1", "mdl");

        void enqueue(LlmResponse r) {
            scripted.add(r);
        }

        /** Requests as the model received them, for asserting what a tool row actually carried. */
        List<LlmChatRequest> seenRequests() {
            return seen;
        }

        @Override
        public LlmResponse chat(LlmChatRequest request) {
            seen.add(request);
            LlmResponse r = scripted.poll();
            if (r == null) {
                throw new IllegalStateException("no scripted response");
            }
            return r;
        }

        @Override
        public LlmUsageWireIds usageWireIds() {
            return ids;
        }

        @Override
        public boolean healthCheck() {
            return true;
        }
    }
}
