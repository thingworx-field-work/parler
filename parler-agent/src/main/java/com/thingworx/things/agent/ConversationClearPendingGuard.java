package com.thingworx.things.agent;

import org.joda.time.DateTime;

/**
 * Suppresses restoring a pending HITL message snapshot into empty JVM memory when the thread was cleared after the
 * pending was created ({@code docs/agent/conversation-continuity.md}).
 */
public final class ConversationClearPendingGuard {

    private ConversationClearPendingGuard() {}

    /**
     * When {@code historyClearedAt} is strictly after {@code pendingCreatedAtEpochMillis}, an expired-pending delivery
     * must not repopulate {@code _conversations} from the pre-clear snapshot.
     */
    public static boolean suppressExpiredPendingSnapshotRestore(DateTime historyClearedAtOrNull,
            long pendingCreatedAtEpochMillis) {
        return historyClearedAtOrNull != null
                && historyClearedAtOrNull.getMillis() > pendingCreatedAtEpochMillis;
    }
}
