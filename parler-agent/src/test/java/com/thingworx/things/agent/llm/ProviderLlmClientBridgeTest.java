package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ratecontrol.LlmRateLimitAdmissionException;
import com.thingworx.things.agent.llm.ratecontrol.RateControlConfig;
import com.thingworx.things.agent.llm.ratecontrol.RateControlMode;
import com.thingworx.things.agent.llm.ratecontrol.TokenReserveStrategy;
import com.thingworx.things.agent.llm.usage.AgentLlmCallStreamWriter;
import com.thingworx.things.agent.llm.usage.LlmCallContext;
import com.thingworx.things.agent.llm.usage.LlmCallEvent;
import com.thingworx.things.agent.llm.usage.LlmCallEventType;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;
import com.thingworx.things.agent.llm.usage.LlmRecordedHttpChat;

/**
 * CC-7.7 census: bridge healthCheck request construction and local admission rejection.
 */
class ProviderLlmClientBridgeTest {

    private static final LlmUsageWireIds IDS =
            LlmUsageWireIds.forProviderThing("StubProvider", "TestBridgeProviderTemplate", "openai-chat-completions-v4", "gpt-4o");

    @BeforeEach
    void installSink() {
        LlmCallRecorder.resetForTest();
    }

    @AfterEach
    void cleanup() {
        LlmCallRecorder.resetForTest();
    }

    @Test
    void healthCheck_executesProbeThroughBridgeAndRecordsHttp() throws Exception {
        AtomicInteger httpExecutes = new AtomicInteger();
        RecordingHttpLlmClient delegate = new RecordingHttpLlmClient(httpExecutes);
        ProviderLlmClientBridge bridge = new ProviderLlmClientBridge(
                new TestBridgeContext(TestBridgeContext.disabledRateConfig()), delegate, "gpt-4o");

        assertTrue(bridge.healthCheck());
        assertEquals(1, httpExecutes.get());

        List<LlmCallEvent> dispatched = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_DISPATCHED)
                .toList();
        assertEquals(1, dispatched.size());
        assertTrue(dispatched.get(0).getEventJson().contains("\"attemptIndex\":1"));
    }

    @Test
    void chat_localAdmissionRejection_finishNotSentWithoutHttp() throws Exception {
        AtomicInteger httpExecutes = new AtomicInteger();
        RecordingHttpLlmClient delegate = new RecordingHttpLlmClient(httpExecutes);
        RateControlConfig rejectConfig = new RateControlConfig(
                RateControlMode.enforce, 1, 0, 0, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        ProviderLlmClientBridge bridge = new ProviderLlmClientBridge(new TestBridgeContext(rejectConfig), delegate, "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext(
                "rid-bridge", "cid-bridge", "AgentThing", 1, IDS, null);
        LlmChatRequest request = LlmChatRequest.copyWithCallContext(
                LlmChatRequest.forAgentRound(
                        List.of(ChatMessage.user("hello world")),
                        null,
                        0.0,
                        32,
                        null,
                        IDS),
                context);

        assertThrows(LlmRateLimitAdmissionException.class, () -> bridge.chat(request));
        assertEquals(0, httpExecutes.get());

        List<LlmCallEvent> finished = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_FINISHED)
                .toList();
        assertEquals(1, finished.size());
        assertTrue(finished.get(0).getEventJson().contains("\"outcome\":\"not_sent\""),
                finished.get(0).getEventJson());
    }

    @Test
    void contextPlanningInputCapChars_emitsNoRecorderEvents() {
        AtomicInteger httpExecutes = new AtomicInteger();
        RecordingHttpLlmClient delegate = new RecordingHttpLlmClient(httpExecutes);
        RateControlConfig planningConfig = new RateControlConfig(
                RateControlMode.enforce, 10_000, 0, 0, 0,
                TokenReserveStrategy.input_only, 1.0, 500, false);
        ProviderLlmClientBridge bridge = new ProviderLlmClientBridge(new TestBridgeContext(planningConfig), delegate, "gpt-4o");

        int before = AgentLlmCallStreamWriter.testCapture().size();
        bridge.contextPlanningInputCapChars(1024, null);
        assertEquals(before, AgentLlmCallStreamWriter.testCapture().size());
        assertEquals(0, httpExecutes.get());
    }

    private static final class RecordingHttpLlmClient implements LlmClient {
        private final AtomicInteger httpExecutes;

        RecordingHttpLlmClient(AtomicInteger httpExecutes) {
            this.httpExecutes = httpExecutes;
        }

        @Override
        public LlmResponse chat(LlmChatRequest request) throws Exception {
            String body = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
            LlmUsageWireIds wireIds = request.getUsageWireIdsOverride() != null
                    ? request.getUsageWireIdsOverride()
                    : IDS;
            return LlmRecordedHttpChat.executeChatCompletions(
                    request,
                    wireIds,
                    "OpenAI",
                    "bridge-test",
                    30_000,
                    () -> {
                        httpExecutes.incrementAndGet();
                        return new LlmRecordedHttpChat.HttpResult(200, body, new Header[0]);
                    });
        }

        @Override
        public LlmUsageWireIds usageWireIds() {
            return IDS;
        }

        @Override
        public LlmUsageWireIds usageWireIdsForEffectiveModel(String effectiveModel) {
            return IDS.withModel(effectiveModel != null && !effectiveModel.isBlank() ? effectiveModel : IDS.getModel());
        }

        @Override
        public boolean healthCheck() {
            return false;
        }
    }
}

