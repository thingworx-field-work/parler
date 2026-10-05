package com.thingworx.things.agent.playbook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Machine-readable Playbook document validation report (Slice B). */
public final class PlaybookValidationReport {

    public enum Status {
        VALID,
        INVALID
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Status status;
    private final String playbookId;
    private final String agentVersion;
    private final List<PlaybookValidationIssue> errors;
    private final List<PlaybookValidationIssue> warnings;

    public PlaybookValidationReport(Status status, String playbookId, String agentVersion,
            List<PlaybookValidationIssue> errors, List<PlaybookValidationIssue> warnings) {
        this.status = status != null ? status : Status.INVALID;
        this.playbookId = playbookId;
        this.agentVersion = agentVersion;
        this.errors = errors != null ? List.copyOf(errors) : List.of();
        this.warnings = warnings != null ? List.copyOf(warnings) : List.of();
    }

    public Status status() {
        return status;
    }

    public String playbookId() {
        return playbookId;
    }

    public String agentVersion() {
        return agentVersion;
    }

    public List<PlaybookValidationIssue> errors() {
        return errors;
    }

    public List<PlaybookValidationIssue> warnings() {
        return warnings;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(toObjectNode());
        } catch (Exception e) {
            return "{\"status\":\"invalid\",\"errors\":[{\"code\":\"REPORT_SERIALIZE_FAILED\",\"severity\":\"error\","
                    + "\"message\":\"" + (e.getMessage() != null ? e.getMessage().replace("\"", "'") : "error")
                    + "\"}]}";
        }
    }

    public ObjectNode toObjectNode() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", status.name().toLowerCase());
        if (playbookId != null && !playbookId.isBlank()) {
            root.put("playbookId", playbookId);
        } else {
            root.putNull("playbookId");
        }
        if (agentVersion != null && !agentVersion.isBlank()) {
            root.put("agentVersion", agentVersion);
        } else {
            root.putNull("agentVersion");
        }
        root.set("errors", issuesArray(errors));
        root.set("warnings", issuesArray(warnings));
        return root;
    }

    private static ArrayNode issuesArray(List<PlaybookValidationIssue> issues) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (PlaybookValidationIssue issue : issues) {
            ObjectNode row = arr.addObject();
            row.put("code", issue.code());
            row.put("severity", issue.severity() == PlaybookValidationIssue.Severity.WARNING ? "warning" : "error");
            row.put("path", issue.path());
            if (issue.nodeId() != null && !issue.nodeId().isBlank()) {
                row.put("nodeId", issue.nodeId());
            }
            row.put("message", issue.message());
            if (!issue.recoveryHint().isEmpty()) {
                row.set("recoveryHint", MAPPER.valueToTree(issue.recoveryHint()));
            }
        }
        return arr;
    }

    public static PlaybookValidationReport invalidRequest(String message, String agentVersion) {
        return new PlaybookValidationReport(Status.INVALID, null, agentVersion, List.of(new PlaybookValidationIssue(
                "INVALID_REQUEST", PlaybookValidationIssue.Severity.ERROR, "", "", message, Map.of())), List.of());
    }

    public static PlaybookValidationReport invalidJson(String message, String playbookId, String agentVersion) {
        return new PlaybookValidationReport(Status.INVALID, playbookId, agentVersion, List.of(new PlaybookValidationIssue(
                "INVALID_JSON", PlaybookValidationIssue.Severity.ERROR, "", "", message != null ? message : "invalid JSON",
                Map.of("action", "fix_json_syntax"))), List.of());
    }

    public static PlaybookValidationReport packageUnreadable(String packageDirId, String path, String loadKind,
            String agentVersion) {
        String message = "playbook package not readable at " + path + ": " + loadKind;
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("packageDirId", packageDirId);
        hint.put("path", path);
        hint.put("loadKind", loadKind);
        return new PlaybookValidationReport(Status.INVALID, packageDirId, agentVersion, List.of(new PlaybookValidationIssue(
                "PACKAGE_UNREADABLE", PlaybookValidationIssue.Severity.ERROR, path, "", message, hint)), List.of());
    }
}
