package com.thingworx.things.agent.llm;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict JSON Schema checks for LLM tool parameters (OpenAI / Azure Chat Completions and similar).
 * <p>
 * Azure returns {@code 400 invalid_function_parameters} when an array schema omits {@code items}
 * (see ApplicationLog: {@code array schema missing items}).
 * <p>
 * Root-level {@code oneOf} / {@code anyOf} / {@code allOf} can cause provider-side tool-request
 * rejection. Prefer a single
 * {@code type: object} root and enforce mutual exclusion in description + executor.
 * <p>
 * TODO: optional walker pass for {@code additionalProperties: false} consistency on object nodes (future gate;
 * tracked from cached-table decision tools review follow-up A1).
 */
public final class LlmJsonSchemaCompat {

    private static final Set<String> FORBIDDEN_ROOT_COMBINATORS = Set.of("oneOf", "anyOf", "allOf");

    private LlmJsonSchemaCompat() {}

    /**
     * Full provider-safety check for an advertised tool parameters schema.
     */
    public static void assertCompatible(String toolName, Map<String, Object> schema) {
        assertNoRootSchemaCombinators(toolName, schema);
        assertArraysDeclareItems(toolName, schema);
    }

    /**
     * Fails when the parameters root carries {@code oneOf}/{@code anyOf}/{@code allOf}.
     * Nested combinators under {@code properties} are not scanned here.
     */
    public static void assertNoRootSchemaCombinators(String toolName, Map<String, Object> schema) {
        if (schema == null) {
            throw new IllegalArgumentException(
                    "LLM JSON Schema: tools[" + toolName + "].function.parameters must be a non-null object map");
        }
        for (String key : FORBIDDEN_ROOT_COMBINATORS) {
            if (schema.containsKey(key)) {
                throw new IllegalArgumentException(
                        "LLM JSON Schema: root \"" + key + "\" at tools[" + toolName
                                + "].function.parameters can cause provider-side tool request rejection; "
                                + "use a single type:object root and enforce XOR in description + executor");
            }
        }
    }

    /**
     * Walks a JSON Schema value map and throws if any node declares {@code type: array} (or a list type that includes
     * {@code array}) without {@code items} or {@code prefixItems}.
     *
     * @param toolName logical tool name (for error messages only)
     * @param schema    root parameters schema (typically {@code type: object})
     */
    public static void assertArraysDeclareItems(String toolName, Map<String, Object> schema) {
        walk(schema, "tools[" + toolName + "].function.parameters");
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object node, String path) {
        if (node == null) {
            return;
        }
        if (node instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) node;
            if (declaresArrayType(m) && !m.containsKey("items") && !m.containsKey("prefixItems")) {
                throw new IllegalArgumentException(
                        "LLM JSON Schema: array at " + path + " must declare \"items\" or \"prefixItems\" (strict providers).");
            }
            for (Map.Entry<String, Object> e : m.entrySet()) {
                walk(e.getValue(), path + "/" + e.getKey());
            }
        } else if (node instanceof List) {
            List<?> list = (List<?>) node;
            for (int i = 0; i < list.size(); i++) {
                walk(list.get(i), path + "[" + i + "]");
            }
        }
    }

    private static boolean declaresArrayType(Map<String, Object> m) {
        Object t = m.get("type");
        if ("array".equals(t)) {
            return true;
        }
        if (t instanceof List) {
            for (Object x : (List<?>) t) {
                if ("array".equals(x)) {
                    return true;
                }
            }
        }
        return false;
    }
}
