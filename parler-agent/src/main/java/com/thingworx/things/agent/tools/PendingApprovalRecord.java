package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.security.context.SecurityContext;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Snapshot for resuming Parler AlwaysOn after {@link ApprovalPendingException}.
 * <p>
 * <b>Identity invariant:</b> instances must keep reference identity for {@link PendingApprovalStore#compareAndRemove} —
 * do not add value-based {@code equals}/{@code hashCode} keyed only on {@code pendingId} without revisiting that CAS.
 */
public final class PendingApprovalRecord {

    /** Default server-side TTL (aligns with typical {@code expires_at} horizon on {@code approval.required}). */
    public static final long DEFAULT_TTL_MILLIS = 15L * 60 * 1000;

    private final String pendingId;
    private final String requestId;
    private final String conversationId;
    private final String principal;
    private final String agentThingName;
    private final String remoteThingName;
    private final ToolCall gatedToolCall;
    private final List<ChatMessage> messagesCopy;
    private final SecurityContext securityContextAtCreate;
    /**
     * Canonical JSON text of the property value at pending creation (Phase D compare-and-stale), or {@code null} if
     * a snapshot could not be taken (stale check is skipped for this pending).
     */
    private final String preWriteValueSnapshotJson;
    /** Canonical IANA from Parler uplink at pending creation; null when absent. */
    private final String userIanaTimezone;
    private final long expiresAtEpochMillis;
    /** JVM time when this pending was constructed (ordering vs {@code AgentThreadDataTable.historyClearedAt}). */
    private final long createdAtEpochMillis;
    /**
     * When {@code invoke_service} was pre-normalized (e.g. DataTable → Thing) before HITL, preserves
     * {@code entityTypeNormalized} metadata for the executor after args rewrite.
     */
    private final ServiceTargetEntityTypeResolution invokeServiceTypeResolution;
    /**
     * When pre-HITL hoisted service fields into {@code parameters}, preserves {@code parametersNormalized} for
     * post-approval execution.
     */
    private final InvokeServiceParameterNormalizer.Repair invokeServiceParameterRepair;
    /**
     * Slash-loaded skill short names from the user message (declaration order); used to rebuild
     * {@code parler-task-checklist-v1} on AlwaysOn approval continuation ({@code docs/agent/task-state.md} § v1b
     * HITL continuity). Immutable copy; possibly empty but non-null when absent.
     */
    private final List<String> slashSkillShortNamesSnapshot;
    /**
     * v1b.2: merged dynamic {@code get_agent_skill} short ids (merge order); immutable copy; empty when none.
     */
    private final List<String> dynamicSkillShortNamesSnapshot;
    /**
     * When the gated tool is a configuration-repository extended tool with HITL, holds the resolved Thing name for
     * post-approval execution; {@code null} for {@code invoke_service} / {@code set_property_value}.
     */
    private final String extendedToolTargetEntityName;
    /** Service name on {@link #extendedToolTargetEntityName}; null when not an extended-tool pending. */
    private final String extendedToolTargetServiceName;
    /**
     * Parallel batch {@code tool_calls} after the gated call that never executed because the dispatcher paused for HITL
     * (Bug 010). Immutable; empty when N=1 or not attached yet.
     */
    private final List<ToolCall> interruptedBatchSiblingToolCalls;
    /** Exact chart source captured before pausing; never inferred from compacted transcript rows. */
    private final TabularChartRoundState.Snapshot tabularChartRoundSnapshot;

    public PendingApprovalRecord(
            String pendingId,
            String requestId,
            String conversationId,
            String principal,
            String agentThingName,
            String remoteThingName,
            ToolCall gatedToolCall,
            List<ChatMessage> messages,
            SecurityContext securityContextAtCreate,
            String preWriteValueSnapshotJson,
            String userIanaTimezone,
            long expiresAtEpochMillis,
            ServiceTargetEntityTypeResolution invokeServiceTypeResolution,
            InvokeServiceParameterNormalizer.Repair invokeServiceParameterRepair,
            List<String> slashSkillShortNamesInOrderOrNull,
            List<String> dynamicSkillShortNamesInOrderOrNull,
            String extendedToolTargetEntityName,
            String extendedToolTargetServiceName) {
        this(pendingId, requestId, conversationId, principal, agentThingName, remoteThingName, gatedToolCall, messages,
                securityContextAtCreate, preWriteValueSnapshotJson, userIanaTimezone, expiresAtEpochMillis,
                invokeServiceTypeResolution, invokeServiceParameterRepair, slashSkillShortNamesInOrderOrNull,
                dynamicSkillShortNamesInOrderOrNull, extendedToolTargetEntityName, extendedToolTargetServiceName, null);
    }

    public PendingApprovalRecord(
            String pendingId, String requestId, String conversationId, String principal, String agentThingName,
            String remoteThingName, ToolCall gatedToolCall, List<ChatMessage> messages,
            SecurityContext securityContextAtCreate, String preWriteValueSnapshotJson, String userIanaTimezone,
            long expiresAtEpochMillis, ServiceTargetEntityTypeResolution invokeServiceTypeResolution,
            InvokeServiceParameterNormalizer.Repair invokeServiceParameterRepair,
            List<String> slashSkillShortNamesInOrderOrNull, List<String> dynamicSkillShortNamesInOrderOrNull,
            String extendedToolTargetEntityName, String extendedToolTargetServiceName,
            TabularChartRoundState.Snapshot tabularChartRoundSnapshot) {
        this(pendingId, requestId, conversationId, principal, agentThingName, remoteThingName, gatedToolCall, messages,
                securityContextAtCreate, preWriteValueSnapshotJson, userIanaTimezone, expiresAtEpochMillis,
                invokeServiceTypeResolution, invokeServiceParameterRepair,
                slashSkillShortNamesInOrderOrNull, dynamicSkillShortNamesInOrderOrNull,
                extendedToolTargetEntityName, extendedToolTargetServiceName,
                System.currentTimeMillis(), List.of(), tabularChartRoundSnapshot);
    }

    private PendingApprovalRecord(
            String pendingId,
            String requestId,
            String conversationId,
            String principal,
            String agentThingName,
            String remoteThingName,
            ToolCall gatedToolCall,
            List<ChatMessage> messages,
            SecurityContext securityContextAtCreate,
            String preWriteValueSnapshotJson,
            String userIanaTimezone,
            long expiresAtEpochMillis,
            ServiceTargetEntityTypeResolution invokeServiceTypeResolution,
            InvokeServiceParameterNormalizer.Repair invokeServiceParameterRepair,
            List<String> slashSkillShortNamesInOrderOrNull,
            List<String> dynamicSkillShortNamesInOrderOrNull,
            String extendedToolTargetEntityName,
            String extendedToolTargetServiceName,
            long createdAtEpochMillis,
            List<ToolCall> interruptedBatchSiblingToolCallsOrNull,
            TabularChartRoundState.Snapshot tabularChartRoundSnapshot) {
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.pendingId = pendingId;
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.principal = principal != null ? principal : "";
        this.agentThingName = agentThingName;
        this.remoteThingName = remoteThingName;
        this.gatedToolCall = gatedToolCall;
        this.tabularChartRoundSnapshot = tabularChartRoundSnapshot;
        this.messagesCopy = new ArrayList<>(messages);
        this.securityContextAtCreate = securityContextAtCreate;
        this.preWriteValueSnapshotJson = preWriteValueSnapshotJson;
        this.userIanaTimezone = userIanaTimezone != null && !userIanaTimezone.isEmpty() ? userIanaTimezone : null;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
        this.invokeServiceTypeResolution = invokeServiceTypeResolution;
        this.invokeServiceParameterRepair = invokeServiceParameterRepair;
        if (slashSkillShortNamesInOrderOrNull == null || slashSkillShortNamesInOrderOrNull.isEmpty()) {
            this.slashSkillShortNamesSnapshot = List.of();
        } else {
            this.slashSkillShortNamesSnapshot = List.copyOf(slashSkillShortNamesInOrderOrNull);
        }
        if (dynamicSkillShortNamesInOrderOrNull == null || dynamicSkillShortNamesInOrderOrNull.isEmpty()) {
            this.dynamicSkillShortNamesSnapshot = List.of();
        } else {
            this.dynamicSkillShortNamesSnapshot = List.copyOf(dynamicSkillShortNamesInOrderOrNull);
        }
        this.extendedToolTargetEntityName = extendedToolTargetEntityName;
        this.extendedToolTargetServiceName = extendedToolTargetServiceName;
        if (interruptedBatchSiblingToolCallsOrNull == null || interruptedBatchSiblingToolCallsOrNull.isEmpty()) {
            this.interruptedBatchSiblingToolCalls = List.of();
        } else {
            this.interruptedBatchSiblingToolCalls = List.copyOf(interruptedBatchSiblingToolCallsOrNull);
        }
    }

    /**
     * Returns a new record with {@link #getInterruptedBatchSiblingToolCalls()} populated, preserving
     * {@link #getCreatedAtEpochMillis()} and all other fields. Idempotent when siblings are empty or already set.
     */
    public PendingApprovalRecord withInterruptedBatchSiblings(List<ToolCall> siblings) {
        if (siblings == null || siblings.isEmpty() || !this.interruptedBatchSiblingToolCalls.isEmpty()) {
            return this;
        }
        return new PendingApprovalRecord(
                pendingId,
                requestId,
                conversationId,
                principal,
                agentThingName,
                remoteThingName,
                gatedToolCall,
                new ArrayList<>(messagesCopy),
                securityContextAtCreate,
                preWriteValueSnapshotJson,
                userIanaTimezone,
                expiresAtEpochMillis,
                invokeServiceTypeResolution,
                invokeServiceParameterRepair,
                slashSkillShortNamesSnapshot,
                dynamicSkillShortNamesSnapshot,
                extendedToolTargetEntityName,
                extendedToolTargetServiceName,
                createdAtEpochMillis,
                siblings,
                tabularChartRoundSnapshot);
    }

    /** Legacy arity — {@code invokeServiceTypeResolution} and {@code invokeServiceParameterRepair} are {@code null}. */
    public PendingApprovalRecord(
            String pendingId,
            String requestId,
            String conversationId,
            String principal,
            String agentThingName,
            String remoteThingName,
            ToolCall gatedToolCall,
            List<ChatMessage> messages,
            SecurityContext securityContextAtCreate,
            String preWriteValueSnapshotJson,
            String userIanaTimezone,
            long expiresAtEpochMillis) {
        this(pendingId, requestId, conversationId, principal, agentThingName, remoteThingName, gatedToolCall, messages,
                securityContextAtCreate, preWriteValueSnapshotJson, userIanaTimezone, expiresAtEpochMillis, null,
                null, null, null, null, null);
    }

    /** Legacy arity — {@code invokeServiceParameterRepair} is {@code null}. */
    public PendingApprovalRecord(
            String pendingId,
            String requestId,
            String conversationId,
            String principal,
            String agentThingName,
            String remoteThingName,
            ToolCall gatedToolCall,
            List<ChatMessage> messages,
            SecurityContext securityContextAtCreate,
            String preWriteValueSnapshotJson,
            String userIanaTimezone,
            long expiresAtEpochMillis,
            ServiceTargetEntityTypeResolution invokeServiceTypeResolution) {
        this(pendingId, requestId, conversationId, principal, agentThingName, remoteThingName, gatedToolCall, messages,
                securityContextAtCreate, preWriteValueSnapshotJson, userIanaTimezone, expiresAtEpochMillis,
                invokeServiceTypeResolution, null, null, null, null, null);
    }

    public long getExpiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public boolean isExpiredAt(long nowEpochMillis) {
        return nowEpochMillis > expiresAtEpochMillis;
    }

    public String getPendingId() {
        return pendingId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getPrincipal() {
        return principal;
    }

    public String getAgentThingName() {
        return agentThingName;
    }

    public String getRemoteThingName() {
        return remoteThingName;
    }

    public ToolCall getGatedToolCall() {
        return gatedToolCall;
    }

    public List<ChatMessage> getMessagesCopy() {
        return messagesCopy;
    }

    public SecurityContext getSecurityContextAtCreate() {
        return securityContextAtCreate;
    }

    public String getPreWriteValueSnapshotJson() {
        return preWriteValueSnapshotJson;
    }

    public String getUserIanaTimezone() {
        return userIanaTimezone;
    }

    /** @return normalization from pre-HITL {@code invoke_service} prep, or {@code null} */
    public ServiceTargetEntityTypeResolution getInvokeServiceTypeResolution() {
        return invokeServiceTypeResolution;
    }

    /** @return parameter hoisting repair from pre-HITL prep, or {@code null} */
    public InvokeServiceParameterNormalizer.Repair getInvokeServiceParameterRepair() {
        return invokeServiceParameterRepair;
    }

    /**
     * @return immutable snapshot of slash skill ids for checklist rebuild; empty when not captured (pre-v1b pending
     *         or no slash skills)
     */
    public List<String> getSlashSkillShortNamesSnapshot() {
        return slashSkillShortNamesSnapshot;
    }

    /** v1b.2 dynamic skill merge order snapshot; immutable; empty when none. */
    public List<String> getDynamicSkillShortNamesSnapshot() {
        return dynamicSkillShortNamesSnapshot;
    }

    /** @return resolved Thing name for extended-tool HITL continuation, or {@code null} */
    public String getExtendedToolTargetEntityName() {
        return extendedToolTargetEntityName;
    }

    /** @return service name on {@link #getExtendedToolTargetEntityName()}, or {@code null} */
    public String getExtendedToolTargetServiceName() {
        return extendedToolTargetServiceName;
    }

    /**
     * @return immutable list of parallel batch tool calls after the gated call that did not run (Bug 010); empty when
     *         none
     */
    public List<ToolCall> getInterruptedBatchSiblingToolCalls() {
        return interruptedBatchSiblingToolCalls;
    }

    public TabularChartRoundState.Snapshot getTabularChartRoundSnapshot() {
        return tabularChartRoundSnapshot;
    }
}
