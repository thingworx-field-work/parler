package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure Chat Completions request-body helpers (JUnit-friendly; no HTTP).
 */
public final class ChatCompletionsApi {

    private ChatCompletionsApi() {
    }

    public static Map<String, Object> buildRequestBody(
            String model,
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            LlmChatRequest request,
            boolean useMaxCompletionTokens,
            int maxTok) {
        return buildRequestBodyWithDiagnostics(
                model, messages, tools, request, useMaxCompletionTokens, maxTok).getBody();
    }

    public static RequestBodyResult buildRequestBodyWithDiagnostics(
            String model,
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            LlmChatRequest request,
            boolean useMaxCompletionTokens,
            int maxTok) {
        String maxTokenField = useMaxCompletionTokens ? "max_completion_tokens" : "max_tokens";
        Map<String, Object> body = new HashMap<>();
        ChatCompletionsApiMessages.MessagesResult serialized =
                ChatCompletionsApiMessages.toApiMessagesWithDiagnostics(messages);
        body.put("model", model);
        body.put("messages", serialized.getMessages());
        body.put(maxTokenField, maxTok);
        if (!useMaxCompletionTokens) {
            body.put("temperature", Math.max(0.0, Math.min(2.0, request.getTemperature())));
        }
        String reasoning = request.getReasoningEffort();
        if (reasoning != null && !reasoning.isBlank()) {
            body.put("reasoning_effort", reasoning.trim());
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", toApiTools(tools));
            if (request.isToolChoiceNone()) {
                body.put("tool_choice", "none");
            }
        }
        return new RequestBodyResult(body, serialized.getDiagnostics());
    }

    public static final class RequestBodyResult {
        private final Map<String, Object> body;
        private final SuffixClassificationDiagnostics diagnostics;

        private RequestBodyResult(Map<String, Object> body, SuffixClassificationDiagnostics diagnostics) {
            this.body = body;
            this.diagnostics = diagnostics;
        }

        public Map<String, Object> getBody() {
            return body;
        }

        public SuffixClassificationDiagnostics getDiagnostics() {
            return diagnostics;
        }
    }

    /**
     * Tools array as serialized on Chat Completions requests, for {@code LLM_CONTEXT_PLAN} / context budget telemetry
     * ({@code docs/agent/context-compaction.md} Slice A).
     */
    public static List<Map<String, Object>> toolsWireMapsForBudget(List<ToolDefinition> tools) {
        return toApiTools(tools);
    }

    /**
     * Single tool object as serialized on Chat Completions requests, for per-tool schema-size telemetry
     * ({@code LLM_TOOL_SCHEMA_USAGE}). The sum of per-tool sizes equals {@link #toolsWireMapsForBudget} minus
     * array framing.
     */
    public static Map<String, Object> toolWireMapForBudget(ToolDefinition t) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        Map<String, Object> fn = new HashMap<>();
        fn.put("name", t.getName());
        fn.put("description", t.getDescription() != null ? t.getDescription() : "");
        fn.put("parameters", t.getParametersSchema() != null ? t.getParametersSchema() : new HashMap<String, Object>());
        tool.put("function", fn);
        return tool;
    }

    private static List<Map<String, Object>> toApiTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolDefinition t : tools) {
            out.add(toolWireMapForBudget(t));
        }
        return out;
    }
}
