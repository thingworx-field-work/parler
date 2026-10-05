package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

class LlmUsageReportReducerTest {

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
    void reduce_countsSuccessfulCall_andKnownCost() throws Exception {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(ids, usage(120, 40, 20), null, 200, "req", "resp", "gpt-4o", 10L);

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(
                now.minusSeconds(60),
                now.plusSeconds(60),
                "cid",
                "AgentThing",
                "gpt-4o");
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[{\"providerFamily\":\"openai\","
                        + "\"requestedModel\":\"gpt-4o\",\"inputPerMillionUsd\":1,\"outputPerMillionUsd\":2,"
                        + "\"cacheReadPerMillionUsd\":0.5}]}"));
        assertEquals(1, report.path("callCount").asInt());
        assertEquals(1, report.path("successCount").asInt());
        assertTrue(report.path("knownCostUsd").asDouble() > 0.0);
    }

    @Test
    void reduce_lateUnavailable_doesNotClearCompleteUsage() throws Exception {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(ids, usage(120, 40, 20), null, 200, "req", "resp", "gpt-4o", 10L);
        attempt.observeLateUsage(LlmUsageSnapshot.unavailable());

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[]}"));
        assertEquals("complete", report.withArray("calls").get(0).path("usageStatus").asText());
        assertEquals(120L, report.path("knownSum").path("inputTokensTotal").asLong());
    }

    @Test
    void reduce_conflictRows_keptInCallsArray() throws Exception {
        Instant started = Instant.parse("2026-09-12T10:00:00Z");
        String callId = "call-conflict";
        LlmCallEvent startedEvent = event(callId, 1, LlmCallEventType.CALL_STARTED, started,
                "logical-conflict",
                "{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-conflict\"}}");
        LlmCallEvent finishedA = event(callId, 2, LlmCallEventType.CALL_FINISHED, started,
                "logical-conflict",
                "{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-conflict\"},"
                        + "\"outcome\":\"success\",\"usage\":{\"status\":\"complete\",\"revision\":0,"
                        + "\"normalized\":{\"inputTokensTotal\":100}}}");
        LlmCallEvent finishedB = LlmCallEvent.builder(LlmCallEventType.CALL_FINISHED)
                .eventId(LlmCallEvent.newId())
                .callId(callId)
                .logicalCallId("logical-conflict")
                .callStartedAt(started)
                .occurredAt(started)
                .sequence(2)
                .eventJson("{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-conflict\"},"
                        + "\"outcome\":\"error\"}")
                .build();
        AgentLlmCallStreamReader.setTestEvents(List.of(startedEvent, finishedA, finishedB));
        LlmUsageReportQuery query = new LlmUsageReportQuery(started.minusSeconds(1), started.plusSeconds(60), null, null, null);
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, started.plusSeconds(3600)),
                started.plusSeconds(3600),
                new LlmUsageCostCalculator("{\"version\":\"unpriced-v1\",\"prices\":[]}"));
        assertEquals(1, report.withArray("calls").size());
        assertTrue(report.withArray("calls").get(0).path("conflict").asBoolean());
    }

    @Test
    void reduce_unavailableCall_incrementsUnknownCountForFixedColumns() throws Exception {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(ids, LlmUsageSnapshot.unavailable(), null, 200, "req", "resp", "gpt-4o", 10L);

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[]}"));
        assertEquals(1, report.path("unknownCount").path("inputTokensTotal").asInt());
        assertEquals(1, report.path("unknownCount").path("outputTokensTotal").asInt());
    }

    @Test
    void reduce_conflictingTerminalSnapshots_marksConflictAndUnpriced() throws Exception {
        Instant started = Instant.parse("2026-09-12T10:00:00Z");
        String callId = "call-terminal-conflict";
        LlmCallEvent startedEvent = event(callId, 1, LlmCallEventType.CALL_STARTED, started,
                "logical-terminal",
                "{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-terminal\"}}");
        LlmCallEvent finishedA = event(callId, 3, LlmCallEventType.CALL_FINISHED, started,
                "logical-terminal",
                "{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-terminal\"},"
                        + "\"outcome\":\"success\",\"dispatchState\":\"attempted\","
                        + "\"usage\":{\"status\":\"complete\",\"revision\":0,"
                        + "\"normalized\":{\"inputTokensTotal\":100,\"outputTokensTotal\":20}}}");
        LlmCallEvent finishedB = LlmCallEvent.builder(LlmCallEventType.CALL_FINISHED)
                .eventId(LlmCallEvent.newId())
                .callId(callId)
                .logicalCallId("logical-terminal")
                .conversationId("cid")
                .agentThing("AgentThing")
                .requestedModel("gpt-4o")
                .providerFamily("openai")
                .callKind(LlmCallKind.AGENT_ROUND)
                .callStartedAt(started)
                .occurredAt(started)
                .sequence(4)
                .eventJson("{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-terminal\"},"
                        + "\"outcome\":\"error\",\"dispatchState\":\"attempted\"}")
                .build();
        AgentLlmCallStreamReader.setTestEvents(List.of(startedEvent, finishedA, finishedB));
        LlmUsageReportQuery query = new LlmUsageReportQuery(started.minusSeconds(1), started.plusSeconds(60), null, null, null);
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, started.plusSeconds(3600)),
                started.plusSeconds(3600),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[{\"providerFamily\":\"openai\","
                        + "\"requestedModel\":\"gpt-4o\",\"inputPerMillionUsd\":1,\"outputPerMillionUsd\":2,"
                        + "\"currency\":\"USD\"}]}"));
        assertTrue(report.withArray("calls").get(0).path("conflict").asBoolean());
        assertEquals("unpriced", report.withArray("calls").get(0).path("costStatus").asText());
    }

    @Test
    void reduce_startedOnlyAttempt_countsUnknownDispatch() throws Exception {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallRecorder.begin(context);

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[]}"));
        assertEquals(1, report.path("callCount").asInt());
        assertEquals(1, report.path("unfinishedCount").asInt());
        assertEquals(1, report.path("unknownDispatchCount").asInt());
        assertEquals(0, report.path("notSentCount").asInt());
    }

    @Test
    void reduce_conflictingRawUsage_marksConflictAndUnpriced() throws Exception {
        Instant started = Instant.parse("2026-09-12T10:00:00Z");
        String callId = "call-raw-conflict";
        LlmCallEvent startedEvent = event(callId, 1, LlmCallEventType.CALL_STARTED, started,
                "logical-raw",
                "{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-raw\"}}");
        LlmCallEvent finishedA = event(callId, 3, LlmCallEventType.CALL_FINISHED, started,
                "logical-raw",
                "{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-raw\"},"
                        + "\"outcome\":\"success\",\"dispatchState\":\"attempted\","
                        + "\"usage\":{\"status\":\"complete\",\"revision\":0,"
                        + "\"normalized\":{\"inputTokensTotal\":100,\"outputTokensTotal\":20},"
                        + "\"rawUsage\":{\"prompt_tokens\":100,\"completion_tokens\":20,"
                        + "\"prompt_tokens_details\":{\"audio_tokens\":0}}}}");
        LlmCallEvent finishedB = LlmCallEvent.builder(LlmCallEventType.CALL_FINISHED)
                .eventId(LlmCallEvent.newId())
                .callId(callId)
                .logicalCallId("logical-raw")
                .conversationId("cid")
                .agentThing("AgentThing")
                .requestedModel("gpt-4o")
                .providerFamily("openai")
                .callKind(LlmCallKind.AGENT_ROUND)
                .callStartedAt(started)
                .occurredAt(started)
                .sequence(4)
                .eventJson("{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-raw\"},"
                        + "\"outcome\":\"success\",\"dispatchState\":\"attempted\","
                        + "\"usage\":{\"status\":\"complete\",\"revision\":1,"
                        + "\"normalized\":{\"inputTokensTotal\":100,\"outputTokensTotal\":20},"
                        + "\"rawUsage\":{\"prompt_tokens\":100,\"completion_tokens\":20,"
                        + "\"prompt_tokens_details\":{\"audio_tokens\":50}}}}")
                .build();
        AgentLlmCallStreamReader.setTestEvents(List.of(startedEvent, finishedA, finishedB));
        LlmUsageReportQuery query = new LlmUsageReportQuery(started.minusSeconds(1), started.plusSeconds(60), null, null, null);
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, started.plusSeconds(3600)),
                started.plusSeconds(3600),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[{\"providerFamily\":\"openai\","
                        + "\"requestedModel\":\"gpt-4o\",\"inputPerMillionUsd\":1,\"outputPerMillionUsd\":2,"
                        + "\"currency\":\"USD\"}]}"));
        assertTrue(report.withArray("calls").get(0).path("conflict").asBoolean());
        assertEquals("unpriced", report.withArray("calls").get(0).path("costStatus").asText());
    }

    @Test
    void reduce_unknownSchemaVersion_failsClosed() {
        Instant started = Instant.parse("2026-09-12T10:00:00Z");
        LlmCallEvent bad = LlmCallEvent.builder(LlmCallEventType.CALL_STARTED)
                .eventId(LlmCallEvent.newId())
                .callId("call-bad")
                .logicalCallId("logical-bad")
                .callStartedAt(started)
                .occurredAt(started)
                .eventJson("{")
                .build();
        AgentLlmCallStreamReader.setTestEvents(java.util.List.of(bad));
        LlmUsageReportQuery query = new LlmUsageReportQuery(started.minusSeconds(1), started.plusSeconds(60), null, null, null);
        assertThrows(LlmUsageReportException.class, () -> LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, started.plusSeconds(3600)),
                started.plusSeconds(3600),
                new LlmUsageCostCalculator("{\"version\":\"unpriced-v1\",\"prices\":[]}")));
    }

    @Test
    void reduce_invalidUsage_excludedFromKnownSumAndCountedSeparately() throws Exception {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(ids, new LlmUsageSnapshot.Builder()
                .status(LlmUsageSnapshot.UsageStatus.INVALID)
                .captureError("usage_invalid_counter")
                .putNormalized("inputTokensTotal", 100L, LlmUsageSnapshot.FieldPresence.REPORTED)
                .putNormalized("outputTokensTotal", 20L, LlmUsageSnapshot.FieldPresence.REPORTED)
                .build(), null, 200, "req", "resp", "gpt-4o", 10L);

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[{\"providerFamily\":\"openai\","
                        + "\"requestedModel\":\"gpt-4o\",\"inputPerMillionUsd\":1,\"outputPerMillionUsd\":2}]}"));
        assertEquals(1, report.path("usageInvalidCount").asInt());
        assertEquals(1, report.path("unknownCount").path("inputTokensTotal").asInt());
        assertEquals(1, report.path("unknownCount").path("outputTokensTotal").asInt());
        assertEquals(0, report.path("knownSum").path("inputTokensTotal").asInt());
        assertEquals(0, report.path("knownSum").path("outputTokensTotal").asInt());
        assertEquals("unpriced", report.withArray("calls").get(0).path("costStatus").asText());
        assertEquals(0.0, report.path("knownCostUsd").asDouble(), 0.0001);
    }

    @Test
    void reduce_partialUsage_preservesKnownFieldsInKnownSum() throws Exception {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(ids, new LlmUsageSnapshot.Builder()
                .status(LlmUsageSnapshot.UsageStatus.PARTIAL)
                .captureError("usage_incomplete")
                .putNormalized("inputTokensTotal", 10L, LlmUsageSnapshot.FieldPresence.REPORTED)
                .build(), null, 200, "req", "resp", "gpt-4o", 10L);

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[]}"));
        assertEquals(1, report.path("usagePartialCount").asInt());
        assertEquals(10L, report.path("knownSum").path("inputTokensTotal").asLong());
        assertEquals(1, report.path("unknownCount").path("outputTokensTotal").asInt());
    }

    @Test
    void reduce_completeExplicitZero_inKnownSumNotUnknown() throws Exception {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(ids, new LlmUsageSnapshot.Builder()
                .status(LlmUsageSnapshot.UsageStatus.COMPLETE)
                .putNormalized("inputTokensTotal", 0L, LlmUsageSnapshot.FieldPresence.REPORTED)
                .putNormalized("outputTokensTotal", 0L, LlmUsageSnapshot.FieldPresence.REPORTED)
                .build(), null, 200, "req", "resp", "gpt-4o", 10L);

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[]}"));
        assertEquals(0L, report.path("knownSum").path("inputTokensTotal").asLong());
        assertEquals(0, report.path("unknownCount").path("inputTokensTotal").asInt());
    }

    @Test
    void reduce_anthropicZeroCacheCreateWithContradictoryTtl_excludedFromKnownSumAndCost() throws Exception {
        String body = "{\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":0,"
                + "\"cache_creation_input_tokens\":0,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":100,\"ephemeral_1h_input_tokens\":0}}}";
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing(
                "P", "Anthropic", "anthropic-messages-v1", "claude-sonnet-4-20250514");
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, ids);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus());

        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(ids, snapshot, null, 200, "req", "resp", "claude-sonnet-4-20250514", 10L);

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[{\"providerFamily\":\"anthropic\","
                        + "\"requestedModel\":\"claude-sonnet-4-20250514\",\"inputPerMillionUsd\":1,"
                        + "\"outputPerMillionUsd\":2,\"cacheWrite5mPerMillionUsd\":10}]}"));
        assertEquals(1, report.path("usageInvalidCount").asInt());
        assertEquals(0, report.path("knownSum").path("cacheWrite5mTokens").asInt());
        assertEquals(0, report.path("knownSum").path("inputTokensCacheWrite").asInt());
        assertEquals("unpriced", report.withArray("calls").get(0).path("costStatus").asText());
        assertEquals(0.0, report.path("knownCostUsd").asDouble(), 0.0001);
    }

    @Test
    void reduce_anthropicMatchingCacheCreateTtl_inKnownSumAndKnownCost() throws Exception {
        String body = "{\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":0,"
                + "\"cache_creation_input_tokens\":100,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":100,\"ephemeral_1h_input_tokens\":0}}}";
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing(
                "P", "Anthropic", "anthropic-messages-v1", "claude-sonnet-4-20250514");
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, ids);
        assertEquals(LlmUsageSnapshot.UsageStatus.COMPLETE, snapshot.getStatus());

        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(ids, snapshot, null, 200, "req", "resp", "claude-sonnet-4-20250514", 10L);

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"test-v1\",\"prices\":[{\"providerFamily\":\"anthropic\","
                        + "\"requestedModel\":\"claude-sonnet-4-20250514\",\"inputPerMillionUsd\":1,"
                        + "\"outputPerMillionUsd\":2,\"cacheWrite5mPerMillionUsd\":10}]}"));
        assertEquals(1, report.path("usageCompleteCount").asInt());
        assertEquals(100L, report.path("knownSum").path("cacheWrite5mTokens").asLong());
        assertEquals(100L, report.path("knownSum").path("inputTokensCacheWrite").asLong());
        assertEquals("known", report.withArray("calls").get(0).path("costStatus").asText());
        assertTrue(report.path("knownCostUsd").asDouble() > 0.0);
    }

    @Test
    void reduce_latencyCheckpointWithEmptyPriceTable_countsUnpricedNotCostUnsupported() throws Exception {
        LlmUsageSnapshot snapshot = AzureLatencyUsageFixtures.capture(1198, 1050, 2248);
        LlmUsageWireIds ids = AzureLatencyUsageFixtures.AZURE_IDS;
        assertEquals(LlmUsageSnapshot.UsageStatus.COMPLETE, snapshot.getStatus());

        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(ids, snapshot, null, 200, "req", "resp", "gpt-5.4-2026-03-05", 10L);

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator("{\"version\":\"unpriced-v1\",\"prices\":[]}"));
        assertEquals(1, report.path("unpricedCallCount").asInt());
        assertEquals(0, report.path("costUnsupportedCount").asInt());
    }

    private static LlmCallEvent event(
            String callId,
            int sequence,
            LlmCallEventType type,
            Instant started,
            String logicalCallId,
            String eventJson) {
        return LlmCallEvent.builder(type)
                .eventId(LlmCallEvent.newId())
                .callId(callId)
                .logicalCallId(logicalCallId)
                .conversationId("cid")
                .agentThing("AgentThing")
                .requestedModel("gpt-4o")
                .providerFamily("openai")
                .callKind(LlmCallKind.AGENT_ROUND)
                .sequence(sequence)
                .callStartedAt(started)
                .occurredAt(started)
                .eventJson(eventJson)
                .build();
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
