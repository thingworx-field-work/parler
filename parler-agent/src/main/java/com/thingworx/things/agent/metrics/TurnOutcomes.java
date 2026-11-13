package com.thingworx.things.agent.metrics;

import com.thingworx.things.agent.AgentLoop;

/**
 * Plain-Java turn outcome mapper for the terminal branches named in {@code docs/agent/metrics-support.md}.
 */
public final class TurnOutcomes {

    private TurnOutcomes() {}

    /**
     * Maps a loop status reached on an ordinary terminal branch. {@code AWAITING_APPROVAL} without the pending-record
     * and tombstone context is an incomplete pause and maps to {@code error}; callers that hold that context use
     * {@link #forAwaitingApproval(boolean, boolean)}.
     */
    public static TurnOutcome forResultStatus(AgentLoop.AgentResult.Status status) {
        if (status == null) {
            return TurnOutcome.ERROR;
        }
        switch (status) {
            case SUCCESS:
                return TurnOutcome.SUCCESS;
            case CANCELLED:
                return TurnOutcome.CANCELLED;
            case ERROR:
            case TIMEOUT:
            case MAX_ITERATIONS:
            case AWAITING_APPROVAL:
            default:
                return TurnOutcome.ERROR;
        }
    }

    /**
     * {@code AWAITING_APPROVAL} terminal branch: an active stop tombstone wins ({@code cancelled}); otherwise a valid
     * pending record pauses the segment ({@code awaiting_approval}); a null pending ID or store miss is {@code error}.
     */
    public static TurnOutcome forAwaitingApproval(boolean stopTombstoneActive, boolean pendingRecordPresent) {
        if (stopTombstoneActive) {
            return TurnOutcome.CANCELLED;
        }
        return pendingRecordPresent ? TurnOutcome.AWAITING_APPROVAL : TurnOutcome.ERROR;
    }

    /** Playbook slash outcome; a failure maps to {@code error} even when the response is success-shaped. */
    public static TurnOutcome forPlaybookSlash(boolean succeeded) {
        return succeeded ? TurnOutcome.SUCCESS : TurnOutcome.ERROR;
    }
}
