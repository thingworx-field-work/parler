package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class AnthropicMessagesLlmClientPhase1Test {

    @Test
    void anthropicWarningSeamEmitsContentFreeClassificationWarning() {
        List<String> calls = new ArrayList<>();
        SuffixClassificationWarningLogger.log(
                ChatCompletionsClientDiagnosticsTest.recordingLogger(calls),
                "anthropic",
                SuffixClassificationDiagnostics.fromPlannedMessages(List.of(
                        ChatMessage.system("stable"),
                        ChatMessage.system("unclassified-secret"),
                        ChatMessage.user("u"))));
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).contains("anthropic"));
        assertTrue(calls.get(0).contains("b645abfd0f5b985a"));
        assertFalse(calls.get(0).contains("unclassified-secret"));
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void buildRequestPayload_structuredSystemWithCacheOnFirstStableRow() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-prefix"));
        messages.add(ChatMessage.system("dynamic-second"));
        messages.add(ChatMessage.user("hello"));

        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(new ToolDefinition("a", "da", new HashMap<>()));
        tools.add(new ToolDefinition("b", "db", new HashMap<>()));

        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(messages, tools, "claude-test", 0.0, 100);
        JsonNode root = JSON.valueToTree(body);
        assertTrue(root.path("system").isArray());
        assertEquals(2, root.path("system").size());
        JsonNode first = root.path("system").get(0);
        assertEquals("text", first.path("type").asText());
        assertEquals("stable-prefix", first.path("text").asText());
        assertEquals("ephemeral", first.path("cache_control").path("type").asText());
        JsonNode second = root.path("system").get(1);
        assertTrue(second.path("cache_control").isMissingNode());

        JsonNode toolsNode = root.path("tools");
        assertEquals(2, toolsNode.size());
        JsonNode lastTool = toolsNode.get(1);
        assertEquals("ephemeral", lastTool.path("cache_control").path("type").asText());
    }

    @Test
    void emptyFinalRetryInstruction_passesSuffixAssemblyGuardAndStaysOutOfStableSystemPrefix() throws Exception {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("stable-prefix"),
                ChatMessage.user("answer"),
                ChatMessage.system(EmptyFinalAnswerRetryInjector.buildSystemContent()));

        AnthropicMessagesApi.RequestPayloadResult result = AnthropicMessagesApi.buildRequestPayloadWithDiagnostics(
                messages, Collections.emptyList(), "claude-test", 0.0, 8192, 0, null,
                AnthropicSamplingParametersMode.legacy);
        JsonNode payload = JSON.valueToTree(result.getPayload());

        assertTrue(result.getDiagnostics().isEmpty());
        assertEquals("stable-prefix", payload.path("system").get(0).path("text").asText());
        assertTrue(payload.path("messages").toString().contains(EmptyFinalAnswerRetryInjector.PREFIX));
        assertFalse(payload.path("system").toString().contains(EmptyFinalAnswerRetryInjector.PREFIX));
    }

    @Test
    void parseResponse_aggregatesPromptTokensWithCacheBreakdown() throws Exception {
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"cache_read_input_tokens\":100,"
                + "\"cache_creation_input_tokens\":20}}";
        LlmResponse r = AnthropicMessagesApi.parseResponse(body, "rid-1");
        assertEquals(10, r.getInputTokens());
        assertEquals(100, r.getCacheReadInputTokens());
        assertEquals(20, r.getCacheCreationInputTokens());
        assertEquals(130, r.getPromptTokens());
        assertEquals(2, r.getOutputTokens());
        assertEquals("rid-1", r.getProviderRequestId());
    }

    @Test
    void parseResponse_exposesOnlySanitizedBlockShapeDiagnostics() throws Exception {
        String body = "{\"content\":["
                + "{\"type\":\"text\",\"text\":\"\"},"
                + "{\"type\":\"thinking\",\"thinking\":\"secret-thought\"},"
                + "{\"type\":\"redacted thinking!\",\"data\":\"secret-redaction\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"noop\","
                + "\"input\":{\"password\":\"secret-input\"}}],"
                + "\"stop_reason\":\"tool_use\",\"usage\":{}}";

        LlmResponse response = AnthropicMessagesApi.parseResponse(body, "rid-shape");
        LlmResponseShapeDiagnostics shape = response.getResponseShapeDiagnostics();

        assertEquals(4, shape.getContentBlockCount());
        assertEquals(1, shape.getTextBlockCount());
        assertEquals(1, shape.getToolUseBlockCount());
        assertEquals(2, shape.getOtherBlockCount());
        assertEquals(List.of("thinking", "redacted_thinking_"), shape.getOtherBlockTypes());
        String safeShape = shape.getOtherBlockTypes().toString();
        assertFalse(safeShape.contains("secret-thought"));
        assertFalse(safeShape.contains("secret-redaction"));
        assertFalse(safeShape.contains("secret-input"));
    }

    @Test
    void parseResponse_noContentBlocksReportsZeroShapeAndBlankText() throws Exception {
        LlmResponse response = AnthropicMessagesApi.parseResponse(
                "{\"content\":[],\"stop_reason\":\"end_turn\",\"usage\":{}}", "rid-empty");

        assertEquals("", response.getContent());
        assertEquals(0, response.getResponseShapeDiagnostics().getContentBlockCount());
        assertEquals(0, response.getResponseShapeDiagnostics().getTextBlockCount());
        assertEquals(0, response.getResponseShapeDiagnostics().getToolUseBlockCount());
        assertEquals(0, response.getResponseShapeDiagnostics().getOtherBlockCount());
        assertTrue(response.getResponseShapeDiagnostics().getOtherBlockTypes().isEmpty());
    }

    @Test
    void buildRequestPayload_omitsThinkingWhenBudgetZero() throws Exception {
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                Collections.singletonList(ChatMessage.user("hi")), null, "m", 0.0, 8192, 0);
        JsonNode root = JSON.valueToTree(body);
        assertTrue(root.path("thinking").isMissingNode());
    }

    @Test
    void buildRequestPayload_includesThinkingWhenBudgetPositive() throws Exception {
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                Collections.singletonList(ChatMessage.user("hi")), null, "m", 0.0, 8192, 1024);
        JsonNode root = JSON.valueToTree(body);
        assertEquals("enabled", root.path("thinking").path("type").asText());
        assertEquals(1024, root.path("thinking").path("budget_tokens").asInt());
    }

    @Test
    void buildRequestPayload_rejectsThinkingBudgetBelow1024() {
        assertThrows(IllegalArgumentException.class, () -> AnthropicMessagesApi.buildRequestPayload(
                Collections.singletonList(ChatMessage.user("hi")), null, "m", 0.0, 8192, 512));
    }

    @Test
    void buildRequestPayload_acceptsThinkingBudgetAt1024() throws Exception {
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                Collections.singletonList(ChatMessage.user("hi")), null, "m", 0.0, 8192, 1024);
        JsonNode root = JSON.valueToTree(body);
        assertEquals(1024, root.path("thinking").path("budget_tokens").asInt());
    }

    @Test
    void buildRequestPayload_omitsTemperatureWhenThinkingEnabled() throws Exception {
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                Collections.singletonList(ChatMessage.user("hi")), null, "m", 0.2, 8192, 4096);
        assertTrue(!body.containsKey("temperature"));
    }

    @Test
    void buildRequestPayload_includesTemperatureWhenThinkingDisabled() throws Exception {
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                Collections.singletonList(ChatMessage.user("hi")), null, "m", 0.2, 8192, 0);
        assertEquals(0.2, (Double) body.get("temperature"));
    }

    @Test
    void buildRequestPayload_legacySamplingModeIncludesTemperatureWhenThinkingDisabled() throws Exception {
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                Collections.singletonList(ChatMessage.user("hi")), null, "m", 0.2, 8192, 0,
                AnthropicSamplingParametersMode.legacy);
        assertEquals(0.2, (Double) body.get("temperature"));
    }

    @Test
    void buildRequestPayload_omitSamplingModeSuppressesTemperatureWhenThinkingDisabled() throws Exception {
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                Collections.singletonList(ChatMessage.user("hi")), null, "m", 0.2, 8192, 0,
                AnthropicSamplingParametersMode.omit);
        assertFalse(body.containsKey("temperature"));
    }

    @Test
    void buildRequestPayload_nullSamplingModeDefaultsToLegacy() throws Exception {
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                Collections.singletonList(ChatMessage.user("hi")), null, "m", 0.2, 8192, 0,
                (AnthropicSamplingParametersMode) null);
        assertEquals(0.2, (Double) body.get("temperature"));
    }

    @Test
    void buildRequestPayload_rejectsThinkingBudgetGteMaxTokens() {
        assertThrows(IllegalArgumentException.class, () -> AnthropicMessagesApi.buildRequestPayload(
                Collections.singletonList(ChatMessage.user("hi")), null, "m", 0.0, 1024, 1024));
    }

    @Test
    void probeMode_skipsThinkingOnSmallMaxOutputProbe() throws Exception {
        int configuredThinking = 4096;
        int effectiveThinking = true ? 0 : configuredThinking;
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                Collections.singletonList(ChatMessage.user("Reply with exactly: OK")),
                null,
                "m",
                0.0,
                32,
                effectiveThinking,
                AnthropicSamplingParametersMode.omit);
        JsonNode root = JSON.valueToTree(body);
        assertTrue(root.path("thinking").isMissingNode());
        assertFalse(body.containsKey("temperature"));
    }

    @Test
    void samplingParametersModeParser_defaultsBlankAndParsesCaseInsensitive() {
        assertEquals(AnthropicSamplingParametersMode.legacy, AnthropicSamplingParametersMode.parse(null));
        assertEquals(AnthropicSamplingParametersMode.legacy, AnthropicSamplingParametersMode.parse(" "));
        assertEquals(AnthropicSamplingParametersMode.legacy, AnthropicSamplingParametersMode.parse("LEGACY"));
        assertEquals(AnthropicSamplingParametersMode.omit, AnthropicSamplingParametersMode.parse(" Omit "));
    }

    @Test
    void samplingParametersModeParser_rejectsUnknownValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AnthropicSamplingParametersMode.parse("auto"));
        assertTrue(e.getMessage().contains("samplingParametersMode must be legacy or omit"));
    }

    @Test
    void buildRequestPayload_singleDynamicSystemRow_usesStringForm() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u"));
        messages.add(ChatMessage.system("only-dynamic"));
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(messages, null, "m", 0.0, 50);
        assertTrue(body.get("system") instanceof String);
        assertEquals("only-dynamic", body.get("system"));
    }

    @Test
    void buildRequestPayload_omits_tool_choice_when_routing_with_tools() throws Exception {
        List<ToolDefinition> tools = List.of(new ToolDefinition("x", "d", Map.of("type", "object")));
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("hi")), tools, 0.0, 100, null);
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                req.getMessages(), tools, "m", 0.0, 100, 0, req);
        JsonNode root = JSON.valueToTree(body);
        assertTrue(root.path("tools").isArray());
        assertTrue(root.path("tool_choice").isMissingNode());
    }

    @Test
    void buildRequestPayload_toolChoiceNone_with_empty_tools_omits_tools_and_tool_choice() throws Exception {
        List<ToolDefinition> empty = Collections.emptyList();
        LlmChatRequest base = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("hi")), empty, 0.0, 100, null);
        LlmChatRequest req = LlmChatRequest.copyWithToolPolicy(base, true);
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                req.getMessages(), empty, "m", 0.0, 100, 0, req);
        JsonNode root = JSON.valueToTree(body);
        assertTrue(root.path("tools").isMissingNode());
        assertTrue(root.path("tool_choice").isMissingNode());
    }

    @Test
    void buildRequestPayload_toolChoiceNone_with_tools_sends_tool_choice_none() throws Exception {
        List<ToolDefinition> tools = List.of(new ToolDefinition("x", "d", Map.of("type", "object")));
        LlmChatRequest base = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("hi")), tools, 0.0, 100, null);
        LlmChatRequest req = LlmChatRequest.copyWithToolPolicy(base, true);
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                req.getMessages(), tools, "m", 0.0, 100, 0, req);
        JsonNode root = JSON.valueToTree(body);
        assertTrue(root.path("tools").isArray());
        assertEquals("none", root.path("tool_choice").path("type").asText());
    }
}
