package com.thingworx.things.agent.compaction;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Counts CC-1.1 editable description characters for M1a baseline and acceptance.
 */
public final class M1aEditableDescriptionMetrics {

    public static final List<String> PRIORITY_TOOL_NAMES = List.of(
            "tabulate_cached_result",
            "query_entities",
            "build_chart_from_tabular_result",
            "invoke_service",
            "query_entities_by_taxonomy",
            "query_property_history",
            "build_history_overlay_chart");

    private M1aEditableDescriptionMetrics() {}

    public static int topLevelDescriptionChars(ToolDefinition tool) {
        if (tool == null || tool.getDescription() == null) {
            return 0;
        }
        return tool.getDescription().getBytes(StandardCharsets.UTF_8).length;
    }

    public static int parametersSchemaEditableDescriptionChars(Map<String, Object> parametersSchema) {
        if (parametersSchema == null || parametersSchema.isEmpty()) {
            return 0;
        }
        return M1aSchemaWalk.countEditableDescriptions(parametersSchema);
    }

    public static int toolSchemaEditableChars(ToolDefinition tool) {
        if (tool == null) {
            return 0;
        }
        return topLevelDescriptionChars(tool) + parametersSchemaEditableDescriptionChars(tool.getParametersSchema());
    }

    public static LinkedHashMap<String, Integer> perToolEditableChars(List<ToolDefinition> tools) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        if (tools == null) {
            return out;
        }
        for (ToolDefinition tool : tools) {
            if (tool == null || tool.getName() == null || tool.getName().isEmpty()) {
                continue;
            }
            out.merge(tool.getName(), toolSchemaEditableChars(tool), Integer::sum);
        }
        return out;
    }

    public static int sevenPriorityToolsEditableTotal(List<ToolDefinition> tools) {
        LinkedHashMap<String, Integer> perTool = perToolEditableChars(tools);
        int sum = 0;
        for (String name : PRIORITY_TOOL_NAMES) {
            sum += perTool.getOrDefault(name, 0);
        }
        return sum;
    }
}
