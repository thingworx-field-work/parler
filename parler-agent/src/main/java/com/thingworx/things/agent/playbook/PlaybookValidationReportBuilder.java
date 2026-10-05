package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps {@link PlaybookValidator.Result} to structured {@link PlaybookValidationReport}
 * issues (single report builder for repository and document validation).
 */
public final class PlaybookValidationReportBuilder {

    private static final Pattern TRAILING_NODE_ID = Pattern.compile(": ([A-Za-z][A-Za-z0-9_-]*)$");
    private static final Pattern UNKNOWN_DERIVE_OP =
            Pattern.compile("^unknown derive op for ([A-Za-z][A-Za-z0-9_-]*): (.+)$");
    private static final Pattern UNKNOWN_TOOL = Pattern.compile("^unknown tool: (.+)$");
    private static final Pattern UNSAFE_TOOL = Pattern.compile("^tool playbookSafe=false: (.+)$");

    private PlaybookValidationReportBuilder() {}

    public static PlaybookValidationReport build(PlaybookDocument doc, PlaybookValidator.Result result,
            String declaredPlaybookId, String agentVersion) {
        String playbookId = resolvePlaybookId(doc, declaredPlaybookId);
        if (result.valid()) {
            return new PlaybookValidationReport(PlaybookValidationReport.Status.VALID, playbookId, agentVersion,
                    List.of(), List.of());
        }
        List<PlaybookValidationIssue> errors = new ArrayList<>();
        List<PlaybookValidationIssue> warnings = new ArrayList<>();
        if (!result.issues().isEmpty()) {
            for (PlaybookValidationIssue issue : result.issues()) {
                if (issue.severity() == PlaybookValidationIssue.Severity.WARNING) {
                    warnings.add(issue);
                } else {
                    errors.add(issue);
                }
            }
        } else {
            Map<String, Integer> nodeIndex = doc != null ? nodeIndex(doc) : Map.of();
            for (String line : result.errors()) {
                PlaybookValidationIssue issue = mapLegacyError(line, doc, nodeIndex);
                if (issue.severity() == PlaybookValidationIssue.Severity.WARNING) {
                    warnings.add(issue);
                } else {
                    errors.add(issue);
                }
            }
        }
        PlaybookValidationReport.Status status =
                errors.isEmpty() ? PlaybookValidationReport.Status.VALID : PlaybookValidationReport.Status.INVALID;
        return new PlaybookValidationReport(status, playbookId, agentVersion, errors, warnings);
    }

    private static String resolvePlaybookId(PlaybookDocument doc, String declaredPlaybookId) {
        if (declaredPlaybookId != null && !declaredPlaybookId.isBlank()) {
            return declaredPlaybookId;
        }
        if (doc != null && doc.playbookId() != null && !doc.playbookId().isBlank()) {
            return doc.playbookId();
        }
        return null;
    }

    private static Map<String, Integer> nodeIndex(PlaybookDocument doc) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        List<String> ids = doc.nodeIdsInOrder();
        for (int i = 0; i < ids.size(); i++) {
            idx.put(ids.get(i), i);
        }
        return idx;
    }

    /** Fallback for catalog / root validation paths that still emit string errors only. */
    private static PlaybookValidationIssue mapLegacyError(String line, PlaybookDocument doc,
            Map<String, Integer> nodeIndex) {
        String nodeId = extractNodeId(line);
        String path = pathForNode(nodeId, nodeIndex);

        Matcher derive = UNKNOWN_DERIVE_OP.matcher(line);
        if (derive.matches()) {
            nodeId = derive.group(1);
            String op = derive.group(2);
            path = pathForNode(nodeId, nodeIndex) + ".op";
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("action", "replace_or_remove_node");
            hint.put("unsupportedOp", op);
            hint.put("supportedAlternatives", List.copyOf(PlaybookValidator.supportedDeriveOps()));
            return issue("UNSUPPORTED_DERIVE_OP", PlaybookValidationIssue.Severity.ERROR, path, nodeId, line, hint);
        }

        if (line.contains("group_by derive keys must be a non-empty array")) {
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("action", "fix_group_by_keys");
            hint.put("expectedShape", "array of single-segment identifier strings");
            hint.put("example", Map.of("keys", List.of("status")));
            return issue("INVALID_GROUP_BY_KEYS", PlaybookValidationIssue.Severity.ERROR,
                    pathForNode(nodeId, nodeIndex) + ".args.keys", nodeId, line, hint);
        }

        Matcher tool = UNKNOWN_TOOL.matcher(line);
        if (tool.matches()) {
            return issue("UNKNOWN_TOOL", PlaybookValidationIssue.Severity.ERROR, path, nodeId, line, Map.of());
        }

        Matcher unsafe = UNSAFE_TOOL.matcher(line);
        if (unsafe.matches()) {
            return issue("TOOL_NOT_PLAYBOOK_SAFE", PlaybookValidationIssue.Severity.ERROR, path, nodeId, line,
                    Map.of("tool", unsafe.group(1)));
        }

        if (line.contains("$infotable")) {
            return issue("UNSUPPORTED_BINDING", PlaybookValidationIssue.Severity.ERROR, path, nodeId, line,
                    Map.of("action", "use_repository_extended_tool_or_fix_binding"));
        }

        if ("finalNode is required".equals(line) || (line.startsWith("finalNode not found:"))) {
            return issue("INVALID_FINAL_NODE", PlaybookValidationIssue.Severity.ERROR, "finalNode", "", line, Map.of());
        }

        if (line.startsWith("exactly one llm_summary node required") || line.startsWith("llm_summary must be finalNode")) {
            return issue("INVALID_LLM_SUMMARY", PlaybookValidationIssue.Severity.ERROR, path, nodeId, line, Map.of());
        }

        if (line.startsWith("schema must be ")) {
            return issue("UNSUPPORTED_SCHEMA_VERSION", PlaybookValidationIssue.Severity.ERROR, "schema", "", line,
                    Map.of("expectedSchema", PlaybookIds.SCHEMA_V1));
        }

        if (line.contains("playbook document must not contain provider field")) {
            return issue("UNSUPPORTED_PROVIDER_FIELD", PlaybookValidationIssue.Severity.ERROR, "provider", "", line,
                    Map.of());
        }

        if (line.contains("fan_out maxConcurrency must be")) {
            return issue("INVALID_FAN_OUT_CONCURRENCY", PlaybookValidationIssue.Severity.ERROR, path, nodeId, line,
                    Map.of("maxAllowed", PlaybookGenericOpsConstants.MAX_FAN_OUT_CONCURRENCY));
        }

        return issue("PLAYBOOK_VALIDATION", PlaybookValidationIssue.Severity.ERROR, path, nodeId, line, Map.of());
    }

    private static PlaybookValidationIssue issue(String code, PlaybookValidationIssue.Severity severity, String path,
            String nodeId, String message, Map<String, Object> hint) {
        return new PlaybookValidationIssue(code, severity, path, nodeId, message, hint);
    }

    private static String extractNodeId(String line) {
        Matcher m = TRAILING_NODE_ID.matcher(line);
        if (m.find()) {
            return m.group(1);
        }
        if (line.startsWith("unknown derive op for ")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String rest = line.substring("unknown derive op for ".length(), colon).trim();
                if (!rest.isEmpty()) {
                    return rest;
                }
            }
        }
        return "";
    }

    private static String pathForNode(String nodeId, Map<String, Integer> nodeIndex) {
        if (nodeId == null || nodeId.isBlank()) {
            return "";
        }
        Integer idx = nodeIndex.get(nodeId);
        if (idx == null) {
            return "nodes[?]";
        }
        return "nodes[" + idx + "]";
    }
}
