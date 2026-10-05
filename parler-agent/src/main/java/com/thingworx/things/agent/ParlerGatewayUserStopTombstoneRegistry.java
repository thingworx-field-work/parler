package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory tombstones for successful {@link ParlerGateway#CancelUserPrompt} parked-HITL completions so a
 * racing or repeated stop for the same {@code (conversation_id, request_id, principal, agentThingName)} returns
 * {@code already_terminal} instead of {@code not_active} (see {@code docs/agent/turn-cancellation-control.md} §7).
 * Entries expire after the v1 default TTL; they hold no handles or payloads.
 * Expired keys are swept on write and on read so idle stops do not grow the map without bound.
 */
public final class ParlerGatewayUserStopTombstoneRegistry {

    /** Matches {@code docs/agent/turn-cancellation-control.md} registry tombstone default. */
    private static final long TOMBSTONE_TTL_MS = 5L * 60L * 1000L;

    private static final ConcurrentHashMap<String, Long> EXPIRY_EPOCH_MS = new ConcurrentHashMap<>();

    private ParlerGatewayUserStopTombstoneRegistry() {}

    private static String key(String conversationId, String requestId, String principal, String agentThingName) {
        String p = principal != null ? principal : "";
        String a = agentThingName != null ? agentThingName : "";
        return conversationId + "\u0000" + requestId + "\u0000" + p + "\u0000" + a;
    }

    private static void sweepExpiredEntries(long nowEpochMillis) {
        for (String k : new ArrayList<>(EXPIRY_EPOCH_MS.keySet())) {
            Long exp = EXPIRY_EPOCH_MS.get(k);
            if (exp != null && nowEpochMillis > exp.longValue()) {
                EXPIRY_EPOCH_MS.remove(k, exp);
            }
        }
    }

    /**
     * Record a gateway user-stop tombstone for this tuple. Call <strong>immediately after</strong>
     * {@link com.thingworx.things.agent.tools.PendingApprovalStore#compareAndRemove} wins for the parked gateway-stop
     * path — before transcript mutation or network sends — so a concurrent {@code CancelUserPrompt} lock-free pre-check
     * observes {@link #isGatewayUserStopTerminalActive} and returns {@code already_terminal}.
     */
    public static void noteGatewayUserStopTerminal(String conversationId, String requestId, String principal,
            String agentThingName) {
        if (conversationId == null || requestId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        sweepExpiredEntries(now);
        long exp = now + TOMBSTONE_TTL_MS;
        EXPIRY_EPOCH_MS.put(key(conversationId.trim(), requestId.trim(), principal, agentThingName), Long.valueOf(exp));
    }

    /**
     * @return {@code true} if a non-expired gateway user-stop tombstone exists for this tuple.
     */
    public static boolean isGatewayUserStopTerminalActive(String conversationId, String requestId, String principal,
            String agentThingName) {
        if (conversationId == null || requestId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        sweepExpiredEntries(now);
        String k = key(conversationId.trim(), requestId.trim(), principal, agentThingName);
        Long exp = EXPIRY_EPOCH_MS.get(k);
        if (exp == null) {
            return false;
        }
        if (now > exp.longValue()) {
            EXPIRY_EPOCH_MS.remove(k, exp);
            return false;
        }
        return true;
    }

    /** Same-package tests: insert an entry that is already expired so the next note/sweep removes it. */
    static void putExpiryMillisForTests(String conversationId, String requestId, String principal, String agentThingName,
            long expiryEpochMillis) {
        EXPIRY_EPOCH_MS.put(key(conversationId.trim(), requestId.trim(), principal, agentThingName),
                Long.valueOf(expiryEpochMillis));
    }
}
