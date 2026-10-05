package com.thingworx.things.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema root for {@code analyze_entity_set} tool arguments (LLM provider strict mode).
 *
 * <p>B18: {@code operation} is a JSON-Schema string enum with bidirectional executor/contract parity
 * ({@link #OPERATIONS}).
 */
public final class AnalyzeEntitySetToolSchema {

    /**
     * Advertised and executor-accepted {@code operation} values, in schema enum order.
     * Keep in lockstep with {@code CONTRACTS/ENTITY_SET_TOOL.md}.
     */
    public static final List<String> OPERATIONS = List.of(
            "difference", "intersection", "union", "symmetric_difference");

    private static final Set<String> OPERATION_SET = Set.copyOf(OPERATIONS);

    private AnalyzeEntitySetToolSchema() {}

    /** True when {@code op} is a supported set operation (already lower-cased / trimmed). */
    public static boolean isSupportedOperation(String op) {
        return op != null && OPERATION_SET.contains(op);
    }

    public static Map<String, Object> parametersSchema() {
        Map<String, Object> operandProps = new LinkedHashMap<>();
        operandProps.put("cacheId", Map.of("type", "string",
                "description", "Explicit conversation cache id from a prior list/tabular tool."));
        operandProps.put("keyColumn", Map.of("type", "string",
                "description", "Identity column on this operand (default **name**)."));
        operandProps.put("label", Map.of("type", "string", "description", "Optional diagnostic label."));
        Map<String, Object> operandSchema = new LinkedHashMap<>();
        operandSchema.put("type", "object");
        operandSchema.put("properties", operandProps);
        operandSchema.put("required", new String[] {"cacheId"});
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "string");
        operation.put("enum", OPERATIONS);
        operation.put("description", "Set operation: **difference**, **intersection**, **union**, or "
                + "**symmetric_difference**.");
        props.put("operation", operation);
        props.put("left", operandSchema);
        props.put("right", operandSchema);
        props.put("projectColumns", Map.of(
                "type", "array",
                "description", "Optional projection. For **difference** / **intersection**, names columns on the **left** operand only. "
                        + "For **union** / **symmetric_difference**, each name must exist on **at least one** operand; missing cells are **null** for keys present on only one side.",
                "items", Map.of("type", "string")));
        props.put("maxItems", Map.of("type", "integer", "description", "Page size into output rows (default 50, max 500)."));
        props.put("offset", Map.of("type", "integer", "description", "Zero-based offset into output rows."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[] {"operation", "left", "right"});
        return schema;
    }
}
