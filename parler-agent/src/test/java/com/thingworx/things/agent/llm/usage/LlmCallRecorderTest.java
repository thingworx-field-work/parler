package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.LlmUsageWireIds;

class LlmCallRecorderTest {

    @BeforeEach
    void installTestSink() {
        LlmCallRecorder.resetForTest();
    }

    @AfterEach
    void cleanup() {
        LlmCallRecorder.resetForTest();
    }

    @Test
    void lifecycle_emitsStartedDispatchedFinished_inOrder() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext(
                "rid-1", "cid-1", "AgentThing", 1, ids, new LlmCallContextPlanSnapshot(null));
        try (LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context)) {
            attempt.markDispatched();
            attempt.finishSuccess(ids, LlmUsageSnapshot.unavailable(), null, 200, "req-1", "resp-1", "gpt-4o", 12L);
        }
        List<LlmCallEventType> types = AgentLlmCallStreamWriter.testCapture().stream()
                .map(LlmCallEvent::getEventType)
                .collect(Collectors.toList());
        assertTrue(types.contains(LlmCallEventType.COLLECTOR_STARTED));
        assertEquals(LlmCallEventType.CALL_STARTED, types.get(types.indexOf(LlmCallEventType.CALL_STARTED)));
        assertTrue(types.contains(LlmCallEventType.CALL_DISPATCHED));
        assertTrue(types.contains(LlmCallEventType.CALL_FINISHED));
    }

    @Test
    void writeFailure_doesNotRecurse_incrementsGap_llmPathContinues() {
        AgentLlmCallStreamWriter.setTestForcedResult(AgentLlmCallStreamWriter.WriteResult.FAILED);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext(
                "rid-gap", "cid-gap", "AgentThing", 1, ids, null);
        try (LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context)) {
            attempt.markDispatched();
            attempt.finishSuccess(ids, LlmUsageSnapshot.unavailable(), null, 200, "req-gap", "resp-gap", "gpt-4o", 5L);
        }
        assertTrue(LlmCallRecorder.knownWriteGapCount() > 0);
        assertEquals(0, AgentLlmCallStreamWriter.testCapture().size());
    }

    @Test
    void gapWriteFails_thenNextSuccess_emitsCollectorGapWithPendingCount() {
        AtomicInteger gapAttempts = new AtomicInteger();
        AtomicInteger finishedAttempts = new AtomicInteger();
        AgentLlmCallStreamWriter.setTestFailPredicate(event -> {
            if (event.getEventType() == LlmCallEventType.COLLECTOR_GAP) {
                return gapAttempts.incrementAndGet() == 1;
            }
            if (event.getEventType() == LlmCallEventType.CALL_FINISHED) {
                return finishedAttempts.incrementAndGet() == 1;
            }
            return false;
        });
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext(
                "rid-gap-recover", "cid-gap-recover", "AgentThing", 1, ids, null);
        try (LlmCallRecorder.LlmCallAttempt first = LlmCallRecorder.begin(context)) {
            first.markDispatched();
            first.finishSuccess(ids, LlmUsageSnapshot.unavailable(), null, 200, "req-1", "resp-1", "gpt-4o", 5L);
        }
        assertEquals(0, AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.COLLECTOR_GAP)
                .count());
        try (LlmCallRecorder.LlmCallAttempt second = LlmCallRecorder.begin(context)) {
            second.markDispatched();
            second.finishSuccess(ids, LlmUsageSnapshot.unavailable(), null, 200, "req-2", "resp-2", "gpt-4o", 6L);
        }
        List<LlmCallEvent> gaps = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.COLLECTOR_GAP)
                .collect(Collectors.toList());
        assertEquals(1, gaps.size());
        assertTrue(gaps.get(0).getEventJson().contains("\"failedEvents\":1"));
    }

    @Test
    void ensureAttempt_differentContextAfterDispatch_doesNotFinishNotSent() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext firstContext = LlmCallRecorder.agentRoundContext(
                "rid-a", "cid-a", "AgentThing", 1, ids, null);
        LlmCallContext secondContext = LlmCallRecorder.agentRoundContext(
                "rid-b", "cid-a", "AgentThing", 1, ids, null);
        LlmCallRecorder.LlmCallAttempt first = LlmCallRecorder.begin(firstContext);
        first.markDispatched();
        LlmCallRecorder.LlmCallAttempt second = LlmCallRecorder.ensureAttempt(secondContext);
        assertTrue(!first.getCallId().equals(second.getCallId()));
        second.finishNotSent();
        List<LlmCallEvent> finished = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_FINISHED
                        && e.getCallId().equals(first.getCallId()))
                .collect(Collectors.toList());
        assertEquals(0, finished.size());
    }

    @Test
    void preRoundCancelCandidate_recordsNotSent() {
        LlmCallRecorder.recordPreRoundCancelCandidate("rid-cancel", "cid-cancel", "AgentThing");
        List<LlmCallEvent> events = AgentLlmCallStreamWriter.testCapture();
        assertTrue(events.stream().anyMatch(e -> e.getEventType() == LlmCallEventType.CALL_CANCEL_REQUESTED));
        assertTrue(events.stream().anyMatch(e -> e.getEventType() == LlmCallEventType.CALL_FINISHED
                && e.getEventJson().contains("\"outcome\":\"not_sent\"")));
    }
}
