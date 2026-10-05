package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

import org.slf4j.Logger;

import com.thingworx.things.agent.compaction.LlmToolResultMatrixSealer;

/**
 * Compact {@code LLM_USAGE} log line for Phase 1 observability ({@code docs/agent/llm-token-budget.md}).
 */
public final class LlmUsageTelemetry {

    /** Max characters per comma-separated tool-name list field in {@code LLM_TOOL_SCHEMA_USAGE} (rest truncated). */
    static final int MAX_TOOL_NAME_LIST_CHARS = 8192;

    private LlmUsageTelemetry() {}

    /**
     * Dedicated probe line for {@code TestConnection} / {@link LlmClient#healthCheck()} — must not emit
     * {@code LLM_USAGE} or pollute usage aggregates ({@code docs/agent/llm-api-provider.md} §10.3).
     */
    public static void logProviderTestConnection(Logger log, LlmUsageWireIds ids, boolean success,
            int messageCount, long elapsedMs, Exception error) {
        if (log == null || !log.isInfoEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("LLM_PROVIDER_TEST_CONNECTION providerThingName=").append(ids != null ? ids.getProviderThingName() : "");
        sb.append(" providerTemplateName=").append(ids != null ? ids.getProviderTemplateName() : "");
        sb.append(" apiShapeId=").append(ids != null ? ids.getApiShapeId() : "");
        sb.append(" model=").append(ids != null ? ids.getModel() : "");
        sb.append(" status=").append(success ? "ok" : "error");
        sb.append(" elapsedMs=").append(Math.max(0, elapsedMs));
        sb.append(" messages=").append(messageCount);
        if (error != null) {
            sb.append(" errorClass=").append(error.getClass().getSimpleName());
            String msg = error.getMessage() != null ? error.getMessage() : "";
            msg = msg.replace('\r', ' ').replace('\n', ' ').trim();
            if (msg.length() > 200) {
                msg = msg.substring(0, 200) + "...";
            }
            sb.append(" errorMsg=").append(msg);
        }
        log.info(sb.toString());
    }

    /**
     * @param roundIndex 1-based iteration when called from {@link com.thingworx.things.agent.AgentLoop}; {@code null} omits {@code rounds=}.
     */
    public static void logLlmUsage(Logger log, LlmUsageWireIds ids, LlmResponse response,
            int messageCount, int toolCount, Integer roundIndex) {
        logLlmUsage(log, ids, response, messageCount, toolCount, roundIndex, null, null, null, null);
    }

    /**
     * @param rawReplayChars sum of tool-result body characters before matrix sealing for the prior batch (Phase 2)
     * @param replayChars    sum after sealing
     * @param compactRatio   {@code replay/raw} when both are available
     */
    public static void logLlmUsage(Logger log, LlmUsageWireIds ids, LlmResponse response,
            int messageCount, int toolCount, Integer roundIndex,
            Integer rawReplayChars, Integer replayChars, Double compactRatio) {
        logLlmUsage(log, ids, response, messageCount, toolCount, roundIndex, rawReplayChars, replayChars, compactRatio,
                null);
    }

    /**
     * @param parlerRequestId Parler turn request id (join key with {@code LLM_CONTEXT_PLAN.requestId}); may be
     *                        {@code null} — field is still emitted empty per {@code docs/agent/context-compaction.md} §13.
     */
    public static void logLlmUsage(Logger log, LlmUsageWireIds ids, LlmResponse response,
            int messageCount, int toolCount, Integer roundIndex,
            Integer rawReplayChars, Integer replayChars, Double compactRatio,
            String parlerRequestId) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info(formatLlmUsageLine(ids, response, messageCount, toolCount, roundIndex, rawReplayChars, replayChars,
                compactRatio, parlerRequestId));
    }

    /**
     * @visibleForTesting
     */
    static String formatLlmUsageLine(LlmUsageWireIds ids, LlmResponse response,
            int messageCount, int toolCount, Integer roundIndex,
            Integer rawReplayChars, Integer replayChars, Double compactRatio,
            String parlerRequestId) {
        StringBuilder sb = new StringBuilder();
        sb.append("LLM_USAGE providerThingName=").append(ids != null ? ids.getProviderThingName() : "");
        sb.append(" providerTemplateName=").append(ids != null ? ids.getProviderTemplateName() : "");
        sb.append(" apiShapeId=").append(ids != null ? ids.getApiShapeId() : "");
        sb.append(" model=").append(ids != null ? ids.getModel() : "");
        sb.append(" messages=").append(messageCount);
        sb.append(" tools=").append(toolCount);
        sb.append(" input=").append(response.getInputTokens());
        sb.append(" prompt=").append(response.getPromptTokens());
        sb.append(" cacheRead=").append(response.getCacheReadInputTokens());
        sb.append(" cacheCreate=").append(response.getCacheCreationInputTokens());
        sb.append(" cachedPrompt=").append(response.getCachedPromptTokens());
        sb.append(" output=").append(response.getOutputTokens());
        sb.append(" completionTokens=").append(response.getCompletionTokens());
        int reasoningTokens = response.getReasoningTokens();
        if (reasoningTokens > 0) {
            sb.append(" reasoningTokens=").append(reasoningTokens);
        }
        if (response.getProviderRequestId() != null && !response.getProviderRequestId().isBlank()) {
            sb.append(" requestId=").append(response.getProviderRequestId());
        }
        sb.append(" parlerRequestId=").append(parlerRequestId != null ? parlerRequestId : "");
        if (roundIndex != null) {
            sb.append(" rounds=").append(roundIndex);
        }
        if (rawReplayChars != null && rawReplayChars > 0) {
            sb.append(" rawReplayChars=").append(rawReplayChars);
            if (replayChars != null) {
                sb.append(" replayChars=").append(replayChars);
            }
            if (compactRatio != null) {
                sb.append(" compactRatio=").append(String.format(java.util.Locale.ROOT, "%.4f", compactRatio));
            }
        }
        return sb.toString();
    }

    /**
     * One {@code LLM_TOOL_SCHEMA_USAGE} line per LLM completion round: tools whose schemas were attached to the
     * request vs tools the model chose to call in that round (Phase 2 tool-schema footprint measurement;
     * {@code docs/agent/llm-token-budget.md}).
     */
    public static void logToolSchemaUsage(Logger log, LlmUsageWireIds ids, int roundIndex,
            List<ToolDefinition> definitions, List<ToolCall> callsInThisRound, String parlerRequestId) {
        if (log == null || !log.isInfoEnabled()) {
            return;
        }
        log.info(formatToolSchemaUsageLine(ids, roundIndex, definitions, callsInThisRound, parlerRequestId));
    }

    /**
     * @visibleForTesting
     */
    static String formatToolSchemaUsageLine(LlmUsageWireIds ids, int roundIndex,
            List<ToolDefinition> definitions, List<ToolCall> callsInThisRound, String parlerRequestId) {
        NavigableSet<String> schema = toolNamesFromDefinitions(definitions);
        NavigableSet<String> called = toolNamesFromCalls(callsInThisRound);
        NavigableSet<String> idle = new TreeSet<>(schema);
        idle.removeAll(called);
        NavigableSet<String> unknownCalls = new TreeSet<>(called);
        unknownCalls.removeAll(schema);

        String schemaJoined = joinComma(schema);
        String calledJoined = joinComma(called);
        String idleJoined = joinComma(idle);
        String unknownJoined = joinComma(unknownCalls);

        Trunc a = truncateListField(schemaJoined);
        Trunc b = truncateListField(calledJoined);
        Trunc c = truncateListField(idleJoined);
        Trunc u = truncateListField(unknownJoined);

        // M1 (docs/operations/tool-schema-admission-control.md): per-tool schema sizes so the largest fixed-overhead
        // tools are visible per round without offline reconstruction. toolSchemaChars equals the budget planner's
        // charge for this tools array; the per-tool sizes reconcile to it within toolSchemaFramingChars.
        String apiShapeId = ids != null ? ids.getApiShapeId() : "";
        int toolSchemaChars = ToolSchemaSizer.totalSchemaChars(apiShapeId, definitions);
        LinkedHashMap<String, Integer> perTool = ToolSchemaSizer.perToolSchemaChars(apiShapeId, definitions);
        int perToolSum = 0;
        for (int v : perTool.values()) {
            perToolSum += v;
        }
        Trunc s = truncateListField(joinSizesBySizeDesc(perTool));
        boolean truncated = a.truncated || b.truncated || c.truncated || u.truncated || s.truncated;

        StringBuilder sb = new StringBuilder();
        sb.append("LLM_TOOL_SCHEMA_USAGE providerThingName=").append(ids != null ? ids.getProviderThingName() : "");
        sb.append(" providerTemplateName=").append(ids != null ? ids.getProviderTemplateName() : "");
        sb.append(" apiShapeId=").append(ids != null ? ids.getApiShapeId() : "");
        sb.append(" model=").append(ids != null ? ids.getModel() : "");
        sb.append(" rounds=").append(roundIndex);
        sb.append(" parlerRequestId=").append(parlerRequestId != null ? parlerRequestId : "");
        sb.append(" schemaCount=").append(schema.size());
        sb.append(" calledCount=").append(called.size());
        sb.append(" idleCount=").append(idle.size());
        sb.append(" toolSchemaChars=").append(toolSchemaChars);
        sb.append(" toolSchemaSizesSum=").append(perToolSum);
        sb.append(" toolSchemaFramingChars=").append(toolSchemaChars - perToolSum);
        sb.append(" schemaTools=").append(a.text);
        sb.append(" calledTools=").append(b.text);
        sb.append(" idleTools=").append(c.text);
        sb.append(" toolSchemaSizes=").append(s.text);
        if (!unknownCalls.isEmpty()) {
            sb.append(" unknownCalls=").append(u.text);
        }
        if (truncated) {
            sb.append(" listTruncated=1");
        }
        return sb.toString();
    }

    /**
     * Joins per-tool sizes as {@code name:chars} ordered by descending chars (largest fixed overhead first), tie-broken
     * by name ascending, so the {@code toolSchemaSizes} field surfaces the biggest schema offenders at a glance.
     *
     * @visibleForTesting
     */
    static String joinSizesBySizeDesc(LinkedHashMap<String, Integer> perTool) {
        if (perTool == null || perTool.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(perTool.entrySet());
        entries.sort((x, y) -> {
            int byChars = Integer.compare(y.getValue(), x.getValue());
            return byChars != 0 ? byChars : x.getKey().compareTo(y.getKey());
        });
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : entries) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * @visibleForTesting
     */
    static NavigableSet<String> toolNamesFromDefinitions(List<ToolDefinition> definitions) {
        NavigableSet<String> out = new TreeSet<>();
        if (definitions == null) {
            return out;
        }
        for (ToolDefinition d : definitions) {
            if (d == null) {
                continue;
            }
            String n = d.getName();
            if (n != null && !n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * @visibleForTesting
     */
    static NavigableSet<String> toolNamesFromCalls(List<ToolCall> calls) {
        NavigableSet<String> out = new TreeSet<>();
        if (calls == null) {
            return out;
        }
        for (ToolCall tc : calls) {
            if (tc == null) {
                continue;
            }
            String n = tc.getFunctionName();
            if (n != null && !n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }

    private static String joinComma(Collection<String> sorted) {
        if (sorted == null || sorted.isEmpty()) {
            return "";
        }
        return String.join(",", sorted);
    }

    private static final class Trunc {
        final String text;
        final boolean truncated;

        Trunc(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }

    private static Trunc truncateListField(String joined) {
        if (joined == null || joined.isEmpty()) {
            return new Trunc("", false);
        }
        if (joined.length() <= MAX_TOOL_NAME_LIST_CHARS) {
            return new Trunc(joined, false);
        }
        return new Trunc(joined.substring(0, MAX_TOOL_NAME_LIST_CHARS) + "...", true);
    }

    /**
     * @visibleForTesting Build the same comma-separated tool lists as {@link #logToolSchemaUsage} for assertions.
     */
    static String previewToolSchemaUsageLists(List<ToolDefinition> definitions, List<ToolCall> callsInThisRound) {
        NavigableSet<String> schema = toolNamesFromDefinitions(definitions);
        NavigableSet<String> called = toolNamesFromCalls(callsInThisRound);
        NavigableSet<String> idle = new TreeSet<>(schema);
        idle.removeAll(called);
        List<String> parts = new ArrayList<>();
        parts.add("schemaTools=" + joinComma(schema));
        parts.add("calledTools=" + joinComma(called));
        parts.add("idleTools=" + joinComma(idle));
        return String.join(" ", parts);
    }

    /**
     * Logs pending Phase 2 replay compaction stats when the subsequent LLM request fails before a successful
     * {@link #logLlmUsage} line (e.g. HTTP 429) so diagnostics still see prior-batch {@code rawReplayChars} /
     * {@code replayChars} / {@code compactRatio}.
     */
    public static void logPendingReplayCompaction(Logger log, LlmUsageWireIds ids, int messageCount,
            int toolCount, Integer roundIndex, LlmToolResultMatrixSealer.SealStats pending) {
        if (log == null || !log.isInfoEnabled() || pending == null || pending.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("LLM_REPLAY_PENDING_COMPACTION providerThingName=").append(ids != null ? ids.getProviderThingName() : "");
        sb.append(" providerTemplateName=").append(ids != null ? ids.getProviderTemplateName() : "");
        sb.append(" apiShapeId=").append(ids != null ? ids.getApiShapeId() : "");
        sb.append(" model=").append(ids != null ? ids.getModel() : "");
        sb.append(" messages=").append(messageCount);
        sb.append(" tools=").append(toolCount);
        if (roundIndex != null) {
            sb.append(" rounds=").append(roundIndex);
        }
        sb.append(" rawReplayChars=").append(pending.getRawReplayChars());
        sb.append(" replayChars=").append(pending.getReplayChars());
        if (pending.getCompactRatio() != null) {
            sb.append(" compactRatio=").append(String.format(Locale.ROOT, "%.4f", pending.getCompactRatio()));
        }
        sb.append(" note=prior_batch_replay_stats_not_attached_to_llm_usage");
        log.info(sb.toString());
    }

    /**
     * One compact line after {@link com.thingworx.things.agent.AgentLoop} completes ({@code docs/agent/llm-performance.md}).
     */
    public static void logLlmTurnPerformance(Logger log, LlmTurnPerformanceFields f) {
        if (log == null || !log.isInfoEnabled() || f == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("LLM_TURN_PERFORMANCE");
        sb.append(" conversationId=").append(nullToEmpty(f.conversationId));
        sb.append(" requestId=").append(nullToEmpty(f.requestId));
        sb.append(" agentIterations=").append(Math.max(0, f.agentIterations));
        sb.append(" toolCallCount=").append(Math.max(0, f.toolCallCount));
        sb.append(" llmWallMs=").append(Math.max(0L, f.llmWallMs));
        sb.append(" toolWallMs=").append(Math.max(0L, f.toolWallMs));
        sb.append(" rateWaitMs=").append(Math.max(0L, f.rateWaitMs));
        sb.append(" turnWallMs=").append(Math.max(0L, f.turnWallMs));
        sb.append(" promptTokensTotal=").append(Math.max(0, f.promptTokensTotal));
        sb.append(" completionTokensTotal=").append(Math.max(0, f.completionTokensTotal));
        if (f.reasoningTokensTotal > 0) {
            sb.append(" reasoningTokensTotal=").append(f.reasoningTokensTotal);
        }
        sb.append(" requestedMaxOutputTokensTotal=").append(Math.max(0L, f.requestedMaxOutputTokensTotal));
        sb.append(" finalAnswerRoundIndex=").append(f.finalAnswerRoundIndex);
        sb.append(" fetchAfterCompleteAnswerSetCount=").append(Math.max(0, f.fetchAfterCompleteAnswerSetCount));
        sb.append(" roundsHitMaxOutput=").append(Math.max(0, f.roundsHitMaxOutput));
        sb.append(" firstToolCallCacheHit=").append(nullToEmpty(f.firstToolCallCacheHit));
        sb.append(" markerEmitterEnabled=").append(f.markerEmitterEnabled);
        sb.append(" noToolFinalAnswerApplied=").append(f.noToolFinalAnswerApplied);
        sb.append(" toolExecutionMaxConcurrency=").append(Math.max(1, f.toolExecutionMaxConcurrency));
        sb.append(" multiToolCallRoundsCount=").append(Math.max(0, f.multiToolCallRoundsCount));
        sb.append(" chartExpectedButMissing=").append(f.chartExpectedButMissing);
        sb.append(" chartRescueAttempted=").append(f.chartRescueAttempted);
        sb.append(" repetitionBlockedCount=").append(Math.max(0, f.repetitionBlockedCount));
        sb.append(" parlerChartWireEmittedCount=").append(Math.max(0, f.parlerChartWireEmittedCount));
        sb.append(" presentationPhaseEntered=").append(f.presentationPhaseEntered);
        sb.append(" presentationActionsRequested=").append(Math.max(0, f.presentationActionsRequested));
        sb.append(" presentationActionsExecuted=").append(Math.max(0, f.presentationActionsExecuted));
        sb.append(" presentationActionsBlocked=").append(Math.max(0, f.presentationActionsBlocked));
        if (f.toolProtocolViolation != null && !f.toolProtocolViolation.isEmpty()) {
            sb.append(" toolProtocolViolation=").append(f.toolProtocolViolation);
        }
        log.info(sb.toString());
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    /**
     * {@code requestId=} for {@code LLM_CONTEXT_PLAN} must follow the same emptiness contract as {@code LLM_USAGE}
     * when no provider request id exists yet ({@code docs/agent/context-compaction.md} §13).
     */
    public static String effectiveRequestIdForContextPlan(String parlerRequestIdOrNull) {
        return parlerRequestIdOrNull != null ? parlerRequestIdOrNull : "";
    }

    /**
     * One {@code LLM_CONTEXT_PLAN} line per LLM round (Slice A telemetry only; {@code docs/agent/context-compaction.md}).
     */
    public static void logLlmContextPlan(
            Logger log,
            LlmUsageWireIds ids,
            String conversationId,
            String requestId,
            int messageCount,
            int toolCount,
            int stableChars,
            int toolSchemaChars,
            int ephemeralChars,
            int currentUserChars,
            int activeBatchReserveChars,
            int transcriptChars,
            int evidenceRawChars,
            int evidenceChars,
            int droppedTranscript,
            int droppedEvidence,
            int droppedAssistantBatches,
            int configuredCapChars,
            long effectiveRequestCapChars,
            long historyBudgetChars,
            int historyClampedToZero,
            int unsafeDisable,
            int checkpointChars) {
        if (log == null || !log.isInfoEnabled()) {
            return;
        }
        log.info(formatLlmContextPlanLine(
                ids,
                conversationId,
                requestId,
                messageCount,
                toolCount,
                stableChars,
                toolSchemaChars,
                ephemeralChars,
                currentUserChars,
                activeBatchReserveChars,
                transcriptChars,
                evidenceRawChars,
                evidenceChars,
                droppedTranscript,
                droppedEvidence,
                droppedAssistantBatches,
                configuredCapChars,
                effectiveRequestCapChars,
                historyBudgetChars,
                historyClampedToZero,
                unsafeDisable,
                checkpointChars));
    }

    /**
     * Structured failure line before {@code ContextBudgetExceededException} when the planner cannot emit
     * {@code LLM_CONTEXT_PLAN} for the successful path ({@code docs/agent/context-compaction.md} §13).
     */
    public static void logLlmContextPlanFail(
            Logger log,
            LlmUsageWireIds ids,
            String conversationId,
            String requestId,
            String reason,
            int messageCount,
            int toolCount,
            int stableChars,
            int toolSchemaChars,
            int ephemeralChars,
            int currentUserChars,
            int activeBatchReserveChars,
            int transcriptChars,
            int evidenceRawChars,
            int evidenceChars,
            int droppedTranscript,
            int droppedEvidence,
            int droppedAssistantBatches,
            int configuredCapChars,
            long effectiveRequestCapChars,
            long historyBudgetChars,
            int historyClampedToZero,
            int unsafeDisable,
            int checkpointChars) {
        if (log == null || !log.isErrorEnabled()) {
            return;
        }
        log.error(formatLlmContextPlanFailLine(
                reason,
                ids,
                conversationId,
                requestId,
                messageCount,
                toolCount,
                stableChars,
                toolSchemaChars,
                ephemeralChars,
                currentUserChars,
                activeBatchReserveChars,
                transcriptChars,
                evidenceRawChars,
                evidenceChars,
                droppedTranscript,
                droppedEvidence,
                droppedAssistantBatches,
                configuredCapChars,
                effectiveRequestCapChars,
                historyBudgetChars,
                historyClampedToZero,
                unsafeDisable,
                checkpointChars));
    }

    /**
     * @visibleForTesting
     */
    static String formatLlmContextPlanLine(
            LlmUsageWireIds ids,
            String conversationId,
            String requestId,
            int messageCount,
            int toolCount,
            int stableChars,
            int toolSchemaChars,
            int ephemeralChars,
            int currentUserChars,
            int activeBatchReserveChars,
            int transcriptChars,
            int evidenceRawChars,
            int evidenceChars,
            int droppedTranscript,
            int droppedEvidence,
            int droppedAssistantBatches,
            int configuredCapChars,
            long effectiveRequestCapChars,
            long historyBudgetChars,
            int historyClampedToZero,
            int unsafeDisable,
            int checkpointChars) {
        StringBuilder sb = new StringBuilder(384);
        sb.append("LLM_CONTEXT_PLAN ");
        appendLlmContextPlanFieldTail(
                sb,
                ids,
                conversationId,
                requestId,
                messageCount,
                toolCount,
                stableChars,
                toolSchemaChars,
                ephemeralChars,
                currentUserChars,
                activeBatchReserveChars,
                transcriptChars,
                evidenceRawChars,
                evidenceChars,
                droppedTranscript,
                droppedEvidence,
                droppedAssistantBatches,
                configuredCapChars,
                effectiveRequestCapChars,
                historyBudgetChars,
                historyClampedToZero,
                unsafeDisable,
                checkpointChars);
        return sb.toString();
    }

    /**
     * @visibleForTesting
     */
    static String formatLlmContextPlanFailLine(
            String reason,
            LlmUsageWireIds ids,
            String conversationId,
            String requestId,
            int messageCount,
            int toolCount,
            int stableChars,
            int toolSchemaChars,
            int ephemeralChars,
            int currentUserChars,
            int activeBatchReserveChars,
            int transcriptChars,
            int evidenceRawChars,
            int evidenceChars,
            int droppedTranscript,
            int droppedEvidence,
            int droppedAssistantBatches,
            int configuredCapChars,
            long effectiveRequestCapChars,
            long historyBudgetChars,
            int historyClampedToZero,
            int unsafeDisable,
            int checkpointChars) {
        StringBuilder sb = new StringBuilder(420);
        sb.append("LLM_CONTEXT_PLAN_FAIL reason=").append(reason != null ? reason : "");
        sb.append(" ");
        appendLlmContextPlanFieldTail(
                sb,
                ids,
                conversationId,
                requestId,
                messageCount,
                toolCount,
                stableChars,
                toolSchemaChars,
                ephemeralChars,
                currentUserChars,
                activeBatchReserveChars,
                transcriptChars,
                evidenceRawChars,
                evidenceChars,
                droppedTranscript,
                droppedEvidence,
                droppedAssistantBatches,
                configuredCapChars,
                effectiveRequestCapChars,
                historyBudgetChars,
                historyClampedToZero,
                unsafeDisable,
                checkpointChars);
        return sb.toString();
    }

    private static void appendLlmContextPlanFieldTail(
            StringBuilder sb,
            LlmUsageWireIds ids,
            String conversationId,
            String requestId,
            int messageCount,
            int toolCount,
            int stableChars,
            int toolSchemaChars,
            int ephemeralChars,
            int currentUserChars,
            int activeBatchReserveChars,
            int transcriptChars,
            int evidenceRawChars,
            int evidenceChars,
            int droppedTranscript,
            int droppedEvidence,
            int droppedAssistantBatches,
            int configuredCapChars,
            long effectiveRequestCapChars,
            long historyBudgetChars,
            int historyClampedToZero,
            int unsafeDisable,
            int checkpointChars) {
        sb.append("providerThingName=").append(ids != null ? ids.getProviderThingName() : "");
        sb.append(" provider=").append(ids != null ? ids.getApiShapeId() : "");
        sb.append(" model=").append(ids != null ? ids.getModel() : "");
        sb.append(" conversationId=").append(conversationId != null ? conversationId : "");
        sb.append(" requestId=").append(requestId != null ? requestId : "");
        sb.append(" messages=").append(messageCount);
        sb.append(" tools=").append(toolCount);
        sb.append(" stableChars=").append(stableChars);
        sb.append(" toolSchemaChars=").append(toolSchemaChars);
        sb.append(" ephemeralChars=").append(ephemeralChars);
        sb.append(" currentUserChars=").append(currentUserChars);
        sb.append(" activeBatchReserveChars=").append(activeBatchReserveChars);
        sb.append(" transcriptChars=").append(transcriptChars);
        sb.append(" evidenceRawChars=").append(evidenceRawChars);
        sb.append(" evidenceChars=").append(evidenceChars);
        sb.append(" droppedTranscript=").append(droppedTranscript);
        sb.append(" droppedEvidence=").append(droppedEvidence);
        sb.append(" droppedAssistantBatches=").append(droppedAssistantBatches);
        sb.append(" configuredCapChars=").append(configuredCapChars);
        sb.append(" effectiveRequestCapChars=").append(effectiveRequestCapChars);
        sb.append(" historyBudgetChars=").append(historyBudgetChars);
        sb.append(" historyClampedToZero=").append(historyClampedToZero);
        sb.append(" unsafeDisable=").append(unsafeDisable);
        // Appended, never inserted: §10.1 requires the existing field positions to stay stable for
        // downstream log parsing when a new component is added.
        sb.append(" checkpointChars=").append(checkpointChars);
    }

    /** Mutable bag for {@link #logLlmTurnPerformance}; AgentLoop fills and passes once per turn. */
    public static final class LlmTurnPerformanceFields {
        public String conversationId;
        public String requestId;
        public int agentIterations;
        public int toolCallCount;
        public long llmWallMs;
        public long toolWallMs;
        public long rateWaitMs;
        public long turnWallMs;
        public int promptTokensTotal;
        public int completionTokensTotal;
        /** Sum of per-round {@code reasoningTokens} when the provider exposes them; 0 when absent. */
        public int reasoningTokensTotal;
        public long requestedMaxOutputTokensTotal;
        public int finalAnswerRoundIndex = -1;
        public int fetchAfterCompleteAnswerSetCount;
        public int roundsHitMaxOutput;
        public String firstToolCallCacheHit = "unknown";
        public boolean markerEmitterEnabled;
        public boolean noToolFinalAnswerApplied;
        /** Agent runtime tool execution concurrency (today: 1 = serial). */
        public int toolExecutionMaxConcurrency = 1;
        /** Count of LLM rounds (excluding post-marker and chart end-turn rescue singleton rounds) where the model returned more than one tool call. */
        public int multiToolCallRoundsCount;
        public String toolProtocolViolation = "";
        /**
         * {@code true} when {@code build_chart_from_tabular_result} was invoked this Parler turn and no
         * {@code type: chart} frame was successfully downlinked ({@code parlerChartWireEmittedCount == 0};
         * behavior-derived — no user-message heuristic). See
         * {@code docs/agent/multi-chart-and-thrashing-safeguards.md} §4.3 Fix B3.
         */
        public boolean chartExpectedButMissing;
        /**
         * When {@code true}, {@link com.thingworx.things.agent.AgentLoop} scheduled one end-of-turn singleton
         * {@code build_chart_from_tabular_result} rescue round after prose without a chart wire
         * ({@code docs/agent/prompt-to-chart.md} §7.6).
         */
        public boolean chartRescueAttempted;
        /**
         * Count of synthetic {@code REPETITION_BLOCKED} tool result envelopes returned this {@code AgentLoop} run
         * ({@code docs/agent/multi-chart-and-thrashing-safeguards.md} §3).
         */
        public int repetitionBlockedCount;
        /**
         * Count of {@code type: chart} wire frames emitted this Parler turn ({@code docs/agent/multi-chart-and-thrashing-safeguards.md}
         * §4.3 Fix B3).
         */
        public int parlerChartWireEmittedCount;
        /** Answer Presentation Phase (aggregate-first post-marker chart exposure). */
        public boolean presentationPhaseEntered;
        public int presentationActionsRequested;
        public int presentationActionsExecuted;
        public int presentationActionsBlocked;
    }
}
