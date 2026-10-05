package com.thingworx.things.agent.compaction;

import org.slf4j.Logger;

/**
 * The §12 {@code CONVERSATION_CHECKPOINT_*} single-line events, in one place.
 *
 * <p>Three components emit the skip event — post-turn normalization for every generation-side reason,
 * {@link ContextBudgetPlanner} for {@code CHECKPOINT_CANNOT_FIT} (the checkpoint does not fit this request's
 * budget) and {@code PROVIDER_SHAPE_UNSUPPORTED} (this provider's request contract cannot carry it at all), and
 * {@code ConversationCheckpointRehydrate} for {@code WATERMARK_UNVERIFIED}. §12 pins the field set and their order, so the
 * format lives here rather than being written twice: two copies of a field list are two things that can disagree,
 * and only one of them would be covered by whichever test someone remembered to write.
 *
 * <p>Never log checkpoint semantic text, raw payload, {@code PASSWORD}, a cache repository path, or full tool
 * arguments (§12).
 */
public final class ConversationCheckpointEvents {

    /** §12 reason: the fixed overhead leaves no room for the newest checkpoint in this provider request. */
    public static final String REASON_CHECKPOINT_CANNOT_FIT = "CHECKPOINT_CANNOT_FIT";
    /** §12 reason: the checkpoint was created and installed, but its Stream row could not be persisted (§8.4). */
    public static final String REASON_STREAM_APPEND_FAILED = "STREAM_APPEND_FAILED";
    /**
     * §12 reason: this provider's request contract cannot carry the injected checkpoint (§9.2), so it is omitted
     * from the request rather than promoted to a system row. Always duration zero — no summary call is involved.
     */
    public static final String REASON_PROVIDER_SHAPE_UNSUPPORTED = "PROVIDER_SHAPE_UNSUPPORTED";
    /** §12 reason: the selected persisted candidate failed validation on rehydrate; always duration zero. */
    public static final String REASON_WATERMARK_UNVERIFIED = "WATERMARK_UNVERIFIED";

    private ConversationCheckpointEvents() {}

    /**
     * @param summaryDurationMs measured wall clock when the single summary call began, whatever followed it; zero
     *                          only for the reasons that skip before the call (§12)
     */
    public static void skip(Logger log, String conversationId, String requestId, String reason,
            long summaryDurationMs) {
        if (log == null || !log.isInfoEnabled()) {
            return;
        }
        log.info("CONVERSATION_CHECKPOINT_SKIP conversationId={} requestId={} reason={} summaryDurationMs={}",
                conversationId, requestId, reason, summaryDurationMs);
    }

    public static void created(Logger log, String conversationId, String requestId, int coveredRows,
            int retainedRows, int beforeChars, int afterChars, int checkpointChars, int evidenceRefs,
            int summaryCalls, int promptTokens, int completionTokens, long summaryDurationMs) {
        if (log == null || !log.isInfoEnabled()) {
            return;
        }
        log.info("CONVERSATION_CHECKPOINT_CREATED conversationId={} requestId={} coveredRows={} retainedRows={} "
                        + "beforeChars={} afterChars={} checkpointChars={} evidenceRefs={} summaryCalls={} "
                        + "promptTokens={} completionTokens={} summaryDurationMs={}",
                conversationId, requestId, coveredRows, retainedRows, beforeChars, afterChars, checkpointChars,
                evidenceRefs, summaryCalls, promptTokens, completionTokens, summaryDurationMs);
    }
}
