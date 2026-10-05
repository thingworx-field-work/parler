package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.http.message.BasicHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.AnthropicMessagesApi;
import com.thingworx.things.agent.llm.ChatCompletionsResponseParser;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmHttpDiagnostics;
import com.thingworx.things.agent.llm.LlmHttpFailureException;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

/**
 * CC-7.7 item 1: HTTP error matrix for {@link LlmRecordedHttpChat} wrapper methods.
 */
class LlmRecordedHttpChatTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TIMEOUT_MS = 30_000;
    private static final LlmUsageWireIds OPENAI_IDS =
            LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
    private static final LlmUsageWireIds ANTHROPIC_IDS =
            LlmUsageWireIds.forProviderThing("P", "Anthropic", "anthropic-messages-v1", "claude-sonnet-4-20250514");

    private static final String OPENAI_SUCCESS_BODY =
            "{\"id\":\"resp-openai-1\",\"model\":\"gpt-4o\","
                    + "\"choices\":[{\"message\":{\"content\":\"hello\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    private static final String ANTHROPIC_SUCCESS_BODY =
            "{\"id\":\"resp-anthropic-1\",\"model\":\"claude-sonnet-4-20250514\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],"
                    + "\"stop_reason\":\"end_turn\","
                    + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";

    @BeforeEach
    void installSink() {
        LlmCallRecorder.resetForTest();
    }

    @AfterEach
    void cleanup() {
        LlmCallRecorder.resetForTest();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chatCompletionsMatrix")
    void executeChatCompletions_httpMatrix(MatrixCase case_) throws Exception {
        runMatrix(case_, true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("anthropicMessagesMatrix")
    void executeAnthropicMessages_httpMatrix(MatrixCase case_) throws Exception {
        runMatrix(case_, false);
    }

    private static Stream<MatrixCase> chatCompletionsMatrix() {
        return Stream.of(MatrixCase.values());
    }

    private static Stream<MatrixCase> anthropicMessagesMatrix() {
        return Stream.of(MatrixCase.values());
    }

    private void runMatrix(MatrixCase case_, boolean chatCompletions) throws Exception {
        LlmUsageWireIds wireIds = chatCompletions ? OPENAI_IDS : ANTHROPIC_IDS;
        LlmChatRequest request = requestWithContext(wireIds);
        String body = case_.bodyFor(chatCompletions);
        int status = case_.httpStatus;

        if (case_.expectSuccess(chatCompletions)) {
            LlmResponse response = execute(request, wireIds, chatCompletions, status, body, case_.executorError);
            assertSuccessResponse(case_, chatCompletions, response);
            assertFinished(case_, status, chatCompletions, null);
            return;
        }

        Exception thrown = assertThrows(case_.expectedExceptionType(chatCompletions),
                () -> execute(request, wireIds, chatCompletions, status, body, case_.executorError));
        assertPreRecorderException(case_, chatCompletions, status, body, thrown);
        assertFinished(case_, status, chatCompletions, thrown);
    }

    private static LlmResponse execute(
            LlmChatRequest request,
            LlmUsageWireIds wireIds,
            boolean chatCompletions,
            int status,
            String body,
            Exception transportError) throws Exception {
        return execute(request, wireIds, chatCompletions, status, body, transportError, null);
    }

    private static LlmResponse execute(
            LlmChatRequest request,
            LlmUsageWireIds wireIds,
            boolean chatCompletions,
            int status,
            String body,
            Exception transportError,
            java.util.concurrent.atomic.AtomicInteger executes) throws Exception {
        LlmRecordedHttpChat.HttpExecutor executor = () -> {
            if (executes != null) {
                executes.incrementAndGet();
            }
            if (transportError != null) {
                throw transportError;
            }
            return new LlmRecordedHttpChat.HttpResult(
                    status,
                    body,
                    new BasicHeader[] { new BasicHeader("x-request-id", "req-matrix-1") });
        };
        if (chatCompletions) {
            return LlmRecordedHttpChat.executeChatCompletions(
                    request, wireIds, "OpenAI", "matrix-test", TIMEOUT_MS, executor);
        }
        return LlmRecordedHttpChat.executeAnthropicMessages(
                request, wireIds, "messages", TIMEOUT_MS, executor);
    }

    private static void assertSuccessResponse(MatrixCase case_, boolean chatCompletions, LlmResponse response) {
        switch (case_) {
            case SUCCESS_WITH_USAGE:
                assertEquals("hello", response.getContent());
                if (chatCompletions) {
                    assertEquals(10, response.getPromptTokens());
                    assertEquals(5, response.getCompletionTokens());
                } else {
                    assertEquals(10, response.getInputTokens());
                    assertEquals(5, response.getOutputTokens());
                }
                return;
            case SUCCESS_NO_USAGE:
                assertEquals("hello", response.getContent());
                return;
            case PARSE_FAILURE_WITH_USAGE:
                assertEquals("", response.getContent());
                assertEquals(3, response.getInputTokens());
                assertEquals(1, response.getOutputTokens());
                return;
            default:
                assertNotNull(response.getContent());
        }
    }

    private static void assertUsageTokens(JsonNode envelope, boolean chatCompletions, int inputTotal, int outputTotal) {
        JsonNode normalized = envelope.path("usage").path("normalized");
        if (chatCompletions) {
            assertEquals(inputTotal, normalized.path("inputTokensTotal").asInt());
        } else {
            assertEquals(inputTotal, normalized.path("inputTokensUncached").asInt());
        }
        assertEquals(outputTotal, normalized.path("outputTokensTotal").asInt());
    }

    private static void assertPreRecorderException(
            MatrixCase case_,
            boolean chatCompletions,
            int status,
            String body,
            Exception thrown) throws Exception {
        switch (case_) {
            case TRANSPORT_FAILURE:
                assertInstanceOf(IOException.class, thrown);
                assertEquals("connection reset", thrown.getMessage());
                return;
            case TIMEOUT:
                assertInstanceOf(SocketTimeoutException.class, thrown);
                return;
            case HTTP_FAILURE_WITH_USAGE:
                LlmHttpFailureException baseline = LlmHttpDiagnostics.httpFailureException(
                        chatCompletions ? "OpenAI" : "Anthropic", status, body, new BasicHeader[0]);
                assertInstanceOf(LlmHttpFailureException.class, thrown);
                assertEquals(baseline.getMessage(), thrown.getMessage());
                assertEquals(baseline.getStatus(), ((LlmHttpFailureException) thrown).getStatus());
                return;
            case PARSE_FAILURE_WITH_USAGE:
            case INVALID_JSON:
                Exception parserBaseline = assertThrows(Exception.class, () -> {
                    if (chatCompletions) {
                        ChatCompletionsResponseParser.parse(body, "req-matrix-1");
                    } else {
                        AnthropicMessagesApi.parseResponse(body, "req-matrix-1");
                    }
                });
                assertEquals(parserBaseline.getClass(), thrown.getClass());
                if (case_ == MatrixCase.PARSE_FAILURE_WITH_USAGE) {
                    assertEquals(parserBaseline.getMessage(), thrown.getMessage());
                }
                return;
            default:
                throw new AssertionError("unexpected case " + case_);
        }
    }

    private static void assertFinished(
            MatrixCase case_,
            int status,
            boolean chatCompletions,
            Exception thrown) throws Exception {
        List<LlmCallEvent> finished = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_FINISHED)
                .collect(Collectors.toList());
        assertEquals(1, finished.size(), "expected one terminal recorder event");
        JsonNode envelope = JSON.readTree(finished.get(0).getEventJson());

        switch (case_) {
            case SUCCESS_WITH_USAGE:
                assertEquals("success", envelope.path("outcome").asText());
                assertEquals(status, envelope.path("httpStatus").asInt());
                assertEquals("complete", envelope.path("usage").path("status").asText());
                assertEquals(chatCompletions ? "resp-openai-1" : "resp-anthropic-1",
                        envelope.path("providerResponseId").asText());
                assertEquals(chatCompletions ? "gpt-4o" : "claude-sonnet-4-20250514",
                        envelope.path("responseModel").asText());
                assertUsageTokens(envelope, chatCompletions, 10, 5);
                return;
            case SUCCESS_NO_USAGE:
                assertEquals("success", envelope.path("outcome").asText());
                assertEquals(status, envelope.path("httpStatus").asInt());
                assertEquals("unavailable", envelope.path("usage").path("status").asText());
                assertEquals(chatCompletions ? "resp-openai-2" : "resp-anthropic-2",
                        envelope.path("providerResponseId").asText());
                assertEquals(chatCompletions ? "gpt-4o" : "claude-sonnet-4-20250514",
                        envelope.path("responseModel").asText());
                return;
            case HTTP_FAILURE_WITH_USAGE:
                assertEquals("error", envelope.path("outcome").asText());
                assertEquals(status, envelope.path("httpStatus").asInt());
                assertEquals("complete", envelope.path("usage").path("status").asText());
                assertEquals("http_" + status, envelope.path("error").path("category").asText());
                assertEquals(chatCompletions ? "resp-openai-err" : "resp-anthropic-err",
                        envelope.path("providerResponseId").asText());
                assertEquals(chatCompletions ? "gpt-4o" : "claude-sonnet-4-20250514",
                        envelope.path("responseModel").asText());
                assertUsageTokens(envelope, chatCompletions, 4, 2);
                assertInstanceOf(LlmHttpFailureException.class, thrown);
                return;
            case PARSE_FAILURE_WITH_USAGE:
                if (!chatCompletions) {
                    assertEquals("success", envelope.path("outcome").asText());
                    assertEquals(status, envelope.path("httpStatus").asInt());
                    assertEquals("complete", envelope.path("usage").path("status").asText());
                    assertEquals("resp-anthropic-parse", envelope.path("providerResponseId").asText());
                    assertEquals("claude-sonnet-4-20250514", envelope.path("responseModel").asText());
                    assertUsageTokens(envelope, chatCompletions, 3, 1);
                    return;
                }
                assertEquals("error", envelope.path("outcome").asText());
                assertEquals(status, envelope.path("httpStatus").asInt());
                assertEquals("complete", envelope.path("usage").path("status").asText());
                assertEquals("content_parse_error", envelope.path("error").path("category").asText());
                assertEquals("resp-openai-parse", envelope.path("providerResponseId").asText());
                assertEquals("gpt-4o", envelope.path("responseModel").asText());
                assertUsageTokens(envelope, chatCompletions, 3, 1);
                assertNotNull(thrown);
                return;
            case INVALID_JSON:
                assertEquals("error", envelope.path("outcome").asText());
                assertEquals(status, envelope.path("httpStatus").asInt());
                assertEquals("partial", envelope.path("usage").path("status").asText());
                assertEquals("content_parse_error", envelope.path("error").path("category").asText());
                assertTrue(thrown instanceof JsonParseException || thrown instanceof RuntimeException);
                return;
            case TRANSPORT_FAILURE:
                assertEquals("error", envelope.path("outcome").asText());
                assertTrue(envelope.path("httpStatus").isMissingNode() || envelope.path("httpStatus").isNull());
                assertEquals("unavailable", envelope.path("usage").path("status").asText());
                assertEquals("transport_error", envelope.path("error").path("category").asText());
                return;
            case TIMEOUT:
                assertEquals("timeout", envelope.path("outcome").asText());
                assertEquals("timeout", envelope.path("error").path("category").asText());
                assertTrue(envelope.path("httpStatus").isMissingNode() || envelope.path("httpStatus").isNull());
                return;
            default:
                throw new AssertionError("unexpected case " + case_);
        }
    }

    private static LlmChatRequest requestWithContext(LlmUsageWireIds wireIds) {
        LlmCallContext context = LlmCallRecorder.agentRoundContext(
                "rid-matrix", "cid-matrix", "AgentThing", 1, wireIds, null);
        return LlmChatRequest.copyWithCallContext(
                LlmChatRequest.forAgentRound(List.of(ChatMessage.user("hi")), null, 0.0, 32, null, wireIds),
                context);
    }

    private enum MatrixCase {
        SUCCESS_WITH_USAGE(200, null),
        SUCCESS_NO_USAGE(200, null),
        HTTP_FAILURE_WITH_USAGE(503, null),
        PARSE_FAILURE_WITH_USAGE(200, null),
        INVALID_JSON(200, null),
        TRANSPORT_FAILURE(200, new IOException("connection reset")),
        TIMEOUT(200, new SocketTimeoutException("Read timed out"));

        final int httpStatus;
        final Exception executorError;

        MatrixCase(int httpStatus, Exception executorError) {
            this.httpStatus = httpStatus;
            this.executorError = executorError;
        }

        boolean expectSuccess(boolean chatCompletions) {
            return this == SUCCESS_WITH_USAGE || this == SUCCESS_NO_USAGE
                    || (this == PARSE_FAILURE_WITH_USAGE && !chatCompletions);
        }

        Class<? extends Exception> expectedExceptionType(boolean chatCompletions) {
            switch (this) {
                case HTTP_FAILURE_WITH_USAGE:
                    return LlmHttpFailureException.class;
                case PARSE_FAILURE_WITH_USAGE:
                    return RuntimeException.class;
                case INVALID_JSON:
                    return JsonParseException.class;
                case TRANSPORT_FAILURE:
                    return IOException.class;
                case TIMEOUT:
                    return SocketTimeoutException.class;
                default:
                    throw new IllegalStateException("not a failure case: " + this);
            }
        }

        String bodyFor(boolean chatCompletions) {
            switch (this) {
                case SUCCESS_WITH_USAGE:
                    return chatCompletions ? OPENAI_SUCCESS_BODY : ANTHROPIC_SUCCESS_BODY;
                case SUCCESS_NO_USAGE:
                    if (chatCompletions) {
                        return "{\"id\":\"resp-openai-2\",\"model\":\"gpt-4o\","
                                + "\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";
                    }
                    return "{\"id\":\"resp-anthropic-2\",\"model\":\"claude-sonnet-4-20250514\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\"}";
                case HTTP_FAILURE_WITH_USAGE:
                    if (chatCompletions) {
                        return "{\"id\":\"resp-openai-err\",\"model\":\"gpt-4o\","
                                + "\"choices\":[{\"message\":{\"content\":\"ignored\"}}],"
                                + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}";
                    }
                    return "{\"id\":\"resp-anthropic-err\",\"model\":\"claude-sonnet-4-20250514\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"ignored\"}],"
                            + "\"usage\":{\"input_tokens\":4,\"output_tokens\":2}}";
                case PARSE_FAILURE_WITH_USAGE:
                    if (chatCompletions) {
                        return "{\"id\":\"resp-openai-parse\",\"model\":\"gpt-4o\","
                                + "\"choices\":[],"
                                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}";
                    }
                    return "{\"id\":\"resp-anthropic-parse\",\"model\":\"claude-sonnet-4-20250514\","
                            + "\"content\":null,\"stop_reason\":\"end_turn\","
                            + "\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}";
                case INVALID_JSON:
                    return "{not-json";
                case TRANSPORT_FAILURE:
                case TIMEOUT:
                    return "";
                default:
                    throw new IllegalStateException("unexpected case " + this);
            }
        }
    }

    @ParameterizedTest(name = "openai {0}")
    @MethodSource("compatibleOpenAiUsageVariants")
    void executeChatCompletions_compatibleUsageVariants_preservesAnswerAndClassifiesUsage(
            String label,
            String body,
            String expectedUsageStatus,
            String expectedCaptureError) throws Exception {
        runCompatibleUsageVariant(true, label, body, expectedUsageStatus, expectedCaptureError);
    }

    @ParameterizedTest(name = "anthropic {0}")
    @MethodSource("compatibleAnthropicUsageVariants")
    void executeAnthropicMessages_compatibleUsageVariants_preservesAnswerAndClassifiesUsage(
            String label,
            String body,
            String expectedUsageStatus,
            String expectedCaptureError) throws Exception {
        runCompatibleUsageVariant(false, label, body, expectedUsageStatus, expectedCaptureError);
    }

    @Test
    void executeAnthropicMessages_zeroCacheCreateWithContradictoryTtl_preservesAnswerAndMarksInvalidUsage()
            throws Exception {
        String body = anthropicBody("{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":0,"
                + "\"cache_creation_input_tokens\":0,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":100,\"ephemeral_1h_input_tokens\":0}}");
        java.util.concurrent.atomic.AtomicInteger executes = new java.util.concurrent.atomic.AtomicInteger();
        LlmChatRequest request = requestWithContext(ANTHROPIC_IDS);
        LlmResponse response = execute(request, ANTHROPIC_IDS, false, 200, body, null, executes);
        assertEquals("hello", response.getContent());
        assertEquals(1, executes.get(), "expected exactly one HTTP execute");
        List<LlmCallEvent> finished = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_FINISHED)
                .collect(Collectors.toList());
        assertEquals(1, finished.size());
        JsonNode usage = JSON.readTree(finished.get(0).getEventJson()).path("usage");
        assertEquals("invalid", usage.path("status").asText());
        assertEquals("usage_invalid_counter", usage.path("captureError").asText());
        assertEquals(100L, usage.path("normalized").path("cacheWrite5mTokens").asLong());
        assertEquals(0L, usage.path("normalized").path("inputTokensCacheWrite").asLong());
    }

    private static Stream<Arguments> compatibleOpenAiUsageVariants() {
        return Stream.of(
                Arguments.of("missing", openAiBody(null), "unavailable", null),
                Arguments.of("null", openAiBody("null"), "unavailable", null),
                Arguments.of("empty-object", openAiBody("{}"), "partial", "usage_incomplete"),
                Arguments.of("string-type", openAiBody("\"n/a\""), "invalid", "usage_type_string"),
                Arguments.of("partial-counters", openAiBody("{\"prompt_tokens\":10}"), "partial", "usage_incomplete"),
                Arguments.of("explicit-zero", openAiBody(
                        "{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}"), "complete", null),
                Arguments.of("invalid-string-counter", openAiBody(
                        "{\"prompt_tokens\":\"10\",\"completion_tokens\":5,\"total_tokens\":15}"),
                        "invalid", "usage_invalid_counter"));
    }

    private static Stream<Arguments> compatibleAnthropicUsageVariants() {
        return Stream.of(
                Arguments.of("missing", anthropicBody(null), "unavailable", null),
                Arguments.of("empty-object", anthropicBody("{}"), "partial", "usage_incomplete"),
                Arguments.of("string-type", anthropicBody("\"n/a\""), "invalid", "usage_type_string"),
                Arguments.of("partial-counters", anthropicBody("{\"input_tokens\":10}"), "partial", "usage_incomplete"));
    }

    private static String openAiBody(String usageJson) {
        String usagePart = usageJson == null ? "" : ",\"usage\":" + usageJson;
        return "{\"id\":\"resp-compat\",\"model\":\"gpt-4o\","
                + "\"choices\":[{\"message\":{\"content\":\"hello\"}}]" + usagePart + "}";
    }

    private static String anthropicBody(String usageJson) {
        String usagePart = usageJson == null ? "" : ",\"usage\":" + usageJson;
        return "{\"id\":\"resp-compat\",\"model\":\"claude-sonnet-4-20250514\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\""
                + usagePart + "}";
    }

    private void runCompatibleUsageVariant(
            boolean chatCompletions,
            String label,
            String body,
            String expectedUsageStatus,
            String expectedCaptureError) throws Exception {
        java.util.concurrent.atomic.AtomicInteger executes = new java.util.concurrent.atomic.AtomicInteger();
        LlmUsageWireIds wireIds = chatCompletions ? OPENAI_IDS : ANTHROPIC_IDS;
        LlmChatRequest request = requestWithContext(wireIds);
        LlmResponse response = execute(request, wireIds, chatCompletions, 200, body, null, executes);
        assertEquals("hello", response.getContent());
        assertEquals(1, executes.get(), "expected exactly one HTTP execute");
        List<LlmCallEvent> finished = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_FINISHED)
                .collect(Collectors.toList());
        assertEquals(1, finished.size());
        JsonNode envelope = JSON.readTree(finished.get(0).getEventJson());
        JsonNode usage = envelope.path("usage");
        assertEquals("success", envelope.path("outcome").asText());
        assertEquals(expectedUsageStatus, usage.path("status").asText());
        if (expectedCaptureError == null) {
            assertTrue(usage.path("captureError").isMissingNode()
                    || usage.path("captureError").isNull());
        } else {
            assertEquals(expectedCaptureError, usage.path("captureError").asText());
        }
        assertTerminalUsageEvidence(label, usage, chatCompletions);
    }

    @Test
    void executeChatCompletions_toolCallResponse_invalidUsage_preservesCallIdentity() throws Exception {
        runCompatibleToolCallVariant(
                true,
                openAiToolCallBody("\"usage\":\"n/a\""),
                "invalid",
                "usage_type_string",
                "call_compat_1",
                "noop",
                "{\"q\":1}");
    }

    @Test
    void executeChatCompletions_toolCallResponse_unavailableUsage_preservesCallIdentity() throws Exception {
        runCompatibleToolCallVariant(
                true,
                openAiToolCallBody(null),
                "unavailable",
                null,
                "call_compat_1",
                "noop",
                "{\"q\":1}");
    }

    @Test
    void executeAnthropicMessages_toolUseResponse_invalidUsage_preservesCallIdentity() throws Exception {
        runCompatibleToolCallVariant(
                false,
                anthropicToolUseBody("\"usage\":\"n/a\""),
                "invalid",
                "usage_type_string",
                "toolu_compat_1",
                "noop",
                "{\"q\":1}");
    }

    @Test
    void executeAnthropicMessages_toolUseResponse_unavailableUsage_preservesCallIdentity() throws Exception {
        runCompatibleToolCallVariant(
                false,
                anthropicToolUseBody(null),
                "unavailable",
                null,
                "toolu_compat_1",
                "noop",
                "{\"q\":1}");
    }

    private static String openAiToolCallBody(String usageJson) {
        String usagePart = usageJson == null ? "" : "," + usageJson;
        return "{\"id\":\"resp-compat-tool\",\"model\":\"gpt-4o\","
                + "\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{"
                + "\"tool_calls\":[{\"id\":\"call_compat_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"noop\",\"arguments\":\"{\\\"q\\\":1}\"}}]}}]"
                + usagePart + "}";
    }

    private static String anthropicToolUseBody(String usageJson) {
        String usagePart = usageJson == null ? "" : "," + usageJson;
        return "{\"id\":\"resp-compat-tool\",\"model\":\"claude-sonnet-4-20250514\","
                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_compat_1\",\"name\":\"noop\","
                + "\"input\":{\"q\":1}}],\"stop_reason\":\"tool_use\""
                + usagePart + "}";
    }

    private void runCompatibleToolCallVariant(
            boolean chatCompletions,
            String body,
            String expectedUsageStatus,
            String expectedCaptureError,
            String expectedCallId,
            String expectedFunctionName,
            String expectedArguments) throws Exception {
        java.util.concurrent.atomic.AtomicInteger executes = new java.util.concurrent.atomic.AtomicInteger();
        LlmUsageWireIds wireIds = chatCompletions ? OPENAI_IDS : ANTHROPIC_IDS;
        LlmChatRequest request = requestWithContext(wireIds);
        LlmResponse response = execute(request, wireIds, chatCompletions, 200, body, null, executes);
        assertEquals(LlmResponse.FinishReason.TOOL_CALLS, response.getFinishReason());
        assertEquals(1, response.getToolCalls().size());
        assertEquals(expectedCallId, response.getToolCalls().get(0).getId());
        assertEquals(expectedFunctionName, response.getToolCalls().get(0).getFunctionName());
        assertEquals(expectedArguments, response.getToolCalls().get(0).getArguments());
        assertEquals(1, executes.get(), "expected exactly one HTTP execute");
        List<LlmCallEvent> finished = AgentLlmCallStreamWriter.testCapture().stream()
                .filter(e -> e.getEventType() == LlmCallEventType.CALL_FINISHED)
                .collect(Collectors.toList());
        assertEquals(1, finished.size());
        JsonNode envelope = JSON.readTree(finished.get(0).getEventJson());
        JsonNode usage = envelope.path("usage");
        assertEquals("success", envelope.path("outcome").asText());
        assertEquals(expectedUsageStatus, usage.path("status").asText());
        if (expectedCaptureError == null) {
            assertTrue(usage.path("captureError").isMissingNode()
                    || usage.path("captureError").isNull());
        } else {
            assertEquals(expectedCaptureError, usage.path("captureError").asText());
        }
        if ("invalid".equals(expectedUsageStatus)) {
            assertTrue(usage.path("rawUsage").isTextual());
            assertEquals("n/a", usage.path("rawUsage").asText());
            assertNormalizedAbsent(usage.path("normalized"), "inputTokensTotal");
        } else {
            assertTrue(usage.path("rawUsage").isMissingNode() || usage.path("rawUsage").isNull());
            assertTrue(usage.path("normalized").isObject() && usage.path("normalized").isEmpty());
        }
    }

    private static void assertTerminalUsageEvidence(String label, JsonNode usage, boolean chatCompletions) {
        JsonNode normalized = usage.path("normalized");
        JsonNode presence = usage.path("presence");
        JsonNode rawUsage = usage.path("rawUsage");
        switch (label) {
            case "missing":
            case "null":
                assertTrue(normalized.isObject() && normalized.isEmpty());
                assertTrue(rawUsage.isMissingNode() || rawUsage.isNull());
                break;
            case "empty-object":
                assertTrue(rawUsage.isObject() && rawUsage.isEmpty());
                assertTrue(normalized.isObject() && normalized.isEmpty());
                break;
            case "string-type":
                assertTrue(rawUsage.isTextual());
                assertEquals("n/a", rawUsage.asText());
                assertNormalizedAbsent(normalized, chatCompletions ? "inputTokensTotal" : "inputTokensUncached");
                break;
            case "partial-counters":
                if (chatCompletions) {
                    assertNormalizedReported(normalized, presence, "inputTokensTotal", 10L);
                    assertEquals("absent", presence.path("outputTokensTotal").asText());
                    assertEquals("absent", presence.path("totalTokens").asText());
                } else {
                    assertNormalizedReported(normalized, presence, "inputTokensUncached", 10L);
                    assertEquals("absent", presence.path("outputTokensTotal").asText());
                }
                assertTrue(rawUsage.isObject());
                break;
            case "explicit-zero":
                assertNormalizedReported(normalized, presence, "inputTokensTotal", 0L);
                assertNormalizedReported(normalized, presence, "outputTokensTotal", 0L);
                assertEquals("absent", presence.path("inputTokensCacheRead").asText());
                break;
            case "invalid-string-counter":
                assertTrue(rawUsage.isObject());
                assertTrue(rawUsage.path("prompt_tokens").isTextual());
                assertNormalizedAbsent(normalized, "inputTokensTotal");
                break;
            default:
                throw new AssertionError("unexpected compatibility label: " + label);
        }
    }

    private static void assertNormalizedAbsent(JsonNode normalized, String key) {
        assertTrue(normalized.path(key).isMissingNode() || normalized.path(key).isNull(),
                "expected absent normalized key: " + key);
    }

    private static void assertNormalizedReported(
            JsonNode normalized,
            JsonNode presence,
            String key,
            long expectedValue) {
        assertTrue(normalized.has(key), "expected normalized key: " + key);
        assertEquals(expectedValue, normalized.get(key).asLong());
        assertEquals("reported", presence.path(key).asText());
    }
}
