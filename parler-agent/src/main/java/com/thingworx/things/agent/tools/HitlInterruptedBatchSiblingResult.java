package com.thingworx.things.agent.tools;

/**
 * Synthetic tool result for tool calls in the same assistant batch as a HITL-gated call that paused before they ran
 * (Bug 010).
 */
public final class HitlInterruptedBatchSiblingResult {

    /** JSON body for {@code role: tool} rows; {@code status: skipped} — not user cancellation of the gated tool. */
    public static final String JSON = "{\"status\":\"skipped\",\"code\":\"HITL_PAUSE_INTERRUPTED_BATCH\","
            + "\"message\":\"Skipped because an earlier tool call in the same parallel batch is awaiting human approval. "
            + "Reissue this call after the approval outcome if still needed.\"}";

    private HitlInterruptedBatchSiblingResult() {
    }
}
