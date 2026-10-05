package com.thingworx.things.agent.tools;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.thingworx.types.InfoTable;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;

/**
 * Per-turn presentation artifact registry (Answer Presentation Phase). Keyed like
 * {@link ConsecutiveIdenticalToolCallRegistry}: survives {@link AgentToolContext#clear()} across HITL pause/resume;
 * removed with {@link #removeTurn(String)} when the agent loop ends a segment without awaiting approval
 * ({@link com.thingworx.things.agent.AgentLoop#run} finally), and on terminal HITL paths in {@code AgentThing}.
 */
public final class PresentationArtifactRegistry {

    private static final int MAX_TURN_KEYS = 4096;

    private static final ConcurrentHashMap<String, LinkedHashMap<String, PresentationArtifactRecord>> BY_TURN =
            new ConcurrentHashMap<>();

    private PresentationArtifactRegistry() {}

    /**
     * Registers or replaces a complete tabular artifact keyed by {@code cacheId} (the single identifier).
     */
    public static void registerTabulateArtifact(String turnKey, PresentationArtifactRecord record) {
        if (turnKey == null || turnKey.isEmpty() || record == null) {
            return;
        }
        String cid = record.getCacheId();
        if (cid.isEmpty()) {
            return;
        }
        evictIfNeeded();
        BY_TURN.computeIfAbsent(turnKey, k -> new LinkedHashMap<>()).put(cid, record);
    }

    /**
     * @return whether this turn has at least one {@link PresentationArtifactRecord} that is complete and resolves
     *         to a non-empty cached {@link InfoTable}.
     */
    public static boolean hasCompleteChartable(String turnKey) {
        return hasCompleteChartable(turnKey, java.util.Set.of());
    }

    /**
     * Same, ignoring artifacts whose {@code cacheId} is in {@code alreadyChartedCacheIds}: an artifact that a
     * downlinked chart was built from does not need another presentation round.
     */
    public static boolean hasCompleteChartable(String turnKey, java.util.Set<String> alreadyChartedCacheIds) {
        if (turnKey == null || turnKey.isEmpty()) {
            return false;
        }
        LinkedHashMap<String, PresentationArtifactRecord> m = BY_TURN.get(turnKey);
        if (m == null || m.isEmpty()) {
            return false;
        }
        for (PresentationArtifactRecord r : m.values()) {
            if (r == null || !r.isComplete()) {
                continue;
            }
            if (alreadyChartedCacheIds != null && alreadyChartedCacheIds.contains(r.getCacheId())) {
                continue;
            }
            InfoTable t;
            try {
                t = InvokeServiceExecutor.lookupCachedInfotable(r.getCacheId());
            } catch (ArtifactCacheException e) {
                ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
                continue;
            }
            if (t != null && t.getRowCount() > 0) {
                return true;
            }
        }
        return false;
    }

    /** Ordered cache ids for tests / deterministic multi-chart execution. */
    public static java.util.List<String> orderedCacheIds(String turnKey) {
        LinkedHashMap<String, PresentationArtifactRecord> m = turnKey == null ? null : BY_TURN.get(turnKey);
        if (m == null || m.isEmpty()) {
            return java.util.List.of();
        }
        return java.util.List.copyOf(m.keySet());
    }

    public static void removeTurn(String turnKey) {
        if (turnKey == null || turnKey.isEmpty()) {
            return;
        }
        BY_TURN.remove(turnKey);
    }

    private static void evictIfNeeded() {
        while (BY_TURN.size() > MAX_TURN_KEYS) {
            Iterator<String> it = BY_TURN.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            BY_TURN.remove(it.next());
        }
    }
}
