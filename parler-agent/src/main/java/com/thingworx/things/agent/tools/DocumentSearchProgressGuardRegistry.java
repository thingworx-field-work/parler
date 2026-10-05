package com.thingworx.things.agent.tools;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-turn registry for {@link DocumentSearchProgressGuard}.
 */
public final class DocumentSearchProgressGuardRegistry {

    private static final ConcurrentHashMap<String, DocumentSearchProgressGuard> GUARDS_BY_TURN =
            new ConcurrentHashMap<>();

    private DocumentSearchProgressGuardRegistry() {}

    public static DocumentSearchProgressGuard acquireForTurn(String turnKey) {
        if (turnKey == null || turnKey.isEmpty()) {
            return new DocumentSearchProgressGuard();
        }
        return GUARDS_BY_TURN.computeIfAbsent(turnKey, k -> new DocumentSearchProgressGuard());
    }

    public static void removeTurn(String turnKey) {
        if (turnKey != null && !turnKey.isEmpty()) {
            GUARDS_BY_TURN.remove(turnKey);
        }
    }
}
