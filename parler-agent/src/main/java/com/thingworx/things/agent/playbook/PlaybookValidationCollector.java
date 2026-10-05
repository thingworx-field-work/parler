package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parallel structured-issue channel for {@link PlaybookValidator}; keeps legacy string errors for tests.
 */
final class PlaybookValidationCollector {

    private final PlaybookDocument doc;
    private final List<String> errors = new ArrayList<>();
    private final List<PlaybookValidationIssue> issues = new ArrayList<>();
    private final Map<String, Integer> nodeIndex;

    PlaybookValidationCollector(PlaybookDocument doc) {
        this.doc = doc;
        this.nodeIndex = indexNodes(doc);
    }

    List<String> errors() {
        return errors;
    }

    List<PlaybookValidationIssue> issues() {
        return issues;
    }

    PlaybookValidator.Result toResult() {
        return new PlaybookValidator.Result(errors.isEmpty(), errors, issues);
    }

    void document(String code, String path, String message, Map<String, Object> hint) {
        errors.add(message);
        issues.add(issue(code, path, "", message, hint));
    }

    void document(String message) {
        document(classifyCode(message, ""), classifyPath(message, ""), message, classifyHint(message));
    }

    void node(String nodeId, String pathSuffix, String message) {
        errors.add(message);
        String path = nodePath(nodeId) + (pathSuffix != null ? pathSuffix : "");
        issues.add(issue(classifyCode(message, nodeId), path, nodeId, message, classifyHint(message)));
    }

    void node(String nodeId, String pathSuffix, String code, String message, Map<String, Object> hint) {
        errors.add(message);
        issues.add(issue(code, nodePath(nodeId) + (pathSuffix != null ? pathSuffix : ""), nodeId, message, hint));
    }

    private static Map<String, Integer> indexNodes(PlaybookDocument doc) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        if (doc == null) {
            return idx;
        }
        List<String> ids = doc.nodeIdsInOrder();
        for (int i = 0; i < ids.size(); i++) {
            idx.put(ids.get(i), i);
        }
        return idx;
    }

    private String nodePath(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return "";
        }
        Integer idx = nodeIndex.get(nodeId);
        return idx != null ? "nodes[" + idx + "]" : "nodes[?]";
    }

    private static PlaybookValidationIssue issue(String code, String path, String nodeId, String message,
            Map<String, Object> hint) {
        return new PlaybookValidationIssue(code, PlaybookValidationIssue.Severity.ERROR, path != null ? path : "",
                nodeId != null ? nodeId : "", message, hint != null ? hint : Map.of());
    }

    static String classifyCode(String message, String nodeId) {
        if (message == null) {
            return "PLAYBOOK_VALIDATION";
        }
        if (message.startsWith("unknown derive op for ")) {
            return "UNSUPPORTED_DERIVE_OP";
        }
        if (message.contains("group_by derive keys")) {
            return "INVALID_GROUP_BY_KEYS";
        }
        if (message.contains("dedupe derive keys") || message.contains("dedupe derive duplicate key")) {
            return "INVALID_DEDUPE_KEYS";
        }
        if (message.contains("match_candidates derive") && message.contains("onZero")) {
            return "INVALID_MATCH_CANDIDATES_MODE";
        }
        if (message.contains("match_candidates derive") && message.contains("onMultiple")) {
            return "INVALID_MATCH_CANDIDATES_MODE";
        }
        if (message.contains("match_candidates derive candidateField")) {
            return "INVALID_MATCH_CANDIDATES_FIELD";
        }
        if (message.contains("limit_rows derive maxRows")) {
            return "INVALID_LIMIT_ROWS_MAX";
        }
        if (message.contains("format_evidence_lines derive maxLines")) {
            return "INVALID_FORMAT_EVIDENCE_MAX_LINES";
        }
        if (message.contains("normalize_text derive mode")) {
            return "INVALID_NORMALIZE_TEXT_MODE";
        }
        if (message.startsWith("unknown tool: ")) {
            return "UNKNOWN_TOOL";
        }
        if (message.startsWith("tool playbookSafe=false: ")) {
            return "TOOL_NOT_PLAYBOOK_SAFE";
        }
        if (message.contains("$infotable") || message.contains("$table")) {
            return "UNSUPPORTED_BINDING";
        }
        if (message.startsWith("unknown node kind for ")) {
            return "UNSUPPORTED_NODE_KIND";
        }
        if (message.contains("dependsOn references missing node")
                || message.startsWith("missing node: ")
                || message.startsWith("finalNode not found:")) {
            return "UNKNOWN_NODE_REFERENCE";
        }
        if (message.contains("fan_out")) {
            if (message.contains("child must be tool_call") || message.contains("missing child node")) {
                return "INVALID_FAN_OUT_CHILD";
            }
            if (message.contains("maxConcurrency")) {
                return "INVALID_FAN_OUT_CONCURRENCY";
            }
        }
        if (message.contains("dataShapeName")) {
            return "MISSING_DATASHAPE_NAME";
        }
        if (message.contains("maxNodes") || message.contains("maxToolCalls") || message.contains("exceeds cap")
                || message.contains("budget")) {
            return "BUDGET_VIOLATION";
        }
        if (message.contains("evidenceRefs") || message.contains("evidence reference")) {
            return "UNKNOWN_EVIDENCE_REF";
        }
        if ("finalNode is required".equals(message) || message.startsWith("llm_summary must be finalNode")) {
            return "INVALID_FINAL_NODE";
        }
        if (message.startsWith("exactly one llm_summary")) {
            return "INVALID_LLM_SUMMARY";
        }
        if (message.startsWith("schema must be ")) {
            return "UNSUPPORTED_SCHEMA_VERSION";
        }
        if (message.contains("provider")) {
            return "UNSUPPORTED_PROVIDER_FIELD";
        }
        return "PLAYBOOK_VALIDATION";
    }

    static String classifyPath(String message, String nodeId) {
        if ("finalNode is required".equals(message) || message.startsWith("finalNode not found")) {
            return "finalNode";
        }
        if (message.startsWith("schema must be ")) {
            return "schema";
        }
        if (message.startsWith("unknown derive op for ")) {
            return nodePathStatic(nodeId) + ".op";
        }
        if (message.contains("group_by derive keys")) {
            return nodePathStatic(nodeId) + ".args.keys";
        }
        if (message.contains("dedupe derive keys") || message.contains("dedupe derive duplicate key")) {
            return nodePathStatic(nodeId) + ".args.keys";
        }
        return nodePathStatic(nodeId);
    }

    private static String nodePathStatic(String nodeId) {
        return nodeId != null && !nodeId.isBlank() ? "nodes[?]" : "";
    }

    static Map<String, Object> classifyHint(String message) {
        if (message == null) {
            return Map.of();
        }
        if (message.contains("group_by derive keys")) {
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("action", "fix_group_by_keys");
            hint.put("expectedShape", "array of single-segment identifier strings");
            hint.put("example", Map.of("keys", List.of("status")));
            return hint;
        }
        if (message.contains("dedupe derive keys") || message.contains("dedupe derive duplicate key")) {
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("action", "fix_dedupe_keys");
            hint.put("expectedShape", "array of single-segment identifier strings");
            hint.put("example", Map.of("keys", List.of("name")));
            return hint;
        }
        if (message.startsWith("unknown derive op for ")) {
            int colon = message.lastIndexOf(':');
            String op = colon > 0 ? message.substring(colon + 1).trim() : "";
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("action", "replace_or_remove_node");
            hint.put("unsupportedOp", op);
            hint.put("supportedAlternatives", List.copyOf(PlaybookValidator.supportedDeriveOps()));
            return hint;
        }
        if (message.startsWith("unknown tool: ")) {
            return Map.of("tool", message.substring("unknown tool: ".length()).trim());
        }
        if (message.startsWith("schema must be ")) {
            return Map.of("expectedSchema", PlaybookIds.SCHEMA_V1);
        }
        return Map.of();
    }
}
