package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.http.Header;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.compaction.ConversationCheckpoint;
import com.thingworx.things.agent.compaction.ConversationCheckpointGenerator;
import com.thingworx.things.agent.compaction.ConversationCompactionBoundarySelector;
import com.thingworx.things.agent.llm.AnthropicMessagesApi;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ProviderLlmClientBridge;
import com.thingworx.things.agent.llm.TestBridgeContext;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.usage.AgentLlmCallStreamWriter;
import com.thingworx.things.agent.llm.usage.LlmCallContext;
import com.thingworx.things.agent.llm.usage.LlmCallEvent;
import com.thingworx.things.agent.llm.usage.LlmCallEventType;
import com.thingworx.things.agent.llm.usage.LlmCallKind;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;
import com.thingworx.things.agent.llm.usage.LlmRecordedHttpChat;
import com.thingworx.things.agent.llm.usage.LlmUsageSnapshot;
import com.thingworx.things.agent.playbook.PlaybookDocument;
import com.thingworx.things.agent.playbook.PlaybookRunner;
import com.thingworx.things.agent.playbook.PlaybookRunResult;
import com.thingworx.things.agent.playbook.PlaybookToolExecutionResult;
import com.thingworx.things.agent.playbook.PlaybookToolExecutor;
import com.thingworx.things.agent.playbook.PlaybookValidator;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * CC-7.7 census: real entry points must emit one dispatched recorder event per HTTP execute.
 */
class AgentLoopCallSiteCensusTest {

    private static final String TABULATE_COMPLETE_JSON =
            "{\"status\":\"success\",\"answerSetComplete\":true,\"rows\":[{\"k\":1}]}";

    private static final LlmUsageWireIds IDS =
            LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
    private static final String CHECKPOINT_REPLY =
            "{\"goal\":\"compare pump throughput\",\"constraints\":[\"metric units\"],"
                    + "\"progress\":{\"done\":[\"surveyed 3 assets\"],\"inProgress\":[],\"blocked\":[]},"
                    + "\"nextSteps\":[\"chart the two candidates\"]}";
    private static final String MINI_PLAYBOOK = "{"
            + "\"schema\":\"parler-playbook-v1\","
            + "\"title\":\"census-mini\","
            + "\"nodes\":["
            + "{\"id\":\"x\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"noop_pb\",\"args\":{},"
            + "\"evidence\":{\"label\":\"L\"}},"
            + "{\"id\":\"sum\",\"kind\":\"llm_summary\",\"dependsOn\":[\"x\"],\"prompt\":\"Reply ok.\","
            + "\"evidenceRefs\":[\"x\"],\"maxEvidenceBytes\":8000}"
            + "],\"finalNode\":\"sum\"}";

    @BeforeEach
    void installSink() {
        LlmCallRecorder.resetForTest();
        ParlerRunningTurnCancelRegistry.clearAllForTests();
        AgentToolContext.clear();
    }

    @AfterEach
    void cleanup() {
        LlmCallRecorder.resetForTest();
        ParlerRunningTurnCancelRegistry.clearAllForTests();
        AgentToolContext.clear();
    }

    @Test
    void entryPoints_httpExecuteCountMatchesDispatchedRecorderEvents() throws Exception {
        AtomicInteger httpExecutes = new AtomicInteger();
        RecordingHttpLlmClient llm = new RecordingHttpLlmClient(httpExecutes, IDS);

        runAgentRound(llm);
        runEmptyFinalRetry(llm);
        runCheckpoint(llm);
        runPlaybookSummaries(llm);
        runProviderHealthCheck(httpExecutes);

        List<LlmCallEvent> events = AgentLlmCallStreamWriter.testCapture();
        long dispatched = events.stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_DISPATCHED)
                .count();
        assertEquals(httpExecutes.get(), dispatched);
        assertEquals(7, dispatched, "normal + empty-final-retry(2) + checkpoint + playbook(2) + bridge healthCheck");

        assertTrue(events.stream().anyMatch(e -> e.getCallKind() == LlmCallKind.AGENT_ROUND
                && "cid-census".equals(e.getConversationId())
                && "AgentThing".equals(e.getAgentThing())));
        assertDispatchedIdentity(LlmCallKind.CHECKPOINT, "rid-ckpt", "cid-ckpt", "AgentThing");
        List<LlmCallEvent> playbookEvents = events.stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_DISPATCHED
                        && e.getCallKind() == LlmCallKind.PLAYBOOK)
                .collect(Collectors.toList());
        assertEquals(2, playbookEvents.size());
        for (LlmCallEvent event : playbookEvents) {
            assertEquals("rid-retry", event.getTurnRequestId());
            assertEquals("cid-retry", event.getConversationId());
            assertEquals("AgentThing", event.getAgentThing());
        }

        List<LlmCallEvent> dispatchedEvents = events.stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_DISPATCHED)
                .collect(Collectors.toList());
        assertEquals(7, dispatchedEvents.size());
        for (LlmCallEvent event : dispatchedEvents) {
            assertTrue(event.getEventJson().contains("\"attemptIndex\":1"),
                    "dispatch must record attemptIndex=1: " + event.getCallKind());
        }
        long distinctCallIds = dispatchedEvents.stream().map(LlmCallEvent::getCallId).distinct().count();
        assertEquals(dispatchedEvents.size(), distinctCallIds, "each dispatch must have a distinct callId");

        List<String> retryRoundLogicalIds = events.stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_DISPATCHED
                        && e.getCallKind() == LlmCallKind.AGENT_ROUND
                        && "cid-retry".equals(e.getConversationId()))
                .map(LlmCallEvent::getLogicalCallId)
                .collect(Collectors.toList());
        assertEquals(2, retryRoundLogicalIds.size(), "empty-final retry uses two restricted rounds");
        assertEquals(2, retryRoundLogicalIds.stream().distinct().count(),
                "each restricted agent round must have a distinct logicalCallId");

        long playbookDispatches = events.stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_DISPATCHED
                        && e.getCallKind() == LlmCallKind.PLAYBOOK)
                .count();
        assertEquals(2, playbookDispatches, "playbook fixture must emit two llm_summary dispatches");
    }

    @Test
    void postMarkerRound_emitsDistinctLogicalCallIdPerHttpExecute() throws Exception {
        AtomicInteger httpExecutes = new AtomicInteger();
        RecordingHttpLlmClient llm = new RecordingHttpLlmClient(httpExecutes, IDS);
        AgentToolContext.setConversationId("cid-post");
        AgentToolContext.setParlerStreamIds("rid-post", "remote");
        AgentToolContext.setParlerRunningCancelAgentThingNameForTests("AgentThing");
        llm.enqueue(toolCallsResponse(Collections.singletonList(
                new ToolCall("t1", "tabulate_cached_result", "{\"cacheId\":\"x\"}"))));
        llm.enqueue(textResponse("done"));
        AgentLoop loop = new AgentLoop(llm, tc -> TABULATE_COMPLETE_JSON, null, 0, 8192, 8, 60_000L);
        AgentLoop.AgentResult result = loop.run(
                new ArrayList<>(List.of(ChatMessage.user("hi"))), stubTools());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, result.getStatus());
        assertEquals(2, httpExecutes.get());
        assertRestrictedAgentRoundIdentity("rid-post", "cid-post", "AgentThing", 2);
    }

    @Test
    void summaryNoneRound_emitsDistinctLogicalCallIdPerHttpExecute() throws Exception {
        AtomicInteger httpExecutes = new AtomicInteger();
        RecordingHttpLlmClient llm = new RecordingHttpLlmClient(httpExecutes, IDS);
        AgentToolContext.setConversationId("cid-sum");
        AgentToolContext.setParlerStreamIds("rid-sum", "remote");
        AgentToolContext.setParlerRunningCancelAgentThingNameForTests("AgentThing");
        llm.enqueue(toolCallsResponse(Arrays.asList(
                new ToolCall("t1", "search_document_chunks", "{\"q\":\"damage\"}"),
                new ToolCall("t2", "search_document_chunks", "{\"q\":\"table\"}"))));
        llm.enqueue(textResponse("final after repetition guard"));
        AgentLoop loop = new AgentLoop(llm, tc -> {
            AgentToolContext.incrementRepetitionBlockedCountForTurnPerf();
            return "{\"status\":\"error\",\"code\":\"REPETITION_BLOCKED\",\"message\":\"blocked\"}";
        }, null, 0, 8192, 8, 60_000L);
        AgentLoop.AgentResult result = loop.run(
                new ArrayList<>(List.of(ChatMessage.user("search docs"))), stubTools());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, result.getStatus());
        assertEquals(2, httpExecutes.get());
        assertRestrictedAgentRoundIdentity("rid-sum", "cid-sum", "AgentThing", 2);
    }

    @Test
    void contextPlanningInputCapChars_emitsNoRecorderEvents() throws Exception {
        AtomicInteger httpExecutes = new AtomicInteger();
        RecordingHttpLlmClient llm = new RecordingHttpLlmClient(httpExecutes, IDS);
        TestBridgeContext context = new TestBridgeContext(TestBridgeContext.disabledRateConfig());
        ProviderLlmClientBridge bridge = new ProviderLlmClientBridge(context, llm, "gpt-4o");

        int before = AgentLlmCallStreamWriter.testCapture().size();
        bridge.contextPlanningInputCapChars(1024, null);
        assertEquals(before, AgentLlmCallStreamWriter.testCapture().size());
        assertEquals(0, httpExecutes.get());
    }

    private static void assertRestrictedAgentRoundIdentity(
            String turnRequestId,
            String conversationId,
            String agentThing,
            int expectedDispatches) {
        List<LlmCallEvent> agentDispatches = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_DISPATCHED
                        && e.getCallKind() == LlmCallKind.AGENT_ROUND
                        && conversationId.equals(e.getConversationId()))
                .collect(Collectors.toList());
        assertEquals(expectedDispatches, agentDispatches.size());
        for (LlmCallEvent event : agentDispatches) {
            assertEquals(turnRequestId, event.getTurnRequestId());
            assertEquals(conversationId, event.getConversationId());
            assertEquals(agentThing, event.getAgentThing());
            assertTrue(event.getEventJson().contains("\"attemptIndex\":1"),
                    "dispatch must record attemptIndex=1: " + event.getCallId());
        }
        assertEquals(agentDispatches.size(),
                agentDispatches.stream().map(LlmCallEvent::getCallId).distinct().count(),
                "each restricted round must have a distinct callId");
        assertEquals(agentDispatches.size(),
                agentDispatches.stream().map(LlmCallEvent::getLogicalCallId).distinct().count(),
                "each restricted round must have a distinct logicalCallId");
    }

    private static void assertDispatchedIdentity(
            LlmCallKind kind,
            String turnRequestId,
            String conversationId,
            String agentThing) {
        List<LlmCallEvent> matches = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_DISPATCHED
                        && e.getCallKind() == kind)
                .collect(Collectors.toList());
        assertEquals(1, matches.size(), "expected one dispatched " + kind + " event");
        LlmCallEvent event = matches.get(0);
        assertEquals(turnRequestId, event.getTurnRequestId());
        assertEquals(conversationId, event.getConversationId());
        assertEquals(agentThing, event.getAgentThing());
    }

    @Test
    void agentLoopCancel_marksAgentRoundNotPlaybookSubcall() throws Exception {
        AtomicInteger httpExecutes = new AtomicInteger();
        RecordingHttpLlmClient llm = new RecordingHttpLlmClient(httpExecutes, IDS);
        llm.enqueue(textResponse("Invisible final"));
        llm.setBeforeChatReturn(() -> {
            LlmCallContext roundContext = LlmCallRecorder.agentRoundContext(
                    "rid-cancel", "cid-cancel", "AgentThing", 1, IDS, null);
            LlmCallContext playbookContext = LlmCallRecorder.subCallContext(LlmCallKind.PLAYBOOK, roundContext, IDS);
            try (LlmCallRecorder.LlmCallAttempt playbookAttempt = LlmCallRecorder.begin(playbookContext)) {
                playbookAttempt.markDispatched();
                playbookAttempt.finishSuccess(IDS, LlmUsageSnapshot.unavailable(), null, 200, "req-pb", "resp-pb",
                        "gpt-4o", 8L);
            }
            ParlerRunningTurnCancelRegistry.tryRequestCancel("cid-cancel", "rid-cancel", "alice", "AgentThing");
        });

        AgentToolContext.setConversationId("cid-cancel");
        AgentToolContext.setParlerStreamIds("rid-cancel", "remote");
        AgentToolContext.setParlerGatewayCallerPrincipal("alice");
        AgentToolContext.setParlerRunningCancelAgentThingNameForTests("AgentThing");
        ParlerRunningTurnCancelRegistry.register("cid-cancel", "rid-cancel", "alice", "AgentThing");

        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L);
        AgentLoop.AgentResult result = loop.run(
                new ArrayList<>(List.of(ChatMessage.user("hi"))),
                stubTools());
        assertEquals(AgentLoop.AgentResult.Status.CANCELLED, result.getStatus());

        List<LlmCallEvent> cancelEvents = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_CANCEL_REQUESTED)
                .collect(Collectors.toList());
        assertEquals(1, cancelEvents.size());
        assertEquals(LlmCallKind.AGENT_ROUND, cancelEvents.get(0).getCallKind());
        assertTrue(eventsForKind(LlmCallKind.AGENT_ROUND).stream()
                .anyMatch(e -> e.getCallId().equals(cancelEvents.get(0).getCallId())));
        assertTrue(eventsForKind(LlmCallKind.PLAYBOOK).stream()
                .noneMatch(e -> e.getCallId().equals(cancelEvents.get(0).getCallId())));
    }

    private static List<LlmCallEvent> eventsForKind(LlmCallKind kind) {
        return AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getCallKind() == kind)
                .collect(Collectors.toList());
    }

    private static void runAgentRound(RecordingHttpLlmClient llm) throws Exception {
        AgentToolContext.setConversationId("cid-census");
        AgentToolContext.setParlerStreamIds("rid-census", "remote");
        AgentToolContext.setParlerRunningCancelAgentThingNameForTests("AgentThing");
        llm.enqueue(textResponse("done"));
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L);
        loop.run(new ArrayList<>(List.of(ChatMessage.user("hi"))), stubTools());
    }

    private static void runEmptyFinalRetry(RecordingHttpLlmClient llm) throws Exception {
        AgentToolContext.setConversationId("cid-retry");
        AgentToolContext.setParlerStreamIds("rid-retry", "remote");
        AgentToolContext.setParlerRunningCancelAgentThingNameForTests("AgentThing");
        llm.enqueue(AnthropicMessagesApi.parseResponse(
                "{\"content\":[],\"stop_reason\":\"end_turn\","
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}",
                "anthropic-empty-rid"));
        llm.enqueue(textResponse("recovered"));
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-prefix"));
        messages.add(ChatMessage.user("answer me"));
        AgentLoop.AgentResult result = loop.run(messages, stubTools());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, result.getStatus());
        assertEquals("recovered", result.getContent());
    }

    private static void runCheckpoint(RecordingHttpLlmClient llm) {
        LlmCallContext parent = LlmCallRecorder.agentRoundContext(
                "rid-ckpt", "cid-ckpt", "AgentThing", 1, IDS, null);
        llm.enqueue(textResponse(CHECKPOINT_REPLY));
        ConversationCheckpointGenerator.Result result = ConversationCheckpointGenerator.generate(
                eligiblePlan(),
                llm,
                400_000,
                null,
                null,
                new ConversationCheckpoint.Source("cid-ckpt", "AgentThing", "amid-ckpt"),
                "openai",
                "gpt-4o",
                "2026-09-12T00:00:00Z",
                null,
                parent);
        assertTrue(result.isCreated(), String.valueOf(result.outcome()));
    }

    private static void runPlaybookSummaries(RecordingHttpLlmClient llm) throws Exception {
        runPlaybookSummary(llm, "one");
        runPlaybookSummary(llm, "two");
    }

    private static void runPlaybookSummary(RecordingHttpLlmClient llm, String reply) throws Exception {
        PlaybookDocument doc = PlaybookDocument.parse(MINI_PLAYBOOK);
        List<ToolDefinition> tools = List.of(new ToolDefinition("noop_pb", "n",
                Map.of("type", "object", "properties", Map.of()), true));
        assertTrue(PlaybookValidator.validateDocument(doc, tools).valid());
        llm.enqueue(textResponse(reply));
        PlaybookToolExecutor exec = (tool, jsonArgs, infotableArgs) -> PlaybookToolExecutionResult.jsonOnly(
                "{\"rowCount\":0}");
        PlaybookRunResult result = PlaybookRunner.run(
                doc,
                "census_mini",
                new JSONObject(),
                "goal",
                "junit-pb-" + UUID.randomUUID(),
                List.of(),
                exec,
                llm,
                0.0,
                100);
        assertEquals(PlaybookRunResult.Status.COMPLETED, result.status());
    }

    private static void runProviderHealthCheck(AtomicInteger httpExecutes) throws Exception {
        RecordingHttpLlmClient delegate = new RecordingHttpLlmClient(httpExecutes, IDS);
        delegate.enqueue(textResponse("OK"));
        TestBridgeContext context = new TestBridgeContext(TestBridgeContext.disabledRateConfig());
        ProviderLlmClientBridge bridge = new ProviderLlmClientBridge(context, delegate, "gpt-4o");
        assertTrue(bridge.healthCheck());
    }

    private static ConversationCompactionBoundarySelector.BoundaryPlan eligiblePlan() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("u".repeat(1_000)));
        messages.add(ChatMessage.assistant("a".repeat(1_000)));
        messages.add(ChatMessage.user("v".repeat(1_000)));
        messages.add(ChatMessage.assistant("b".repeat(1_000)));
        messages.add(ChatMessage.user("last"));
        messages.add(ChatMessage.assistant("final"));
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(messages, 3_000, true, "cid-ckpt");
        assertTrue(plan.isCheckpointEligible());
        return plan;
    }

    private static List<ToolDefinition> stubTools() {
        return List.of(new ToolDefinition("noop", "n", Map.of("type", "object", "properties", Map.of()), true));
    }

    private static LlmResponse textResponse(String text) {
        return new LlmResponse(text, Collections.emptyList(), LlmResponse.FinishReason.STOP,
                1, 2, 1, 2, 0, 0, 0, "rid", 0L);
    }

    private static LlmResponse toolCallsResponse(List<ToolCall> calls) {
        return new LlmResponse("", calls, LlmResponse.FinishReason.TOOL_CALLS,
                3, 4, 3, 4, 0, 0, 0, "rid2", 0L);
    }

    private static final class RecordingHttpLlmClient implements LlmClient {
        private final AtomicInteger httpExecutes;
        private final LlmUsageWireIds ids;
        private final Queue<LlmResponse> scripted = new ArrayDeque<>();
        private Runnable beforeChatReturn;

        RecordingHttpLlmClient(AtomicInteger httpExecutes, LlmUsageWireIds ids) {
            this.httpExecutes = httpExecutes;
            this.ids = ids;
        }

        void enqueue(LlmResponse response) {
            scripted.add(response);
        }

        void setBeforeChatReturn(Runnable hook) {
            this.beforeChatReturn = hook;
        }

        @Override
        public LlmResponse chat(LlmChatRequest request) throws Exception {
            LlmResponse scriptedResponse = scripted.poll();
            if (scriptedResponse == null) {
                throw new IllegalStateException("no scripted response");
            }
            String body = "{\"choices\":[{\"message\":{\"content\":"
                    + JSONObject.quote(scriptedResponse.getContent() != null ? scriptedResponse.getContent() : "ok")
                    + "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
            LlmUsageWireIds wireIds = request.getUsageWireIdsOverride() != null
                    ? request.getUsageWireIdsOverride()
                    : ids;
            LlmResponse recorded = LlmRecordedHttpChat.executeChatCompletions(
                    request,
                    wireIds,
                    "OpenAI",
                    "census",
                    30_000,
                    () -> {
                        httpExecutes.incrementAndGet();
                        return new LlmRecordedHttpChat.HttpResult(200, body, new Header[0]);
                    });
            if (beforeChatReturn != null) {
                beforeChatReturn.run();
            }
            return new LlmResponse(
                    scriptedResponse.getContent(),
                    scriptedResponse.getToolCalls(),
                    scriptedResponse.getFinishReason(),
                    recorded.getPromptTokens(),
                    recorded.getCompletionTokens(),
                    recorded.getInputTokens(),
                    recorded.getOutputTokens(),
                    recorded.getCacheReadInputTokens(),
                    recorded.getCacheCreationInputTokens(),
                    recorded.getCachedPromptTokens(),
                    recorded.getProviderRequestId(),
                    recorded.getRateGateAdmissionWaitMs());
        }

        @Override
        public LlmUsageWireIds usageWireIds() {
            return ids;
        }

        @Override
        public LlmUsageWireIds usageWireIdsForEffectiveModel(String effectiveModel) {
            return ids.withModel(effectiveModel != null && !effectiveModel.isBlank() ? effectiveModel : ids.getModel());
        }

        @Override
        public boolean healthCheck() {
            return true;
        }
    }
}
