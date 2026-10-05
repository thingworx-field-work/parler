package com.thingworx.things.agent.tools;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Phase G: single-line, grep-friendly audit events for Parler HITL ({@code data-operation-impl.md} §Phase G).
 * Prefix {@code PARLER_HITL} for log pipeline filtering; values are flattened (no multi-line).
 * {@code event=decision_rejected}: {@code WARN} for identity / mismatch / ownership; {@code DEBUG} for
 * {@code MISSING_*}, {@code INVALID_DECISION}, and {@code GATEWAY_SUBMIT_CONV_MISMATCH} unless {@link
 * com.thingworx.things.agent.AgentBaseThing AgentBaseThing} configuration {@code hitlAuditDebugAll} is true for the
 * relevant Agent (or any Agent when {@code agent_thing} is not yet known — see {@link #registerAgentHitlAuditDebugAll}).
 */
public final class ParlerHitlAuditLog {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ParlerHitlAuditLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Agent Thing names with {@code AgentSettings.hitlAuditDebugAll=true} (ThingWorx initializes each Agent). */
    private static final ConcurrentHashMap<String, Boolean> AGENT_HITL_AUDIT_DEBUG_ALL = new ConcurrentHashMap<>();

    private ParlerHitlAuditLog() {}

    /**
     * Called from {@code AgentBaseThing#initializeThing} when an {@code AIAgent} loads configuration. When {@code
     * enabled} is false, removes the name so other Agents' settings stay authoritative. Not invoked on configuration
     * save alone — restart the Thing (or equivalent re-init) after toggling {@code hitlAuditDebugAll}.
     */
    public static void registerAgentHitlAuditDebugAll(String agentThingName, boolean enabled) {
        if (agentThingName == null || agentThingName.isEmpty()) {
            return;
        }
        if (enabled) {
            AGENT_HITL_AUDIT_DEBUG_ALL.put(agentThingName, Boolean.TRUE);
        } else {
            AGENT_HITL_AUDIT_DEBUG_ALL.remove(agentThingName);
        }
    }

    /** Pending record stored; loop pauses for human decision. */
    public static void pendingEnqueued(PendingApprovalRecord rec, String agentThingName) {
        if (rec == null) {
            return;
        }
        LOG.info(
                "PARLER_HITL event=pending_enqueued pending_id={} principal={} conversation_id={} request_id={} "
                        + "tool_name={} agent_thing={}{}",
                safe(rec.getPendingId()),
                safe(rec.getPrincipal()),
                safe(rec.getConversationId()),
                safe(rec.getRequestId()),
                toolName(rec),
                safe(agentThingName),
                invokeTargetSuffix(rec));
    }

    /**
     * Record removed and continuation scheduled (Gateway or direct Agent service).
     *
     * @param source          {@code gateway} or {@code agent_service}
     * @param gatewayName     Gateway Thing name when source is gateway, else {@code "-"}
     * @param commentCharCount length of comment string (no body logged)
     */
    public static void decisionConsumed(String source, String gatewayName, PendingApprovalRecord rec, String decision,
            int commentCharCount) {
        if (rec == null) {
            return;
        }
        LOG.info(
                "PARLER_HITL event=decision_consumed source={} gateway={} pending_id={} principal={} "
                        + "conversation_id={} request_id={} tool_name={} decision={} comment_chars={} agent_thing={}{}",
                safe(source),
                safe(gatewayName),
                safe(rec.getPendingId()),
                safe(rec.getPrincipal()),
                safe(rec.getConversationId()),
                safe(rec.getRequestId()),
                toolName(rec),
                safe(decision),
                commentCharCount,
                safe(rec.getAgentThingName()),
                invokeTargetSuffix(rec));
    }

    /**
     * End of {@code runParlerApprovalContinuation} for cancel / reject / approve (after wire + tool result known for
     * approve).
     */
    public static void continuationOutcome(PendingApprovalRecord rec, String decision, String wireOutcome,
            boolean executed, String errorCode, String agentThingName) {
        if (rec == null) {
            return;
        }
        String code = errorCode != null && !errorCode.isEmpty() ? safe(errorCode) : "";
        LOG.info(
                "PARLER_HITL event=continuation_outcome pending_id={} principal={} conversation_id={} request_id={} "
                        + "tool_name={} decision={} wire_outcome={} executed={} code={} agent_thing={}{}",
                safe(rec.getPendingId()),
                safe(rec.getPrincipal()),
                safe(rec.getConversationId()),
                safe(rec.getRequestId()),
                toolName(rec),
                safe(decision),
                safe(wireOutcome),
                executed,
                code,
                safe(agentThingName),
                invokeTargetSuffix(rec));
    }

    /**
     * Validation failed before a pending record could be consumed. Does not log comment bodies.
     * {@code MISSING_*} / {@code INVALID_DECISION} / {@code GATEWAY_SUBMIT_CONV_MISMATCH} → {@code DEBUG} unless
     * {@code AgentSettings.hitlAuditDebugAll} applies (see class Javadoc); other reasons → {@code WARN}.
     */
    public static void decisionRejected(String reasonCode, String source, String gatewayName, String pendingId,
            String requestId, String conversationId, String decision, String principal, String agentThingOrDash) {
        boolean useDebug = decisionRejectedUseDebug(reasonCode);
        if (useDebug && shouldElevateDecisionRejectedToWarn(agentThingOrDash)) {
            useDebug = false;
        }
        if (useDebug) {
            LOG.debug(
                    "PARLER_HITL event=decision_rejected reason={} source={} gateway={} pending_id={} request_id={} "
                            + "conversation_id={} decision={} principal={} agent_thing={}",
                    safe(reasonCode),
                    safe(source),
                    safe(gatewayName),
                    safe(pendingId),
                    safe(requestId),
                    safe(conversationId),
                    safe(decision),
                    safe(principal),
                    safe(agentThingOrDash));
        } else {
            LOG.warn(
                    "PARLER_HITL event=decision_rejected reason={} source={} gateway={} pending_id={} request_id={} "
                            + "conversation_id={} decision={} principal={} agent_thing={}",
                    safe(reasonCode),
                    safe(source),
                    safe(gatewayName),
                    safe(pendingId),
                    safe(requestId),
                    safe(conversationId),
                    safe(decision),
                    safe(principal),
                    safe(agentThingOrDash));
        }
    }

    private static boolean decisionRejectedUseDebug(String reasonCode) {
        if (reasonCode == null) {
            return false;
        }
        return reasonCode.startsWith("MISSING_")
                || "INVALID_DECISION".equals(reasonCode)
                || "GATEWAY_SUBMIT_CONV_MISMATCH".equals(reasonCode);
    }

    /**
     * When at least one {@code AIAgent} has {@code hitlAuditDebugAll}, elevate DEBUG-tier rejections to WARN for that
     * agent's {@code agent_thing} name. For {@code agent_thing} {@code "-"} (name not resolved yet), elevate if any
     * agent enabled the flag so integration errors remain visible without logger-level DEBUG.
     */
    private static boolean shouldElevateDecisionRejectedToWarn(String agentThingOrDash) {
        if (AGENT_HITL_AUDIT_DEBUG_ALL.isEmpty()) {
            return false;
        }
        if (agentThingOrDash != null && !agentThingOrDash.isEmpty() && !"-".equals(agentThingOrDash)) {
            return AGENT_HITL_AUDIT_DEBUG_ALL.containsKey(agentThingOrDash);
        }
        return true;
    }

    /** TTL sweep or late submit after expiry. */
    public static void pendingExpired(PendingApprovalRecord rec) {
        if (rec == null) {
            return;
        }
        LOG.info(
                "PARLER_HITL event=pending_expired pending_id={} principal={} conversation_id={} request_id={} "
                        + "tool_name={} code=PENDING_EXPIRED agent_thing={}{}",
                safe(rec.getPendingId()),
                safe(rec.getPrincipal()),
                safe(rec.getConversationId()),
                safe(rec.getRequestId()),
                toolName(rec),
                safe(rec.getAgentThingName()),
                invokeTargetSuffix(rec));
    }

    private static String toolName(PendingApprovalRecord rec) {
        ToolCall tc = rec.getGatedToolCall();
        return tc != null ? safe(tc.getFunctionName()) : "";
    }

    /** Extra key=value for {@code invoke_service} or extended-tool targets (empty for other tools). */
    private static String invokeTargetSuffix(PendingApprovalRecord rec) {
        ToolCall tc = rec != null ? rec.getGatedToolCall() : null;
        if (tc == null) {
            return "";
        }
        if ("invoke_service".equals(tc.getFunctionName())) {
            try {
                JsonNode root = MAPPER.readTree(tc.getArguments() == null ? "{}" : tc.getArguments());
                String et = trunc(safe(root.path("entityType").asText("")), 64);
                String en = trunc(safe(root.path("entityName").asText("")), 128);
                String sn = trunc(safe(root.path("serviceName").asText("")), 128);
                return " entity_type=" + et + " entity_name=" + en + " service_name=" + sn;
            } catch (Exception e) {
                return "";
            }
        }
        String extEn = rec.getExtendedToolTargetEntityName();
        String extSv = rec.getExtendedToolTargetServiceName();
        if (extEn != null && !extEn.isEmpty() && extSv != null && !extSv.isEmpty()) {
            return " entity_name=" + trunc(safe(extEn), 128) + " service_name=" + trunc(safe(extSv), 128);
        }
        return "";
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return t.trim();
    }

    private static String trunc(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
