package com.thingworx.things.agent;

import java.lang.reflect.Method;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.llm.ratecontrol.LlmRateLimitAdmissionReason;
import com.thingworx.things.Thing;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Builds Parler wire JSON (AlwaysOn profile) and invokes the edge {@code ReceiveMessage} remote service.
 * Uses reflection for {@code RemoteThing#callService} so the extension JAR does not need the communications
 * API on the compile classpath (see {@code IEndpointBindingObserver}).
 */
public final class ParlerReceiveMessageSupport {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ParlerReceiveMessageSupport.class);

    private static final String REMOTE_THING_CLASS = "com.thingworx.things.connected.RemoteThing";

    private ParlerReceiveMessageSupport() {}

    /**
     * @return {@code false} if the invoke did not complete successfully (caller must assume the downlink may be broken)
     */
    public static boolean send(Thing conversation, String jsonPayload) {
        if (conversation == null || jsonPayload == null) {
            return false;
        }
        try {
            Class<?> rtc = Class.forName(REMOTE_THING_CLASS);
            if (!rtc.isInstance(conversation)) {
                LOG.warn("[{}] ReceiveMessage skipped: not a RemoteThing", conversation.getName());
                return false;
            }
            Method callService = rtc.getMethod("callService", String.class, ValueCollection.class, BaseTypes.class);
            ValueCollection vc = new ValueCollection();
            vc.put("payload", new StringPrimitive(jsonPayload));
            callService.invoke(conversation, "ReceiveMessage", vc, BaseTypes.NOTHING);
            return true;
        } catch (Exception e) {
            LOG.warn("[{}] ReceiveMessage failed: {}", conversation.getName(), e.getMessage());
            return false;
        }
    }

    public static String wireSessionAck(String requestId, String conversationId) {
        JSONObject o = base(requestId, conversationId);
        o.put("type", "session.ack");
        return o.toString();
    }

    public static String wireActivity(String requestId, String conversationId, String message) {
        JSONObject o = base(requestId, conversationId);
        o.put("type", "activity");
        o.put("message", message != null ? message : "");
        return o.toString();
    }

    /**
     * Optional live UI status for provider local rate-control waits ({@code docs/agent/rate-control-ui-status.md},
     * {@code CONTRACTS/API_CONTRACT.md}).
     */
    public static String wireRateControlStatus(
            String requestId,
            String conversationId,
            boolean waiting,
            LlmRateLimitAdmissionReason reasonOrNull,
            long waitMs,
            long retryAfterMs) {
        JSONObject o = base(requestId, conversationId);
        o.put("type", "rate_control.status");
        o.put("status", waiting ? "waiting" : "resumed");
        if (waiting && reasonOrNull != null) {
            o.put("reason", reasonOrNull.wireValue());
        }
        if (waiting && waitMs > 0) {
            o.put("wait_ms", waitMs);
        }
        if (waiting && retryAfterMs > 0) {
            o.put("retry_after_ms", retryAfterMs);
        }
        return o.toString();
    }

    public static String wireContentDelta(String requestId, String conversationId, String delta) {
        JSONObject o = base(requestId, conversationId);
        o.put("type", "content.delta");
        o.put("delta", delta != null ? delta : "");
        return o.toString();
    }

    /**
     * Parler wire {@code type: "chart"}; {@code chart} must match {@code ChartBlock} in Parler {@code CONTRACTS/CHART_CONTRACT.md}.
     */
    public static String wireChart(String requestId, String conversationId, JSONObject chart) {
        JSONObject o = base(requestId, conversationId);
        o.put("type", "chart");
        o.put("chart", chart != null ? chart : new JSONObject());
        return o.toString();
    }

    /**
     * Parler wire {@code type: "table"}; {@code table} must match {@code TableBlock} in Parler {@code CONTRACTS/TABLE_CONTRACT.md}.
     * Delegates to {@link ParlerTableWire#toWireJson} (pure {@code org.json}) for offline-testable JSON construction.
     */
    public static String wireTable(String requestId, String conversationId, JSONObject table) {
        return ParlerTableWire.toWireJson(requestId, conversationId, table);
    }

    /** Parler wire {@code type: "chart_group"} (C3b-1); {@code manifest} is a full {@code ChartGroupManifest}. */
    public static String wireChartGroup(String requestId, String conversationId, JSONObject manifest) {
        return ParlerChartGroupWire.toWireJson(requestId, conversationId, manifest);
    }

    /**
     * Parler wire {@code type: "task.state"} (v1b); snapshot fields must stay metadata-only per
     * {@code docs/agent/task-state.md}.
     */
    public static String wireTaskState(
            String requestId,
            String conversationId,
            int schemaVersion,
            String status,
            String titleOrNull,
            JSONObject summary,
            JSONArray items) {
        JSONObject o = base(requestId, conversationId);
        o.put("type", "task.state");
        o.put("schemaVersion", schemaVersion);
        o.put("status", status != null ? status : "executing");
        if (titleOrNull != null && !titleOrNull.isEmpty()) {
            o.put("title", titleOrNull);
        }
        o.put("summary", summary != null ? summary : new JSONObject());
        o.put("items", items != null ? items : new JSONArray());
        return o.toString();
    }

    public static String wireDone(String requestId, String conversationId) {
        return wireDone(requestId, conversationId, null, null);
    }

    /**
     * @param assistantMessageIdOrNull when non-empty, included as {@code assistant_message_id} for feedback anchoring
     */
    public static String wireDone(String requestId, String conversationId, String assistantMessageIdOrNull) {
        return wireDone(requestId, conversationId, assistantMessageIdOrNull, null);
    }

    /**
     * @param assistantMessageIdOrNull when non-empty, included as {@code assistant_message_id} for feedback anchoring
     * @param llmUsageJsonOrNull optional compact usage JSON from {@link StreamTokenUsage#getLlmUsageJson()} — sanitized
     *            to {@code llm_usage} object (no prompts or tool bodies)
     */
    public static String wireDone(String requestId, String conversationId, String assistantMessageIdOrNull,
            String llmUsageJsonOrNull) {
        JSONObject o = base(requestId, conversationId);
        o.put("type", "done");
        if (assistantMessageIdOrNull != null && !assistantMessageIdOrNull.trim().isEmpty()) {
            o.put("assistant_message_id", assistantMessageIdOrNull.trim());
            o.put("completed_at", ISODateTimeFormat.dateTime().withZone(DateTimeZone.UTC)
                    .print(DateTime.now(DateTimeZone.UTC)));
            JSONObject usage = ParlerLlmUsageWireSanitizer.sanitizeLlmUsageJson(llmUsageJsonOrNull);
            if (usage != null && usage.length() > 0) {
                o.put("llm_usage", usage);
            }
        }
        return o.toString();
    }

    public static String wireError(String requestId, String conversationId, String message, String code) {
        JSONObject o = base(requestId, conversationId);
        o.put("type", "error");
        o.put("message", message != null ? message : "error");
        if (code != null && !code.isEmpty()) {
            o.put("code", code);
        }
        return o.toString();
    }

    /**
     * Terminal user stop — fourth **`busy`**-clearing frame alongside **`done`**, **`error`**, **`superseded`**
     * ({@code CONTRACTS/API_CONTRACT.md}, {@code docs/agent/turn-cancellation-control.md}).
     */
    public static String wireSessionCancelled(
            String requestId,
            String conversationId,
            String reasonOrNull,
            String messageOrNull) {
        JSONObject o = base(requestId, conversationId);
        o.put("type", "session.cancelled");
        if (reasonOrNull != null && !reasonOrNull.trim().isEmpty()) {
            o.put("reason", reasonOrNull.trim());
        }
        if (messageOrNull != null && !messageOrNull.trim().isEmpty()) {
            o.put("message", messageOrNull.trim());
        }
        return o.toString();
    }

    /**
     * Parked gateway user-stop live downlink: {@code approval.resolved} then {@code session.cancelled} (ordering
     * invariant for {@link AgentThing#applyGatewayUserStopParkedToConversation}; JUnit-safe entrypoint).
     */
    public static void sendParkedGatewayUserStopLiveWirePair(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            String pendingId,
            String reasonOrNull) {
        if (remoteConversation == null) {
            return;
        }
        String wireReason = reasonOrNull != null && !reasonOrNull.trim().isEmpty() ? reasonOrNull.trim() : "user_stop";
        String wireMsg = "Turn stopped.";
        send(remoteConversation,
                wireApprovalResolved(wireRequestId, wireConversationId, pendingId,
                        "cancelled", false, null, null, "gateway_user_stop"));
        send(remoteConversation,
                wireSessionCancelled(wireRequestId, wireConversationId, wireReason, wireMsg));
    }

    /** HITL gate — see {@code CONTRACTS/API_CONTRACT.md}. */
    public static String wireApprovalRequired(
            String requestId,
            String conversationId,
            String pendingId,
            String expiresAtIso,
            String toolName,
            JSONObject summary,
            JSONArray actions) {
        JSONObject o = base(requestId, conversationId);
        o.put("type", "approval.required");
        o.put("pending_id", pendingId != null ? pendingId : "");
        o.put("expires_at", expiresAtIso != null ? expiresAtIso : "");
        o.put("tool_name", toolName != null ? toolName : "");
        o.put("summary", summary != null ? summary : new JSONObject());
        o.put("actions", actions != null ? actions : new JSONArray());
        return o.toString();
    }

    public static String wireApprovalResolved(
            String requestId,
            String conversationId,
            String pendingId,
            String outcome,
            Boolean executed,
            String errorCode,
            String errorMessage) {
        return wireApprovalResolved(requestId, conversationId, pendingId, outcome, executed, errorCode, errorMessage,
                null);
    }

    /**
     * @param hitlResolutionSourceOrNull v1: when non-blank, emitted as {@code hitl_resolution_source} (only
     *            {@code gateway_user_stop} is valid on the wire — {@code CONTRACTS/API_CONTRACT.md})
     */
    public static String wireApprovalResolved(
            String requestId,
            String conversationId,
            String pendingId,
            String outcome,
            Boolean executed,
            String errorCode,
            String errorMessage,
            String hitlResolutionSourceOrNull) {
        JSONObject o = base(requestId, conversationId);
        o.put("type", "approval.resolved");
        o.put("pending_id", pendingId != null ? pendingId : "");
        o.put("outcome", outcome != null ? outcome : "");
        if (executed != null) {
            o.put("executed", executed.booleanValue());
        }
        if (errorMessage != null && !errorMessage.isEmpty()) {
            JSONObject err = new JSONObject();
            if (errorCode != null && !errorCode.isEmpty()) {
                err.put("code", errorCode);
            }
            err.put("message", errorMessage);
            o.put("error", err);
        }
        if (hitlResolutionSourceOrNull != null && !hitlResolutionSourceOrNull.trim().isEmpty()) {
            o.put("hitl_resolution_source", hitlResolutionSourceOrNull.trim());
        }
        return o.toString();
    }

    private static JSONObject base(String requestId, String conversationId) {
        JSONObject o = new JSONObject();
        o.put("request_id", requestId != null ? requestId : "");
        o.put("conversation_id", conversationId != null ? conversationId : "");
        return o;
    }
}
