package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.compaction.ContextBudgetPlanner;
import com.thingworx.things.agent.compaction.LlmReplayCompactionGate;
import com.thingworx.things.agent.compaction.LlmToolResultMatrixSealer.SealStats;
import com.thingworx.things.agent.compaction.LlmToolResultReplayCompactor;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageTelemetry;
import com.thingworx.things.agent.llm.LlmUsageTelemetry.LlmTurnPerformanceFields;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.DocumentCoverageSummaryInjector;
import com.thingworx.things.agent.llm.EmptyFinalAnswerRetryInjector;
import com.thingworx.things.agent.llm.LlmUtcClockInjector;
import com.thingworx.things.agent.llm.LlmResponseShapeDiagnostics;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.ratecontrol.RateControlStatusSink;
import com.thingworx.things.agent.llm.usage.LlmCallContext;
import com.thingworx.things.agent.llm.usage.LlmCallContextPlanSnapshot;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;
import com.thingworx.things.agent.playbook.PlaybookTerminalHandoff;
import com.thingworx.things.agent.taskstate.AgentTaskState;
import com.thingworx.things.agent.taskstate.AgentTaskStateRenderer;
import com.thingworx.things.agent.taskstate.TaskStateLlmInjector;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.ApprovalPendingException;
import com.thingworx.things.agent.tools.PendingApprovalRecord;
import com.thingworx.things.agent.tools.PendingApprovalStore;
import com.thingworx.things.agent.tools.CachedTabularGroupMetricExecutor;
import com.thingworx.things.agent.tools.ConsecutiveIdenticalToolCallRegistry;
import com.thingworx.things.agent.tools.DocumentSearchProgressGuardRegistry;
import com.thingworx.things.agent.tools.FetchCachedReplayGuard;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.things.agent.tools.PresentationArtifactRegistry;
import com.thingworx.things.agent.tools.TabularChartRoundHooks;
import com.thingworx.things.agent.tools.TabularChartRoundState;
import com.thingworx.things.agent.tools.TabularCompleteAnswerSetDetector;
import com.thingworx.things.agent.tools.ToolExecutor;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.things.agent.ToolResultEgressGateway.EgressResult;

/**
 * The core agentic loop: LLM → tool call → execute → feed result back → repeat.
 *
 * This is the central differentiator from simple LLM wrappers.
 * The loop continues until:
 *   1. The LLM returns a final text response (no tool calls)
 *   2. Max iterations reached (safety limit)
 *   3. Total timeout exceeded
 *   4. An unrecoverable error occurs
 *
 * Execution flow:
 *   Developer calls Chat Service
 *     → AgentLoop.run()
 *       → LlmClient.chat(LlmChatRequest)
 *         → LLM decides to call a tool
 *           → toolExecutor.execute(toolCall) (built-in tools, configuration-repository extended tools, or registry)
 *             → Direct ThingWorx Java API call (zero HTTP overhead)
 *               → Result injected back as tool message
 *                 → LlmClient.chat(LlmChatRequest with messages + toolResult)
 *                   → ... repeat until final response
 *     → Return final response string
 */
public class AgentLoop {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(AgentLoop.class);

    private static final ObjectMapper LOOP_JSON = new ObjectMapper();

    /** Max {@code build_chart_from_tabular_result} dispatches per Answer Presentation Phase batch. */
    private static final int PRESENTATION_ACTION_LIMIT = 6;

    private static final String[] LONG_ANSWER_PROMPT_MARKERS = {
            "detailed report", "explain thoroughly", "write a report", "export", "full narrative"
    };

    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int maxIterations;
    private final long timeoutMs;
    /** Optional live UI downlink for provider rate-control waits ({@code docs/agent/rate-control-ui-status.md}). */
    private final RateControlStatusSink rateControlStatusSink;
    /** Upper bound on model-facing request characters for context planning ({@code docs/agent/context-compaction.md}). */
    private final int llmContextMaxChars;
    /** Optional per-round filter (e.g. document-turn tool narrowing — {@code docs/operations/doc-index-enhance.md} D2). */
    private final ToolDefinitionsRoundFilter toolDefinitionsRoundFilter;
    /** Canonical user IANA timezone captured at turn construction; never read from thread-local state by the clock. */
    private final String userIanaTimezone;
    /** Stats from the last completed tool batch seal; applied to the next {@code LLM_USAGE} line. */
    private SealStats pendingReplayStats = SealStats.EMPTY;

    /** Narrows or adjusts the merged tool list for one agent-loop iteration. */
    @FunctionalInterface
    public interface ToolDefinitionsRoundFilter {
        List<ToolDefinition> apply(List<ToolDefinition> mergedForRound, int iterationOneBased);
    }

    public AgentLoop(LlmClient llmClient, ToolExecutor toolExecutor,
                     String model, double temperature, int maxTokens,
                     int maxIterations, long timeoutMs) {
        this(llmClient, toolExecutor, model, temperature, maxTokens, maxIterations, timeoutMs, null, 750_000);
    }

    public AgentLoop(LlmClient llmClient, ToolExecutor toolExecutor,
                     String model, double temperature, int maxTokens,
                     int maxIterations, long timeoutMs,
                     RateControlStatusSink rateControlStatusSink) {
        this(llmClient, toolExecutor, model, temperature, maxTokens, maxIterations, timeoutMs,
                rateControlStatusSink, 750_000);
    }

    public AgentLoop(LlmClient llmClient, ToolExecutor toolExecutor,
                     String model, double temperature, int maxTokens,
                     int maxIterations, long timeoutMs,
                     RateControlStatusSink rateControlStatusSink, int llmContextMaxChars) {
        this(llmClient, toolExecutor, model, temperature, maxTokens, maxIterations, timeoutMs,
                rateControlStatusSink, llmContextMaxChars, null, null);
    }

    public AgentLoop(LlmClient llmClient, ToolExecutor toolExecutor,
                     String model, double temperature, int maxTokens,
                     int maxIterations, long timeoutMs,
                     RateControlStatusSink rateControlStatusSink, int llmContextMaxChars,
                     ToolDefinitionsRoundFilter toolDefinitionsRoundFilter) {
        this(llmClient, toolExecutor, model, temperature, maxTokens, maxIterations, timeoutMs,
                rateControlStatusSink, llmContextMaxChars, toolDefinitionsRoundFilter, null);
    }

    public AgentLoop(LlmClient llmClient, ToolExecutor toolExecutor,
                     String model, double temperature, int maxTokens,
                     int maxIterations, long timeoutMs,
                     RateControlStatusSink rateControlStatusSink, int llmContextMaxChars,
                     ToolDefinitionsRoundFilter toolDefinitionsRoundFilter,
                     String userIanaTimezone) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.maxIterations = maxIterations;
        this.timeoutMs = timeoutMs;
        this.rateControlStatusSink = rateControlStatusSink;
        this.llmContextMaxChars = normalizeLlmContextMaxChars(llmContextMaxChars);
        this.toolDefinitionsRoundFilter = toolDefinitionsRoundFilter;
        this.userIanaTimezone = userIanaTimezone;
    }

    private static int normalizeLlmContextMaxChars(int v) {
        if (v <= 0) {
            return 750_000;
        }
        return Math.min(2_000_000, Math.max(10_000, v));
    }

    /**
     * Post-marker round tool list: normally empty (tool_choice none); when chart-rescue eligibility holds (model
     * attempted {@code build_chart_from_tabular_result}, hit a recoverable chart error, no chart wire was emitted, and
     * a chartable tabular source is available), retain {@code build_chart_from_tabular_result} so the LLM can retry
     * after aggregation ({@code docs/agent/prompt-to-chart.md} §7.5). When rescue does not apply,
     * the same singleton may be offered for the Answer Presentation Phase (aggregate-first complete tabular artifacts;
     * {@code docs/agent/multi-chart-and-thrashing-safeguards.md}).
     */
    static List<ToolDefinition> postMarkerToolDefinitions(List<ToolDefinition> all) {
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        if (AgentToolContext.eligibleChartRescueToolExposureForTurn()) {
            for (ToolDefinition def : all) {
                if (def != null && "build_chart_from_tabular_result".equals(def.getName())) {
                    return Collections.singletonList(def);
                }
            }
            return Collections.emptyList();
        }
        if (AgentToolContext.eligibleAnswerPresentationPhasePostMarkerExposure()) {
            for (ToolDefinition def : all) {
                if (def != null && "build_chart_from_tabular_result".equals(def.getName())) {
                    return Collections.singletonList(def);
                }
            }
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    /**
     * Merge {@code toolProtocolViolation} strings; non-empty {@code incoming} replaces {@code current}
     * ({@code docs/agent/llm-performance.md} — only {@code tool_call_after_tool_none} is used today).
     */
    static String mergeToolProtocolViolation(String current, String incoming) {
        if (incoming != null && !incoming.isEmpty()) {
            return incoming;
        }
        return current != null ? current : "";
    }

    /** Emits content-free diagnostics for the only provider response shape that this loop rejects. */
    private static void logEmptyFinalResponse(LlmUsageWireIds wireIds, LlmResponse response, int iteration,
            int retryAttempt, String terminalAction) {
        LlmResponseShapeDiagnostics shape = response.getResponseShapeDiagnostics();
        int contentBlockCount = shape != null ? shape.getContentBlockCount() : -1;
        int textBlockCount = shape != null ? shape.getTextBlockCount() : -1;
        int toolUseBlockCount = shape != null ? shape.getToolUseBlockCount() : -1;
        int otherBlockCount = shape != null ? shape.getOtherBlockCount() : -1;
        String otherBlockTypes = shape != null ? shape.getOtherBlockTypes().toString() : "[]";
        String content = response.getContent();
        int textChars = content != null ? content.length() : 0;
        boolean whitespaceOnly = content != null && !content.isEmpty() && content.isBlank();
        LOG.warn("LLM_EMPTY_FINAL_RESPONSE providerThingName={} apiShapeId={} model={} providerRequestId={} "
                        + "parlerRequestId={} round={} finishReason={} outputTokens={} textChars={} "
                        + "textWhitespaceOnly={} toolCallCount=0 contentBlockCount={} textBlockCount={} "
                        + "toolUseBlockCount={} otherBlockCount={} otherBlockTypes={} retryAttempt={} terminalAction={}",
                wireIds != null ? wireIds.getProviderThingName() : "",
                wireIds != null ? wireIds.getApiShapeId() : "",
                wireIds != null ? wireIds.getModel() : "",
                response.getProviderRequestId(), AgentToolContext.getParlerRequestId(), iteration,
                response.getFinishReason(), response.getOutputTokens(), textChars, whitespaceOnly,
                contentBlockCount, textBlockCount, toolUseBlockCount, otherBlockCount, otherBlockTypes,
                retryAttempt, terminalAction);
    }

    /**
     * One end-of-turn singleton {@code build_chart_from_tabular_result} round after the model returned prose
     * without tool calls, when chart-rescue eligibility holds ({@code docs/agent/prompt-to-chart.md} §7.6).
     */
    private static boolean eligibleSingletonChartRescueAfterProse(List<ToolDefinition> toolDefinitions,
            boolean chartEndTurnRescueAttemptedLocal) {
        if (chartEndTurnRescueAttemptedLocal) {
            return false;
        }
        if (!AgentToolContext.eligibleChartRescueToolExposureForTurn()) {
            return false;
        }
        if (postMarkerToolDefinitions(toolDefinitions).isEmpty()) {
            return false;
        }
        if (!AgentToolContext.isCompleteAnswerSetSeenThisTurn()) {
            TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
            if (!st.isLastChartRescueDataCompleteEnough()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Requested max-output budget for one LLM round ({@code docs/agent/llm-performance.md} §5).
     * <p>
     * {@code AgentLoop} does not impose a hard-coded routing/post-marker default or
     * cap. {@code AgentSettings.maxTokens = -1} delegates to the Provider setting; positive values override. The
     * boolean parameters document call-site intent only.
     */
    @SuppressWarnings("unused")
    static int resolveEffectiveMaxOutputTokensForRound(boolean longAnswerPrompt, boolean postMarkerNoToolRound,
            boolean routingRoundWithTools, int agentConfiguredMax) {
        return agentConfiguredMax;
    }

    static boolean isLongAnswerUserPrompt(String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) {
            return false;
        }
        String lc = userMessage.toLowerCase();
        for (String m : LONG_ANSWER_PROMPT_MARKERS) {
            if (lc.contains(m)) {
                return true;
            }
        }
        return false;
    }

    static String evaluateFirstReaderCacheHit(List<String> cacheIdsAtLoopStart, ToolCall tc) {
        if (tc == null) {
            return null;
        }
        String name = tc.getFunctionName();
        if (!"tabulate_cached_result".equals(name) && !"fetch_cached_result".equals(name)
                && !"summarize_cached_result".equals(name)) {
            return null;
        }
        String id = jsonStringField(tc.getArguments(), "cacheId");
        if (id.isEmpty()) {
            return "unknown";
        }
        return cacheIdsAtLoopStart != null && cacheIdsAtLoopStart.contains(id) ? "true" : "false";
    }

    private static String jsonStringField(String json, String field) {
        try {
            JsonNode n = LOOP_JSON.readTree(json == null || json.isBlank() ? "{}" : json);
            return n.path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * When {@code build_chart_from_tabular_result} returns a recoverable chart-construction error in JSON, record
     * turn state for post-marker / end-of-turn rescue.
     */
    private static void noteRecoverableChartFailureFromBuildChartResult(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return;
        }
        try {
            JsonNode n = LOOP_JSON.readTree(resultJson);
            if (!"error".equals(n.path("status").asText(""))) {
                return;
            }
            if ("DUPLICATE_SLICE_LABEL".equals(n.path("code").asText(""))) {
                AgentToolContext.markChartBuildFailedRecoverablyThisTurn();
            }
        } catch (Exception ignored) {
            // ignore malformed tool JSON
        }
    }

    private static String lastUserMessageText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.getRole() == ChatMessage.Role.USER) {
                String c = m.getContent();
                return c != null ? c : "";
            }
        }
        return "";
    }

    /**
     * Run the agent loop with the given conversation history and tool definitions.
     *
     * @param messages mutable list of conversation messages (will be appended to)
     * @param toolDefinitions merged list of built-in + custom tools to send to the LLM
     * @return the agent's final text response
     */
    public AgentResult run(List<ChatMessage> messages, List<ToolDefinition> toolDefinitions) throws Exception {
        return run(messages, toolDefinitions, null);
    }

    /**
     * @param streamAppender if non-null, called for each assistant (tool_calls) and tool message appended during the loop,
     *                       with token usage for that LLM round (assistant rows) or {@link StreamTokenUsage#ZERO} for tools
     */
    public AgentResult run(List<ChatMessage> messages, List<ToolDefinition> toolDefinitions,
            BiConsumer<ChatMessage, StreamTokenUsage> streamAppender) throws Exception {
        return runWithPendingSource(messages, toolDefinitions, streamAppender, null, null);
    }

    /** Explicit same-request continuation. Ordinary runs never derive executable sources from transcript content. */
    AgentResult runAfterApproval(List<ChatMessage> messages, List<ToolDefinition> toolDefinitions,
            BiConsumer<ChatMessage, StreamTokenUsage> streamAppender, PendingApprovalRecord pending,
            String completedGatedResult) throws Exception {
        return runWithPendingSource(messages, toolDefinitions, streamAppender, pending, completedGatedResult);
    }

    private AgentResult runWithPendingSource(List<ChatMessage> messages, List<ToolDefinition> toolDefinitions,
            BiConsumer<ChatMessage, StreamTokenUsage> streamAppender, PendingApprovalRecord pending,
            String completedGatedResult) throws Exception {
        AtomicBoolean skipFetchGuardEndTurn = new AtomicBoolean(false);
        try {
            return runInner(messages, toolDefinitions, streamAppender, skipFetchGuardEndTurn,
                    pending, completedGatedResult);
        } finally {
            if (!skipFetchGuardEndTurn.get()) {
                endFetchCachedReplayGuardTurn();
                String tk = FetchCachedReplayGuard.resolveCurrentTurnKey();
                ConsecutiveIdenticalToolCallRegistry.removeTurn(tk);
                DocumentSearchProgressGuardRegistry.removeTurn(tk);
                PresentationArtifactRegistry.removeTurn(tk);
            }
        }
    }

    private static LlmCallContextPlanSnapshot planSnapshotFrom(ContextBudgetPlanner.Metrics metrics) {
        if (metrics == null) {
            return new LlmCallContextPlanSnapshot(Collections.emptyMap());
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("transcriptChars", metrics.transcriptChars);
        fields.put("evidenceRawChars", metrics.evidenceRawChars);
        fields.put("historyBudgetChars", metrics.historyBudgetChars);
        fields.put("droppedTranscript", metrics.droppedTranscript);
        fields.put("droppedEvidence", metrics.droppedEvidence);
        fields.put("droppedAssistantBatches", metrics.droppedAssistantBatches);
        fields.put("configuredCapChars", metrics.configuredCapChars);
        fields.put("effectiveRequestCapChars", metrics.effectiveRequestCapChars);
        return new LlmCallContextPlanSnapshot(fields);
    }

    private static boolean isParlerTurnCancelRequested() {
        String rid = AgentToolContext.getParlerRequestId();
        String pr = AgentToolContext.getParlerGatewayCallerPrincipal();
        if (rid == null || rid.isEmpty() || pr == null) {
            return false;
        }
        String agentThingName = AgentToolContext.parlerRunningCancelAgentThingNameForRegistry();
        if (agentThingName == null || agentThingName.isEmpty()) {
            return false;
        }
        return ParlerRunningTurnCancelRegistry.isCancelRequested(AgentToolContext.getConversationId(), rid, pr,
                agentThingName);
    }

    /** Same JSON as parked gateway user-stop synthetic tool rows — replay pairing for running-turn cancel. */
    private static final String PARLER_RUNNING_CANCEL_SYNTHETIC_TOOL_JSON =
            "{\"status\":\"error\",\"code\":\"TURN_CANCELLED\",\"message\":\"Turn was stopped by the user before this tool call ran.\"}";

    private static String parlerDurableStreamThreadKeyForCancelSynthetics() {
        String cid = AgentToolContext.getConversationId();
        return cid != null ? cid.trim() : "";
    }

    private static void appendParlerRunningTurnCancelSynthetics(List<ChatMessage> messages, List<ToolCall> calls,
            int fromInclusive) {
        if (messages == null || calls == null || fromInclusive < 0 || fromInclusive >= calls.size()) {
            return;
        }
        String sk = parlerDurableStreamThreadKeyForCancelSynthetics();
        String agentName = AgentToolContext.parlerRunningCancelAgentThingNameForRegistry();
        boolean durable = AgentToolContext.getAgentThing() != null;
        for (int i = fromInclusive; i < calls.size(); i++) {
            ToolCall tc = calls.get(i);
            if (tc == null || tc.getId() == null || tc.getId().isEmpty()) {
                continue;
            }
            if (durable) {
                HitlSyntheticToolResultAppender.appendDurable(messages, tc.getId(), PARLER_RUNNING_CANCEL_SYNTHETIC_TOOL_JSON,
                        sk, sk, agentName, tc.getFunctionName());
            } else {
                messages.add(ChatMessage.toolResult(tc.getId(), PARLER_RUNNING_CANCEL_SYNTHETIC_TOOL_JSON,
                        tc.getFunctionName()));
            }
        }
    }

    /**
     * One executed tool: complete-answer bookkeeping, egress compaction, transcript append, optional live stream sink.
     * Shared by the normal tool path and the post-tool running-cancel path (single sequence).
     *
     * @return whether a complete answer set was detected — caller sets {@code pendingPostMarkerNoToolRound} only when
     *         the turn will continue (not when returning {@link AgentResult.Status#CANCELLED})
     */
    private static boolean appendCompactedExecutedToolResultToMessages(List<ChatMessage> messages,
            ToolCall toolCall, String result, BiConsumer<ChatMessage, StreamTokenUsage> streamAppender) {
        boolean complete = TabularCompleteAnswerSetDetector.isCompleteAnswerSet(toolCall.getFunctionName(), result);
        if (complete) {
            AgentToolContext.markCompleteAnswerSetSeenThisTurn();
        }
        List<String> classification = List.of();
        AgentThing agent = AgentToolContext.getAgentThing();
        if (agent != null) {
            classification = agent.capabilityDataClassificationForTool(toolCall.getFunctionName());
        }
        EgressResult egress = ToolResultEgressGateway.compactForLlmAppend(
                toolCall.getFunctionName(), toolCall.getId(), result, LOG, classification);
        if (egress.isCompacted()
                && AgentToolContext.peekToolEgressFullJsonForToolCall(toolCall.getId()) == null) {
            AgentToolContext.setToolEgressFullJsonForToolCall(toolCall.getId(), result);
        }
        ChatMessage toolMsg = ChatMessage.toolResult(
                toolCall.getId(), egress.getLlmContent(), toolCall.getFunctionName());
        messages.add(toolMsg);
        if (streamAppender != null) {
            streamAppender.accept(toolMsg, StreamTokenUsage.ZERO);
        }
        return complete;
    }

    private static int appendRepositoryUnavailableSkippedSiblings(List<ChatMessage> messages, List<ToolCall> calls,
            int fromInclusive, BiConsumer<ChatMessage, StreamTokenUsage> streamAppender) {
        if (messages == null || calls == null || fromInclusive < 0 || fromInclusive >= calls.size()) {
            return 0;
        }
        int appended = 0;
        String skippedJson = ArtifactCacheTurnFaults.repositoryUnavailableSkippedToolJson();
        for (int i = fromInclusive; i < calls.size(); i++) {
            ToolCall skipped = calls.get(i);
            if (skipped == null || skipped.getId() == null || skipped.getId().isEmpty()) {
                continue;
            }
            ChatMessage skippedMessage = ChatMessage.toolResult(
                    skipped.getId(), skippedJson, skipped.getFunctionName());
            messages.add(skippedMessage);
            if (streamAppender != null) {
                streamAppender.accept(skippedMessage, StreamTokenUsage.ZERO);
            }
            appended++;
        }
        return appended;
    }

    private AgentResult runInner(List<ChatMessage> messages, List<ToolDefinition> toolDefinitions,
            BiConsumer<ChatMessage, StreamTokenUsage> streamAppender,
            AtomicBoolean skipFetchGuardEndTurn, PendingApprovalRecord pending,
            String completedGatedResult) throws Exception {
        long turnStartMs = System.currentTimeMillis();
        AgentToolContext.resetLlmTurnPerformanceFlagsForAgentLoop();
        AgentToolContext.resetTabularChartRound();
        if (pending != null) {
            AgentToolContext.tabularChartRoundState().restore(pending.getTabularChartRoundSnapshot());
            if (pending.getGatedToolCall() != null && completedGatedResult != null) {
                // The gated executor bypassed normal dispatch. Apply its new result once, including TOKEN updates;
                // restoring the pre-pause snapshot itself must not repeat historic side effects.
                TabularChartRoundHooks.afterBuiltInToolResult(
                        pending.getGatedToolCall().getFunctionName(), completedGatedResult);
            }
        }
        List<String> cacheIdsAtLoopStart = new ArrayList<>(InvokeServiceExecutor.snapshotConversationInfotableCacheIds());
        boolean longAnswerPrompt = isLongAnswerUserPrompt(lastUserMessageText(messages));

        int iteration = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        int totalReasoningTokens = 0;
        long totalLlmWallMs = 0L;
        long totalToolWallMs = 0L;
        long totalRateWaitMs = 0L;
        long requestedMaxOutputTokensTotal = 0L;
        int agentLlmCalls = 0;
        int totalToolCallsExecuted = 0;
        int roundsHitMaxOutput = 0;
        String toolProtocolViolation = "";
        boolean noToolFinalAnswerApplied = false;
        boolean pendingPostMarkerNoToolRound = false;
        boolean pendingForcedSummaryToolNoneRound = false;
        boolean pendingCoverageSummaryRound = false;
        String firstToolCallCacheHit = "unknown";
        boolean firstReaderResolved = false;
        int multiToolCallRoundsCount = 0;
        boolean chartEndTurnRescueAttemptedLocal = false;
        boolean pendingExplicitChartRescueRound = false;
        boolean pendingEmptyFinalAnswerRetryRound = false;
        int emptyFinalResponses = 0;

        while (iteration < maxIterations || pendingEmptyFinalAnswerRetryRound) {
            long elapsed = System.currentTimeMillis() - turnStartMs;
            if (elapsed > timeoutMs) {
                LOG.warn("Agent loop timed out after {}ms ({} iterations)", elapsed, iteration);
                String perf = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted, totalLlmWallMs, totalToolWallMs,
                        totalRateWaitMs, totalPromptTokens, totalCompletionTokens, totalReasoningTokens,
                        requestedMaxOutputTokensTotal,
                        -1, roundsHitMaxOutput, firstToolCallCacheHit, noToolFinalAnswerApplied, multiToolCallRoundsCount, toolProtocolViolation);
                return AgentResult.timeout(iteration, totalPromptTokens, totalCompletionTokens, perf);
            }

            boolean emptyFinalAnswerRetryRound = pendingEmptyFinalAnswerRetryRound;
            pendingEmptyFinalAnswerRetryRound = false;
            if (!emptyFinalAnswerRetryRound) {
                iteration++;
                LOG.info("Agent loop iteration {}/{} (elapsed {}ms, messages={})",
                        iteration, maxIterations, System.currentTimeMillis() - turnStartMs, messages.size());
            } else {
                LOG.info("Agent loop empty-final recovery round (iteration {}/{}, elapsed {}ms, messages={})",
                        iteration, maxIterations, System.currentTimeMillis() - turnStartMs, messages.size());
            }

            String playbookTerminal = AgentToolContext.consumePlaybookTerminalAnswer();
            if (playbookTerminal != null) {
                LOG.info("Agent loop terminating after successful start_playbook (iteration {})", iteration);
                String perfPb = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted, totalLlmWallMs, totalToolWallMs,
                        totalRateWaitMs, totalPromptTokens, totalCompletionTokens, totalReasoningTokens,
                        requestedMaxOutputTokensTotal,
                        iteration, roundsHitMaxOutput, firstToolCallCacheHit, noToolFinalAnswerApplied,
                        multiToolCallRoundsCount, toolProtocolViolation);
                return AgentResult.success(playbookTerminal, iteration, totalPromptTokens, totalCompletionTokens,
                        StreamTokenUsage.ZERO, perfPb);
            }

            if (isParlerTurnCancelRequested()) {
                LlmCallRecorder.recordPreRoundCancelCandidate(
                        AgentToolContext.getParlerRequestId(),
                        AgentToolContext.getConversationId(),
                        AgentToolContext.parlerRunningCancelAgentThingNameForRegistry());
                String perfCancel = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted, totalLlmWallMs,
                        totalToolWallMs,
                        totalRateWaitMs, totalPromptTokens, totalCompletionTokens, totalReasoningTokens,
                        requestedMaxOutputTokensTotal,
                        iteration, roundsHitMaxOutput, firstToolCallCacheHit, noToolFinalAnswerApplied,
                        multiToolCallRoundsCount, toolProtocolViolation);
                return AgentResult.userCancelled(iteration, totalPromptTokens, totalCompletionTokens, perfCancel);
            }

            boolean chartRescueRound = pendingExplicitChartRescueRound;
            if (pendingExplicitChartRescueRound) {
                pendingExplicitChartRescueRound = false;
            }

            boolean summaryToolNoneRound = pendingForcedSummaryToolNoneRound;
            if (pendingForcedSummaryToolNoneRound) {
                pendingForcedSummaryToolNoneRound = false;
            }

            boolean coverageSummaryRound = pendingCoverageSummaryRound;
            if (pendingCoverageSummaryRound) {
                pendingCoverageSummaryRound = false;
            }

            boolean postMarkerRound = pendingPostMarkerNoToolRound;
            if (pendingPostMarkerNoToolRound) {
                pendingPostMarkerNoToolRound = false;
            }

            List<ToolDefinition> defsForRound;
            if (emptyFinalAnswerRetryRound || summaryToolNoneRound) {
                defsForRound = Collections.emptyList();
            } else if (postMarkerRound) {
                defsForRound = postMarkerToolDefinitions(toolDefinitions);
            } else if (chartRescueRound) {
                defsForRound = postMarkerToolDefinitions(toolDefinitions);
            } else {
                defsForRound = toolDefinitions != null ? toolDefinitions : Collections.emptyList();
                if (toolDefinitionsRoundFilter != null) {
                    defsForRound = toolDefinitionsRoundFilter.apply(defsForRound, iteration);
                }
            }
            if (postMarkerRound && defsForRound.isEmpty() && !summaryToolNoneRound) {
                noToolFinalAnswerApplied = true;
            }
            boolean routingWithTools = !postMarkerRound && !chartRescueRound && !summaryToolNoneRound
                    && !emptyFinalAnswerRetryRound
                    && !defsForRound.isEmpty();
            int effMax = resolveEffectiveMaxOutputTokensForRound(
                    longAnswerPrompt, postMarkerRound || emptyFinalAnswerRetryRound, routingWithTools, maxTokens);
            if (effMax > 0) {
                requestedMaxOutputTokensTotal += effMax;
            }

            boolean postMarkerOffersSingletonChart =
                    postMarkerRound && !defsForRound.isEmpty() && !summaryToolNoneRound;
            boolean presentationPathThisRound = postMarkerOffersSingletonChart
                    && !AgentToolContext.eligibleChartRescueToolExposureForTurn()
                    && AgentToolContext.eligibleAnswerPresentationPhasePostMarkerExposure();
            if (presentationPathThisRound) {
                AgentToolContext.markPresentationPhaseEnteredThisLoop();
            }

            AgentTaskState taskState = AgentToolContext.getAgentTaskState();
            String taskStateBlock = taskState != null ? AgentTaskStateRenderer.render(taskState) : "";
            int taskStateIdx = TaskStateLlmInjector.insertForApiRound(messages, taskStateBlock);
            int utcClockIdx = LlmUtcClockInjector.insertForApiRound(messages, userIanaTimezone);
            int coverageGuidanceIdx = coverageSummaryRound
                    ? DocumentCoverageSummaryInjector.insertForApiRound(messages)
                    : -1;
            int emptyFinalRetryGuidanceIdx = emptyFinalAnswerRetryRound
                    ? EmptyFinalAnswerRetryInjector.insertForApiRound(messages)
                    : -1;
            int outboundMessageCount = messages != null ? messages.size() : 0;
            LlmUsageWireIds wireIds = llmClient.usageWireIdsForEffectiveModel(model);
            OptionalLong providerRequestCapChars = llmClient.contextPlanningInputCapChars(effMax, model);
            LlmResponse response;
            LlmCallRecorder.LlmCallAttempt roundAttempt = null;
            int llmRoundIndex = agentLlmCalls + 1;
            long llmT0 = System.currentTimeMillis();
            try {
                ContextBudgetPlanner.PlannedOutbound planned = ContextBudgetPlanner.planForProviderRound(
                        LOG, messages, defsForRound, wireIds, llmContextMaxChars,
                        providerRequestCapChars.orElse(0L), taskStateIdx, utcClockIdx);
                List<ChatMessage> outboundForLlm = planned.getOutboundMessages();
                outboundMessageCount = outboundForLlm.size();
                LlmChatRequest chatRequest = rateControlStatusSink == null
                        ? LlmChatRequest.forAgentRound(outboundForLlm, defsForRound, temperature, effMax, model, wireIds)
                        : LlmChatRequest.forAgentRound(outboundForLlm, defsForRound, temperature, effMax, model, wireIds,
                                rateControlStatusSink);
                chatRequest = LlmChatRequest.copyWithCacheControl(chatRequest, true);
                if (postMarkerRound || summaryToolNoneRound || emptyFinalAnswerRetryRound) {
                    chatRequest = LlmChatRequest.copyWithToolPolicy(chatRequest, defsForRound.isEmpty());
                }
                LlmCallContext callContext = LlmCallRecorder.agentRoundContext(
                        AgentToolContext.getParlerRequestId(),
                        AgentToolContext.getConversationId(),
                        AgentToolContext.parlerRunningCancelAgentThingNameForRegistry(),
                        llmRoundIndex,
                        wireIds,
                        planSnapshotFrom(planned.getPlannedMetrics()));
                chatRequest = LlmChatRequest.copyWithCallContext(chatRequest, callContext);
                roundAttempt = LlmCallRecorder.ensureAttempt(callContext.withWireIds(wireIds));
                response = llmClient.chat(chatRequest);
            } catch (Exception e) {
                if (!pendingReplayStats.isEmpty()) {
                    LlmUsageTelemetry.logPendingReplayCompaction(LOG, wireIds, outboundMessageCount,
                            defsForRound.size(), llmRoundIndex, pendingReplayStats);
                }
                pendingReplayStats = SealStats.EMPTY;
                throw e;
            } finally {
                // Remove highest-index injections first so earlier indices stay valid.
                EmptyFinalAnswerRetryInjector.removeAtIndex(messages, emptyFinalRetryGuidanceIdx);
                DocumentCoverageSummaryInjector.removeAtIndex(messages, coverageGuidanceIdx);
                LlmUtcClockInjector.removeAtIndex(messages, utcClockIdx);
                TaskStateLlmInjector.removeAtIndex(messages, taskStateIdx);
            }
            long llmWall = System.currentTimeMillis() - llmT0;
            totalLlmWallMs += llmWall;
            long rateWait = response.getRateGateAdmissionWaitMs();
            totalRateWaitMs += rateWait;
            agentLlmCalls++;

            LOG.info("LLM round completed in {}ms (finishReason={}, toolCalls={})",
                    llmWall,
                    response.getFinishReason(),
                    response.hasToolCalls() ? response.getToolCalls().size() : 0);

            LlmUsageTelemetry.logToolSchemaUsage(LOG, wireIds, llmRoundIndex, defsForRound, response.getToolCalls(),
                    AgentToolContext.getParlerRequestId());

            SealStats sealSnap = pendingReplayStats;
            pendingReplayStats = SealStats.EMPTY;
            Integer rawReplay = sealSnap.isEmpty() ? null : sealSnap.getRawReplayChars();
            Integer replayChars = sealSnap.isEmpty() ? null : sealSnap.getReplayChars();
            Double compactRatio = sealSnap.isEmpty() ? null : sealSnap.getCompactRatio();
            LlmUsageTelemetry.logLlmUsage(LOG, wireIds, response,
                    outboundMessageCount,
                    defsForRound.size(),
                    llmRoundIndex,
                    rawReplay, replayChars, compactRatio,
                    AgentToolContext.getParlerRequestId());

            totalPromptTokens += response.getPromptTokens();
            totalCompletionTokens += response.getCompletionTokens();
            totalReasoningTokens += response.getReasoningTokens();

            if (response.getFinishReason() == LlmResponse.FinishReason.LENGTH) {
                roundsHitMaxOutput++;
            }

            if (!postMarkerRound && !chartRescueRound && !summaryToolNoneRound && !emptyFinalAnswerRetryRound
                    && response.hasToolCalls()
                    && response.getToolCalls().size() > 1) {
                multiToolCallRoundsCount++;
            }

            if (presentationPathThisRound && response.hasToolCalls()) {
                int n = 0;
                for (ToolCall tc : response.getToolCalls()) {
                    if (tc != null && "build_chart_from_tabular_result".equals(tc.getFunctionName())) {
                        n++;
                    }
                }
                if (n > 0) {
                    AgentToolContext.addPresentationActionsRequested(n);
                }
            }

            if (isParlerTurnCancelRequested() && response.hasToolCalls()) {
                roundAttempt.markCancelObserved();
                LlmCallRecorder.releaseCurrentAttempt();
                int cancelToolBatchStart = messages.size();
                ChatMessage asstCancel = ChatMessage.assistantWithToolCalls(response.getToolCalls());
                messages.add(asstCancel);
                if (streamAppender != null) {
                    streamAppender.accept(asstCancel, StreamTokenUsage.fromLlmResponse(response, wireIds));
                }
                appendParlerRunningTurnCancelSynthetics(messages, response.getToolCalls(), 0);
                if (LlmReplayCompactionGate.isReplayCompactionEffective()) {
                    pendingReplayStats = LlmToolResultReplayCompactor.compactBatch(messages, cancelToolBatchStart, true,
                            LOG);
                }
                String perfCancelTools = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted,
                        totalLlmWallMs, totalToolWallMs, totalRateWaitMs, totalPromptTokens, totalCompletionTokens,
                        totalReasoningTokens, requestedMaxOutputTokensTotal, iteration, roundsHitMaxOutput,
                        firstToolCallCacheHit, noToolFinalAnswerApplied, multiToolCallRoundsCount, toolProtocolViolation);
                return AgentResult.userCancelled(iteration, totalPromptTokens, totalCompletionTokens, perfCancelTools);
            }

            if (!response.hasToolCalls()) {
                if (isParlerTurnCancelRequested()) {
                    roundAttempt.markCancelObserved();
                    LlmCallRecorder.releaseCurrentAttempt();
                    String perfCancelNoTool = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted,
                            totalLlmWallMs, totalToolWallMs, totalRateWaitMs, totalPromptTokens, totalCompletionTokens,
                            totalReasoningTokens, requestedMaxOutputTokensTotal, iteration, roundsHitMaxOutput,
                            firstToolCallCacheHit, noToolFinalAnswerApplied, multiToolCallRoundsCount, toolProtocolViolation);
                    return AgentResult.userCancelled(iteration, totalPromptTokens, totalCompletionTokens, perfCancelNoTool);
                }
                if (summaryToolNoneRound) {
                    noToolFinalAnswerApplied = true;
                }
                String finalContent = response.getContent();
                if (finalContent == null || finalContent.isBlank()) {
                    int retryAttempt = emptyFinalResponses;
                    emptyFinalResponses++;
                    boolean retry = retryAttempt == 0;
                    logEmptyFinalResponse(wireIds, response, iteration, retryAttempt, retry ? "retry" : "error");
                    if (retry) {
                        pendingEmptyFinalAnswerRetryRound = true;
                        continue;
                    }
                    String perfEmpty = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted,
                            totalLlmWallMs, totalToolWallMs, totalRateWaitMs, totalPromptTokens,
                            totalCompletionTokens, totalReasoningTokens, requestedMaxOutputTokensTotal,
                            -1, roundsHitMaxOutput, firstToolCallCacheHit, noToolFinalAnswerApplied,
                            multiToolCallRoundsCount, toolProtocolViolation);
                    return AgentResult.error("EMPTY_FINAL_ANSWER",
                            "The model returned no final answer after one retry.", iteration,
                            totalPromptTokens, totalCompletionTokens, perfEmpty);
                }
                if (!summaryToolNoneRound && !emptyFinalAnswerRetryRound
                        && eligibleSingletonChartRescueAfterProse(toolDefinitions, chartEndTurnRescueAttemptedLocal)) {
                    chartEndTurnRescueAttemptedLocal = true;
                    AgentToolContext.markParlerChartEndTurnRescueAttemptedForTurnPerf();
                    String prose = response.getContent() != null ? response.getContent() : "";
                    ChatMessage asstProse = ChatMessage.assistant(prose);
                    messages.add(asstProse);
                    if (streamAppender != null) {
                        streamAppender.accept(asstProse, StreamTokenUsage.fromLlmResponse(response, wireIds));
                    }
                    pendingExplicitChartRescueRound = true;
                    continue;
                }
                LOG.info("Agent completed after {} iteration(s), tokens: prompt={}, completion={}",
                        iteration, totalPromptTokens, totalCompletionTokens);
                StreamTokenUsage finalRound = StreamTokenUsage.fromLlmResponse(response, wireIds);
                finalRound = StreamTokenUsage.withParlerTurnDiagnostics(finalRound, totalRateWaitMs, firstToolCallCacheHit);
                String perf = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted, totalLlmWallMs, totalToolWallMs,
                        totalRateWaitMs, totalPromptTokens, totalCompletionTokens, totalReasoningTokens,
                        requestedMaxOutputTokensTotal,
                        iteration, roundsHitMaxOutput, firstToolCallCacheHit, noToolFinalAnswerApplied,
                        multiToolCallRoundsCount, toolProtocolViolation);
                return AgentResult.success(
                        response.getContent(), iteration, totalPromptTokens, totalCompletionTokens, finalRound, perf);
            }
            if ((postMarkerRound || summaryToolNoneRound || emptyFinalAnswerRetryRound) && defsForRound.isEmpty()) {
                toolProtocolViolation = mergeToolProtocolViolation(toolProtocolViolation, "tool_call_after_tool_none");
                String synthesized = CompleteAnswerFallbackMarkdown.fromMessages(messages);
                StreamTokenUsage finalRound = StreamTokenUsage.fromLlmResponse(response, wireIds);
                finalRound = StreamTokenUsage.withParlerTurnDiagnostics(finalRound, totalRateWaitMs, firstToolCallCacheHit);
                String perf = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted, totalLlmWallMs, totalToolWallMs,
                        totalRateWaitMs, totalPromptTokens, totalCompletionTokens, totalReasoningTokens,
                        requestedMaxOutputTokensTotal,
                        iteration, roundsHitMaxOutput, firstToolCallCacheHit, noToolFinalAnswerApplied,
                        multiToolCallRoundsCount, toolProtocolViolation);
                return AgentResult.success(
                        synthesized, iteration, totalPromptTokens, totalCompletionTokens, finalRound, perf);
            }

            int batchStart = messages.size();
            ChatMessage asstTools = ChatMessage.assistantWithToolCalls(response.getToolCalls());
            messages.add(asstTools);
            if (streamAppender != null) {
                streamAppender.accept(asstTools,
                        StreamTokenUsage.fromLlmResponse(response, wireIds));
            }

            List<ToolCall> toolCalls = response.getToolCalls();
            int presentationBuildChartsExecutedThisBatch = 0;
            for (int toolIndex = 0; toolIndex < toolCalls.size(); toolIndex++) {
                ToolCall toolCall = toolCalls.get(toolIndex);
                LOG.info("Running tool from LLM: {} (callId={})", toolCall.getFunctionName(), toolCall.getId());
                if (!firstReaderResolved) {
                    String hit = evaluateFirstReaderCacheHit(cacheIdsAtLoopStart, toolCall);
                    if (hit != null) {
                        firstToolCallCacheHit = hit;
                        firstReaderResolved = true;
                    }
                }
                if (isParlerTurnCancelRequested()) {
                    roundAttempt.markCancelObserved();
                    LlmCallRecorder.releaseCurrentAttempt();
                    appendParlerRunningTurnCancelSynthetics(messages, toolCalls, toolIndex);
                    if (LlmReplayCompactionGate.isReplayCompactionEffective()) {
                        pendingReplayStats = LlmToolResultReplayCompactor.compactBatch(messages, batchStart, true, LOG);
                    }
                    String perfPreTool = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted,
                            totalLlmWallMs, totalToolWallMs, totalRateWaitMs, totalPromptTokens, totalCompletionTokens,
                            totalReasoningTokens, requestedMaxOutputTokensTotal, iteration, roundsHitMaxOutput,
                            firstToolCallCacheHit, noToolFinalAnswerApplied, multiToolCallRoundsCount, toolProtocolViolation);
                    return AgentResult.userCancelled(iteration, totalPromptTokens, totalCompletionTokens, perfPreTool);
                }
                totalToolCallsExecuted++;
                String result;
                ArtifactCacheException fatalCacheFault = null;
                long toolT0 = System.currentTimeMillis();
                try {
                    boolean isBuildChart = "build_chart_from_tabular_result".equals(toolCall.getFunctionName());
                    boolean blockedByPresentationCap = presentationPathThisRound && isBuildChart
                            && presentationBuildChartsExecutedThisBatch >= PRESENTATION_ACTION_LIMIT;
                    if (blockedByPresentationCap) {
                        AgentToolContext.addPresentationActionsBlocked(1);
                        com.thingworx.things.agent.tools.BuildChartFromTabularResultExecutor.noteBlockedBuild(toolCall,
                                "PRESENTATION_ACTION_LIMIT");
                        ObjectNode err = LOOP_JSON.createObjectNode();
                        err.put("status", "error");
                        err.put("code", "PRESENTATION_ACTION_LIMIT");
                        err.put("message", "Exceeded presentation-phase chart action budget for this turn.");
                        result = LOOP_JSON.writeValueAsString(err);
                    } else {
                        if (isBuildChart) {
                            AgentToolContext.markChartBuildAttemptedThisTurn();
                        }
                        result = toolExecutor.execute(toolCall);
                        if (isBuildChart) {
                            noteRecoverableChartFailureFromBuildChartResult(result);
                        }
                        if (presentationPathThisRound && isBuildChart) {
                            presentationBuildChartsExecutedThisBatch++;
                            AgentToolContext.addPresentationActionsExecuted(1);
                        }
                    }
                    LOG.info("Tool {} returned in {}ms (resultChars={})",
                            toolCall.getFunctionName(), System.currentTimeMillis() - toolT0,
                            result != null ? result.length() : 0);
                } catch (ApprovalPendingException e) {
                    totalToolWallMs += System.currentTimeMillis() - toolT0;
                    LOG.info("Tool {} paused for approval (pendingId={})",
                            toolCall.getFunctionName(), e.getPendingId());
                    String pendingId = e.getPendingId();
                    int nextIdx = toolIndex + 1;
                    if (pendingId != null && !pendingId.isEmpty() && nextIdx < toolCalls.size()) {
                        PendingApprovalStore.attachInterruptedBatchSiblings(pendingId,
                                toolCalls.subList(nextIdx, toolCalls.size()));
                    }
                    if (isParlerTurnCancelRequested()) {
                        /*
                         * Serialize CAS remove + tombstone with {@link ParlerCancelUserPromptGatewayOrchestration}'s
                         * parked path under the same {@link ParlerConversationLocks} identity as gateway stop.
                         * {@link AgentThing#ParlerStreamToRemoteThing} already holds
                         * {@code lockForConversation(cid) == ParlerConversationLocks.lockFor(cid)} across {@link #run} for
                         * registration-bearing AlwaysOn turns — this is a reentrant acquire; other
                         * {@code AgentLoop.run} entry points must not assume an outer conversation lock.
                         */
                        String lockCid = AgentToolContext.getConversationId();
                        synchronized (ParlerConversationLocks.lockFor(lockCid)) {
                            PendingApprovalRecord fresh =
                                    pendingId != null && !pendingId.isEmpty() ? PendingApprovalStore.get(pendingId) : null;
                            if (fresh != null) {
                                PendingApprovalRecord taken = PendingApprovalStore.compareAndRemove(pendingId, fresh);
                                if (taken != null) {
                                    ParlerGatewayUserStopTombstoneRegistry.noteGatewayUserStopTerminal(
                                            taken.getConversationId(), taken.getRequestId(), taken.getPrincipal(),
                                            taken.getAgentThingName());
                                }
                            }
                        }
                        roundAttempt.markCancelObserved();
                        LlmCallRecorder.releaseCurrentAttempt();
                        appendParlerRunningTurnCancelSynthetics(messages, toolCalls, toolIndex);
                        if (LlmReplayCompactionGate.isReplayCompactionEffective()) {
                            pendingReplayStats = LlmToolResultReplayCompactor.compactBatch(messages, batchStart, true, LOG);
                        }
                        skipFetchGuardEndTurn.set(true);
                        String perfCancel = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted,
                                totalLlmWallMs, totalToolWallMs, totalRateWaitMs, totalPromptTokens, totalCompletionTokens,
                                totalReasoningTokens, requestedMaxOutputTokensTotal, iteration, roundsHitMaxOutput,
                                firstToolCallCacheHit, noToolFinalAnswerApplied, multiToolCallRoundsCount,
                                toolProtocolViolation);
                        return AgentResult.userCancelled(iteration, totalPromptTokens, totalCompletionTokens, perfCancel);
                    }
                    LlmCallRecorder.releaseCurrentAttempt();
                    skipFetchGuardEndTurn.set(true);
                    StreamTokenUsage awaiting = StreamTokenUsage.fromLlmResponse(response, wireIds);
                    awaiting = StreamTokenUsage.withParlerTurnDiagnostics(awaiting, totalRateWaitMs, firstToolCallCacheHit);
                    String perf = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted, totalLlmWallMs, totalToolWallMs,
                            totalRateWaitMs, totalPromptTokens, totalCompletionTokens, totalReasoningTokens,
                        requestedMaxOutputTokensTotal,
                            iteration, roundsHitMaxOutput, firstToolCallCacheHit, noToolFinalAnswerApplied,
                            multiToolCallRoundsCount, toolProtocolViolation);
                    return AgentResult.awaitingApproval(e.getPendingId(), iteration,
                            totalPromptTokens, totalCompletionTokens, awaiting, perf);
                } catch (ArtifactCacheException e) {
                    ArtifactCacheException repositoryFault =
                            ArtifactCacheTurnFaults.findRepositoryUnavailable(e);
                    if (repositoryFault != null) {
                        fatalCacheFault = repositoryFault;
                        result = ArtifactCacheTurnFaults.repositoryUnavailableToolErrorJson();
                    } else {
                        LOG.warn("Tool cache operation failed: {} — code={} message={}",
                                toolCall.getFunctionName(), e.code(), e.getMessage(), e);
                        result = ArtifactCacheTurnFaults.toolErrorJson(e);
                    }
                } catch (Exception e) {
                    ArtifactCacheException repositoryFault =
                            ArtifactCacheTurnFaults.findRepositoryUnavailable(e);
                    ArtifactCacheException typed = ArtifactCacheTurnFaults.find(e);
                    if (repositoryFault != null) {
                        fatalCacheFault = repositoryFault;
                        result = ArtifactCacheTurnFaults.repositoryUnavailableToolErrorJson();
                    } else if (typed != null) {
                        LOG.warn("Tool cache operation failed: {} — code={} message={}",
                                toolCall.getFunctionName(), typed.code(), typed.getMessage(), e);
                        result = ArtifactCacheTurnFaults.toolErrorJson(typed);
                    } else {
                        LOG.warn("Tool execution failed: {} — {}", toolCall.getFunctionName(), e.getMessage(), e);
                        ObjectNode err = LOOP_JSON.createObjectNode();
                        err.put("error", e.getMessage() != null ? e.getMessage() : "Tool execution failed.");
                        result = err.toString();
                    }
                } finally {
                    totalToolWallMs += System.currentTimeMillis() - toolT0;
                }
                if (fatalCacheFault != null) {
                    LOG.error("Artifact Cache repository unavailable during tool {} (callId={}); aborting turn",
                            toolCall.getFunctionName(), toolCall.getId(), fatalCacheFault);
                    appendCompactedExecutedToolResultToMessages(messages, toolCall, result, streamAppender);
                    int skipped = appendRepositoryUnavailableSkippedSiblings(
                            messages, toolCalls, toolIndex + 1, streamAppender);
                    LOG.info("Agent loop cache-fatal abort after callId={} skippedSiblings={}",
                            toolCall.getId(), skipped);
                    if (LlmReplayCompactionGate.isReplayCompactionEffective()) {
                        pendingReplayStats = LlmToolResultReplayCompactor.compactBatch(messages, batchStart, true, LOG);
                    }
                    LlmCallRecorder.releaseCurrentAttempt();
                    String perfFatal = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted,
                            totalLlmWallMs, totalToolWallMs, totalRateWaitMs, totalPromptTokens,
                            totalCompletionTokens, totalReasoningTokens, requestedMaxOutputTokensTotal, iteration,
                            roundsHitMaxOutput, firstToolCallCacheHit, noToolFinalAnswerApplied,
                            multiToolCallRoundsCount, toolProtocolViolation);
                    return AgentResult.error(
                            ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE,
                            ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_MESSAGE,
                            iteration, totalPromptTokens, totalCompletionTokens, perfFatal);
                }
                if (isParlerTurnCancelRequested()) {
                    // Executed tool already ran — persist its real result; only not-yet-run siblings get TURN_CANCELLED
                    // synthetics (transcript must not claim a side-effecting tool never ran).
                    roundAttempt.markCancelObserved();
                    LlmCallRecorder.releaseCurrentAttempt();
                    appendCompactedExecutedToolResultToMessages(messages, toolCall, result, streamAppender);
                    appendParlerRunningTurnCancelSynthetics(messages, toolCalls, toolIndex + 1);
                    if (LlmReplayCompactionGate.isReplayCompactionEffective()) {
                        pendingReplayStats = LlmToolResultReplayCompactor.compactBatch(messages, batchStart, true, LOG);
                    }
                    String perfPostTool = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted,
                            totalLlmWallMs, totalToolWallMs, totalRateWaitMs, totalPromptTokens, totalCompletionTokens,
                            totalReasoningTokens, requestedMaxOutputTokensTotal, iteration, roundsHitMaxOutput,
                            firstToolCallCacheHit, noToolFinalAnswerApplied, multiToolCallRoundsCount, toolProtocolViolation);
                    return AgentResult.userCancelled(iteration, totalPromptTokens, totalCompletionTokens, perfPostTool);
                }
                if (appendCompactedExecutedToolResultToMessages(messages, toolCall, result, streamAppender)) {
                    pendingPostMarkerNoToolRound = true;
                }
                String midBatchTerminal = AgentToolContext.consumePlaybookTerminalAnswer();
                if (midBatchTerminal != null) {
                    int skipped = PlaybookTerminalHandoff.appendSkippedSiblingToolResults(
                            toolCalls, toolIndex + 1, messages, streamAppender);
                    LOG.info(
                            "Agent loop terminating mid-batch after successful start_playbook (iteration {}, after callId={}, skippedSiblings={})",
                            iteration, toolCall.getId(), skipped);
                    if (LlmReplayCompactionGate.isReplayCompactionEffective()) {
                        pendingReplayStats = LlmToolResultReplayCompactor.compactBatch(messages, batchStart, true, LOG);
                    }
                    LlmCallRecorder.releaseCurrentAttempt();
                    String perf = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted, totalLlmWallMs, totalToolWallMs,
                            totalRateWaitMs, totalPromptTokens, totalCompletionTokens, totalReasoningTokens,
                        requestedMaxOutputTokensTotal,
                            iteration, roundsHitMaxOutput, firstToolCallCacheHit, noToolFinalAnswerApplied,
                            multiToolCallRoundsCount, toolProtocolViolation);
                    return AgentResult.success(midBatchTerminal, iteration, totalPromptTokens, totalCompletionTokens,
                            StreamTokenUsage.ZERO, perf);
                }
            }
            boolean postMarkerOrRescueSingletonOffered = (postMarkerRound || chartRescueRound) && !defsForRound.isEmpty()
                    && !summaryToolNoneRound;
            if (postMarkerOrRescueSingletonOffered && response.hasToolCalls()) {
                pendingForcedSummaryToolNoneRound = true;
                if (presentationPathThisRound) {
                    AgentToolContext.incrementPresentationPhaseRoundsUsedThisTurn();
                }
            }
            // Repetition-blocked and document-search saturation forced summaries compose: a pending
            // grounded-coverage request (C1) rides whichever path wins, including the repetition branch.
            boolean repetitionForcedSummary = AgentToolContext.getRepetitionBlockedCountForTurnPerf() >= 2;
            AgentToolContext.ForcedSummaryDecision summaryDecision =
                    AgentToolContext.resolveForcedSummaryDecision(repetitionForcedSummary);
            if (summaryDecision.forceSummary()) {
                pendingForcedSummaryToolNoneRound = true;
                if (summaryDecision.coverageGuidance()) {
                    pendingCoverageSummaryRound = true;
                }
            }
            if (LlmReplayCompactionGate.isReplayCompactionEffective()) {
                pendingReplayStats = LlmToolResultReplayCompactor.compactBatch(messages, batchStart, true, LOG);
            }
            LlmCallRecorder.releaseCurrentAttempt();

            playbookTerminal = AgentToolContext.consumePlaybookTerminalAnswer();
            if (playbookTerminal != null) {
                LOG.info("Agent loop terminating after successful start_playbook (iteration {})", iteration);
                String perf = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted, totalLlmWallMs, totalToolWallMs,
                        totalRateWaitMs, totalPromptTokens, totalCompletionTokens, totalReasoningTokens,
                        requestedMaxOutputTokensTotal,
                        iteration, roundsHitMaxOutput, firstToolCallCacheHit, noToolFinalAnswerApplied,
                        multiToolCallRoundsCount, toolProtocolViolation);
                return AgentResult.success(playbookTerminal, iteration, totalPromptTokens, totalCompletionTokens,
                        StreamTokenUsage.ZERO, perf);
            }
        }

        LOG.warn("Agent loop reached max iterations ({})", maxIterations);
        String perfMax = emitTurnPerformance(turnStartMs, agentLlmCalls, totalToolCallsExecuted, totalLlmWallMs, totalToolWallMs,
                totalRateWaitMs, totalPromptTokens, totalCompletionTokens, totalReasoningTokens,
                requestedMaxOutputTokensTotal,
                -1, roundsHitMaxOutput, firstToolCallCacheHit, noToolFinalAnswerApplied, multiToolCallRoundsCount, toolProtocolViolation);
        return AgentResult.maxIterations(maxIterations, totalPromptTokens, totalCompletionTokens, perfMax);
    }

    private String emitTurnPerformance(long turnStartMs, int agentLlmCalls, int toolCallCount, long llmWallMs,
            long toolWallMs, long rateWaitMs, int promptTokensTotal, int completionTokensTotal,
            int reasoningTokensTotal, long requestedMaxOutputTokensTotal, int finalAnswerRoundIndex,
            int roundsHitMaxOutput,
            String firstToolCallCacheHit, boolean noToolFinalAnswerApplied, int multiToolCallRoundsCount,
            String toolProtocolViolation) {
        LlmTurnPerformanceFields f = new LlmTurnPerformanceFields();
        f.conversationId = AgentToolContext.getConversationId();
        String rid = AgentToolContext.getParlerRequestId();
        f.requestId = rid != null ? rid : "";
        f.agentIterations = agentLlmCalls;
        f.toolCallCount = toolCallCount;
        f.llmWallMs = llmWallMs;
        f.toolWallMs = toolWallMs;
        f.rateWaitMs = rateWaitMs;
        f.turnWallMs = Math.max(0L, System.currentTimeMillis() - turnStartMs);
        f.promptTokensTotal = promptTokensTotal;
        f.completionTokensTotal = completionTokensTotal;
        f.reasoningTokensTotal = Math.max(0, reasoningTokensTotal);
        f.requestedMaxOutputTokensTotal = requestedMaxOutputTokensTotal;
        f.finalAnswerRoundIndex = finalAnswerRoundIndex;
        f.fetchAfterCompleteAnswerSetCount = AgentToolContext.getFetchAfterCompleteAnswerSetCount();
        f.roundsHitMaxOutput = roundsHitMaxOutput;
        f.firstToolCallCacheHit = firstToolCallCacheHit != null ? firstToolCallCacheHit : "unknown";
        f.markerEmitterEnabled = CachedTabularGroupMetricExecutor.isAnswerSetCompleteMarkerEmitterEnabled();
        f.noToolFinalAnswerApplied = noToolFinalAnswerApplied;
        f.multiToolCallRoundsCount = Math.max(0, multiToolCallRoundsCount);
        // Today: serial tool execution (maxConcurrency=1). Future: raise as Agent runtime allows.
        f.toolExecutionMaxConcurrency = 1;
        f.toolProtocolViolation = toolProtocolViolation != null ? toolProtocolViolation : "";
        f.chartExpectedButMissing = AgentToolContext.chartExpectedButMissingForTurnPerf();
        f.chartRescueAttempted = AgentToolContext.chartEndTurnRescueAttemptedForTurnPerf();
        f.repetitionBlockedCount = AgentToolContext.getRepetitionBlockedCountForTurnPerf();
        f.parlerChartWireEmittedCount = AgentToolContext.parlerChartWireEmittedCountForTurnPerf();
        f.presentationPhaseEntered = AgentToolContext.presentationPhaseEnteredThisLoop();
        f.presentationActionsRequested = AgentToolContext.getPresentationActionsRequestedForTurnPerf();
        f.presentationActionsExecuted = AgentToolContext.getPresentationActionsExecutedForTurnPerf();
        f.presentationActionsBlocked = AgentToolContext.getPresentationActionsBlockedForTurnPerf();
        LlmUsageTelemetry.logLlmTurnPerformance(LOG, f);
        return buildTurnPerfWireJson(f);
    }

    private static String buildTurnPerfWireJson(LlmTurnPerformanceFields f) {
        try {
            ObjectNode o = LOOP_JSON.createObjectNode();
            o.put("turnWallMs", (int) Math.min(f.turnWallMs, Integer.MAX_VALUE));
            o.put("agentIterations", f.agentIterations);
            o.put("toolCallCount", f.toolCallCount);
            o.put("llmWallMs", (int) Math.min(f.llmWallMs, Integer.MAX_VALUE));
            o.put("toolWallMs", (int) Math.min(f.toolWallMs, Integer.MAX_VALUE));
            o.put("rateWaitMs", (int) Math.min(f.rateWaitMs, Integer.MAX_VALUE));
            o.put("promptTokensTotal", f.promptTokensTotal);
            o.put("completionTokensTotal", f.completionTokensTotal);
            if (f.reasoningTokensTotal > 0) {
                o.put("reasoningTokensTotal", f.reasoningTokensTotal);
            }
            o.put("requestedMaxOutputTokensTotal", (int) Math.min(f.requestedMaxOutputTokensTotal, Integer.MAX_VALUE));
            o.put("finalAnswerRoundIndex", f.finalAnswerRoundIndex);
            o.put("fetchAfterCompleteAnswerSetCount", f.fetchAfterCompleteAnswerSetCount);
            o.put("roundsHitMaxOutput", f.roundsHitMaxOutput);
            o.put("firstToolCallCacheHit", f.firstToolCallCacheHit != null ? f.firstToolCallCacheHit : "unknown");
            o.put("markerEmitterEnabled", f.markerEmitterEnabled);
            o.put("noToolFinalAnswerApplied", f.noToolFinalAnswerApplied);
            o.put("toolExecutionMaxConcurrency", f.toolExecutionMaxConcurrency);
            o.put("multiToolCallRoundsCount", f.multiToolCallRoundsCount);
            o.put("chartExpectedButMissing", f.chartExpectedButMissing);
            o.put("chartRescueAttempted", f.chartRescueAttempted);
            o.put("repetitionBlockedCount", f.repetitionBlockedCount);
            o.put("parlerChartWireEmittedCount", f.parlerChartWireEmittedCount);
            o.put("presentationPhaseEntered", f.presentationPhaseEntered);
            o.put("presentationActionsRequested", f.presentationActionsRequested);
            o.put("presentationActionsExecuted", f.presentationActionsExecuted);
            o.put("presentationActionsBlocked", f.presentationActionsBlocked);
            if (f.toolProtocolViolation != null && !f.toolProtocolViolation.isEmpty()) {
                o.put("toolProtocolViolation", f.toolProtocolViolation);
            }
            return LOOP_JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Drops replay-guard ordinals for the current turn. Skipped when pausing for HITL so continuation shares counters.
     */
    private static void endFetchCachedReplayGuardTurn() {
        FetchCachedReplayGuard.endTurn(FetchCachedReplayGuard.resolveCurrentTurnKey());
    }

    public static class AgentResult {

        public enum Status { SUCCESS, ERROR, TIMEOUT, MAX_ITERATIONS, AWAITING_APPROVAL, CANCELLED }

        private final Status status;
        private final String content;
        private final int iterations;
        private final int promptTokens;
        private final int completionTokens;
        /** Prompt/completion tokens for the last successful LLM call that produced assistant text (final reply only). */
        private final StreamTokenUsage lastSuccessfulAssistantRound;
        private final String approvalPendingId;
        private final String errorCode;
        /** Turn-level performance subset JSON for {@code done.llm_usage} merge (may be empty). */
        private final String llmTurnPerformanceWireJson;

        private AgentResult(Status status, String content, int iterations,
                            int promptTokens, int completionTokens, StreamTokenUsage lastSuccessfulAssistantRound,
                            String approvalPendingId, String errorCode, String llmTurnPerformanceWireJson) {
            this.status = status;
            this.content = content;
            this.iterations = iterations;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.lastSuccessfulAssistantRound = lastSuccessfulAssistantRound != null
                    ? lastSuccessfulAssistantRound : StreamTokenUsage.ZERO;
            this.approvalPendingId = approvalPendingId;
            this.errorCode = errorCode;
            this.llmTurnPerformanceWireJson = llmTurnPerformanceWireJson != null ? llmTurnPerformanceWireJson : "";
        }

        static AgentResult success(String content, int iterations, int totalPt, int totalCt, StreamTokenUsage finalRound) {
            return success(content, iterations, totalPt, totalCt, finalRound, null);
        }

        static AgentResult success(String content, int iterations, int totalPt, int totalCt, StreamTokenUsage finalRound,
                String llmTurnPerformanceWireJson) {
            return new AgentResult(Status.SUCCESS, content, iterations, totalPt, totalCt, finalRound, null, null,
                    llmTurnPerformanceWireJson);
        }

        public static AgentResult error(String errorCode, String message, int iterations, int totalPt, int totalCt,
                String llmTurnPerformanceWireJson) {
            return new AgentResult(Status.ERROR, message != null ? message : "", iterations, totalPt, totalCt,
                    StreamTokenUsage.ZERO, null, errorCode, llmTurnPerformanceWireJson);
        }

        static AgentResult timeout(int iterations, int pt, int ct) {
            return timeout(iterations, pt, ct, null);
        }

        static AgentResult timeout(int iterations, int pt, int ct, String llmTurnPerformanceWireJson) {
            return new AgentResult(Status.TIMEOUT, "Agent timed out after " + iterations + " iterations",
                    iterations, pt, ct, StreamTokenUsage.ZERO, null, null, llmTurnPerformanceWireJson);
        }

        static AgentResult maxIterations(int max, int pt, int ct) {
            return maxIterations(max, pt, ct, null);
        }

        static AgentResult maxIterations(int max, int pt, int ct, String llmTurnPerformanceWireJson) {
            return new AgentResult(Status.MAX_ITERATIONS, "Agent reached max iterations (" + max + ")",
                    max, pt, ct, StreamTokenUsage.ZERO, null, null, llmTurnPerformanceWireJson);
        }

        static AgentResult awaitingApproval(String pendingId, int iterations, int totalPt, int totalCt,
                StreamTokenUsage assistantToolRound) {
            return awaitingApproval(pendingId, iterations, totalPt, totalCt, assistantToolRound, null);
        }

        static AgentResult awaitingApproval(String pendingId, int iterations, int totalPt, int totalCt,
                StreamTokenUsage assistantToolRound, String llmTurnPerformanceWireJson) {
            return new AgentResult(Status.AWAITING_APPROVAL, "", iterations, totalPt, totalCt,
                    assistantToolRound, pendingId, null, llmTurnPerformanceWireJson);
        }

        static AgentResult userCancelled(int iterations, int totalPt, int totalCt, String llmTurnPerformanceWireJson) {
            return new AgentResult(Status.CANCELLED, "", iterations, totalPt, totalCt, StreamTokenUsage.ZERO, null, null,
                    llmTurnPerformanceWireJson);
        }

        public Status getStatus() { return status; }
        public String getContent() { return content; }
        public int getIterations() { return iterations; }
        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int getTotalTokens() { return promptTokens + completionTokens; }
        public String getErrorCode() { return errorCode; }

        /** Tokens for the final assistant text message (one LLM round), for Stream row attribution. */
        public StreamTokenUsage getLastSuccessfulAssistantRound() {
            return lastSuccessfulAssistantRound;
        }

        /** When status is {@link Status#AWAITING_APPROVAL}, the server-generated pending id for the UI gate. */
        public String getApprovalPendingId() {
            return approvalPendingId;
        }

        /** Sanitized JSON object text with turn-level LLM performance fields (empty when none). */
        public String getLlmTurnPerformanceWireJson() {
            return llmTurnPerformanceWireJson;
        }
    }
}
