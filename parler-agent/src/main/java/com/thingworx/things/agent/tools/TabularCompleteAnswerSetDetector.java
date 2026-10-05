package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Detects strict complete-answer semantics from cached-tabular tool JSON ({@code docs/agent/query-spec.md} §11).
 */
public final class TabularCompleteAnswerSetDetector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TabularCompleteAnswerSetDetector() {}

    /**
     * @param toolName built-in function name (e.g. {@code tabulate_cached_result})
     * @param resultJson tool result body
     */
    public static boolean isCompleteAnswerSet(String toolName, String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return false;
        }
        if (!"tabulate_cached_result".equals(toolName)) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(resultJson);
            return tabulateSuccessJsonCompleteEnough(root);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Completeness for {@code tabulate_cached_result} success JSON only (same rules as {@link #isCompleteAnswerSet}
     * without the tool-name guard). Used by chart-orchestration hooks.
     */
    public static boolean tabulateSuccessJsonCompleteEnough(JsonNode root) {
        if (root == null) {
            return false;
        }
        if (!"success".equals(root.path("status").asText())) {
            return false;
        }
        if (root.path("answerSetComplete").asBoolean(false)) {
            return true;
        }
        boolean sampleOnly = root.path("sampleOnly").asBoolean(true);
        boolean rowsOmitted = root.path("rowsOmitted").asBoolean(true);
        int returned = root.path("returnedRows").asInt(-1);
        int total = root.path("totalRows").asInt(-2);
        return !sampleOnly && !rowsOmitted && returned >= 0 && returned == total;
    }
}
