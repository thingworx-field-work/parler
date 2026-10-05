package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.compaction.LlmReplayCompactionGate;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.things.agent.evidence.EvidenceAssessment;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.llm.AnthropicMessagesApi;
import com.thingworx.things.agent.llm.ChatCompletionsApi;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.DocumentCoverageSummaryInjector;
import com.thingworx.things.agent.llm.EmptyFinalAnswerRetryInjector;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ParlerSuffixFraming;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.taskstate.AgentTaskState;
import com.thingworx.things.agent.taskstate.TaskStateLlmInjector;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.ApprovalPendingException;
import com.thingworx.things.agent.tools.FetchCachedReplayGuard;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.things.agent.tools.PendingApprovalRecord;
import com.thingworx.things.agent.tools.PendingApprovalStore;
import com.thingworx.things.agent.tools.PresentationArtifactRegistry;
import com.thingworx.things.agent.tools.TabularChartRoundHooks;
import com.thingworx.things.agent.tools.ToolExecutor;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;

class AgentLoopTest {

    AgentLoopTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final String HITL_PARALLEL_SIBLING_TEST_PENDING_ID = "agent-loop-hitl-sibling-test-pid";

    private static final String APX_HITL_CANCEL_TEST_PENDING_ID = "pid-apx-hitl-cancel-test";

    private static final String TABULATE_COMPLETE_JSON =
            "{\"status\":\"success\",\"answerSetComplete\":true,\"rows\":[{\"k\":1}]}";

    private static final String BUILD_CHART_DUPLICATE_ERROR =
            "{\"status\":\"error\",\"code\":\"DUPLICATE_SLICE_LABEL\",\"message\":\"duplicate slice labels\"}";

    @BeforeEach
    void installLlmCallRecorderTestSink() {
        LlmCallRecorder.resetForTest();
    }

    @AfterEach
    void tearDownAgentToolContext() {
        LlmCallRecorder.resetForTest();
        LlmReplayCompactionGate.clearAllTestHooks();
        PresentationArtifactRegistry.removeTurn(FetchCachedReplayGuard.resolveCurrentTurnKey());
        PendingApprovalStore.remove(HITL_PARALLEL_SIBLING_TEST_PENDING_ID);
        PendingApprovalStore.remove(APX_HITL_CANCEL_TEST_PENDING_ID);
        ParlerGatewayUserStopTombstoneRegistry.putExpiryMillisForTests("loop-apx-conv", "rid-apx", "runner", "AgentZ",
                System.currentTimeMillis() - 1L);
        ParlerRunningTurnCancelRegistry.clearAllForTests();
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void agentRound_preservesStableCatalogAndBuildsOneTimezoneAwareTimeRow() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(textResponse("done"));
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L,
                null, 750_000, null, "America/New_York");
        String stable = "workflow-catalog\n" + ParlerTimeAnchor.STABLE_TIME_GUIDANCE;
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.system(stable));
        msgs.add(ChatMessage.user("hi"));

        loop.run(msgs, Collections.emptyList());

        List<ChatMessage> outbound = llm.requests.get(0).getMessages();
        assertEquals(stable, outbound.get(0).getContent());
        ChatMessage time = onlyTimeRow(outbound);
        assertTrue(time.getContent().contains("- now_utc: "));
        assertTrue(time.getContent().contains("- now_local: "));
        assertTrue(time.getContent().contains("- user_timezone: America/New_York"));
    }

    @Test
    void agentRound_nullTimezone_keepsUtcAndOmitsLocalValues() throws Exception {
        assertUtcOnlyTimeRow(null);
    }

    @Test
    void agentRound_invalidTimezone_keepsUtcAndOmitsLocalValues() throws Exception {
        assertUtcOnlyTimeRow("Not/A_Zone");
    }

    @Test
    void finalAssistantAnswer_afterRelationshipAssessment_passesThroughArbitraryUnicodeExactly() throws Exception {
        String answer = "Correlation result — 中文、Deutsch, 日本語, العربية, emoji 🧭 — unchanged.";
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(textResponse(answer));
        AgentTaskState state = new AgentTaskState("r", "unicode-pass-through", "compare signals");
        state.recordAnalysisAssessment(EvidenceAssessment.builder()
                .status(EvidenceStatus.SUCCESS)
                .completeness(CompletenessStatus.COMPLETE)
                .applicability(List.of("associational", "not_tested_causal"))
                .build());
        AgentToolContext.setAgentTaskState(state);
        AgentToolContext.setConversationId("unicode-pass-through");
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L);

        AgentLoop.AgentResult result = loop.run(
                new ArrayList<>(List.of(ChatMessage.user("compare the two signals"))),
                Collections.emptyList());

        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, result.getStatus());
        assertEquals(answer, result.getContent());
    }

    @Test
    void emptyFinalAnswer_retriesOnceWithFramedNoToolInstruction_andReturnsRetryTextExactly() throws Exception {
        String recovered = "已恢复 — Deutsch, 日本語, العربية 🧭";
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(AnthropicMessagesApi.parseResponse(
                "{\"content\":[],\"stop_reason\":\"end_turn\","
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}",
                "anthropic-empty-rid"));
        llm.enqueue(textResponse(recovered));
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L,
                null, 750_000, null, "America/New_York");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-prefix"));
        messages.add(ChatMessage.user("answer me"));

        AgentLoop.AgentResult result = loop.run(messages, stubTools());

        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, result.getStatus());
        assertEquals(recovered, result.getContent());
        assertEquals(1, result.getIterations(), "recovery is not another ordinary tool-loop iteration");
        assertEquals(2, result.getPromptTokens());
        assertEquals(4, result.getCompletionTokens());
        assertEquals(2, llm.requests.size());
        assertEquals(1, llm.requests.get(0).getTools().size());
        assertTrue(llm.requests.get(1).getTools().isEmpty());
        assertTrue(llm.requests.get(1).isToolChoiceNone());
        assertNoUnclassifiedNonLeadingSystemRows(llm.requests.get(1).getMessages());
        assertTrue(llm.requests.get(1).getMessages().stream().anyMatch(m -> m.getContent() != null
                && m.getContent().startsWith(ParlerSuffixFraming.SERVER_INSTRUCTION + "\n"
                        + EmptyFinalAnswerRetryInjector.PREFIX)));
        assertEquals("stable-prefix", llm.requests.get(0).getMessages().get(0).getContent());
        assertEquals("stable-prefix", llm.requests.get(1).getMessages().get(0).getContent());
        assertEquals(2, messages.size(), "neither blank response nor transient retry instruction enters history");
        assertTrue(result.getLlmTurnPerformanceWireJson().contains("\"agentIterations\":2"),
                result.getLlmTurnPerformanceWireJson());
    }

    @Test
    void emptyFinalAnswer_secondBlankReturnsStableError_withoutRepeatingTools() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(new ToolCall("t1", "noop", "{}"))));
        llm.enqueue(textResponse(null));
        llm.enqueue(textResponse("\u3000\t\n"));
        AtomicInteger executions = new AtomicInteger();
        ToolExecutor executor = tc -> {
            executions.incrementAndGet();
            return "{\"status\":\"success\"}";
        };
        AgentLoop loop = new AgentLoop(llm, executor, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user("use a tool, then answer")));
        List<ChatMessage> streamed = new ArrayList<>();

        AgentLoop.AgentResult result = loop.run(messages, stubTools(), (message, usage) -> streamed.add(message));

        assertEquals(AgentLoop.AgentResult.Status.ERROR, result.getStatus());
        assertEquals("EMPTY_FINAL_ANSWER", result.getErrorCode());
        assertEquals(1, executions.get());
        assertEquals(3, llm.requests.size());
        assertTrue(llm.requests.get(2).getTools().isEmpty());
        assertTrue(llm.requests.get(2).isToolChoiceNone());
        assertEquals(2, result.getIterations());
        assertEquals(5, result.getPromptTokens());
        assertEquals(8, result.getCompletionTokens());
        assertEquals(2, streamed.size(), "only the tool-call row and its result are streamed by AgentLoop");
        assertTrue(streamed.get(0).hasToolCalls());
        assertEquals(ChatMessage.Role.TOOL, streamed.get(1).getRole());
        assertFalse(messages.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().startsWith(ParlerSuffixFraming.SERVER_INSTRUCTION + "\n"
                        + EmptyFinalAnswerRetryInjector.PREFIX)));
    }

    @Test
    void emptyFinalAnswer_atMaxIterationStillGetsOneBoundedFinalizationRetry() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(textResponse(""));
        llm.enqueue(textResponse("recovered at boundary"));
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 1, 60_000L);

        AgentLoop.AgentResult result = loop.run(
                new ArrayList<>(List.of(ChatMessage.user("answer"))), stubTools());

        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, result.getStatus());
        assertEquals("recovered at boundary", result.getContent());
        assertEquals(1, result.getIterations());
        assertEquals(2, llm.requests.size());
    }

    @Test
    void realRoundAssembly_classifiesEveryNonLeadingSystemRow_withoutRoundAccumulation() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(new ToolCall("t1", "noop", "{}"))));
        llm.enqueue(textResponse("coverage-grounded final"));
        ToolExecutor executor = tc -> {
            AgentToolContext.requestForcedSummaryForDocumentSearchLoop();
            AgentToolContext.requestGroundedCoverageSummary();
            return "{\"status\":\"success\"}";
        };
        AgentToolContext.setAgentTaskState(new AgentTaskState("r", "c", "inspect evidence"));
        AgentLoop loop = new AgentLoop(llm, executor, null, 0, 8192, 8, 60_000L,
                null, 750_000, null, "America/New_York");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable\n" + LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE));
        messages.add(ChatMessage.system(ParlerSuffixFraming.SKILL_INSTRUCTIONS + "\nslash body"));
        messages.add(ChatMessage.system(ParlerSuffixFraming.SERVER_OBSERVATIONS + "\nhost scope"));
        messages.add(ChatMessage.user("question"));

        loop.run(messages, stubTools());

        assertEquals(2, llm.requests.size());
        for (LlmChatRequest request : llm.requests) {
            assertTrue(request.isEnableCacheControl(),
                    "AgentLoop marks every outbound request true before optional tool-policy copies");
            assertNoUnclassifiedNonLeadingSystemRows(request.getMessages());
            assertEquals(1, countAuthority(request.getMessages(), ParlerSuffixFraming.AuthorityClass.SKILL));
            assertEquals(2, countAuthority(request.getMessages(), ParlerSuffixFraming.AuthorityClass.OBSERVATIONS),
                    "host scope plus one recomputed task-state row");
            assertEquals(1, countAuthority(request.getMessages(), ParlerSuffixFraming.AuthorityClass.TIME));
        }
        assertEquals(0, countAuthority(llm.requests.get(0).getMessages(),
                ParlerSuffixFraming.AuthorityClass.INSTRUCTION));
        assertEquals(1, countAuthority(llm.requests.get(1).getMessages(),
                ParlerSuffixFraming.AuthorityClass.INSTRUCTION));
        assertTrue(llm.requests.get(1).getMessages().stream().anyMatch(m -> m.getContent() != null
                && m.getContent().startsWith(ParlerSuffixFraming.SERVER_INSTRUCTION + "\n"
                        + DocumentCoverageSummaryInjector.PREFIX)));
    }

    @Test
    void rowUniqueRoundRemovalGuards_doNotDeleteShiftedSameAuthorityRows() {
        List<ChatMessage> taskRows = new ArrayList<>();
        taskRows.add(ChatMessage.user("u"));
        int taskIdx = TaskStateLlmInjector.insertForApiRound(taskRows, "## Recent Tool Evidence\nrow");
        taskRows.add(taskIdx, ChatMessage.system(ParlerSuffixFraming.SERVER_OBSERVATIONS + "\nhost-scope"));
        TaskStateLlmInjector.removeAtIndex(taskRows, taskIdx);
        assertEquals(3, taskRows.size(), "shifted index must not delete the host observation row");
        TaskStateLlmInjector.removeAtIndex(taskRows, taskIdx + 1);
        assertEquals(2, taskRows.size());

        List<ChatMessage> coverageRows = new ArrayList<>();
        coverageRows.add(ChatMessage.user("u"));
        int coverageIdx = DocumentCoverageSummaryInjector.insertForApiRound(coverageRows);
        coverageRows.add(coverageIdx,
                ChatMessage.system(ParlerSuffixFraming.SERVER_INSTRUCTION + "\nnot-coverage"));
        DocumentCoverageSummaryInjector.removeAtIndex(coverageRows, coverageIdx);
        assertEquals(3, coverageRows.size(), "shifted index must not delete another instruction row");
        DocumentCoverageSummaryInjector.removeAtIndex(coverageRows, coverageIdx + 1);
        assertEquals(2, coverageRows.size());

        List<ChatMessage> retryRows = new ArrayList<>();
        retryRows.add(ChatMessage.user("u"));
        int retryIdx = EmptyFinalAnswerRetryInjector.insertForApiRound(retryRows);
        retryRows.add(retryIdx,
                ChatMessage.system(ParlerSuffixFraming.SERVER_INSTRUCTION + "\nnot-empty-retry"));
        EmptyFinalAnswerRetryInjector.removeAtIndex(retryRows, retryIdx);
        assertEquals(3, retryRows.size(), "shifted index must not delete another instruction row");
        EmptyFinalAnswerRetryInjector.removeAtIndex(retryRows, retryIdx + 1);
        assertEquals(2, retryRows.size());
    }

    private static void assertUtcOnlyTimeRow(String timezone) throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(textResponse("done"));
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L,
                null, 750_000, null, timezone);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.system("stable-catalog"));
        msgs.add(ChatMessage.user("hi"));
        loop.run(msgs, Collections.emptyList());

        ChatMessage time = onlyTimeRow(llm.requests.get(0).getMessages());
        assertTrue(time.getContent().contains("- now_utc: "));
        assertFalse(time.getContent().contains("- now_local: "));
        assertFalse(time.getContent().contains("- user_timezone: "));
    }

    private static ChatMessage onlyTimeRow(List<ChatMessage> messages) {
        ChatMessage found = null;
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.SYSTEM && message.getContent() != null
                    && message.getContent().startsWith(ParlerSuffixFraming.TIME_CONTEXT)) {
                assertNull(found, "time row must occur exactly once");
                found = message;
            }
        }
        assertTrue(found != null, "time row must be present");
        return found;
    }

    private static void assertNoUnclassifiedNonLeadingSystemRows(List<ChatMessage> messages) {
        for (int i = 1; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message.getRole() == ChatMessage.Role.SYSTEM) {
                assertTrue(ParlerSuffixFraming.isClassified(message.getContent()),
                        "unclassified non-leading SYSTEM row at index " + i + ": " + message.getContent());
            }
        }
    }

    private static int countAuthority(List<ChatMessage> messages,
            ParlerSuffixFraming.AuthorityClass authorityClass) {
        int count = 0;
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.SYSTEM
                    && ParlerSuffixFraming.classify(message.getContent()) == authorityClass) {
                count++;
            }
        }
        return count;
    }

    @Test
    void completeAnswerSetSeen_schedules_post_marker_round() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t1", "tabulate_cached_result", "{\"cacheId\":\"x\"}"))));
        llm.enqueue(textResponse("done"));
        ToolExecutor exec = tc -> TABULATE_COMPLETE_JSON;
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("hi"));
        AgentLoop.AgentResult r = loop.run(msgs, stubTools());
        assertEquals(2, llm.requests.size());
        LlmChatRequest post = llm.requests.get(1);
        assertTrue(post.isToolChoiceNone());
        assertTrue(post.getTools().isEmpty());
        assertTrue(r.getLlmTurnPerformanceWireJson().contains("\"noToolFinalAnswerApplied\":true"),
                r.getLlmTurnPerformanceWireJson());
    }

    @Test
    void twoRepetitionBlocked_schedules_forced_tool_none_round() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Arrays.asList(
                new ToolCall("t1", "search_document_chunks", "{\"q\":\"damage\"}"),
                new ToolCall("t2", "search_document_chunks", "{\"q\":\"table\"}"))));
        llm.enqueue(textResponse("final after repetition guard"));
        ToolExecutor exec = tc -> {
            AgentToolContext.incrementRepetitionBlockedCountForTurnPerf();
            return "{\"status\":\"error\",\"code\":\"REPETITION_BLOCKED\",\"message\":\"blocked\"}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("search docs"));
        AgentLoop.AgentResult r = loop.run(msgs, stubTools());
        assertEquals(2, llm.requests.size());
        LlmChatRequest forced = llm.requests.get(1);
        assertTrue(forced.isToolChoiceNone());
        assertTrue(forced.getTools().isEmpty());
        assertTrue(r.getLlmTurnPerformanceWireJson().contains("\"noToolFinalAnswerApplied\":true"),
                r.getLlmTurnPerformanceWireJson());
        assertTrue(r.getLlmTurnPerformanceWireJson().contains("\"repetitionBlockedCount\":2"),
                r.getLlmTurnPerformanceWireJson());
    }

    @Test
    void post_marker_round_uses_tool_choice_none_and_empty_tools() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t1", "tabulate_cached_result", "{\"cacheId\":\"y\"}"))));
        llm.enqueue(textResponse("final"));
        ToolExecutor exec = tc -> TABULATE_COMPLETE_JSON;
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("q"));
        loop.run(msgs, stubTools());
        assertTrue(llm.requests.get(1).isToolChoiceNone());
    }

    @Test
    void post_marker_round_openai_body_omits_tools_and_tool_choice() throws Exception {
        OpenAiWireShapeClient llm = new OpenAiWireShapeClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t1", "tabulate_cached_result", "{\"cacheId\":\"y\"}"))));
        llm.enqueue(textResponse("final"));
        ToolExecutor exec = tc -> TABULATE_COMPLETE_JSON;
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("q"));
        loop.run(msgs, stubTools());
        assertEquals(2, llm.openAiBodies.size());
        Map<String, Object> post = llm.openAiBodies.get(1);
        assertFalse(post.containsKey("tools"), post.toString());
        assertFalse(post.containsKey("tool_choice"), post.toString());
    }

    @Test
    void approval_pending_mid_batch_attaches_sibling_tool_calls_to_pending_store() throws Exception {
        AgentToolContext.setConversationId("agent-loop-hitl-sibling-conv");
        ToolCall first = new ToolCall("call_hitl_first", "invoke_service", "{}");
        ToolCall second = new ToolCall("call_hitl_second", "noop", "{}");
        PendingApprovalRecord rec = new PendingApprovalRecord(
                HITL_PARALLEL_SIBLING_TEST_PENDING_ID,
                "rid-hitl-sib",
                "agent-loop-hitl-sibling-conv",
                "u",
                "Agent",
                "remote",
                first,
                Collections.emptyList(),
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000);
        PendingApprovalStore.put(rec);

        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Arrays.asList(first, second)));
        ToolExecutor exec = tc -> {
            if ("call_hitl_first".equals(tc.getId())) {
                throw new ApprovalPendingException(HITL_PARALLEL_SIBLING_TEST_PENDING_ID);
            }
            return "{\"status\":\"success\"}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 2048, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("parallel hitl"));
        AgentLoop.AgentResult r = loop.run(msgs, toolsWithInvokeServiceAndNoop());
        assertEquals(AgentLoop.AgentResult.Status.AWAITING_APPROVAL, r.getStatus());
        assertEquals("", r.getContent(), "intentional blank HITL terminal remains untouched");
        assertEquals(HITL_PARALLEL_SIBLING_TEST_PENDING_ID, r.getApprovalPendingId());
        assertEquals(1, llm.requests.size(), "HITL terminal must not enter empty-final recovery");
        PendingApprovalRecord loaded = PendingApprovalStore.get(HITL_PARALLEL_SIBLING_TEST_PENDING_ID);
        assertEquals(1, loaded.getInterruptedBatchSiblingToolCalls().size());
        assertEquals("call_hitl_second", loaded.getInterruptedBatchSiblingToolCalls().get(0).getId());
        assertEquals("noop", loaded.getInterruptedBatchSiblingToolCalls().get(0).getFunctionName());
    }

    @Test
    void parallel_tool_calls_execute_serially_and_count_multi_round() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        List<ToolCall> two = new ArrayList<>();
        two.add(new ToolCall("a", "noop", "{}"));
        two.add(new ToolCall("b", "noop", "{}"));
        llm.enqueue(toolCallsResponse(two));
        llm.enqueue(textResponse("ok"));
        List<String> order = new ArrayList<>();
        ToolExecutor exec = tc -> {
            order.add(tc.getId());
            return "{\"status\":\"success\"}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 2048, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("x"));
        AgentLoop.AgentResult r = loop.run(msgs, stubTools());
        assertEquals(List.of("a", "b"), order);
        String perf = r.getLlmTurnPerformanceWireJson();
        assertTrue(perf.contains("\"multiToolCallRoundsCount\":1"), perf);
        assertTrue(perf.contains("\"toolExecutionMaxConcurrency\":1"), perf);
        assertTrue(perf.contains("\"parlerChartWireEmittedCount\":0"), perf);
    }

    @Test
    void repositoryUnavailable_pairsCurrentTool_skipsSiblings_andAbortsWithoutAnotherLlmRound() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        ToolCall first = new ToolCall("cache-fatal", "noop", "{}");
        ToolCall sibling = new ToolCall("must-not-run", "noop", "{}");
        llm.enqueue(toolCallsResponse(Arrays.asList(first, sibling)));
        AtomicInteger executions = new AtomicInteger();
        List<ChatMessage> streamed = new ArrayList<>();
        ToolExecutor exec = tc -> {
            executions.incrementAndGet();
            throw new ArtifactCacheException(
                    ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE, "injected repository failure");
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 2048, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("use cache"));

        AgentLoop.AgentResult result = loop.run(msgs, stubTools(), (m, usage) -> streamed.add(m));

        assertEquals(AgentLoop.AgentResult.Status.ERROR, result.getStatus());
        assertEquals(ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE, result.getErrorCode());
        assertEquals(ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_MESSAGE, result.getContent());
        assertEquals(1, result.getIterations());
        assertEquals(3, result.getPromptTokens());
        assertEquals(4, result.getCompletionTokens());
        assertEquals(1, executions.get());
        assertEquals(1, llm.requests.size());

        List<ChatMessage> tools = msgs.stream().filter(m -> m.getRole() == ChatMessage.Role.TOOL).toList();
        assertEquals(2, tools.size());
        assertEquals("cache-fatal", tools.get(0).getToolCallId());
        assertEquals("REPOSITORY_UNAVAILABLE", new JSONObject(tools.get(0).getContent()).getString("code"));
        assertEquals("must-not-run", tools.get(1).getToolCallId());
        assertEquals("skipped", new JSONObject(tools.get(1).getContent()).getString("status"));
        assertEquals(ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE,
                new JSONObject(tools.get(1).getContent()).getString("code"));
        assertEquals(3, streamed.size());
    }

    @Test
    void wrappedRepositoryUnavailable_isStillFatal() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(new ToolCall("wrapped-cache-fatal", "noop", "{}"))));
        ToolExecutor exec = tc -> {
            ArtifactCacheException typed = new ArtifactCacheException(
                    ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE, "injected wrapped repository failure");
            throw new java.io.IOException("stream failed", typed);
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 2048, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("use wrapped cache"));

        AgentLoop.AgentResult result = loop.run(msgs, stubTools());

        assertEquals(AgentLoop.AgentResult.Status.ERROR, result.getStatus());
        assertEquals(ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE, result.getErrorCode());
        assertEquals(1, llm.requests.size());
    }

    @Test
    void payloadFault_remainsStructuredToolEvidence_andConversationContinues() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        ToolCall faulty = new ToolCall("payload-fault", "noop", "{}");
        ToolCall sibling = new ToolCall("payload-sibling", "noop", "{}");
        llm.enqueue(toolCallsResponse(Arrays.asList(faulty, sibling)));
        llm.enqueue(textResponse("continued"));
        List<String> executions = new ArrayList<>();
        ToolExecutor exec = tc -> {
            executions.add(tc.getId());
            if ("payload-fault".equals(tc.getId())) {
                throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT, "corrupt artifact");
            }
            return "{\"status\":\"success\"}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 2048, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("continue after local fault"));

        AgentLoop.AgentResult result = loop.run(msgs, stubTools());

        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, result.getStatus());
        assertEquals("continued", result.getContent());
        assertEquals(List.of("payload-fault", "payload-sibling"), executions);
        assertEquals(2, llm.requests.size());
        ChatMessage faultResult = msgs.stream()
                .filter(m -> "payload-fault".equals(m.getToolCallId()))
                .findFirst()
                .orElseThrow();
        assertEquals("PAYLOAD_FAULT", new JSONObject(faultResult.getContent()).getString("code"));
    }

    @Test
    void tool_results_are_compacted_before_next_llm_round_and_stream_sink() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("hist-1", "query_property_history", "{\"thingName\":\"x\"}"))));
        llm.enqueue(textResponse("done"));
        String raw = ToolResultEgressGatewayTest.numericAggregateResult(232);
        List<ChatMessage> streamed = new ArrayList<>();
        ToolExecutor exec = tc -> raw;
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("stats"));

        loop.run(msgs, stubTools(), (m, tu) -> streamed.add(m));

        assertEquals(2, llm.requests.size());
        ChatMessage replayTool = llm.requests.get(1).getMessages().stream()
                .filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .findFirst()
                .orElseThrow();
        assertTrue(replayTool.getContent().contains("\"aggregates\""), replayTool.getContent());
        assertTrue(replayTool.getContent().contains("\"_egress\""), replayTool.getContent());
        assertFalse(replayTool.getContent().contains("marker-021"), replayTool.getContent());
        ChatMessage streamTool = streamed.stream()
                .filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .findFirst()
                .orElseThrow();
        assertEquals(replayTool.getContent(), streamTool.getContent());
        assertTrue(AgentToolContext.peekToolEgressFullJsonForToolCall("hist-1").contains("marker-021"));
    }

    @Test
    void compacted_numeric_history_downlink_uses_full_points_for_chart() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("hist-chart", "query_property_history", "{\"thingName\":\"x\"}"))));
        llm.enqueue(textResponse("done"));
        String raw = ToolResultEgressGatewayTest.numericAggregateResult(232);
        List<ChatMessage> streamed = new ArrayList<>();
        ToolExecutor exec = tc -> raw;
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("trend"));

        loop.run(msgs, stubTools(), (m, tu) -> streamed.add(m));

        ChatMessage compactTool = streamed.stream()
                .filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .findFirst()
                .orElseThrow();
        ChatMessage persist = com.thingworx.things.agent.tools.FetchCachedStreamLaneHelper
                .augmentToolForParlerStreamPersist(compactTool, null, "rid", "cid");
        assertEquals(compactTool.getContent(), persist.getContent());
        ChatMessage ui = com.thingworx.things.agent.tools.FetchCachedStreamLaneHelper
                .resolveToolMessageForParlerTableDownlinks(compactTool, persist, null, "rid", "cid");
        assertTrue(ui.getContent().contains("marker-231"), ui.getContent());
        JSONObject chart = ParlerChartWireSupport.chartBlockFromNumericHistoryToolResult(ui.getContent(), false)
                .orElseThrow();
        assertEquals(232, chart.getJSONArray("series").getJSONObject(0).getJSONArray("y").length());
    }

    @Test
    void first_party_tabular_list_tool_results_are_compacted_before_replay_and_stream() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        List<ToolCall> calls = Arrays.asList(
                new ToolCall("q-entities", "query_entities", "{}"),
                new ToolCall("list-type", "list_entities_by_type", "{}"),
                new ToolCall("alert-hist", "query_alert_history", "{}"));
        llm.enqueue(toolCallsResponse(calls));
        llm.enqueue(textResponse("done"));
        List<ChatMessage> streamed = new ArrayList<>();
        ToolExecutor exec = tc -> largeFirstPartyTabularResult(tc.getFunctionName());
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("large tabular first-party"));

        loop.run(msgs, stubTools(), (m, tu) -> streamed.add(m));

        List<ChatMessage> replayTools = llm.requests.get(1).getMessages().stream()
                .filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .toList();
        assertEquals(3, replayTools.size());
        for (ChatMessage tool : replayTools) {
            assertTrue(tool.getContent().contains("\"_egress\""), tool.getContent());
            assertFalse(tool.getContent().contains("marker-079"), tool.getContent());
        }
        List<ChatMessage> streamTools = streamed.stream()
                .filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .toList();
        assertEquals(3, streamTools.size());
        for (ChatMessage tool : streamTools) {
            assertTrue(tool.getContent().contains("\"_egress\""), tool.getContent());
            assertFalse(tool.getContent().contains("marker-079"), tool.getContent());
        }
    }

    @Test
    void gateway_compaction_does_not_overwrite_fetch_cached_full_registration() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("fetch-wide", "fetch_cached_result", "{\"cacheId\":\"c1\"}"))));
        llm.enqueue(textResponse("done"));
        String fullFetch = "{\"status\":\"success\",\"cacheId\":\"c1\",\"rows\":[{\"full\":1},{\"full\":2}]}";
        String compactWide = wideFetchCompactBody(80);
        ToolExecutor exec = tc -> {
            AgentToolContext.setFetchCachedStreamJsonForToolCall(tc.getId(), fullFetch);
            return compactWide;
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("fetch"));

        loop.run(msgs, stubTools());

        assertEquals(fullFetch, AgentToolContext.peekFetchCachedStreamJsonForToolCall("fetch-wide"));
        assertEquals(compactWide, AgentToolContext.peekToolEgressFullJsonForToolCall("fetch-wide"));
    }

    @Test
    void gateway_compaction_does_not_overwrite_pre_registered_generic_full_body() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("hist-pre", "query_property_history", "{\"thingName\":\"x\"}"))));
        llm.enqueue(textResponse("done"));
        String executorFull = "{\"status\":\"success\",\"points\":[{\"marker\":\"executor-full\"}]}";
        String raw = ToolResultEgressGatewayTest.numericAggregateResult(232);
        ToolExecutor exec = tc -> {
            AgentToolContext.setToolEgressFullJsonForToolCall(tc.getId(), executorFull);
            return raw;
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("trend"));

        loop.run(msgs, stubTools());

        assertEquals(executorFull, AgentToolContext.peekToolEgressFullJsonForToolCall("hist-pre"));
    }

    @Test
    void runningTurnCancel_afterLlmReturnsNoTools_suppressesSuccessTerminal() throws Exception {
        AgentToolContext.setConversationId("loop-cancel-no-tool");
        AgentToolContext.setParlerStreamIds("rid-can-1", "remote");
        AgentToolContext.setParlerGatewayCallerPrincipal("alice");
        AgentToolContext.setParlerRunningCancelAgentThingNameForTests("AgentZ");
        ParlerRunningTurnCancelRegistry.register("loop-cancel-no-tool", "rid-can-1", "alice", "AgentZ");
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(textResponse("Invisible final"));
        llm.setBeforeChatReturn(() -> ParlerRunningTurnCancelRegistry.tryRequestCancel(
                "loop-cancel-no-tool", "rid-can-1", "alice", "AgentZ"));
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("hi"));
        AgentLoop.AgentResult r = loop.run(msgs, stubTools());
        assertEquals(AgentLoop.AgentResult.Status.CANCELLED, r.getStatus());
        assertFalse(msgs.stream().anyMatch(m -> m.getRole() == ChatMessage.Role.ASSISTANT
                && "Invisible final".equals(m.getContent())));
    }

    @Test
    void runningTurnCancel_afterLlmReturnsToolCalls_appendsSyntheticsWithoutExecuting() throws Exception {
        AgentToolContext.setConversationId("loop-cancel-tools-immediate");
        AgentToolContext.setParlerStreamIds("rid-can-2", "remote");
        AgentToolContext.setParlerGatewayCallerPrincipal("bob");
        AgentToolContext.setParlerRunningCancelAgentThingNameForTests("AgentZ");
        ParlerRunningTurnCancelRegistry.register("loop-cancel-tools-immediate", "rid-can-2", "bob", "AgentZ");
        RecordingLlmClient llm = new RecordingLlmClient();
        ToolCall tc = new ToolCall("ex1", "noop", "{}");
        llm.enqueue(toolCallsResponse(Collections.singletonList(tc)));
        llm.setBeforeChatReturn(() -> ParlerRunningTurnCancelRegistry.tryRequestCancel(
                "loop-cancel-tools-immediate", "rid-can-2", "bob", "AgentZ"));
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolExecutor exec = t -> {
            executed.set(true);
            return "{\"status\":\"success\"}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("q"));
        AgentLoop.AgentResult r = loop.run(msgs, stubTools());
        assertEquals(AgentLoop.AgentResult.Status.CANCELLED, r.getStatus());
        assertFalse(executed.get(), "tool executor must not run when cancelled immediately after LLM returns tool_calls");
        assertEquals(3, msgs.size());
        assertEquals(ChatMessage.Role.TOOL, msgs.get(2).getRole());
        assertTrue(msgs.get(2).getContent().contains("TURN_CANCELLED"), msgs.get(2).getContent());
    }

    @Test
    void runningTurnCancel_beforeSecondTool_skipsSecondExecution() throws Exception {
        AgentToolContext.setConversationId("loop-cancel-2tool");
        AgentToolContext.setParlerStreamIds("rid-can-3", "remote");
        AgentToolContext.setParlerGatewayCallerPrincipal("carol");
        AgentToolContext.setParlerRunningCancelAgentThingNameForTests("AgentZ");
        ParlerRunningTurnCancelRegistry.register("loop-cancel-2tool", "rid-can-3", "carol", "AgentZ");
        RecordingLlmClient llm = new RecordingLlmClient();
        List<ToolCall> two = Arrays.asList(
                new ToolCall("t-first", "noop", "{}"),
                new ToolCall("t-second", "noop", "{}"));
        llm.enqueue(toolCallsResponse(two));
        llm.enqueue(textResponse("done"));
        List<String> order = new ArrayList<>();
        ToolExecutor exec = tc -> {
            if ("t-first".equals(tc.getId())) {
                ParlerRunningTurnCancelRegistry.tryRequestCancel(
                        "loop-cancel-2tool", "rid-can-3", "carol", "AgentZ");
            }
            order.add(tc.getId());
            return "{\"status\":\"success\"}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("x"));
        AgentLoop.AgentResult r = loop.run(msgs, stubTools());
        assertEquals(AgentLoop.AgentResult.Status.CANCELLED, r.getStatus());
        assertEquals(List.of("t-first"), order);
        assertTrue(msgs.get(msgs.size() - 1).getContent().contains("TURN_CANCELLED"));
    }

    @Test
    void runningTurnCancel_afterFirstTool_returnsCancelledAndSkipsSecondTool() throws Exception {
        AgentToolContext.setConversationId("loop-cancel-post-tool");
        AgentToolContext.setParlerStreamIds("rid-can-4", "remote");
        AgentToolContext.setParlerGatewayCallerPrincipal("dave");
        AgentToolContext.setParlerRunningCancelAgentThingNameForTests("AgentZ");
        ParlerRunningTurnCancelRegistry.register("loop-cancel-post-tool", "rid-can-4", "dave", "AgentZ");
        RecordingLlmClient llm = new RecordingLlmClient();
        List<ToolCall> two = Arrays.asList(
                new ToolCall("p-first", "noop", "{}"),
                new ToolCall("p-second", "noop", "{}"));
        llm.enqueue(toolCallsResponse(two));
        llm.enqueue(textResponse("done"));
        List<String> order = new ArrayList<>();
        ToolExecutor exec = tc -> {
            if ("p-first".equals(tc.getId())) {
                ParlerRunningTurnCancelRegistry.tryRequestCancel(
                        "loop-cancel-post-tool", "rid-can-4", "dave", "AgentZ");
            }
            order.add(tc.getId());
            return "{\"status\":\"success\",\"ok\":true}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("y"));
        AgentLoop.AgentResult r = loop.run(msgs, stubTools());
        assertEquals(AgentLoop.AgentResult.Status.CANCELLED, r.getStatus());
        assertEquals(List.of("p-first"), order);
        List<ChatMessage> tools = msgs.stream().filter(m -> m.getRole() == ChatMessage.Role.TOOL).toList();
        assertEquals(2, tools.size());
        assertTrue(tools.get(0).getContent().contains("\"ok\":true"), tools.get(0).getContent());
        assertTrue(tools.get(1).getContent().contains("TURN_CANCELLED"), tools.get(1).getContent());
    }

    /**
     * If cooperative running cancel is set before {@link ApprovalPendingException} is observed, the loop
     * must return {@link AgentLoop.AgentResult.Status#CANCELLED} (not {@code AWAITING_APPROVAL}) and terminalize the
     * just-created pending with CAS + tombstone replay semantics.
     */
    @Test
    void runningCancel_beforeApprovalPending_returnsCancelledNotAwaiting() throws Exception {
        AgentToolContext.setConversationId("loop-apx-conv");
        AgentToolContext.setParlerStreamIds("rid-apx", "remote");
        AgentToolContext.setParlerGatewayCallerPrincipal("runner");
        AgentToolContext.setParlerRunningCancelAgentThingNameForTests("AgentZ");
        ParlerRunningTurnCancelRegistry.register("loop-apx-conv", "rid-apx", "runner", "AgentZ");
        ToolCall gated = new ToolCall("call-g", "invoke_service", "{}");
        List<ChatMessage> snap = List.of(ChatMessage.user("q"));
        long exp = System.currentTimeMillis() + 120_000;
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(gated)));
        ToolExecutor exec = tc -> {
            ParlerRunningTurnCancelRegistry.tryRequestCancel("loop-apx-conv", "rid-apx", "runner", "AgentZ");
            PendingApprovalRecord rec = new PendingApprovalRecord(
                    APX_HITL_CANCEL_TEST_PENDING_ID, "rid-apx", "loop-apx-conv", "runner", "AgentZ", "remote", gated, snap,
                    null, null, null, exp);
            PendingApprovalStore.put(rec);
            throw new ApprovalPendingException(APX_HITL_CANCEL_TEST_PENDING_ID);
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("hi"));
        AgentLoop.AgentResult r = loop.run(msgs, stubTools());
        assertEquals(AgentLoop.AgentResult.Status.CANCELLED, r.getStatus());
        assertTrue(msgs.stream().anyMatch(m -> m.getRole() == ChatMessage.Role.TOOL
                && m.getContent() != null && m.getContent().contains("TURN_CANCELLED")));
        assertNull(PendingApprovalStore.get(APX_HITL_CANCEL_TEST_PENDING_ID));
        assertTrue(ParlerGatewayUserStopTombstoneRegistry.isGatewayUserStopTerminalActive(
                "loop-apx-conv", "rid-apx", "runner", "AgentZ"));
    }

    @Test
    void mergeToolProtocolViolation_prefers_non_empty_incoming() {
        assertEquals("tool_call_after_tool_none",
                AgentLoop.mergeToolProtocolViolation("", "tool_call_after_tool_none"));
        assertEquals("tool_call_after_tool_none",
                AgentLoop.mergeToolProtocolViolation("x", "tool_call_after_tool_none"));
        assertEquals("kept", AgentLoop.mergeToolProtocolViolation("kept", ""));
    }

    @Test
    void post_marker_tool_response_does_not_execute_tools_uses_fallback() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t1", "tabulate_cached_result", "{\"cacheId\":\"z\"}"))));
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t2", "fetch_cached_result", "{\"cacheId\":\"z\"}"))));
        ToolExecutor exec = tc -> {
            if ("fetch_cached_result".equals(tc.getFunctionName())) {
                throw new IllegalStateException("fetch must not run after post-marker tool violation");
            }
            return TABULATE_COMPLETE_JSON;
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("u"));
        AgentLoop.AgentResult r = loop.run(msgs, stubTools());
        assertTrue(r.getContent().contains("# Results"));
        assertEquals(2, llm.requests.size());
    }

    @Test
    void rate_wait_ms_aggregated_into_final_stream_usage() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        LlmResponse r1 = LlmResponse.withRateGateAdmissionWait(
                toolCallsResponse(Collections.singletonList(
                        new ToolCall("t1", "tabulate_cached_result", "{\"cacheId\":\"z\"}"))),
                44L);
        llm.enqueue(r1);
        llm.enqueue(LlmResponse.withRateGateAdmissionWait(textResponse("x"), 12L));
        ToolExecutor exec = tc -> TABULATE_COMPLETE_JSON;
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 2048, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("u"));
        AgentLoop.AgentResult r = loop.run(msgs, stubTools());
        String json = r.getLastSuccessfulAssistantRound().getLlmUsageJson();
        assertTrue(json.contains("56"), json);
    }

    @Test
    void first_reader_cache_hit_true_when_id_in_loop_start_snapshot() {
        List<String> snap = Collections.singletonList("cid-1");
        ToolCall tc = new ToolCall("1", "fetch_cached_result", "{\"cacheId\":\"cid-1\"}");
        assertEquals("true", AgentLoop.evaluateFirstReaderCacheHit(snap, tc));
    }

    @Test
    void first_reader_cache_hit_false_when_id_not_in_snapshot() {
        List<String> snap = Collections.singletonList("other");
        ToolCall tc = new ToolCall("1", "fetch_cached_result", "{\"cacheId\":\"cid-1\"}");
        assertEquals("false", AgentLoop.evaluateFirstReaderCacheHit(snap, tc));
    }

    @Test
    void first_reader_unknown_when_missing_cache_id() {
        ToolCall tc = new ToolCall("1", "fetch_cached_result", "{}");
        assertEquals("unknown", AgentLoop.evaluateFirstReaderCacheHit(Collections.emptyList(), tc));
    }

    @Test
    void long_answer_prompt_keeps_full_max_tokens_budget() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(textResponse("only"));
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("Please write a report about X."));
        loop.run(msgs, stubTools());
        assertEquals(8192, llm.requests.get(0).getRequestedMaxOutputTokens());
    }

    @Test
    void routing_round_passes_through_positive_max_tokens_no_cap() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(textResponse("only"));
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("short"));
        loop.run(msgs, stubTools());
        assertEquals(8192, llm.requests.get(0).getRequestedMaxOutputTokens());
    }

    @Test
    void routing_with_delegated_max_tokens_passes_negative_one_to_wire() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(textResponse("only"));
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, -1, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("short"));
        loop.run(msgs, stubTools());
        assertEquals(-1, llm.requests.get(0).getRequestedMaxOutputTokens());
    }

    @Test
    void resolveMaxOutput_delegates_negative_one_when_no_tools_offered() {
        assertEquals(-1, AgentLoop.resolveEffectiveMaxOutputTokensForRound(false, false, false, -1));
    }

    @Test
    void resolveMaxOutput_long_answer_passes_through_delegation() {
        assertEquals(-1, AgentLoop.resolveEffectiveMaxOutputTokensForRound(true, false, true, -1));
    }

    @Test
    void resolveMaxOutput_routing_with_tools_passes_through_positive_agent_value() {
        assertEquals(8192, AgentLoop.resolveEffectiveMaxOutputTokensForRound(false, false, true, 8192));
    }

    @Test
    void resolveMaxOutput_post_marker_passes_through_positive_agent_value() {
        assertEquals(8192, AgentLoop.resolveEffectiveMaxOutputTokensForRound(false, true, false, 8192));
    }

    @Test
    void fetch_after_complete_increments_when_marked() throws Exception {
        AgentToolContext.resetLlmTurnPerformanceFlagsForAgentLoop();
        AgentToolContext.setConversationId("conv-fetch-test");
        AgentToolContext.markCompleteAnswerSetSeenThisTurn();
        try {
            String miss = InvokeServiceExecutor.executeFetchCachedResult(
                    new ToolCall("f", "fetch_cached_result", "{\"cacheId\":\"no-such\"}"));
            assertTrue(miss.contains("CACHE_MISS") || miss.contains("MISSING"));
            assertEquals(1, AgentToolContext.getFetchAfterCompleteAnswerSetCount());
        } finally {
            AgentToolContext.clear();
        }
    }

    private static InfoTable oneRowNumericTable() {
        InfoTable t = new InfoTable();
        ValueCollection vc = new ValueCollection();
        vc.put("n", new NumberPrimitive(1.0));
        t.addRow(vc);
        return t;
    }

    private static String wideFetchCompactBody(int rowsCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"success\",\"cacheId\":\"c1\",\"sampleOnly\":true,\"rowsOmitted\":true,");
        sb.append("\"columns\":[");
        for (int i = 0; i < 30; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"col_").append(i).append("\",\"baseType\":\"STRING\"}");
        }
        sb.append("],\"rows\":[");
        for (int i = 0; i < rowsCount; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"col_0\":\"row_").append(i).append("\",\"payload\":\"");
            sb.append("large row payload used to force gateway compaction");
            sb.append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    void postMarkerToolDefinitions_includes_chart_when_recoverable_attempt_no_emit_and_chartable_tabular()
            throws Exception {
        AgentToolContext.setConversationId("agent-loop-pmdef");
        String srcCid = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        AgentToolContext.markChartBuildAttemptedThisTurn();
        AgentToolContext.markChartBuildFailedRecoverablyThisTurn();
        String tabJson = "{\"status\":\"success\",\"resultKind\":\"CACHED_GROUP_METRIC_INLINE\",\"cacheId\":\"" + srcCid
                + "\",\"answerSetComplete\":true,\"totalRows\":1,\"returnedRows\":1,\"rows\":[{\"n\":1}],\"columns\":[]}";
        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", tabJson);
        List<ToolDefinition> pm = AgentLoop.postMarkerToolDefinitions(toolsWithChart());
        assertEquals(1, pm.size());
        assertEquals("build_chart_from_tabular_result", pm.get(0).getName());
    }

    @Test
    void postMarkerToolDefinitions_includes_chart_when_complete_artifacts_without_rescue() throws Exception {
        AgentToolContext.setConversationId("agent-loop-pmdef-2");
        String srcCid = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        String tabJson = "{\"status\":\"success\",\"resultKind\":\"CACHED_GROUP_METRIC_INLINE\",\"cacheId\":\"" + srcCid
                + "\",\"answerSetComplete\":true,\"totalRows\":1,\"returnedRows\":1,\"rows\":[{\"n\":1}],\"columns\":[]}";
        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", tabJson);
        List<ToolDefinition> pm = AgentLoop.postMarkerToolDefinitions(toolsWithChart());
        assertEquals(1, pm.size());
        assertEquals("build_chart_from_tabular_result", pm.get(0).getName());
    }

    @Test
    void postMarkerToolDefinitions_empty_when_tabulate_incomplete_no_presentation_artifact() throws Exception {
        AgentToolContext.setConversationId("agent-loop-inc");
        String srcCid = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        String tabJson = "{\"status\":\"success\",\"resultKind\":\"CACHED_GROUP_METRIC_INLINE\",\"cacheId\":\"" + srcCid
                + "\",\"answerSetComplete\":false,\"sampleOnly\":true,\"totalRows\":10,\"returnedRows\":1,"
                + "\"rows\":[{\"n\":1}],\"columns\":[]}";
        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", tabJson);
        assertTrue(AgentLoop.postMarkerToolDefinitions(toolsWithChart()).isEmpty());
    }

    @Test
    void postMarkerToolDefinitions_empty_when_chart_wire_already_emitted() throws Exception {
        AgentToolContext.setConversationId("agent-loop-pmdef-3");
        String srcCid = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        AgentToolContext.markChartBuildAttemptedThisTurn();
        AgentToolContext.markChartBuildFailedRecoverablyThisTurn();
        String tabJson = "{\"status\":\"success\",\"resultKind\":\"CACHED_GROUP_METRIC_INLINE\",\"cacheId\":\"" + srcCid
                + "\",\"answerSetComplete\":true,\"totalRows\":1,\"returnedRows\":1,\"rows\":[{\"n\":1}],\"columns\":[]}";
        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", tabJson);
        AgentToolContext.markParlerChartWireEmitted(srcCid);
        assertTrue(AgentLoop.postMarkerToolDefinitions(toolsWithChart()).isEmpty(),
                "the only complete artifact is already the source of a downlinked chart");
    }

    private static String completeTabulate(String cacheId) {
        return "{\"status\":\"success\",\"resultKind\":\"CACHED_BIN_NUMERIC_INLINE\",\"cacheId\":\"" + cacheId
                + "\",\"answerSetComplete\":true,\"totalRows\":1,\"returnedRows\":1,\"rows\":[{\"n\":1}],\"columns\":[]}";
    }

    /**
     * {@code query_property_history} downlinks a numeric line chart by itself (no {@code source.sourceCacheId}).
     * With the old turn-wide "no chart wire yet" test that chart closed the presentation round, so a histogram
     * could never follow a history query in the same turn (first seen live on 0.1.243).
     */
    @Test
    void postMarkerToolDefinitions_includes_chart_after_a_chart_without_a_source_cache() throws Exception {
        AgentToolContext.setConversationId("agent-loop-pmdef-auto");
        AgentToolContext.markParlerChartWireEmitted(null);
        String binCid = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", completeTabulate(binCid));
        List<ToolDefinition> pm = AgentLoop.postMarkerToolDefinitions(toolsWithChart());
        assertEquals(1, pm.size());
        assertEquals("build_chart_from_tabular_result", pm.get(0).getName());
    }

    @Test
    void postMarkerToolDefinitions_tracks_charted_artifacts_one_by_one() throws Exception {
        AgentToolContext.setConversationId("agent-loop-pmdef-multi");
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", completeTabulate(a));
        AgentToolContext.markParlerChartWireEmitted(a);
        assertTrue(AgentLoop.postMarkerToolDefinitions(toolsWithChart()).isEmpty(), "A is charted, nothing else");
        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", completeTabulate(b));
        assertEquals(1, AgentLoop.postMarkerToolDefinitions(toolsWithChart()).size(), "B is complete and not charted");
        AgentToolContext.markParlerChartWireEmitted(b);
        assertTrue(AgentLoop.postMarkerToolDefinitions(toolsWithChart()).isEmpty(), "every artifact is charted");
    }

    /**
     * The live sequence end to end through the loop: history query (emits its own line chart), then a complete
     * distribution result, then the post-marker round. That round must offer the chart tool, the histogram build
     * must run, and the turn must close with the forced no-tool summary.
     */
    @Test
    void history_auto_chart_then_distribution_still_gets_a_presentation_round() throws Exception {
        AgentToolContext.setConversationId("agent-loop-pres-auto");
        String binCid = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        String binned = completeTabulate(binCid);
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Arrays.asList(new ToolCall("h1", "query_property_history", "{}"))));
        llm.enqueue(toolCallsResponse(Arrays.asList(new ToolCall("t1", "tabulate_cached_result", "{}"))));
        llm.enqueue(toolCallsResponse(Arrays.asList(new ToolCall("b1", "build_chart_from_tabular_result",
                "{\"source\":\"cache_id\",\"cacheId\":\"" + binCid + "\",\"kind\":\"histogram\"}"))));
        llm.enqueue(textResponse("Histogram rendered."));
        List<String> executed = new ArrayList<>();
        ToolExecutor exec = tc -> {
            executed.add(tc.getFunctionName());
            if ("query_property_history".equals(tc.getFunctionName())) {
                AgentToolContext.markParlerChartWireEmitted(null);
                return "{\"status\":\"success\",\"resultKind\":\"NUMERIC_HISTORY_INLINE\",\"chartEmitted\":true}";
            }
            if ("tabulate_cached_result".equals(tc.getFunctionName())) {
                TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", binned);
                return binned;
            }
            if ("build_chart_from_tabular_result".equals(tc.getFunctionName())) {
                AgentToolContext.markParlerChartWireEmitted(binCid);
                return "{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"kind\":\"histogram\"}";
            }
            return "{}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("distribution of contactForce as a histogram"));
        AgentLoop.AgentResult r = loop.run(msgs, toolsWithChart());
        assertEquals(Arrays.asList("query_property_history", "tabulate_cached_result",
                "build_chart_from_tabular_result"), executed);
        assertEquals(4, llm.requests.size());
        assertEquals(1, llm.requests.get(2).getTools().size(), "the post-marker round offers the chart tool");
        assertEquals("build_chart_from_tabular_result", llm.requests.get(2).getTools().get(0).getName());
        assertTrue(llm.requests.get(3).isToolChoiceNone(), "then the forced no-tool summary");
        String perf = r.getLlmTurnPerformanceWireJson();
        assertTrue(perf.contains("\"presentationPhaseEntered\":true"), perf);
        assertTrue(perf.contains("\"parlerChartWireEmittedCount\":2"), perf);
    }

    @Test
    void post_marker_rescue_round_retains_build_chart_after_recoverable_failure_and_tabulate() throws Exception {
        AgentToolContext.setConversationId("agent-loop-chart-rescue");
        String aggCid = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        String tabJson = "{\"status\":\"success\",\"resultKind\":\"CACHED_GROUP_METRIC_INLINE\",\"cacheId\":\"" + aggCid
                + "\",\"answerSetComplete\":true,\"totalRows\":1,\"returnedRows\":1,\"rows\":[{\"n\":1}],\"columns\":[]}";
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Arrays.asList(
                new ToolCall("t0", "build_chart_from_tabular_result",
                        "{\"source\":\"cache_id\",\"cacheId\":\"" + aggCid + "\",\"kind\":\"pie\",\"xColumn\":\"n\","
                                + "\"yColumn\":\"n\"}"),
                new ToolCall("t1", "tabulate_cached_result", "{\"cacheId\":\"" + aggCid + "\"}")
        )));
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t2", "build_chart_from_tabular_result",
                        "{\"source\":\"last_invoke\",\"xColumn\":\"n\",\"yColumn\":\"n\"}"))));
        llm.enqueue(textResponse("done"));
        ToolExecutor exec = tc -> {
            if ("build_chart_from_tabular_result".equals(tc.getFunctionName())) {
                if ("t2".equals(tc.getId())) {
                    AgentToolContext.markParlerChartWireEmitted();
                    return "{\"status\":\"success\",\"code\":\"CHART_EMITTED\"}";
                }
                return BUILD_CHART_DUPLICATE_ERROR;
            }
            if ("tabulate_cached_result".equals(tc.getFunctionName())) {
                TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", tabJson);
                return tabJson;
            }
            return "{}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("plain status question without chart vocabulary"));
        loop.run(msgs, toolsWithChart());
        assertEquals(3, llm.requests.size());
        LlmChatRequest rescue = llm.requests.get(1);
        assertFalse(rescue.isToolChoiceNone());
        assertEquals(1, rescue.getTools().size());
        assertEquals("build_chart_from_tabular_result", rescue.getTools().get(0).getName());
    }

    @Test
    void aggregate_first_presentation_offers_build_chart_then_forces_summary_with_telemetry() throws Exception {
        AgentToolContext.setConversationId("agent-loop-pres-2");
        String cid1 = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        String cid2 = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        String tab1 = "{\"status\":\"success\",\"resultKind\":\"CACHED_GROUP_METRIC_INLINE\",\"cacheId\":\"" + cid1
                + "\",\"answerSetComplete\":true,\"totalRows\":1,\"returnedRows\":1,\"rows\":[{\"n\":1}],\"columns\":[]}";
        String tab2 = "{\"status\":\"success\",\"resultKind\":\"CACHED_GROUP_METRIC_INLINE\",\"cacheId\":\"" + cid2
                + "\",\"answerSetComplete\":true,\"totalRows\":1,\"returnedRows\":1,\"rows\":[{\"n\":1}],\"columns\":[]}";
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Arrays.asList(
                new ToolCall("u1", "tabulate_cached_result", "{}"),
                new ToolCall("u2", "tabulate_cached_result", "{}"))));
        llm.enqueue(toolCallsResponse(Arrays.asList(
                new ToolCall("b1", "build_chart_from_tabular_result",
                        "{\"source\":\"cache_id\",\"cacheId\":\"" + cid1 + "\",\"kind\":\"bar\",\"xColumn\":\"n\","
                                + "\"yColumn\":\"n\"}"),
                new ToolCall("b2", "build_chart_from_tabular_result",
                        "{\"source\":\"cache_id\",\"cacheId\":\"" + cid2 + "\",\"kind\":\"bar\",\"xColumn\":\"n\","
                                + "\"yColumn\":\"n\"}"))));
        llm.enqueue(textResponse("Final prose summary."));
        String chartOk = "{\"status\":\"success\",\"code\":\"CHART_EMITTED\"}";
        ToolExecutor exec = tc -> {
            if ("tabulate_cached_result".equals(tc.getFunctionName())) {
                String body = "u1".equals(tc.getId()) ? tab1 : tab2;
                TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", body);
                return body;
            }
            if ("build_chart_from_tabular_result".equals(tc.getFunctionName())) {
                AgentToolContext.markParlerChartWireEmitted();
                return chartOk;
            }
            return "{}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("aggregate two charts"));
        AgentLoop.AgentResult r = loop.run(msgs, toolsWithChart());
        assertEquals(3, llm.requests.size());
        assertEquals(1, llm.requests.get(1).getTools().size());
        assertFalse(llm.requests.get(1).isToolChoiceNone());
        assertTrue(llm.requests.get(2).isToolChoiceNone());
        String perf = r.getLlmTurnPerformanceWireJson();
        assertTrue(perf.contains("\"presentationPhaseEntered\":true"), perf);
        assertTrue(perf.contains("\"presentationActionsExecuted\":2"), perf);
        assertTrue(perf.contains("\"presentationActionsRequested\":2"), perf);
        assertTrue(perf.contains("\"noToolFinalAnswerApplied\":true"), perf);
    }

    @Test
    void presentation_phase_blocks_excess_chart_actions() throws Exception {
        AgentToolContext.setConversationId("agent-loop-pres-cap");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(oneRowNumericTable());
        String tab = "{\"status\":\"success\",\"resultKind\":\"CACHED_GROUP_METRIC_INLINE\",\"cacheId\":\"" + cid
                + "\",\"answerSetComplete\":true,\"totalRows\":1,\"returnedRows\":1,\"rows\":[{\"n\":1}],\"columns\":[]}";
        List<ToolCall> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(new ToolCall("c" + i, "build_chart_from_tabular_result",
                    "{\"source\":\"cache_id\",\"cacheId\":\"" + cid + "\",\"kind\":\"bar\",\"xColumn\":\"n\",\"yColumn\":\"n\","
                            + "\"title\":\"t" + i + "\"}"));
        }
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("u1", "tabulate_cached_result", "{}"))));
        llm.enqueue(toolCallsResponse(seven));
        llm.enqueue(textResponse("wrapped up."));
        ToolExecutor exec = tc -> {
            if ("tabulate_cached_result".equals(tc.getFunctionName())) {
                TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", tab);
                return tab;
            }
            if ("build_chart_from_tabular_result".equals(tc.getFunctionName())) {
                AgentToolContext.markParlerChartWireEmitted();
                return "{\"status\":\"success\",\"code\":\"CHART_EMITTED\"}";
            }
            return "{}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("many charts"));
        AgentLoop.AgentResult r = loop.run(msgs, toolsWithChart());
        assertEquals(3, llm.requests.size());
        String perf = r.getLlmTurnPerformanceWireJson();
        assertTrue(perf.contains("\"presentationActionsBlocked\":1"), perf);
        assertTrue(perf.contains("\"presentationActionsExecuted\":6"), perf);
        assertTrue(perf.contains("\"presentationActionsRequested\":7"), perf);
    }

    @Test
    void end_turn_chart_rescue_fires_after_prose_with_qualifying_extended_infotable() throws Exception {
        AgentToolContext.setConversationId("agent-loop-eotr");
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("pre", "build_chart_from_tabular_result", "{}"))));
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t1", "utilization_records_by_machine", "{}"))));
        llm.enqueue(textResponse("Summary without chart."));
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t2", "build_chart_from_tabular_result",
                        "{\"source\":\"last_invoke\",\"xColumn\":\"UtilizationState\",\"yColumn\":\"Duration\"}"))));
        llm.enqueue(textResponse("Final."));
        String infotableOk = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rows\":["
                + "{\"UtilizationState\":\"Down\",\"Duration\":5}]}";
        ToolExecutor exec = tc -> {
            if ("build_chart_from_tabular_result".equals(tc.getFunctionName())) {
                if ("pre".equals(tc.getId())) {
                    return BUILD_CHART_DUPLICATE_ERROR;
                }
                AgentToolContext.markParlerChartWireEmitted();
                return "{\"status\":\"success\",\"code\":\"CHART_EMITTED\"}";
            }
            if ("utilization_records_by_machine".equals(tc.getFunctionName())) {
                TabularChartRoundHooks.afterBuiltInToolResult(tc.getFunctionName(), infotableOk);
                return infotableOk;
            }
            return "{\"status\":\"success\"}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("plain question"));
        AgentLoop.AgentResult r = loop.run(msgs, toolsWithChartAndUtilization());
        assertEquals(5, llm.requests.size());
        LlmChatRequest rescue = llm.requests.get(3);
        assertEquals(1, rescue.getTools().size());
        assertEquals("build_chart_from_tabular_result", rescue.getTools().get(0).getName());
        assertFalse(rescue.isToolChoiceNone());
        assertTrue(r.getLlmTurnPerformanceWireJson().contains("\"chartRescueAttempted\":true"),
                r.getLlmTurnPerformanceWireJson());
    }

    @Test
    void end_turn_chart_rescue_fires_when_two_qualifying_infotable_successes_uses_latest() throws Exception {
        AgentToolContext.setConversationId("agent-loop-eotr-latest");
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("pre", "build_chart_from_tabular_result", "{}"))));
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t1", "utilization_ext_a", "{}"))));
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t2", "utilization_ext_b", "{}"))));
        llm.enqueue(textResponse("Summary without chart."));
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t3", "build_chart_from_tabular_result",
                        "{\"source\":\"last_invoke\",\"xColumn\":\"UtilizationState\",\"yColumn\":\"Percentage\"}"))));
        llm.enqueue(textResponse("Final."));
        String infotableA = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rows\":["
                + "{\"UtilizationState\":\"Raw\",\"Duration\":1}]}";
        String infotableB = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rows\":["
                + "{\"UtilizationState\":\"Down\",\"Percentage\":59.45},"
                + "{\"UtilizationState\":\"Unavailable\",\"Percentage\":39.64},"
                + "{\"UtilizationState\":\"Running\",\"Percentage\":0.9}]}";
        ToolExecutor exec = tc -> {
            if ("build_chart_from_tabular_result".equals(tc.getFunctionName())) {
                if ("pre".equals(tc.getId())) {
                    return BUILD_CHART_DUPLICATE_ERROR;
                }
                AgentToolContext.markParlerChartWireEmitted();
                return "{\"status\":\"success\",\"code\":\"CHART_EMITTED\"}";
            }
            if ("utilization_ext_a".equals(tc.getFunctionName())) {
                TabularChartRoundHooks.afterBuiltInToolResult(tc.getFunctionName(), infotableA);
                return infotableA;
            }
            if ("utilization_ext_b".equals(tc.getFunctionName())) {
                TabularChartRoundHooks.afterBuiltInToolResult(tc.getFunctionName(), infotableB);
                return infotableB;
            }
            return "{\"status\":\"success\"}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("another plain question"));
        AgentLoop.AgentResult r = loop.run(msgs, toolsWithChartAndTwoUtilizationInfotools());
        assertEquals(6, llm.requests.size(), "rescue after prose when two qualifying tabular tools (last_invoke latest-wins)");
        LlmChatRequest rescue = llm.requests.get(4);
        assertEquals(1, rescue.getTools().size());
        assertEquals("build_chart_from_tabular_result", rescue.getTools().get(0).getName());
        assertFalse(rescue.isToolChoiceNone());
        assertTrue(r.getLlmTurnPerformanceWireJson().contains("\"chartRescueAttempted\":true"),
                r.getLlmTurnPerformanceWireJson());
    }

    @Test
    void end_turn_chart_rescue_skips_when_sample_truncated_flag() throws Exception {
        AgentToolContext.setConversationId("agent-loop-eotr-neg");
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("pre", "build_chart_from_tabular_result", "{}"))));
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t1", "utilization_records_by_machine", "{}"))));
        llm.enqueue(textResponse("Only text."));
        String truncated = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"sampleOnly\":true,"
                + "\"rows\":[{\"a\":1}]}";
        ToolExecutor exec = tc -> {
            if ("build_chart_from_tabular_result".equals(tc.getFunctionName())) {
                return BUILD_CHART_DUPLICATE_ERROR;
            }
            if ("utilization_records_by_machine".equals(tc.getFunctionName())) {
                TabularChartRoundHooks.afterBuiltInToolResult(tc.getFunctionName(), truncated);
                return truncated;
            }
            return "{\"status\":\"success\"}";
        };
        AgentLoop loop = new AgentLoop(llm, exec, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("plain question"));
        loop.run(msgs, toolsWithChartAndUtilization());
        assertEquals(3, llm.requests.size());
    }

    @Test
    void chartExpectedButMissing_true_after_build_attempt_without_chart_wire() {
        AgentToolContext.setConversationId("chart-exp-miss-1");
        AgentToolContext.markChartBuildAttemptedThisTurn();
        assertTrue(AgentToolContext.chartExpectedButMissingForTurnPerf());
    }

    @Test
    void chartExpectedButMissing_false_after_chart_wire_emitted() {
        AgentToolContext.setConversationId("chart-exp-miss-2");
        AgentToolContext.markChartBuildAttemptedThisTurn();
        AgentToolContext.markParlerChartWireEmitted();
        assertFalse(AgentToolContext.chartExpectedButMissingForTurnPerf());
    }

    private static List<ToolDefinition> stubTools() {
        return Collections.singletonList(new ToolDefinition("noop", "desc", Collections.emptyMap()));
    }

    private static List<ToolDefinition> toolsWithInvokeServiceAndNoop() {
        List<ToolDefinition> out = new ArrayList<>();
        out.add(new ToolDefinition("invoke_service", "invoke", Collections.emptyMap()));
        out.add(new ToolDefinition("noop", "desc", Collections.emptyMap()));
        return out;
    }

    private static List<ToolDefinition> toolsWithChart() {
        List<ToolDefinition> out = new ArrayList<>();
        out.add(new ToolDefinition("noop", "d", Collections.emptyMap()));
        out.add(new ToolDefinition("tabulate_cached_result", "d", Collections.emptyMap()));
        out.add(new ToolDefinition("build_chart_from_tabular_result", "d", Collections.emptyMap()));
        return out;
    }

    private static List<ToolDefinition> toolsWithInvokeServiceAndChart() {
        List<ToolDefinition> out = toolsWithChart();
        out.add(new ToolDefinition("invoke_service", "invoke", Collections.emptyMap()));
        return out;
    }

    private static List<ToolDefinition> toolsWithChartAndUtilization() {
        List<ToolDefinition> out = toolsWithChart();
        out.add(new ToolDefinition("utilization_records_by_machine", "d", Collections.emptyMap()));
        return out;
    }

    private static List<ToolDefinition> toolsWithChartAndTwoUtilizationInfotools() {
        List<ToolDefinition> out = toolsWithChart();
        out.add(new ToolDefinition("utilization_ext_a", "d", Collections.emptyMap()));
        out.add(new ToolDefinition("utilization_ext_b", "d", Collections.emptyMap()));
        return out;
    }

    private static LlmResponse textResponse(String text) {
        return new LlmResponse(text, Collections.emptyList(), LlmResponse.FinishReason.STOP,
                1, 2, 1, 2, 0, 0, 0, "rid", 0L);
    }

    private static LlmResponse toolCallsResponse(List<ToolCall> calls) {
        return new LlmResponse("", calls, LlmResponse.FinishReason.TOOL_CALLS,
                3, 4, 3, 4, 0, 0, 0, "rid2", 0L);
    }

    private static String largeFirstPartyTabularResult(String functionName) {
        String resultKind;
        String rowsKey;
        if ("list_entities_by_type".equals(functionName)) {
            resultKind = "ENTITY_LIST_LARGE";
            rowsKey = "rootEntityList";
        } else if ("query_alert_history".equals(functionName)) {
            resultKind = "INFOTABLE_LARGE";
            rowsKey = "sampleRows";
        } else {
            resultKind = "ENTITY_QUERY_LARGE";
            rowsKey = "rows";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"success\",\"resultKind\":\"").append(resultKind)
                .append("\",\"totalRows\":90,\"columns\":[{\"name\":\"name\",\"baseType\":\"STRING\"}],\"")
                .append(rowsKey).append("\":[");
        for (int i = 0; i < 90; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"Thing-").append(i)
                    .append("\",\"marker\":\"marker-").append(String.format("%03d", i))
                    .append("\",\"description\":\"")
                    .append("first party tabular/list gateway coverage row ".repeat(4))
                    .append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static final class OpenAiWireShapeClient implements LlmClient {
        private final List<Map<String, Object>> openAiBodies = new ArrayList<>();
        private final Queue<LlmResponse> scripted = new ArrayDeque<>();
        private final LlmUsageWireIds ids = new LlmUsageWireIds("pt", "tpl", "openai-chat-completions-v1", "mdl");

        void enqueue(LlmResponse r) {
            scripted.add(r);
        }

        @Override
        public LlmResponse chat(LlmChatRequest request) throws Exception {
            int maxTok = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, request.getRequestedMaxOutputTokens()));
            openAiBodies.add(ChatCompletionsApi.buildRequestBody(
                    "gpt-4o",
                    request.getMessages(),
                    request.getTools(),
                    request,
                    false,
                    maxTok));
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

    private static final class RecordingLlmClient implements LlmClient {
        private final List<LlmChatRequest> requests = new ArrayList<>();
        private final Queue<LlmResponse> scripted = new ArrayDeque<>();
        private final LlmUsageWireIds ids = new LlmUsageWireIds("pt", "tpl", "openai-chat-completions-v1", "mdl");
        private Runnable beforeChatReturn;

        void enqueue(LlmResponse r) {
            scripted.add(r);
        }

        void setBeforeChatReturn(Runnable r) {
            this.beforeChatReturn = r;
        }

        @Override
        public LlmResponse chat(LlmChatRequest request) throws Exception {
            requests.add(request);
            LlmResponse r = scripted.poll();
            if (r == null) {
                throw new IllegalStateException("no scripted response");
            }
            if (beforeChatReturn != null) {
                beforeChatReturn.run();
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
