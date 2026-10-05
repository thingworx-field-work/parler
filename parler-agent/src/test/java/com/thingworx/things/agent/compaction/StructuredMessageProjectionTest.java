package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.AnthropicMessagesApi;
import com.thingworx.things.agent.llm.ChatCompletionsApi;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;

/** Minimal reverse fixtures for CC-8 structured projection semantics. */
class StructuredMessageProjectionTest {

    private static final List<ToolDefinition> TOOLS = List.of(
            new ToolDefinition("invoke_service", "Invoke a service", Map.of("type", "object")));

    @Test
    void reverseFixture_sameTextDifferentToolArguments_projectionDiffers_onBothWires() {
        List<ChatMessage> deviceA = baseMessages("{\"entityName\":\"Device.A\"}");
        List<ChatMessage> deviceB = baseMessages("{\"entityName\":\"Device.B\"}");

        assertNotEquals(
                WirePartitionHasher.hashMessages(openAiWire(deviceA), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS),
                WirePartitionHasher.hashMessages(openAiWire(deviceB), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS));
        assertNotEquals(
                WirePartitionHasher.hashMessages(anthropicWire(deviceA), StructuredMessageProjection.WireKind.ANTHROPIC),
                WirePartitionHasher.hashMessages(anthropicWire(deviceB), StructuredMessageProjection.WireKind.ANTHROPIC));
    }

    @Test
    void reverseFixture_sameTextDifferentCallOrPairingIds_projectionDiffers_onBothWires() {
        List<ChatMessage> idA = pairingMessages("call-alpha", "call-alpha");
        List<ChatMessage> idB = pairingMessages("call-beta", "call-beta");

        assertNotEquals(
                WirePartitionHasher.hashMessages(openAiWire(idA), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS),
                WirePartitionHasher.hashMessages(openAiWire(idB), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS));
        assertNotEquals(
                WirePartitionHasher.hashMessages(anthropicWire(idA), StructuredMessageProjection.WireKind.ANTHROPIC),
                WirePartitionHasher.hashMessages(anthropicWire(idB), StructuredMessageProjection.WireKind.ANTHROPIC));

        List<ChatMessage> mismatchedPair = pairingMessages("call-alpha", "call-other");
        assertNotEquals(
                WirePartitionHasher.hashMessages(openAiWire(idA), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS),
                WirePartitionHasher.hashMessages(openAiWire(mismatchedPair),
                        StructuredMessageProjection.WireKind.CHAT_COMPLETIONS));
    }

    @Test
    void reverseFixture_nestedCacheControlInToolArguments_projectionDiffers_onBothWires() {
        List<ChatMessage> cacheA = baseMessages("{\"entityName\":\"Device.A\",\"cache_control\":\"A\"}");
        List<ChatMessage> cacheB = baseMessages("{\"entityName\":\"Device.A\",\"cache_control\":\"B\"}");

        assertNotEquals(
                WirePartitionHasher.hashMessages(openAiWire(cacheA), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS),
                WirePartitionHasher.hashMessages(openAiWire(cacheB), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS));
        assertNotEquals(
                WirePartitionHasher.hashMessages(anthropicWire(cacheA), StructuredMessageProjection.WireKind.ANTHROPIC),
                WirePartitionHasher.hashMessages(anthropicWire(cacheB), StructuredMessageProjection.WireKind.ANTHROPIC));
    }

    @Test
    void reverseFixture_noLeadingSystemRow_laterSystemVisibleInPartitions() {
        List<ChatMessage> suffixA = List.of(
                ChatMessage.user("same-user-text"),
                ChatMessage.system("suffix-A"));
        List<ChatMessage> suffixB = List.of(
                ChatMessage.user("same-user-text"),
                ChatMessage.system("suffix-B"));

        assertNotEquals(
                WirePartitionHasher.hashMessages(openAiWire(suffixA), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS),
                WirePartitionHasher.hashMessages(openAiWire(suffixB), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS));
    }

    @Test
    void reverseFixture_trailingSystemSuffix_projectionDiffers_onChatCompletions() {
        List<ChatMessage> base = baseMessages("{\"entityName\":\"Device.A\"}");
        List<ChatMessage> withSuffix = new java.util.ArrayList<>(base);
        withSuffix.add(ChatMessage.system("ephemeral-time-suffix"));

        assertNotEquals(
                WirePartitionHasher.hashMessages(openAiWire(base), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS),
                WirePartitionHasher.hashMessages(openAiWire(withSuffix),
                        StructuredMessageProjection.WireKind.CHAT_COMPLETIONS));
    }

    @Test
    void reverseFixture_cacheControlMoveOnly_projectionSame_onBothWires() {
        List<ChatMessage> messages = baseMessages("{\"entityName\":\"Device.A\"}");
        Map<String, Object> openAi = openAiWire(messages);
        Map<String, Object> anthropic = anthropicWire(messages);

        Map<String, Object> openAiMarked = deepCopy(openAi);
        markFirstTextBlock(openAiMarked, StructuredMessageProjection.WireKind.CHAT_COMPLETIONS);
        Map<String, Object> anthropicMarked = deepCopy(anthropic);
        markFirstTextBlock(anthropicMarked, StructuredMessageProjection.WireKind.ANTHROPIC);

        assertEquals(
                WirePartitionHasher.hashMessages(openAi, StructuredMessageProjection.WireKind.CHAT_COMPLETIONS),
                WirePartitionHasher.hashMessages(openAiMarked, StructuredMessageProjection.WireKind.CHAT_COMPLETIONS));
        assertEquals(
                WirePartitionHasher.hashMessages(anthropic, StructuredMessageProjection.WireKind.ANTHROPIC),
                WirePartitionHasher.hashMessages(anthropicMarked, StructuredMessageProjection.WireKind.ANTHROPIC));
    }

    private static List<ChatMessage> baseMessages(String toolArguments) {
        return List.of(
                ChatMessage.system("stable-system"),
                ChatMessage.user("same-user-text"),
                ChatMessage.assistantWithToolCalls(List.of(new ToolCall("call-1", "invoke_service", toolArguments))),
                ChatMessage.toolResult("call-1", "{\"status\":\"success\"}"));
    }

    private static List<ChatMessage> pairingMessages(String callId, String resultId) {
        return List.of(
                ChatMessage.system("stable-system"),
                ChatMessage.user("same-user-text"),
                ChatMessage.assistantWithToolCalls(List.of(new ToolCall(callId, "invoke_service", "{}"))),
                ChatMessage.toolResult(resultId, "{\"status\":\"success\"}"));
    }

    private static Map<String, Object> openAiWire(List<ChatMessage> messages) {
        LlmChatRequest request = LlmChatRequest.forAgentRound(messages, TOOLS, 0.0, 256, null);
        return ChatCompletionsApi.buildRequestBody("gpt-4o", messages, TOOLS, request, false, 256);
    }

    private static Map<String, Object> anthropicWire(List<ChatMessage> messages) {
        LlmChatRequest request = LlmChatRequest.copyWithCacheControl(
                LlmChatRequest.forAgentRound(messages, TOOLS, 0.0, 256, null), true);
        return AnthropicMessagesApi.buildRequestPayload(messages, TOOLS, "claude", 0.0, 256, 0, request);
    }

    @SuppressWarnings("unchecked")
    private static void markFirstTextBlock(Map<String, Object> wire, StructuredMessageProjection.WireKind kind) {
        Map<String, Object> cacheControl = new LinkedHashMap<>();
        cacheControl.put("type", "ephemeral");
        if (kind == StructuredMessageProjection.WireKind.ANTHROPIC) {
            List<Map<String, Object>> rows = (List<Map<String, Object>>) wire.get("messages");
            Map<String, Object> firstUser = rows.get(0);
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) firstUser.get("content");
            blocks.get(0).put("cache_control", cacheControl);
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) wire.get("messages");
        for (Map<String, Object> row : rows) {
            if ("user".equals(row.get("role")) && row.get("content") instanceof String) {
                row.put("cache_control", cacheControl);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> copy = new HashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?>) {
            List<Object> copy = new java.util.ArrayList<>();
            for (Object item : (List<?>) value) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return value;
    }
}
