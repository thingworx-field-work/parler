package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.llm.ChatMessage;

/**
 * Builds the message list snapshot passed to {@link com.thingworx.things.agent.tools.PendingApprovalRecord} for Parler
 * HITL — same rules as {@link com.thingworx.things.agent.AgentThing#tryEnqueueParlerHitlPending} so JUnit can assert the
 * strip behavior without loading {@link AgentThing}.
 */
public final class ParlerHitlPendingSnapshotHelper {

    private ParlerHitlPendingSnapshotHelper() {
    }

    /**
     * @param activeMsgs current in-loop messages
     * @param ephemeralIdx per-turn ephemeral system row indices, or {@code null} when none were registered
     * @return a new {@link ArrayList} copy of {@code activeMsgs} (possibly stripped); never aliases {@code activeMsgs},
     *         so callers may continue mutating the active list without affecting the pending snapshot
     */
    public static List<ChatMessage> messagesForPendingSnapshot(List<ChatMessage> activeMsgs,
            ParlerEphemeralSystemIndices ephemeralIdx) {
        if (activeMsgs == null) {
            return null;
        }
        List<ChatMessage> copy = new ArrayList<>(activeMsgs);
        if (ephemeralIdx != null) {
            EphemeralSystemStripHelper.stripEphemeralSystemInjectionsByIndices(copy, ephemeralIdx);
        }
        return copy;
    }
}
