package com.thingworx.things.agent.playbook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-conversation single active Playbook run (V1a). Tracks in-flight runs only; does not interrupt an
 * already-running thread. Conversation message handling is serialized by {@code AgentThing} locks, so
 * {@link #cancel} clears reservation for the next turn rather than aborting mid-node execution.
 */
public final class PlaybookActiveRunTracker {

    private static final Map<String, String> ACTIVE_BY_CONVERSATION = new ConcurrentHashMap<>();

    private PlaybookActiveRunTracker() {}

    public static boolean tryStart(String conversationKey, String runId) {
        if (conversationKey == null || conversationKey.isEmpty()) {
            return true;
        }
        String prior = ACTIVE_BY_CONVERSATION.putIfAbsent(conversationKey, runId);
        return prior == null || prior.equals(runId);
    }

    public static void clear(String conversationKey, String runId) {
        if (conversationKey == null || conversationKey.isEmpty()) {
            return;
        }
        ACTIVE_BY_CONVERSATION.computeIfPresent(conversationKey, (k, v) -> runId.equals(v) ? null : v);
    }

    public static void cancel(String conversationKey) {
        if (conversationKey != null && !conversationKey.isEmpty()) {
            ACTIVE_BY_CONVERSATION.remove(conversationKey);
        }
    }

    public static boolean hasActive(String conversationKey) {
        return conversationKey != null && !conversationKey.isEmpty()
                && ACTIVE_BY_CONVERSATION.containsKey(conversationKey);
    }
}
