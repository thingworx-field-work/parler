package com.thingworx.things.agent.playbook;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/** Validator hooks for Slice E authoring derive ops. */
final class PlaybookAuthoringDeriveOpsValidator {

    private static final Set<String> ON_ZERO_MODES = Set.of("needs_clarification", "gap", "empty");
    private static final Set<String> ON_MULTIPLE_MODES = Set.of("needs_clarification", "gap", "first");

    private PlaybookAuthoringDeriveOpsValidator() {}

    static void validate(String op, JSONObject node, String nodeId, PlaybookDocument doc, PlaybookValidationCollector col) {
        if ("normalize_text".equals(op)) {
            validateNormalizeText(node, nodeId, col);
        } else if ("match_candidates".equals(op)) {
            validateMatchCandidates(node, nodeId, col);
        } else if ("dedupe".equals(op)) {
            validateDedupe(node, nodeId, col);
        } else if ("limit_rows".equals(op)) {
            validateLimitRows(node, nodeId, col);
        } else if ("format_evidence_lines".equals(op)) {
            validateFormatEvidenceLines(node, nodeId, col);
        }
    }

    private static void validateNormalizeText(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "normalize_text derive missing args: " + nodeId);
            return;
        }
        if (!args.has("text")) {
            col.node(nodeId, ".args.text", "normalize_text derive missing text: " + nodeId);
        }
        String mode = args.optString("mode", "identifier").trim();
        if (!mode.isEmpty() && !isNormalizeMode(mode)) {
            col.node(nodeId, ".args.mode", "INVALID_NORMALIZE_TEXT_MODE",
                    "normalize_text derive mode must be trim|lower|collapse_ws|identifier: " + nodeId,
                    normalizeModeHint());
        }
    }

    private static void validateMatchCandidates(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "match_candidates derive missing args: " + nodeId);
            return;
        }
        if (!args.has("needle")) {
            col.node(nodeId, ".args.needle", "match_candidates derive missing needle: " + nodeId);
        }
        if (!args.has("rows")) {
            col.node(nodeId, ".args.rows", "match_candidates derive missing rows: " + nodeId);
        }
        String field = args.optString("candidateField", "name").trim();
        if (!field.isEmpty() && !PlaybookGenericPathGrammar.isSingleSegmentField(field)) {
            col.node(nodeId, ".args.candidateField", "INVALID_MATCH_CANDIDATES_FIELD",
                    "match_candidates derive candidateField must be a single segment: " + nodeId,
                    singleSegmentFieldHint("candidateField"));
        }
        validatePickMode(args, nodeId, "onZero", ON_ZERO_MODES, col);
        validatePickMode(args, nodeId, "onMultiple", ON_MULTIPLE_MODES, col);
    }

    private static void validateDedupe(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "dedupe derive missing args: " + nodeId);
            return;
        }
        if (!args.has("rows")) {
            col.node(nodeId, ".args.rows", "dedupe derive missing rows: " + nodeId);
        }
        JSONArray keys = args.optJSONArray("keys");
        if (keys == null || keys.length() == 0) {
            col.node(nodeId, ".args.keys", "INVALID_DEDUPE_KEYS",
                    "dedupe derive keys must be a non-empty array: " + nodeId, dedupeKeysHint());
            return;
        }
        Set<String> keySet = new HashSet<>();
        for (int i = 0; i < keys.length(); i++) {
            Object raw = keys.get(i);
            if (!(raw instanceof String)) {
                col.node(nodeId, ".args.keys[" + i + "]", "dedupe derive keys[" + i + "] must be a JSON string: " + nodeId);
                continue;
            }
            String k = ((String) raw).trim();
            if (k.isEmpty()) {
                col.node(nodeId, ".args.keys[" + i + "]", "dedupe derive keys[" + i + "] must be a non-blank string: " + nodeId);
                continue;
            }
            if (!PlaybookGenericPathGrammar.isSingleSegmentField(k)) {
                col.node(nodeId, ".args.keys[" + i + "]", "dedupe derive keys[" + i + "] must be a single identifier segment (no dots): "
                        + nodeId);
            }
            if (!keySet.add(k)) {
                col.node(nodeId, "", "dedupe derive duplicate key \"" + k + "\": " + nodeId);
            }
        }
    }

    private static void validateLimitRows(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "limit_rows derive missing args: " + nodeId);
            return;
        }
        if (!args.has("rows")) {
            col.node(nodeId, ".args.rows", "limit_rows derive missing rows: " + nodeId);
        }
        if (!args.has("maxRows")) {
            col.node(nodeId, ".args.maxRows", "limit_rows derive missing maxRows: " + nodeId);
        } else {
            validateNonNegativeIntCap(args, "maxRows", PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS, nodeId,
                    "limit_rows", col);
        }
    }

    private static void validateFormatEvidenceLines(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "format_evidence_lines derive missing args: " + nodeId);
            return;
        }
        if (!args.has("template") || args.optString("template", "").trim().isEmpty()) {
            col.node(nodeId, ".args.template", "format_evidence_lines derive missing template: " + nodeId);
        }
        boolean hasRows = args.has("rows");
        boolean hasObject = args.has("object");
        if (hasRows == hasObject) {
            col.node(nodeId, "", "format_evidence_lines derive requires exactly one of rows or object: " + nodeId);
        }
        if (args.has("maxLines")) {
            validatePositiveIntCap(args, "maxLines", PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS, nodeId,
                    "format_evidence_lines", col);
        }
    }

    private static void validatePickMode(JSONObject args, String nodeId, String field, Set<String> allowed,
            PlaybookValidationCollector col) {
        if (!args.has(field)) {
            return;
        }
        String val = args.optString(field, "").trim().toLowerCase(Locale.ROOT);
        if (val.isEmpty() || allowed.contains(val)) {
            return;
        }
        col.node(nodeId, ".args." + field, "INVALID_MATCH_CANDIDATES_MODE",
                "match_candidates derive " + field + " must be one of " + allowed + ": " + nodeId,
                matchCandidatesModeHint(field, allowed));
    }

    private static void validateNonNegativeIntCap(JSONObject args, String key, int cap, String nodeId, String op,
            PlaybookValidationCollector col) {
        Object o = args.opt(key);
        if (!(o instanceof Number)) {
            col.node(nodeId, ".args." + key, op + " derive " + key + " must be a number: " + nodeId);
            return;
        }
        double d = ((Number) o).doubleValue();
        if (d < 0 || d != Math.rint(d) || d > cap) {
            col.node(nodeId, ".args." + key, "INVALID_LIMIT_ROWS_MAX",
                    op + " derive " + key + " must be an integer from 0 to " + cap + ": " + nodeId,
                    limitRowsMaxHint(cap));
        }
    }

    private static void validatePositiveIntCap(JSONObject args, String key, int cap, String nodeId, String op,
            PlaybookValidationCollector col) {
        Object o = args.opt(key);
        if (!(o instanceof Number)) {
            col.node(nodeId, ".args." + key, op + " derive " + key + " must be a number: " + nodeId);
            return;
        }
        double d = ((Number) o).doubleValue();
        if (d < 1 || d != Math.rint(d) || d > cap) {
            col.node(nodeId, ".args." + key, "INVALID_FORMAT_EVIDENCE_MAX_LINES",
                    op + " derive " + key + " must be an integer from 1 to " + cap + ": " + nodeId,
                    formatEvidenceMaxLinesHint(cap));
        }
    }

    private static boolean isNormalizeMode(String mode) {
        return "trim".equals(mode) || "lower".equals(mode) || "collapse_ws".equals(mode) || "identifier".equals(mode);
    }

    private static Map<String, Object> dedupeKeysHint() {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("action", "fix_dedupe_keys");
        hint.put("expectedShape", "array of single-segment identifier strings");
        hint.put("example", Map.of("keys", List.of("name")));
        return hint;
    }

    private static Map<String, Object> limitRowsMaxHint(int cap) {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("action", "fix_limit_rows_max");
        hint.put("expectedShape", "non-negative integer");
        hint.put("maxAllowed", cap);
        return hint;
    }

    private static Map<String, Object> formatEvidenceMaxLinesHint(int cap) {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("action", "fix_format_evidence_max_lines");
        hint.put("expectedShape", "positive integer");
        hint.put("maxAllowed", cap);
        return hint;
    }

    private static Map<String, Object> matchCandidatesModeHint(String field, Set<String> allowed) {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("action", "fix_match_candidates_mode");
        hint.put("field", field);
        hint.put("allowedValues", List.copyOf(allowed));
        return hint;
    }

    private static Map<String, Object> normalizeModeHint() {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("action", "fix_normalize_text_mode");
        hint.put("allowedValues", List.of("trim", "lower", "collapse_ws", "identifier"));
        return hint;
    }

    private static Map<String, Object> singleSegmentFieldHint(String field) {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("action", "fix_single_segment_field");
        hint.put("field", field);
        hint.put("expectedShape", "single identifier segment (no dots)");
        return hint;
    }
}
