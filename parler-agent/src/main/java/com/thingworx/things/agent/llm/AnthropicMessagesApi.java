package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure Anthropic Messages request/response helpers (no static ThingWorx logger) so JUnit can assert
 * serialization and usage parsing without platform logback init.
 */
public final class AnthropicMessagesApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AnthropicMessagesApi() {}

    public static Map<String, Object> buildRequestPayload(List<ChatMessage> messages, List<ToolDefinition> tools,
            String model, double temperature, int maxTokens) {
        return buildRequestPayload(messages, tools, model, temperature, maxTokens, 0, (LlmChatRequest) null);
    }

    public static Map<String, Object> buildRequestPayload(List<ChatMessage> messages, List<ToolDefinition> tools,
            String model, double temperature, int maxTokens, int thinkingBudgetTokens) {
        return buildRequestPayload(
                messages, tools, model, temperature, maxTokens, thinkingBudgetTokens, (LlmChatRequest) null);
    }

    public static Map<String, Object> buildRequestPayload(List<ChatMessage> messages, List<ToolDefinition> tools,
            String model, double temperature, int maxTokens, int thinkingBudgetTokens,
            AnthropicSamplingParametersMode samplingParametersMode) {
        return buildRequestPayload(
                messages, tools, model, temperature, maxTokens, thinkingBudgetTokens, null, samplingParametersMode);
    }

    /**
     * @param toolPolicy when non-null, {@link LlmChatRequest#isToolChoiceNone()} adds Anthropic {@code tool_choice}
     *        {@code none} <strong>only when</strong> {@code tools} is non-empty (OpenAI/Azure reject
     *        {@code tool_choice} without a {@code tools} array; Anthropic follows the same omission rule here).
     */
    public static Map<String, Object> buildRequestPayload(List<ChatMessage> messages, List<ToolDefinition> tools,
            String model, double temperature, int maxTokens, int thinkingBudgetTokens, LlmChatRequest toolPolicy) {
        return buildRequestPayload(messages, tools, model, temperature, maxTokens, thinkingBudgetTokens, toolPolicy,
                AnthropicSamplingParametersMode.legacy);
    }

    public static Map<String, Object> buildRequestPayload(List<ChatMessage> messages, List<ToolDefinition> tools,
            String model, double temperature, int maxTokens, int thinkingBudgetTokens, LlmChatRequest toolPolicy,
            AnthropicSamplingParametersMode samplingParametersMode) {
        return buildRequestPayloadWithDiagnostics(messages, tools, model, temperature, maxTokens,
                thinkingBudgetTokens, toolPolicy, samplingParametersMode).getPayload();
    }

    public static RequestPayloadResult buildRequestPayloadWithDiagnostics(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            String model,
            double temperature,
            int maxTokens,
            int thinkingBudgetTokens,
            LlmChatRequest toolPolicy,
            AnthropicSamplingParametersMode samplingParametersMode) {
        int maxOut = Math.max(1, maxTokens);
        if (thinkingBudgetTokens > 0) {
            if (thinkingBudgetTokens < 1024) {
                throw new IllegalArgumentException(
                        "thinkingBudgetTokens must be >= 1024 when positive (got " + thinkingBudgetTokens
                                + "); Anthropic minimum budget is 1024.");
            }
            if (thinkingBudgetTokens >= maxOut) {
                throw new IllegalArgumentException(
                        "thinkingBudgetTokens must be less than max_tokens (thinking=" + thinkingBudgetTokens
                                + ", max_tokens=" + maxOut + ")");
            }
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxOut);
        AnthropicSamplingParametersMode mode = samplingParametersMode != null
                ? samplingParametersMode
                : AnthropicSamplingParametersMode.legacy;
        if (mode == AnthropicSamplingParametersMode.legacy && thinkingBudgetTokens == 0) {
            body.put("temperature", Math.max(0.0, Math.min(1.0, temperature)));
        }
        if (thinkingBudgetTokens > 0) {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", thinkingBudgetTokens);
            body.put("thinking", thinking);
        }
        putAnthropicSystemField(body, messages);
        boolean enableHistoryMarkers = toolPolicy != null && toolPolicy.isEnableCacheControl();
        body.put("messages", buildAnthropicMessages(messages, enableHistoryMarkers));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", toAnthropicToolsWithCacheOnLast(tools));
            if (toolPolicy != null && toolPolicy.isToolChoiceNone()) {
                Map<String, Object> tc = new HashMap<>();
                tc.put("type", "none");
                body.put("tool_choice", tc);
            }
        }
        return new RequestPayloadResult(body, SuffixClassificationDiagnostics.fromPlannedMessages(messages));
    }

    private static void putAnthropicSystemField(Map<String, Object> body, List<ChatMessage> messages) {
        List<Map<String, Object>> systemBlocks = new ArrayList<>();
        if (messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage m = messages.get(i);
                if (m.getRole() != ChatMessage.Role.SYSTEM) {
                    continue;
                }
                String c = m.getContent();
                if (c == null || c.isEmpty()) {
                    continue;
                }
                if (isClassifiedSuffix(messages, i)) {
                    continue;
                }
                boolean cacheFirstStable = i == 0 && LeadingSystemRow.isStableFirstSystemRow(messages);
                systemBlocks.add(systemTextBlock(c, cacheFirstStable));
            }
        }
        if (systemBlocks.isEmpty()) {
            return;
        }
        if (systemBlocks.size() == 1 && !systemBlocks.get(0).containsKey("cache_control")) {
            body.put("system", systemBlocks.get(0).get("text"));
        } else {
            body.put("system", systemBlocks);
        }
    }

    private static Map<String, Object> systemTextBlock(String text, boolean cacheEphemeral) {
        Map<String, Object> b = new HashMap<>();
        b.put("type", "text");
        b.put("text", text != null ? text : "");
        if (cacheEphemeral) {
            Map<String, Object> cc = new HashMap<>();
            cc.put("type", "ephemeral");
            b.put("cache_control", cc);
        }
        return b;
    }

    public static List<Map<String, Object>> buildAnthropicMessages(List<ChatMessage> messages) {
        return buildAnthropicMessages(messages, false);
    }

    static List<Map<String, Object>> buildAnthropicMessages(
            List<ChatMessage> messages,
            boolean enableHistoryMarkers) {
        List<Map<String, Object>> apiMessages = new ArrayList<>();
        if (messages == null) {
            return apiMessages;
        }
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.USER
                    && (message.getContent() == null || message.getContent().trim().isEmpty())) {
                throw new IllegalArgumentException("Anthropic user text content must be non-empty");
            }
        }
        List<ChatMessage> rest = new ArrayList<>();
        List<ChatMessage> suffix = new ArrayList<>();
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            ChatMessage m = messages.get(messageIndex);
            if (isClassifiedSuffix(messages, messageIndex)) {
                suffix.add(m);
            } else if (m.getRole() != ChatMessage.Role.SYSTEM) {
                rest.add(m);
            }
        }
        List<Map<String, Object>> frontierCandidates = new ArrayList<>();
        int i = 0;
        while (i < rest.size()) {
            ChatMessage m = rest.get(i);
            if (m.getRole() == ChatMessage.Role.TOOL) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                while (i < rest.size() && rest.get(i).getRole() == ChatMessage.Role.TOOL) {
                    ChatMessage t = rest.get(i);
                    blocks.add(toolResultBlock(t.getToolCallId(), t.getContent()));
                    i++;
                }
                Map<String, Object> row = new HashMap<>();
                row.put("role", "user");
                row.put("content", blocks);
                apiMessages.add(row);
                frontierCandidates.add(blocks.get(blocks.size() - 1));
                continue;
            }
            if (m.getRole() == ChatMessage.Role.USER) {
                Map<String, Object> row = new HashMap<>();
                row.put("role", "user");
                String content = m.getContent() != null ? m.getContent() : "";
                if (enableHistoryMarkers) {
                    Map<String, Object> textBlock = plainTextBlock(content);
                    List<Map<String, Object>> blocks = new ArrayList<>();
                    blocks.add(textBlock);
                    row.put("content", blocks);
                    // Anthropic cannot cache empty text blocks; preserve the true-kind array but skip the candidate.
                    if (!content.isEmpty()) {
                        frontierCandidates.add(textBlock);
                    }
                } else {
                    row.put("content", content);
                }
                apiMessages.add(row);
                i++;
                continue;
            }
            if (m.getRole() == ChatMessage.Role.ASSISTANT) {
                Map<String, Object> row = new HashMap<>();
                row.put("role", "assistant");
                if (m.hasToolCalls()) {
                    List<Map<String, Object>> blocks = new ArrayList<>();
                    if (m.getContent() != null && !m.getContent().isEmpty()) {
                        blocks.add(plainTextBlock(m.getContent()));
                    }
                    for (ToolCall tc : m.getToolCalls()) {
                        blocks.add(toolUseBlock(tc));
                    }
                    row.put("content", blocks);
                } else {
                    row.put("content", m.getContent() != null ? m.getContent() : "");
                }
                apiMessages.add(row);
                i++;
            } else {
                i++;
            }
        }
        appendClassifiedSuffix(apiMessages, suffix);
        if (enableHistoryMarkers && !frontierCandidates.isEmpty()) {
            addCacheControl(frontierCandidates.get(frontierCandidates.size() - 1));
            if (frontierCandidates.size() > 1) {
                addCacheControl(frontierCandidates.get(frontierCandidates.size() - 2));
            }
        }
        return apiMessages;
    }

    private static void addCacheControl(Map<String, Object> block) {
        Map<String, Object> cacheControl = new HashMap<>();
        cacheControl.put("type", "ephemeral");
        block.put("cache_control", cacheControl);
    }

    private static boolean isClassifiedSuffix(List<ChatMessage> messages, int index) {
        if (messages == null || index < 0 || index >= messages.size()) {
            return false;
        }
        ChatMessage m = messages.get(index);
        if (m.getRole() != ChatMessage.Role.SYSTEM || !ParlerSuffixFraming.isClassified(m.getContent())) {
            return false;
        }
        return index != 0 || !LeadingSystemRow.isStableFirstSystemRow(messages);
    }

    private static void appendClassifiedSuffix(List<Map<String, Object>> apiMessages, List<ChatMessage> suffix) {
        if (suffix.isEmpty()) {
            return;
        }
        List<Map<String, Object>> suffixBlocks = new ArrayList<>();
        for (ParlerSuffixFraming.AuthorityClass authorityClass : ParlerSuffixFraming.AuthorityClass.values()) {
            for (ChatMessage m : suffix) {
                if (ParlerSuffixFraming.classify(m.getContent()) == authorityClass) {
                    suffixBlocks.add(plainTextBlock(m.getContent()));
                }
            }
        }

        if (!apiMessages.isEmpty() && "user".equals(apiMessages.get(apiMessages.size() - 1).get("role"))) {
            Map<String, Object> last = apiMessages.get(apiMessages.size() - 1);
            Object existing = last.get("content");
            List<Map<String, Object>> blocks = new ArrayList<>();
            if (existing instanceof List<?>) {
                for (Object block : (List<?>) existing) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) block;
                    blocks.add(typed);
                }
            } else {
                blocks.add(plainTextBlock(existing != null ? existing.toString() : ""));
            }
            blocks.addAll(suffixBlocks);
            last.put("content", blocks);
            return;
        }

        Map<String, Object> carrier = new HashMap<>();
        carrier.put("role", "user");
        carrier.put("content", suffixBlocks);
        apiMessages.add(carrier);
    }

    private static Map<String, Object> plainTextBlock(String text) {
        Map<String, Object> b = new HashMap<>();
        b.put("type", "text");
        b.put("text", text != null ? text : "");
        return b;
    }

    private static Map<String, Object> toolUseBlock(ToolCall tc) {
        Map<String, Object> b = new HashMap<>();
        b.put("type", "tool_use");
        b.put("id", tc.getId());
        b.put("name", tc.getFunctionName());
        try {
            String raw = tc.getArguments() != null ? tc.getArguments() : "{}";
            JsonNode node = JSON.readTree(raw);
            @SuppressWarnings("unchecked")
            Map<String, Object> inputObj = JSON.convertValue(node, Map.class);
            b.put("input", inputObj != null ? inputObj : new HashMap<String, Object>());
        } catch (Exception e) {
            b.put("input", new HashMap<String, Object>());
        }
        return b;
    }

    private static Map<String, Object> toolResultBlock(String toolUseId, String content) {
        Map<String, Object> b = new HashMap<>();
        b.put("type", "tool_result");
        b.put("tool_use_id", toolUseId != null ? toolUseId : "");
        b.put("content", content != null ? content : "");
        return b;
    }

    /**
     * Tools array as serialized on Anthropic Messages requests (including {@code cache_control} on the last tool),
     * for {@code LLM_CONTEXT_PLAN} / context budget telemetry ({@code docs/agent/context-compaction.md} Slice A).
     */
    public static List<Map<String, Object>> toolsWireMapsForBudget(List<ToolDefinition> tools) {
        return toAnthropicToolsWithCacheOnLast(tools);
    }

    /**
     * Single tool object as serialized on Anthropic Messages requests, <strong>without</strong> the trailing-tool
     * {@code cache_control} marker, for per-tool schema-size telemetry ({@code LLM_TOOL_SCHEMA_USAGE}). The sum of
     * per-tool sizes equals {@link #toolsWireMapsForBudget} minus array framing and the one {@code cache_control}.
     */
    public static Map<String, Object> toolWireMapForBudget(ToolDefinition t) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", t.getName());
        tool.put("description", t.getDescription() != null ? t.getDescription() : "");
        Map<String, Object> schema = t.getParametersSchema() != null
                ? t.getParametersSchema()
                : new HashMap<String, Object>();
        tool.put("input_schema", schema);
        return tool;
    }

    private static List<Map<String, Object>> toAnthropicToolsWithCacheOnLast(List<ToolDefinition> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolDefinition t : tools) {
            out.add(toolWireMapForBudget(t));
        }
        if (!out.isEmpty()) {
            Map<String, Object> cc = new HashMap<>();
            cc.put("type", "ephemeral");
            out.get(out.size() - 1).put("cache_control", cc);
        }
        return out;
    }

    public static final class RequestPayloadResult {
        private final Map<String, Object> payload;
        private final SuffixClassificationDiagnostics diagnostics;

        private RequestPayloadResult(
                Map<String, Object> payload,
                SuffixClassificationDiagnostics diagnostics) {
            this.payload = payload;
            this.diagnostics = diagnostics;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }

        public SuffixClassificationDiagnostics getDiagnostics() {
            return diagnostics;
        }
    }

    public static LlmResponse parseResponse(String responseBody, String providerRequestId) throws Exception {
        JsonNode root = JSON.readTree(responseBody);
        StringBuilder textOut = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        int contentBlockCount = 0;
        int textBlockCount = 0;
        int toolUseBlockCount = 0;
        List<String> otherBlockTypes = new ArrayList<>();
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                contentBlockCount++;
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    textBlockCount++;
                    textOut.append(block.path("text").asText(""));
                } else if ("tool_use".equals(type)) {
                    toolUseBlockCount++;
                    String id = block.path("id").asText("");
                    String name = block.path("name").asText("");
                    JsonNode input = block.path("input");
                    String args = input.isMissingNode() || input.isNull() ? "{}"
                            : JSON.writeValueAsString(input);
                    toolCalls.add(new ToolCall(id, name, args));
                } else {
                    otherBlockTypes.add(type);
                }
            }
        }

        String stop = root.path("stop_reason").asText("end_turn");
        LlmResponse.FinishReason reason = LlmResponse.FinishReason.STOP;
        if ("tool_use".equals(stop)) {
            reason = LlmResponse.FinishReason.TOOL_CALLS;
        } else if ("max_tokens".equals(stop)) {
            reason = LlmResponse.FinishReason.LENGTH;
        }

        JsonNode usage = root.path("usage");
        int inputTok = usage.path("input_tokens").asInt(0);
        int outTok = usage.path("output_tokens").asInt(0);
        int cacheRead = usage.path("cache_read_input_tokens").asInt(0);
        int cacheCreate = usage.path("cache_creation_input_tokens").asInt(0);
        int promptAgg = inputTok + cacheRead + cacheCreate;

        LlmResponseShapeDiagnostics shape = new LlmResponseShapeDiagnostics(
                contentBlockCount, textBlockCount, toolUseBlockCount, otherBlockTypes);
        return new LlmResponse(textOut.toString(), toolCalls, reason, promptAgg, outTok,
                inputTok, outTok, cacheRead, cacheCreate, 0, providerRequestId, 0, 0L, shape);
    }
}
