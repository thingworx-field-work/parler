package com.thingworx.things.agent.compaction;

/**
 * Raised when the context planner cannot assemble a provider-safe outbound message list within
 * {@code effectiveRequestCapChars} after drop-only trimming ({@code docs/agent/context-compaction.md} §7, §16 Slice C).
 */
public final class ContextBudgetExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable operator-facing classification for alerts and log filters. */
    public enum Reason {
        /** Non-history components alone exceed {@code effectiveRequestCapChars}. */
        OVERHEAD_EXCEEDS_CAP,
        /** Drop-only trimming exhausted historic evidence and transcript pairs but history still does not fit. */
        CANNOT_FIT_AFTER_TRIM
    }

    private final Reason reason;

    public ContextBudgetExceededException(Reason reason, String message) {
        super(message);
        this.reason = reason != null ? reason : Reason.CANNOT_FIT_AFTER_TRIM;
    }

    public Reason getReason() {
        return reason;
    }
}
