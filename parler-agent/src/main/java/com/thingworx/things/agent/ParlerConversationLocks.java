package com.thingworx.things.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-{@code conversation_id} mutex objects for Parler Gateway + {@link AgentThing} AlwaysOn paths (extracted from
 * {@link AgentThing} so JUnit can synchronize without loading {@link AgentThing}'s static initializer).
 */
public final class ParlerConversationLocks {

    private static final ConcurrentMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private ParlerConversationLocks() {}

    /**
     * Canonical map key for conversation locks: {@code null} and blank-after-trim collapse to {@code ""}; otherwise
     * {@link String#trim()}. All acquirers must pass raw ids through this so {@link #lockFor} mutual exclusion cannot
     * silently split on surrounding whitespace.
     */
    public static String canonicalConversationLockKey(String conversationId) {
        if (conversationId == null) {
            return "";
        }
        String t = conversationId.trim();
        return t.isEmpty() ? "" : t;
    }

    /**
     * Mutex for {@code conversationId}, keyed by {@link #canonicalConversationLockKey}.
     */
    public static Object lockFor(String conversationId) {
        return LOCKS.computeIfAbsent(canonicalConversationLockKey(conversationId), k -> new Object());
    }
}
