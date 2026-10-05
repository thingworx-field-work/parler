package com.thingworx.things.agent.playbook;

/**
 * One playbook-registry discovery / load outcome for operator surfaces ({@link PlaybookRegistrySnapshot}) and
 * {@code ValidateAgentConfigurationRepository} items.
 */
public final class PlaybookRegistryDiagnostic {

    public enum Severity {
        ERROR,
        WARNING
    }

    private final Severity severity;
    private final String path;
    private final String code;
    private final String message;

    public PlaybookRegistryDiagnostic(Severity severity, String path, String code, String message) {
        this.severity = severity != null ? severity : Severity.ERROR;
        this.path = path != null ? path : "";
        this.code = code != null ? code : "PLAYBOOK";
        this.message = message != null ? message : "";
    }

    public Severity severity() {
        return severity;
    }

    /** Path for validation items and snapshot context (playbook file path or {@code /playbooks}). */
    public String path() {
        return path;
    }

    public String code() {
        return code;
    }

    /** Human-readable detail (without path prefix for package-local issues). */
    public String message() {
        return message;
    }

    /**
     * Line shape for {@link com.thingworx.things.agent.AgentThing} runtime snapshot {@code playbooks.diagnostics[]}
     * (backward compatible with prior string-only diagnostics).
     */
    public String snapshotLine() {
        if ("PLAYBOOK_REGISTRY_CAP".equals(code)) {
            return message;
        }
        if ("PLAYBOOK_DOCUMENT_READ".equals(code)) {
            return "document load failed: " + path + " (" + message + ")";
        }
        if ("PLAYBOOK_BUILD".equals(code)) {
            return "build failed: " + message;
        }
        if ("PLAYBOOK_DIAGNOSTICS_TRUNCATED".equals(code)) {
            return message;
        }
        return path + ": " + message;
    }

    public static PlaybookRegistryDiagnostic error(String path, String code, String message) {
        return new PlaybookRegistryDiagnostic(Severity.ERROR, path, code, message);
    }

    public static PlaybookRegistryDiagnostic warning(String path, String code, String message) {
        return new PlaybookRegistryDiagnostic(Severity.WARNING, path, code, message);
    }
}
