package com.thingworx.things.agent.tools;

import java.util.List;
import java.util.UUID;

import com.thingworx.security.context.SecurityContext;
import com.thingworx.things.agent.ParlerEphemeralSystemIndices;
import com.thingworx.things.agent.ParlerHitlPendingSnapshotHelper;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.taskstate.AgentTaskState;
import com.thingworx.webservices.context.ThreadLocalContext;

/**
 * Parler AlwaysOn: enqueue {@link PendingApprovalRecord} then throw {@link ApprovalPendingException} when stream
 * context is active. Extracted from {@link com.thingworx.things.agent.AgentThing} so the put-id / throw-id contract is
 * unit-testable without constructing an {@code AgentThing}.
 */
public final class ParlerHitlStreamScopedEnqueue {

    public static final String CODE_APPROVAL_REQUIRES_PARLER_CONTEXT = "APPROVAL_REQUIRES_PARLER_CONTEXT";

    private ParlerHitlStreamScopedEnqueue() {}

    /**
     * Tool result for a call that needs human approval when no Parler stream context can hold the pending
     * approval ({@code Chat} / {@code ChatAsync}). The call is not executed.
     */
    public static String approvalRequiresParlerContextJson(String toolName) {
        String name = toolName == null ? "" : toolName.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"status\":\"blocked\",\"code\":\"" + CODE_APPROVAL_REQUIRES_PARLER_CONTEXT
                + "\",\"message\":\"" + name + " requires human approval, which is only available in a Parler "
                + "conversation. The call was not executed.\"}";
    }

    /**
     * When {@link AgentToolContext#getParlerRequestId()} and {@link AgentToolContext#getParlerActiveMessages()} are
     * primed, stores a pending record and throws {@link ApprovalPendingException} with the same id. Otherwise returns
     * without side effects (no Parler stream context); the caller must then refuse the call, for example with
     * {@link #approvalRequiresParlerContextJson(String)}.
     */
    public static void enqueueOrThrow(
            String principal,
            String agentThingName,
            ToolCall toolCall,
            String preWriteValueSnapshotJson,
            ServiceTargetEntityTypeResolution invokeServiceTypeResolution,
            InvokeServiceParameterNormalizer.Repair invokeServiceParameterRepair,
            String extendedToolTargetEntityName,
            String extendedToolTargetServiceName) throws ApprovalPendingException {
        String prid = AgentToolContext.getParlerRequestId();
        List<ChatMessage> activeMsgs = AgentToolContext.getParlerActiveMessages();
        if (prid == null || prid.isEmpty() || activeMsgs == null) {
            return;
        }
        ParlerEphemeralSystemIndices ephemeralIdx = AgentToolContext.getParlerEphemeralSystemIndices();
        List<ChatMessage> messagesForPending =
                ParlerHitlPendingSnapshotHelper.messagesForPendingSnapshot(activeMsgs, ephemeralIdx);
        String convWire = AgentToolContext.getParlerRemoteThingName();
        if (convWire == null || convWire.isEmpty()) {
            convWire = AgentToolContext.getConversationId();
        }
        String remoteThing = AgentToolContext.getParlerRemoteThingName();
        if (remoteThing == null || remoteThing.isEmpty()) {
            remoteThing = convWire;
        }
        String pendingId = UUID.randomUUID().toString();
        SecurityContext scAtCreate = ThreadLocalContext.getSecurityContext();
        long expiresAt = System.currentTimeMillis() + PendingApprovalRecord.DEFAULT_TTL_MILLIS;
        AgentTaskState taskSt = AgentToolContext.getAgentTaskState();
        List<String> dynamicSnap = taskSt != null ? taskSt.snapshotDynamicSkillShortNamesForPending() : List.of();
        PendingApprovalRecord rec = new PendingApprovalRecord(
                pendingId,
                prid,
                convWire,
                principal,
                agentThingName,
                remoteThing,
                toolCall,
                messagesForPending,
                scAtCreate,
                preWriteValueSnapshotJson,
                AgentToolContext.getUserIanaTimezone(),
                expiresAt,
                invokeServiceTypeResolution,
                invokeServiceParameterRepair,
                AgentToolContext.snapshotSlashSkillShortNamesForPending(),
                dynamicSnap,
                extendedToolTargetEntityName,
                extendedToolTargetServiceName,
                AgentToolContext.tabularChartRoundState().snapshot());
        PendingApprovalStore.put(rec);
        ParlerHitlAuditLog.pendingEnqueued(rec, agentThingName);
        throw new ApprovalPendingException(pendingId);
    }
}
