package com.thingworx.things.agent.recovery;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded in-process G18 retry ledger (EG3). Keyed by {@code invocationId + retryBudgetKey}.
 * Survives only current-JVM retry / HITL continuation; restart clears it. A repaired call cannot
 * reset its own allowance.
 */
public final class RetryLedger {

    /** Default finite allowance for {@link ErrorRecoveryMapper#BUDGET_KEY_SOURCE_QUERY}. */
    public static final int DEFAULT_SOURCE_QUERY_ALLOWANCE = 1;

    private static final ConcurrentHashMap<String, AtomicInteger> REMAINING = new ConcurrentHashMap<>();

    private RetryLedger() {}

    static String key(String invocationId, String retryBudgetKey) {
        String inv = invocationId == null ? "" : invocationId.trim();
        String budget = retryBudgetKey == null ? "" : retryBudgetKey.trim();
        return inv + "\u0001" + budget;
    }

    /**
     * Ensures a budget entry exists with {@code initialAllowance} remaining (no-op if already present).
     */
    public static void ensureBudget(String invocationId, String retryBudgetKey, int initialAllowance) {
        if (invocationId == null || invocationId.isBlank() || retryBudgetKey == null || retryBudgetKey.isBlank()) {
            return;
        }
        if (initialAllowance < 0) {
            throw new IllegalArgumentException("initialAllowance must be >= 0");
        }
        REMAINING.putIfAbsent(key(invocationId, retryBudgetKey), new AtomicInteger(initialAllowance));
    }

    /** Remaining allowance, or {@code 0} when unknown / absent. */
    public static int remaining(String invocationId, String retryBudgetKey) {
        if (invocationId == null || retryBudgetKey == null) {
            return 0;
        }
        AtomicInteger n = REMAINING.get(key(invocationId, retryBudgetKey));
        return n == null ? 0 : Math.max(0, n.get());
    }

    /**
     * Decrements one unit when remaining &gt; 0. Returns {@code true} if the unit was consumed.
     * Explicit decrement point for a recovery attempt / advised automatic recovery slot.
     */
    public static boolean tryConsume(String invocationId, String retryBudgetKey) {
        if (invocationId == null || invocationId.isBlank() || retryBudgetKey == null || retryBudgetKey.isBlank()) {
            return false;
        }
        ensureBudget(invocationId, retryBudgetKey, DEFAULT_SOURCE_QUERY_ALLOWANCE);
        AtomicInteger n = REMAINING.get(key(invocationId, retryBudgetKey));
        if (n == null) {
            return false;
        }
        while (true) {
            int cur = n.get();
            if (cur <= 0) {
                return false;
            }
            if (n.compareAndSet(cur, cur - 1)) {
                return true;
            }
        }
    }

    /** Test / process shutdown seam. */
    public static void clearAllForTests() {
        REMAINING.clear();
    }

    public static boolean hasEntry(String invocationId, String retryBudgetKey) {
        Objects.requireNonNull(invocationId);
        Objects.requireNonNull(retryBudgetKey);
        return REMAINING.containsKey(key(invocationId, retryBudgetKey));
    }
}
