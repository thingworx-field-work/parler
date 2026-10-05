package com.thingworx.things.agent.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Single source of truth for measuring how many characters the advertised tool schemas occupy on an LLM request,
 * in the same wire shape the provider receives. Used both by {@code ContextBudgetPlanner} (per-round total, the
 * budget-bearing {@code toolSchemaChars}) and by {@code LlmUsageTelemetry} (per-tool breakdown for
 * {@code LLM_TOOL_SCHEMA_USAGE}). Keeping one sizer guarantees the per-tool sizes reconcile against the total
 * within array framing (M1 of {@code docs/operations/tool-schema-admission-control.md}).
 */
public final class ToolSchemaSizer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolSchemaSizer() {}

    private static boolean isAnthropic(String apiShapeId) {
        return apiShapeId != null && apiShapeId.toLowerCase(Locale.ROOT).contains("anthropic");
    }

    /**
     * Total characters of the serialized {@code tools} array, including array framing and (for Anthropic) the single
     * trailing {@code cache_control} marker — identical to what the context-budget planner charges as
     * {@code toolSchemaChars}.
     */
    public static int totalSchemaChars(String apiShapeId, List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        try {
            List<Map<String, Object>> wire = isAnthropic(apiShapeId)
                    ? AnthropicMessagesApi.toolsWireMapsForBudget(tools)
                    : ChatCompletionsApi.toolsWireMapsForBudget(tools);
            return JSON.writeValueAsString(wire).length();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Per-tool serialized size keyed by tool name, in definition order (cache_control excluded so sizes are
     * comparable across positions). Duplicate names are summed. The sum reconciles to {@link #totalSchemaChars}
     * minus array framing and the one {@code cache_control}.
     */
    public static LinkedHashMap<String, Integer> perToolSchemaChars(String apiShapeId, List<ToolDefinition> tools) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        if (tools == null || tools.isEmpty()) {
            return out;
        }
        boolean anthropic = isAnthropic(apiShapeId);
        for (ToolDefinition t : tools) {
            if (t == null) {
                continue;
            }
            String name = t.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            int chars;
            try {
                Map<String, Object> wire = anthropic
                        ? AnthropicMessagesApi.toolWireMapForBudget(t)
                        : ChatCompletionsApi.toolWireMapForBudget(t);
                chars = JSON.writeValueAsString(wire).length();
            } catch (Exception e) {
                chars = 0;
            }
            out.merge(name, chars, Integer::sum);
        }
        return out;
    }

    /** Sum of {@link #perToolSchemaChars} values. */
    public static int perToolSchemaCharsSum(String apiShapeId, List<ToolDefinition> tools) {
        int sum = 0;
        for (int v : perToolSchemaChars(apiShapeId, tools).values()) {
            sum += v;
        }
        return sum;
    }
}
