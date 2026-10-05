package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;

import org.junit.jupiter.api.Test;

class LlmUsageTelemetryToolSchemaTest {

    private static final String ANTHROPIC = "anthropic-messages-v1";

    private static LlmUsageWireIds ids() {
        return LlmUsageWireIds.forProviderThing("Prov", "Tmpl", ANTHROPIC, "claude");
    }

    private static ToolDefinition tool(String name, String desc, int schemaPad) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "string");
        field.put("description", "d".repeat(schemaPad));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("arg", field);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        return new ToolDefinition(name, desc, schema);
    }

    @Test
    void toolNamesFromDefinitions_sortsAndSkipsNulls() {
        List<ToolDefinition> defs = Arrays.asList(
                new ToolDefinition("zebra", "z", Map.of()),
                new ToolDefinition("alpha", "a", Map.of()),
                null,
                new ToolDefinition("", "e", Map.of()));
        NavigableSet<String> n = LlmUsageTelemetry.toolNamesFromDefinitions(defs);
        assertEquals("alpha,zebra", String.join(",", n));
    }

    @Test
    void idleTools_are_schema_minus_called() {
        List<ToolDefinition> defs = Arrays.asList(
                new ToolDefinition("a", "", Map.of()),
                new ToolDefinition("b", "", Map.of()),
                new ToolDefinition("c", "", Map.of()));
        List<ToolCall> calls = Collections.singletonList(new ToolCall("1", "b", "{}"));
        String preview = LlmUsageTelemetry.previewToolSchemaUsageLists(defs, calls);
        assertTrue(preview.contains("schemaTools=a,b,c"));
        assertTrue(preview.contains("calledTools=b"));
        assertTrue(preview.contains("idleTools=a,c"));
    }

    @Test
    void emptyCalls_yields_allIdle() {
        List<ToolDefinition> defs = Arrays.asList(new ToolDefinition("x", "", Map.of()));
        String preview = LlmUsageTelemetry.previewToolSchemaUsageLists(defs, Collections.emptyList());
        assertTrue(preview.contains("calledTools="));
        assertTrue(preview.contains("idleTools=x"));
    }

    // ---- M1: per-tool schema-size telemetry (docs/operations/tool-schema-admission-control.md) ----

    @Test
    void line_emitsToolSchemaCharsMatchingSizerTotal() {
        List<ToolDefinition> defs = Arrays.asList(
                tool("big", "a big tool", 400),
                tool("small", "s", 1));
        String line = LlmUsageTelemetry.formatToolSchemaUsageLine(ids(), 1, defs, Collections.emptyList(), "rid");
        int expectedTotal = ToolSchemaSizer.totalSchemaChars(ANTHROPIC, defs);
        assertTrue(line.contains(" toolSchemaChars=" + expectedTotal + " "),
                "toolSchemaChars must equal the planner sizer total; line=" + line);
    }

    @Test
    void line_perToolSizesSortedBySizeDescAndReconcileWithFraming() {
        List<ToolDefinition> defs = Arrays.asList(
                tool("small", "s", 1),
                tool("big", "a big tool", 400),
                tool("mid", "mid", 80));
        String line = LlmUsageTelemetry.formatToolSchemaUsageLine(ids(), 1, defs, Collections.emptyList(), "rid");

        // Largest schema first in toolSchemaSizes.
        int big = line.indexOf("big:");
        int mid = line.indexOf("mid:");
        int small = line.indexOf("small:");
        assertTrue(big > 0 && mid > big && small > mid,
                "toolSchemaSizes must be ordered by descending chars (big,mid,small); line=" + line);

        int total = parseIntField(line, "toolSchemaChars=");
        int sum = parseIntField(line, "toolSchemaSizesSum=");
        int framing = parseIntField(line, "toolSchemaFramingChars=");
        assertEquals(total - sum, framing, "framing must equal total - sum");
        assertTrue(framing > 0 && framing <= defs.size() + 1 + 64, "framing must be small and positive: " + framing);
    }

    @Test
    void joinSizesBySizeDesc_ordersByCharsThenName() {
        LinkedHashMap<String, Integer> perTool = new LinkedHashMap<>();
        perTool.put("b", 10);
        perTool.put("a", 10);
        perTool.put("z", 99);
        assertEquals("z:99,a:10,b:10", LlmUsageTelemetry.joinSizesBySizeDesc(perTool));
    }

    private static int parseIntField(String line, String key) {
        int i = line.indexOf(key);
        assertTrue(i >= 0, "missing field " + key + " in: " + line);
        int start = i + key.length();
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
            end++;
        }
        return Integer.parseInt(line.substring(start, end));
    }
}
