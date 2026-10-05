package com.thingworx.things.agent.tools;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per submitted user-turn counts for {@code fetch_cached_result} paging (see
 * {@code docs/agent/large-table-replay-control.md} §4.4). Survives {@link AgentToolContext#clear()} so HITL
 * continuation loops share the same turn key as the pre-approval segment.
 */
public final class FetchCachedReplayGuard {

    private static final char SEP = '\u0001';

    /** Defensive cap on static turn-key entries; not LRU — drops arbitrary keys when over limit. */
    private static final int MAX_TURN_KEYS = 4096;

    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>> COUNTS_BY_TURN =
            new ConcurrentHashMap<>();

    private FetchCachedReplayGuard() {}

    /** Stable key for an AlwaysOn / post-HITL segment (same {@code request_id} across continuation). */
    public static String turnKey(String conversationId, String requestId) {
        String c = conversationId != null && !conversationId.isEmpty() ? conversationId : AgentToolContext.SINGLE_TURN_CONVERSATION_ID;
        String r = requestId != null && !requestId.isEmpty() ? requestId : "_";
        return c + SEP + r;
    }

    /** Chat(sync/async) turn key: new nonce per user message. */
    public static String turnKeyForChat(String conversationId, String chatTurnNonce) {
        String c = conversationId != null && !conversationId.isEmpty() ? conversationId : AgentToolContext.SINGLE_TURN_CONVERSATION_ID;
        String n = chatTurnNonce != null && !chatTurnNonce.isEmpty() ? chatTurnNonce : "_";
        return c + SEP + n;
    }

    public static String resolveCurrentTurnKey() {
        String rid = AgentToolContext.getParlerRequestId();
        if (rid != null && !rid.isEmpty()) {
            return turnKey(AgentToolContext.getConversationId(), rid);
        }
        String nonce = AgentToolContext.getFetchCachedChatTurnNonce();
        if (nonce != null && !nonce.isEmpty()) {
            return turnKeyForChat(AgentToolContext.getConversationId(), nonce);
        }
        return turnKey(AgentToolContext.getConversationId(), "_");
    }

    /**
     * Starts a fresh paging counter map for this turn (new user message). Do not call for HITL continuation
     * loops — they must reuse the pre-approval map.
     */
    public static void beginTurn(String turnKey) {
        if (turnKey == null || turnKey.isEmpty()) {
            return;
        }
        COUNTS_BY_TURN.put(turnKey, new ConcurrentHashMap<>());
        evictExcessTurnKeys();
    }

    /** Removes paging counters for a completed turn (not used while awaiting HITL approval). */
    public static void endTurn(String turnKey) {
        if (turnKey == null || turnKey.isEmpty()) {
            return;
        }
        COUNTS_BY_TURN.remove(turnKey);
    }

    private static void evictExcessTurnKeys() {
        while (COUNTS_BY_TURN.size() > MAX_TURN_KEYS) {
            Iterator<String> it = COUNTS_BY_TURN.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            COUNTS_BY_TURN.remove(it.next());
        }
    }

    /** @return 1-based ordinal for this {@code cacheId} within the turn (first call → 1). */
    public static int incrementAndGetOrdinal(String turnKey, String cacheId) {
        if (turnKey == null || cacheId == null || cacheId.isEmpty()) {
            return 1;
        }
        ConcurrentHashMap<String, AtomicInteger> inner =
                COUNTS_BY_TURN.computeIfAbsent(turnKey, k -> new ConcurrentHashMap<>());
        AtomicInteger c = inner.computeIfAbsent(cacheId, k -> new AtomicInteger(0));
        return c.incrementAndGet();
    }
}
