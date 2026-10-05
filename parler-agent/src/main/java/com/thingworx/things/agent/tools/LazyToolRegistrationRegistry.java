package com.thingworx.things.agent.tools;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-turn record of tool names the model has loaded via {@code load_tool_schemas} in {@code lazy} admission mode
 * (docs/operations/tool-schema-admission-control.md M3). The executor adds names during tool execution; the
 * round-filter reads them on subsequent rounds to advertise their full schemas natively. Keyed by turn (like the
 * other per-turn guards) rather than a ThreadLocal so it is correct even when tool execution runs off the agent-loop
 * thread. Removed at turn end.
 */
public final class LazyToolRegistrationRegistry {

    private static final ConcurrentHashMap<String, Set<String>> REGISTERED_BY_TURN = new ConcurrentHashMap<>();

    private LazyToolRegistrationRegistry() {}

    /** The mutable, thread-safe registered-name set for this turn (created empty on first use). */
    public static Set<String> acquireForTurn(String turnKey) {
        if (turnKey == null || turnKey.isEmpty()) {
            return Collections.synchronizedSet(new LinkedHashSet<>());
        }
        return REGISTERED_BY_TURN.computeIfAbsent(turnKey,
                k -> Collections.synchronizedSet(new LinkedHashSet<>()));
    }

    /** A stable snapshot of the registered names for this turn (empty if none). */
    public static Set<String> snapshotForTurn(String turnKey) {
        if (turnKey == null || turnKey.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> live = REGISTERED_BY_TURN.get(turnKey);
        if (live == null) {
            return Collections.emptySet();
        }
        synchronized (live) {
            return new LinkedHashSet<>(live);
        }
    }

    public static void removeTurn(String turnKey) {
        if (turnKey != null && !turnKey.isEmpty()) {
            REGISTERED_BY_TURN.remove(turnKey);
        }
    }
}
