package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses OpenAI-style chat completions JSON into {@link LlmResponse} including optional cached prompt tokens.
 */
public final class ChatCompletionsResponseParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatCompletionsResponseParser() {}

    public static LlmResponse parse(String responseBody, String providerRequestId) throws Exception {
        JsonNode root = JSON.readTree(responseBody);
        return parseRoot(root, providerRequestId);
    }

    static LlmResponse parseRoot(JsonNode root, String providerRequestId) throws Exception {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new RuntimeException(
                    "OpenAI-style response has no choices (empty or missing choices array; full body omitted from exception message)");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        String content = message.has("content") && !message.get("content").isNull()
                ? message.get("content").asText(null)
                : "";
        if (content == null) {
            content = "";
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode tcArray = message.path("tool_calls");
        if (tcArray.isArray()) {
            for (JsonNode tc : tcArray) {
                String id = tc.path("id").asText("");
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText("");
                String args = fn.path("arguments").asText("{}");
                toolCalls.add(new ToolCall(id, name, args));
            }
        }

        String finishReason = choice.has("finish_reason") ? choice.get("finish_reason").asText("stop") : "stop";
        LlmResponse.FinishReason reason = LlmResponse.FinishReason.STOP;
        if ("tool_calls".equals(finishReason)) {
            reason = LlmResponse.FinishReason.TOOL_CALLS;
        } else if ("length".equals(finishReason)) {
            reason = LlmResponse.FinishReason.LENGTH;
        }

        int promptTokens = 0;
        int completionTokens = 0;
        int cachedPromptTokens = 0;
        int reasoningTokens = 0;
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            promptTokens = usage.path("prompt_tokens").asInt(0);
            completionTokens = usage.path("completion_tokens").asInt(0);
            JsonNode promptDetails = usage.path("prompt_tokens_details");
            if (promptDetails.isObject()) {
                cachedPromptTokens = promptDetails.path("cached_tokens").asInt(0);
            }
            JsonNode completionDetails = usage.path("completion_tokens_details");
            if (completionDetails.isObject()) {
                reasoningTokens = completionDetails.path("reasoning_tokens").asInt(0);
            }
        }

        return new LlmResponse(content, toolCalls, reason, promptTokens, completionTokens,
                promptTokens, completionTokens, 0, 0, cachedPromptTokens, providerRequestId, reasoningTokens, 0L);
    }
}
