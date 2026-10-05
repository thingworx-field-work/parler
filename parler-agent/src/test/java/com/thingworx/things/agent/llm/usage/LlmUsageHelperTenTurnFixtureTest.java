package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

/**
 * M0c: ten-turn fake call ledger reduced through helper report path with independent expectations.
 */
class LlmUsageHelperTenTurnFixtureTest {

    private static final int EXPECTED_AGENT_ROUNDS = 10;
    private static final int EXPECTED_CHECKPOINT_CALLS = 1;
    private static final int EXPECTED_NOT_SENT = 1;
    private static final long EXPECTED_INPUT_TOTAL_KNOWN_SUM = 10L * 100L + 50L;

    @BeforeEach
    void installTestSink() {
        LlmCallRecorder.resetForTest();
    }

    @AfterEach
    void cleanup() {
        AgentLlmCallStreamReader.clearTestHooks();
        LlmCallRecorder.resetForTest();
    }

    @Test
    void tenTurnFixture_matchesIndependentCallAndUsageExpectations() throws Exception {
        Instant base = Instant.now().minusSeconds(30);
        List<LlmCallEvent> events = new ArrayList<>();
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");

        for (int round = 1; round <= EXPECTED_AGENT_ROUNDS; round++) {
            Instant started = base.plusSeconds(round);
            LlmCallContext context = LlmCallRecorder.agentRoundContext(
                    "rid-fixture", "cid-fixture", "AgentThing", round, ids, null);
            LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
            attempt.markDispatched();
            attempt.finishSuccess(ids, usage(100, 20, 10), new LlmResponse("ok", List.of(),
                    LlmResponse.FinishReason.STOP, 100, 20), 200, "req-" + round, "resp-" + round, "gpt-4o", 5L);
            events.addAll(AgentLlmCallStreamWriter.testCapture());
            AgentLlmCallStreamWriter.clearTestHooks();
            AgentLlmCallStreamWriter.setTestSink(event -> { });
        }

        LlmCallContext parent = LlmCallRecorder.agentRoundContext(
                "rid-fixture", "cid-fixture", "AgentThing", 1, ids, null);
        LlmCallContext checkpoint = LlmCallRecorder.subCallContext(LlmCallKind.CHECKPOINT, parent, ids);
        LlmCallRecorder.LlmCallAttempt checkpointAttempt = LlmCallRecorder.begin(checkpoint);
        checkpointAttempt.markDispatched();
        checkpointAttempt.finishSuccess(ids, usage(50, 10, 0), null, 200, "req-ckpt", "resp-ckpt", "gpt-4o", 3L);
        events.addAll(AgentLlmCallStreamWriter.testCapture());
        AgentLlmCallStreamWriter.clearTestHooks();
        AgentLlmCallStreamWriter.setTestSink(event -> { });

        LlmCallRecorder.recordPreRoundCancelCandidate("rid-fixture", "cid-fixture", "AgentThing");
        events.addAll(AgentLlmCallStreamWriter.testCapture());

        AgentLlmCallStreamReader.setTestEvents(events);
        LlmUsageReportQuery query = new LlmUsageReportQuery(
                base.minusSeconds(60), base.plusSeconds(3600), null, null, null);
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, Instant.now().plusSeconds(3600)),
                Instant.now().plusSeconds(3600),
                new LlmUsageCostCalculator("{\"version\":\"fixture-v1\",\"prices\":[{\"providerFamily\":\"openai\","
                        + "\"requestedModel\":\"gpt-4o\",\"inputPerMillionUsd\":1,\"outputPerMillionUsd\":1,"
                        + "\"cacheReadPerMillionUsd\":0.5}]}"));

        assertEquals(EXPECTED_AGENT_ROUNDS + EXPECTED_CHECKPOINT_CALLS + EXPECTED_NOT_SENT,
                report.path("callCount").asInt());
        assertEquals(EXPECTED_AGENT_ROUNDS + EXPECTED_CHECKPOINT_CALLS, report.path("successCount").asInt());
        assertEquals(EXPECTED_NOT_SENT, report.path("notSentCount").asInt());
        assertEquals(EXPECTED_INPUT_TOTAL_KNOWN_SUM, report.path("knownSum").path("inputTokensTotal").asLong());
        assertTrue(report.path("knownCostUsd").asDouble() > 0.0);
        String csv = LlmUsageCsvWriter.write(report);
        assertTrue(csv.startsWith("callId,logicalCallId"));
        assertEquals(EXPECTED_AGENT_ROUNDS + EXPECTED_CHECKPOINT_CALLS + EXPECTED_NOT_SENT + 1,
                csv.lines().count());
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
