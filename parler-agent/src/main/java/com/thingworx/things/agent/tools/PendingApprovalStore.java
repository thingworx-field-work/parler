package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.thingworx.things.agent.llm.ToolCall;

/**
 * In-memory pending approvals (v1: not cross-JVM). Wire shapes: {@code CONTRACTS/API_CONTRACT.md}.
 */
public final class PendingApprovalStore {

    private static final ConcurrentHashMap<String, PendingApprovalRecord> BY_PENDING_ID = new ConcurrentHashMap<>();

    private PendingApprovalStore() {}

    public static void put(PendingApprovalRecord rec) {
        if (rec != null && rec.getPendingId() != null && !rec.getPendingId().isEmpty()) {
            BY_PENDING_ID.put(rec.getPendingId(), rec);
        }
    }

    public static PendingApprovalRecord remove(String pendingId) {
        if (pendingId == null || pendingId.isEmpty()) {
            return null;
        }
        return BY_PENDING_ID.remove(pendingId);
    }

    /**
     * Removes {@code pendingId} only if the map still holds {@code expected} (reference equality).
     * Use with {@link #get} for atomic consume vs concurrent gateway cancellation (turn-cancellation-control).
     *
     * @return {@code expected} if removed, else {@code null}
     */
    public static PendingApprovalRecord compareAndRemove(String pendingId, PendingApprovalRecord expected) {
        if (pendingId == null || pendingId.trim().isEmpty() || expected == null) {
            return null;
        }
        String pid = pendingId.trim();
        if (BY_PENDING_ID.remove(pid, expected)) {
            return expected;
        }
        return null;
    }

    public static PendingApprovalRecord get(String pendingId) {
        if (pendingId == null || pendingId.isEmpty()) {
            return null;
        }
        return BY_PENDING_ID.get(pendingId);
    }

    /**
     * Non-expired pending whose {@code conversation_id}, {@code request_id}, and {@code principal} match — used by
     * {@code ParlerGateway.CancelUserPrompt} before {@link #compareAndRemove}. Does not remove the entry.
     */
    public static PendingApprovalRecord findPendingForConversationRequestAndPrincipal(
            String conversationId,
            String requestId,
            String principal,
            long nowEpochMillis) {
        if (conversationId == null || conversationId.trim().isEmpty()
                || requestId == null || requestId.trim().isEmpty()
                || principal == null || principal.trim().isEmpty()) {
            return null;
        }
        String cid = conversationId.trim();
        String rid = requestId.trim();
        String pr = principal.trim();
        for (PendingApprovalRecord rec : BY_PENDING_ID.values()) {
            if (rec == null || rec.isExpiredAt(nowEpochMillis)) {
                continue;
            }
            if (cid.equals(rec.getConversationId()) && rid.equals(rec.getRequestId()) && pr.equals(rec.getPrincipal())) {
                return rec;
            }
        }
        return null;
    }

    /**
     * After {@link com.thingworx.things.agent.AgentLoop} catches {@link ApprovalPendingException} for tool index {@code i},
     * binds remaining parallel {@code tool_calls} ({@code i+1..end}) to the pending record so continuation can emit
     * synthetic {@code role: tool} rows (Bug 010).
     *
     * @return {@code true} if the map entry was replaced
     */
    public static boolean attachInterruptedBatchSiblings(String pendingId, List<ToolCall> siblings) {
        if (pendingId == null || pendingId.isEmpty() || siblings == null || siblings.isEmpty()) {
            return false;
        }
        PendingApprovalRecord cur = get(pendingId);
        if (cur == null) {
            return false;
        }
        PendingApprovalRecord updated = cur.withInterruptedBatchSiblings(siblings);
        if (updated == cur) {
            return false;
        }
        put(updated);
        return true;
    }

    /** True if any non-expired pending record is tied to {@code conversationId} (trimmed). */
    public static boolean hasPendingForConversationId(String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return false;
        }
        String cid = conversationId.trim();
        long now = System.currentTimeMillis();
        for (PendingApprovalRecord rec : BY_PENDING_ID.values()) {
            if (rec != null && cid.equals(rec.getConversationId()) && !rec.isExpiredAt(now)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes each record whose TTL has passed and invokes {@code onRemoved} with that record (already absent from the map).
     *
     * @return number of records removed
     */
    public static int sweepExpired(long nowEpochMillis, Consumer<PendingApprovalRecord> onRemoved) {
        int n = 0;
        for (String id : new ArrayList<>(BY_PENDING_ID.keySet())) {
            PendingApprovalRecord rec = BY_PENDING_ID.get(id);
            if (rec != null && rec.isExpiredAt(nowEpochMillis)) {
                if (BY_PENDING_ID.remove(id, rec)) {
                    onRemoved.accept(rec);
                    n++;
                }
            }
        }
        return n;
    }
}
