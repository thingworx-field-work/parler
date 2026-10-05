package com.thingworx.things.agent.configrepo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Resolved external whole-block system prompt selection for one prompt-context refresh. */
public final class ExternalSystemPromptSelection {

    public enum Mode {
        DEFAULT,
        EXTERNAL_FILE
    }

    private final Mode mode;
    private final String activePath;
    private final String promptText;
    private final String fallbackDiagnostic;

    private ExternalSystemPromptSelection(Mode mode, String activePath, String promptText, String fallbackDiagnostic) {
        this.mode = mode != null ? mode : Mode.DEFAULT;
        this.activePath = activePath != null ? activePath : "";
        this.promptText = promptText;
        this.fallbackDiagnostic = fallbackDiagnostic != null ? fallbackDiagnostic : "";
    }

    public static ExternalSystemPromptSelection defaultSelection() {
        return new ExternalSystemPromptSelection(Mode.DEFAULT, null, null, null);
    }

    public static ExternalSystemPromptSelection externalFile(String activePath, String promptText) {
        return new ExternalSystemPromptSelection(Mode.EXTERNAL_FILE, activePath, promptText, null);
    }

    public static ExternalSystemPromptSelection fallback(String fallbackDiagnostic) {
        return new ExternalSystemPromptSelection(Mode.DEFAULT, null, null, fallbackDiagnostic);
    }

    public Mode mode() {
        return mode;
    }

    public String activePath() {
        return activePath;
    }

    public String promptText() {
        return promptText;
    }

    public String fallbackDiagnostic() {
        return fallbackDiagnostic;
    }

    public boolean isActiveExternal() {
        return mode == Mode.EXTERNAL_FILE && promptText != null && !promptText.isBlank();
    }

    /** JSON snapshot value: {@code default} or {@code external_file}. */
    public String sourceKey() {
        return isActiveExternal() ? "external_file" : "default";
    }

    /**
     * Operator-only markdown appended to {@code RefreshPromptContextCache} and surfaced in runtime snapshot
     * diagnostics. Not part of the model-facing leading stable row.
     */
    public String formatRefreshDiagnosticsMarkdown() {
        StringBuilder sb = new StringBuilder("---\n## External system prompt selection\n\n");
        sb.append("- stablePromptSource: ").append(sourceKey()).append('\n');
        if (isActiveExternal()) {
            sb.append("- externalSystemPromptPath: ").append(activePath()).append('\n');
        } else if (fallbackDiagnostic != null && !fallbackDiagnostic.isBlank()) {
            sb.append("- externalSystemPromptFallback: ").append(fallbackDiagnostic).append('\n');
        } else {
            sb.append("- externalSystemPromptPath: (none)\n");
        }
        return sb.toString();
    }

    /** Lines for {@code GetAgentRuntimeSnapshot.prompt.diagnostics}. */
    public List<String> refreshDiagnosticLines() {
        List<String> lines = new ArrayList<>(3);
        lines.add("externalSystemPrompt.stablePromptSource=" + sourceKey());
        if (isActiveExternal()) {
            lines.add("externalSystemPrompt.path=" + activePath());
        } else if (fallbackDiagnostic != null && !fallbackDiagnostic.isBlank()) {
            lines.add("externalSystemPrompt.fallback=" + fallbackDiagnostic);
        }
        return lines;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExternalSystemPromptSelection)) {
            return false;
        }
        ExternalSystemPromptSelection that = (ExternalSystemPromptSelection) o;
        return mode == that.mode && Objects.equals(activePath, that.activePath)
                && Objects.equals(promptText, that.promptText)
                && Objects.equals(fallbackDiagnostic, that.fallbackDiagnostic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, activePath, promptText, fallbackDiagnostic);
    }
}
