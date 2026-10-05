package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.LlmUsageWireIds;

/**
 * CC-7.7: same logicalCall with three attempts (two fail, one success) records retry chain.
 */
class LlmCallRecorderMultiAttemptTest {

    private static final LlmUsageWireIds IDS =
            LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");

    @BeforeEach
    void installSink() {
        LlmCallRecorder.resetForTest();
    }

    @AfterEach
    void cleanup() {
        LlmCallRecorder.resetForTest();
    }

    @Test
    void sameLogicalCall_threeAttempts_recordsAttemptIndexAndRetryChain() {
        String logicalCallId = LlmCallEvent.newId();
        LlmCallContext firstContext = LlmCallContext.builder(LlmCallKind.AGENT_ROUND, logicalCallId)
                .turnRequestId("rid-multi")
                .conversationId("cid-multi")
                .agentThing("AgentThing")
                .roundIndex(1)
                .attemptIndex(1)
                .wireIds(IDS)
                .build();

        LlmCallRecorder.LlmCallAttempt first = LlmCallRecorder.begin(firstContext);
        String firstCallId = first.getCallId();
        first.markDispatched();
        first.finishError(IDS, LlmUsageSnapshot.unavailable(), 503, "req-1", "resp-1", "gpt-4o",
                "http_503", "upstream", 12L);

        LlmCallContext secondContext = firstContext.toBuilder()
                .attemptIndex(2)
                .retryOfCallId(firstCallId)
                .build();
        LlmCallRecorder.LlmCallAttempt second = LlmCallRecorder.begin(secondContext);
        String secondCallId = second.getCallId();
        assertNotEquals(firstCallId, secondCallId);
        second.markDispatched();
        second.finishTimeout(IDS, "gpt-4o", 30_000L);

        LlmCallContext thirdContext = firstContext.toBuilder()
                .attemptIndex(3)
                .retryOfCallId(secondCallId)
                .build();
        LlmCallRecorder.LlmCallAttempt third = LlmCallRecorder.begin(thirdContext);
        assertNotEquals(secondCallId, third.getCallId());
        third.markDispatched();
        third.finishSuccess(IDS, usage(10, 2, 0), null, 200, "req-3", "resp-3", "gpt-4o", 8L);

        List<LlmCallEvent> finished = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_FINISHED)
                .collect(Collectors.toList());
        assertEquals(3, finished.size());
        assertTrue(finished.stream().allMatch(e -> logicalCallId.equals(e.getLogicalCallId())));
        assertTrue(finished.get(0).getEventJson().contains("\"attemptIndex\":1"));
        assertTrue(finished.get(1).getEventJson().contains("\"attemptIndex\":2"));
        assertTrue(finished.get(2).getEventJson().contains("\"attemptIndex\":3"));
        assertTrue(finished.get(1).getEventJson().contains("\"retryOfCallId\":\"" + firstCallId + "\""));
        assertTrue(finished.get(2).getEventJson().contains("\"retryOfCallId\":\"" + secondCallId + "\""));
    }

    private static LlmUsageSnapshot usage(long prompt, long completion, long cached) {
        return new LlmUsageSnapshot.Builder()
                .status(LlmUsageSnapshot.UsageStatus.COMPLETE)
                .putNormalized("inputTokensTotal", prompt, LlmUsageSnapshot.FieldPresence.REPORTED)
                .putNormalized("inputTokensUncached", prompt - cached, LlmUsageSnapshot.FieldPresence.DERIVED)
                .putNormalized("inputTokensCacheRead", cached, LlmUsageSnapshot.FieldPresence.REPORTED)
                .putNormalized("outputTokensTotal", completion, LlmUsageSnapshot.FieldPresence.REPORTED)
                .build();
    }
}
