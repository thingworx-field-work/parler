package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

class ConversationClearPendingGuardTest {

    @Test
    void no_suppress_when_never_cleared() {
        assertFalse(ConversationClearPendingGuard.suppressExpiredPendingSnapshotRestore(null, 1000L));
    }

    @Test
    void no_suppress_when_cleared_before_pending() {
        DateTime cleared = new DateTime(1000L);
        assertFalse(ConversationClearPendingGuard.suppressExpiredPendingSnapshotRestore(cleared, 2000L));
    }

    @Test
    void suppress_when_cleared_strictly_after_pending() {
        DateTime cleared = new DateTime(3000L);
        assertTrue(ConversationClearPendingGuard.suppressExpiredPendingSnapshotRestore(cleared, 2000L));
    }

    @Test
    void no_suppress_when_cleared_same_millis_as_pending() {
        DateTime cleared = new DateTime(2000L);
        assertFalse(ConversationClearPendingGuard.suppressExpiredPendingSnapshotRestore(cleared, 2000L));
    }
}
