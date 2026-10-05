package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.PendingApprovalRecord;
import com.thingworx.things.agent.tools.PendingApprovalStore;

/**
 * Orchestration JUnit for {@link ParlerCancelUserPromptGatewayOrchestration} (same control flow as
 * {@link AgentThing#cancelUserPromptFromParlerGateway} after DataTable + entity resolution).
 * Kept free of {@link AgentThing} references so the suite does not depend on {@link AgentThing} static init in isolation.
 */
class CancelUserPromptOrchestrationTest {

    private static ParlerCancelUserPromptGatewayOrchestration.ParkedApplySink parkedMustNotRun() {
        return (t, gn, r) -> {
            throw new AssertionError("parked apply must not run on this path");
        };
    }

    @AfterEach
    void tearDown() {
        ParlerRunningTurnCancelRegistry.clearAllForTests();
        PendingApprovalStore.remove("cup-orch-pid-a");
        PendingApprovalStore.remove("cup-orch-pid-b");
        PendingApprovalStore.remove("cup-orch-pid-c");
        PendingApprovalStore.remove("cup-orch-pid-d");
        PendingApprovalStore.remove("cup-orch-pid-overlap");
        PendingApprovalStore.remove("cup-orch-pid-lock");
        ParlerGatewayUserStopTombstoneRegistry.putExpiryMillisForTests("cup-lock-cid", "cup-lock-rid", "orch-lock-p",
                "AgentLockOrch", System.currentTimeMillis() - 1L);
    }

    @Test
    void runningStop_acceptedThenAlreadyRequested_withoutParkedApply() throws Exception {
        String cid = "cup-run-cid";
        String rid = "cup-run-rid";
        String principal = "orch-pr-a";
        String agent = "AgentOrchA";
        ParlerRunningTurnCancelRegistry.register(cid, rid, principal, agent);

        String j1 = ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve(
                "GW", cid, rid, agent, principal, parkedMustNotRun(), null);
        JSONObject o1 = new JSONObject(j1);
        assertEquals("accepted", o1.getString("status"));
        assertFalse(o1.getBoolean("alreadyRequested"));

        String j2 = ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve(
                "GW", cid, rid, agent, principal, parkedMustNotRun(), null);
        JSONObject o2 = new JSONObject(j2);
        assertEquals("accepted", o2.getString("status"));
        assertTrue(o2.getBoolean("alreadyRequested"));
    }

    @Test
    void runningStop_doesNotInvokeParkedApplySink() throws Exception {
        AtomicBoolean parkedSinkRan = new AtomicBoolean(false);
        String cid = "cup-run-hook-cid";
        String rid = "cup-run-hook-rid";
        String principal = "orch-pr-hook";
        String agent = "AgentOrchHook";
        ParlerRunningTurnCancelRegistry.register(cid, rid, principal, agent);

        ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve("GW", cid, rid, agent, principal,
                (t, gn, r) -> parkedSinkRan.set(true), null);

        assertFalse(parkedSinkRan.get(), "running path must not invoke parked sink");
    }

    @Test
    void noPending_notActive() throws Exception {
        String cid = "cup-no-pend";
        String rid = "cup-rid-1";
        String principal = "alice";
        String agent = "AgentX";
        String j = ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve(
                "GW", cid, rid, agent, principal, parkedMustNotRun(), null);
        JSONObject o = new JSONObject(j);
        assertEquals("not_active", o.getString("status"));
        assertFalse(o.has("alreadyRequested"));
    }

    @Test
    void pendingAgentMismatch_wrongAgent() throws Exception {
        String cid = "cup-wrong-agent";
        String rid = "cup-rid-wa";
        String principal = "bob";
        String boundAgent = "AgentBound";
        ToolCall gated = new ToolCall("gt-wa", "invoke_service", "{}");
        List<ChatMessage> snap = List.of(ChatMessage.user("u"));
        long exp = System.currentTimeMillis() + 120_000;
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "cup-orch-pid-b", rid, cid, principal, "AgentOther", "RemoteR", gated, snap, null, null, null, exp);
        PendingApprovalStore.put(rec);

        String j = ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve(
                "GW", cid, rid, boundAgent, principal, parkedMustNotRun(), null);
        JSONObject o = new JSONObject(j);
        assertEquals("wrong_agent", o.getString("status"));
        assertNotNull(PendingApprovalStore.get("cup-orch-pid-b"), "pending must remain when wrong_agent");
    }

    @Test
    void parkedStop_sinkReceivesTaken_pendingRemoved_acceptedJson() throws Exception {
        String cid = "cup-parked-1";
        String rid = "cup-rid-p1";
        String principal = "carol";
        String agent = "AgentParked";
        ToolCall gated = new ToolCall("gt-p1", "invoke_service", "{}");
        List<ChatMessage> snap = new ArrayList<>();
        snap.add(ChatMessage.user("hi"));
        snap.add(ChatMessage.assistantWithToolCalls(List.of(gated)));
        long exp = System.currentTimeMillis() + 120_000;
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "cup-orch-pid-a", rid, cid, principal, agent, "RemoteR", gated, snap, null, null, null, exp);
        PendingApprovalStore.put(rec);

        List<String> capturedPendingIds = new ArrayList<>();
        String j = ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve("GWName", cid, rid, agent,
                principal, (t, gn, r) -> capturedPendingIds.add(t.getPendingId()), "user_stop");
        JSONObject o = new JSONObject(j);
        assertEquals("accepted", o.getString("status"));
        assertFalse(o.getBoolean("alreadyRequested"));
        assertEquals(List.of("cup-orch-pid-a"), capturedPendingIds);
        assertNull(PendingApprovalStore.get("cup-orch-pid-a"));
    }

    @Test
    void parkedStop_repeat_alreadyTerminal() throws Exception {
        String cid = "cup-parked-2";
        String rid = "cup-rid-p2";
        String principal = "dave";
        String agent = "AgentParked2";
        ToolCall gated = new ToolCall("gt-p2", "noop", "{}");
        List<ChatMessage> snap = List.of(ChatMessage.user("u"));
        long exp = System.currentTimeMillis() + 120_000;
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "cup-orch-pid-c", rid, cid, principal, agent, "RemoteR", gated, snap, null, null, null, exp);
        PendingApprovalStore.put(rec);

        String j1 = ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve(
                "GW", cid, rid, agent, principal, (t, gn, r) -> { /* no-op */ }, null);
        assertEquals("accepted", new JSONObject(j1).getString("status"));

        String j2 = ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve(
                "GW", cid, rid, agent, principal, parkedMustNotRun(), null);
        JSONObject o2 = new JSONObject(j2);
        assertEquals("already_terminal", o2.getString("status"));
        assertTrue(o2.getBoolean("alreadyRequested"));
    }

    /**
     * When a running-turn registration still exists but a matching pending is already visible, parked
     * user-stop must win — not the running {@code accepted} short-circuit (which would skip live terminal frames).
     */
    @Test
    void overlappingRunningRegistrationAndPending_parkedPathRuns() throws Exception {
        String cid = "cup-overlap-cid";
        String rid = "cup-overlap-rid";
        String principal = "eve";
        String agent = "AgentOverlap";
        ParlerRunningTurnCancelRegistry.register(cid, rid, principal, agent);
        ToolCall gated = new ToolCall("gt-o", "invoke_service", "{}");
        List<ChatMessage> snap = List.of(ChatMessage.user("u"));
        long exp = System.currentTimeMillis() + 120_000;
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "cup-orch-pid-overlap", rid, cid, principal, agent, "RemoteR", gated, snap, null, null, null, exp);
        PendingApprovalStore.put(rec);
        AtomicBoolean sinkRan = new AtomicBoolean(false);
        String j = ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve("GW", cid, rid, agent, principal,
                (t, gn, r) -> sinkRan.set(true), null);
        JSONObject o = new JSONObject(j);
        assertEquals("accepted", o.getString("status"));
        assertFalse(o.getBoolean("alreadyRequested"));
        assertTrue(sinkRan.get());
        assertNull(PendingApprovalStore.get("cup-orch-pid-overlap"));
    }

    /**
     * Loop-side HITL running-cancel terminalization must use the same {@link ParlerConversationLocks}
     * ordering as gateway parked stop (CAS remove + tombstone under one mutex). After that sequence, a duplicate
     * {@code CancelUserPrompt} orchestration must not return {@code not_active}.
     */
    @Test
    void hitlLoopSideTerminalization_underConversationLock_duplicateStop_alreadyTerminal() throws Exception {
        String cid = "cup-lock-cid";
        String rid = "cup-lock-rid";
        String principal = "orch-lock-p";
        String agent = "AgentLockOrch";
        ToolCall gated = new ToolCall("gt-lock", "invoke_service", "{}");
        List<ChatMessage> snap = List.of(ChatMessage.user("u"));
        long exp = System.currentTimeMillis() + 120_000;
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "cup-orch-pid-lock", rid, cid, principal, agent, "RemoteR", gated, snap, null, null, null, exp);
        PendingApprovalStore.put(rec);

        synchronized (ParlerConversationLocks.lockFor(cid)) {
            PendingApprovalRecord got = PendingApprovalStore.get("cup-orch-pid-lock");
            assertNotNull(got);
            assertNotNull(PendingApprovalStore.compareAndRemove("cup-orch-pid-lock", got));
            ParlerGatewayUserStopTombstoneRegistry.noteGatewayUserStopTerminal(cid, rid, principal, agent);
        }

        String j = ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve(
                "GW", cid, rid, agent, principal, parkedMustNotRun(), null);
        JSONObject o = new JSONObject(j);
        assertEquals("already_terminal", o.getString("status"), j);
        assertTrue(o.getBoolean("alreadyRequested"));
    }
}
