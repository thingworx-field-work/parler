package com.thingworx.things.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.concurrent.ConcurrentHashMap;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;
import org.json.JSONArray;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.EventDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.annotations.ThingworxEventDefinition;
import com.thingworx.metadata.annotations.ThingworxEventDefinitions;
import com.thingworx.metadata.annotations.ThingworxServiceDefinition;
import com.thingworx.metadata.annotations.ThingworxServiceParameter;
import com.thingworx.metadata.annotations.ThingworxServiceResult;
import com.thingworx.system.ContextType;
import com.thingworx.persistence.TransactionFactory;
import com.thingworx.security.context.SecurityContext;
import com.thingworx.things.repository.FileRepositoryThing;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.hierarchy.HierarchyNetworkServiceNames;
import com.thingworx.things.agent.hostcontext.HostContextPreviousSnapshot;
import com.thingworx.things.agent.hostcontext.HostContextPreviousSnapshotLookup;
import com.thingworx.things.agent.hostcontext.HostContextTurnCarryStore;
import com.thingworx.things.agent.hostcontext.HostContextTurnPrep;
import com.thingworx.things.agent.hostcontext.HostContextUplink;
import com.thingworx.things.agent.cache.ArtifactCacheTurnAdmission;
import com.thingworx.things.agent.cache.ArtifactCacheTurnAdmission.Decision;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.things.agent.hostcontext.HostContextValidate;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmProviderResolveException;
import com.thingworx.things.agent.llm.ratecontrol.RateControlStatusSink;
import com.thingworx.things.agent.llm.LlmRoutingGuide;
import com.thingworx.things.agent.llm.ParlerSuffixFraming;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.ToolSchemaSizer;
import com.thingworx.things.agent.hostcontext.HostContextTemplate;
import com.thingworx.things.agent.hostcontext.HostContextTemplateRegistry;
import com.thingworx.things.agent.tools.DocumentTurnToolNarrowing;
import com.thingworx.things.agent.tools.LazyToolRegistrationRegistry;
import com.thingworx.things.agent.tools.LoadToolSchemasExecutor;
import com.thingworx.things.agent.tools.ToolAdmissionMode;
import com.thingworx.things.agent.tools.ToolAdmissionPolicy;
import com.thingworx.things.agent.tools.ToolAdmissionSignals;
import com.thingworx.things.agent.tools.ToolBucket;
import com.thingworx.things.agent.tools.ToolBuckets;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.DocumentKnowledgeRuntime;
import com.thingworx.things.agent.tools.ConsecutiveIdenticalToolCallRegistry;
import com.thingworx.things.agent.tools.DocumentSearchProgressGuard;
import com.thingworx.things.agent.tools.DocumentSearchProgressGuardRegistry;
import com.thingworx.things.agent.tools.ConsecutiveIdenticalToolCallTracker;
import com.thingworx.things.agent.tools.FetchCachedReplayGuard;
import com.thingworx.things.agent.tools.FetchCachedStreamLaneHelper;
import com.thingworx.things.agent.tools.GenericThingIncomingDependencyResolver;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ModelKeyResolutionNormalize;
import com.thingworx.things.agent.tools.AlertPromptDefaults;
import com.thingworx.things.agent.tools.ApprovalPendingException;
import com.thingworx.things.agent.tools.BuiltInToolTimeErrorJson;
import com.thingworx.things.agent.tools.CustomToolHarvester;
import com.thingworx.things.agent.tools.CustomToolNaturalTimeException;
import com.thingworx.things.agent.tools.ExtendedToolThingnamePreflight;
import com.thingworx.things.agent.tools.InvokeServiceEntityTypeNormalization;
import com.thingworx.things.agent.tools.InvokeServiceParameterNormalizer;
import com.thingworx.things.agent.tools.InvokeServiceParameterRepairBinding;
import com.thingworx.things.agent.tools.InvokeServiceErrorJson;
import com.thingworx.things.agent.tools.ModelFacingSkillAdmission;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.things.agent.tools.UnsupportedRelativeLiteralException;
import com.thingworx.things.agent.tools.LlmApiProviderDirectory;
import com.thingworx.things.agent.tools.InfotableJsonCodec;
import com.thingworx.things.agent.tools.ServiceTargetEntityTypeResolution;
import com.thingworx.things.agent.tools.ServiceTargetEntityTypeResolver;
import com.thingworx.things.agent.tools.ParlerHitlAuditLog;
import com.thingworx.things.agent.tools.ParlerHitlStreamScopedEnqueue;
import com.thingworx.things.agent.tools.PresentationArtifactRegistry;
import com.thingworx.things.agent.tools.ProtectedValuePolicy;
import com.thingworx.things.agent.tools.ReservedBuiltinToolNames;
import com.thingworx.things.agent.tools.PendingApprovalRecord;
import com.thingworx.things.agent.tools.PendingApprovalStore;
import com.thingworx.things.agent.tools.SetPropertyValueExecutor;
import com.thingworx.things.agent.tools.ServiceResultInfotable;
import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.agent.configrepo.ConfigurationRepositoryAuthoringJson;
import com.thingworx.things.agent.configrepo.ConfigurationRepositoryLoadedFileCaptures;
import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;
import com.thingworx.things.agent.configrepo.ExtendedToolDefinition;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.configrepo.ExtendedToolsManifest;
import com.thingworx.things.agent.configrepo.ExternalSystemPromptAssembly;
import com.thingworx.things.agent.configrepo.ExternalSystemPromptSelection;
import com.thingworx.things.agent.configrepo.ServiceCapabilityDryRunEnforce;
import com.thingworx.things.agent.configrepo.ServiceCapabilityMetadata;
import com.thingworx.things.agent.configrepo.ServiceCapabilityRuntimePolicy;
import com.thingworx.things.agent.recovery.ErrorCategory;
import com.thingworx.things.agent.recovery.TypedToolError;
import com.thingworx.things.agent.recovery.TypedToolErrorJson;
import com.thingworx.things.agent.configrepo.InvokeServiceAllowPolicy;
import com.thingworx.things.agent.configrepo.InvokeServicePolicyBatchCache;
import com.thingworx.things.agent.configrepo.ParlerPackageVersion;
import com.thingworx.things.agent.configrepo.RepositoryFileFingerprint;
import com.thingworx.things.agent.configrepo.RepositoryTextLoads;
import com.thingworx.things.agent.configrepo.SystemPromptFileLoader;
import com.thingworx.things.agent.configrepo.TypeTaxonomyMarkdownLoader;
import com.thingworx.things.agent.skillregistry.FileRepositoryRepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.agent.skillregistry.SkillRegistryBuilder;
import com.thingworx.things.agent.skillregistry.AgentWorkflowCatalogFormatter;
import com.thingworx.things.agent.skillregistry.SkillRegistryCatalogFormatter;
import com.thingworx.things.agent.skillregistry.SkillRegistryDescriptor;
import com.thingworx.things.agent.skillregistry.SkillRegistryLoader;
import com.thingworx.things.agent.skillregistry.SkillRegistrySnapshot;
import com.thingworx.things.agent.skillregistry.SkillSourceKind;
import com.thingworx.things.agent.semantics.SemanticProfileBuilder;
import com.thingworx.things.agent.semantics.SemanticProfileDiagnosticsJson;
import com.thingworx.things.agent.semantics.SemanticProfileSnapshot;
import com.thingworx.things.agent.taxonomy.ApplicationSemanticTaxonomyBuilder;
import com.thingworx.things.agent.taxonomy.ApplicationSemanticTaxonomySnapshot;
import com.thingworx.things.agent.taxonomy.AssetTypeEntry;
import com.thingworx.things.agent.taxonomy.TaxonomyResolverExecutor;
import com.thingworx.things.agent.taxonomy.TaxonomyResolverJson;
import com.thingworx.things.agent.taxonomy.TaxonomyRowsFromIdentitySnapshot;
import com.thingworx.things.agent.playbook.PlaybookActiveRunTracker;
import com.thingworx.things.agent.playbook.PlaybookArtifactEmitter;
import com.thingworx.things.agent.playbook.PlaybookInfotableBindingPolicy;
import com.thingworx.things.agent.playbook.PlaybookInternalArtifactEmissionOrder;
import com.thingworx.things.agent.playbook.PlaybookCatalogEntry;
import com.thingworx.things.agent.playbook.PlaybookCatalogFormatter;
import com.thingworx.things.agent.playbook.PlaybookDocument;
import com.thingworx.things.agent.playbook.PlaybookDocumentValidation;
import com.thingworx.things.agent.playbook.PlaybookRuntimeSnapshotBuilder;
import com.thingworx.things.agent.playbook.PlaybookIds;
import com.thingworx.things.agent.playbook.PlaybookParlerStreamBindings;
import com.thingworx.things.agent.playbook.PlaybookRegistryBuilder;
import com.thingworx.things.agent.playbook.PlaybookRegistrySnapshot;
import com.thingworx.things.agent.playbook.PlaybookRunResult;
import com.thingworx.things.agent.playbook.PlaybookRunner;
import com.thingworx.things.agent.playbook.PlaybookRunException;
import com.thingworx.things.agent.playbook.PlaybookStartToolDefinitionBuilder;
import com.thingworx.things.agent.playbook.PlaybookSlashParser;
import com.thingworx.things.agent.playbook.PlaybookSlashTurnFinalize;
import com.thingworx.things.agent.playbook.PlaybookToolDefinitionsMerge;
import com.thingworx.things.agent.playbook.PlaybookToolExecutionResult;
import com.thingworx.things.agent.playbook.PlaybookToolExecutor;
import com.thingworx.things.agent.tools.SkillSlashParser;
import com.thingworx.things.agent.taskstate.AgentTaskState;
import com.thingworx.things.agent.taskstate.AgentTaskStateHooks;
import com.thingworx.things.agent.taskstate.SkillChecklistContinuationMerge;
import com.thingworx.things.agent.taskstate.TaskProgressV1bDynamicMerge;
import com.thingworx.things.agent.taskstate.SkillChecklistParseException;
import com.thingworx.things.agent.taskstate.SkillChecklistParser;
import com.thingworx.things.agent.taskstate.TaskProgressV1b;
import com.thingworx.things.agent.taskstate.TaskProgressV1bHitlReplay;
import com.thingworx.things.agent.taskstate.TaskProgressV1bHooks;
import com.thingworx.things.agent.taskstate.TaskProgressWireEmitter;
import com.thingworx.things.agent.taskstate.TaskStateErrorCode;
import com.thingworx.things.agent.tools.TabularChartRoundHooks;
import com.thingworx.things.agent.tools.ToolExecutor;
import com.thingworx.things.agent.compaction.ConversationCheckpointWorkingSet;
import com.thingworx.things.agent.compaction.ConversationsReplayNormalization;
import com.thingworx.things.agent.compaction.ConversationsStorageBudgetTrimmer;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.BaseTypes;
import com.thingworx.data.util.InfoTableInstanceFactory;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.JSONPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.webservices.context.ThreadLocalContext;

/**
 * Self-contained Agent Thing.
 * Manages its own LLM connection and tool registry.
 * ThingWorx JS developers interact via Chat/ChatAsync services and AgentResponseEvent.
 *
 * Usage from ThingWorx JavaScript:
 *   // Synchronous — blocks until agent completes (optional hostContext: HostScopeJson UTF-8 text)
 *   var result = Things["MyAgent"].Chat({
 *       message: "What is the current value of Temperature on Pump-01?",
 *       conversationId: "session-123",
 *       hostContext: undefined
 *   });
 *
 *   // Asynchronous — fires AgentResponseEvent when done
 *   Things["MyAgent"].ChatAsync({
 *       message: "Set Pump-01 speed to 75 and confirm the change",
 *       conversationId: "session-456",
 *       hostContext: undefined
 *   });
 *
 *   // Parler AlwaysOn — transient {@code ParlerGateway} (Thing name = conversationId): {@code SubmitUserPrompt} calls
 *   // {@link AgentThing#ParlerStreamToRemoteThing} (see docs/agent/AGENT-ALWAYSON-TWX.md).
 */
@ThingworxEventDefinitions(events = {
    @ThingworxEventDefinition(
        name = "AgentResponseEvent",
        description = "Fired when the agent completes processing (async mode or tool call notifications)",
        dataShape = "AgentResponseEventData"),
    @ThingworxEventDefinition(
        name = "AgentToolCallEvent",
        description = "Declared for tool-call observability; the extension does not fire it",
        dataShape = "AgentToolCallEventData")
})
public class AgentThing extends AgentBaseThing {

    private static final long serialVersionUID = 1L;
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        ParlerApprovalExpiryScheduler.ensureStarted();
    }

    private final ConcurrentHashMap<String, List<ChatMessage>> _conversations = new ConcurrentHashMap<>();
    private final HostContextTurnCarryStore _hostContextTurnCarry = new HostContextTurnCarryStore();
    /**
     * Per-conversation mutex for Parler + Chat mutation (see {@link ParlerConversationLocks}; historically lived on
     * {@link AgentThing} so {@link ParlerGateway} can synchronize with {@link #ParlerStreamToRemoteThing} on the same
     * conversation id).
     */
    private volatile PromptContextCacheSnapshot _promptContextSnapshot;
    private volatile PlaybookRegistrySnapshot _playbookRegistrySnapshot = PlaybookRegistrySnapshot.empty(Instant.now());
    private volatile String _lastPlaybookRunOutcomeJson;
    private static final ThreadLocal<Boolean> PLAYBOOK_TOOL_CALLED_THIS_TURN = ThreadLocal.withInitial(() -> false);
    private final Object _promptCacheLock = new Object();
    private long _emptyCacheWarnNextAllowedMillis;
    private long _resolverFallbackGenericThingWarnNextAllowedMillis;
    private static final long PROMPT_CACHE_WARN_THROTTLE_MS = 15 * 60 * 1000L;

    @Override
    protected void initializeThing(ContextType contextType) {
        super.initializeThing(contextType);
    }

    private Decision artifactCacheAdmission(String entryPath, String requestId, String conversationId) {
        Decision decision = ArtifactCacheTurnAdmission.evaluate(this, _logger);
        if (!decision.isReady()) {
            ArtifactCacheTurnAdmission.logRejected(_logger, getName(), entryPath, requestId, conversationId, decision);
        }
        return decision;
    }

    private static AgentLoop.AgentResult artifactCacheAdmissionResult(Decision decision) {
        return AgentLoop.AgentResult.error(decision.errorCode(), decision.message(), 0, 0, 0, null);
    }

    private static boolean isArtifactCacheTerminal(AgentLoop.AgentResult result) {
        if (result == null || result.getStatus() != AgentLoop.AgentResult.Status.ERROR) {
            return false;
        }
        String code = result.getErrorCode();
        return ArtifactCacheTurnAdmission.NOT_CONFIGURED_CODE.equals(code)
                || ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE.equals(code);
    }

    private static Exception artifactCacheServiceException(AgentLoop.AgentResult result) {
        String code = result != null ? result.getErrorCode() : null;
        String message = result != null ? result.getContent() : null;
        return new Exception("[" + (code != null ? code : ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE)
                + "] " + (message != null ? message : ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_MESSAGE));
    }

    private static boolean sendArtifactCacheTerminal(Thing remoteConversation, String requestId,
            String conversationId, AgentLoop.AgentResult result) {
        return remoteConversation != null && isArtifactCacheTerminal(result)
                && ParlerReceiveMessageSupport.send(remoteConversation,
                        ParlerReceiveMessageSupport.wireError(requestId, conversationId,
                                result.getContent(), result.getErrorCode()));
    }

    private RateControlStatusSink rateControlSinkForRemote(Thing remoteConversation, String conversationIdWire,
            String requestIdWire) {
        if (remoteConversation == null || conversationIdWire == null || requestIdWire == null) {
            return null;
        }
        final Thing remote = remoteConversation;
        final String cid = conversationIdWire;
        final String rid = requestIdWire;
        return (waiting, reason, waitMs, retryAfterMs) -> {
            try {
                String json = ParlerReceiveMessageSupport.wireRateControlStatus(rid, cid, waiting, reason, waitMs,
                        retryAfterMs);
                if (!ParlerReceiveMessageSupport.send(remote, json)) {
                    _logger.info(
                            "LLM_RATE_UI_STATUS_DROPPED conversationId={} requestId={} status={} reason={}",
                            cid, rid, waiting ? "waiting" : "resumed",
                            reason != null ? reason.wireValue() : "");
                }
            } catch (Throwable t) {
                _logger.info(
                        "LLM_RATE_UI_STATUS_DROPPED conversationId={} requestId={} status={} reason={}",
                        cid, rid, waiting ? "waiting" : "resumed",
                        reason != null ? reason.wireValue() : "");
            }
        };
    }

    private LlmClient llmClientForTurn() throws Exception {
        try {
            return resolveLlmClientForTurn();
        } catch (LlmProviderResolveException e) {
            throw new Exception("[" + getName() + "] " + e.getCode().name() + ": " + e.getMessage());
        }
    }

    // ── Synchronous Chat ────────────────────────────────────────────────
    @ThingworxServiceDefinition(
        name = "Chat",
        description = "Send a message to the AI agent and wait for the response. "
            + "The agent may invoke tools (ThingWorx API calls) in a loop before returning.")
    @ThingworxServiceResult(name = "result", baseType = "STRING",
        description = "The agent's final text response")
    public String Chat(
        @ThingworxServiceParameter(name = "message", baseType = "STRING",
            description = "User message to send to the agent") String message,
        @ThingworxServiceParameter(name = "systemPrompt", baseType = "TEXT",
            description = "Override the default system prompt for this call",
            aspects = {"isRequired:false"}) String systemPrompt,
        @ThingworxServiceParameter(name = "conversationId", baseType = "STRING",
            description = "Conversation ID for multi-turn context. Must exist in AgentThreadDataTable (use GetOrCreateConversationId). Omit or pass empty for single-turn.",
            aspects = {"isRequired:false"}) String conversationId,
        @ThingworxServiceParameter(name = "hostContext", baseType = "STRING",
            description = "Optional UTF-8 JSON host-scope sideband (HostScopeJson); same semantics as ParlerStreamToRemoteThing.hostContext",
            aspects = {"isRequired:false"}) String hostContext
    ) throws Exception {
        if (!hasLlmRuntimeConfigured()) {
            throw new Exception("[" + getName() + "] LLM client not initialized. Set AgentSettings.llmApiProviderRef to an enabled LLM API Provider Thing.");
        }
        if (conversationId != null && !conversationId.isEmpty()) {
            ensureConversationIdInDataTable(conversationId);
        }
        if (message == null || message.trim().isEmpty()) {
            throw new Exception("[" + getName() + "] Chat: message is empty.");
        }
        Decision cacheAdmission = artifactCacheAdmission("Chat", null, conversationId);
        if (!cacheAdmission.isReady()) {
            throw new Exception(cacheAdmission.serviceExceptionMessage());
        }

        List<ToolDefinition> toolsPreview = getMergedToolDefinitions();
        _logger.info("[{}] Chat(sync) start conversationId={} userMessageChars={} toolCount={} hostContextUtf8Bytes={}",
                getName(), conversationId != null && !conversationId.isEmpty() ? conversationId : "(single-turn)",
                message != null ? message.length() : 0, toolsPreview.size(),
                hostContextUtf8Bytes(hostContext));

        if (conversationId != null && !conversationId.isEmpty()) {
            synchronized (lockForConversation(conversationId)) {
                HostContextTurnPrep prep = prepareHostContextTurn(hostContext, conversationId, "Chat");
                return executeChatTurn(message, systemPrompt, conversationId, toolsPreview, prep);
            }
        }
        HostContextTurnPrep prep = HostContextTurnPrep.renderedOnly(hostContext, this);
        logHostContextUplinkDecision(prep.decision, "Chat");
        return executeChatTurn(message, systemPrompt, conversationId, toolsPreview, prep);
    }

    /**
     * One synchronous chat turn: resolve history, run agent loop, persist messages for threaded chats.
     */
    private String executeChatTurn(String message, String systemPrompt, String conversationId,
            List<ToolDefinition> toolsPreview, HostContextTurnPrep hostContextTurnOrNull) throws Exception {
        final String hostContextRenderedPromptOrNull = hostContextTurnOrNull != null
                ? hostContextTurnOrNull.llmEphemeralPromptOrNull
                : null;
        final String hostContextSnapshotJsonOrNull = hostContextTurnOrNull != null
                ? hostContextTurnOrNull.snapshotJson
                : null;
        PLAYBOOK_TOOL_CALLED_THIS_TURN.set(false);
        bindPlaybookSlashTurnContext(conversationId, null, null, null, null, hostContextRenderedPromptOrNull, null);
        try {
            PlaybookSlashEarly playbookEarly = tryExecutePlaybookSlashTurn(message, conversationId, hostContextRenderedPromptOrNull);
            if (playbookEarly != null) {
                return finishPlaybookSlashTurn(message, playbookEarly.assistantText, conversationId, systemPrompt, null,
                        hostContextRenderedPromptOrNull, ConversationRehydrateSettings.disabled(), playbookEarly.playbookLlmUsage,
                        hostContextSnapshotJsonOrNull, null).assistantText;
            }
        } catch (Exception e) {
            if (ArtifactCacheTurnFaults.isRepositoryUnavailable(e)) {
                _logger.error("[{}] Artifact Cache repository unavailable during Chat playbook", getName(), e);
                throw artifactCacheServiceException(AgentLoop.AgentResult.error(
                        ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE,
                        ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_MESSAGE, 0, 0, 0, null));
            }
            _logger.warn("[{}] Playbook slash turn failed: {}", getName(), e.getMessage(), e);
            return "Playbook execution failed: " + e.getMessage();
        } finally {
            AgentToolContext.clear();
        }
        LlmTurnContext turn = buildLlmTurnContext(conversationId, systemPrompt, message, null, hostContextRenderedPromptOrNull,
                ConversationRehydrateSettings.disabled());
        List<ChatMessage> messages = turn.messages;
        final String streamKey = streamConversationKey(conversationId);
        final String streamSource = streamKey;
        final String agentName = getName();
        messages.add(turn.userMessageForModel);
        AgentMessageStreamAppender.append(streamKey, ChatMessage.user(message), streamSource, agentName,
                StreamTokenUsage.ZERO, null, hostContextSnapshotJsonOrNull);
        if (hostContextSnapshotJsonOrNull != null && conversationId != null && !conversationId.isEmpty()) {
            _hostContextTurnCarry.recordAfterUserRow(conversationId, hostContextSnapshotJsonOrNull);
        }
        final String exportWireRid = conversationId != null && !conversationId.isEmpty() ? conversationId : "chat-sync";
        BiConsumer<ChatMessage, StreamTokenUsage> streamSink = (m, tu) -> {
            ChatMessage mStream = m.getRole() == ChatMessage.Role.TOOL
                    ? ParlerToolStreamTableExportSidecar.augmentToolMessageForStreamAppend(m, null, exportWireRid, exportWireRid)
                    : m;
            AgentMessageStreamAppender.append(streamKey, mStream, streamSource, agentName, tu);
        };

        ToolExecutor combinedExecutor = this::executeToolCall;
        LlmClient turnLlm = llmClientForTurn();
        AgentLoop loop = newAgentLoopForTurn(turnLlm, combinedExecutor, null, null);

        List<ToolDefinition> tools = toolsPreview;
        AgentLoop.AgentResult result;
        boolean loopCompletedOk = false;
        try {
            String chatNonce = UUID.randomUUID().toString();
            AgentToolContext.setFetchCachedChatTurnNonce(chatNonce);
            FetchCachedReplayGuard.beginTurn(FetchCachedReplayGuard.turnKeyForChat(
                    conversationId != null && !conversationId.isEmpty() ? conversationId
                            : AgentToolContext.SINGLE_TURN_CONVERSATION_ID,
                    chatNonce));
            AgentToolContext.setConversationId(conversationId);
            AgentToolContext.setAgentThing(this);
            AgentToolContext.setUserIanaTimezone(null);
            AgentToolContext.setHostContextJson(null);
            maybeInjectHostContextDocumentScope(hostContextTurnOrNull);
            AgentToolContext.setParlerActiveMessages(messages);
            AgentToolContext.setParlerEphemeralSystemIndices(turn.ephemeralIndices());
            primeAgentTaskStateForTurn(turn, "", conversationId);
            AgentToolContext.resetTabularChartRound();
            DocumentTurnToolNarrowing.resetTurnState();
            result = loop.run(messages, tools, streamSink);
            loopCompletedOk = true;
        } finally {
            ChartGroupTurnHooks.captureBeforeContextClear();
            AgentToolContext.clear();
            PLAYBOOK_TOOL_CALLED_THIS_TURN.set(false);
            applySkillTurnMutationFinish(messages, turn, loopCompletedOk);
        }

        _logger.info("[{}] Chat(sync) done status={} iterations={} promptTokens={} completionTokens={} responseChars={}",
                getName(), result.getStatus(), result.getIterations(),
                result.getPromptTokens(), result.getCompletionTokens(),
                result.getContent() != null ? result.getContent().length() : 0);

        if (isArtifactCacheTerminal(result)) {
            ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(messages, result,
                    isLlmReplayCompactionEffective(), _logger, conversationId, null, _llmContextMaxChars,
                    turnLlm, null, agentName,
                    org.joda.time.DateTime.now(org.joda.time.DateTimeZone.UTC).toString());
            if (conversationId != null && !conversationId.isEmpty()) {
                _conversations.put(conversationId, messages);
            }
            throw artifactCacheServiceException(result);
        }

        ChartGroupTurnHooks.onTurnEnd(result);
ChatMessage finalAssistant = ChatMessage.assistant(result.getContent());
        messages.add(finalAssistant);
        StreamTokenUsage finalTok = mergeAgentLoopUsageWithTurnPerf(result);
        String assistantMessageId = UUID.randomUUID().toString();
        AgentMessageStreamAppender.append(streamKey, finalAssistant, streamSource, agentName, finalTok,
                assistantMessageId);

        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(messages, result,
                isLlmReplayCompactionEffective(), _logger, conversationId, null, _llmContextMaxChars,
                turnLlm, assistantMessageId, agentName,
                org.joda.time.DateTime.now(org.joda.time.DateTimeZone.UTC).toString());
        if (conversationId != null && !conversationId.isEmpty()) {
            _conversations.put(conversationId, messages);
        }

        return result.getContent();
    }

    // ── Asynchronous Chat ───────────────────────────────────────────────
    @ThingworxServiceDefinition(
        name = "ChatAsync",
        description = "Send a message to the AI agent asynchronously. "
            + "The response is delivered via AgentResponseEvent.")
    @ThingworxServiceResult(name = "result", baseType = "STRING",
        description = "Acknowledgement with conversation ID")
    public String ChatAsync(
        @ThingworxServiceParameter(name = "message", baseType = "STRING",
            description = "User message") String message,
        @ThingworxServiceParameter(name = "systemPrompt", baseType = "TEXT",
            description = "Override system prompt",
            aspects = {"isRequired:false"}) String systemPrompt,
        @ThingworxServiceParameter(name = "conversationId", baseType = "STRING",
            description = "Conversation ID (required). Must exist in AgentThreadDataTable; use GetOrCreateConversationId.",
            aspects = {"isRequired:true"}) String conversationId,
        @ThingworxServiceParameter(name = "hostContext", baseType = "STRING",
            description = "Optional UTF-8 JSON host-scope sideband (HostScopeJson); same semantics as ParlerStreamToRemoteThing.hostContext",
            aspects = {"isRequired:false"}) String hostContext
    ) throws Exception {
        if (!hasLlmRuntimeConfigured()) {
            throw new Exception("[" + getName() + "] LLM client not initialized. Set AgentSettings.llmApiProviderRef to an enabled LLM API Provider Thing.");
        }
        if (conversationId == null || conversationId.isEmpty()) {
            throw new Exception("[" + getName() + "] For async chat a conversation ID is required. Use GetOrCreateConversationId to create or select a thread.");
        }
        ensureConversationIdInDataTable(conversationId);
        if (message == null || message.trim().isEmpty()) {
            throw new Exception("[" + getName() + "] ChatAsync: message is empty.");
        }

        final String effectiveConvId = conversationId;
        final String thingName = getName();
        _logger.info("[{}] ChatAsync queued conversationId={} messageChars={}", thingName, effectiveConvId,
                message != null ? message.length() : 0);

        final SecurityContext callerSecurityContext = ThreadLocalContext.getSecurityContext();
        final Object convLock = lockForConversation(effectiveConvId);

        new Thread(() -> {
            try {
                if (callerSecurityContext != null) {
                    ThreadLocalContext.setSecurityContext(callerSecurityContext);
                }
                synchronized (convLock) {
                    Decision cacheAdmission = artifactCacheAdmission(
                            "ChatAsync", null, effectiveConvId);
                    if (!cacheAdmission.isReady()) {
                        fireAgentResponseEvent(effectiveConvId, artifactCacheAdmissionResult(cacheAdmission));
                        return;
                    }
                    final HostContextTurnPrep hostContextTurn = prepareHostContextTurn(hostContext, effectiveConvId,
                            "ChatAsync");
                    final String hostContextRenderedPrompt = hostContextTurn.llmEphemeralPromptOrNull;
                    PLAYBOOK_TOOL_CALLED_THIS_TURN.set(false);
                    bindPlaybookSlashTurnContext(effectiveConvId, null, null, null, null, hostContextRenderedPrompt, null);
                    try {
                        PlaybookSlashEarly playbookEarly = tryExecutePlaybookSlashTurn(message, effectiveConvId, hostContextRenderedPrompt);
                        if (playbookEarly != null) {
                            PlaybookSlashTurnFinish fin = finishPlaybookSlashTurn(message, playbookEarly.assistantText,
                                    effectiveConvId,
                                    systemPrompt, null, hostContextRenderedPrompt,
                                    ConversationRehydrateSettings.disabled(), playbookEarly.playbookLlmUsage,
                                    hostContextTurn.snapshotJson, null);
                            StreamTokenUsage slashU = fin.playbookLlmUsage;
                            fireAgentResponseEvent(effectiveConvId,
                                    AgentLoop.AgentResult.success(fin.assistantText, 0, slashU.getPromptTokens(),
                                            slashU.getCompletionTokens(), slashU));
                            _logger.info("[{}] ChatAsync playbook slash done conversationId={}", thingName,
                                    effectiveConvId);
                            return;
                        }
                    } catch (Exception playbookEx) {
                        if (ArtifactCacheTurnFaults.isRepositoryUnavailable(playbookEx)) {
                            _logger.error("[{}] Artifact Cache repository unavailable during ChatAsync playbook",
                                    thingName, playbookEx);
                            fireAgentResponseEvent(effectiveConvId, AgentLoop.AgentResult.error(
                                    ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE,
                                    ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_MESSAGE,
                                    0, 0, 0, null));
                            return;
                        }
                        _logger.warn("[{}] ChatAsync playbook slash failed conversationId={}: {}", thingName,
                                effectiveConvId, playbookEx.getMessage(), playbookEx);
                        fireAgentResponseEvent(effectiveConvId,
                                AgentLoop.AgentResult.success(
                                        "Playbook execution failed: " + playbookEx.getMessage(),
                                        0, 0, 0, StreamTokenUsage.ZERO));
                        return;
                    } finally {
                        AgentToolContext.clear();
                    }

                    LlmTurnContext turn = buildLlmTurnContext(effectiveConvId, systemPrompt, message, null,
                            hostContextRenderedPrompt, ConversationRehydrateSettings.disabled());
                    List<ChatMessage> messages = turn.messages;
                    final String streamKey = streamConversationKey(effectiveConvId);
                    final String streamSource = streamKey;
                    messages.add(turn.userMessageForModel);
                    AgentMessageStreamAppender.append(streamKey, ChatMessage.user(message), streamSource, thingName,
                            StreamTokenUsage.ZERO, null, hostContextTurn.snapshotJson);
                    _hostContextTurnCarry.recordAfterUserRow(effectiveConvId, hostContextTurn.snapshotJson);
                    BiConsumer<ChatMessage, StreamTokenUsage> streamSink = (m, tu) -> {
                        ChatMessage mStream = m.getRole() == ChatMessage.Role.TOOL
                                ? ParlerToolStreamTableExportSidecar.augmentToolMessageForStreamAppend(
                                        m, null, effectiveConvId, effectiveConvId)
                                : m;
                        AgentMessageStreamAppender.append(streamKey, mStream, streamSource, thingName, tu);
                    };

                    ToolExecutor combinedExecutor = AgentThing.this::executeToolCall;
                    LlmClient turnLlm = llmClientForTurn();
                    AgentLoop loop = newAgentLoopForTurn(turnLlm, combinedExecutor, null, null);

                    List<ToolDefinition> tools = getMergedToolDefinitions();
                    AgentLoop.AgentResult result;
                    boolean loopCompletedOk = false;
                    try {
                        String chatNonce = UUID.randomUUID().toString();
                        AgentToolContext.setFetchCachedChatTurnNonce(chatNonce);
                        FetchCachedReplayGuard.beginTurn(FetchCachedReplayGuard.turnKeyForChat(effectiveConvId, chatNonce));
                        AgentToolContext.setConversationId(effectiveConvId);
                        AgentToolContext.setAgentThing(AgentThing.this);
                        AgentToolContext.setUserIanaTimezone(null);
                        AgentToolContext.setHostContextJson(null);
                        maybeInjectHostContextDocumentScope(hostContextTurn);
                        AgentToolContext.setParlerActiveMessages(messages);
                        AgentToolContext.setParlerEphemeralSystemIndices(turn.ephemeralIndices());
                        primeAgentTaskStateForTurn(turn, "", effectiveConvId);
                        AgentToolContext.resetTabularChartRound();
                        DocumentTurnToolNarrowing.resetTurnState();
                        result = loop.run(messages, tools, streamSink);
                        loopCompletedOk = true;
                    } finally {
                        ChartGroupTurnHooks.captureBeforeContextClear();
                        AgentToolContext.clear();
                        applySkillTurnMutationFinish(messages, turn, loopCompletedOk);
                    }
                    if (loopCompletedOk) {
                        if (isArtifactCacheTerminal(result)) {
                            ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(messages, result,
                                    isLlmReplayCompactionEffective(), _logger, effectiveConvId, null,
                                    _llmContextMaxChars,
                                    turnLlm, null, thingName,
                                    org.joda.time.DateTime.now(org.joda.time.DateTimeZone.UTC).toString());
                            _conversations.put(effectiveConvId, messages);
                            fireAgentResponseEvent(effectiveConvId, result);
                            return;
                        }
                        ChartGroupTurnHooks.onTurnEnd(result);
ChatMessage finalAssistant = ChatMessage.assistant(result.getContent());
                        messages.add(finalAssistant);
                        StreamTokenUsage finalTok = mergeAgentLoopUsageWithTurnPerf(result);
                        String assistantMessageId = UUID.randomUUID().toString();
                        AgentMessageStreamAppender.append(streamKey, finalAssistant, streamSource, thingName, finalTok,
                                assistantMessageId);
                        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(messages, result,
                                isLlmReplayCompactionEffective(), _logger, effectiveConvId, null,
                                _llmContextMaxChars,
                                turnLlm, assistantMessageId, thingName,
                                org.joda.time.DateTime.now(org.joda.time.DateTimeZone.UTC).toString());
                        _conversations.put(effectiveConvId, messages);

                        _logger.info("[{}] ChatAsync done conversationId={} status={} iterations={}",
                                thingName, effectiveConvId, result.getStatus(), result.getIterations());
                        fireAgentResponseEvent(effectiveConvId, result);
                    }
                }
            } catch (Exception e) {
                _logger.warn("[{}] ChatAsync failed conversationId={}: {}", thingName, effectiveConvId, e.getMessage());
                _logger.error("[{}] Async chat error: {}", thingName, e.getMessage(), e);
                if (ArtifactCacheTurnFaults.isRepositoryUnavailable(e)) {
                    fireAgentResponseEvent(effectiveConvId, AgentLoop.AgentResult.error(
                            ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE,
                            ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_MESSAGE,
                            0, 0, 0, null));
                }
            } finally {
                ThreadLocalContext.clearSecurityContext();
            }
        }, "agent-async-" + thingName).start();

        return effectiveConvId;
    }

    /** Fielded warn for discarded / rejected hostContext (parsable by log pipelines). */
    private void logHostContextDiscarded(String thingName, String logLabel, String headline,
            HostContextUplink.Decision hostScope) {
        _logger.warn("[{}] {}: {} {}", thingName, logLabel, headline,
                ParlerHostScopeLogFormatter.discardWarnSuffix(hostScope.rejectReason, hostScope.rejectDetail));
    }

    /**
     * Evaluates uplink {@code hostContext}, builds turn snapshot + freshness LLM block, logs discards.
     */
    private HostContextTurnPrep prepareHostContextTurn(String hostContextRaw, String conversationId, String logLabel) {
        HostContextPreviousSnapshot previous = HostContextPreviousSnapshot.NONE;
        if (conversationId != null && !conversationId.isEmpty()) {
            previous = _hostContextTurnCarry.getOrDefault(conversationId);
            if (previous.kind() == HostContextPreviousSnapshot.Kind.NONE) {
                DateTime clearedAt = AgentThreadDataTableSupport.loadHistoryClearedAtOrNull(conversationId);
                previous = HostContextPreviousSnapshotLookup.resolve(streamConversationKey(conversationId), clearedAt);
            }
        }
        HostContextTurnPrep prep = HostContextTurnPrep.prepare(hostContextRaw, this, previous);
        logHostContextUplinkDecision(prep.decision, logLabel);
        return prep;
    }

    /**
     * Server-injects the host-context document scope for this turn
     * (knowledge-retrieval-pipeline §3.2): when host-context was accepted via a
     * <strong>registered template</strong> ({@link HostContextUplink.Outcome#ACCEPTED} only —
     * not {@link HostContextUplink.Outcome#UNREGISTERED_GENERIC_FALLBACK}) and carries a
     * bound {@code context.thingName}, resolve the document set (override-first → built-in
     * matcher) and store it so the first {@code search_document_chunks} is already scoped.
     * Fails open — any problem leaves the turn unscoped.
     */
    private void maybeInjectHostContextDocumentScope(HostContextTurnPrep prep) {
        try {
            if (prep == null || prep.decision == null
                    || prep.decision.outcome != HostContextUplink.Outcome.ACCEPTED) {
                return;
            }
            String thingName = extractHostContextThingName(prep.rawWireBytesOrNull);
            if (thingName == null || thingName.isBlank()) {
                return;
            }
            DocumentKnowledgeRuntime.resolveHostContextScope(this, thingName).ifPresent(
                    scope -> AgentToolContext.setInjectedDocumentScope(
                            scope.documentIds(), scope.source().wire()));
        } catch (Exception e) {
            _logger.warn("[{}] host-context document scope injection failed: {}", getName(), e.getMessage());
        }
    }

    /** Reads {@code context.thingName} from the raw host-context wire JSON, or null. */
    private static String extractHostContextThingName(String wireJson) {
        if (wireJson == null || wireJson.isEmpty()) {
            return null;
        }
        try {
            JsonNode context = JSON.readTree(wireJson).get("context");
            if (context != null && context.isObject()) {
                JsonNode thingName = context.get("thingName");
                if (thingName != null && thingName.isTextual()) {
                    String value = thingName.asText().trim();
                    return value.isEmpty() ? null : value;
                }
            }
        } catch (Exception ignored) {
            // malformed wire -> no injection (fail open)
        }
        return null;
    }

    private void logHostContextUplinkDecision(HostContextUplink.Decision hostScope, String logLabel) {
        final String thingName = getName();
        if (hostScope.outcome == HostContextUplink.Outcome.OVERSIZE) {
            logHostContextDiscarded(thingName, logLabel,
                    "hostContext " + hostScope.measuredUtf8Bytes + " UTF-8 bytes exceeds "
                            + HostContextUplink.MAX_UTF8_BYTES + ", ignored",
                    hostScope);
        } else if (hostScope.outcome == HostContextUplink.Outcome.INVALID_JSON) {
            logHostContextDiscarded(thingName, logLabel, "hostContext invalid JSON, ignored", hostScope);
        } else if (hostScope.outcome == HostContextUplink.Outcome.MISSING_KEY) {
            logHostContextDiscarded(thingName, logLabel, "hostContext missing key, ignored", hostScope);
        } else if (hostScope.outcome == HostContextUplink.Outcome.UNREGISTERED_GENERIC_FALLBACK) {
            _logger.warn("[{}] {}: hostContext unregistered key={} utf8Bytes={} genericFallback=true "
                    + "(register a repository template for app-specific guidance)",
                    thingName, logLabel, hostScope.templateKey, hostScope.measuredUtf8Bytes);
        } else if (hostScope.outcome == HostContextUplink.Outcome.SCHEMA_REJECT) {
            logHostContextDiscarded(thingName, logLabel, "hostContext schema rejected, ignored", hostScope);
        } else if (hostScope.outcome == HostContextUplink.Outcome.RENDER_FAILED) {
            logHostContextDiscarded(thingName, logLabel, "hostContext render failed, ignored", hostScope);
        } else if (hostScope.outcome == HostContextUplink.Outcome.ACCEPTED
                && !hostScope.renderDiagnostics.isEmpty()) {
            _logger.info("[{}] {}: hostContext rendered key={} diagnostics={}", thingName, logLabel,
                    hostScope.templateKey, hostScope.renderDiagnostics);
        }
    }

    private static int hostContextUtf8Bytes(String hostContextRaw) {
        if (hostContextRaw == null || hostContextRaw.isEmpty()) {
            return 0;
        }
        return hostContextRaw.getBytes(StandardCharsets.UTF_8).length;
    }

    @ThingworxServiceDefinition(
        name = "ValidateHostContext",
        description = "Validate HostScopeJson (key + context), render preview, and return diagnostics. "
            + "See docs/architecture/host-context.md §13.")
    @ThingworxServiceResult(name = "result", baseType = "JSON",
        description = "Validation and render preview JSON")
    public JSONObject ValidateHostContext(
        @ThingworxServiceParameter(name = "hostScopeJson", baseType = "STRING",
            description = "UTF-8 HostScopeJson text (key + context)") String hostScopeJson
    ) {
        return HostContextValidate.validate(hostScopeJson, this);
    }

    /**
     * AlwaysOn Parler path: runs the agent loop and pushes Parler wire JSON to the bound Conversation
     * {@link com.thingworx.things.connected.RemoteThing} via {@code ReceiveMessage}. Invoked from
     * {@link ParlerGateway#SubmitUserPrompt}.
     */
    @ThingworxServiceDefinition(
        name = "ParlerStreamToRemoteThing",
        description = "Run Parler agent loop; stream frames as JSON through ReceiveMessage on the bound Gateway / "
            + "conversation Thing named remoteConversationThingName (must be connected). Enforces AgentThreadDataTable "
            + "ownership for that name (same as ParlerGateway.SubmitUserPrompt). Returns request_id immediately.")
    @ThingworxServiceResult(name = "result", baseType = "STRING", description = "request_id for this turn")
    public String ParlerStreamToRemoteThing(
        @ThingworxServiceParameter(name = "message", baseType = "STRING",
            description = "User message") String message,
        @ThingworxServiceParameter(name = "systemPrompt", baseType = "TEXT",
            description = "Optional system prompt override",
            aspects = {"isRequired:false"}) String systemPrompt,
        @ThingworxServiceParameter(name = "remoteConversationThingName", baseType = "STRING",
            description = "Bound ParlerGateway (or conversation) Thing name — ReceiveMessage target",
            aspects = {"isRequired:true"}) String remoteConversationThingName,
        @ThingworxServiceParameter(name = "userTimezone", baseType = "STRING",
            description = "Optional IANA id from the client for relative-time interpretation (e.g. Europe/Berlin)",
            aspects = {"isRequired:false"}) String userTimezone,
        @ThingworxServiceParameter(name = "hostContext", baseType = "STRING",
            description = "Optional UTF-8 JSON host-scope sideband (HostScopeJson); see CONTRACTS/API_CONTRACT.md",
            aspects = {"isRequired:false"}) String hostContext
    ) throws Exception {
        if (!hasLlmRuntimeConfigured()) {
            throw new Exception("[" + getName() + "] LLM client not initialized. Set AgentSettings.llmApiProviderRef to an enabled LLM API Provider Thing.");
        }
        if (remoteConversationThingName == null || remoteConversationThingName.trim().isEmpty()) {
            throw new Exception("[" + getName() + "] ParlerStreamToRemoteThing: remoteConversationThingName is required.");
        }
        String userMessage = message == null ? "" : message.trim();
        if (userMessage.isEmpty()) {
            throw new Exception("[" + getName() + "] ParlerStreamToRemoteThing: message is empty.");
        }
        final String remoteName = remoteConversationThingName.trim();
        AgentThreadDataTableSupport.ensureConversationOwnedByCurrentUser(remoteName,
                "[" + getName() + "] ParlerStreamToRemoteThing");
        Object rent = PlatformAccess.findProgrammatic(remoteName, RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (!(rent instanceof Thing)) {
            throw new Exception("[" + getName() + "] ParlerStreamToRemoteThing: no Thing named \"" + remoteName + "\".");
        }
        final Thing remoteConversation = (Thing) rent;
        Class<?> remoteThingClass = Class.forName("com.thingworx.things.connected.RemoteThing");
        if (!remoteThingClass.isInstance(remoteConversation)) {
            throw new Exception("[" + getName() + "] ParlerStreamToRemoteThing: \"" + remoteName + "\" is not a RemoteThing.");
        }
        Object connected = remoteThingClass.getMethod("isConnected").invoke(remoteConversation);
        if (!(connected instanceof Boolean) || !(Boolean) connected) {
            throw new Exception("[" + getName() + "] ParlerStreamToRemoteThing: \"" + remoteName
                    + "\" is not connected (bind AlwaysOn first).");
        }

        final String effectiveConvId = remoteName;
        final String thingName = getName();
        final String requestId = UUID.randomUUID().toString();

        final String canonicalUserTz = ParlerTimeAnchor.normalizeIanaOrNull(userTimezone);
        if (userTimezone != null && !userTimezone.trim().isEmpty() && canonicalUserTz == null) {
            _logger.warn("[{}] ParlerStreamToRemoteThing: invalid userTimezone \"{}\" ignored",
                    thingName, userTimezone);
        }

        _logger.info("[{}] ParlerStreamToRemoteThing start remoteThing={} requestId={} messageChars={} hostContextUtf8Bytes={}",
                thingName, remoteName, requestId, userMessage.length(),
                hostContextUtf8Bytes(hostContext));

        final SecurityContext callerSecurityContext = ThreadLocalContext.getSecurityContext();
        final Object convLock = lockForConversation(effectiveConvId);

        new Thread(() -> {
            final AtomicBoolean downlinkOk = new AtomicBoolean(true);
            try {
                if (callerSecurityContext != null) {
                    ThreadLocalContext.setSecurityContext(callerSecurityContext);
                }
                synchronized (convLock) {
                    if (!ParlerReceiveMessageSupport.send(remoteConversation,
                            ParlerReceiveMessageSupport.wireSessionAck(requestId, remoteName))) {
                        downlinkOk.set(false);
                        _logger.error("[{}] ParlerStreamToRemoteThing: session.ack not delivered; downlink broken for "
                                + "requestId={} — client must use transport fallback (see agent-alwayson.md §6.5)",
                                thingName, requestId);
                    }

                    Decision cacheAdmission = artifactCacheAdmission(
                            "ParlerStreamToRemoteThing", requestId, effectiveConvId);
                    if (!cacheAdmission.isReady()) {
                        AgentLoop.AgentResult rejection = artifactCacheAdmissionResult(cacheAdmission);
                        if (downlinkOk.get() && !sendArtifactCacheTerminal(
                                remoteConversation, requestId, remoteName, rejection)) {
                            downlinkOk.set(false);
                        }
                        fireAgentResponseEvent(effectiveConvId, rejection);
                        return;
                    }

                    final HostContextTurnPrep hostContextTurn = prepareHostContextTurn(hostContext, effectiveConvId,
                            "ParlerStreamToRemoteThing");
                    final String hostContextRenderedPrompt = hostContextTurn.llmEphemeralPromptOrNull;

                    PLAYBOOK_TOOL_CALLED_THIS_TURN.set(false);
                    bindPlaybookSlashTurnContext(effectiveConvId, requestId, remoteName, remoteConversation,
                            canonicalUserTz, hostContextRenderedPrompt, downlinkOk);
                    try {
                        PlaybookSlashEarly pe = tryExecutePlaybookSlashTurn(userMessage, effectiveConvId,
                                hostContextRenderedPrompt);
                        if (pe != null) {
                            finishPlaybookSlashTurn(userMessage, pe.assistantText, effectiveConvId, systemPrompt,
                                    canonicalUserTz, hostContextRenderedPrompt,
                                    ConversationRehydrateSettings.parlerAlwaysOnDefaults(),
                                    pe.playbookLlmUsage,
                                    hostContextTurn.snapshotJson,
                                    (assistantText, assistantMessageId, usage) -> {
                                        if (downlinkOk.get()) {
                                            if (!ParlerReceiveMessageSupport.send(remoteConversation,
                                                    ParlerReceiveMessageSupport.wireContentDelta(requestId,
                                                            remoteName, assistantText))) {
                                                downlinkOk.set(false);
                                            }
                                            if (downlinkOk.get()
                                                    && !ParlerReceiveMessageSupport.send(remoteConversation,
                                                            ParlerReceiveMessageSupport.wireDone(requestId,
                                                                    remoteName, assistantMessageId,
                                                                    usage != null ? usage.getLlmUsageJson() : null))) {
                                                downlinkOk.set(false);
                                            }
                                        }
                                    });
                            _logger.info(
                                    "[{}] ParlerStreamToRemoteThing playbook slash done remoteThing={} requestId={}",
                                    thingName, remoteName, requestId);
                            return;
                        }
                    } catch (Exception playbookEx) {
                        _logger.warn("[{}] ParlerStreamToRemoteThing playbook slash failed requestId={}: {}",
                                thingName, requestId, playbookEx.getMessage(), playbookEx);
                        if (ArtifactCacheTurnFaults.isRepositoryUnavailable(playbookEx)) {
                            _logger.error("[{}] Artifact Cache repository unavailable during AlwaysOn playbook",
                                    thingName, playbookEx);
                            AgentLoop.AgentResult cacheFailure = AgentLoop.AgentResult.error(
                                    ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE,
                                    ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_MESSAGE,
                                    0, 0, 0, null);
                            if (downlinkOk.get() && !sendArtifactCacheTerminal(
                                    remoteConversation, requestId, remoteName, cacheFailure)) {
                                downlinkOk.set(false);
                            }
                            fireAgentResponseEvent(effectiveConvId, cacheFailure);
                            return;
                        }
                        if (downlinkOk.get()) {
                            ParlerReceiveMessageSupport.send(remoteConversation,
                                    ParlerReceiveMessageSupport.wireError(requestId, remoteName,
                                            "Playbook execution failed: " + playbookEx.getMessage(), "exception"));
                            ParlerReceiveMessageSupport.send(remoteConversation,
                                    ParlerReceiveMessageSupport.wireDone(requestId, remoteName));
                        }
                        return;
                    } finally {
                        AgentToolContext.clear();
                    }

                    final String turnPrincipal = AgentThing.currentParlerPrincipalOrEmpty();
                    ParlerRunningTurnCancelRegistry.register(effectiveConvId, requestId, turnPrincipal, thingName);
                    try {

                    LlmTurnContext turn = buildLlmTurnContext(effectiveConvId, systemPrompt, userMessage,
                            canonicalUserTz, hostContextRenderedPrompt, ConversationRehydrateSettings.parlerAlwaysOnDefaults());
                    List<ChatMessage> messages = turn.messages;
                    final String streamKey = streamConversationKey(effectiveConvId);
                    final String streamSource = streamKey;
                    messages.add(turn.userMessageForModel);
                    AgentMessageStreamAppender.append(streamKey, ChatMessage.user(userMessage), streamSource, thingName,
                            StreamTokenUsage.ZERO, null, hostContextTurn.snapshotJson);
                    _hostContextTurnCarry.recordAfterUserRow(effectiveConvId, hostContextTurn.snapshotJson);

                    BiConsumer<ChatMessage, StreamTokenUsage> streamSink = (m, tu) -> {
                        if (m.getRole() == ChatMessage.Role.TOOL) {
                            ChatMessage mPersist = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(
                                    m, remoteConversation, requestId, remoteName);
                            AgentMessageStreamAppender.append(streamKey, mPersist, streamSource, thingName, tu);
                            if (!downlinkOk.get()) {
                                return;
                            }
                            if (remoteConversation == null) {
                                return;
                            }
                            ChatMessage mUi = FetchCachedStreamLaneHelper.resolveToolMessageForParlerTableDownlinks(
                                    m, mPersist, remoteConversation, requestId, remoteName);
                            ParlerToolArtifactWireEmitter.emitAfterResolvedToolUi(remoteConversation, requestId,
                                    remoteName, mUi, downlinkOk, m);
                            return;
                        }
                        AgentMessageStreamAppender.append(streamKey, m, streamSource, thingName, tu);
                        if (!downlinkOk.get()) {
                            return;
                        }
                        if (remoteConversation == null) {
                            return;
                        }
                        if (m.getRole() == ChatMessage.Role.ASSISTANT && m.hasToolCalls()) {
                            for (ToolCall tc : m.getToolCalls()) {
                                if (!downlinkOk.get()) {
                                    return;
                                }
                                String line = "Tool call: " + tc.getFunctionName()
                                        + " (" + trunc(tc.getId(), 64) + ")";
                                if (!ParlerReceiveMessageSupport.send(remoteConversation,
                                        ParlerReceiveMessageSupport.wireActivity(requestId, remoteName, line))) {
                                    downlinkOk.set(false);
                                }
                            }
                        }
                    };

                    ToolExecutor combinedExecutor = AgentThing.this::executeToolCall;
                    LlmClient turnLlm = llmClientForTurn();
                    AgentLoop loop = newAgentLoopForTurn(turnLlm, combinedExecutor,
                            rateControlSinkForRemote(remoteConversation, remoteName, requestId), canonicalUserTz);

                    List<ToolDefinition> tools = getMergedToolDefinitions();
                    AgentLoop.AgentResult result;
                    boolean loopCompletedOk = false;
                    try {
                        AgentToolContext.setConversationId(effectiveConvId);
                        AgentToolContext.setParlerGatewayCallerPrincipal(turnPrincipal);
                        AgentToolContext.setAgentThing(AgentThing.this);
                        AgentToolContext.setUserIanaTimezone(canonicalUserTz);
                        AgentToolContext.setParlerStreamIds(requestId, remoteName);
                        AgentToolContext.setParlerActiveMessages(messages);
                        AgentToolContext.setParlerEphemeralSystemIndices(turn.ephemeralIndices());
                        AgentToolContext.setHostContextJson(null);
                        maybeInjectHostContextDocumentScope(hostContextTurn);
                        AgentToolContext.setParlerRemoteConversation(remoteConversation);
                        AgentToolContext.setParlerDownlinkOk(downlinkOk);
                        FetchCachedReplayGuard.beginTurn(FetchCachedReplayGuard.turnKey(effectiveConvId, requestId));
                        primeAgentTaskStateForTurn(turn, requestId, effectiveConvId);
                        AgentToolContext.resetTabularChartRound();
                        AgentToolContext.resetChartArtifactObservabilityForTurn();
                        DocumentTurnToolNarrowing.resetTurnState();
                        result = loop.run(messages, tools, streamSink);
                        loopCompletedOk = true;
                        emitParlerTaskStateTurnEnd(result);
                    } finally {
                        if (!loopCompletedOk) {
                            TaskProgressWireEmitter.emitFailedTurnEnd();
                        }
                        ChartGroupTurnHooks.captureBeforeContextClear();
                        AgentToolContext.clear();
                        applySkillTurnMutationFinish(messages, turn, loopCompletedOk);
                    }

                    if (loopCompletedOk) {
                        if (result.getStatus() == AgentLoop.AgentResult.Status.AWAITING_APPROVAL) {
                            String pendingId = result.getApprovalPendingId();
                            synchronized (ParlerConversationLocks.lockFor(effectiveConvId)) {
                                PendingApprovalRecord par =
                                        pendingId != null ? PendingApprovalStore.get(pendingId) : null;
                                boolean tomb = ParlerGatewayUserStopTombstoneRegistry.isGatewayUserStopTerminalActive(
                                        effectiveConvId, requestId, turnPrincipal, thingName);
                                boolean suppressApprovalRequired =
                                        tomb || pendingId == null || par == null;
                                if (suppressApprovalRequired) {
                                    _logger.info(
                                            "[{}] ParlerStreamToRemoteThing: suppress approval.required "
                                                    + "(pendingId={} tombstoneActive={} storeHit={}) remoteThing={} requestId={}",
                                            thingName, pendingId, tomb, par != null, remoteName, requestId);
                                    ParlerRunningTurnCancelRegistry.clear(effectiveConvId, requestId, turnPrincipal,
                                            thingName);
                                } else {
                                    if (downlinkOk.get() && remoteConversation != null) {
                                        JSONObject summary = buildHitlApprovalSummary(par);
                                        String toolWireName = par.getGatedToolCall() != null
                                                ? par.getGatedToolCall().getFunctionName() : "unknown";
                                        String expiresIso = ISODateTimeFormat.dateTime().withZone(DateTimeZone.UTC)
                                                .print(DateTime.now(DateTimeZone.UTC).plusMinutes(15));
                                        JSONArray actions = new JSONArray();
                                        actions.put("approve");
                                        actions.put("cancel");
                                        actions.put("reject_with_comment");
                                        if (!ParlerReceiveMessageSupport.send(remoteConversation,
                                                ParlerReceiveMessageSupport.wireApprovalRequired(requestId, remoteName,
                                                        pendingId, expiresIso, toolWireName, summary, actions))) {
                                            downlinkOk.set(false);
                                        }
                                    }
                                    ParlerRunningTurnCancelRegistry.clear(effectiveConvId, requestId, turnPrincipal,
                                            thingName);
                                    _conversations.put(effectiveConvId, messages);
                                    _logger.info(
                                            "[{}] ParlerStreamToRemoteThing awaiting approval remoteThing={} requestId={} pendingId={}",
                                            thingName, remoteName, requestId, pendingId);
                                    fireAgentResponseEvent(effectiveConvId, result);
                                }
                            }
                        } else if (isArtifactCacheTerminal(result)) {
                            if (downlinkOk.get() && !sendArtifactCacheTerminal(
                                    remoteConversation, requestId, remoteName, result)) {
                                downlinkOk.set(false);
                            }
                            ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(messages, result,
                                    isLlmReplayCompactionEffective(), _logger, effectiveConvId, requestId,
                                    _llmContextMaxChars,
                                    turnLlm, null, thingName,
                                    org.joda.time.DateTime.now(org.joda.time.DateTimeZone.UTC).toString());
                            _conversations.put(effectiveConvId, messages);
                            _logger.info("[{}] ParlerStreamToRemoteThing cache terminal remoteThing={} requestId={}",
                                    thingName, remoteName, requestId);
                            fireAgentResponseEvent(effectiveConvId, result);
                        } else if (result.getStatus() == AgentLoop.AgentResult.Status.CANCELLED) {
                            ChartGroupTurnHooks.onTurnEnd(result);
                            if (downlinkOk.get()) {
                                ParlerReceiveMessageSupport.send(remoteConversation,
                                        ParlerReceiveMessageSupport.wireSessionCancelled(requestId, remoteName,
                                                "user_stop", "Turn stopped."));
                            }
                            ParlerGatewayUserStopTombstoneRegistry.noteGatewayUserStopTerminal(effectiveConvId,
                                    requestId, turnPrincipal, thingName);
                            PlaybookActiveRunTracker.cancel(streamConversationKey(effectiveConvId));
                            try {
                                String tk = FetchCachedReplayGuard.turnKey(effectiveConvId, requestId);
                                ConsecutiveIdenticalToolCallRegistry.removeTurn(tk);
                                DocumentSearchProgressGuardRegistry.removeTurn(tk);
                                PresentationArtifactRegistry.removeTurn(tk);
                                LazyToolRegistrationRegistry.removeTurn(tk);
                            } catch (Exception ignore) {
                                // defensive
                            }
                            _conversations.put(effectiveConvId, messages);
                            _logger.info("[{}] ParlerStreamToRemoteThing user cancel remoteThing={} requestId={}",
                                    thingName, remoteName, requestId);
                            fireAgentResponseEvent(effectiveConvId, result);
                        } else {
                            ChartGroupTurnHooks.onTurnEnd(result);
ChatMessage finalAssistant = ChatMessage.assistant(result.getContent());
                            messages.add(finalAssistant);
                            StreamTokenUsage finalTok = mergeAgentLoopUsageWithTurnPerf(result);
                            String assistantMessageId = UUID.randomUUID().toString();
                            AgentMessageStreamAppender.append(streamKey, finalAssistant, streamSource, thingName,
                                    finalTok, assistantMessageId);
                            if (downlinkOk.get()) {
                                if (result.getStatus() == AgentLoop.AgentResult.Status.SUCCESS) {
                                    String content = result.getContent() != null ? result.getContent() : "";
                                    if (!ParlerReceiveMessageSupport.send(remoteConversation,
                                            ParlerReceiveMessageSupport.wireContentDelta(requestId, remoteName, content))) {
                                        downlinkOk.set(false);
                                    }
                                } else {
                                    String err = result.getContent() != null ? result.getContent() : result.getStatus().name();
                                    if (!ParlerReceiveMessageSupport.send(remoteConversation,
                                            ParlerReceiveMessageSupport.wireError(requestId, remoteName, err,
                                                    result.getStatus().name()))) {
                                        downlinkOk.set(false);
                                    }
                                }
                                if (downlinkOk.get()) {
                                    if (!ParlerReceiveMessageSupport.send(remoteConversation,
                                            ParlerReceiveMessageSupport.wireDone(requestId, remoteName,
                                                    assistantMessageId, finalTok.getLlmUsageJson()))) {
                                        downlinkOk.set(false);
                                        _logger.warn("[{}] ParlerStreamToRemoteThing: wire done not delivered for requestId={}; "
                                                + "widget may never see session.done — use transport fallback (§6.5)",
                                                thingName, requestId);
                                    }
                                }
                            }
                            if (!downlinkOk.get()) {
                                _logger.warn("[{}] ParlerStreamToRemoteThing: skipped or truncated ReceiveMessage tail for "
                                        + "requestId={}; widget must clear busy via transport fallback (§6.4–6.5)",
                                        thingName, requestId);
                            }
                            ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(messages, result,
                                    isLlmReplayCompactionEffective(), _logger, effectiveConvId, requestId,
                                    _llmContextMaxChars,
                                    turnLlm, assistantMessageId, thingName,
                                    org.joda.time.DateTime.now(org.joda.time.DateTimeZone.UTC).toString());
                            _conversations.put(effectiveConvId, messages);
                            _logger.info("[{}] ParlerStreamToRemoteThing done remoteThing={} requestId={} status={}",
                                    thingName, remoteName, requestId, result.getStatus());
                            fireAgentResponseEvent(effectiveConvId, result);
                        }
                    }
                    } finally {
                        ParlerRunningTurnCancelRegistry.clear(effectiveConvId, requestId, turnPrincipal, thingName);
                    }
                }
            } catch (Exception e) {
                _logger.warn("[{}] ParlerStreamToRemoteThing failed remoteThing={} requestId={}: {}",
                        thingName, remoteName, requestId, e.getMessage());
                _logger.error("[{}] ParlerStreamToRemoteThing error", thingName, e);
                if (ArtifactCacheTurnFaults.isRepositoryUnavailable(e)) {
                    AgentLoop.AgentResult cacheFailure = AgentLoop.AgentResult.error(
                            ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE,
                            ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_MESSAGE,
                            0, 0, 0, null);
                    if (downlinkOk.get() && !sendArtifactCacheTerminal(
                            remoteConversation, requestId, remoteName, cacheFailure)) {
                        downlinkOk.set(false);
                    }
                    fireAgentResponseEvent(effectiveConvId, cacheFailure);
                    return;
                }
                if (downlinkOk.get()) {
                    boolean er = ParlerReceiveMessageSupport.send(remoteConversation,
                            ParlerReceiveMessageSupport.wireError(requestId, remoteName, e.getMessage(), "exception"));
                    if (er && !ParlerReceiveMessageSupport.send(remoteConversation,
                            ParlerReceiveMessageSupport.wireDone(requestId, remoteName))) {
                        _logger.warn("[{}] ParlerStreamToRemoteThing: recovery error delivered but wireDone failed for "
                                + "requestId={}; widget must use transport fallback (§6.5)",
                                thingName, requestId);
                    }
                }
            } finally {
                ThreadLocalContext.clearSecurityContext();
            }
        }, "agent-parler-alwayson-" + thingName).start();

        return requestId;
    }

    /**
     * Shared lock for a Parler {@code conversation_id} (Gateway name). Used by {@link ParlerGateway} approval uplink
     * and {@link #ParlerStreamToRemoteThing}.
     */
    public static Object parlerConversationLock(String conversationId) {
        return ParlerConversationLocks.lockFor(conversationId);
    }

    /**
     * Validates the caller, removes the pending record, and returns it. Synchronized per conversation id.
     *
     * @param expectedAgentThingNameOrNull when non-null, pending record must name this Agent Thing (direct invoke path)
     */
    public static PendingApprovalRecord consumePendingParlerApprovalRecord(
            String pendingId,
            String decision,
            String requestId,
            String conversationId,
            String expectedAgentThingNameOrNull) throws Exception {
        String pidRaw = pendingId != null ? pendingId : "";
        String ridRaw = requestId != null ? requestId : "";
        String cidRaw = conversationId != null ? conversationId : "";
        String decRaw = decision != null ? decision : "";
        if (pendingId == null || pendingId.trim().isEmpty()) {
            ParlerHitlAuditLog.decisionRejected("MISSING_PENDING_ID", "consume", "-", pidRaw, ridRaw, cidRaw, decRaw,
                    parlerCurrentPrincipal(), "-");
            throw new Exception("[Parler] pendingId is required.");
        }
        if (decision == null || decision.trim().isEmpty()) {
            ParlerHitlAuditLog.decisionRejected("MISSING_DECISION", "consume", "-", pidRaw, ridRaw, cidRaw, decRaw,
                    parlerCurrentPrincipal(), "-");
            throw new Exception("[Parler] decision is required.");
        }
        if (requestId == null || requestId.trim().isEmpty()
                || conversationId == null || conversationId.trim().isEmpty()) {
            ParlerHitlAuditLog.decisionRejected("MISSING_REQUEST_OR_CONVERSATION", "consume", "-", pidRaw, ridRaw,
                    cidRaw, decRaw, parlerCurrentPrincipal(), "-");
            throw new Exception("[Parler] requestId and conversationId are required.");
        }
        final String dec = decision.trim();
        if (!"approve".equalsIgnoreCase(dec) && !"cancel".equalsIgnoreCase(dec)
                && !"reject_with_comment".equalsIgnoreCase(dec)) {
            ParlerHitlAuditLog.decisionRejected("INVALID_DECISION", "consume", "-", pidRaw, ridRaw, cidRaw, decRaw,
                    parlerCurrentPrincipal(), "-");
            throw new Exception("[Parler] decision must be approve, cancel, or reject_with_comment.");
        }
        final String pid = pendingId.trim();
        final String rid = requestId.trim();
        final String cid = conversationId.trim();
        synchronized (parlerConversationLock(cid)) {
            PendingApprovalRecord got = PendingApprovalStore.get(pid);
            if (got == null) {
                ParlerHitlAuditLog.decisionRejected("UNKNOWN_PENDING", "consume", "-", pid, rid, cid, dec,
                        parlerCurrentPrincipal(), "-");
                throw new Exception("[Parler] Unknown or expired pending_id.");
            }
            if (!got.getRequestId().equals(rid)) {
                ParlerHitlAuditLog.decisionRejected("REQUEST_ID_MISMATCH", "consume", "-", pid, rid, cid, dec,
                        parlerCurrentPrincipal(), got.getAgentThingName());
                throw new Exception("[Parler] request_id does not match pending record.");
            }
            if (!got.getConversationId().equals(cid)) {
                ParlerHitlAuditLog.decisionRejected("CONVERSATION_ID_MISMATCH", "consume", "-", pid, rid, cid, dec,
                        parlerCurrentPrincipal(), got.getAgentThingName());
                throw new Exception("[Parler] conversation_id does not match pending record.");
            }
            String principal = parlerCurrentPrincipal();
            if (!got.getPrincipal().equals(principal)) {
                ParlerHitlAuditLog.decisionRejected("PRINCIPAL_MISMATCH", "consume", "-", pid, rid, cid, dec,
                        principal, got.getAgentThingName());
                throw new Exception("[Parler] Approval must be submitted by the same user who started the turn.");
            }
            if (expectedAgentThingNameOrNull != null
                    && !expectedAgentThingNameOrNull.equals(got.getAgentThingName())) {
                ParlerHitlAuditLog.decisionRejected("WRONG_AGENT_THING", "consume", "-", pid, rid, cid, dec,
                        principal, got.getAgentThingName());
                throw new Exception("[Parler] pending_id belongs to another Agent Thing.");
            }
            if (got.isExpiredAt(System.currentTimeMillis())) {
                ParlerHitlAuditLog.decisionRejected("LATE_SUBMIT_EXPIRED", "consume", "-", pid, rid, cid, dec,
                        principal, got.getAgentThingName());
                PendingApprovalRecord takenExpired = PendingApprovalStore.compareAndRemove(pid, got);
                if (takenExpired != null) {
                    deliverParlerApprovalExpired(takenExpired);
                    throw new Exception("[Parler] Pending approval expired.");
                }
                ParlerHitlAuditLog.decisionRejected("PENDING_NO_LONGER_ACTIVE", "consume", "-", pid, rid, cid, dec,
                        principal, "-");
                throw new Exception("[Parler] Pending approval is no longer active.");
            }
            PendingApprovalRecord taken = PendingApprovalStore.compareAndRemove(pid, got);
            if (taken == null) {
                ParlerHitlAuditLog.decisionRejected("PENDING_NO_LONGER_ACTIVE", "consume", "-", pid, rid, cid, dec,
                        principal, "-");
                throw new Exception("[Parler] Pending approval is no longer active.");
            }
            return taken;
        }
    }

    private static String parlerCurrentPrincipal() {
        try {
            SecurityContext ctx = ThreadLocalContext.getSecurityContext();
            return ctx != null && ctx.getName() != null ? ctx.getName() : "Anonymous";
        } catch (Exception e) {
            return "Anonymous";
        }
    }

    /** Principal snapshot for Parler AlwaysOn running-turn cancel registry (gateway worker thread). */
    static String currentParlerPrincipalOrEmpty() {
        return parlerCurrentPrincipal();
    }

    private static final String AI_AGENT_TEMPLATE_NAME = "AIAgent";

    /**
     * v1 Parler HITL uplink on {@link ParlerGateway}: validates thread ownership, resolves {@link AgentThing}, then
     * removes the pending record and continues the turn. Synchronized with {@link #ParlerStreamToRemoteThing}.
     */
    public static void completeParlerApprovalFromParlerGateway(
            ParlerGateway gateway,
            String pendingId,
            String decision,
            String requestId,
            String conversationId,
            String comment) throws Exception {
        String pidRaw = pendingId != null ? pendingId : "";
        String ridRaw = requestId != null ? requestId : "";
        String cidRaw = conversationId != null ? conversationId : "";
        String decRaw = decision != null ? decision : "";
        if (gateway == null) {
            ParlerHitlAuditLog.decisionRejected("GATEWAY_NULL", "gateway", "-", pidRaw, ridRaw, cidRaw, decRaw,
                    parlerCurrentPrincipal(), "-");
            throw new Exception("[Parler] Gateway reference is null.");
        }
        final String gname = gateway.getName();
        if (conversationId == null || !conversationId.trim().equals(gname)) {
            ParlerHitlAuditLog.decisionRejected("GATEWAY_CONV_MISMATCH", "gateway", gname, pidRaw, ridRaw, cidRaw,
                    decRaw, parlerCurrentPrincipal(), "-");
            throw new Exception("[" + gname + "] conversationId must match this Gateway name.");
        }
        try {
            AgentThreadDataTableSupport.ensureConversationOwnedByCurrentUser(gname,
                    "[" + gname + "] SubmitApprovalDecision");
        } catch (Exception e) {
            ParlerHitlAuditLog.decisionRejected("CONVERSATION_NOT_OWNED", "gateway", gname, pidRaw, ridRaw, cidRaw,
                    decRaw, parlerCurrentPrincipal(), "-");
            throw e;
        }

        if (pendingId == null || pendingId.trim().isEmpty()) {
            ParlerHitlAuditLog.decisionRejected("MISSING_PENDING_ID", "gateway", gname, pidRaw, ridRaw, cidRaw,
                    decRaw, parlerCurrentPrincipal(), "-");
            throw new Exception("[" + gname + "] pendingId is required.");
        }
        if (decision == null || decision.trim().isEmpty()) {
            ParlerHitlAuditLog.decisionRejected("MISSING_DECISION", "gateway", gname, pidRaw, ridRaw, cidRaw,
                    decRaw, parlerCurrentPrincipal(), "-");
            throw new Exception("[" + gname + "] decision is required.");
        }
        if (requestId == null || requestId.trim().isEmpty()) {
            ParlerHitlAuditLog.decisionRejected("MISSING_REQUEST_ID", "gateway", gname, pidRaw, ridRaw, cidRaw,
                    decRaw, parlerCurrentPrincipal(), "-");
            throw new Exception("[" + gname + "] requestId is required.");
        }
        final String dec = decision.trim();
        if (!"approve".equalsIgnoreCase(dec) && !"cancel".equalsIgnoreCase(dec)
                && !"reject_with_comment".equalsIgnoreCase(dec)) {
            ParlerHitlAuditLog.decisionRejected("INVALID_DECISION", "gateway", gname, pidRaw, ridRaw, cidRaw,
                    decRaw, parlerCurrentPrincipal(), "-");
            throw new Exception("[" + gname + "] decision must be approve, cancel, or reject_with_comment.");
        }
        final String pid = pendingId.trim();
        final String rid = requestId.trim();
        final String cid = conversationId.trim();

        synchronized (parlerConversationLock(cid)) {
            PendingApprovalRecord got = PendingApprovalStore.get(pid);
            if (got == null) {
                ParlerHitlAuditLog.decisionRejected("UNKNOWN_PENDING", "gateway", gname, pid, rid, cid, dec,
                        parlerCurrentPrincipal(), "-");
                throw new Exception("[" + gname + "] Unknown or expired pending_id.");
            }
            if (!got.getRequestId().equals(rid)) {
                ParlerHitlAuditLog.decisionRejected("REQUEST_ID_MISMATCH", "gateway", gname, pid, rid, cid, dec,
                        parlerCurrentPrincipal(), got.getAgentThingName());
                throw new Exception("[" + gname + "] request_id does not match pending record.");
            }
            if (!got.getConversationId().equals(cid)) {
                ParlerHitlAuditLog.decisionRejected("CONVERSATION_ID_MISMATCH", "gateway", gname, pid, rid, cid,
                        dec, parlerCurrentPrincipal(), got.getAgentThingName());
                throw new Exception("[" + gname + "] conversation_id does not match pending record.");
            }
            if (!got.getPrincipal().equals(parlerCurrentPrincipal())) {
                ParlerHitlAuditLog.decisionRejected("PRINCIPAL_MISMATCH", "gateway", gname, pid, rid, cid, dec,
                        parlerCurrentPrincipal(), got.getAgentThingName());
                throw new Exception("[" + gname + "] Approval must be submitted by the same user who started the turn.");
            }
            if (got.isExpiredAt(System.currentTimeMillis())) {
                ParlerHitlAuditLog.decisionRejected("LATE_SUBMIT_EXPIRED", "gateway", gname, pid, rid, cid, dec,
                        parlerCurrentPrincipal(), got.getAgentThingName());
                PendingApprovalRecord takenExpired = PendingApprovalStore.compareAndRemove(pid, got);
                if (takenExpired != null) {
                    deliverParlerApprovalExpired(takenExpired);
                    throw new Exception("[" + gname + "] Pending approval expired.");
                }
                ParlerHitlAuditLog.decisionRejected("PENDING_NO_LONGER_ACTIVE", "gateway", gname, pid, rid, cid, dec,
                        parlerCurrentPrincipal(), "-");
                throw new Exception("[" + gname + "] Pending approval is no longer active.");
            }

            RootEntity ent = PlatformAccess.findAsUser(got.getAgentThingName(),
                    RelationshipTypes.ThingworxRelationshipTypes.Thing);
            if (!(ent instanceof AgentThing)) {
                ParlerHitlAuditLog.decisionRejected("AGENT_NOT_FOUND", "gateway", gname, pid, rid, cid, dec,
                        parlerCurrentPrincipal(), got.getAgentThingName());
                throw new Exception("[" + gname + "] No AIAgent Thing named \"" + got.getAgentThingName() + "\".");
            }
            AgentThing agent = (AgentThing) ent;
            try {
                ThingTemplateSupport.ensureThingDerivedFromTemplate(agent, AI_AGENT_TEMPLATE_NAME,
                        "[" + gname + "] SubmitApprovalDecision");
            } catch (Exception e) {
                ParlerHitlAuditLog.decisionRejected("AGENT_TEMPLATE_INVALID", "gateway", gname, pid, rid, cid, dec,
                        parlerCurrentPrincipal(), got.getAgentThingName());
                throw e;
            }

            int cLen = comment != null ? comment.length() : 0;
            ParlerHitlAuditLog.decisionConsumed("gateway", gname, got, dec, cLen);
            PendingApprovalRecord taken = PendingApprovalStore.compareAndRemove(pid, got);
            if (taken == null) {
                ParlerHitlAuditLog.decisionRejected("PENDING_NO_LONGER_ACTIVE", "gateway", gname, pid, rid, cid, dec,
                        parlerCurrentPrincipal(), "-");
                throw new Exception("[" + gname + "] Pending approval is no longer active.");
            }
            agent.enqueueParlerApprovalContinuationFromGateway(taken, decision, comment);
        }
    }

    private static final String PARLER_GATEWAY_USER_STOP_SYNTHETIC_TOOL_JSON =
            "{\"status\":\"error\",\"code\":\"TURN_CANCELLED\",\"message\":\"Turn was stopped by the user before this tool call ran.\"}";

    private static String parlerCancelUserPromptResultJson(String status, String conversationId, String requestId,
            Boolean alreadyRequested) {
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

    /**
     * AlwaysOn gateway user stop (v1 parked HITL): CAS-removes a matching pending approval, mutates in-memory
     * conversation + stream with synthetic {@code TURN_CANCELLED} tool results when safe, and emits live
     * {@code approval.resolved} + {@code session.cancelled} (no post-approval LLM).
     *
     * @return JSON result envelope per {@code docs/agent/turn-cancellation-control.md} §6.1
     */
    public static String cancelUserPromptFromParlerGateway(
            ParlerGateway gateway,
            String requestId,
            String agentThingName,
            String reasonOrNull) throws Exception {
        if (gateway == null) {
            throw new Exception("[Parler] CancelUserPrompt: gateway is null.");
        }
        final String gname = gateway.getName();
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new Exception("[" + gname + "] CancelUserPrompt: requestId is required.");
        }
        if (agentThingName == null || agentThingName.trim().isEmpty()) {
            throw new Exception("[" + gname + "] CancelUserPrompt: agentThingName is required.");
        }
        final String rid = requestId.trim();
        final String agent = agentThingName.trim();
        final String cid = gname != null ? gname.trim() : "";
        final String prefix = "[" + gname + "] CancelUserPrompt";
        AgentThreadDataTableSupport.ensureConversationOwnedByCurrentUser(cid, prefix);
        ConversationMetadata meta = AgentThreadDataTableSupport.loadConversationMetadataForCurrentUser(cid, prefix);
        String bound = meta.getAgentName() != null ? meta.getAgentName().trim() : "";
        if (bound.isEmpty()) {
            throw new Exception(prefix + ": thread row has empty agentName.");
        }
        if (!bound.equals(agent)) {
            return parlerCancelUserPromptResultJson("wrong_agent", cid, rid, null);
        }
        RootEntity ent = PlatformAccess.findAsUser(bound, RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (ent == null || !(ent instanceof AgentThing)) {
            throw new Exception(prefix + ": no AIAgent Thing named \"" + bound + "\".");
        }
        AgentThing agentThing = (AgentThing) ent;
        ThingTemplateSupport.ensureThingDerivedFromTemplate(agentThing, AI_AGENT_TEMPLATE_NAME, prefix);

        final String principal = parlerCurrentPrincipal();
        return ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve(gname, cid, rid, agent, principal,
                (taken, gn, reason) -> agentThing.applyGatewayUserStopParkedToConversation(taken, reason, gn),
                reasonOrNull);
    }

    /**
     * Invoked under {@link #parlerConversationLock} after a successful {@link PendingApprovalStore#compareAndRemove} for
     * gateway user stop. The caller **must** have already recorded {@link ParlerGatewayUserStopTombstoneRegistry} for this
     * tuple (immediately after CAS wins).
     */
    private void applyGatewayUserStopParkedToConversation(PendingApprovalRecord taken, String reasonOrNull,
            String gatewayNameForAudit) {
        if (taken == null) {
            return;
        }
        final String wireCid = taken.getConversationId();
        final String wireRid = taken.getRequestId();
        final String thingName = getName();
        final String gatedId = taken.getGatedToolCall() != null ? taken.getGatedToolCall().getId() : null;
        final List<ChatMessage> snapshot = taken.getMessagesCopy();
        final String sk = streamConversationKey(wireCid);

        List<ChatMessage> msgs = null;
        List<ChatMessage> current = _conversations.get(wireCid);
        if (current == null || current.isEmpty()) {
            DateTime clearedAt = AgentThreadDataTableSupport.loadHistoryClearedAtOrNull(wireCid);
            if (ConversationClearPendingGuard.suppressExpiredPendingSnapshotRestore(clearedAt,
                    taken.getCreatedAtEpochMillis())) {
                _logger.info("[{}] gateway user stop: skip empty-memory restore (conversation cleared after pending) "
                        + "cid={} pendingCreatedAt={} historyClearedAt={}",
                        thingName, wireCid, taken.getCreatedAtEpochMillis(), clearedAt);
            } else {
                msgs = new ArrayList<>(snapshot);
            }
        } else if (gatedId != null && conversationHasToolResultForCallId(current, gatedId)) {
            _logger.info("[{}] gateway user stop: gated tool result already present; skip transcript mutation cid={}",
                    thingName, wireCid);
        } else if (current.size() > snapshot.size()) {
            _logger.info("[{}] gateway user stop: skip conversation rewrite (session advanced) cid={} currentSize={} "
                    + "snapshotSize={}",
                    thingName, wireCid, current.size(), snapshot.size());
        } else if (current.size() < snapshot.size()) {
            _logger.warn("[{}] gateway user stop: skip conversation rewrite (current shorter than snapshot) cid={}",
                    thingName, wireCid);
        } else if (!conversationMatchesSnapshot(current, snapshot)) {
            _logger.info("[{}] gateway user stop: skip conversation rewrite (diverged) cid={}", thingName, wireCid);
        } else {
            msgs = new ArrayList<>(current);
        }

        if (msgs != null) {
            if (gatedId != null && taken.getGatedToolCall() != null) {
                HitlSyntheticToolResultAppender.appendDurable(msgs, gatedId, PARLER_GATEWAY_USER_STOP_SYNTHETIC_TOOL_JSON,
                        sk, sk, thingName, taken.getGatedToolCall().getFunctionName());
            }
            appendHitlInterruptedBatchSiblingToolResultsDurable(taken, msgs, wireCid);
            _conversations.put(wireCid, msgs);
        }

        Thing remoteConversation = resolveConnectedRemoteThing(taken.getRemoteThingName());
        if (remoteConversation != null) {
            ParlerReceiveMessageSupport.sendParkedGatewayUserStopLiveWirePair(remoteConversation, wireRid, wireCid,
                    taken.getPendingId(), reasonOrNull);
        } else {
            _logger.warn("[{}] gateway user stop: RemoteThing not connected; skip live downlink cid={} requestId={}",
                    thingName, wireCid, wireRid);
        }
        PlaybookActiveRunTracker.cancel(sk);
        try {
            String tk = FetchCachedReplayGuard.turnKey(wireCid, wireRid);
            ConsecutiveIdenticalToolCallRegistry.removeTurn(tk);
            DocumentSearchProgressGuardRegistry.removeTurn(tk);
            PresentationArtifactRegistry.removeTurn(tk);
            LazyToolRegistrationRegistry.removeTurn(tk);
        } catch (Exception ignore) {
            // defensive
        }
        ParlerHitlAuditLog.decisionConsumed("gateway_stop", gatewayNameForAudit != null ? gatewayNameForAudit : "-",
                taken, "gateway_user_stop", 0);
        ParlerHitlAuditLog.continuationOutcome(taken, "gateway_user_stop", "cancelled", false, "", thingName);
    }

    /**
     * Entry point after {@link ParlerGateway#SubmitApprovalDecision} consumed the pending record; runs the continuation
     * on this Agent Thing's executor thread.
     */
    public void enqueueParlerApprovalContinuationFromGateway(PendingApprovalRecord rec, String decision, String comment)
            throws Exception {
        if (!hasLlmRuntimeConfigured()) {
            ParlerHitlAuditLog.decisionRejected("LLM_NOT_INITIALIZED", "continuation_enqueue", "-",
                    rec != null ? rec.getPendingId() : "",
                    rec != null ? rec.getRequestId() : "",
                    rec != null ? rec.getConversationId() : "",
                    decision != null ? decision : "",
                    parlerCurrentPrincipal(), getName());
            throw new Exception("[" + getName() + "] LLM client not initialized. Set AgentSettings.llmApiProviderRef to an enabled LLM API Provider Thing.");
        }
        if (!getName().equals(rec.getAgentThingName())) {
            ParlerHitlAuditLog.decisionRejected("ENQUEUE_AGENT_MISMATCH", "continuation_enqueue", "-",
                    rec.getPendingId(), rec.getRequestId(), rec.getConversationId(),
                    decision != null ? decision : "", parlerCurrentPrincipal(), rec.getAgentThingName());
            throw new Exception("[" + getName() + "] Pending record targets another agent.");
        }
        final SecurityContext submitterCtx = ThreadLocalContext.getSecurityContext();
        final String dec = decision != null ? decision.trim() : "";
        final String cmt = comment != null ? comment : "";
        new Thread(() -> runParlerApprovalContinuation(rec, dec, submitterCtx, cmt),
                "agent-parler-approval-" + getName()).start();
    }

    @ThingworxServiceDefinition(
        name = "SubmitParlerApprovalDecision",
        description = "Parler HITL (alternate path): submit approve/cancel on this Agent Thing. "
            + "Prefer ParlerGateway.SubmitApprovalDecision (same uplink locus as SubmitUserPrompt).")
    @ThingworxServiceResult(name = "result", baseType = "NOTHING")
    public void SubmitParlerApprovalDecision(
        @ThingworxServiceParameter(name = "pendingId", baseType = "STRING",
            description = "pending_id from approval.required") String pendingId,
        @ThingworxServiceParameter(name = "decision", baseType = "STRING",
            description = "approve, cancel, or reject_with_comment") String decision,
        @ThingworxServiceParameter(name = "requestId", baseType = "STRING",
            description = "Must match approval.required.request_id") String requestId,
        @ThingworxServiceParameter(name = "conversationId", baseType = "STRING",
            description = "Must match wire conversation_id") String conversationId,
        @ThingworxServiceParameter(name = "comment", baseType = "STRING",
            description = "Optional; reserved for reject_with_comment",
            aspects = {"isRequired:false"}) String comment
    ) throws Exception {
        if (!hasLlmRuntimeConfigured()) {
            ParlerHitlAuditLog.decisionRejected("LLM_NOT_INITIALIZED", "agent_service", "-",
                    pendingId != null ? pendingId : "",
                    requestId != null ? requestId : "",
                    conversationId != null ? conversationId : "",
                    decision != null ? decision : "",
                    parlerCurrentPrincipal(), getName());
            throw new Exception("[" + getName() + "] LLM client not initialized. Set AgentSettings.llmApiProviderRef to an enabled LLM API Provider Thing.");
        }
        PendingApprovalRecord rec = consumePendingParlerApprovalRecord(
                pendingId, decision, requestId, conversationId, getName());
        ParlerHitlAuditLog.decisionConsumed("agent_service", "-", rec,
                decision != null ? decision.trim() : "", comment != null ? comment.length() : 0);
        enqueueParlerApprovalContinuationFromGateway(rec, decision, comment);
    }

    /**
     * After {@code approval.resolved} and the gated-tool result row is appended to {@code messages},
     * runs the agent loop and Parler downlink ({@code content.delta} / nested {@code approval.required} /
     * {@code error} + {@code done}) — shared by approve, cancel, and reject_with_comment continuations.
     */
    /**
     * Optional seed so the post-HITL LLM round sees {@code Recent Tool Evidence} for the gated tool (task-state v1a).
     */
    private static final class HitlEvidenceSeed {
        final ToolCall gated;
        final String approvedInvokeJson;
        final TaskStateErrorCode hitlTerminalCode;
        /** Non-null only for approved {@code set_property_value} continuation (v1b progress). */
        final String approvedSetPropertyJson;
        /** With {@link #approvedInvokeJson}: resolved Thing for extended-tool HITL continuation. */
        final String hitlExtendedTargetThing;
        final String hitlExtendedTargetService;

        static HitlEvidenceSeed approveInvoke(ToolCall gated, String json) {
            return new HitlEvidenceSeed(gated, json, null, null, null, null);
        }

        static HitlEvidenceSeed approveSetProperty(ToolCall gated, String json) {
            return new HitlEvidenceSeed(gated, null, null, json, null, null);
        }

        static HitlEvidenceSeed terminal(ToolCall gated, TaskStateErrorCode code) {
            return new HitlEvidenceSeed(gated, null, code, null, null, null);
        }

        static HitlEvidenceSeed approveExtended(ToolCall gated, String json, String targetThing, String service) {
            return new HitlEvidenceSeed(gated, json, null, null, targetThing, service);
        }

        private HitlEvidenceSeed(ToolCall gated, String approvedInvokeJson, TaskStateErrorCode hitlTerminalCode,
                String approvedSetPropertyJson, String hitlExtendedTargetThing, String hitlExtendedTargetService) {
            this.gated = gated;
            this.approvedInvokeJson = approvedInvokeJson;
            this.hitlTerminalCode = hitlTerminalCode;
            this.approvedSetPropertyJson = approvedSetPropertyJson;
            this.hitlExtendedTargetThing = hitlExtendedTargetThing;
            this.hitlExtendedTargetService = hitlExtendedTargetService;
        }
    }

    private void applyHitlTaskStateSeed(HitlEvidenceSeed seed) {
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        if (st == null || seed == null || seed.gated == null) {
            return;
        }
        if (seed.approvedInvokeJson != null && seed.hitlExtendedTargetThing != null && seed.hitlExtendedTargetService != null) {
            st.addSeededExtendedToolRow(seed.gated, seed.hitlExtendedTargetThing, seed.hitlExtendedTargetService,
                    seed.approvedInvokeJson);
        } else if (seed.approvedInvokeJson != null && "invoke_service".equals(seed.gated.getFunctionName())) {
            st.addSeededInvokeServiceRow(seed.gated, seed.approvedInvokeJson);
        } else if (seed.hitlTerminalCode != null) {
            st.addSeededHitlDecisionRow(seed.gated, seed.hitlTerminalCode);
        }
    }

    /** v1b: after v1a seed, record approved {@code set_property_value} outcome on the progress panel. */
    private void applyHitlV1bSetPropertyAfterSeed(HitlEvidenceSeed seed) {
        if (seed == null || seed.gated == null || seed.approvedSetPropertyJson == null) {
            return;
        }
        if (!"set_property_value".equals(seed.gated.getFunctionName())) {
            return;
        }
        // Continuation primes a fresh ledger; correlate this approved write with a checklist/ad-hoc row.
        TaskProgressV1bHooks.onBeforeTool(seed.gated);
        TaskProgressV1bHooks.afterTrackedTool(seed.gated, seed.approvedSetPropertyJson);
    }

    /** v1b: replay approved {@code invoke_service} into the fresh checklist (HITL continuation). */
    private void applyHitlV1bInvokeServiceAfterSeed(HitlEvidenceSeed seed) {
        if (seed == null || seed.gated == null || seed.approvedInvokeJson == null) {
            return;
        }
        TaskProgressV1bHitlReplay.replayApprovedInvokeService(seed.gated, seed.approvedInvokeJson);
    }

    /** v1b: replay cancel/reject terminal outcome into the fresh checklist (HITL continuation). */
    private void applyHitlV1bDecisionAfterSeed(HitlEvidenceSeed seed) {
        if (seed == null || seed.gated == null || seed.hitlTerminalCode == null) {
            return;
        }
        TaskProgressV1bHitlReplay.replayTerminalDecision(seed.gated);
    }

    private void runParlerPostToolAgentLoop(
            PendingApprovalRecord rec,
            List<ChatMessage> messages,
            Thing remoteConversation,
            AtomicBoolean downlinkOk,
            HitlEvidenceSeed hitlSeedOrNull) {
        final String thingName = getName();
        final String wireRid = rec.getRequestId();
        final String wireCid = rec.getConversationId();
        final String streamKey = streamConversationKey(wireCid);
        final String streamSource = streamKey;

        BiConsumer<ChatMessage, StreamTokenUsage> streamSink = (m, tu) -> {
            if (m.getRole() == ChatMessage.Role.TOOL) {
                ChatMessage mPersist = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(m, remoteConversation, wireRid, wireCid);
                AgentMessageStreamAppender.append(streamKey, mPersist, streamSource, thingName, tu);
                if (!downlinkOk.get()) {
                    return;
                }
                if (remoteConversation == null) {
                    return;
                }
                ChatMessage mUi = FetchCachedStreamLaneHelper.resolveToolMessageForParlerTableDownlinks(
                        m, mPersist, remoteConversation, wireRid, wireCid);
                ParlerToolArtifactWireEmitter.emitAfterResolvedToolUi(remoteConversation, wireRid, wireCid, mUi,
                        downlinkOk, m);
                return;
            }
            AgentMessageStreamAppender.append(streamKey, m, streamSource, thingName, tu);
            if (!downlinkOk.get() || remoteConversation == null) {
                return;
            }
            if (m.getRole() == ChatMessage.Role.ASSISTANT && m.hasToolCalls()) {
                for (ToolCall tc : m.getToolCalls()) {
                    if (!downlinkOk.get()) {
                        return;
                    }
                    String line = "Tool call: " + tc.getFunctionName()
                            + " (" + trunc(tc.getId(), 64) + ")";
                    if (!ParlerReceiveMessageSupport.send(remoteConversation,
                            ParlerReceiveMessageSupport.wireActivity(wireRid, wireCid, line))) {
                        downlinkOk.set(false);
                    }
                }
            }
        };

        ToolExecutor combinedExecutor = AgentThing.this::executeToolCall;
        List<ToolDefinition> tools = getMergedToolDefinitions();
        AgentLoop.AgentResult result;
        boolean loopOk = false;
        // Declared outside the try so post-turn normalization can pass the very client this turn used (§8.1.1);
        // resolving a second one would break the provider/model/rate-control/timeout snapshot the turn ran under.
        LlmClient turnLlm = null;
        try {
            // HITL continuation deliberately omits setParlerEphemeralSystemIndices: pending snapshot indices are not
            // valid on reconstructed message lists; ContextBudgetPlanner uses null-bundle ephemeral fallback.
            turnLlm = llmClientForTurn();
            AgentLoop loop = newAgentLoopForTurn(turnLlm, combinedExecutor,
                    rateControlSinkForRemote(remoteConversation, wireCid, wireRid), rec.getUserIanaTimezone());
            ParlerHitlContinuationContext.bindForGatedToolExecution(AgentThing.this, rec, remoteConversation,
                    downlinkOk, messages);
            List<String> slashSnap = rec.getSlashSkillShortNamesSnapshot();
            primeAgentTaskStateFromHistory(wireRid, wireCid, messages, slashSnap,
                    rec.getDynamicSkillShortNamesSnapshot());
            applyHitlTaskStateSeed(hitlSeedOrNull);
            if (hitlSeedOrNull != null) {
                if (hitlSeedOrNull.approvedSetPropertyJson != null) {
                    applyHitlV1bSetPropertyAfterSeed(hitlSeedOrNull);
                } else if (hitlSeedOrNull.approvedInvokeJson != null && hitlSeedOrNull.hitlExtendedTargetThing != null) {
                    TaskProgressV1bHitlReplay.replayApprovedExtendedTool(hitlSeedOrNull.gated,
                            hitlSeedOrNull.approvedInvokeJson);
                } else if (hitlSeedOrNull.approvedInvokeJson != null && hitlSeedOrNull.gated != null
                        && "invoke_service".equals(hitlSeedOrNull.gated.getFunctionName())) {
                    applyHitlV1bInvokeServiceAfterSeed(hitlSeedOrNull);
                } else if (hitlSeedOrNull.hitlTerminalCode != null) {
                    applyHitlV1bDecisionAfterSeed(hitlSeedOrNull);
                }
            }
            AgentToolContext.resetTabularChartRound();
            String completedGatedResult = hitlSeedOrNull == null ? null
                    : hitlSeedOrNull.approvedInvokeJson != null ? hitlSeedOrNull.approvedInvokeJson
                    : hitlSeedOrNull.approvedSetPropertyJson;
            result = loop.runAfterApproval(messages, tools, streamSink, rec, completedGatedResult);
            loopOk = true;
            emitParlerTaskStateTurnEnd(result);
        } catch (Exception e) {
            TaskProgressWireEmitter.emitFailedTurnEnd();
            _logger.warn("[{}] post-approval agent loop failed: {}", thingName, e.getMessage());
            if (ArtifactCacheTurnFaults.isRepositoryUnavailable(e)) {
                _logger.error("[{}] Artifact Cache repository unavailable during post-approval agent loop",
                        thingName, e);
                AgentLoop.AgentResult cacheFailure = AgentLoop.AgentResult.error(
                        ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE,
                        ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_MESSAGE,
                        0, 0, 0, null);
                if (downlinkOk.get() && remoteConversation != null
                        && !sendArtifactCacheTerminal(remoteConversation, wireRid, wireCid, cacheFailure)) {
                    downlinkOk.set(false);
                }
                synchronized (lockForConversation(wireCid)) {
                    _conversations.put(wireCid, messages);
                }
                fireAgentResponseEvent(wireCid, cacheFailure);
                return;
            }
            if (downlinkOk.get() && remoteConversation != null) {
                ParlerReceiveMessageSupport.send(remoteConversation,
                        ParlerReceiveMessageSupport.wireError(wireRid, wireCid, e.getMessage(), "exception"));
                ParlerReceiveMessageSupport.send(remoteConversation,
                        ParlerReceiveMessageSupport.wireDone(wireRid, wireCid));
            }
            synchronized (lockForConversation(wireCid)) {
                _conversations.put(wireCid, messages);
            }
            return;
        } finally {
            ChartGroupTurnHooks.captureBeforeContextClear();
            AgentToolContext.clear();
        }

        if (!loopOk) {
            return;
        }

        if (result.getStatus() == AgentLoop.AgentResult.Status.AWAITING_APPROVAL) {
            String nestedPending = result.getApprovalPendingId();
            synchronized (ParlerConversationLocks.lockFor(wireCid)) {
                PendingApprovalRecord nestedRec =
                        nestedPending != null ? PendingApprovalStore.get(nestedPending) : null;
                boolean tomb = ParlerGatewayUserStopTombstoneRegistry.isGatewayUserStopTerminalActive(wireCid, wireRid,
                        rec.getPrincipal(), thingName);
                boolean suppressApprovalRequired =
                        tomb || nestedPending == null || nestedRec == null;
                if (suppressApprovalRequired) {
                    _logger.info(
                            "[{}] runParlerPostToolAgentLoop: suppress approval.required "
                                    + "(pendingId={} tombstoneActive={} storeHit={}) requestId={} conversationId={}",
                            thingName, nestedPending, tomb, nestedRec != null, wireRid, wireCid);
                } else {
                    if (downlinkOk.get() && remoteConversation != null) {
                        JSONObject summary = buildHitlApprovalSummary(nestedRec);
                        String nestedToolName = nestedRec.getGatedToolCall() != null
                                ? nestedRec.getGatedToolCall().getFunctionName() : "unknown";
                        String expiresInner = ISODateTimeFormat.dateTime().withZone(DateTimeZone.UTC)
                                .print(DateTime.now(DateTimeZone.UTC).plusMinutes(15));
                        JSONArray actionsInner = new JSONArray();
                        actionsInner.put("approve");
                        actionsInner.put("cancel");
                        actionsInner.put("reject_with_comment");
                        if (!ParlerReceiveMessageSupport.send(remoteConversation,
                                ParlerReceiveMessageSupport.wireApprovalRequired(wireRid, wireCid, nestedPending,
                                        expiresInner, nestedToolName, summary, actionsInner))) {
                            downlinkOk.set(false);
                        }
                    }
                    _conversations.put(wireCid, messages);
                    fireAgentResponseEvent(wireCid, result);
                }
            }
            return;
        }

        if (isArtifactCacheTerminal(result)) {
            if (downlinkOk.get() && remoteConversation != null
                    && !sendArtifactCacheTerminal(remoteConversation, wireRid, wireCid, result)) {
                downlinkOk.set(false);
            }
            ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(messages, result,
                    isLlmReplayCompactionEffective(), _logger, wireCid, wireRid, _llmContextMaxChars,
                    turnLlm, null, thingName,
                    org.joda.time.DateTime.now(org.joda.time.DateTimeZone.UTC).toString());
            synchronized (lockForConversation(wireCid)) {
                _conversations.put(wireCid, messages);
            }
            fireAgentResponseEvent(wireCid, result);
            return;
        }

        ChartGroupTurnHooks.onTurnEnd(result);
ChatMessage finalAssistant = ChatMessage.assistant(result.getContent());
        messages.add(finalAssistant);
        StreamTokenUsage finalTok = mergeAgentLoopUsageWithTurnPerf(result);
        String assistantMessageId = UUID.randomUUID().toString();
        AgentMessageStreamAppender.append(streamKey, finalAssistant, streamSource, thingName, finalTok,
                assistantMessageId);

        if (downlinkOk.get() && remoteConversation != null) {
            if (result.getStatus() == AgentLoop.AgentResult.Status.SUCCESS) {
                String content = result.getContent() != null ? result.getContent() : "";
                if (!ParlerReceiveMessageSupport.send(remoteConversation,
                        ParlerReceiveMessageSupport.wireContentDelta(wireRid, wireCid, content))) {
                    downlinkOk.set(false);
                }
            } else {
                String err = result.getContent() != null ? result.getContent() : result.getStatus().name();
                if (!ParlerReceiveMessageSupport.send(remoteConversation,
                        ParlerReceiveMessageSupport.wireError(wireRid, wireCid, err, result.getStatus().name()))) {
                    downlinkOk.set(false);
                }
            }
            if (downlinkOk.get()) {
                ParlerReceiveMessageSupport.send(remoteConversation,
                        ParlerReceiveMessageSupport.wireDone(wireRid, wireCid, assistantMessageId,
                                finalTok.getLlmUsageJson()));
            }
        }
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(messages, result,
                isLlmReplayCompactionEffective(), _logger, wireCid, wireRid, _llmContextMaxChars,
                turnLlm, assistantMessageId, thingName,
                org.joda.time.DateTime.now(org.joda.time.DateTimeZone.UTC).toString());
        synchronized (lockForConversation(wireCid)) {
            _conversations.put(wireCid, messages);
        }
        fireAgentResponseEvent(wireCid, result);
    }

    private void runParlerApprovalContinuation(PendingApprovalRecord rec, String decision,
            SecurityContext submitterCtx, String approvalComment) {
        final String thingName = getName();
        final String wireRid = rec.getRequestId();
        final String wireCid = rec.getConversationId();
        final AtomicBoolean downlinkOk = new AtomicBoolean(true);
        try {
            if (submitterCtx != null) {
                ThreadLocalContext.setSecurityContext(submitterCtx);
            }
            Thing remoteConversation = resolveConnectedRemoteThing(rec.getRemoteThingName());
            if (remoteConversation == null) {
                _logger.warn("[{}] approval continuation: RemoteThing \"{}\" missing or not connected",
                        thingName, rec.getRemoteThingName());
                downlinkOk.set(false);
            }

            Decision cacheAdmission = artifactCacheAdmission(
                    "ParlerApprovalContinuation", wireRid, wireCid);
            if (!cacheAdmission.isReady()) {
                AgentLoop.AgentResult rejection = artifactCacheAdmissionResult(cacheAdmission);
                if (downlinkOk.get() && remoteConversation != null
                        && !sendArtifactCacheTerminal(remoteConversation, wireRid, wireCid, rejection)) {
                    downlinkOk.set(false);
                }
                fireAgentResponseEvent(wireCid, rejection);
                return;
            }

            if ("cancel".equalsIgnoreCase(decision)) {
                ToolCall gatedCancel = rec.getGatedToolCall();
                if (gatedCancel == null) {
                    _logger.warn("[{}] cancel continuation: missing gated tool call pendingId={}",
                            thingName, rec.getPendingId());
                    try {
                        if (downlinkOk.get() && remoteConversation != null) {
                            ParlerReceiveMessageSupport.send(remoteConversation,
                                    ParlerReceiveMessageSupport.wireApprovalResolved(wireRid, wireCid, rec.getPendingId(),
                                            "cancelled", false, null, null, null));
                            ParlerReceiveMessageSupport.send(remoteConversation,
                                    ParlerReceiveMessageSupport.wireDone(wireRid, wireCid));
                        }
                    } finally {
                        String tk = FetchCachedReplayGuard.turnKey(wireCid, wireRid);
                        ConsecutiveIdenticalToolCallRegistry.removeTurn(tk);
                        DocumentSearchProgressGuardRegistry.removeTurn(tk);
                        PresentationArtifactRegistry.removeTurn(tk);
                        LazyToolRegistrationRegistry.removeTurn(tk);
                    }
                    return;
                }
                if (downlinkOk.get() && remoteConversation != null) {
                    if (!ParlerReceiveMessageSupport.send(remoteConversation,
                            ParlerReceiveMessageSupport.wireApprovalResolved(wireRid, wireCid, rec.getPendingId(),
                                    "cancelled", false, null, null, null))) {
                        downlinkOk.set(false);
                    }
                }
                List<ChatMessage> msgs = new ArrayList<>(rec.getMessagesCopy());
                String sk = streamConversationKey(wireCid);
                HitlSyntheticToolResultAppender.appendDurable(msgs, gatedCancel.getId(),
                        "{\"status\":\"cancelled\",\"message\":\"User cancelled the pending operation.\"}", sk, sk,
                        getName(), gatedCancel.getFunctionName());
                appendHitlInterruptedBatchSiblingToolResultsDurable(rec, msgs, wireCid);
                ParlerHitlAuditLog.continuationOutcome(rec, "cancel", "cancelled", false, "", thingName);
                runParlerPostToolAgentLoop(rec, msgs, remoteConversation, downlinkOk,
                        HitlEvidenceSeed.terminal(gatedCancel, TaskStateErrorCode.HITL_CANCELLED));
                return;
            }

            if ("reject_with_comment".equalsIgnoreCase(decision)) {
                ToolCall gatedReject = rec.getGatedToolCall();
                if (gatedReject == null) {
                    _logger.warn("[{}] reject_with_comment continuation: missing gated tool call pendingId={}",
                            thingName, rec.getPendingId());
                    try {
                        if (downlinkOk.get() && remoteConversation != null) {
                            ParlerReceiveMessageSupport.send(remoteConversation,
                                    ParlerReceiveMessageSupport.wireApprovalResolved(wireRid, wireCid, rec.getPendingId(),
                                            "rejected", false, null, null, null));
                            ParlerReceiveMessageSupport.send(remoteConversation,
                                    ParlerReceiveMessageSupport.wireDone(wireRid, wireCid));
                        }
                    } finally {
                        String tk = FetchCachedReplayGuard.turnKey(wireCid, wireRid);
                        ConsecutiveIdenticalToolCallRegistry.removeTurn(tk);
                        DocumentSearchProgressGuardRegistry.removeTurn(tk);
                        PresentationArtifactRegistry.removeTurn(tk);
                        LazyToolRegistrationRegistry.removeTurn(tk);
                    }
                    return;
                }
                String cmt = approvalComment != null ? approvalComment.trim() : "";
                if (downlinkOk.get() && remoteConversation != null) {
                    if (!ParlerReceiveMessageSupport.send(remoteConversation,
                            ParlerReceiveMessageSupport.wireApprovalResolved(wireRid, wireCid, rec.getPendingId(),
                                    "rejected", false, null, null, null))) {
                        downlinkOk.set(false);
                    }
                }
                List<ChatMessage> msgs = new ArrayList<>(rec.getMessagesCopy());
                String sk = streamConversationKey(wireCid);
                HitlSyntheticToolResultAppender.appendDurable(msgs, gatedReject.getId(), buildRejectedToolResultJson(cmt),
                        sk, sk, getName(), gatedReject.getFunctionName());
                appendHitlInterruptedBatchSiblingToolResultsDurable(rec, msgs, wireCid);
                ParlerHitlAuditLog.continuationOutcome(rec, "reject_with_comment", "rejected", false, "", thingName);
                runParlerPostToolAgentLoop(rec, msgs, remoteConversation, downlinkOk,
                        HitlEvidenceSeed.terminal(gatedReject, TaskStateErrorCode.HITL_REJECTED));
                return;
            }

            ToolCall gated = rec.getGatedToolCall();
            String gatedName = gated != null ? gated.getFunctionName() : "";
            List<ChatMessage> continuationMessages = new ArrayList<>(rec.getMessagesCopy());
            ParlerHitlContinuationContext.bindForGatedToolExecution(this, rec, remoteConversation, downlinkOk,
                    continuationMessages);
            String toolResult;
            ParsedWriteOutcome po;
            if ("set_property_value".equals(gatedName)) {
                String preSnap = rec.getPreWriteValueSnapshotJson();
                if (preSnap != null) {
                    String nowSnap = SetPropertyValueExecutor.snapshotPropertyValueForStaleCheck(gated);
                    if (nowSnap != null && !preSnap.equals(nowSnap)) {
                        toolResult = SetPropertyValueExecutor.staleCompareRejectedJson();
                        po = parseSetPropertyToolResult(toolResult);
                        _logger.info("[{}] set_property_value stale compare: pendingId={} conversationId={}",
                                thingName, rec.getPendingId(), wireCid);
                    } else {
                        toolResult = SetPropertyValueExecutor.executeApprovedWrite(gated);
                        po = parseSetPropertyToolResult(toolResult);
                    }
                } else {
                    toolResult = SetPropertyValueExecutor.executeApprovedWrite(gated);
                    po = parseSetPropertyToolResult(toolResult);
                }
            } else if (rec.getExtendedToolTargetEntityName() != null && rec.getExtendedToolTargetServiceName() != null) {
                try {
                    // Fail closed on registry miss: raw target/service execute would skip
                    // dry-run enforce and executionBlockReason.
                    ExtendedToolRegistrySnapshot hitlExt = _promptContextSnapshot != null
                            ? _promptContextSnapshot.getExtendedToolRegistry()
                            : ExtendedToolRegistrySnapshot.missing();
                    Optional<String> miss = ServiceCapabilityRuntimePolicy.hitlApproveRegistryMissReason(
                            hitlExt, gatedName);
                    if (miss.isPresent()) {
                        toolResult = capabilityPolicyBlockedResult(gatedName, miss.get());
                    } else {
                        toolResult = executeExtendedTool(gated, hitlExt.find(gatedName).orElseThrow());
                    }
                } catch (Exception e) {
                    _logger.warn("[{}] extended tool approve execution failed: {}", thingName, e.getMessage(), e);
                    toolResult = "{\"status\":\"error\",\"message\":\"" + escapeJsonString(e.getMessage()) + "\"}";
                }
                po = parseSetPropertyToolResult(toolResult);
            } else if ("invoke_service".equals(gatedName)) {
                ServiceTargetEntityTypeResolution fromPending = rec.getInvokeServiceTypeResolution();
                boolean bindNorm = fromPending != null && fromPending.isNormalized();
                if (bindNorm) {
                    InvokeServiceEntityTypeNormalization.bind(gated.getId(), fromPending);
                }
                InvokeServiceParameterNormalizer.Repair repairFromPending = rec.getInvokeServiceParameterRepair();
                boolean bindParamRepair = repairFromPending != null && repairFromPending.isRepaired();
                if (bindParamRepair) {
                    InvokeServiceParameterRepairBinding.bind(gated.getId(), repairFromPending);
                }
                try {
                    toolResult = InvokeServiceExecutor.executeInvokeService(gated);
                } finally {
                    if (bindNorm) {
                        InvokeServiceEntityTypeNormalization.unbind();
                    }
                    if (bindParamRepair) {
                        InvokeServiceParameterRepairBinding.unbind();
                    }
                }
                po = parseSetPropertyToolResult(toolResult);
            } else {
                _logger.warn("[{}] approve continuation: unsupported gated tool {} pendingId={}",
                        thingName, gatedName, rec.getPendingId());
                toolResult = "{\"status\":\"error\",\"code\":\"UNSUPPORTED_GATE\",\"message\":\"Unsupported gated tool after approval.\"}";
                po = parseSetPropertyToolResult(toolResult);
            }
            if (downlinkOk.get() && remoteConversation != null) {
                if (!ParlerReceiveMessageSupport.send(remoteConversation,
                        ParlerReceiveMessageSupport.wireApprovalResolved(wireRid, wireCid, rec.getPendingId(),
                                "approved", po.executed, po.errorCode, po.errorMessage, null))) {
                    downlinkOk.set(false);
                }
            }
            ParlerHitlAuditLog.continuationOutcome(rec, "approve", "approved", po.executed,
                    po.errorCode != null ? po.errorCode : "", thingName);

            // A qualifying JSON table gets its chart source handle here, before the result is persisted or
            // replayed, so the durable tool row, the in-memory history, and the next model request all carry
            // the same cacheId. Charting an approved table after a later query took over last_invoke needs a
            // handle the model actually received.
            toolResult = TabularChartRoundHooks.augmentJsonSourceHandle(gatedName, toolResult);
            List<ChatMessage> messages = new ArrayList<>(rec.getMessagesCopy());
            String sk = streamConversationKey(wireCid);
            HitlSyntheticToolResultAppender.appendDurable(messages, rec.getGatedToolCall().getId(), toolResult, sk, sk,
                    getName(), gatedName);
            appendHitlInterruptedBatchSiblingToolResultsDurable(rec, messages, wireCid);
            HitlEvidenceSeed seed = null;
            if (rec.getGatedToolCall() != null) {
                if ("invoke_service".equals(gatedName)) {
                    seed = HitlEvidenceSeed.approveInvoke(rec.getGatedToolCall(), toolResult);
                } else if ("set_property_value".equals(gatedName)) {
                    seed = HitlEvidenceSeed.approveSetProperty(rec.getGatedToolCall(), toolResult);
                } else if (rec.getExtendedToolTargetEntityName() != null && rec.getExtendedToolTargetServiceName() != null) {
                    seed = HitlEvidenceSeed.approveExtended(rec.getGatedToolCall(), toolResult,
                            rec.getExtendedToolTargetEntityName(), rec.getExtendedToolTargetServiceName());
                }
            }
            runParlerPostToolAgentLoop(rec, messages, remoteConversation, downlinkOk, seed);
        } finally {
            ThreadLocalContext.clearSecurityContext();
        }
    }

    /**
     * Downlink {@code approval.resolved} (expired) + {@code done} and persist a synthetic tool row (TTL sweep or late submit).
     */
    public static void deliverParlerApprovalExpired(PendingApprovalRecord rec) {
        if (rec == null) {
            return;
        }
        ParlerHitlAuditLog.pendingExpired(rec);
        boolean adoptedCreatorContext = false;
        try {
            if (ThreadLocalContext.getSecurityContext() == null && rec.getSecurityContextAtCreate() != null) {
                ThreadLocalContext.setSecurityContext(rec.getSecurityContextAtCreate());
                adoptedCreatorContext = true;
            }
            Thing remoteConversation = resolveConnectedRemoteThing(rec.getRemoteThingName());
            if (remoteConversation != null) {
                ParlerReceiveMessageSupport.send(remoteConversation,
                        ParlerReceiveMessageSupport.wireApprovalResolved(rec.getRequestId(), rec.getConversationId(),
                                rec.getPendingId(), "expired", false, "PENDING_EXPIRED",
                                "This approval request expired.", null));
                ParlerReceiveMessageSupport.send(remoteConversation,
                        ParlerReceiveMessageSupport.wireDone(rec.getRequestId(), rec.getConversationId()));
            }
            RootEntity ent = PlatformAccess.findAsUser(rec.getAgentThingName(),
                    RelationshipTypes.ThingworxRelationshipTypes.Thing);
            if (ent instanceof AgentThing) {
                ((AgentThing) ent).applyParlerApprovalExpiredToConversation(rec);
            }
        } catch (Exception e) {
            AgentBaseThing._logger.warn("deliverParlerApprovalExpired: {}", e.getMessage());
        } finally {
            if (adoptedCreatorContext) {
                ThreadLocalContext.clearSecurityContext();
            }
            String expiredTurnKey = FetchCachedReplayGuard.turnKey(rec.getConversationId(), rec.getRequestId());
            ConsecutiveIdenticalToolCallRegistry.removeTurn(expiredTurnKey);
            DocumentSearchProgressGuardRegistry.removeTurn(expiredTurnKey);
            PresentationArtifactRegistry.removeTurn(expiredTurnKey);
        }
    }

    /**
     * Appends the synthetic expired tool result only when the live conversation still matches the pending snapshot
     * (same length and message-wise equal). If the user reconnected and advanced the thread, or the list diverged,
     * skips {@code _conversations} write so TTL does not roll back newer history.
     */
    public void applyParlerApprovalExpiredToConversation(PendingApprovalRecord rec) {
        String wireCid = rec.getConversationId();
        String gatedId = rec.getGatedToolCall().getId();
        String expiredJson = "{\"status\":\"expired\",\"message\":\"Approval request timed out.\"}";
        List<ChatMessage> snapshot = rec.getMessagesCopy();
        synchronized (parlerConversationLock(wireCid)) {
            List<ChatMessage> current = _conversations.get(wireCid);
            if (current == null || current.isEmpty()) {
                DateTime clearedAt = AgentThreadDataTableSupport.loadHistoryClearedAtOrNull(wireCid);
                if (ConversationClearPendingGuard.suppressExpiredPendingSnapshotRestore(clearedAt,
                        rec.getCreatedAtEpochMillis())) {
                    _logger.info("[{}] Parler approval expired: skip empty-memory restore (conversation cleared after "
                            + "pending) cid={} pendingCreatedAt={} historyClearedAt={}",
                            getName(), wireCid, rec.getCreatedAtEpochMillis(), clearedAt);
                    return;
                }
                List<ChatMessage> msgs = new ArrayList<>(snapshot);
                String sk = streamConversationKey(wireCid);
                HitlSyntheticToolResultAppender.appendDurable(msgs, gatedId, expiredJson, sk, sk, getName(),
                        rec.getGatedToolCall() != null ? rec.getGatedToolCall().getFunctionName() : null);
                appendHitlInterruptedBatchSiblingToolResultsDurable(rec, msgs, wireCid);
                _conversations.put(wireCid, msgs);
                return;
            }
            if (conversationHasToolResultForCallId(current, gatedId)) {
                return;
            }
            if (current.size() > snapshot.size()) {
                _logger.info("[{}] Parler approval expired: skip conversation rewrite (session advanced) cid={} "
                        + "currentSize={} snapshotSize={}",
                        getName(), wireCid, current.size(), snapshot.size());
                return;
            }
            if (current.size() < snapshot.size()) {
                _logger.warn("[{}] Parler approval expired: skip conversation rewrite (current shorter than snapshot) "
                        + "cid={}",
                        getName(), wireCid);
                return;
            }
            if (!conversationMatchesSnapshot(current, snapshot)) {
                _logger.info("[{}] Parler approval expired: skip conversation rewrite (diverged) cid={}",
                        getName(), wireCid);
                return;
            }
            List<ChatMessage> msgs = new ArrayList<>(current);
            String sk = streamConversationKey(wireCid);
            HitlSyntheticToolResultAppender.appendDurable(msgs, gatedId, expiredJson, sk, sk, getName(),
                    rec.getGatedToolCall() != null ? rec.getGatedToolCall().getFunctionName() : null);
            appendHitlInterruptedBatchSiblingToolResultsDurable(rec, msgs, wireCid);
            _conversations.put(wireCid, msgs);
        }
    }

    private static boolean conversationHasToolResultForCallId(List<ChatMessage> msgs, String toolCallId) {
        if (toolCallId == null || toolCallId.isEmpty() || msgs == null) {
            return false;
        }
        for (ChatMessage m : msgs) {
            if (m.getRole() == ChatMessage.Role.TOOL && toolCallId.equals(m.getToolCallId())) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code current} has the same length as {@code snapshot} and each message matches pairwise. */
    private static boolean conversationMatchesSnapshot(List<ChatMessage> current, List<ChatMessage> snapshot) {
        if (current == null || snapshot == null || current.size() != snapshot.size()) {
            return false;
        }
        for (int i = 0; i < snapshot.size(); i++) {
            if (!chatMessagesStructurallyEqual(current.get(i), snapshot.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean chatMessagesStructurallyEqual(ChatMessage a, ChatMessage b) {
        if (a.getRole() != b.getRole()) {
            return false;
        }
        if (!Objects.equals(a.getContent(), b.getContent())) {
            return false;
        }
        if (!Objects.equals(a.getToolCallId(), b.getToolCallId())) {
            return false;
        }
        if (!Objects.equals(a.getExecutedToolName(), b.getExecutedToolName())) {
            return false;
        }
        List<ToolCall> ta = a.getToolCalls();
        List<ToolCall> tb = b.getToolCalls();
        if (ta.size() != tb.size()) {
            return false;
        }
        for (int i = 0; i < ta.size(); i++) {
            ToolCall x = ta.get(i);
            ToolCall y = tb.get(i);
            if (!Objects.equals(x.getId(), y.getId())
                    || !Objects.equals(x.getFunctionName(), y.getFunctionName())
                    || !Objects.equals(x.getArguments(), y.getArguments())) {
                return false;
            }
        }
        return true;
    }

    private void appendHitlInterruptedBatchSiblingToolResultsDurable(PendingApprovalRecord rec, List<ChatMessage> msgs,
            String wireCid) {
        String sk = streamConversationKey(wireCid);
        HitlSyntheticToolResultAppender.appendInterruptedSiblingsDurable(rec, msgs, sk, sk, getName());
    }

    private static String buildRejectedToolResultJson(String comment) {
        String m = comment != null ? comment : "";
        return "{\"status\":\"rejected\",\"comment\":\"" + escapeJsonString(m) + "\"}";
    }

    private static Thing resolveConnectedRemoteThing(String remoteName) {
        if (remoteName == null || remoteName.trim().isEmpty()) {
            return null;
        }
        try {
            Object rent = PlatformAccess.findProgrammatic(remoteName.trim(),
                    RelationshipTypes.ThingworxRelationshipTypes.Thing);
            if (!(rent instanceof Thing)) {
                return null;
            }
            Thing t = (Thing) rent;
            Class<?> rtc = Class.forName("com.thingworx.things.connected.RemoteThing");
            if (!rtc.isInstance(t)) {
                return null;
            }
            Object connected = rtc.getMethod("isConnected").invoke(t);
            if (!(connected instanceof Boolean) || !(Boolean) connected) {
                return null;
            }
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class ParsedWriteOutcome {
        final boolean executed;
        final String errorCode;
        final String errorMessage;

        ParsedWriteOutcome(boolean executed, String errorCode, String errorMessage) {
            this.executed = executed;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }
    }

    private static ParsedWriteOutcome parseSetPropertyToolResult(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            if ("error".equalsIgnoreCase(n.path("status").asText())) {
                return new ParsedWriteOutcome(false,
                        n.path("code").asText("SET_PROPERTY_ERROR"),
                        n.path("message").asText("Write failed"));
            }
            return new ParsedWriteOutcome(true, null, null);
        } catch (Exception e) {
            return new ParsedWriteOutcome(false, "PARSE_ERROR", "Could not parse tool result");
        }
    }

    /**
     * Summary JSON for {@code approval.required}: {@code invoke_service}, a configuration-repository extended tool,
     * or {@code set_property_value}.
     */
    private static JSONObject buildHitlApprovalSummary(PendingApprovalRecord rec) {
        JSONObject summary = new JSONObject();
        JSONArray lines = new JSONArray();
        if (rec != null && rec.getGatedToolCall() != null) {
            String tool = rec.getGatedToolCall().getFunctionName();
            try {
                String safeArgs = ProtectedValuePolicy.redactToolArgumentsForApprovalPreview(
                        rec.getGatedToolCall().getArguments(), tool);
                JsonNode root = JSON.readTree(safeArgs == null ? "{}" : safeArgs);
                if ("invoke_service".equals(tool)) {
                    summary.put("title", "Confirm service invocation");
                    approvalSummaryLine(lines, "Tool", "invoke_service");
                    approvalSummaryLine(lines, "Entity type", textNode(root, "entityType"));
                    approvalSummaryLine(lines, "Entity name", textNode(root, "entityName"));
                    approvalSummaryLine(lines, "Service", textNode(root, "serviceName"));
                    JsonNode params = root.get("parameters");
                    String pStr = params == null || params.isNull() ? "" : trunc(params.toString(), 400);
                    approvalSummaryLine(lines, "Parameters (preview)", pStr);
                } else if (rec.getExtendedToolTargetEntityName() != null) {
                    summary.put("title", "Confirm service invocation");
                    approvalSummaryLine(lines, "Tool", tool);
                    approvalSummaryLine(lines, "Thing", rec.getExtendedToolTargetEntityName());
                    approvalSummaryLine(lines, "Service", rec.getExtendedToolTargetServiceName());
                    approvalSummaryLine(lines, "Parameters (preview)", trunc(
                            ProtectedValuePolicy.redactPersistedToolArgumentsJson(
                                    rec.getGatedToolCall().getArguments(), tool), 400));
                } else {
                    summary.put("title", "Confirm property write");
                    approvalSummaryLine(lines, "Tool", "set_property_value");
                    approvalSummaryLine(lines, "Thing",
                            firstNonEmpty(textNode(root, "thing_name"), textNode(root, "thingName")));
                    approvalSummaryLine(lines, "Property",
                            firstNonEmpty(textNode(root, "property_name"), textNode(root, "propertyName")));
                    approvalSummaryLine(lines, "Base type",
                            firstNonEmpty(textNode(root, "base_type"), textNode(root, "baseType")));
                    JsonNode val = root.get("value");
                    String valStr = val == null || val.isNull() ? "" : val.isTextual() ? val.asText() : val.toString();
                    approvalSummaryLine(lines, "New value", valStr);
                    if (rec.getPreWriteValueSnapshotJson() != null) {
                        String obs = rec.getPreWriteValueSnapshotJson();
                        if (obs.length() > 160) {
                            obs = obs.substring(0, 160) + "…";
                        }
                        approvalSummaryLine(lines, "Observed value (server, at request)", obs);
                    }
                }
            } catch (Exception e) {
                summary.put("title", "Confirm action");
                approvalSummaryLine(lines, "Tool", tool != null ? tool : "unknown");
            }
        } else {
            summary.put("title", "Confirm action");
            approvalSummaryLine(lines, "Tool", "unknown");
        }
        summary.put("lines", lines);
        return summary;
    }

    private static void approvalSummaryLine(JSONArray lines, String label, String value) {
        JSONObject o = new JSONObject();
        o.put("label", label);
        o.put("value", value != null ? value : "");
        lines.put(o);
    }

    private static String textNode(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return "";
        }
        return n.asText("");
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b != null ? b : "";
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

    // ── Clear Conversation ──────────────────────────────────────────────
    @ThingworxServiceDefinition(
        name = "ClearConversation",
        description = "Clear the conversation history for a given conversation ID")
    @ThingworxServiceResult(name = "result", baseType = "NOTHING")
    public void ClearConversation(
        @ThingworxServiceParameter(name = "conversationId", baseType = "STRING",
            description = "Conversation ID to clear") String conversationId
    ) throws Exception {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return;
        }
        String cid = conversationId.trim();
        final String prefix = "[" + getName() + "] ClearConversation";
        synchronized (parlerConversationLock(cid)) {
            if (PendingApprovalStore.hasPendingForConversationId(cid)) {
                throw new Exception(prefix + ": pending approvals exist for this conversation; "
                        + "approve, reject, or cancel them first.");
            }
            DateTime now = new DateTime();
            AgentThreadDataTableSupport.markHistoryCleared(cid, now, getName(), prefix);
            _conversations.remove(cid);
            ConversationCheckpointWorkingSet.clearConversation(getName(), cid);
            _hostContextTurnCarry.clearConversation(cid);
            com.thingworx.things.agent.tools.TabularCacheHandleMirror.clearForConversationId(cid);
            _logger.info("[{}] Cleared conversation (history boundary): {}", getName(), cid);
        }
    }

    private static final int FEEDBACK_STREAM_LOOKUP_MAX = 800;

    /**
     * Sets the durable history boundary to {@code cutoffAt} (assistant row completion / Stream timestamp semantics).
     * Monotonic: no-op when {@code historyClearedAt} is already at or after {@code cutoffAt}. Matches {@link #ClearConversation}
     * HITL guard and JVM/cache cleanup.
     */
    @ThingworxServiceDefinition(
        name = "SetConversationHistoryCutoff",
        description = "Advance AgentThreadDataTable.historyClearedAt to cutoffAt (monotonic). Clears JVM conversation "
            + "state and tabular cache mirrors for the thread. Blocked when pending HITL approvals exist.")
    @ThingworxServiceResult(name = "result", baseType = "NOTHING")
    public void SetConversationHistoryCutoff(
        @ThingworxServiceParameter(name = "conversationId", baseType = "STRING",
            description = "Conversation / Gateway id") String conversationId,
        @ThingworxServiceParameter(name = "cutoffAt", baseType = "DATETIME",
            description = "Inclusive-style cutoff instant; same boundary family as ClearConversation / history hydrate")
            DateTime cutoffAt
    ) throws Exception {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return;
        }
        if (cutoffAt == null) {
            throw new Exception("[" + getName() + "] SetConversationHistoryCutoff: cutoffAt is required.");
        }
        String cid = conversationId.trim();
        final String prefix = "[" + getName() + "] SetConversationHistoryCutoff";
        synchronized (parlerConversationLock(cid)) {
            if (PendingApprovalStore.hasPendingForConversationId(cid)) {
                throw new Exception("CUTOFF_BLOCKED_HITL_PENDING: pending approvals exist for this conversation; "
                        + "approve, reject, or cancel them first.");
            }
            ConversationMetadata meta = AgentThreadDataTableSupport.loadConversationMetadata(cid, prefix);
            DateTime existing = meta.getHistoryClearedAtOrNull();
            if (existing != null && !existing.isBefore(cutoffAt)) {
                _logger.info("[{}] SetConversationHistoryCutoff no-op (existing marker >= cutoff) conversationId={}",
                        getName(), cid);
                return;
            }
            AgentThreadDataTableSupport.markHistoryCleared(cid, cutoffAt, null, prefix);
            _conversations.remove(cid);
            ConversationCheckpointWorkingSet.clearConversation(getName(), cid);
            _hostContextTurnCarry.clearConversation(cid);
            com.thingworx.things.agent.tools.TabularCacheHandleMirror.clearForConversationId(cid);
            _logger.info("[{}] Conversation history cutoff applied: {}", getName(), cid);
        }
    }

    /**
     * Appends a {@code ui_feedback} row to {@link AgentMessageStreamAppender#STREAM_THING_NAME} after validating
     * {@code assistantMessageId} on a bounded recent Stream scan.
     */
    @ThingworxServiceDefinition(
        name = "RecordAssistantFeedback",
        description = "Append ui_feedback JSON for thumbs up/down; assistantMessageId must match a recent final assistant row.")
    @ThingworxServiceResult(name = "result", baseType = "NOTHING")
    public void RecordAssistantFeedback(
        @ThingworxServiceParameter(name = "conversationId", baseType = "STRING",
            description = "Conversation / Gateway id") String conversationId,
        @ThingworxServiceParameter(name = "assistantMessageId", baseType = "STRING",
            description = "Stable id from assistant Stream row") String assistantMessageId,
        @ThingworxServiceParameter(name = "rating", baseType = "STRING",
            description = "up or down") String rating,
        @ThingworxServiceParameter(name = "requestId", baseType = "STRING",
            description = "Turn request id for logs",
            aspects = {"isRequired:false"}) String requestId,
        @ThingworxServiceParameter(name = "previousRating", baseType = "STRING",
            description = "up, down, or empty",
            aspects = {"isRequired:false"}) String previousRating
    ) throws Exception {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            throw new Exception("[" + getName() + "] RecordAssistantFeedback: conversationId is required.");
        }
        final String prefix = "[" + getName() + "] RecordAssistantFeedback";
        String cid = conversationId.trim();
        AgentThreadDataTableSupport.loadConversationMetadata(cid, prefix);
        String r = rating != null ? rating.trim().toLowerCase(Locale.ROOT) : "";
        if (!"up".equals(r) && !"down".equals(r)) {
            throw new Exception(prefix + ": rating must be up or down.");
        }
        String aid = assistantMessageId != null ? assistantMessageId.trim() : "";
        if (aid.isEmpty()) {
            throw new Exception(prefix + ": assistantMessageId is required.");
        }
        String streamAgentThing = resolveFinalAssistantStreamAgentThing(cid, aid, prefix);
        String agentForFeedbackRow =
                streamAgentThing != null && !streamAgentThing.isEmpty() ? streamAgentThing : getName();

        JSONObject body = new JSONObject();
        body.put("type", "assistant_feedback");
        body.put("conversationId", cid);
        body.put("assistantMessageId", aid);
        body.put("rating", r);
        body.put("requestId", requestId != null ? requestId : "");
        if (previousRating == null || previousRating.trim().isEmpty()) {
            body.put("previousRating", JSONObject.NULL);
        } else {
            String p0 = previousRating.trim().toLowerCase(Locale.ROOT);
            if ("up".equals(p0) || "down".equals(p0)) {
                body.put("previousRating", p0);
            } else {
                body.put("previousRating", JSONObject.NULL);
            }
        }
        body.put("createdAt", ISODateTimeFormat.dateTime().withZone(DateTimeZone.UTC)
                .print(DateTime.now(DateTimeZone.UTC)));

        String streamKey = streamConversationKey(cid);
        if (!AgentMessageStreamAppender.appendUiFeedback(streamKey, streamKey, agentForFeedbackRow, body.toString())) {
            throw new Exception(prefix + ": FEEDBACK_PERSIST_FAILED: feedback was not appended to the message stream.");
        }
    }

    /**
     * @return trimmed {@code agentThing} from the newest matching assistant stream row for {@code assistantMessageId}
     */
    private String resolveFinalAssistantStreamAgentThing(String conversationId, String assistantMessageId, String prefix)
            throws Exception {
        String streamKey = streamConversationKey(conversationId);
        List<ValueCollection> rows = AgentMessageStreamReader.queryChronologicalRows(streamKey, FEEDBACK_STREAM_LOOKUP_MAX,
                null);
        List<AssistantStreamFinalAssistantLookup.AssistantStreamRow> snaps = new ArrayList<>(rows.size());
        for (ValueCollection r : rows) {
            snaps.add(new AssistantStreamFinalAssistantLookup.AssistantStreamRow(
                    AgentMessageStreamReader.stringField(r, "role"),
                    AgentMessageStreamReader.stringField(r, "assistantMessageId"),
                    AgentMessageStreamReader.stringField(r, "agentThing")));
        }
        String at = AssistantStreamFinalAssistantLookup.findAgentThingForAssistantMessageId(snaps, assistantMessageId);
        if (at == null) {
            throw new Exception(prefix + ": assistantMessageId not found for this conversation within bounded history.");
        }
        return at;
    }

    // ── Health Check ────────────────────────────────────────────────────
    @ThingworxServiceDefinition(
        name = "TestConnection",
        description = "Test the LLM connection")
    @ThingworxServiceResult(name = "result", baseType = "BOOLEAN")
    public Boolean TestConnection() {
        try {
            LlmClient c = resolveLlmClientForTurn();
            if (c == null) {
                _logger.warn("[{}] LLM client not initialized (empty or invalid llmApiProviderRef)", getName());
                return false;
            }
            return c.healthCheck();
        } catch (LlmProviderResolveException e) {
            _logger.warn("[{}] TestConnection: {} — {}", getName(), e.getCode().name(), e.getMessage());
            return false;
        }
    }

    @ThingworxServiceDefinition(
        name = "GetAvailableLlmApiProviders",
        description = "Runtime listing of enabled LLM API Provider Things (non-sensitive columns only; "
            + "see docs/agent/llm-api-provider.md §5.10).")
    @ThingworxServiceResult(name = "result", baseType = "INFOTABLE",
        description = "Rows: name, displayName, providerTemplateName, apiShapeId, modelName, contextWindowTokens, "
            + "enabled, lastHealthy, description")
    public InfoTable GetAvailableLlmApiProviders() throws Exception {
        return LlmApiProviderDirectory.listEnabledLlmApiProviders();
    }

    /**
     * Override to replace the default alert routing + skill markdown injected each LLM turn. Default matches
     * {@link com.thingworx.things.agent.tools.AlertPromptDefaults#DEFAULT_ALERT_PROMPT_MARKDOWN}. Return empty string to
     * skip injection (see {@code docs/operations/alert-solution.md} §10 item 5).
     */
    @ThingworxServiceDefinition(
            name = "GetAlertPrompt",
            description = "Alert system-prompt section (Markdown). No parameters. Override replaces default entirely.",
            isAllowOverride = true)
    @ThingworxServiceResult(name = "result", baseType = "STRING",
            description = "Routing guide + skill-style instructions for query_alert_summary / query_alert_history / acknowledge_alerts")
    public String GetAlertPrompt() {
        return AlertPromptDefaults.DEFAULT_ALERT_PROMPT_MARKDOWN;
    }

    /**
     * Rebuilds the stable prompt-context cache (taxonomy, GenericThing template names, alert prompt) and returns the
     * full assembled leading stable system prompt for inspection.
     */
    @ThingworxServiceDefinition(
            name = "RefreshPromptContextCache",
            description = "Rebuilds in-memory stable prompt-context snapshot; returns assembled leading system prompt body.")
    @ThingworxServiceResult(name = "result", baseType = "STRING",
            description = "Full assembled stable prompt per docs/agent/system-prompt-cache.md")
    public String RefreshPromptContextCache() throws Exception {
        synchronized (_promptCacheLock) {
            try {
                return commitPromptContextCacheRefreshLocked();
            } catch (Exception e) {
                _logger.error("[{}] Prompt context cache refresh failed: {}", getName(), e.getMessage(), e);
                throw e;
            }
        }
    }

    /**
     * Authoring / operator: compact JSON snapshot of runtime prompt-related state (not an LLM tool). See
     * {@code docs/agent/configuration-repository.md} § {@code GetAgentRuntimeSnapshot}.
     */
    @ThingworxServiceDefinition(
            name = "GetAgentRuntimeSnapshot",
            description = "Returns JSON snapshot of agent runtime and prompt state for operators and authoring tools.")
    @ThingworxServiceResult(name = "result", baseType = "STRING", description = "JSON per configuration-repository.md")
    public String GetAgentRuntimeSnapshot(
            @ThingworxServiceParameter(name = "options", baseType = "STRING",
                    description = "JSON string: includePrompt, includeSkills, includeTools, includePolicies,"
                            + " includeTaxonomy, includePlaybooks, includeRepositoryFiles, refresh (see configuration-repository.md)") String optionsJson)
            throws Exception {
        JsonNode opts = (optionsJson == null || optionsJson.isBlank()) ? JSON.readTree("{}") : JSON.readTree(optionsJson);
        boolean refresh = opts.path("refresh").asBoolean(false);
        if (refresh) {
            synchronized (_promptCacheLock) {
                commitPromptContextCacheRefreshLocked();
            }
        }
        return buildAgentRuntimeSnapshotJson(opts);
    }

    /**
     * Authoring / operator: read-only validation of a FileRepository configuration layout. See
     * {@code docs/agent/configuration-repository.md} § {@code ValidateAgentConfigurationRepository}.
     */
    @ThingworxServiceDefinition(
            name = "ValidateAgentConfigurationRepository",
            description = "Validates repository files using the same parsers as runtime; does not mutate cache state.")
    @ThingworxServiceResult(name = "result", baseType = "STRING", description = "JSON validation report")
    public String ValidateAgentConfigurationRepository(
            @ThingworxServiceParameter(name = "repositoryName", baseType = "STRING",
                    description = "Optional FileRepository Thing name; empty uses this agent's configurationRepository") String repositoryName) {
        return ConfigurationRepositoryAuthoringJson.validate(repositoryName == null ? "" : repositoryName, this,
                _logger);
    }

    /**
     * Authoring / operator: validate a single Playbook document without mutating repository or cache. See
     * {@code docs/agent/playbook-customer-readiness.md} §6.5.
     */
    @ThingworxServiceDefinition(
            name = "ValidatePlaybookDocument",
            description = "Validates one playbook.json using the same validator context as repository admission; "
                    + "returns structured JSON report.")
    @ThingworxServiceResult(name = "result", baseType = "STRING", description = "Structured validation report JSON")
    public String ValidatePlaybookDocument(
            @ThingworxServiceParameter(name = "playbookJson", baseType = "STRING",
                    description = "Raw playbook.json text; exactly one of playbookJson or packageDirId must be set") String playbookJson,
            @ThingworxServiceParameter(name = "packageDirId", baseType = "STRING",
                    description = "Playbook package id under /playbooks/<id>/playbook.json in configurationRepository") String packageDirId) {
        return PlaybookDocumentValidation.validateJson(playbookJson, packageDirId, this, _logger);
    }

    /**
     * Lab-only: create/write/close/publish/open a TEXT artifact via
     * {@code artifactCacheFileRepository}. Used by the User-run U1A FileRepository checklist —
     * not a product wire API.
     */
    @ThingworxServiceDefinition(
            name = "ParlerArtifactCacheLabTextRoundTrip",
            description = "Lab: TEXT artifact round-trip on artifactCacheFileRepository (see docs/agent/nearterm/cache-correctness-foundation.md).")
    @ThingworxServiceResult(name = "result", baseType = "STRING", description = "JSON ok/fault report")
    public String ParlerArtifactCacheLabTextRoundTrip(
            @ThingworxServiceParameter(name = "text", baseType = "STRING",
                    description = "UTF-8 text payload to cache") String text) {
        return com.thingworx.things.agent.cache.ArtifactCacheLab.textRoundTrip(this, _logger, text);
    }

    @ThingworxServiceDefinition(
            name = "RefreshTaxonomyCache",
            description = "Refresh structured taxonomy (`/taxonomies/identity-types.json` as v2 object or v3 array, and/or `/taxonomies/asset-types.json` v3 object map — each loads independently) into the prompt-context snapshot; returns diagnostics JSON.")
    @ThingworxServiceResult(name = "result", baseType = "STRING", description = "JSON per CONTRACTS/TAXONOMY_RESOLVER.md")
    public String RefreshTaxonomyCache() throws Exception {
        synchronized (_promptCacheLock) {
            if (_promptContextSnapshot == null) {
                commitPromptContextCacheRefreshLocked();
                PromptContextCacheSnapshot snap = _promptContextSnapshot;
                ApplicationSemanticTaxonomySnapshot sem =
                        snap != null ? snap.getApplicationSemanticTaxonomy() : null;
                if (sem == null) {
                    return TaxonomyResolverJson.unavailable();
                }
                return TaxonomyResolverJson.diagnosticsJson(sem, this, true);
            }
            ApplicationSemanticTaxonomySnapshot built = ApplicationSemanticTaxonomyBuilder.build(this, _logger);
            ApplicationSemanticTaxonomySnapshot prev = _promptContextSnapshot.getApplicationSemanticTaxonomy();
            ApplicationSemanticTaxonomySnapshot sem = built;
            if (!built.isLoaded()) {
                _logger.error("[{}] RefreshTaxonomyCache: taxonomy structured file refresh failed", getName());
                if (prev != null && prev.isLoaded()) {
                    sem = ApplicationSemanticTaxonomySnapshot.staleFromPrior(prev, Instant.now(), built.diagnostics());
                } else {
                    sem = built;
                }
            }
            _promptContextSnapshot = _promptContextSnapshot.withApplicationSemanticTaxonomy(sem);
            return TaxonomyResolverJson.diagnosticsJson(sem, this, true);
        }
    }

    @ThingworxServiceDefinition(
            name = "GetTaxonomyDiagnostics",
            description = "Returns current semantic taxonomy cache status and diagnostics JSON.")
    @ThingworxServiceResult(name = "result", baseType = "STRING", description = "JSON per CONTRACTS/TAXONOMY_RESOLVER.md")
    public String GetTaxonomyDiagnostics() {
        PromptContextCacheSnapshot snap = _promptContextSnapshot;
        ApplicationSemanticTaxonomySnapshot sem =
                snap != null ? snap.getApplicationSemanticTaxonomy() : null;
        if (sem == null) {
            return TaxonomyResolverJson.unavailable();
        }
        return TaxonomyResolverJson.diagnosticsJson(sem, this, false);
    }

    @ThingworxServiceDefinition(
            name = "RefreshSemanticProfileCache",
            description = "Refresh `/semantics/semantic-profile.json` into the prompt-context snapshot; "
                    + "invalid refresh retains the prior valid profile and returns diagnostics JSON.")
    @ThingworxServiceResult(name = "result", baseType = "STRING", description = "JSON semantic-profile diagnostics")
    public String RefreshSemanticProfileCache() throws Exception {
        synchronized (_promptCacheLock) {
            if (_promptContextSnapshot == null) {
                commitPromptContextCacheRefreshLocked();
                PromptContextCacheSnapshot snap = _promptContextSnapshot;
                SemanticProfileSnapshot profile = snap != null ? snap.getSemanticProfile() : null;
                if (profile == null) {
                    return SemanticProfileDiagnosticsJson.unavailable();
                }
                return SemanticProfileDiagnosticsJson.diagnosticsJson(profile, true);
            }
            ApplicationSemanticTaxonomySnapshot tax = _promptContextSnapshot.getApplicationSemanticTaxonomy();
            Set<String> knownKeys = knownAssetTypeKeys(tax);
            SemanticProfileSnapshot built = SemanticProfileBuilder.build(this, _logger, Instant.now(), knownKeys);
            SemanticProfileSnapshot prev = _promptContextSnapshot.getSemanticProfile();
            SemanticProfileSnapshot profile = built;
            if (!built.isLoaded()) {
                _logger.error("[{}] RefreshSemanticProfileCache: semantic-profile refresh failed status={}",
                        getName(), built.snapshotStatus());
                if (prev != null && prev.isLoaded()) {
                    profile = SemanticProfileSnapshot.staleFromPrior(prev, Instant.now(), built.diagnostics());
                }
            }
            _promptContextSnapshot = _promptContextSnapshot.withSemanticProfile(profile);
            return SemanticProfileDiagnosticsJson.diagnosticsJson(profile, true);
        }
    }

    @ThingworxServiceDefinition(
            name = "GetSemanticProfileDiagnostics",
            description = "Returns current application semantic-profile cache status and diagnostics JSON.")
    @ThingworxServiceResult(name = "result", baseType = "STRING", description = "JSON semantic-profile diagnostics")
    public String GetSemanticProfileDiagnostics() {
        PromptContextCacheSnapshot snap = _promptContextSnapshot;
        SemanticProfileSnapshot profile = snap != null ? snap.getSemanticProfile() : null;
        if (profile == null) {
            return SemanticProfileDiagnosticsJson.unavailable();
        }
        return SemanticProfileDiagnosticsJson.diagnosticsJson(profile, false);
    }

    @ThingworxServiceDefinition(name = "ListAssetTypes",
            description = "List application asset types (STRING JSON) from `/taxonomies/asset-types.json` when v3 taxonomy is loaded; v2 uses identity-types rows.")
    @ThingworxServiceResult(name = "result", baseType = "STRING")
    public String ListAssetTypes() {
        return TaxonomyResolverExecutor.executeListAssetTypes(new ToolCall("svc", "list_asset_types", "{}"), this);
    }

    @ThingworxServiceDefinition(name = "ResolveAssetType",
            description = "Resolve user text to an asset type row (STRING JSON); v3 asset-types map keys and aliases.")
    @ThingworxServiceResult(name = "result", baseType = "STRING")
    public String ResolveAssetType(@ThingworxServiceParameter(name = "text", baseType = "STRING") String text)
            throws Exception {
        ObjectNode args = JSON.createObjectNode();
        args.put("text", text != null ? text : "");
        return TaxonomyResolverExecutor.executeResolveAssetType(
                new ToolCall("svc", "resolve_asset_type", JSON.writeValueAsString(args)), this);
    }

    @ThingworxServiceDefinition(name = "ResolveThing",
            description = "Resolve user-facing text to a unique Thing (STRING JSON); requires v3 identity-types.json array rules.")
    @ThingworxServiceResult(name = "result", baseType = "STRING")
    public String ResolveThing(
            @ThingworxServiceParameter(name = "text", baseType = "STRING") String text,
            @ThingworxServiceParameter(name = "assetTypeKey", baseType = "STRING",
                    aspects = {"isRequired:false"}) String assetTypeKey) throws Exception {
        ObjectNode args = JSON.createObjectNode();
        args.put("text", text != null ? text : "");
        args.put("assetTypeKey", assetTypeKey != null ? assetTypeKey : "");
        return TaxonomyResolverExecutor.executeResolveThing(
                new ToolCall("svc", "resolve_thing", JSON.writeValueAsString(args)), this);
    }

    /**
     * Caller must hold {@link #_promptCacheLock}. Replaces {@link #_promptContextSnapshot} only after a full
     * successful build.
     *
     * @return assembled leading stable prompt (for service return and metrics)
     */
    private String commitPromptContextCacheRefreshLocked() throws Exception {
        PromptContextCacheRefreshSupport.CommitResult committed = PromptContextCacheRefreshSupport.commitRefresh(
                () -> _promptContextSnapshot,
                snap -> _promptContextSnapshot = snap,
                this::buildPromptContextSnapshotInternal,
                this::promptContextAssemblyContext,
                this::appendRefreshOperatorTail);
        resetPromptCacheTelemetryOnSuccessfulSnapshotCommit();
        logPromptContextRefreshMetrics(committed.snapshot(), committed.assembledStable());
        return committed.refreshResponseBody();
    }

    private PromptContextAssemblyContext promptContextAssemblyContext() {
        return new PromptContextAssemblyContext(
                _systemPrompt,
                _appendBuiltInToolRoutingGuide,
                _taxonomyPromptInjectionEffective,
                _playbookRegistrySnapshot);
    }

    private void appendRefreshOperatorTail(PromptContextCacheSnapshot snap, StringBuilder out) {
        out.append("\n\n---\n## Skill registry diagnostics\n\n").append(snap.getSkillRegistry().formatDiagnosticsMarkdown());
        if (snap.getApplicationSemanticTaxonomy() != null) {
            out.append("\n\n").append(snap.getApplicationSemanticTaxonomy().formatDiagnosticsMarkdown());
        }
        if (snap.getSemanticProfile() != null) {
            out.append("\n\n").append(snap.getSemanticProfile().formatDiagnosticsMarkdown());
        }
        if (snap.getConfigurationRepositoryState() != null) {
            out.append("\n\n").append(snap.getConfigurationRepositoryState().formatDiagnosticsMarkdown());
        }
    }

    private String buildAgentRuntimeSnapshotJson(JsonNode opts) throws Exception {
        boolean includePrompt = opts.path("includePrompt").asBoolean(false);
        boolean includeSkills = opts.path("includeSkills").asBoolean(true);
        boolean includeTools = opts.path("includeTools").asBoolean(true);
        boolean includePolicies = opts.path("includePolicies").asBoolean(true);
        boolean includeTaxonomy = opts.path("includeTaxonomy").asBoolean(false);
        ObjectNode root = JSON.createObjectNode();
        ObjectNode agentObj = root.putObject("agent");
        agentObj.put("name", getName());
        String extVer = ParlerPackageVersion.fromAnchorClass(AgentThing.class);
        if (extVer == null || extVer.isBlank()) {
            agentObj.putNull("extensionVersion");
        } else {
            agentObj.put("extensionVersion", extVer);
        }
        ObjectNode cfg = root.putObject("configurationRepository");
        String repo = getConfigurationRepositoryThingName();
        if (repo == null || repo.isBlank()) {
            cfg.put("thingName", "").put("status", "not_configured");
        } else {
            String rt = repo.trim();
            cfg.put("thingName", rt);
            cfg.put("status",
                    FileRepositoryThingResolver.resolve(rt, _logger, getName()).isPresent() ? "ok" : "unavailable");
        }
        PromptContextCacheSnapshot snap = _promptContextSnapshot;
        ObjectNode prompt = root.putObject("prompt");
        ArrayNode promptDiagnostics = prompt.putArray("diagnostics");
        PromptContextCacheRefreshSupport.PromptSnapshotInspection inspection =
                PromptContextCacheRefreshSupport.inspect(
                        snap, includePrompt, promptContextAssemblyContext());
        prompt.put("stablePromptSource", inspection.stablePromptSource());
        if (inspection.externalSystemPromptPath() != null) {
            prompt.put("externalSystemPromptPath", inspection.externalSystemPromptPath());
        } else {
            prompt.putNull("externalSystemPromptPath");
        }
        if (inspection.externalSystemPromptFallback() != null) {
            prompt.put("externalSystemPromptFallback", inspection.externalSystemPromptFallback());
        } else {
            prompt.putNull("externalSystemPromptFallback");
        }
        if (includePrompt) {
            prompt.put("stableSystemPrompt",
                    inspection.stableSystemPrompt() != null ? inspection.stableSystemPrompt() : "");
        } else {
            prompt.putNull("stableSystemPrompt");
        }
        for (String line : inspection.diagnosticLines()) {
            promptDiagnostics.add(line);
        }
        if (includeSkills && snap != null) {
            List<SkillRegistryDescriptor> skillList = snap.getSkillRegistry().descriptorsByShortId().values().stream()
                    .sorted(Comparator.comparing(SkillRegistryDescriptor::shortId))
                    .toList();
            String catalog = SkillRegistryCatalogFormatter.format(skillList);
            prompt.put("skillCatalog", catalog.isEmpty() ? "" : catalog);
            for (String line : snap.getSkillRegistry().diagnostics()) {
                if (line != null && !line.isBlank()) {
                    promptDiagnostics.add(line);
                }
            }
            ArrayNode skillsArr = root.putArray("skills");
            for (SkillRegistryDescriptor d : skillList) {
                ObjectNode so = skillsArr.addObject();
                so.put("id", d.shortId());
                so.put("source", "repository");
                if (d.sourceKind() != SkillSourceKind.REPOSITORY) {
                    _logger.error("[{}] GetAgentRuntimeSnapshot: unexpected skill sourceKind {} for id {}",
                            getName(), d.sourceKind(), d.shortId());
                }
                if (d.repositorySkillPath() != null && !d.repositorySkillPath().isEmpty()) {
                    so.put("path", d.repositorySkillPath());
                } else {
                    so.putNull("path");
                }
                so.put("title", d.title());
                so.put("whenToUse", d.whenToUse());
                so.put("status", "registered");
            }
        } else {
            prompt.putNull("skillCatalog");
            root.putNull("skills");
        }
        if (includeTools && snap != null) {
            ObjectNode tools = root.putObject("tools");
            tools.put("advertiseLegacyServiceDiscoveryTools", _advertiseLegacyServiceDiscoveryTools);
            tools.put("documentKnowledgeBuiltinsEnabled", _documentKnowledgeBuiltinsEnabled);
            tools.put("documentTurnToolNarrowingDisabled", _documentTurnToolNarrowingDisabled);
            ArrayNode builtIn = tools.putArray("builtIn");
            _toolRegistry.getAllDefinitions().stream().map(ToolDefinition::getName).sorted(Comparator.naturalOrder())
                    .forEach(builtIn::add);
            ObjectNode executorAliases = tools.putObject("executorAliases");
            for (Map.Entry<String, String> e : _toolRegistry.getExecutorAliasCanonicalTargets().entrySet()) {
                executorAliases.put(e.getKey(), e.getValue());
            }
            ArrayNode executorOnly = tools.putArray("executorOnly");
            for (String name : _toolRegistry.getDirectExecutorOnlyToolNames()) {
                executorOnly.add(name);
            }
            if (!ModelFacingSkillAdmission.hasModelFacingSkills(snap)) {
                boolean registeredGetAgentSkill = _toolRegistry.getAllDefinitions().stream()
                        .anyMatch(td -> "get_agent_skill".equals(td.getName()));
                if (registeredGetAgentSkill) {
                    ArrayNode suppressed = tools.putArray("modelFacingSuppressed");
                    ObjectNode m = suppressed.addObject();
                    m.put("name", "get_agent_skill");
                    m.put("reason", "empty_skill_catalog");
                }
            }
            ArrayNode extended = tools.putArray("extended");
            for (ExtendedToolDefinition et : snap.getExtendedToolRegistry().allByName().values()) {
                ObjectNode e = extended.addObject();
                e.put("name", et.llmName());
                e.putObject("target").put("resolvedEntityName", et.resolvedTargetThingName()).put("serviceName",
                        et.serviceName());
                e.put("hitl", ServiceCapabilityRuntimePolicy.requiresHitl(et));
                e.put("executorOnly", et.executorOnly());
                e.put("modelAdvertisable", ServiceCapabilityRuntimePolicy.isModelAdvertisable(et));
                e.put("playbookEligible", ServiceCapabilityRuntimePolicy.isPlaybookEligible(et));
                et.capability().ifPresent(cap -> {
                    cap.risk().ifPresent(r -> e.put("risk", r.name()));
                    cap.admission().ifPresent(a -> e.put("admission", a.name()));
                    e.put("enabled", cap.enabled());
                    e.put("runtimeState", cap.runtimeState().name());
                    e.put("dryRunSupported", cap.dryRunSupported());
                    cap.idempotencyMode().ifPresent(m -> e.put("idempotencyMode", m.name()));
                    if (!cap.dataClassification().isEmpty()) {
                        ArrayNode dc = e.putArray("dataClassification");
                        for (String c : cap.dataClassification()) {
                            dc.add(c);
                        }
                    }
                    if (!cap.businessErrors().isEmpty()) {
                        ArrayNode be = e.putArray("businessErrors");
                        for (String c : cap.businessErrors()) {
                            be.add(c);
                        }
                    }
                });
                e.put("status", "registered");
            }
        } else {
            root.putNull("tools");
        }
        if (includePolicies && snap != null && snap.getConfigurationRepositoryState() != null) {
            PromptContextCacheSnapshot.ConfigurationRepositoryState st = snap.getConfigurationRepositoryState();
            ObjectNode pol = root.putObject("policies");
            ObjectNode isp = pol.putObject("invoke_service");
            isp.put("path", ConfigurationRepositoryPaths.INVOKE_SERVICE_POLICY);
            if (st.isInvokeServicePolicyInvalid()) {
                isp.put("status", "invalid");
            } else if (st.isInvokeServicePolicyMissing()) {
                isp.put("status", "missing");
            } else {
                isp.put("status", "loaded");
            }
            isp.put("ruleCount", st.getInvokeServicePolicyRuleCount());
        } else {
            root.putNull("policies");
        }
        if (includeTaxonomy && snap != null) {
            ObjectNode tax = root.putObject("taxonomy");
            ObjectNode md = tax.putObject("typeTaxonomyMarkdown");
            md.put("path", ConfigurationRepositoryPaths.TAXONOMY_TYPE_MARKDOWN);
            PromptContextCacheSnapshot.ConfigurationRepositoryState cfgSt = snap.getConfigurationRepositoryState();
            if (cfgSt == null) {
                md.put("status", "not_configured");
                md.put("charCount", 0);
            } else {
                md.put("status", cfgSt.getTypeTaxonomyMarkdownStatus());
                md.put("charCount", cfgSt.getTypeTaxonomyMarkdownCharCount());
            }
            ObjectNode tab = tax.putObject("assetTaxonomyTable");
            tab.put("status", snap.getTaxonomyRows().isEmpty() ? "empty" : "loaded");
            tab.put("rowCount", snap.getTaxonomyRows().size());
            ApplicationSemanticTaxonomySnapshot sem = snap.getApplicationSemanticTaxonomy();
            ObjectNode semObj = tax.putObject("applicationSemanticTaxonomy");
            String semStatus = sem != null ? sem.snapshotStatus() : "unavailable";
            semObj.put("status", semStatus);
                if (sem != null) {
                    semObj.put("stale", sem.isStale());
                    String sp = sem.effectiveSourcePath();
                    if (sp != null && !sp.isEmpty()) {
                        semObj.put("sourcePath", sp);
                    }
                semObj.put("assetTypeCount", sem.assetTypeCount());
                semObj.set("diagnostics", TaxonomyResolverJson.diagnosticsArray(sem.diagnostics()));
            }
            SemanticProfileSnapshot profile = snap.getSemanticProfile();
            ObjectNode profileObj = tax.putObject("semanticProfile");
            if (profile == null) {
                profileObj.put("status", "unavailable");
            } else {
                profileObj.put("status", profile.snapshotStatus());
                profileObj.put("loaded", profile.isLoaded());
                profileObj.put("stale", profile.isStale());
                profileObj.put("profileId", profile.profileId());
                profileObj.put("version", profile.version());
                profileObj.put("digest", profile.digest());
                profileObj.put("assetTypeCount", profile.assetTypeCount());
                profileObj.put("roleCount", profile.roleCount());
                String psp = profile.effectiveSourcePath();
                if (psp != null && !psp.isEmpty()) {
                    profileObj.put("sourcePath", psp);
                }
                profileObj.set("diagnostics",
                        SemanticProfileDiagnosticsJson.diagnosticsArray(profile.diagnostics()));
            }
        } else {
            root.putNull("taxonomy");
        }
        boolean includePlaybooks = opts.path("includePlaybooks").asBoolean(false);
        boolean includeRepositoryFiles = opts.path("includeRepositoryFiles").asBoolean(false);
        if (includePlaybooks) {
            ObjectNode pb = root.putObject("playbooks");
            pb.put("discoveryPattern", PlaybookIds.DISCOVERY_PATTERN);
            PlaybookRegistrySnapshot pr = _playbookRegistrySnapshot;
            if (pr == null) {
                pb.put("loaded", false);
                pb.putNull("loadedAtUtc");
                pb.putArray("catalogIds");
                pb.putArray("documentIds");
                pb.putArray("reservedSlashIds");
                pb.putArray("diagnostics");
                pb.putArray("playbooks");
            } else {
                boolean loaded = pr.isLoaded();
                pb.put("loaded", loaded);
                pb.put("loadedAtUtc", pr.loadedAtUtc().toString());
                ArrayNode catIds = pb.putArray("catalogIds");
                for (String id : pr.catalogById().keySet()) {
                    catIds.add(id);
                }
                ArrayNode docIds = pb.putArray("documentIds");
                for (String id : pr.documentsById().keySet()) {
                    docIds.add(id);
                }
                ArrayNode reserve = pb.putArray("reservedSlashIds");
                for (String id : pr.reservedSlashIds()) {
                    reserve.add(id);
                }
                ArrayNode di = pb.putArray("diagnostics");
                for (String d : pr.diagnostics()) {
                    if (d != null && !d.isBlank()) {
                        di.add(d);
                    }
                }
                ArrayNode pentries = pb.putArray("playbooks");
                if (loaded) {
                    for (Map.Entry<String, PlaybookCatalogEntry> e : pr.catalogById().entrySet()) {
                        ObjectNode row = pentries.addObject();
                        String id = e.getKey();
                        row.put("id", id);
                        PlaybookCatalogEntry ce = e.getValue();
                        row.put("path", ce.playbookPath());
                        PlaybookDocument pd = pr.document(id);
                        row.put("nodeCount", pd != null ? pd.nodesById().size() : 0);
                    }
                }
            }
            if (_lastPlaybookRunOutcomeJson != null && !_lastPlaybookRunOutcomeJson.isBlank()) {
                try {
                    pb.set("lastRun", JSON.readTree(_lastPlaybookRunOutcomeJson));
                } catch (Exception ignored) {
                    pb.putNull("lastRun");
                }
            }
            root.set("playbookRuntime", PlaybookRuntimeSnapshotBuilder.build(this, snap));
        } else {
            root.putNull("playbooks");
            root.putNull("playbookRuntime");
        }
        if (includePlaybooks) {
            PlaybookRegistrySnapshot prCatalog = _playbookRegistrySnapshot;
            if (prCatalog != null && prCatalog.isLoaded()) {
                String playbookCatalog = PlaybookCatalogFormatter.format(
                        new ArrayList<>(prCatalog.catalogById().values()));
                prompt.put("playbookCatalog", playbookCatalog.isEmpty() ? "" : playbookCatalog);
            } else {
                prompt.putNull("playbookCatalog");
            }
        } else {
            prompt.putNull("playbookCatalog");
        }
        if (includeRepositoryFiles) {
            appendConfigurationRepositoryFilesJson(cfg, snap);
        }
        return JSON.writeValueAsString(root);
    }

    private void appendConfigurationRepositoryFilesJson(ObjectNode cfg, PromptContextCacheSnapshot snap)
            throws Exception {
        ArrayNode files = cfg.putArray("files");
        String repo = getConfigurationRepositoryThingName();
        if (repo == null || repo.isBlank()) {
            return;
        }
        Optional<FileRepositoryThing> fr = FileRepositoryThingResolver.resolve(repo.trim(), _logger, getName());
        RepositoryReader reader = fr.map(FileRepositoryRepositoryReader::forConfiguration).orElse(null);
        Map<String, PromptContextCacheSnapshot.RepositoryFileLoadIdentity> loadedByPath = new HashMap<>();
        if (snap != null) {
            for (PromptContextCacheSnapshot.RepositoryFileLoadIdentity id : snap.getRepositoryFileLoads()) {
                loadedByPath.put(id.path(), id);
            }
        }
        SkillRegistrySnapshot skills =
                snap != null ? snap.getSkillRegistry() : SkillRegistrySnapshot.empty(Instant.now());
        PlaybookRegistrySnapshot playbooks = _playbookRegistrySnapshot;
        Set<String> paths = ConfigurationRepositoryLoadedFileCaptures.unionPaths(
                snap != null ? snap.getRepositoryFileLoads() : List.of(), skills, playbooks);
        Instant now = Instant.now();
        for (String path : paths) {
            ObjectNode fo = files.addObject();
            fo.put("path", path);
            PromptContextCacheSnapshot.RepositoryFileLoadIdentity loaded = loadedByPath.get(path);
            if (reader == null) {
                fo.put("exists", false);
                fo.put("status", "read_error");
                fo.put("byteSize", 0);
                fo.putNull("modifiedAt");
                fo.putNull("sha256");
                putLoadedSideFileFields(fo, loaded);
                continue;
            }
            RepositoryFileFingerprint.Result cur = RepositoryFileFingerprint.capture(reader, path, now);
            boolean exists = cur.status() == RepositoryFileFingerprint.Status.present
                    || cur.status() == RepositoryFileFingerprint.Status.oversized_for_hash;
            fo.put("exists", exists);
            fo.put("status", cur.statusWireLower());
            fo.put("byteSize", cur.byteSize());
            fo.putNull("modifiedAt");
            if (cur.sha256Hex() != null) {
                fo.put("sha256", cur.sha256Hex());
            } else {
                fo.putNull("sha256");
            }
            putLoadedSideFileFields(fo, loaded);
        }
    }

    private static void putLoadedSideFileFields(ObjectNode fo,
            PromptContextCacheSnapshot.RepositoryFileLoadIdentity loaded) {
        if (loaded == null) {
            fo.putNull("loadedSha256");
            fo.putNull("loadedAtUtc");
            fo.putNull("loadedPath");
            fo.putNull("loadedModifiedAt");
            return;
        }
        if (loaded.loadedSha256() != null && !loaded.loadedSha256().isBlank()) {
            fo.put("loadedSha256", loaded.loadedSha256());
        } else {
            fo.putNull("loadedSha256");
        }
        fo.put("loadedAtUtc", loaded.loadedAtUtc().toString());
        fo.put("loadedPath", loaded.loadedPath() != null && !loaded.loadedPath().isBlank() ? loaded.loadedPath()
                : loaded.path());
        fo.putNull("loadedModifiedAt");
    }

    /**
     * Lazy first-use: when the snapshot is still {@code null}, build once on the LLM submit path (after the Thing is
     * running) so overrideable services dispatch normally. Double-checked under {@link #_promptCacheLock}; on failure
     * logs {@code ERROR}, does not throw, and leaves {@code null} unchanged. If a snapshot already exists, does nothing
     * — updates only via {@link #RefreshPromptContextCache}.
     */
    private void ensurePromptContextCacheForTurn() {
        if (_promptContextSnapshot != null) {
            return;
        }
        synchronized (_promptCacheLock) {
            if (_promptContextSnapshot != null) {
                return;
            }
            try {
                commitPromptContextCacheRefreshLocked();
            } catch (Exception e) {
                _logger.error("[{}] Prompt context cache lazy refresh failed (first LLM submit): {}",
                        getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * First successful commit from {@link #ensurePromptContextCacheForTurn} (lazy first LLM submit) or
     * {@link #RefreshPromptContextCache}.
     */
    public PromptContextCacheSnapshot getPromptContextSnapshot() {
        return _promptContextSnapshot;
    }

    /** Playbook catalog snapshot for this agent (S8 same-id Skill/Playbook shadow hints). */
    public PlaybookRegistrySnapshot getPlaybookRegistrySnapshot() {
        return _playbookRegistrySnapshot;
    }

    /** Resolver live-service fallback telemetry — see docs/agent/system-prompt-cache.md §Operational Notes. */
    public void noteResolverFallbackGenericThing() {
        long now = System.currentTimeMillis();
        if (now >= _resolverFallbackGenericThingWarnNextAllowedMillis) {
            _logger.warn(
                    "[{}] ModelKeyResolver: GenericThing snapshot unavailable; falling back to live GetIncomingDependencies. "
                            + "Run RefreshPromptContextCache after fixing startup errors.",
                    getName());
            _resolverFallbackGenericThingWarnNextAllowedMillis = now + PROMPT_CACHE_WARN_THROTTLE_MS;
        } else {
            _logger.debug("[{}] ModelKeyResolver: GenericThing live fallback (throttled)", getName());
        }
    }

    private void resetPromptCacheTelemetryOnSuccessfulSnapshotCommit() {
        _emptyCacheWarnNextAllowedMillis = 0;
        _resolverFallbackGenericThingWarnNextAllowedMillis = 0;
    }

    private void maybeWarnEmptyPromptCacheSnapshot() {
        if (_promptContextSnapshot != null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < _emptyCacheWarnNextAllowedMillis) {
            return;
        }
        _logger.warn("[{}] Stable prompt context cache is empty; run RefreshPromptContextCache after fixing startup errors.",
                getName());
        _emptyCacheWarnNextAllowedMillis = now + PROMPT_CACHE_WARN_THROTTLE_MS;
    }

    private String assembleLeadingStableSystemPrompt(String systemPromptOverride) {
        return assembleLeadingStableSystemPrompt(systemPromptOverride, _promptContextSnapshot);
    }

    private String assembleLeadingStableSystemPrompt(String systemPromptOverride,
            PromptContextCacheSnapshot snapshot) {
        return PromptContextCacheRefreshSupport.assembleLeadingStable(
                snapshot, systemPromptOverride, promptContextAssemblyContext());
    }

    /**
     * Overwrites {@code messages.get(0)} when it is {@code SYSTEM}, or inserts at 0 — the list is often the live
     * {@code _conversations} entry; replacing the leading row on refresh is intentional (prompt context, not user
     * history). See {@code docs/agent/system-prompt-cache.md}.
     */
    private void applyLeadingStableSystemRow(List<ChatMessage> messages, String systemPromptOverride) {
        LeadingStableSystemRowSupport.apply(messages,
                () -> assembleLeadingStableSystemPrompt(systemPromptOverride));
        maybeWarnEmptyPromptCacheSnapshot();
    }

    /**
     * Builds a complete snapshot. Optional repository {@code type-taxonomy.md} text, structured taxonomy rows
     * (from {@code /taxonomies/identity-types.json} when loaded), GenericThing template-name discovery, and
     * {@code GetAlertPrompt} are each best-effort: on failure the corresponding cached field is left empty, an error is
     * logged, and the rest of the snapshot (including the skill registry) still builds. Only an unexpected failure in
     * the skill-registry build or other remaining steps propagates so {@link #RefreshPromptContextCache} can preserve
     * last-good. See {@code docs/agent/system-prompt-cache.md} and {@code docs/agent/skill-management.md} (refresh failure semantics).
     */
    private PromptContextCacheSnapshot buildPromptContextSnapshotInternal() throws Exception {
        RuntimeException failBuild = PromptContextCacheRefreshSupport.failSnapshotBuildForTests;
        if (failBuild != null) {
            throw failBuild;
        }
        String directPrefix = "";
        String typeTaxonomyFileStatus = "none";
        int typeTaxonomyFileCharCount = 0;
        String cfgRepoName = getConfigurationRepositoryThingName();
        Optional<FileRepositoryThing> cfgRepositoryThing = Optional.empty();
        RepositoryReader cfgRepositoryReader = null;
        if (cfgRepoName != null && !cfgRepoName.isBlank()) {
            cfgRepositoryThing = FileRepositoryThingResolver.resolve(cfgRepoName.trim(), _logger, getName());
            if (cfgRepositoryThing.isPresent()) {
                cfgRepositoryReader = FileRepositoryRepositoryReader.forConfiguration(cfgRepositoryThing.get());
                TypeTaxonomyMarkdownLoader.TypeTaxonomyMarkdownOutcome tax =
                        TypeTaxonomyMarkdownLoader.loadWithStatus(cfgRepositoryReader, _logger);
                typeTaxonomyFileStatus = tax.status().name().toLowerCase(Locale.ROOT);
                typeTaxonomyFileCharCount = tax.measuredCharCount();
                if (tax.status() == TypeTaxonomyMarkdownLoader.Status.OVERSIZED) {
                    _logger.error("[{}] configurationRepository: type taxonomy markdown exceeds {} bytes; ignored",
                            getName(), TypeTaxonomyMarkdownLoader.MAX_CHARS);
                } else if (tax.status() == TypeTaxonomyMarkdownLoader.Status.LOADED) {
                    directPrefix = tax.promptText();
                }
            } else {
                typeTaxonomyFileStatus = "unavailable";
            }
        }
        String taxonomyMd = directPrefix != null ? directPrefix : "";
        List<String> gtNames = Collections.emptyList();
        String gtBlock = "";
        try {
            gtNames = GenericThingIncomingDependencyResolver.fetchSortedThingTemplateNames();
            gtBlock = formatGenericThingTemplateNamesBlock(gtNames);
        } catch (Exception e) {
            _logger.error("[{}] Prompt context: GenericThing template-name step failed; continuing without cached list: {}",
                    getName(), e.getMessage(), e);
        }
        String alertBlock = "";
        try {
            String rawAlert = invokeGetAlertPromptThroughPlatform();
            alertBlock = rawAlert != null ? rawAlert.trim() : "";
        } catch (Exception e) {
            _logger.error("[{}] Prompt context: GetAlertPrompt step failed; continuing without cached alert block: {}",
                    getName(), e.getMessage(), e);
        }
        ExtendedToolRegistrySnapshot extendedTools = ExtendedToolRegistrySnapshot.missing();
        boolean repoUnavailable = false;
        boolean policyMissing = false;
        boolean policyInvalid = false;
        int policyRuleCount = 0;
        PromptContextCacheSnapshot.ConfigurationRepositoryState cfgState = null;
        if (cfgRepoName != null && !cfgRepoName.isBlank()) {
            if (cfgRepositoryThing.isEmpty()) {
                repoUnavailable = true;
                policyMissing = true;
                policyInvalid = false;
            } else {
                RepositoryReader reader = cfgRepositoryReader;
                RepositoryTextLoads.Result polRes =
                        RepositoryTextLoads.loadText(reader, ConfigurationRepositoryPaths.INVOKE_SERVICE_POLICY);
                if (polRes.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
                    _logger.error("[{}] configurationRepository: invoke_service policy read failed: {}",
                            getName(), polRes.errorMessage());
                    policyInvalid = true;
                } else if (polRes.kind() == RepositoryTextLoads.Kind.MISSING
                        || polRes.kind() == RepositoryTextLoads.Kind.EMPTY) {
                    policyMissing = true;
                } else {
                    InvokeServiceAllowPolicy pol =
                            InvokeServiceAllowPolicy.parseJsonOrInvalid(polRes.text().trim(), _logger);
                    if (pol.isInvalid()) {
                        policyInvalid = true;
                    } else if (pol.isFileMissing()) {
                        policyMissing = true;
                    } else {
                        policyRuleCount = pol.ruleCount();
                    }
                }
                Set<String> builtins = new HashSet<>(builtinToolDefinitionNames());
                extendedTools = ExtendedToolsManifest.load(reader, getName(), this, builtins, _logger);
            }
            cfgState = new PromptContextCacheSnapshot.ConfigurationRepositoryState(
                    extendedTools, repoUnavailable, policyMissing, policyInvalid, policyRuleCount,
                    typeTaxonomyFileStatus, typeTaxonomyFileCharCount);
        }
        List<ToolDefinition> playbookToolDefs = PlaybookToolDefinitionsMerge.merge(_toolRegistry, extendedTools);
        PlaybookRegistrySnapshot playbookRegistry =
                PlaybookRegistryBuilder.build(this, playbookToolDefs, extendedTools, _logger);
        _playbookRegistrySnapshot = playbookRegistry;
        SkillRegistrySnapshot skillRegistry = SkillRegistryBuilder.build(this,
                playbookRegistry.reservedSlashIds(), _logger);
        ApplicationSemanticTaxonomySnapshot sem = ApplicationSemanticTaxonomyBuilder.build(this, _logger);
        ApplicationSemanticTaxonomySnapshot prevSem =
                _promptContextSnapshot != null ? _promptContextSnapshot.getApplicationSemanticTaxonomy() : null;
        if (!sem.isLoaded() && prevSem != null && prevSem.isLoaded()) {
            sem = ApplicationSemanticTaxonomySnapshot.staleFromPrior(prevSem, Instant.now(), sem.diagnostics());
        }
        Set<String> knownKeys = knownAssetTypeKeys(sem);
        SemanticProfileSnapshot profile =
                SemanticProfileBuilder.build(this, _logger, Instant.now(), knownKeys);
        SemanticProfileSnapshot prevProfile =
                _promptContextSnapshot != null ? _promptContextSnapshot.getSemanticProfile() : null;
        if (!profile.isLoaded() && prevProfile != null && prevProfile.isLoaded()) {
            profile = SemanticProfileSnapshot.staleFromPrior(prevProfile, Instant.now(), profile.diagnostics());
        }
        List<TaxonomyRow> taxRows = TaxonomyRowsFromIdentitySnapshot.build(sem);
        Instant snapshotAt = Instant.now();
        List<PromptContextCacheSnapshot.RepositoryFileLoadIdentity> fileLoads = List.of();
        ExternalSystemPromptSelection externalSystemPrompt = ExternalSystemPromptSelection.defaultSelection();
        if (cfgRepositoryReader != null) {
            fileLoads = ConfigurationRepositoryLoadedFileCaptures.capture(cfgRepositoryReader, skillRegistry,
                    playbookRegistry, snapshotAt, _logger);
            externalSystemPrompt = SystemPromptFileLoader.load(cfgRepositoryReader,
                    cfgRepoName != null ? cfgRepoName.trim() : "", _logger);
        }
        return new PromptContextCacheSnapshot(taxonomyMd, taxRows, gtNames, gtBlock, alertBlock, skillRegistry, cfgState,
                sem, snapshotAt, fileLoads, profile, externalSystemPrompt);
    }

    private static Set<String> knownAssetTypeKeys(ApplicationSemanticTaxonomySnapshot tax) {
        if (tax == null || !tax.isLoaded() || tax.assetTypeCount() == 0) {
            return Set.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (AssetTypeEntry e : tax.assetTypes()) {
            if (e != null && e.key() != null && !e.key().isBlank()) {
                keys.add(e.key());
            }
        }
        return keys;
    }

    private static String formatGenericThingTemplateNamesBlock(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        String joined = String.join(", ", names);
        return "## Platform ThingTemplate model keys\n\n"
                + "Known GenericThing-derived ThingTemplate names: " + joined + "\n\n"
                + "When the user asks to count or list Things for one of these names, use query_entities with "
                + "thingTemplate exactly matching the name.";
    }

    private void logPromptContextRefreshMetrics(PromptContextCacheSnapshot snap, String assembledStable) {
        if (snap == null) {
            return;
        }
        int taxChars = snap.getTaxonomySystemBlock().length();
        int stableChars = assembledStable != null ? assembledStable.length() : 0;
        int estTok = LeadingStablePromptComposer.estimateTokenCountHeuristic(assembledStable);
        int repoSkills = (int) snap.getSkillRegistry().descriptorsByShortId().values().stream()
                .filter(d -> d.sourceKind() == SkillSourceKind.REPOSITORY).count();
        _logger.info(
                "[{}] Prompt context cache: taxonomyRows={} taxonomyChars={} genericThingNames={} alertChars={} "
                        + "skills(repository={}) stableChars={} estStableTokens={} loadedAt={}",
                getName(), snap.getTaxonomyRows().size(), taxChars, snap.getGenericThingTemplateNames().size(),
                snap.getAlertPromptBlock().length(), repoSkills, stableChars, estTok, snap.getLoadedAtUtc());
        if (estTok < 1024) {
            _logger.warn("[{}] Stable prompt prefix estimate (~{} tokens) is below the typical OpenAI prompt-cache "
                    + "threshold (~1024 tokens); cache hits may not appear from the stable prompt alone.",
                    getName(), estTok);
        }
    }

    // ── Hierarchy Network services (override surface) ────────────────────────
    /**
     * Default implementation returns an empty {@code HierarchyNode_DS} table. Override on tenant/customer Thing to
     * supply rows ({@code id} + {@code name} per DataShape).
     */
    @ThingworxServiceDefinition(
            name = HierarchyNetworkServiceNames.GET_FLATTEN_NAME_DESCRIPTION,
            description = "Default empty HierarchyNode_DS (id + name) for full-network flatten. Override in customer Thing.",
            isAllowOverride = true)
    @ThingworxServiceResult(name = "result", baseType = "INFOTABLE",
            description = "HierarchyNode_DS rows (default empty)",
            aspects = {"dataShape:HierarchyNode_DS"})
    public InfoTable GetFlattenNameDescription() throws Exception {
        return emptyHierarchyNodeTable();
    }

    /**
     * Default implementation returns an empty {@code HierarchyNode_DS} table. Override on tenant/customer Thing to
     * resolve display-name candidates ({@code name} input) to rows ({@code id} + canonical {@code name}).
     */
    @ThingworxServiceDefinition(
            name = HierarchyNetworkServiceNames.RESOLVE_NETWORK_ID,
            description = "Default empty HierarchyNode_DS for name (NL fragment) -> node id resolution. Override in customer Thing.",
            isAllowOverride = true)
    @ThingworxServiceResult(name = "result", baseType = "INFOTABLE",
            description = "HierarchyNode_DS rows (default empty)",
            aspects = {"dataShape:HierarchyNode_DS"})
    public InfoTable ResolveNetworkID(
            @ThingworxServiceParameter(name = "name", baseType = "STRING",
                    description = "Hierarchy node display name candidate (NL / UI fragment)",
                    aspects = {"isRequired:true"}) String name
    ) throws Exception {
        return emptyHierarchyNodeTable();
    }

    /**
     * Default implementation returns an empty {@code HierarchyNode_DS} table. Override on tenant/customer Thing to
     * expose the effective hierarchy root.
     */
    @ThingworxServiceDefinition(
            name = HierarchyNetworkServiceNames.GET_ROOT_NODE,
            description = "Default empty HierarchyNode_DS for hierarchy root node. Override in customer Thing.",
            isAllowOverride = true)
    @ThingworxServiceResult(name = "result", baseType = "INFOTABLE",
            description = "HierarchyNode_DS rows (default empty)",
            aspects = {"dataShape:HierarchyNode_DS"})
    public InfoTable GetRootNode() throws Exception {
        return emptyHierarchyNodeTable();
    }

    /**
     * Default implementation returns an empty {@code EntityList}. Override on tenant/customer Thing to return assets
     * for {@code id} and its full Network subtree (union of related business Things).
     */
    @ThingworxServiceDefinition(
            name = HierarchyNetworkServiceNames.GET_ASSET_LIST,
            description = "Default empty EntityList for id -> asset list (subtree union). Override in customer Thing.",
            isAllowOverride = true)
    @ThingworxServiceResult(name = "result", baseType = "INFOTABLE",
            description = "EntityList rows (default empty)",
            aspects = {"dataShape:EntityList"})
    public InfoTable GetAssetList(
            @ThingworxServiceParameter(name = "id", baseType = "STRING",
                    description = "Stable hierarchy node id",
                    aspects = {"isRequired:true"}) String id
    ) throws Exception {
        return emptyEntityList();
    }

    /**
     * Default implementation returns an empty {@code HierarchyNode_DS} table. Override on tenant/customer Thing to
     * return immediate child network nodes for the given parent {@code id}.
     */
    @ThingworxServiceDefinition(
            name = HierarchyNetworkServiceNames.GET_CHILD_NODES,
            description = "Default empty HierarchyNode_DS for id -> immediate child nodes. Override in customer Thing.",
            isAllowOverride = true)
    @ThingworxServiceResult(name = "result", baseType = "INFOTABLE",
            description = "HierarchyNode_DS rows (default empty)",
            aspects = {"dataShape:HierarchyNode_DS"})
    public InfoTable GetChildNodes(
            @ThingworxServiceParameter(name = "id", baseType = "STRING",
                    description = "Stable parent node id",
                    aspects = {"isRequired:true"}) String id
    ) throws Exception {
        return emptyHierarchyNodeTable();
    }

    // ── Document set resolver (override surface) ─────────────────────────────
    /**
     * Default implementation returns an empty {@code ResolvedDocument} table. Override on the App-developer Thing to
     * map an asset/Thing {@code key} to a scoped document set ({@code documentId} per row). An empty result means
     * "no custom mapping; the agent uses its built-in high-confidence matcher" — diagnostics then report
     * {@code default-match} / {@code default-empty}, not {@code custom}. See
     * {@code docs/operations/knowledge-retrieval-pipeline.md} §3.3.
     */
    @ThingworxServiceDefinition(
            name = "ResolveDocumentSet",
            description = "Default empty ResolvedDocument table for key -> document set. Override in the App-developer "
                    + "Thing to scope document retrieval to the documents that apply to an asset/Thing. An empty result "
                    + "means no custom mapping; the agent falls back to its built-in matcher.",
            isAllowOverride = true)
    @ThingworxServiceResult(name = "result", baseType = "INFOTABLE",
            description = "ResolvedDocument rows (default empty)",
            aspects = {"dataShape:ResolvedDocument"})
    public InfoTable ResolveDocumentSet(
            @ThingworxServiceParameter(name = "key", baseType = "STRING",
                    description = "Asset/Thing identity key to resolve to a document set",
                    aspects = {"isRequired:true"}) String key
    ) throws Exception {
        return emptyResolvedDocumentTable();
    }

    // ── Conversation ID (thread list in DataTable) ─────────────────────────
    private static final String THREAD_DATA_TABLE_NAME = "AgentThreadDataTable";

    @ThingworxServiceDefinition(
        name = "GetOrCreateConversationId",
        description = "Search current user's threads by optional title; return matching rows (conversationId, title). "
            + "If no match, create a new thread and return that single row.")
    @ThingworxServiceResult(name = "result", baseType = "INFOTABLE",
        description = "Rows: conversationId, title (DataShape: AgentConversationSummary)",
        aspects = {"dataShape:AgentConversationSummary"})
    public InfoTable GetOrCreateConversationId(
        @ThingworxServiceParameter(name = "title", baseType = "STRING",
            description = "Optional filter/initial title. If empty, search returns all threads for user; if creating new, title becomes the new conversationId (UUID).",
            aspects = {"isRequired:false"}) String title
    ) throws Exception {
        String username = getCurrentUsername();
        com.thingworx.entities.RootEntity dataTableEntity = PlatformAccess.findProgrammatic(THREAD_DATA_TABLE_NAME, RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (dataTableEntity == null || !(dataTableEntity instanceof com.thingworx.things.Thing)) {
            throw new Exception("[" + getName() + "] AgentThreadDataTable not found. Import the extension entities.");
        }
        com.thingworx.things.Thing dataTable = (com.thingworx.things.Thing) dataTableEntity;

        boolean hasTitleFilter = title != null && !title.trim().isEmpty();
        String titleTrimmed = hasTitleFilter ? title.trim() : null;

        ValueCollection queryParams = new ValueCollection();
        // Server-side filter by username (and title when set) — avoids missing rows when the table has >500 rows total.
        // DataTableThing.QueryDataTableEntries takes query as org.json.JSONObject;
        // processServiceRequest expects QUERY args as JSONPrimitive, not com.thingworx.types.data.queries.Query.
        queryParams.put("maxItems", new NumberPrimitive(10_000.0));
        JSONObject threadQueryJson = buildAgentThreadDataTableQueryJson(username, titleTrimmed);
        queryParams.put("query", new JSONPrimitive(threadQueryJson));
        queryParams.put("values", null);
        queryParams.put("source", null);
        queryParams.put("tags", null);
        Object rawQuery = dataTable.processServiceRequest("QueryDataTableEntries", queryParams);
        InfoTable allRows = (InfoTable) rawQuery;

        List<ValueCollection> matches = new ArrayList<>();
        for (int i = 0; i < allRows.getRowCount(); i++) {
            com.thingworx.types.collections.ValueCollection row =
                    (com.thingworx.types.collections.ValueCollection) allRows.getRow(i);
            String rowUser = (String) row.getValue("username");
            if (rowUser == null || !rowUser.equals(username)) {
                continue;
            }
            String rowTitle = (String) row.getValue("title");
            if (hasTitleFilter && (rowTitle == null || !rowTitle.trim().equals(titleTrimmed))) {
                continue;
            }
            String convId = (String) row.getValue("conversationId");
            ValueCollection summaryRow = new ValueCollection();
            summaryRow.put("conversationId", new StringPrimitive(convId));
            summaryRow.put("title", new StringPrimitive(rowTitle != null ? rowTitle : ""));
            matches.add(summaryRow);
        }

        if (!matches.isEmpty()) {
            return buildSummaryInfoTable(matches);
        }

        String newConvId = UUID.randomUUID().toString();
        String newTitle = hasTitleFilter ? title.trim() : newConvId;
        DateTime now = new DateTime();
        ValueCollection addParams = new ValueCollection();
        InfoTable oneRow = new InfoTable(allRows.getDataShape());
        ValueCollection threadRow = new ValueCollection();
        threadRow.put("conversationId", new StringPrimitive(newConvId));
        threadRow.put("username", new StringPrimitive(username));
        threadRow.put("agentName", new StringPrimitive(getName()));
        threadRow.put("title", new StringPrimitive(newTitle));
        threadRow.put("createdAt", new DatetimePrimitive(now));
        threadRow.put("updatedAt", new DatetimePrimitive(now));
        oneRow.addRow(threadRow);
        addParams.SetInfoTableValue("values", oneRow);
        addParams.put("tags", null);
        addParams.put("location", null);
        addParams.put("source", null);
        addParams.put("sourceType", null);
        dataTable.processServiceRequest("AddDataTableEntry", addParams);

        List<ValueCollection> single = new ArrayList<>();
        ValueCollection r = new ValueCollection();
        r.put("conversationId", new StringPrimitive(newConvId));
        r.put("title", new StringPrimitive(newTitle));
        single.add(r);
        return buildSummaryInfoTable(single);
    }

    @ThingworxServiceDefinition(
        name = "ChangeTitle",
        description = "Update the title of an existing conversation (thread) by conversationId.")
    @ThingworxServiceResult(name = "result", baseType = "NOTHING")
    public void ChangeTitle(
        @ThingworxServiceParameter(name = "conversationId", baseType = "STRING",
            description = "Conversation/thread ID to update") String conversationId,
        @ThingworxServiceParameter(name = "title", baseType = "STRING",
            description = "New title (must not be empty)") String title
    ) throws Exception {
        if (title == null || title.trim().isEmpty()) {
            throw new Exception("ChangeTitle: title must not be empty.");
        }
        ensureConversationIdInDataTable(conversationId);
        com.thingworx.entities.RootEntity dataTableEntity = PlatformAccess.findProgrammatic(THREAD_DATA_TABLE_NAME, RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (dataTableEntity == null || !(dataTableEntity instanceof com.thingworx.things.Thing)) {
            throw new Exception("[" + getName() + "] AgentThreadDataTable not found.");
        }
        com.thingworx.things.Thing dataTable = (com.thingworx.things.Thing) dataTableEntity;

        ValueCollection getParams = new ValueCollection();
        getParams.put("key", new StringPrimitive(conversationId));
        Object rawGet = dataTable.processServiceRequest("GetDataTableEntryByKey", getParams);
        InfoTable existing = (InfoTable) rawGet;
        if (existing == null || existing.getRowCount() == 0) {
            throw new Exception("ChangeTitle: no thread found for conversationId: " + conversationId);
        }
        com.thingworx.types.collections.ValueCollection row = (com.thingworx.types.collections.ValueCollection) existing.getRow(0);
        row.put("title", new StringPrimitive(title.trim()));
        row.put("updatedAt", new DatetimePrimitive(new DateTime()));
        InfoTable updateIt = new InfoTable(existing.getDataShape());
        updateIt.addRow(row);
        ValueCollection updateParams = new ValueCollection();
        updateParams.SetInfoTableValue("values", updateIt);
        updateParams.put("tags", null);
        updateParams.put("location", null);
        updateParams.put("source", null);
        updateParams.put("sourceType", null);
        dataTable.processServiceRequest("UpdateDataTableEntry", updateParams);
    }

    private String getCurrentUsername() {
        try {
            SecurityContext ctx = ThreadLocalContext.getSecurityContext();
            return ctx != null && ctx.getName() != null ? ctx.getName() : "Anonymous";
        } catch (Exception e) {
            return "Anonymous";
        }
    }

    /**
     * Ensures the conversationId exists in AgentThreadDataTable and belongs to the current user.
     * Throws if not found or wrong user. No-op when conversationId is null or empty.
     */
    private void ensureConversationIdInDataTable(String conversationId) throws Exception {
        AgentThreadDataTableSupport.ensureConversationOwnedByCurrentUser(conversationId, "[" + getName() + "]");
    }

    /**
     * JSON query object for {@code QueryDataTableEntries} on {@value #THREAD_DATA_TABLE_NAME}: {@code EQ} on
     * {@code username}, optional {@code And} with exact {@code title}. Passed as {@link JSONPrimitive} into
     * {@code processServiceRequest} (the platform method {@code DataTableThing.QueryDataTableEntries}
     * takes {@code JSONObject query}).
     */
    private static JSONObject buildAgentThreadDataTableQueryJson(String username, String titleExactOrNull) {
        JSONObject root = new JSONObject();
        if (titleExactOrNull != null && !titleExactOrNull.isEmpty()) {
            JSONArray clauses = new JSONArray();
            JSONObject userEq = new JSONObject();
            userEq.put("type", "EQ");
            userEq.put("fieldName", "username");
            userEq.put("value", username);
            clauses.put(userEq);
            JSONObject titleEq = new JSONObject();
            titleEq.put("type", "EQ");
            titleEq.put("fieldName", "title");
            titleEq.put("value", titleExactOrNull);
            clauses.put(titleEq);
            JSONObject and = new JSONObject();
            and.put("type", "And");
            and.put("filters", clauses);
            root.put("filters", and);
        } else {
            JSONObject f = new JSONObject();
            f.put("type", "EQ");
            f.put("fieldName", "username");
            f.put("value", username);
            root.put("filters", f);
        }
        return root;
    }

    private static InfoTable buildSummaryInfoTable(List<ValueCollection> rows) {
        DataShapeDefinition summaryShape = new DataShapeDefinition();
        FieldDefinition fdConv = new FieldDefinition();
        fdConv.setName("conversationId");
        fdConv.setBaseType(BaseTypes.STRING);
        fdConv.setOrdinal(0);
        summaryShape.addFieldDefinition(fdConv);
        FieldDefinition fdTitle = new FieldDefinition();
        fdTitle.setName("title");
        fdTitle.setBaseType(BaseTypes.STRING);
        fdTitle.setOrdinal(1);
        summaryShape.addFieldDefinition(fdTitle);
        InfoTable out = new InfoTable(summaryShape);
        for (ValueCollection row : rows) {
            out.addRow(row);
        }
        return out;
    }

    /** Merged built-in + configuration-repository extended tool definitions for the LLM. */
    private List<ToolDefinition> getMergedToolDefinitions() {
        List<ToolDefinition> out = new ArrayList<>();
        PromptContextCacheSnapshot snap = _promptContextSnapshot;
        boolean modelFacingSkillCatalog = ModelFacingSkillAdmission.hasModelFacingSkills(snap);
        for (ToolDefinition td : _toolRegistry.getAllDefinitions()) {
            if ("get_agent_skill".equals(td.getName()) && !modelFacingSkillCatalog) {
                continue;
            }
            out.add(td);
        }
        PlaybookRegistrySnapshot playbook = _playbookRegistrySnapshot;
        if (playbook != null && playbook.isLoaded()) {
            ToolDefinition startPlaybook = PlaybookStartToolDefinitionBuilder.build(playbook);
            if (startPlaybook != null) {
                out.add(startPlaybook);
            }
        }
        if (snap != null) {
            for (ExtendedToolDefinition et : snap.getExtendedToolRegistry().allByName().values()) {
                if (ServiceCapabilityRuntimePolicy.isModelAdvertisable(et)) {
                    out.add(et.toolDefinition());
                }
            }
        }
        return out;
    }

    /**
     * Built-in tool names reserved against {@code extended_tools.json} conflicts (authoring / manifest /
     * Playbook document validation). Delegates to {@link ReservedBuiltinToolNames#fromRegistry} so
     * dynamic names such as {@code start_playbook} are reserved even when Playbook is not loaded.
     */
    public Set<String> builtinToolDefinitionNames() {
        return ReservedBuiltinToolNames.fromRegistry(_toolRegistry);
    }

    /**
     * Parler AlwaysOn: enqueue {@link PendingApprovalRecord} and pause the loop unless not in Parler tool context.
     *
     * @param preWriteValueSnapshotJson compare-and-stale snapshot for {@code set_property_value}; {@code null} for
     *                                  {@code invoke_service} (Phase E: no generic stale check)
     * @param invokeServiceTypeResolution normalized {@code invoke_service} resolution for HITL continuation, or {@code null}
     * @param invokeServiceParameterRepair pre-HITL top-level→{@code parameters} repair for continuation wire metadata, or {@code null}
     */
    private void tryEnqueueParlerHitlPending(ToolCall toolCall, String preWriteValueSnapshotJson,
            ServiceTargetEntityTypeResolution invokeServiceTypeResolution,
            InvokeServiceParameterNormalizer.Repair invokeServiceParameterRepair,
            String extendedToolTargetEntityName,
            String extendedToolTargetServiceName) throws ApprovalPendingException {
        ParlerHitlStreamScopedEnqueue.enqueueOrThrow(getCurrentUsername(), getName(), toolCall, preWriteValueSnapshotJson,
                invokeServiceTypeResolution, invokeServiceParameterRepair, extendedToolTargetEntityName,
                extendedToolTargetServiceName);
    }

    /** v1b terminal {@code task.state} snapshot before {@link AgentToolContext#clear()} (AlwaysOn). */
    private void emitParlerTaskStateTurnEnd(AgentLoop.AgentResult result) {
        if (result == null) {
            return;
        }
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        if (st == null) {
            return;
        }
        TaskProgressV1b v = st.getTaskProgressV1b();
        if (v == null) {
            return;
        }
        AgentLoop.AgentResult.Status rs = result.getStatus();
        boolean cancelled = rs == AgentLoop.AgentResult.Status.CANCELLED;
        boolean awaiting = rs == AgentLoop.AgentResult.Status.AWAITING_APPROVAL;
        boolean success = rs == AgentLoop.AgentResult.Status.SUCCESS;
        if (cancelled) {
            v.applyTurnEnd(false, false);
        } else {
            v.applyTurnEnd(success, awaiting);
        }
        TaskProgressWireEmitter.flushSnapshot(st);
    }

    /**
     * Agent loop for a user turn, including optional document-turn tool narrowing ({@code docs/operations/doc-index-enhance.md} D2).
     */
    private AgentLoop newAgentLoopForTurn(LlmClient turnLlm, ToolExecutor combinedExecutor,
            RateControlStatusSink rateControlSinkOrNull, String userIanaTimezone) {
        final String apiShapeIdForLog = turnLlm != null && turnLlm.usageWireIds() != null
                ? turnLlm.usageWireIds().getApiShapeId() : "";
        // Per-turn pipeline (tool-schema-admission-control §2.6): admission -> D2 -> ContextBudgetPlanner.
        // Admission runs first (inner); DocumentTurnToolNarrowing narrows the already-admitted set (outer).
        return new AgentLoop(
                turnLlm, combinedExecutor, null,
                _temperature, _maxTokens, _maxIterations, _agentTimeout,
                rateControlSinkOrNull, _llmContextMaxChars,
                (merged, iter) -> DocumentTurnToolNarrowing.filterForRound(
                        applyToolAdmissionForRound(merged, iter, apiShapeIdForLog), iter),
                userIanaTimezone);
    }

    /**
     * Tool-schema admission for one round (tool-schema-admission-control.md §2). {@code narrow} drops irrelevant
     * buckets (§2.7); {@code lazy} advertises core + the {@code load_tool_schemas} meta-tool (§2.8); {@code off}
     * (default) is a no-op pass-through that keeps the merged set byte-for-byte. Logs the {@code TOOL_ADMISSION}
     * decision once per turn (iteration 1).
     */
    private List<ToolDefinition> applyToolAdmissionForRound(List<ToolDefinition> merged, int iterationOneBased,
            String apiShapeIdForLog) {
        ToolAdmissionMode mode = ToolAdmissionMode.parse(getToolAdmissionMode());
        if (mode == ToolAdmissionMode.NARROW) {
            ToolAdmissionSignals signals = resolveToolAdmissionSignals();
            ToolAdmissionPolicy.Result result = ToolAdmissionPolicy.narrow(merged, signals);
            if (iterationOneBased == 1 && _logger.isInfoEnabled()) {
                int beforeChars = ToolSchemaSizer.totalSchemaChars(apiShapeIdForLog, merged);
                int afterChars = ToolSchemaSizer.totalSchemaChars(apiShapeIdForLog, result.admitted());
                _logger.info(
                        ToolAdmissionPolicy.formatDecisionLine(getName(), "narrow", result, beforeChars, afterChars));
            }
            return result.admitted();
        }
        if (mode == ToolAdmissionMode.LAZY) {
            return applyLazyAdmissionForRound(merged, iterationOneBased, apiShapeIdForLog);
        }
        return merged; // off
    }

    /**
     * {@code lazy} admission (M3): advertise core + host-context-required + already-loaded tools with full schemas,
     * plus the {@code load_tool_schemas} meta-tool carrying a per-turn catalog (name + whenToUse) of the deferred
     * tail. As the model loads tools, subsequent rounds advertise them natively.
     */
    private List<ToolDefinition> applyLazyAdmissionForRound(List<ToolDefinition> merged, int iterationOneBased,
            String apiShapeIdForLog) {
        ToolAdmissionSignals signals = resolveToolAdmissionSignals();
        Set<String> registered =
                LazyToolRegistrationRegistry.snapshotForTurn(FetchCachedReplayGuard.resolveCurrentTurnKey());
        ToolAdmissionPolicy.LazyResult lr = ToolAdmissionPolicy.lazy(merged, signals, registered);
        List<ToolDefinition> out = new ArrayList<>(lr.advertised());
        if (!lr.catalog().isEmpty()) {
            out.add(buildLoadToolSchemasDef(lr.catalog()));
        }
        if (iterationOneBased == 1 && _logger.isInfoEnabled()) {
            int beforeChars = ToolSchemaSizer.totalSchemaChars(apiShapeIdForLog, merged);
            int afterChars = ToolSchemaSizer.totalSchemaChars(apiShapeIdForLog, out);
            _logger.info(ToolAdmissionPolicy.formatLazyDecisionLine(getName(), lr, registered.size(),
                    beforeChars, afterChars));
        }
        return out;
    }

    /** Builds the per-turn {@code load_tool_schemas} meta-tool definition embedding the deferred-tail catalog. */
    private static ToolDefinition buildLoadToolSchemasDef(List<ToolAdmissionPolicy.CatalogEntry> catalog) {
        String catalogText = ToolAdmissionPolicy.renderCatalog(catalog);
        String description = LoadToolSchemasExecutor.metaToolDescriptionPrefix() + catalogText;
        Map<String, Object> names = new LinkedHashMap<>();
        names.put("type", "array");
        names.put("items", Map.of("type", "string"));
        names.put("description", "Exact tool names to load, taken from the Available tools catalog above.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("names", names);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("names"));
        return new ToolDefinition("load_tool_schemas", description, schema);
    }

    /** Snapshot of the merged model-facing tools for this turn (used by the {@code load_tool_schemas} executor). */
    public List<ToolDefinition> snapshotMergedModelFacingTools() {
        return getMergedToolDefinitions();
    }

    /** Resolves the deterministic per-turn admission signals (§2.4) from host context, loaded registries, and slash. */
    private ToolAdmissionSignals resolveToolAdmissionSignals() {
        PromptContextCacheSnapshot snap = _promptContextSnapshot;
        boolean loadedSkills = ModelFacingSkillAdmission.hasModelFacingSkills(snap);
        PlaybookRegistrySnapshot playbook = _playbookRegistrySnapshot;
        boolean loadedPlaybook = playbook != null && playbook.isLoaded();
        boolean taxonomyReady = false;
        if (snap != null) {
            ApplicationSemanticTaxonomySnapshot tax = snap.getApplicationSemanticTaxonomy();
            taxonomyReady = tax != null && tax.isLoaded();
        }
        List<String> slash = AgentToolContext.snapshotSlashSkillShortNamesForPending();
        boolean slashActive = slash != null && !slash.isEmpty();
        List<String> docScope = AgentToolContext.getInjectedDocumentScopeIds();
        boolean documentScopeActive = docScope != null && !docScope.isEmpty();
        String hostKey = parseHostContextKey(AgentToolContext.getHostContextJson());
        Set<String> requiredTools = Collections.emptySet();
        Set<ToolBucket> requiredBuckets = Collections.emptySet();
        if (hostKey != null) {
            HostContextTemplate tpl = HostContextTemplateRegistry.find(hostKey, this);
            if (tpl != null) {
                requiredTools = new LinkedHashSet<>(tpl.requiredTools());
                requiredBuckets = EnumSet.noneOf(ToolBucket.class);
                for (String b : tpl.requiredBuckets()) {
                    ToolBucket parsed = ToolBuckets.parseBucket(b);
                    if (parsed != null) {
                        requiredBuckets.add(parsed);
                    }
                }
            }
        }
        return new ToolAdmissionSignals(hostKey, slashActive, documentScopeActive, loadedSkills, loadedPlaybook,
                taxonomyReady, requiredTools, requiredBuckets);
    }

    /** Extracts the host-context registry key from the raw uplink JSON; null when absent or unparsable. */
    private static String parseHostContextKey(String hostContextJson) {
        if (hostContextJson == null || hostContextJson.isEmpty()) {
            return null;
        }
        try {
            String key = new JSONObject(hostContextJson).optString("key", "").trim();
            return key.isEmpty() ? null : key;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * v1a per-turn task state: goal from slash-cleaned user line (see {@link LlmTurnContext#userMessageForModel}).
     */
    private void primeAgentTaskStateForTurn(LlmTurnContext turn, String requestIdOrNull, String conversationIdOrNull) {
        String goal = "";
        if (turn != null && turn.userMessageForModel != null && turn.userMessageForModel.getContent() != null) {
            goal = turn.userMessageForModel.getContent();
        }
        String rid = requestIdOrNull != null ? requestIdOrNull : "";
        String cid = conversationIdOrNull != null && !conversationIdOrNull.isEmpty()
                ? conversationIdOrNull
                : AgentToolContext.SINGLE_TURN_CONVERSATION_ID;
        if (turn != null && turn.slashSkillShortNamesInOrder != null && !turn.slashSkillShortNamesInOrder.isEmpty()) {
            AgentToolContext.setParlerSlashSkillShortNamesForTurn(turn.slashSkillShortNamesInOrder);
        } else {
            AgentToolContext.setParlerSlashSkillShortNamesForTurn(null);
        }
        JSONObject checklistUnion = null;
        boolean alwaysOnTurn = rid != null && !rid.isEmpty();
        if (alwaysOnTurn && turn != null && turn.slashSkillShortNamesInOrder != null
                && !turn.slashSkillShortNamesInOrder.isEmpty()) {
            try {
                checklistUnion = SkillChecklistParser.unionFromSlashSkills(this, turn.slashSkillShortNamesInOrder);
            } catch (SkillChecklistParseException e) {
                _logger.warn("[{}] parler-task-checklist-v1 parse skipped: {}", getName(), e.getMessage());
            }
        }
        AgentTaskState st = new AgentTaskState(rid, cid, goal);
        st.setTaskProgressV1b(TaskProgressV1b.fromChecklist(checklistUnion));
        AgentToolContext.setAgentTaskState(st);
        TaskProgressWireEmitter.flushSnapshot(st);
    }

    /**
     * HITL continuation: reuse latest USER text as Goal; rebuild v1b checklist from {@link PendingApprovalRecord}
     * slash snapshot when present.
     */
    private void primeAgentTaskStateFromHistory(String requestIdOrNull, String conversationIdOrNull,
            List<ChatMessage> messages, List<String> slashSkillSnapshotOrNull,
            List<String> dynamicSkillSnapshotOrNull) {
        String goal = "";
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage m = messages.get(i);
                if (m.getRole() == ChatMessage.Role.USER) {
                    goal = m.getContent() != null ? m.getContent() : "";
                    break;
                }
            }
        }
        String rid = requestIdOrNull != null ? requestIdOrNull : "";
        String cid = conversationIdOrNull != null && !conversationIdOrNull.isEmpty()
                ? conversationIdOrNull
                : AgentToolContext.SINGLE_TURN_CONVERSATION_ID;
        List<String> slashSkillSnapshot =
                slashSkillSnapshotOrNull != null ? slashSkillSnapshotOrNull : List.of();
        List<String> dynamicSnap =
                dynamicSkillSnapshotOrNull != null ? dynamicSkillSnapshotOrNull : List.of();
        JSONObject checklistUnion =
                SkillChecklistContinuationMerge.unionForHitlContinuation(this, slashSkillSnapshot, dynamicSnap);
        AgentTaskState st = new AgentTaskState(rid, cid, goal);
        // v1b.2 R21: restore live dynamic skill identity from the pending snapshot so nested HITL and post-continuation
        // get_agent_skill idempotency match design.
        for (String dyn : dynamicSnap) {
            if (dyn != null && !dyn.isBlank()) {
                st.recordMergedDynamicSkillShortName(dyn.trim());
            }
        }
        st.setTaskProgressV1b(TaskProgressV1b.fromChecklist(checklistUnion));
        AgentToolContext.setAgentTaskState(st);
        TaskProgressWireEmitter.flushSnapshot(st);
    }

    /**
     * Dispatches configuration-repository extended tools or the built-in registry. Applies
     * {@link ConsecutiveIdenticalToolCallTracker} before dispatch; body is in
     * {@link #dispatchExecuteToolCallWithoutRepetitionGuard(ToolCall)}.
     */
    private String executeToolCall(ToolCall toolCall) throws Exception {
        JsonNode repeatArgs = ConsecutiveIdenticalToolCallTracker.parseArgumentsForHashing(toolCall.getArguments());
        String turnKey = FetchCachedReplayGuard.resolveCurrentTurnKey();
        ConsecutiveIdenticalToolCallTracker repTracker = ConsecutiveIdenticalToolCallRegistry.acquireForTurn(turnKey);
        Optional<String> repetitionBlocked = repTracker.interceptThirdIdentical(toolCall.getFunctionName(), repeatArgs);
        if (repetitionBlocked.isPresent()) {
            return repetitionBlocked.get();
        }
        try {
            String dispatched = dispatchExecuteToolCallWithoutRepetitionGuard(toolCall);
            repTracker.recordCompletion(toolCall.getFunctionName(), repeatArgs, dispatched);
            afterDocumentToolDispatch(toolCall.getFunctionName(), toolCall.getArguments(), dispatched, false);
            return dispatched;
        } catch (ApprovalPendingException e) {
            throw e;
        }
    }

    private static void afterDocumentToolDispatch(
            String functionName,
            String argumentsJson,
            String resultJson,
            boolean repetitionBlockedThisDispatch) {
        if ("get_document_chunk".equals(functionName)) {
            DocumentSearchProgressGuard.onGetDocumentChunk(resultJson);
            return;
        }
        if (!"search_document_chunks".equals(functionName)) {
            return;
        }
        int limit = 10;
        try {
            JsonNode args = ConsecutiveIdenticalToolCallTracker.parseArgumentsForHashing(argumentsJson);
            if (args.has("limit") && args.get("limit").isNumber()) {
                limit = args.get("limit").asInt();
            }
        } catch (Exception ignored) {
            // keep default limit for fingerprint sizing
        }
        DocumentSearchProgressGuard.interceptSearchLoop(
                functionName, resultJson, limit, repetitionBlockedThisDispatch);
    }

    /** Core dispatch after {@link ConsecutiveIdenticalToolCallTracker} allows the call. */
    private String dispatchExecuteToolCallWithoutRepetitionGuard(ToolCall toolCall) throws Exception {
        String fn = toolCall.getFunctionName();
        DocumentTurnToolNarrowing.recordToolInvocation(fn);
        if ("start_playbook".equals(fn)) {
            return executeStartPlaybookTool(toolCall);
        }
        ExtendedToolRegistrySnapshot extSnap = _promptContextSnapshot != null
                ? _promptContextSnapshot.getExtendedToolRegistry() : ExtendedToolRegistrySnapshot.missing();
        Optional<ExtendedToolDefinition> extOpt = extSnap.find(fn);
        if (extOpt.isPresent()) {
            ExtendedToolDefinition ext = extOpt.get();
            AgentTaskStateHooks.beforeExecution(toolCall);
            TaskProgressV1bHooks.onBeforeTool(toolCall);
            // SPR-2: capability block before HITL — DISABLED / DESTRUCTIVE / ADMIN must not
            // become approval-pending.
            ServiceCapabilityRuntimePolicy.DirectDispatchDecision gate =
                    ServiceCapabilityRuntimePolicy.directDispatchDecision(ext);
            if (gate == ServiceCapabilityRuntimePolicy.DirectDispatchDecision.BLOCKED) {
                String reason = ServiceCapabilityRuntimePolicy.executionBlockReason(ext)
                        .orElse("capability policy blocked");
                String toolResult = capabilityPolicyBlockedResult(toolCall.getFunctionName(), reason);
                AgentTaskStateHooks.afterExecution(toolCall, toolResult);
                TaskProgressV1bHooks.afterTrackedTool(toolCall, toolResult);
                return toolResult;
            }
            if (gate == ServiceCapabilityRuntimePolicy.DirectDispatchDecision.REQUIRE_HITL) {
                try {
                    tryEnqueueParlerHitlPending(toolCall, null, null, null,
                            ext.resolvedTargetThingName(), ext.serviceName());
                } catch (ApprovalPendingException e) {
                    AgentTaskStateHooks.markBlocked(toolCall);
                    throw e;
                }
                String blocked = ParlerHitlStreamScopedEnqueue.approvalRequiresParlerContextJson(fn);
                AgentTaskStateHooks.afterExecution(toolCall, blocked);
                TaskProgressV1bHooks.afterTrackedTool(toolCall, blocked);
                return blocked;
            }
            String toolResult = executeExtendedTool(toolCall, ext);
            AgentTaskStateHooks.afterExecution(toolCall, toolResult);
            TaskProgressV1bHooks.afterTrackedTool(toolCall, toolResult);
            TaskProgressV1bDynamicMerge.afterGetAgentSkill(toolCall, toolResult);
            return TabularChartRoundHooks.afterBuiltInToolResult(fn, toolResult);
        }
        if ("set_property_value".equals(fn)) {
            SetPropertyValueExecutor.SetPropertyHitlGate hitlGate =
                    SetPropertyValueExecutor.gateSetPropertyValueForHitl(toolCall);
            if (hitlGate.earlyErrorJson() != null) {
                return hitlGate.earlyErrorJson();
            }
            ToolCall gatedSetProperty = hitlGate.gatedToolCall();
            String prot = ProtectedValuePolicy.setPropertyValuePreflightBlockedJson(gatedSetProperty.getArguments());
            if (prot != null) {
                ParlerProtectionAudit.blocked(ProtectedValuePolicy.CODE_WRITE_BLOCKED, "set_property_value",
                        "preflight blocked (PASSWORD or unknown property metadata)");
                return prot;
            }
            TaskProgressV1bHooks.onBeforeTool(gatedSetProperty);
            try {
                tryEnqueueParlerHitlPending(gatedSetProperty,
                        SetPropertyValueExecutor.snapshotPropertyValueForStaleCheck(gatedSetProperty), null, null, null,
                        null);
            } catch (ApprovalPendingException e) {
                TaskProgressV1bHooks.onBlocked(gatedSetProperty);
                throw e;
            }
            return SetPropertyValueExecutor.blockedOutsideParlerContextJson();
        }
        boolean boundInvokeNorm = false;
        boolean boundInvokeParamRepair = false;
        if ("invoke_service".equals(fn)) {
            ServiceTargetEntityTypeResolver.InvokeServiceToolPrep prep =
                    ServiceTargetEntityTypeResolver.prepareInvokeServiceToolCall(toolCall, _promptContextSnapshot);
            ToolCall invokeTracked =
                    prep.getToolCall() != null ? prep.getToolCall() : toolCall;
            AgentTaskStateHooks.beforeExecution(invokeTracked);
            TaskProgressV1bHooks.onBeforeTool(invokeTracked);
            if (prep.getEarlyErrorJson() != null) {
                String earlyErr = prep.getEarlyErrorJson();
                AgentTaskStateHooks.afterExecution(invokeTracked, earlyErr);
                TaskProgressV1bHooks.afterTrackedTool(invokeTracked, earlyErr);
                return earlyErr;
            }
            toolCall = invokeTracked;
            ServiceTargetEntityTypeResolution invokeNorm = prep.getResolution();
            InvokeServiceParameterNormalizer.Repair invokeParamRepair = prep.getParameterRepair();
            if (invokeNorm != null && invokeNorm.isNormalized()) {
                InvokeServiceEntityTypeNormalization.bind(toolCall.getId(), invokeNorm);
                boundInvokeNorm = true;
            }
            if (invokeParamRepair != null && invokeParamRepair.isRepaired()) {
                InvokeServiceParameterRepairBinding.bind(toolCall.getId(), invokeParamRepair);
                boundInvokeParamRepair = true;
            }
            boolean bypassHitl = false;
            try {
                JsonNode argsRoot = JSON.readTree(toolCall.getArguments() == null ? "{}" : toolCall.getArguments());
                String entityName = jsonText(argsRoot, "entityName");
                String serviceNameCall = jsonText(argsRoot, "serviceName");
                String effType = invokeNorm != null && invokeNorm.getEffectiveEntityTypeName() != null
                        ? invokeNorm.getEffectiveEntityTypeName() : jsonText(argsRoot, "entityType");
                InvokeServiceAllowPolicy pol = InvokeServicePolicyBatchCache.getOrLoad(this, _logger);
                bypassHitl = pol.allowsBypass(effType, entityName, serviceNameCall);
            } catch (Exception ignored) {
                bypassHitl = false;
            }
            if (!bypassHitl) {
                try {
                    tryEnqueueParlerHitlPending(toolCall, null, invokeNorm,
                            invokeParamRepair.isRepaired() ? invokeParamRepair : null, null, null);
                } catch (ApprovalPendingException e) {
                    AgentTaskStateHooks.markBlocked(toolCall);
                    if (boundInvokeNorm) {
                        InvokeServiceEntityTypeNormalization.unbind();
                    }
                    if (boundInvokeParamRepair) {
                        InvokeServiceParameterRepairBinding.unbind();
                    }
                    throw e;
                }
                if (boundInvokeNorm) {
                    InvokeServiceEntityTypeNormalization.unbind();
                }
                if (boundInvokeParamRepair) {
                    InvokeServiceParameterRepairBinding.unbind();
                }
                String blocked = ParlerHitlStreamScopedEnqueue.approvalRequiresParlerContextJson(fn);
                AgentTaskStateHooks.afterExecution(toolCall, blocked);
                TaskProgressV1bHooks.afterTrackedTool(toolCall, blocked);
                return blocked;
            }
        }
        if ("fetch_cached_result".equals(toolCall.getFunctionName())) {
            AgentTaskStateHooks.beforeExecution(toolCall);
            TaskProgressV1bHooks.onBeforeTool(toolCall);
        }
        if (!"invoke_service".equals(toolCall.getFunctionName())
                && !"fetch_cached_result".equals(toolCall.getFunctionName())) {
            TaskProgressV1bHooks.onBeforeTool(toolCall);
        }
        PromptContextCacheSnapshot snap = _promptContextSnapshot;
        boolean bindGtRepair =
                "list_entities_by_type".equals(toolCall.getFunctionName()) && snap != null;
        if (bindGtRepair) {
            GenericThingIncomingDependencyResolver.bindRepairCachedThingTemplateNames(snap.getGenericThingTemplateNames());
        }
        try {
            String toolResult = _toolRegistry.executeTool(toolCall);
            if (AgentTaskStateHooks.shouldTrack(toolCall.getFunctionName())) {
                AgentTaskStateHooks.afterExecution(toolCall, toolResult);
            }
            TaskProgressV1bHooks.afterTrackedTool(toolCall, toolResult);
            TaskProgressV1bDynamicMerge.afterGetAgentSkill(toolCall, toolResult);
            return TabularChartRoundHooks.afterBuiltInToolResult(toolCall.getFunctionName(), toolResult);
        } finally {
            if (boundInvokeNorm) {
                InvokeServiceEntityTypeNormalization.unbind();
            }
            if (boundInvokeParamRepair) {
                InvokeServiceParameterRepairBinding.unbind();
            }
            if (bindGtRepair) {
                GenericThingIncomingDependencyResolver.unbindRepairCachedThingTemplateNames();
            }
        }
    }

    private static String jsonText(JsonNode root, String field) {
        if (root == null || field == null) {
            return "";
        }
        JsonNode n = root.get(field);
        return n != null && !n.isNull() ? n.asText("") : "";
    }

    private String capabilityPolicyBlockedResult(String toolName, String reason) {
        ParlerProtectionAudit.blocked(ServiceCapabilityRuntimePolicy.BLOCK_CODE, toolName, reason);
        return TypedToolErrorJson.toJson(TypedToolError.of(
                ServiceCapabilityRuntimePolicy.BLOCK_CODE,
                ErrorCategory.AUTHORIZATION,
                reason,
                false,
                null,
                List.of(),
                true,
                "Extended tool blocked by capability policy: " + reason));
    }

    private String executeExtendedTool(ToolCall toolCall, ExtendedToolDefinition def) throws Exception {
        Optional<String> blocked = ServiceCapabilityRuntimePolicy.executionBlockReason(def);
        if (blocked.isPresent()) {
            // Defense in depth: direct dispatch already gates BLOCKED before HITL.
            return capabilityPolicyBlockedResult(toolCall.getFunctionName(), blocked.get());
        }
        ToolCall enforced = toolCall;
        Optional<ServiceCapabilityMetadata> cap = def.capability();
        if (cap.isPresent() && cap.get().dryRunSupported()) {
            ServiceCapabilityDryRunEnforce.Result dry =
                    ServiceCapabilityDryRunEnforce.apply(toolCall.getArguments(), cap.get());
            if (dry.blocked()) {
                return capabilityPolicyBlockedResult(toolCall.getFunctionName(), dry.blockReason().orElse(
                        "dry-run enforce failed"));
            }
            enforced = new ToolCall(toolCall.getId(), toolCall.getFunctionName(), dry.argumentsJson());
        }
        RootEntity ent = PlatformAccess.findAsUser(def.resolvedTargetThingName(),
                RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (!(ent instanceof Thing)) {
            return "{\"status\":\"error\",\"message\":\"Extended tool target Thing not found\"}";
        }
        return executeExtendedToolOnThing(enforced, (Thing) ent, def.serviceName());
    }

    /**
     * G13 {@code dataClassification} for an extended tool, if the active registry declares it.
     * Used by {@link AgentLoop} egress stamping (SPR-5).
     */
    List<String> capabilityDataClassificationForTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return List.of();
        }
        ExtendedToolRegistrySnapshot extSnap = _promptContextSnapshot != null
                ? _promptContextSnapshot.getExtendedToolRegistry() : ExtendedToolRegistrySnapshot.missing();
        return extSnap.find(toolName)
                .flatMap(ExtendedToolDefinition::capability)
                .map(ServiceCapabilityMetadata::dataClassification)
                .orElse(List.of());
    }

    private String executeExtendedToolOnThing(ToolCall toolCall, String targetThingName, String serviceName)
            throws Exception {
        RootEntity ent = PlatformAccess.findAsUser(targetThingName,
                RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (!(ent instanceof Thing)) {
            return "{\"status\":\"error\",\"message\":\"Target Thing not found\"}";
        }
        return executeExtendedToolOnThing(toolCall, (Thing) ent, serviceName);
    }

    private String executeExtendedToolOnThing(ToolCall toolCall, Thing target, String serviceName) throws Exception {
        String argsJson = toolCall.getArguments();
        if (argsJson == null) {
            argsJson = "{}";
        }
        long t0 = System.currentTimeMillis();
        String llmName = toolCall.getFunctionName();
        _logger.info("[{}] extended tool {} -> {}.{} argsChars={}", getName(), llmName, target.getName(), serviceName,
                argsJson.length());
        ServiceDefinition sdMeta = CustomToolHarvester.findServiceDefinition(target, serviceName);
        if (sdMeta != null && ProtectedValuePolicy.serviceDefinitionDeclaresPasswordParameter(sdMeta)) {
            ParlerProtectionAudit.blocked(ProtectedValuePolicy.CODE_INPUT_BLOCKED, llmName,
                    "extended tool declares PASSWORD input");
            return ProtectedValuePolicy.inputBlockedJson(
                    "This extended tool declares PASSWORD parameters and cannot be invoked through the agent.");
        }
        if (sdMeta == null) {
            return "{\"status\":\"error\",\"message\":\"Service metadata unavailable\"}";
        }
        ValueCollection params;
        try {
            JsonNode root = JSON.readTree(argsJson);
            String preflight = ExtendedToolThingnamePreflight.checkJsonArgs(sdMeta, root);
            if (preflight != null) {
                return preflight;
            }
            params = CustomToolHarvester.buildValueCollectionForCustomTool(target, serviceName, root);
        } catch (UnsupportedRelativeLiteralException e) {
            _logger.info("[{}] extended tool {} UNSUPPORTED_RELATIVE_LITERAL param={} reason={} value=\"{}\"",
                    getName(), llmName, e.getParamName(),
                    e.getRejectionReason() != null ? e.getRejectionReason().name() : null,
                    InvokeServiceErrorJson.truncateForLog(e.getRawValue(), 80));
            return InvokeServiceErrorJson.unsupportedRelativeLiteral(e);
        } catch (CustomToolNaturalTimeException e) {
            _logger.info("[{}] extended tool {} {}: rejectedParameter={}", getName(), llmName, e.getCode(),
                    e.getRejectedParameter());
            return BuiltInToolTimeErrorJson.error(e.getCode(), e.getDetail(), e.getRejectedParameter());
        } catch (IllegalArgumentException e) {
            _logger.warn("[{}] Invalid extended tool arguments for {}: {}", getName(), llmName, e.getMessage());
            return "{\"status\":\"error\",\"message\":\"" + escapeJsonString(e.getMessage()) + "\"}";
        } catch (Exception e) {
            _logger.warn("[{}] Failed to parse extended tool arguments for {}: {}", getName(), llmName, e.getMessage());
            return "{\"status\":\"error\",\"message\":\"Failed to parse tool arguments\"}";
        }
        InfoTable rawResult = PlatformAccess.invokeAsUser(target, serviceName, params);
        String out = InvokeServiceExecutor.formatDirectServiceResultForLlm(rawResult, sdMeta, target);
        _logger.info("[{}] extended tool {} ok in {}ms resultChars={}", getName(), llmName,
                System.currentTimeMillis() - t0, out != null ? out.length() : 0);
        return out;
    }

    /**
     * Stream grouping id: real thread id, or {@code adhoc-&lt;uuid&gt;} when {@code Chat} has no conversationId.
     */
    private static String streamConversationKey(String conversationId) {
        if (conversationId != null && !conversationId.isEmpty()) {
            return conversationId;
        }
        return AgentMessageStreamAppender.ADHOC_PREFIX + UUID.randomUUID().toString();
    }

    private static InfoTable emptyEntityList() throws Exception {
        return InfoTableInstanceFactory.createInfoTableFromDataShape("EntityList");
    }

    private static InfoTable emptyHierarchyNodeTable() throws Exception {
        return InfoTableInstanceFactory.createInfoTableFromDataShape("HierarchyNode_DS");
    }

    private static InfoTable emptyResolvedDocumentTable() throws Exception {
        return InfoTableInstanceFactory.createInfoTableFromDataShape("ResolvedDocument");
    }

    /**
     * Invokes {@code GetAlertPrompt} through the platform service entry so Composer overrides are honored; the call is
     * permission-checked ({@link PlatformAccess#invokeProgrammatic}).
     */
    private String invokeGetAlertPromptThroughPlatform() throws Exception {
        InfoTable raw = PlatformAccess.invokeProgrammatic(this, "GetAlertPrompt", new ValueCollection());
        if (raw == null || raw.getRowCount() == 0) {
            return "";
        }
        Object resultVal = raw.getRow(0).getValue("result");
        return resultVal != null ? resultVal.toString() : "";
    }

    /**
     * Mutable message list for the LLM turn. The workflow catalog, time guidance, taxonomy, alert, and routing
     * context are folded into the leading stable system row. Per-turn slash and host-scope rows are recorded by index
     * and removed on successful completion. {@link #historyExclusiveEndForRollback} is the message list size after
     * {@code resolveConversation} and before those injections, used to revert on failure (including new threads).
     */
    private static final class LlmTurnContext {
        final List<ChatMessage> messages;
        final int ephemeralCatalogIdx;
        final int ephemeralSlashIdx;
        final int ephemeralTimeAnchorIdx;
        /** Deprecated slot kept for wire compatibility; taxonomy is in the leading stable row ({@code -1}). */
        final int ephemeralTaxonomyIdx;
        /** Deprecated slot; alert prompt is in the leading stable row ({@code -1}). */
        final int ephemeralAlertIdx;
        /** Index of validated HostScopeJson system meta; -1 if absent. */
        final int ephemeralHostScopeIdx;
        final ChatMessage userMessageForModel;
        /** Slash-declared skill short ids in user message order (v1b checklist source). */
        final List<String> slashSkillShortNamesInOrder;
        /** Size of {@link #messages} after resolve, before per-turn injections; revert target on failure. */
        final int historyExclusiveEndForRollback;

        LlmTurnContext(List<ChatMessage> messages, int ephemeralCatalogIdx, int ephemeralSlashIdx,
                int ephemeralTimeAnchorIdx, int ephemeralTaxonomyIdx, int ephemeralAlertIdx,
                int ephemeralHostScopeIdx,
                ChatMessage userMessageForModel,
                List<String> slashSkillShortNamesInOrder,
                int historyExclusiveEndForRollback) {
            this.messages = messages;
            this.ephemeralCatalogIdx = ephemeralCatalogIdx;
            this.ephemeralSlashIdx = ephemeralSlashIdx;
            this.ephemeralTimeAnchorIdx = ephemeralTimeAnchorIdx;
            this.ephemeralTaxonomyIdx = ephemeralTaxonomyIdx;
            this.ephemeralAlertIdx = ephemeralAlertIdx;
            this.ephemeralHostScopeIdx = ephemeralHostScopeIdx;
            this.userMessageForModel = userMessageForModel;
            this.slashSkillShortNamesInOrder = slashSkillShortNamesInOrder;
            this.historyExclusiveEndForRollback = historyExclusiveEndForRollback;
        }

        /**
         * Indices of per-turn ephemeral system rows appended after {@link #historyExclusiveEndForRollback}.
         * Any index may be {@code -1} when that injection is absent. Strip helpers use this bundle to remove
         * those lines without scanning message bodies.
         */
        ParlerEphemeralSystemIndices ephemeralIndices() {
            return new ParlerEphemeralSystemIndices(
                    ephemeralCatalogIdx,
                    ephemeralSlashIdx,
                    ephemeralTimeAnchorIdx,
                    ephemeralTaxonomyIdx,
                    ephemeralAlertIdx,
                    ephemeralHostScopeIdx,
                    -1);
        }
    }

    /**
     * Builds per-turn LLM context using registered skill ids from {@link PromptContextCacheSnapshot#getSkillRegistry()},
     * parses {@code /SkillName} tokens in the raw user message, and assembles the message list (without mutating
     * stored history until the user message is appended by the caller).
     */
    private LlmTurnContext buildLlmTurnContext(String conversationId, String systemPromptOverride, String rawUserMessage,
            String userIanaTimezone, String validatedHostScopeRenderedPromptOrNull,
            ConversationRehydrateSettings rehydrateSettings) {
        if (_allowImplicitInvocation) {
            _logger.trace("[{}] allowImplicitInvocation=true (implicit skill-body injection reserved; not active yet)",
                    getName());
        }

        List<ChatMessage> messages = resolveConversation(conversationId, systemPromptOverride, null, rehydrateSettings);

        ensurePromptContextCacheForTurn();
        SkillRegistrySnapshot reg = _promptContextSnapshot != null ? _promptContextSnapshot.getSkillRegistry()
                : SkillRegistrySnapshot.empty(Instant.EPOCH);
        Set<String> validSkillShortIds = new HashSet<>(reg.descriptorsByShortId().keySet());
        PlaybookRegistrySnapshot playbookReg = _playbookRegistrySnapshot;
        if (playbookReg != null && playbookReg.isLoaded()) {
            validSkillShortIds.removeAll(playbookReg.reservedSlashIds());
        }
        SkillSlashParser.Result slash = SkillSlashParser.parse(rawUserMessage, validSkillShortIds);
        String modelFacingUserContent = resolveModelFacingUserContent(rawUserMessage, slash);
        String slashBlock = buildSlashLoadedSkillsBlock(slash.skillShortNamesInOrder());
        applyLeadingStableSystemRow(messages, systemPromptOverride);
        // After leading system: checkpoint must include that row — rehydrated lists have no leading system until here.
        int historyExclusiveEndForRollback = messages.size();

        int ephemeralCatalogIdx = -1;
        int ephemeralSlashIdx = -1;
        if (slashBlock != null && !slashBlock.isEmpty()) {
            ephemeralSlashIdx = messages.size();
            messages.add(ChatMessage.system(slashBlock));
        }
        int ephemeralTimeAnchorIdx = -1;
        int ephemeralTaxonomyIdx = -1;
        int ephemeralAlertIdx = -1;
        int ephemeralHostScopeIdx = -1;
        if (validatedHostScopeRenderedPromptOrNull != null && !validatedHostScopeRenderedPromptOrNull.isEmpty()) {
            ephemeralHostScopeIdx = messages.size();
            messages.add(ChatMessage.system(ParlerSuffixFraming.SERVER_OBSERVATIONS + "\n"
                    + validatedHostScopeRenderedPromptOrNull));
        }
        ChatMessage userForModel = ChatMessage.user(modelFacingUserContent);
        List<String> slashSkillsOrder = new ArrayList<>(slash.skillShortNamesInOrder());
        return new LlmTurnContext(messages, ephemeralCatalogIdx, ephemeralSlashIdx, ephemeralTimeAnchorIdx,
                ephemeralTaxonomyIdx, ephemeralAlertIdx, ephemeralHostScopeIdx, userForModel,
                slashSkillsOrder,
                historyExclusiveEndForRollback);
    }

    /**
     * Resolves the durable/model-facing user text after registered slash-skill parsing.
     * A registered slash-only turn retains the user's own directive rather than emitting an empty row.
     */
    static String resolveModelFacingUserContent(String rawUserMessage, SkillSlashParser.Result slash) {
        return AgentUserMessagePolicy.resolveModelFacingUserContent(rawUserMessage, slash);
    }

    /**
     * Loads Markdown bodies for {@code /SkillName} tokens from the configuration repository; missing or unloadable
     * skills log a warning and are skipped (no error to caller).
     */
    private String buildSlashLoadedSkillsBlock(List<String> skillShortNames) {
        if (skillShortNames == null || skillShortNames.isEmpty()) {
            return "";
        }
        PromptContextCacheSnapshot snapSlash = _promptContextSnapshot;
        SkillRegistrySnapshot reg = snapSlash != null ? snapSlash.getSkillRegistry() : null;
        StringBuilder sb = new StringBuilder();
        int loaded = 0;
        for (String id : skillShortNames) {
            try {
                String body = SkillRegistryLoader.loadBody(this, reg, id);
                if (loaded == 0) {
                    sb.append("## Skill instructions (from /SkillName in user message)\n\n");
                }
                sb.append(slashSkillHeading(reg, id)).append("\n\n")
                        .append(body != null ? body : "").append("\n\n");
                loaded++;
            } catch (Exception e) {
                _logger.warn("[{}] Skill /{} not loaded (no matching repository skill or invocation failed)",
                        getName(), id, e);
            }
        }
        return loaded > 0 ? ParlerSuffixFraming.SKILL_INSTRUCTIONS + "\n" + sb.toString().trim() : "";
    }

    private static String slashSkillHeading(SkillRegistrySnapshot reg, String id) {
        if (reg != null) {
            SkillRegistryDescriptor d = reg.descriptorsByShortId().get(id);
            if (d != null && d.sourceKind() == SkillSourceKind.REPOSITORY) {
                return "### `" + id + "` (source `repository`)";
            }
        }
        return "### `" + id + "` (skill id not in registry)";
    }

    /**
     * After {@code AgentLoop.run}: on success, drop per-turn framed system injections (slash and host scope).
     * On failure, revert any messages appended after {@code historyExclusiveEndForRollback}
     * (including user), for both new and continuing threads.
     */
    private static void applySkillTurnMutationFinish(List<ChatMessage> messages, LlmTurnContext turn,
            boolean loopCompletedOk) {
        if (loopCompletedOk) {
            removeEphemeralTurnSystemInjections(messages, turn);
        } else {
            revertConversationTail(messages, turn.historyExclusiveEndForRollback);
        }
    }

    /**
     * Removes per-turn system messages in descending index order so list indices stay valid. Any index {@code -1}
     * or out of range is skipped. Indices are sorted before removal so the implementation tolerates future reordering
     * or extra ephemeral injection points without hand-maintaining delete order.
     */
    private static void removeEphemeralTurnSystemInjections(List<ChatMessage> messages, LlmTurnContext turn) {
        EphemeralSystemStripHelper.stripEphemeralSystemInjectionsByIndices(messages, turn.ephemeralIndices());
    }

    /** Drops all messages appended after {@code historyExclusiveEnd} (inclusive of user + slash + ephemeral). */
    private static void revertConversationTail(List<ChatMessage> messages, int historyExclusiveEnd) {
        while (messages.size() > historyExclusiveEnd) {
            messages.remove(messages.size() - 1);
        }
    }

    /**
     * @param skillCatalogForNewThread reserved compatibility parameter; pass {@code null} — the workflow catalog is
     *                                 part of the leading stable row
     */
    private List<ChatMessage> resolveConversation(String conversationId, String systemPromptOverride,
            String skillCatalogForNewThread, ConversationRehydrateSettings rehydrateSettings) {
        if (conversationId != null && !conversationId.isEmpty()
                && _conversations.containsKey(conversationId)) {
            return _conversations.get(conversationId);
        }

        final String prefix = "[" + getName() + "]";
        if (conversationId != null && !conversationId.isEmpty() && rehydrateSettings.isStreamRehydrationEnabled()) {
            try {
                ConversationMetadata meta =
                        AgentThreadDataTableSupport.loadConversationMetadataForCurrentUser(conversationId, prefix);
                if (!getName().equals(meta.getAgentName())) {
                    _logger.warn("{} Stream rehydrate skipped: thread agentName '{}' does not match this Thing {}",
                            prefix, meta.getAgentName(), getName());
                    return freshThreadForConversation(conversationId, systemPromptOverride,
                            skillCatalogForNewThread);
                }
                Optional<AgentConversationRehydrator.Rehydrated> rebuilt =
                        AgentConversationRehydrator.rehydrateTranscript(conversationId, getName(), meta,
                                rehydrateSettings, _logger);
                if (rebuilt.isPresent()) {
                    List<ChatMessage> list = rebuilt.get().messages();
                    if (!list.isEmpty()) {
                        _conversations.putIfAbsent(conversationId, list);
                        List<ChatMessage> published = _conversations.get(conversationId);
                        // Only the list that won publication may decide the working checkpoint: putIfAbsent can
                        // lose, and JVM working state must describe the transcript the model will actually see.
                        if (published == list) {
                            rebuilt.get().reconcileWorkingCheckpoint(conversationId, getName());
                        }
                        return published;
                    }
                }
            } catch (Exception e) {
                _logger.warn("{} Stream rehydrate skipped: {}", prefix, e.getMessage());
            }
        }

        return freshThreadForConversation(conversationId, systemPromptOverride, skillCatalogForNewThread);
    }

    /**
     * The single fresh-thread exit for {@link #resolveConversation}.
     *
     * <p>Every route that reaches it — a query or mapping failure, an empty shaped result, an agent mismatch,
     * rehydrate disabled — publishes no checkpoint, so the JVM working entry must not survive it. Routing them
     * through one method makes that a property of the code rather than of remembering it at four returns.
     */
    private List<ChatMessage> freshThreadForConversation(String conversationId, String systemPromptOverride,
            String skillCatalogForNewThread) {
        AgentConversationRehydrator.reconcileForFreshThread(getName(), conversationId);
        return newThreadSeedMessages(systemPromptOverride, skillCatalogForNewThread);
    }

    private List<ChatMessage> newThreadSeedMessages(String systemPromptOverride, String skillCatalogForNewThread) {
        List<ChatMessage> messages = new ArrayList<>();
        String prompt = (systemPromptOverride != null && !systemPromptOverride.isEmpty())
                ? systemPromptOverride : _systemPrompt;
        String forLlm = LlmRoutingGuide.composeSystemPrompt(prompt, _appendBuiltInToolRoutingGuide);
        if (skillCatalogForNewThread != null && !skillCatalogForNewThread.isEmpty()) {
            if (forLlm != null && !forLlm.isEmpty()) {
                forLlm = forLlm + "\n\n---\n" + skillCatalogForNewThread;
            } else {
                forLlm = skillCatalogForNewThread;
            }
        }
        if (forLlm != null && !forLlm.isEmpty()) {
            messages.add(ChatMessage.system(forLlm));
        }
        return messages;
    }

    private void fireAgentResponseEvent(String conversationId, AgentLoop.AgentResult result) {
        try {
            // Fire under the same SecurityContext as the async worker (initiating user), not superuser.
            TransactionFactory.beginTransactionRequired();

            ValueCollection eventData = new ValueCollection();
            eventData.put("conversationId", new StringPrimitive(conversationId));
            String responsePayload = result.getContent() != null ? result.getContent() : "";
            if (result.getStatus() == AgentLoop.AgentResult.Status.AWAITING_APPROVAL
                    && result.getApprovalPendingId() != null) {
                responsePayload = result.getApprovalPendingId();
            }
            eventData.put("response", new StringPrimitive(responsePayload));
            eventData.put("status", new StringPrimitive(result.getStatus().name()));
            eventData.put("iterations", new IntegerPrimitive(result.getIterations()));
            eventData.put("promptTokens", new IntegerPrimitive(result.getPromptTokens()));
            eventData.put("completionTokens", new IntegerPrimitive(result.getCompletionTokens()));
            if (result.getErrorCode() != null && !result.getErrorCode().isEmpty()) {
                eventData.put("errorCode", new StringPrimitive(result.getErrorCode()));
            }

            EventDefinition eventDef = (EventDefinition) getInstanceEventDefinitions().get("AgentResponseEvent");
            if (eventDef != null) {
                fireEvent(eventDef, new DateTime(), eventData);
            }

            ThreadLocalContext.setTransactionSuccess(true);
        } catch (Exception e) {
            try { TransactionFactory.failure(); } catch (Exception ex) { /* consumed */ }
            _logger.error("[{}] Error firing AgentResponseEvent: {}", getName(), e.getMessage(), e);
        } finally {
            TransactionFactory.endTransactionRequired();
            if (ThreadLocalContext.getTransactionSuccessStatus()) {
                try { ThreadLocalContext.dispatchQueuedEvents(); } catch (Exception e) { /* consumed */ }
            }
        }
    }

    private static String latestUserGoalFromActiveMessages() {
        List<ChatMessage> msgs = AgentToolContext.getParlerActiveMessages();
        if (msgs == null) {
            return "";
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessage m = msgs.get(i);
            if (m.getRole() == ChatMessage.Role.USER && m.getContent() != null && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return "";
    }

    @FunctionalInterface
    private interface PlaybookSlashDownlink {
        void send(String assistantText, String assistantMessageId, StreamTokenUsage playbookLlmUsage) throws Exception;
    }

    private static final class PlaybookSlashEarly {
        final String assistantText;
        final StreamTokenUsage playbookLlmUsage;

        private PlaybookSlashEarly(String assistantText, StreamTokenUsage playbookLlmUsage) {
            this.assistantText = assistantText != null ? assistantText : "";
            this.playbookLlmUsage = playbookLlmUsage != null ? playbookLlmUsage : StreamTokenUsage.ZERO;
        }
    }

    private static final class PlaybookSlashTurnFinish {
        final String assistantText;
        final StreamTokenUsage playbookLlmUsage;

        private PlaybookSlashTurnFinish(String assistantText, StreamTokenUsage playbookLlmUsage) {
            this.assistantText = assistantText != null ? assistantText : "";
            this.playbookLlmUsage = playbookLlmUsage != null ? playbookLlmUsage : StreamTokenUsage.ZERO;
        }
    }

    /**
     * Persists user + assistant rows to stream/history and strips per-turn ephemeral system injections
     * (shared by sync {@code Chat} structured slash and AlwaysOn direct slash).
     */
    private PlaybookSlashTurnFinish finishPlaybookSlashTurn(
            String userMessage,
            String assistantText,
            String conversationId,
            String systemPrompt,
            String userIanaTimezone,
            String hostContext,
            ConversationRehydrateSettings rehydrateSettings,
            StreamTokenUsage playbookLlmUsage,
            String hostContextSnapshotJsonOrNull,
            PlaybookSlashDownlink downlink) throws Exception {
        StreamTokenUsage assistantUsage = playbookLlmUsage != null ? playbookLlmUsage : StreamTokenUsage.ZERO;
        LlmTurnContext turn = buildLlmTurnContext(conversationId, systemPrompt, userMessage, userIanaTimezone,
                hostContext, rehydrateSettings);
        List<ChatMessage> messages = turn.messages;
        final String streamKey = streamConversationKey(conversationId);
        final String streamSource = streamKey;
        final String agentName = getName();
        PlaybookSlashTurnFinalize.SlashTurnPersistence persisted = PlaybookSlashTurnFinalize.applySlashTurnPersistence(
                messages, turn.userMessageForModel, userMessage, assistantText, turn.ephemeralIndices());
        String assistantMessageId = UUID.randomUUID().toString();
        for (ChatMessage streamRow : persisted.streamRows()) {
            if (streamRow.getRole() == ChatMessage.Role.ASSISTANT) {
                AgentMessageStreamAppender.append(streamKey, streamRow, streamSource, agentName, assistantUsage,
                        assistantMessageId);
            } else if (streamRow.getRole() == ChatMessage.Role.USER) {
                AgentMessageStreamAppender.append(streamKey, streamRow, streamSource, agentName, StreamTokenUsage.ZERO,
                        null, hostContextSnapshotJsonOrNull);
                if (hostContextSnapshotJsonOrNull != null && conversationId != null && !conversationId.isEmpty()) {
                    _hostContextTurnCarry.recordAfterUserRow(conversationId, hostContextSnapshotJsonOrNull);
                }
            } else {
                AgentMessageStreamAppender.append(streamKey, streamRow, streamSource, agentName, StreamTokenUsage.ZERO);
            }
        }
        if (downlink != null) {
            downlink.send(assistantText, assistantMessageId, assistantUsage);
        }
        if (conversationId != null && !conversationId.isEmpty()) {
            _conversations.put(conversationId, messages);
        }
        return new PlaybookSlashTurnFinish(assistantText, assistantUsage);
    }

    private String playbookConversationKey(String conversationId) {
        if (conversationId != null && !conversationId.isEmpty()) {
            return conversationId;
        }
        return "single-turn";
    }

    /**
     * Binds the tool/playbook context required before {@link #tryExecutePlaybookSlashTurn} so Slice D
     * {@code task.state} can flush on AlwaysOn (and tool execution sees conversation/agent/host scope).
     */
    private void bindPlaybookSlashTurnContext(
            String conversationId,
            String requestId,
            String remoteThingName,
            Thing remoteConversation,
            String userIanaTimezone,
            String hostContext,
            AtomicBoolean downlinkOkOrNull) {
        AgentToolContext.setConversationId(conversationId);
        AgentToolContext.setAgentThing(this);
        AgentToolContext.setUserIanaTimezone(userIanaTimezone);
        AgentToolContext.setHostContextJson(null);
        PlaybookParlerStreamBindings.bindForPlaybookSlash(requestId, remoteThingName, remoteConversation,
                downlinkOkOrNull);
        AgentToolContext.resetTabularChartRound();
    }

    /**
     * Structured playbook slash path (zero routing LLM). Returns {@code null} when not a playbook slash.
     */
    private PlaybookSlashEarly tryExecutePlaybookSlashTurn(String message, String conversationId, String hostContext)
            throws Exception {
        ensurePromptContextCacheForTurn();
        PlaybookRegistrySnapshot reg = _playbookRegistrySnapshot;
        if (reg == null || !reg.isLoaded()) {
            return null;
        }
        PlaybookSlashParser.Result slash = PlaybookSlashParser.parse(message, reg.reservedSlashIds());
        if (slash.kind() == PlaybookSlashParser.Kind.NONE) {
            return null;
        }
        if (slash.kind() == PlaybookSlashParser.Kind.FREE_TEXT) {
            return new PlaybookSlashEarly(slash.clarificationMessage(), StreamTokenUsage.ZERO);
        }
        String convKey = playbookConversationKey(conversationId);
        if (PlaybookActiveRunTracker.hasActive(convKey)) {
            PlaybookActiveRunTracker.cancel(convKey);
        }
        PlaybookRunResult result = executePlaybookRun(slash.playbookId(), slash.params(), message, convKey, hostContext);
        return new PlaybookSlashEarly(result.assistantText(), result.llmUsage());
    }

    private String executeStartPlaybookTool(ToolCall toolCall) throws Exception {
        if (Boolean.TRUE.equals(PLAYBOOK_TOOL_CALLED_THIS_TURN.get())) {
            return new JSONObject()
                    .put("status", "error")
                    .put("code", "PLAYBOOK_ALREADY_STARTED")
                    .put("message", "start_playbook may only be called once per turn.")
                    .toString();
        }
        PLAYBOOK_TOOL_CALLED_THIS_TURN.set(true);
        PlaybookRegistrySnapshot reg = _playbookRegistrySnapshot;
        if (reg == null || !reg.isLoaded()) {
            return new JSONObject()
                    .put("status", "error")
                    .put("code", "PLAYBOOK_REGISTRY_UNAVAILABLE")
                    .put("message", "Playbook registry is not loaded.")
                    .toString();
        }
        JSONObject args = new JSONObject(toolCall.getArguments() != null ? toolCall.getArguments() : "{}");
        String playbookId = args.optString("playbook_id", "");
        JSONObject params = args.optJSONObject("params");
        if (params == null) {
            params = new JSONObject();
        }
        String convKey = playbookConversationKey(AgentToolContext.getConversationId());
        if (PlaybookActiveRunTracker.hasActive(convKey)) {
            PlaybookActiveRunTracker.cancel(convKey);
        }
        PlaybookRunResult result = executePlaybookRun(playbookId, params,
                latestUserGoalFromActiveMessages(), convKey,
                AgentToolContext.getHostContextJson());
        if (result.status() == PlaybookRunResult.Status.COMPLETED) {
            String assistantText = result.assistantText();
            if (assistantText != null && !assistantText.isBlank()) {
                AgentToolContext.setPlaybookTerminalAnswer(assistantText);
            }
        }
        JSONObject out = new JSONObject();
        out.put("status", result.status().name().toLowerCase(Locale.ROOT));
        out.put("assistantText", result.assistantText());
        if (result.failureCode() != null) {
            out.put("failureCode", result.failureCode());
        }
        return out.toString();
    }

    private PlaybookRunResult executePlaybookRun(
            String playbookId,
            JSONObject params,
            String userGoal,
            String conversationKey,
            String hostContext) throws Exception {
        PlaybookRegistrySnapshot reg = _playbookRegistrySnapshot;
        if (reg == null || !reg.isLoaded()) {
            return new PlaybookRunResult(PlaybookRunResult.Status.FAILED,
                    "Playbook registry is not available.", "PLAYBOOK_REGISTRY_UNAVAILABLE", 0, 0, 0);
        }
        PlaybookDocument doc = reg.document(playbookId);
        if (doc == null) {
            return new PlaybookRunResult(PlaybookRunResult.Status.FAILED,
                    "Unknown playbook id: " + playbookId, "PLAYBOOK_NOT_FOUND", 0, 0, 0);
        }
        ensurePromptContextCacheForTurn();
        List<TaxonomyRow> taxRows = _promptContextSnapshot != null
                ? _promptContextSnapshot.getTaxonomyRows() : List.of();
        PlaybookToolExecutor executor = (tool, jsonArgs, infotables) -> {
            AgentToolContext.setAgentThing(this);
            if (hostContext != null) {
                AgentToolContext.setHostContextJson(null);
            }
            return executePlaybookToolCall(tool, jsonArgs, infotables);
        };
        LlmClient llm = llmClientForTurn();
        PlaybookArtifactEmitter artifactEmitter = this::emitPlaybookInternalArtifactsForTool;
        PlaybookRunResult result = PlaybookRunner.run(doc, playbookId, params, userGoal, conversationKey, taxRows, executor, llm,
                _temperature, _maxTokens, artifactEmitter);
        if (result.runOutcome() != null) {
            _lastPlaybookRunOutcomeJson = result.runOutcome().toString();
        }
        return result;
    }

    /**
     * Persists one internal Playbook tool row (with LLM-rehydration skip marker) and emits chart/table/tabular wire
     * for the active AlwaysOn {@code request_id}.
     */
    private void emitPlaybookInternalArtifactsForTool(String playbookId, String nodeId, String toolName,
            String toolCallId, String envelopeJson) {
        String rid = AgentToolContext.getParlerRequestId();
        String cid = AgentToolContext.getParlerRemoteThingName();
        if (!PlaybookInternalArtifactEmissionOrder.hasParlerStreamWireIds(rid, cid)) {
            return;
        }
        Thing rem = AgentToolContext.getParlerRemoteConversation();
        AtomicBoolean ok = AgentToolContext.getParlerDownlinkOk();
        String conv = AgentToolContext.getConversationId();
        String streamKey = streamConversationKey(conv);
        String streamSource = streamKey;
        try {
            ChatMessage mOrig = ChatMessage.toolResult(toolCallId, envelopeJson, toolName);
            ChatMessage mPersistAug = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(mOrig, rem, rid, cid);
            JSONObject j = new JSONObject(mPersistAug.getContent());
            j.put(ParlerPlaybookArtifactWireConstants.OMIT_FROM_LLM_REHYDRATE_JSON_KEY, true);
            if (nodeId != null && !nodeId.isEmpty()) {
                j.put(ParlerPlaybookArtifactWireConstants.PLAYBOOK_NODE_ID_JSON_KEY, nodeId);
            }
            ChatMessage forPersist = ChatMessage.toolResult(toolCallId, j.toString(), toolName);
            // Durable Stream row first (matches top-level TOOL path): replay must survive live downlink loss.
            PlaybookInternalArtifactEmissionOrder.persistThenMaybeEmitParlerWire(
                    () -> AgentMessageStreamAppender.append(streamKey, forPersist, streamSource, getName(),
                            StreamTokenUsage.ZERO),
                    () -> PlaybookInternalArtifactEmissionOrder.shouldEmitLiveParlerWire(rem, ok),
                    () -> {
                        String stripped = ParlerPlaybookArtifactWireConstants.stripInternalWireMetadataForArtifactHydration(
                                forPersist.getContent());
                        ChatMessage forWireResolve = ChatMessage.toolResult(toolCallId, stripped, toolName);
                        ChatMessage mUi = FetchCachedStreamLaneHelper.resolveToolMessageForParlerTableDownlinks(mOrig,
                                forWireResolve, rem, rid, cid);
                        ParlerToolArtifactWireEmitter.emitAfterResolvedToolUi(rem, rid, cid, mUi, ok, mOrig);
                    });
            _logger.info(
                    "PLAYBOOK_ARTIFACT_WIRE internalTool playbookId={} nodeId={} tool={} toolCallId={} parlerChartWireEmittedCount={}",
                    playbookId, nodeId, toolName, toolCallId, AgentToolContext.parlerChartWireEmittedCountForTurnPerf());
        } catch (Exception e) {
            _logger.warn("[{}] PLAYBOOK_ARTIFACT_WIRE persist/emit failed playbookId={} tool={}: {}",
                    getName(), playbookId, toolName, e.getMessage());
        }
    }

    private PlaybookToolExecutionResult executePlaybookToolCall(String tool, JSONObject jsonArgs,
            Map<String, InfoTable> infotableArgs) throws Exception {
        Map<String, InfoTable> tables = infotableArgs != null ? infotableArgs : Map.of();
        ExtendedToolRegistrySnapshot extSnap = _promptContextSnapshot != null
                ? _promptContextSnapshot.getExtendedToolRegistry() : ExtendedToolRegistrySnapshot.missing();
        PlaybookInfotableBindingPolicy.rejectInfotableArgsUnlessExtendedTool(tool, extSnap, tables);
        Optional<ExtendedToolDefinition> extOpt = extSnap.find(tool);
        if (extOpt.isPresent()) {
            ExtendedToolDefinition ext = extOpt.get();
            if (!ServiceCapabilityRuntimePolicy.isPlaybookEligible(ext)) {
                throw new PlaybookRunException("extended tool is not playbook-safe: " + tool,
                        "PLAYBOOK_TOOL_NOT_PLAYBOOK_SAFE");
            }
            Optional<String> blocked = ServiceCapabilityRuntimePolicy.executionBlockReason(ext);
            if (blocked.isPresent()) {
                throw new PlaybookRunException(
                        "extended tool blocked by capability policy: " + blocked.get(),
                        ServiceCapabilityRuntimePolicy.BLOCK_CODE);
            }
            RootEntity ent = PlatformAccess.findAsUser(ext.resolvedTargetThingName(),
                    RelationshipTypes.ThingworxRelationshipTypes.Thing);
            if (!(ent instanceof Thing)) {
                throw new Exception("Extended tool target Thing not found for playbook tool: " + tool);
            }
            Thing targetThing = (Thing) ent;
            ServiceDefinition sd = CustomToolHarvester.findServiceDefinition(targetThing, ext.serviceName());
            if (sd == null) {
                throw new Exception("Service metadata unavailable for playbook extended tool: " + tool);
            }
            PlaybookInfotableBindingPolicy.validateInfotableParameterShapes(sd, tables);
            JsonNode root = JSON.readTree(jsonArgs.toString());
            String preflight = ExtendedToolThingnamePreflight.checkJsonArgs(sd, root);
            if (preflight != null) {
                return PlaybookToolExecutionResult.jsonOnly(preflight);
            }
            ValueCollection params = CustomToolHarvester.buildValueCollectionForPlaybookExtendedTool(targetThing,
                    ext.serviceName(), root, tables);
            InfoTable raw = PlatformAccess.invokeAsUser(targetThing, ext.serviceName(), params);
            String envelope = InvokeServiceExecutor.formatDirectServiceResultForLlm(raw, sd, targetThing);
            InfoTable storeRaw = null;
            FieldDefinition rt = sd.getResultType();
            if (rt != null && rt.getBaseType() == BaseTypes.INFOTABLE) {
                storeRaw = raw;
            }
            return PlaybookToolExecutionResult.withRawTable(envelope, storeRaw, "pb-" + UUID.randomUUID());
        }
        ToolCall tc = new ToolCall("pb-" + UUID.randomUUID(), tool, jsonArgs.toString());
        return PlaybookToolExecutionResult.jsonOnly(executeToolCall(tc), tc.getId());
    }

    private Object lockForConversation(String conversationId) {
        return parlerConversationLock(conversationId);
    }

    private static String escapeJsonString(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static StreamTokenUsage mergeAgentLoopUsageWithTurnPerf(AgentLoop.AgentResult result) {
        if (result == null) {
            return StreamTokenUsage.ZERO;
        }
        return StreamTokenUsage.withPerformanceWireOverlay(
                result.getLastSuccessfulAssistantRound(), result.getLlmTurnPerformanceWireJson());
    }
}
