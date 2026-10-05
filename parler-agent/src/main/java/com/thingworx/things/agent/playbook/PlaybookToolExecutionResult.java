package com.thingworx.things.agent.playbook;

import com.thingworx.types.InfoTable;

/** Result of one playbook {@code tool_call} execution (JSON envelope plus optional raw INFOTABLE). */
public final class PlaybookToolExecutionResult {

    private final String toolJsonEnvelope;
    private final InfoTable rawServiceResultTable;
    private final String toolCallId;

    private PlaybookToolExecutionResult(String toolJsonEnvelope, InfoTable rawServiceResultTable, String toolCallId) {
        this.toolJsonEnvelope = toolJsonEnvelope != null ? toolJsonEnvelope : "";
        this.rawServiceResultTable = rawServiceResultTable;
        this.toolCallId = toolCallId;
    }

    public static PlaybookToolExecutionResult jsonOnly(String toolJsonEnvelope) {
        return new PlaybookToolExecutionResult(toolJsonEnvelope, null, null);
    }

    public static PlaybookToolExecutionResult jsonOnly(String toolJsonEnvelope, String toolCallId) {
        return new PlaybookToolExecutionResult(toolJsonEnvelope, null, toolCallId);
    }

    public static PlaybookToolExecutionResult withRawTable(String toolJsonEnvelope, InfoTable rawTable) {
        return new PlaybookToolExecutionResult(toolJsonEnvelope, rawTable, null);
    }

    public static PlaybookToolExecutionResult withRawTable(String toolJsonEnvelope, InfoTable rawTable,
            String toolCallId) {
        return new PlaybookToolExecutionResult(toolJsonEnvelope, rawTable, toolCallId);
    }

    public String toolJsonEnvelope() {
        return toolJsonEnvelope;
    }

    /**
     * Synthetic tool call id for this internal execution (e.g. {@code pb-…}), or empty when not assigned (preflight
     * errors and legacy call sites).
     */
    public String toolCallId() {
        return toolCallId != null ? toolCallId : "";
    }

    /** Non-null only when the underlying service returns an INFOTABLE-shaped result retained for {@code $table}. */
    public InfoTable rawServiceResultTable() {
        return rawServiceResultTable;
    }
}
