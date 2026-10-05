package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory cancel flags for active Parler AlwaysOn LLM turns (User ruling B — cooperative running cancel v1).
 * {@link AgentThing#ParlerStreamToRemoteThing} registers each turn; {@link ParlerGateway#CancelUserPrompt} sets the
 * flag without taking the long conversation lock; {@link AgentLoop} observes it between iterations.
 */
public final class ParlerRunningTurnCancelRegistry {

    private static final ConcurrentHashMap<String, AtomicBoolean> FLAGS = new ConcurrentHashMap<>();

    private ParlerRunningTurnCancelRegistry() {}

    private static String key(String conversationId, String requestId, String principal, String agentThingName) {
        String p = principal != null ? principal : "";
        String a = agentThingName != null ? agentThingName : "";
        return conversationId + "\u0000" + requestId + "\u0000" + p + "\u0000" + a;
    }

    /** Begin tracking a running turn (call before {@link com.thingworx.things.agent.AgentLoop#run}). */
    public static void register(String conversationId, String requestId, String principal, String agentThingName) {
        if (conversationId == null || requestId == null) {
            return;
        }
        FLAGS.put(key(conversationId.trim(), requestId.trim(), principal, agentThingName), new AtomicBoolean(false));
    }

    /** Remove tracking for this turn (idempotent). */
    public static void clear(String conversationId, String requestId, String principal, String agentThingName) {
        if (conversationId == null || requestId == null) {
            return;
        }
        FLAGS.remove(key(conversationId.trim(), requestId.trim(), principal, agentThingName));
    }

    /**
     * @return {@code 0} if no active running registration for this tuple; {@code 1} if cancel was newly requested;
     *         {@code 2} if cancel was already requested (idempotent repeat)
     */
    public static int tryRequestCancel(String conversationId, String requestId, String principal, String agentThingName) {
        if (conversationId == null || requestId == null) {
            return 0;
        }
        AtomicBoolean b = FLAGS.get(key(conversationId.trim(), requestId.trim(), principal, agentThingName));
        if (b == null) {
            return 0;
        }
        if (!b.compareAndSet(false, true)) {
            return 2;
        }
        return 1;
    }

    public static boolean isCancelRequested(String conversationId, String requestId, String principal, String agentThingName) {
        if (conversationId == null || requestId == null) {
            return false;
        }
        AtomicBoolean b = FLAGS.get(key(conversationId.trim(), requestId.trim(), principal, agentThingName));
        return b != null && b.get();
    }

    /** Same-package tests: snapshot keys (copy) for assertions. */
    static int flagCountForTests() {
        return FLAGS.size();
    }

    /** Test seam: remove every entry (isolate tests). */
    static void clearAllForTests() {
        for (String k : new ArrayList<>(FLAGS.keySet())) {
            FLAGS.remove(k);
        }
    }
}
