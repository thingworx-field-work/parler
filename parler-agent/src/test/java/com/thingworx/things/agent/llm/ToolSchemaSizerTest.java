package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * M1 (docs/operations/tool-schema-admission-control.md): the per-tool schema sizes used by
 * {@code LLM_TOOL_SCHEMA_USAGE} must reconcile against the single budget-bearing total the planner charges, within
 * array framing.
 */
class ToolSchemaSizerTest {

    private static ToolDefinition tool(String name, String desc, Map<String, Object> schema) {
        return new ToolDefinition(name, desc, schema);
    }

    private static List<ToolDefinition> sampleTools(int n) {
        List<ToolDefinition> defs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> props = new LinkedHashMap<>();
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("type", "string");
            field.put("description", "field " + i + " description text of varying length " + "x".repeat(i * 7));
            props.put("arg" + i, field);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            defs.add(tool("tool_" + i, "description for tool " + i + " " + "y".repeat(i * 5), schema));
        }
        return defs;
    }

    @Test
    void chatCompletions_perToolSizesReconcileWithTotal_exactFraming() {
        String apiShape = "openai-chat-completions-v5";
        List<ToolDefinition> defs = sampleTools(4);
        int total = ToolSchemaSizer.totalSchemaChars(apiShape, defs);
        int sum = ToolSchemaSizer.perToolSchemaCharsSum(apiShape, defs);
        int framing = total - sum;
        // Chat Completions has no cache_control: framing is exactly the JSON array brackets + element commas.
        assertEquals(defs.size() + 1, framing,
                "framing should equal 2 brackets + (n-1) commas for the Chat Completions wire shape");
        assertTrue(sum > 0 && total > sum);
    }

    @Test
    void anthropic_perToolSizesReconcileWithTotal_smallPositiveFraming() {
        String apiShape = "anthropic-messages-v1";
        List<ToolDefinition> defs = sampleTools(4);
        int total = ToolSchemaSizer.totalSchemaChars(apiShape, defs);
        int sum = ToolSchemaSizer.perToolSchemaCharsSum(apiShape, defs);
        int framing = total - sum;
        // Anthropic adds brackets + commas + exactly one trailing cache_control marker; the delta stays small.
        assertTrue(framing >= defs.size() + 1, "framing must cover array brackets + commas: " + framing);
        assertTrue(framing <= defs.size() + 1 + 64, "framing must stay small (one cache_control marker): " + framing);
        assertTrue(sum > 0 && total > sum);
    }

    @Test
    void perToolSizes_areNonEmptyAndKeyedByName() {
        LinkedHashMap<String, Integer> perTool =
                ToolSchemaSizer.perToolSchemaChars("anthropic-messages-v1", sampleTools(3));
        assertEquals(3, perTool.size());
        assertTrue(perTool.containsKey("tool_0"));
        for (int chars : perTool.values()) {
            assertTrue(chars > 0);
        }
    }

    @Test
    void emptyOrNullTools_yieldZero() {
        assertEquals(0, ToolSchemaSizer.totalSchemaChars("anthropic-messages-v1", null));
        assertEquals(0, ToolSchemaSizer.perToolSchemaCharsSum("anthropic-messages-v1", List.of()));
        assertTrue(ToolSchemaSizer.perToolSchemaChars("anthropic-messages-v1", null).isEmpty());
    }
}
