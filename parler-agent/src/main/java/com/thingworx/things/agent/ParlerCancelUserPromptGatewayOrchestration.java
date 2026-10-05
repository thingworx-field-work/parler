package com.thingworx.things.agent;

import java.util.Objects;

import org.json.JSONObject;

import com.thingworx.things.agent.tools.PendingApprovalRecord;
import com.thingworx.things.agent.tools.PendingApprovalStore;

/**
 * {@link AgentThing#cancelUserPromptFromParlerGateway} control flow after thread ownership + agent resolution (JUnit
 * exercises this type without loading {@link AgentThing}). Parked pending is consulted before the
 * running-turn registry so {@code CancelUserPrompt} cannot mis-handle the running→parked transition window.
 */
public final class ParlerCancelUserPromptGatewayOrchestration {

    @FunctionalInterface
    public interface ParkedApplySink {
        void apply(PendingApprovalRecord taken, String gatewayName, String reasonOrNull) throws Exception;
    }

    private ParlerCancelUserPromptGatewayOrchestration() {}

    private static String resultJson(String status, String conversationId, String requestId, Boolean alreadyRequested) {
        JSONObject o = new JSONObject();
        o.put("schemaVersion", 1);
        o.put("status", status != null ? status : "unknown");
        o.put("conversationId", conversationId != null ? conversationId : "");
        o.put("requestId", requestId != null ? requestId : "");
        if (alreadyRequested != null) {
            o.put("alreadyRequested", alreadyRequested.booleanValue());
        }
        return o.toString();
    }

    private static String parkedUserStopUnderConversationLock(
            String gatewayName,
            String cid,
            String rid,
            String agent,
            String principal,
            ParkedApplySink parkedApply,
            String reasonOrNull,
            long nowMillis) throws Exception {
        synchronized (ParlerConversationLocks.lockFor(cid)) {
            PendingApprovalRecord got =
                    PendingApprovalStore.findPendingForConversationRequestAndPrincipal(cid, rid, principal, nowMillis);
            if (got == null) {
                if (ParlerGatewayUserStopTombstoneRegistry.isGatewayUserStopTerminalActive(cid, rid, principal, agent)) {
                    return resultJson("already_terminal", cid, rid, Boolean.TRUE);
                }
                return resultJson("not_active", cid, rid, null);
            }
            if (!agent.equals(got.getAgentThingName())) {
                return resultJson("wrong_agent", cid, rid, null);
            }
            PendingApprovalRecord taken = PendingApprovalStore.compareAndRemove(got.getPendingId(), got);
            if (taken == null) {
                return resultJson("already_terminal", cid, rid, Boolean.TRUE);
            }
            ParlerGatewayUserStopTombstoneRegistry.noteGatewayUserStopTerminal(taken.getConversationId(),
                    taken.getRequestId(), taken.getPrincipal(), taken.getAgentThingName());
            parkedApply.apply(taken, gatewayName, reasonOrNull);
            return resultJson("accepted", cid, rid, Boolean.FALSE);
        }
    }

    /**
     * @see AgentThing#cancelUserPromptFromParlerGateway
     */
    public static String orchestrateAfterResolve(
            String gatewayName,
            String conversationId,
            String requestId,
            String boundAgentThingName,
            String principal,
            ParkedApplySink parkedApply,
            String reasonOrNull) throws Exception {
        Objects.requireNonNull(parkedApply, "parkedApply");
        final String cid = conversationId;
        final String rid = requestId;
        final String agent = boundAgentThingName;

        long now = System.currentTimeMillis();
        if (PendingApprovalStore.findPendingForConversationRequestAndPrincipal(cid, rid, principal, now) != null) {
            return parkedUserStopUnderConversationLock(gatewayName, cid, rid, agent, principal, parkedApply,
                    reasonOrNull, now);
        }

        int runningCancel = ParlerRunningTurnCancelRegistry.tryRequestCancel(cid, rid, principal, agent);
        if (runningCancel == 1) {
            return resultJson("accepted", cid, rid, Boolean.FALSE);
        }
        if (runningCancel == 2) {
            return resultJson("accepted", cid, rid, Boolean.TRUE);
        }

        now = System.currentTimeMillis();
        if (PendingApprovalStore.findPendingForConversationRequestAndPrincipal(cid, rid, principal, now) != null) {
            return parkedUserStopUnderConversationLock(gatewayName, cid, rid, agent, principal, parkedApply,
                    reasonOrNull, now);
        }

        if (ParlerGatewayUserStopTombstoneRegistry.isGatewayUserStopTerminalActive(cid, rid, principal, agent)) {
            return resultJson("already_terminal", cid, rid, Boolean.TRUE);
        }
        return resultJson("not_active", cid, rid, null);
    }
}
