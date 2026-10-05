package com.thingworx.things.agent.hostcontext;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-conversation O(1) carry for {@link HostContextSnapshotBuilder} changed computation.
 */
public final class HostContextTurnCarryStore {

    private final ConcurrentHashMap<String, HostContextPreviousSnapshot> byConversation = new ConcurrentHashMap<>();

    public HostContextPreviousSnapshot getOrDefault(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return HostContextPreviousSnapshot.NONE;
        }
        return byConversation.getOrDefault(conversationId, HostContextPreviousSnapshot.NONE);
    }

    public void recordAfterUserRow(String conversationId, String snapshotJson) {
        if (conversationId == null || conversationId.isEmpty() || snapshotJson == null) {
            return;
        }
        byConversation.put(conversationId, HostContextPreviousSnapshot.afterPersistedSnapshot(snapshotJson));
    }

    public void clearConversation(String conversationId) {
        if (conversationId != null) {
            byConversation.remove(conversationId);
        }
    }
}
