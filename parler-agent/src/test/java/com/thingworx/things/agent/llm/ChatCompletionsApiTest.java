package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ChatCompletionsApiTest {

    @Test
    void buildRequestBodyWithDiagnostics_threadsUnknownSystemEntry() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("stable"), ChatMessage.system("unclassified-secret"), ChatMessage.user("u"));
        LlmChatRequest request = LlmChatRequest.forAgentRound(messages, null, 0.0, 32, null);
        ChatCompletionsApi.RequestBodyResult result = ChatCompletionsApi.buildRequestBodyWithDiagnostics(
                "gpt", messages, null, request, false, 32);
        assertEquals(1, result.getDiagnostics().getEntries().size());
        assertEquals("b645abfd0f5b985a",
                result.getDiagnostics().getEntries().get(0).getContentDigest());
        assertEquals(3, ((List<?>) result.getBody().get("messages")).size());
    }

    @Test
    void buildRequestBody_v4_includesTemperature() {
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("hi")), null, 0.1, 4096, null);
        Map<String, Object> body = ChatCompletionsApi.buildRequestBody("gpt-4o", req.getMessages(), null, req, false, 4096);
        assertTrue(body.containsKey("temperature"));
        assertEquals(0.1, (Double) body.get("temperature"));
        assertTrue(body.containsKey("max_tokens"));
        assertFalse(body.containsKey("max_completion_tokens"));
    }

    @Test
    void buildRequestBody_v5_omitsTemperature() {
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("hi")), null, 0.0, 8192, null);
        Map<String, Object> body = ChatCompletionsApi.buildRequestBody(
                "gpt-5.4", req.getMessages(), null, req, true, 8192);
        assertFalse(body.containsKey("temperature"));
        assertTrue(body.containsKey("max_completion_tokens"));
        assertEquals(8192, body.get("max_completion_tokens"));
    }

    @Test
    void buildRequestBody_v5_includesReasoningEffortWhenSet() {
        LlmChatRequest base = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("hi")), null, 0.1, 8192, null);
        LlmChatRequest req = LlmChatRequest.copyWithProviderAugmentation(
                base, null, 8192, "low");
        Map<String, Object> body = ChatCompletionsApi.buildRequestBody(
                "gpt-5.4-mini", req.getMessages(), null, req, true, 8192);
        assertEquals("low", body.get("reasoning_effort"));
        assertFalse(body.containsKey("temperature"));
    }

    @Test
    void buildRequestBody_omits_parallel_tool_calls_when_routing_with_tools() {
        List<ToolDefinition> tools = List.of(new ToolDefinition("fn", "d", Map.of("type", "object")));
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("hi")), tools, 0.0, 100, null);
        Map<String, Object> body = ChatCompletionsApi.buildRequestBody(
                "gpt-4o", req.getMessages(), tools, req, false, 100);
        assertTrue(body.containsKey("tools"));
        assertFalse(body.containsKey("parallel_tool_calls"));
        assertFalse(body.containsKey("tool_choice"));
    }

    @Test
    void buildRequestBody_toolChoiceNone_with_empty_tools_omits_tools_and_tool_choice() {
        List<ToolDefinition> empty = Collections.emptyList();
        LlmChatRequest base = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("hi")), empty, 0.0, 100, null);
        LlmChatRequest req = LlmChatRequest.copyWithToolPolicy(base, true);
        Map<String, Object> body = ChatCompletionsApi.buildRequestBody(
                "gpt-4o", req.getMessages(), empty, req, false, 100);
        assertFalse(body.containsKey("tools"));
        assertFalse(body.containsKey("tool_choice"));
        assertFalse(body.containsKey("parallel_tool_calls"));
    }

    @Test
    void buildRequestBody_toolChoiceNone_with_tools_sends_tool_choice_none() {
        List<ToolDefinition> tools = List.of(new ToolDefinition("fn", "d", Map.of("type", "object")));
        LlmChatRequest base = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("hi")), tools, 0.0, 100, null);
        LlmChatRequest req = LlmChatRequest.copyWithToolPolicy(base, true);
        Map<String, Object> body = ChatCompletionsApi.buildRequestBody(
                "gpt-4o", req.getMessages(), tools, req, false, 100);
        assertTrue(body.containsKey("tools"));
        assertEquals("none", body.get("tool_choice"));
        assertFalse(body.containsKey("parallel_tool_calls"));
    }
}
