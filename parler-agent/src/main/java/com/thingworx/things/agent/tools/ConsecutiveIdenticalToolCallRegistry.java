package com.thingworx.things.agent.tools;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-turn registry for {@link ConsecutiveIdenticalToolCallTracker} (see
 * {@code docs/agent/multi-chart-and-thrashing-safeguards.md} §3.7). Survives
 * {@link AgentToolContext#clear()} and {@code AgentLoop} pause/resume boundaries; removed when the agent loop ends
 * a turn without awaiting HITL (same condition as {@link FetchCachedReplayGuard#endTurn(String)}), and on terminal
 * Parler HITL paths that skip {@code AgentLoop.run} (see {@link com.thingworx.things.agent.AgentThing} cancel/reject
 * early returns and {@link com.thingworx.things.agent.AgentThing#deliverParlerApprovalExpired}).
 */
public final class ConsecutiveIdenticalToolCallRegistry {

    private static final int MAX_TURN_KEYS = 4096;

    private static final ConcurrentHashMap<String, ConsecutiveIdenticalToolCallTracker> TRACKERS_BY_TURN =
            new ConcurrentHashMap<>();

    private ConsecutiveIdenticalToolCallRegistry() {}

    /**
     * Returns the tracker for {@code turnKey}, creating it lazily. {@code turnKey} should match
     * {@link FetchCachedReplayGuard#resolveCurrentTurnKey()}.
     */
    public static ConsecutiveIdenticalToolCallTracker acquireForTurn(String turnKey) {
        if (turnKey == null || turnKey.isEmpty()) {
            return new ConsecutiveIdenticalToolCallTracker();
        }
        evictIfNeeded();
        return TRACKERS_BY_TURN.computeIfAbsent(turnKey, k -> new ConsecutiveIdenticalToolCallTracker());
    }

    /**
     * Drops tracker state for {@code turnKey}. Call with the same {@code turnKey} as
     * {@link FetchCachedReplayGuard#endTurn(String)} when the loop run completed without awaiting approval.
     */
    public static void removeTurn(String turnKey) {
        if (turnKey == null || turnKey.isEmpty()) {
            return;
        }
        TRACKERS_BY_TURN.remove(turnKey);
    }

    private static void evictIfNeeded() {
        while (TRACKERS_BY_TURN.size() > MAX_TURN_KEYS) {
            Iterator<String> it = TRACKERS_BY_TURN.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            TRACKERS_BY_TURN.remove(it.next());
        }
    }
}
