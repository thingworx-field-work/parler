package com.thingworx.things.agent;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;

import com.thingworx.entities.RootEntity;
import com.thingworx.security.context.SecurityContext;
import com.thingworx.metadata.annotations.ThingworxBaseTemplateDefinition;
import com.thingworx.metadata.annotations.ThingworxImplementedShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxImplementedShapeDefinitions;
import com.thingworx.metadata.annotations.ThingworxServiceDefinition;
import com.thingworx.metadata.annotations.ThingworxServiceParameter;
import com.thingworx.metadata.annotations.ThingworxServiceResult;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.configrepo.ParlerPackageVersion;
import com.thingworx.things.agent.tools.ParlerHitlAuditLog;
import com.thingworx.things.connected.SDKGateway;
import com.thingworx.webservices.context.ThreadLocalContext;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transient AlwaysOn gateway: Thing name equals {@code conversationId} in {@link AgentThreadDataTableSupport#THREAD_DATA_TABLE_NAME}.
 * {@link #SubmitUserPrompt} validates ownership then delegates to {@link AgentThing#ParlerStreamToRemoteThing}; edge receives Parler
 * wire frames via {@code ReceiveMessage}. {@link #SubmitApprovalDecision} is the v1 uplink for HITL (maps to
 * {@code approval.decision} fields in {@code CONTRACTS/API_CONTRACT.md}). {@link #CancelUserPrompt} is the v1 uplink for
 * user stop on parked HITL (see {@code docs/agent/turn-cancellation-control.md} §10.3).
 */
@ThingworxImplementedShapeDefinitions(shapes = { @ThingworxImplementedShapeDefinition(name = "Gateway") })
@ThingworxBaseTemplateDefinition(name = "SDKGateway")
public class ParlerGateway extends SDKGateway {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(ParlerGateway.class);
    private static final String AI_AGENT_TEMPLATE_NAME = "AIAgent";

    @ThingworxServiceDefinition(
        name = "SubmitUserPrompt",
        description = "Start a Parler AlwaysOn turn: runs AIAgent.ParlerStreamToRemoteThing with this Gateway's name "
            + "(conversationId) as remoteConversationThingName (must be connected). "
            + "Caller must own the thread row in AgentThreadDataTable.")
    @ThingworxServiceResult(name = "result", baseType = "STRING",
        description = "request_id from ParlerStreamToRemoteThing")
    public String SubmitUserPrompt(
        @ThingworxServiceParameter(name = "message", baseType = "STRING",
            description = "User message") String message,
        @ThingworxServiceParameter(name = "agentThingName", baseType = "STRING",
            description = "Thing name of an agent whose template is derived from AIAgent",
            aspects = {"isRequired:true"}) String agentThingName,
        @ThingworxServiceParameter(name = "systemPrompt", baseType = "TEXT",
            description = "Optional system prompt override",
            aspects = {"isRequired:false"}) String systemPrompt,
        @ThingworxServiceParameter(name = "userTimezone", baseType = "STRING",
            description = "Optional IANA zone from the browser (e.g. America/New_York); aligns with chat.request user_timezone",
            aspects = {"isRequired:false"}) String userTimezone,
        @ThingworxServiceParameter(name = "hostContext", baseType = "STRING",
            description = "Optional UTF-8 JSON text of host-scope sideband (HostScopeJson); CONTRACTS/API_CONTRACT.md hostContext",
            aspects = {"isRequired:false"}) String hostContext
    ) throws Exception {
        if (agentThingName == null || agentThingName.trim().isEmpty()) {
            throw new Exception("[" + getName() + "] SubmitUserPrompt: agentThingName is required.");
        }
        String userMessage = message == null ? "" : message.trim();
        if (userMessage.isEmpty()) {
            throw new Exception("[" + getName() + "] SubmitUserPrompt: message is empty.");
        }
        AgentThreadDataTableSupport.ensureConversationOwnedByCurrentUser(getName(),
                "[" + getName() + "] SubmitUserPrompt");

        String agentName = agentThingName.trim();
        RootEntity ent = PlatformAccess.findAsUser(agentName, RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (ent == null || !(ent instanceof AgentThing)) {
            throw new Exception("[" + getName() + "] SubmitUserPrompt: no AIAgent Thing named \"" + agentName + "\".");
        }
        AgentThing agent = (AgentThing) ent;
        ThingTemplateSupport.ensureThingDerivedFromTemplate(
                agent, AI_AGENT_TEMPLATE_NAME, "[" + getName() + "] SubmitUserPrompt");

        return agent.ParlerStreamToRemoteThing(message, systemPrompt, getName(), userTimezone, hostContext);
    }

    @ThingworxServiceDefinition(
        name = "CancelUserPrompt",
        description = "User stop on the AlwaysOn Gateway: when a parked HITL approval exists for the given "
                + "request_id, consumes the pending record (CAS), appends durable synthetic tool results, emits "
                + "approval.resolved (hitl_resolution_source gateway_user_stop) then session.cancelled — no post-approval LLM. "
                + "Caller must own the AgentThreadDataTable row for this Gateway name. Returns JSON envelope schemaVersion 1 "
                + "(see docs/agent/turn-cancellation-control.md §6.1).")
    @ThingworxServiceResult(name = "result", baseType = "STRING",
        description = "JSON: schemaVersion, status, conversationId, requestId, optional alreadyRequested")
    public String CancelUserPrompt(
        @ThingworxServiceParameter(name = "requestId", baseType = "STRING",
            description = "Assistant request_id to cancel (must match approval.required.request_id when parked)",
            aspects = {"isRequired:true"}) String requestId,
        @ThingworxServiceParameter(name = "agentThingName", baseType = "STRING",
            description = "Must equal AgentThreadDataTable agentName for this Gateway (conversationId)",
            aspects = {"isRequired:true"}) String agentThingName,
        @ThingworxServiceParameter(name = "reason", baseType = "STRING",
            description = "Optional stop reason (e.g. user_stop); echoed on session.cancelled when emitted",
            aspects = {"isRequired:false"}) String reason
    ) throws Exception {
        return AgentThing.cancelUserPromptFromParlerGateway(this, requestId, agentThingName, reason);
    }

    @ThingworxServiceDefinition(
        name = "GetConversationHistoryJson",
        description = "AlwaysOn / parler-ui: return one-shot ai-parler-history-v1 JSON for this Gateway name (conversationId) "
            + "from AgentMessageStream. Caller must own the thread row in AgentThreadDataTable. "
            + "Optional maxItems caps QueryStreamData row count (platform default 500 if omitted). "
            + "Edge widget uses stringFromInvokeResult + hydrateHistoryFromJsonString (see parler docs/ui/load-history.md).")
    @ThingworxServiceResult(name = "result", baseType = "STRING",
        description = "JSON document: format ai-parler-history-v1 + rows[]")
    public String GetConversationHistoryJson(
        @ThingworxServiceParameter(
            name = "maxItems",
            baseType = "NUMBER",
            description = "Maximum stream rows to read (1..20000; effective default 500 when null or non-finite)",
            aspects = { "isRequired:false" }) Double maxItems)
            throws Exception {
        String prefix = "[" + getName() + "] GetConversationHistoryJson";
        ConversationMetadata meta = AgentThreadDataTableSupport.loadConversationMetadataForCurrentUser(getName(), prefix);
        return AgentMessageStreamHistoryExporter.exportHistoryJsonString(getName(), maxItems,
                meta.getHistoryClearedAtOrNull());
    }

    @ThingworxServiceDefinition(
        name = "GetConnectionInfo",
        description = "AlwaysOn: narrow JSON with agent extension display version for this conversation's bound AIAgent. "
            + "Caller must own the thread row. agentThingName must equal the row's agentName (defense in depth). "
            + "Optional widgetPackageVersion is sanitized for PARLER_CONNECTION_INFO logs only.")
    @ThingworxServiceResult(name = "result", baseType = "STRING",
        description = "JSON: parler.connection-info.v1 (see CONTRACTS/API_CONTRACT.md)")
    public String GetConnectionInfo(
        @ThingworxServiceParameter(name = "agentThingName", baseType = "STRING",
            description = "Must equal AgentThreadDataTable agentName for this Gateway (conversationId)",
            aspects = {"isRequired:true"}) String agentThingName,
        @ThingworxServiceParameter(name = "widgetPackageVersion", baseType = "STRING",
            description = "Optional client widget package version for logs only (not echoed in JSON)",
            aspects = {"isRequired:false"}) String widgetPackageVersion)
            throws Exception {
        String prefix = "[" + getName() + "] GetConnectionInfo";
        ConversationMetadata meta =
                AgentThreadDataTableSupport.loadConversationMetadataForCurrentUser(getName(), prefix);
        String bound = meta.getAgentName() != null ? meta.getAgentName().trim() : "";
        if (bound.isEmpty()) {
            throw new Exception(prefix + ": thread row has empty agentName.");
        }
        if (agentThingName == null || agentThingName.trim().isEmpty()) {
            throw new Exception(prefix + ": agentThingName is required.");
        }
        if (!bound.equals(agentThingName.trim())) {
            throw new Exception(prefix + ": agentThingName does not match bound agent for this conversation.");
        }
        RootEntity ent = PlatformAccess.findAsUser(bound, RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (ent == null || !(ent instanceof AgentThing)) {
            throw new Exception(prefix + ": no AIAgent Thing named \"" + bound + "\".");
        }
        AgentThing agent = (AgentThing) ent;
        ThingTemplateSupport.ensureThingDerivedFromTemplate(agent, AI_AGENT_TEMPLATE_NAME, prefix);

        String display = ParlerRuntimeVersion.displayVersion();
        if (display.isEmpty()) {
            display = ParlerPackageVersion.fromAnchorClass(AgentThing.class);
            display = display != null ? display.trim() : "";
        }
        String impl = ParlerRuntimeVersion.implementationVersion();
        String sanitizedWidget = ParlerConnectionInfoSanitizer.sanitizeWidgetEcho(widgetPackageVersion);
        LOG.info("PARLER_CONNECTION_INFO conversation_id={} agent={} agent_version={} widget_version={}",
                getName(), bound, display, sanitizedWidget);

        JSONObject root = new JSONObject();
        root.put("schemaVersion", "parler.connection-info.v1");
        root.put("conversationId", getName());
        JSONObject agentJson = new JSONObject();
        agentJson.put("thingName", bound);
        agentJson.put("extensionVersion", display);
        if (impl != null && !impl.isBlank() && !impl.equals(display)) {
            agentJson.put("implementationVersion", impl);
        }
        root.put("agent", agentJson);
        JSONObject capabilities = new JSONObject();
        // CancelUserPrompt: parked HITL + cooperative running-turn stop (AgentThing#cancelUserPromptFromParlerGateway,
        // ParlerRunningTurnCancelRegistry). supportsCancellation is true for coordinated parler-agent 0.1.187 +
        // parler-ui-widget 0.1.80 after User ruling B (E2E gate / capability flip — CONTRACTS/API_CONTRACT.md 2.4.38,
        // docs/agent/turn-cancellation-control.md).
        capabilities.put("supportsCancellation", true);
        root.put("capabilities", capabilities);
        root.put("serverTime", ISODateTimeFormat.dateTime().withZone(DateTimeZone.UTC).print(DateTime.now()));
        return root.toString();
    }

    @ThingworxServiceDefinition(
        name = "SubmitApprovalDecision",
        description = "Parler HITL: same AlwaysOn invoke locus as SubmitUserPrompt on this Gateway. "
            + "Parameters correspond to approval.decision (request_id, conversation_id, pending_id, decision, comment). "
            + "Delegates to the AIAgent Thing that owns the pending record.")
    @ThingworxServiceResult(name = "result", baseType = "NOTHING")
    public void SubmitApprovalDecision(
        @ThingworxServiceParameter(name = "pendingId", baseType = "STRING",
            description = "pending_id from approval.required") String pendingId,
        @ThingworxServiceParameter(name = "decision", baseType = "STRING",
            description = "approve, cancel, or reject_with_comment") String decision,
        @ThingworxServiceParameter(name = "requestId", baseType = "STRING",
            description = "Must equal approval.required.request_id") String requestId,
        @ThingworxServiceParameter(name = "conversationId", baseType = "STRING",
            description = "Must equal this Gateway name (wire conversation_id)") String conversationId,
        @ThingworxServiceParameter(name = "comment", baseType = "STRING",
            description = "Optional; used with reject_with_comment in later phases",
            aspects = {"isRequired:false"}) String comment
    ) throws Exception {
        if (conversationId == null || !conversationId.trim().equals(getName())) {
            ParlerHitlAuditLog.decisionRejected("GATEWAY_SUBMIT_CONV_MISMATCH", "gateway_submit", getName(),
                    pendingId != null ? pendingId : "",
                    requestId != null ? requestId : "",
                    conversationId != null ? conversationId : "",
                    decision != null ? decision : "",
                    parlerSubmitPrincipal(), "-");
            throw new Exception("[" + getName() + "] SubmitApprovalDecision: conversationId must match this Gateway name.");
        }
        AgentThing.completeParlerApprovalFromParlerGateway(this, pendingId, decision, requestId, conversationId, comment);
    }

    @ThingworxServiceDefinition(
        name = "SetConversationHistoryCutoff",
        description = "AlwaysOn: advance durable historyClearedAt for this Gateway name (conversationId). "
            + "Caller must own the thread row. cutoffAtIso is UTC-preferring ISO-8601 (same instant family as history completedAt). "
            + "Delegates to the bound Agent Thing.")
    @ThingworxServiceResult(name = "result", baseType = "NOTHING")
    public void SetConversationHistoryCutoff(
        @ThingworxServiceParameter(name = "cutoffAtIso", baseType = "STRING",
            description = "Instant for historyClearedAt (ISO-8601)",
            aspects = {"isRequired:true"}) String cutoffAtIso
    ) throws Exception {
        if (cutoffAtIso == null || cutoffAtIso.trim().isEmpty()) {
            throw new Exception("[" + getName() + "] SetConversationHistoryCutoff: cutoffAtIso is required.");
        }
        String prefix = "[" + getName() + "] SetConversationHistoryCutoff";
        DateTime cutoffAt;
        try {
            cutoffAt = ISODateTimeFormat.dateTimeParser().withOffsetParsed().parseDateTime(cutoffAtIso.trim());
        } catch (Exception e) {
            throw new Exception(prefix + ": cutoffAtIso must be ISO-8601.", e);
        }
        AgentThing agent = requireBoundAgentForGateway(prefix);
        agent.SetConversationHistoryCutoff(getName(), cutoffAt);
    }

    @ThingworxServiceDefinition(
        name = "RecordAssistantFeedback",
        description = "AlwaysOn: append ui_feedback for thumbs up/down. conversationId must match this Gateway name. "
            + "Caller must own the thread row.")
    @ThingworxServiceResult(name = "result", baseType = "NOTHING")
    public void RecordAssistantFeedback(
        @ThingworxServiceParameter(name = "conversationId", baseType = "STRING",
            description = "Must equal this Gateway Thing name (wire conversation_id)",
            aspects = {"isRequired:true"}) String conversationId,
        @ThingworxServiceParameter(name = "assistantMessageId", baseType = "STRING",
            aspects = {"isRequired:true"}) String assistantMessageId,
        @ThingworxServiceParameter(name = "rating", baseType = "STRING",
            description = "up or down",
            aspects = {"isRequired:true"}) String rating,
        @ThingworxServiceParameter(name = "requestId", baseType = "STRING",
            aspects = {"isRequired:false"}) String requestId,
        @ThingworxServiceParameter(name = "previousRating", baseType = "STRING",
            aspects = {"isRequired:false"}) String previousRating
    ) throws Exception {
        if (conversationId == null || !conversationId.trim().equals(getName())) {
            throw new Exception("[" + getName() + "] RecordAssistantFeedback: conversationId must match this Gateway name.");
        }
        String prefix = "[" + getName() + "] RecordAssistantFeedback";
        AgentThing agent = requireBoundAgentForGateway(prefix);
        agent.RecordAssistantFeedback(conversationId.trim(), assistantMessageId, rating, requestId, previousRating);
    }

    private AgentThing requireBoundAgentForGateway(String prefix) throws Exception {
        ConversationMetadata meta =
                AgentThreadDataTableSupport.loadConversationMetadataForCurrentUser(getName(), prefix);
        String agentName = meta.getAgentName() != null ? meta.getAgentName().trim() : "";
        if (agentName.isEmpty()) {
            throw new Exception(prefix + ": thread row has empty agentName.");
        }
        RootEntity ent = PlatformAccess.findAsUser(agentName, RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (ent == null || !(ent instanceof AgentThing)) {
            throw new Exception(prefix + ": no AIAgent Thing named \"" + agentName + "\".");
        }
        AgentThing agent = (AgentThing) ent;
        ThingTemplateSupport.ensureThingDerivedFromTemplate(agent, AI_AGENT_TEMPLATE_NAME, prefix);
        return agent;
    }

    private static String parlerSubmitPrincipal() {
        try {
            SecurityContext ctx = ThreadLocalContext.getSecurityContext();
            return ctx != null && ctx.getName() != null ? ctx.getName() : "Anonymous";
        } catch (Exception e) {
            return "Anonymous";
        }
    }
}
