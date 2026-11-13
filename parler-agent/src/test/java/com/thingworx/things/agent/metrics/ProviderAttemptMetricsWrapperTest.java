package com.thingworx.things.agent.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;

import org.apache.http.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmHttpFailureException;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.usage.AgentLlmCallStreamWriter;
import com.thingworx.things.agent.llm.usage.LlmCallContext;
import com.thingworx.things.agent.llm.usage.LlmCallKind;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;
import com.thingworx.things.agent.llm.usage.LlmRecordedHttpChat;

/**
 * Exercises the production {@link LlmRecordedHttpChat} wrapper with a controlled executor and a fake metrics sink
 * (design: Verification check 2). Offline precedent: {@code AgentLoopCallSiteCensusTest} / the recorder test hooks.
 */
class ProviderAttemptMetricsWrapperTest {

    private static final int TIMEOUT_MS = 30_000;
    private static final LlmUsageWireIds OPENAI =
            LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
    private static final LlmUsageWireIds AZURE =
            LlmUsageWireIds.forProviderThing("P", "Azure", "azure-openai-chat-completions-v4", "gpt-4o");
    private static final LlmUsageWireIds ANTHROPIC =
            LlmUsageWireIds.forProviderThing("P", "Anthropic", "anthropic-messages-v1", "claude-sonnet-4-20250514");

    private static final String OPENAI_OK = "{\"id\":\"r1\",\"model\":\"gpt-4o\","
            + "\"choices\":[{\"message\":{\"content\":\"hello\"}}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    private static final String OPENAI_NO_USAGE = "{\"id\":\"r2\",\"model\":\"gpt-4o\","
            + "\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";
    private static final String OPENAI_PARTIAL = "{\"id\":\"r3\",\"model\":\"gpt-4o\","
            + "\"choices\":[{\"message\":{\"content\":\"hello\"}}],\"usage\":{\"prompt_tokens\":7}}";
    private static final String OPENAI_INVALID = "{\"id\":\"r4\",\"model\":\"gpt-4o\","
            + "\"choices\":[{\"message\":{\"content\":\"hello\"}}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":99}}";
    private static final String OPENAI_HTTP_ERROR_WITH_USAGE = "{\"id\":\"r5\",\"model\":\"gpt-4o\","
            + "\"error\":{\"message\":\"overloaded\"},"
            + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}";
    private static final String ANTHROPIC_DERIVED = "{\"id\":\"a1\",\"model\":\"claude-sonnet-4-20250514\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
            + "\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":20,"
            + "\"cache_creation_input_tokens\":5,\"output_tokens\":3}}";
    private static final String ANTHROPIC_NO_CACHE_FIELDS = "{\"id\":\"a2\",\"model\":\"claude-sonnet-4-20250514\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":3}}";

    private RecordingMetricsSink sink;

    @BeforeEach
    void setUp() {
        LlmCallRecorder.resetForTest();
        sink = new RecordingMetricsSink();
        ParlerMetrics.installSinkForTest(sink);
    }

    @AfterEach
    void tearDown() {
        ParlerMetrics.resetForTest();
        LlmCallRecorder.resetForTest();
    }

    private static LlmChatRequest request(LlmUsageWireIds ids, LlmCallKind kind) {
        LlmCallContext round = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        LlmCallContext ctx = kind == LlmCallKind.AGENT_ROUND ? round : LlmCallRecorder.subCallContext(kind, round, ids);
        return LlmChatRequest.copyWithCallContext(
                LlmChatRequest.forAgentRound(List.of(ChatMessage.user("hi")), null, 0.0, 32, null, ids), ctx);
    }

    private static LlmResponse chat(LlmUsageWireIds ids, LlmCallKind kind, int status, String body) throws Exception {
        return LlmRecordedHttpChat.executeChatCompletions(request(ids, kind), ids, "OpenAI", "metrics-test",
                TIMEOUT_MS, () -> new LlmRecordedHttpChat.HttpResult(status, body, new Header[0]));
    }

    private static LlmResponse chatThrowing(LlmUsageWireIds ids, Exception e) throws Exception {
        return LlmRecordedHttpChat.executeChatCompletions(request(ids, LlmCallKind.AGENT_ROUND), ids, "OpenAI",
                "metrics-test", TIMEOUT_MS, () -> {
                    throw e;
                });
    }

    private static LlmResponse anthropic(String body) throws Exception {
        return LlmRecordedHttpChat.executeAnthropicMessages(request(ANTHROPIC, LlmCallKind.AGENT_ROUND), ANTHROPIC,
                "messages", TIMEOUT_MS, () -> new LlmRecordedHttpChat.HttpResult(200, body, new Header[0]));
    }

    @Test
    void successRecordsOneAttemptOneUsageSampleAndEachReportedTokenKind() throws Exception {
        chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, OPENAI_OK);
        assertEquals(List.of(
                "llm|openai|agent_round|success",
                "usage|openai|agent_round|complete",
                "tokens|openai|agent_round|input|10",
                "tokens|openai|agent_round|output|5",
                "tokens|openai|agent_round|total|15"), sink.events);
    }

    @Test
    void azureIsSeparateFromOpenAiAndCallKindIsCarried() throws Exception {
        chat(AZURE, LlmCallKind.CHECKPOINT, 200, OPENAI_OK);
        assertEquals("llm|azure_openai|checkpoint|success", sink.events.get(0));
    }

    @Test
    void retryOrFallbackRecordsEachDispatchedAttempt() throws Exception {
        assertThrows(LlmHttpFailureException.class,
                () -> chat(OPENAI, LlmCallKind.AGENT_ROUND, 503, OPENAI_HTTP_ERROR_WITH_USAGE));
        chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, OPENAI_OK);
        assertEquals(List.of("llm|openai|agent_round|http_error", "llm|openai|agent_round|success"),
                sink.withPrefix("llm|"));
        assertEquals(2, sink.withPrefix("usage|").size());
    }

    @Test
    void nonSuccessStatusIsHttpErrorWithoutDuplicateOnUnwindAndKeepsValidUsage() throws Exception {
        assertThrows(LlmHttpFailureException.class,
                () -> chat(OPENAI, LlmCallKind.AGENT_ROUND, 429, OPENAI_HTTP_ERROR_WITH_USAGE));
        assertEquals(List.of(
                "llm|openai|agent_round|http_error",
                "usage|openai|agent_round|complete",
                "tokens|openai|agent_round|input|4",
                "tokens|openai|agent_round|output|2",
                "tokens|openai|agent_round|total|6"), sink.events);
    }

    @Test
    void malformedResponseIsParseErrorWithoutDuplicate() {
        assertThrows(Exception.class, () -> chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, "{not json"));
        assertEquals(List.of("llm|openai|agent_round|parse_error"), sink.withPrefix("llm|"));
        assertEquals(1, sink.withPrefix("usage|").size());
    }

    @Test
    void timeoutAndTransportFailuresRecordUnavailableUsage() {
        assertThrows(SocketTimeoutException.class,
                () -> chatThrowing(OPENAI, new SocketTimeoutException("Read timed out")));
        assertThrows(IOException.class, () -> chatThrowing(OPENAI, new IOException("connection reset")));
        assertEquals(List.of(
                "llm|openai|agent_round|timeout",
                "usage|openai|agent_round|unavailable",
                "llm|openai|agent_round|transport_error",
                "usage|openai|agent_round|unavailable"), sink.events);
    }

    @Test
    void missingUsageContributesSampleButNoTokens() throws Exception {
        chat(OPENAI, LlmCallKind.PLAYBOOK, 200, OPENAI_NO_USAGE);
        assertEquals(List.of("llm|openai|playbook|success", "usage|openai|playbook|unavailable"), sink.events);
    }

    @Test
    void partialUsageAddsOnlyValidFields() throws Exception {
        chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, OPENAI_PARTIAL);
        assertEquals(List.of(
                "llm|openai|agent_round|success",
                "usage|openai|agent_round|partial",
                "tokens|openai|agent_round|input|7"), sink.events);
    }

    @Test
    void invalidUsageContributesNoTokens() throws Exception {
        chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, OPENAI_INVALID);
        assertEquals(List.of("llm|openai|agent_round|success", "usage|openai|agent_round|invalid"), sink.events);
    }

    @Test
    void anthropicDerivedInputTotalCountsAndAbsentTotalDoesNot() throws Exception {
        anthropic(ANTHROPIC_DERIVED);
        anthropic(ANTHROPIC_NO_CACHE_FIELDS);
        assertEquals(List.of(
                "llm|anthropic|agent_round|success",
                "usage|anthropic|agent_round|complete",
                "tokens|anthropic|agent_round|input|35",
                "tokens|anthropic|agent_round|output|3",
                "tokens|anthropic|agent_round|total|38",
                "tokens|anthropic|agent_round|input_uncached|10",
                "tokens|anthropic|agent_round|input_cache_read|20",
                "tokens|anthropic|agent_round|input_cache_write|5",
                "llm|anthropic|agent_round|success",
                "usage|anthropic|agent_round|complete",
                "tokens|anthropic|agent_round|output|3",
                "tokens|anthropic|agent_round|input_uncached|10"), sink.events);
    }

    @Test
    void metricsAreIndependentOfInactiveRecorder() throws Exception {
        LlmCallRecorder.setEnabledForTest(false);
        chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, OPENAI_OK);
        assertThrows(Exception.class, () -> chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, "{not json"));
        assertThrows(IOException.class, () -> chatThrowing(OPENAI, new IOException("connection reset")));
        assertEquals(List.of("llm|openai|agent_round|success", "llm|openai|agent_round|parse_error",
                "llm|openai|agent_round|transport_error"), sink.withPrefix("llm|"));
    }

    @Test
    void metricsAreIndependentOfFailingStreamWriter() throws Exception {
        AgentLlmCallStreamWriter.setTestForcedResult(AgentLlmCallStreamWriter.WriteResult.FAILED);
        chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, OPENAI_OK);
        assertThrows(LlmHttpFailureException.class,
                () -> chat(OPENAI, LlmCallKind.AGENT_ROUND, 500, OPENAI_HTTP_ERROR_WITH_USAGE));
        assertEquals(List.of("llm|openai|agent_round|success", "llm|openai|agent_round|http_error"),
                sink.withPrefix("llm|"));
    }

    private static final String OPENAI_DETAIL = "{\"id\":\"r6\",\"model\":\"gpt-4o\","
            + "\"choices\":[{\"message\":{\"content\":\"hello\"}}],"
            + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":30,\"audio_tokens\":0},"
            + "\"completion_tokens_details\":{\"reasoning_tokens\":20,\"audio_tokens\":0,"
            + "\"accepted_prediction_tokens\":2,\"rejected_prediction_tokens\":1}}}";
    private static final String OPENAI_HTTP_ERROR_WITH_DETAIL = "{\"id\":\"r7\",\"model\":\"gpt-4o\","
            + "\"error\":{\"message\":\"overloaded\"},"
            + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":1}}}";

    private static final List<String> OPENAI_DETAIL_TOKENS = List.of(
            "tokens|openai|agent_round|input|100",
            "tokens|openai|agent_round|output|50",
            "tokens|openai|agent_round|total|150",
            "tokens|openai|agent_round|input_uncached|70",
            "tokens|openai|agent_round|input_cache_read|30",
            "tokens|openai|agent_round|reasoning|20",
            "tokens|openai|agent_round|input_audio|0",
            "tokens|openai|agent_round|output_audio|0",
            "tokens|openai|agent_round|output_accepted_prediction|2",
            "tokens|openai|agent_round|output_rejected_prediction|1");

    @Test
    void breakdownKindsAreAddedOncePerAttemptOnSuccessAndHttpError() throws Exception {
        chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, OPENAI_DETAIL);
        assertEquals(OPENAI_DETAIL_TOKENS, sink.withPrefix("tokens|"));
        sink.events.clear();
        assertThrows(LlmHttpFailureException.class,
                () -> chat(OPENAI, LlmCallKind.AGENT_ROUND, 503, OPENAI_HTTP_ERROR_WITH_DETAIL));
        assertEquals(List.of(
                "llm|openai|agent_round|http_error",
                "usage|openai|agent_round|complete",
                "tokens|openai|agent_round|input|4",
                "tokens|openai|agent_round|output|2",
                "tokens|openai|agent_round|total|6",
                "tokens|openai|agent_round|input_uncached|3",
                "tokens|openai|agent_round|input_cache_read|1"), sink.events);
    }

    @Test
    void breakdownKindsSurviveFailingStreamWriterAndCountEachRetry() throws Exception {
        AgentLlmCallStreamWriter.setTestForcedResult(AgentLlmCallStreamWriter.WriteResult.FAILED);
        chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, OPENAI_DETAIL);
        chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, OPENAI_DETAIL);
        List<String> twice = new java.util.ArrayList<>(OPENAI_DETAIL_TOKENS);
        twice.addAll(OPENAI_DETAIL_TOKENS);
        assertEquals(twice, sink.withPrefix("tokens|"));
        assertEquals(2, sink.withPrefix("usage|").size());
        assertEquals(2, sink.withPrefix("llm|").size());
    }

    @Test
    void recordingFailurePreservesProviderResult() throws Exception {
        sink.failRecording = true;
        LlmResponse response = chat(OPENAI, LlmCallKind.AGENT_ROUND, 200, OPENAI_OK);
        assertEquals("hello", response.getContent());
        assertThrows(LlmHttpFailureException.class,
                () -> chat(OPENAI, LlmCallKind.AGENT_ROUND, 500, OPENAI_HTTP_ERROR_WITH_USAGE));
    }
}
