package com.thingworx.things.agent.playbook;

import org.json.JSONObject;

import com.thingworx.things.agent.StreamTokenUsage;

/** Outcome of a Playbook run for Chat or {@code start_playbook}. */
public final class PlaybookRunResult {

    public enum Status {
        COMPLETED,
        NEEDS_CLARIFICATION,
        FAILED,
        CANCELLED
    }

    private final Status status;
    private final String assistantText;
    private final String failureCode;
    private final int toolCallCount;
    private final int llmCallCount;
    private final long elapsedMs;
    private final StreamTokenUsage llmUsage;
    private final JSONObject runOutcome;

    public PlaybookRunResult(Status status, String assistantText, String failureCode, int toolCallCount,
            int llmCallCount, long elapsedMs) {
        this(status, assistantText, failureCode, toolCallCount, llmCallCount, elapsedMs, StreamTokenUsage.ZERO, null);
    }

    public PlaybookRunResult(Status status, String assistantText, String failureCode, int toolCallCount,
            int llmCallCount, long elapsedMs, StreamTokenUsage llmUsage) {
        this(status, assistantText, failureCode, toolCallCount, llmCallCount, elapsedMs, llmUsage, null);
    }

    public PlaybookRunResult(Status status, String assistantText, String failureCode, int toolCallCount,
            int llmCallCount, long elapsedMs, StreamTokenUsage llmUsage, JSONObject runOutcome) {
        this.status = status;
        this.assistantText = assistantText;
        this.failureCode = failureCode;
        this.toolCallCount = toolCallCount;
        this.llmCallCount = llmCallCount;
        this.elapsedMs = elapsedMs;
        this.llmUsage = llmUsage != null ? llmUsage : StreamTokenUsage.ZERO;
        this.runOutcome = runOutcome;
    }

    public Status status() {
        return status;
    }

    public String assistantText() {
        return assistantText;
    }

    public String failureCode() {
        return failureCode;
    }

    public int toolCallCount() {
        return toolCallCount;
    }

    public int llmCallCount() {
        return llmCallCount;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    /** Summed usage for all playbook-internal {@code llm_summary} calls (not last-only). */
    public StreamTokenUsage llmUsage() {
        return llmUsage;
    }

    /** Collection-facing run outcome (§8.2); null when no run context was available. */
    public JSONObject runOutcome() {
        return runOutcome;
    }
}
