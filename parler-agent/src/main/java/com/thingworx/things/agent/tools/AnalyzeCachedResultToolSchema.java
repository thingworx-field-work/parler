package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.thingworx.things.agent.analysis.U5OperationAdmission;

/**
 * JSON parameters schema for the conditional {@code analyze_cached_result} tool (DIK-5 / D9).
 * One resident tool with an {@code operation} enum — not three G1/G2/G4 tools. Handle inputs only
 * (§8.2): no row/value arrays in the model-visible surface.
 */
public final class AnalyzeCachedResultToolSchema {

    public static final String TOOL_NAME = "analyze_cached_result";

    public static final List<String> OPERATIONS = List.of(
            "outlier",
            "change_point",
            "spc",
            "relationship",
            "trend",
            "threshold_crossing");

    private AnalyzeCachedResultToolSchema() {}

    public static boolean advertised() {
        return U5OperationAdmission.analyzeEnabled();
    }

    public static Map<String, Object> parametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("operation", Map.of(
                "type", "string",
                "enum", new ArrayList<>(OPERATIONS),
                "description",
                "U5 analysis operation: outlier | change_point | spc | relationship | trend | threshold_crossing."));
        props.put("methodId", Map.of(
                "type", "string",
                "description",
                "Optional versioned method id (e.g. robust_z, pearson, ols_trend). Defaults per operation."));
        props.put("cacheId", Map.of(
                "type", "string",
                "description",
                "Conversation cache handle for the primary series (handle-only; never pass rows)."));
        props.put("rightCacheId", Map.of(
                "type", "string",
                "description",
                "Right-side cache handle for relationship (required when operation=relationship)."));
        props.put("timeColumn", Map.of("type", "string", "description",
                "Timestamp column name. Use the exact column name declared by that cache's result "
                        + "(`columns[]`, and `timeColumn` when the result declares it); do not infer it from a property label."));
        props.put("valueColumn", Map.of("type", "string", "description",
                "Numeric value column name. Use the exact column name declared by that cache's result "
                        + "(`columns[]`, and `valueColumn` when the result declares it); do not infer it from a property label."));
        props.put("rightValueColumn", Map.of(
                "type", "string",
                "description",
                "Right-side value column for relationship (defaults to valueColumn)."));
        props.put("alignment", Map.of(
                "type", "string",
                "enum", List.of("exact", "nearest"),
                "description",
                "Relationship alignment mode. Default exact."));
        props.put("toleranceMillis", Map.of(
                "type", "integer",
                "description",
                "Nearest-alignment tolerance in milliseconds (required when alignment=nearest)."));
        props.put("threshold", Map.of(
                "type", "number",
                "description",
                "Threshold value for threshold_crossing."));
        props.put("horizonSeconds", Map.of(
                "type", "number",
                "description",
                "Approved forecast horizon in seconds for threshold_crossing."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("operation", "cacheId"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
