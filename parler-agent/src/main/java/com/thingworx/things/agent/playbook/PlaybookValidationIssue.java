package com.thingworx.things.agent.playbook;

import java.util.Map;

/** One structured validator or request issue for AI-assisted Playbook authoring. */
public final class PlaybookValidationIssue {

    public enum Severity {
        ERROR,
        WARNING
    }

    private final String code;
    private final Severity severity;
    private final String path;
    private final String nodeId;
    private final String message;
    private final Map<String, Object> recoveryHint;

    public PlaybookValidationIssue(String code, Severity severity, String path, String nodeId, String message,
            Map<String, Object> recoveryHint) {
        this.code = code != null ? code : "PLAYBOOK_VALIDATION";
        this.severity = severity != null ? severity : Severity.ERROR;
        this.path = path != null ? path : "";
        this.nodeId = nodeId != null ? nodeId : "";
        this.message = message != null ? message : "";
        this.recoveryHint = recoveryHint != null ? Map.copyOf(recoveryHint) : Map.of();
    }

    public String code() {
        return code;
    }

    public Severity severity() {
        return severity;
    }

    public String path() {
        return path;
    }

    public String nodeId() {
        return nodeId;
    }

    public String message() {
        return message;
    }

    public Map<String, Object> recoveryHint() {
        return recoveryHint;
    }
}
