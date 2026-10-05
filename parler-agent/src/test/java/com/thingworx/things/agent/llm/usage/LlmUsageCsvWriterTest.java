package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

class LlmUsageCsvWriterTest {

    @AfterEach
    void cleanup() {
        AgentLlmCallStreamReader.clearTestHooks();
        LlmCallRecorder.resetForTest();
    }

    @Test
    void write_includesTokenColumnsAndRawUsageJson() throws Exception {
        Instant now = Instant.parse("2026-09-12T10:00:00Z");
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallRecorder.resetForTest();
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(ids, new LlmUsageSnapshot.Builder()
                .status(LlmUsageSnapshot.UsageStatus.COMPLETE)
                .putNormalized("inputTokensTotal", 100L, LlmUsageSnapshot.FieldPresence.REPORTED)
                .putNormalized("outputTokensTotal", 20L, LlmUsageSnapshot.FieldPresence.REPORTED)
                .build(), null, 200, "req", "resp", "gpt-4o", 5L);

        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[]}"));
        String csv = LlmUsageCsvWriter.write(report);
        assertTrue(csv.contains("openai/prompt_tokens"));
        assertTrue(csv.contains("openai/completion_tokens"));
        assertTrue(csv.contains("anthropic/input_tokens"));
        assertTrue(csv.contains("rawUsageJson"));
        assertTrue(csv.contains("priceVersion"));
        assertTrue(csv.contains("responseModel"));
    }

    @Test
    void write_invalidUsageStatus_stillProducesCsvWithDiagnostics() throws Exception {
        Instant now = Instant.parse("2026-09-12T10:00:00Z");
        LlmCallEvent event = LlmCallEvent.builder(LlmCallEventType.CALL_FINISHED)
                .eventId(LlmCallEvent.newId())
                .callId("call-invalid")
                .logicalCallId("logical-invalid")
                .conversationId("cid")
                .agentThing("AgentThing")
                .requestedModel("gpt-4o")
                .providerFamily("openai")
                .callKind(LlmCallKind.AGENT_ROUND)
                .callStartedAt(now)
                .occurredAt(now)
                .eventJson("{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-invalid\"},"
                        + "\"outcome\":\"success\",\"usage\":{\"status\":\"invalid\",\"revision\":0,"
                        + "\"captureError\":\"usage_type_string\",\"rawUsage\":\"n/a\"}}")
                .build();
        AgentLlmCallStreamReader.setTestEvents(List.of(event));
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[]}"));
        String csv = LlmUsageCsvWriter.write(report);
        assertTrue(csv.contains("invalid"));
        assertTrue(csv.contains("rawUsageJson"));
        assertTrue(csv.contains("logical-invalid"));
    }

    @Test
    void write_collidingRawKeys_exportsDistinctPointerColumns() throws Exception {
        Instant now = Instant.parse("2026-09-12T10:00:00Z");
        LlmCallEvent event = LlmCallEvent.builder(LlmCallEventType.CALL_FINISHED)
                .eventId(LlmCallEvent.newId())
                .callId("call-collide")
                .logicalCallId("logical-collide")
                .conversationId("cid")
                .agentThing("AgentThing")
                .requestedModel("gpt-4o")
                .providerFamily("openai")
                .callKind(LlmCallKind.AGENT_ROUND)
                .callStartedAt(now)
                .occurredAt(now)
                .eventJson("{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-collide\"},"
                        + "\"usage\":{\"status\":\"complete\",\"revision\":0,"
                        + "\"rawUsage\":{\"a/b\":5,\"a\":{\"b\":7}}}}")
                .build();
        AgentLlmCallStreamReader.setTestEvents(List.of(event));
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[]}"));
        String csv = LlmUsageCsvWriter.write(report);
        assertTrue(csv.contains("a~1b"));
        assertTrue(csv.contains("a/b"));
    }
}
