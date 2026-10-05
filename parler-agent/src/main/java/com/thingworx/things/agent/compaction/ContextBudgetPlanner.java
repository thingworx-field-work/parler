package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;

import org.slf4j.Logger;

import com.thingworx.things.agent.ParlerEphemeralSystemIndices;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LeadingSystemRow;
import com.thingworx.things.agent.llm.LlmUsageTelemetry;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.LlmUtcClockInjector;
import com.thingworx.things.agent.llm.ParlerSuffixFraming;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.ToolSchemaSizer;
import com.thingworx.things.agent.taskstate.TaskStateLlmInjector;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Slice A/C: deterministic context budget telemetry and Slice C drop-only outbound trimming before each provider call.
 * Trimming never mutates {@link ChatMessage} instances or the input {@code messages} list; it returns a new list of
 * references ({@code docs/agent/context-compaction.md} §5, §16).
 */
public final class ContextBudgetPlanner {

    private ContextBudgetPlanner() {}

    /**
     * Immutable computed metrics for one planner invocation (tests and telemetry).
     */
    public static final class Metrics {
        public final int stableChars;
        public final int toolSchemaChars;
        public final int ephemeralChars;
        public final int currentUserChars;
        public final int activeBatchReserveChars;
        public final int transcriptChars;
        public final int evidenceRawChars;
        public final int evidenceChars;
        public final int droppedTranscript;
        public final int droppedEvidence;
        public final int droppedAssistantBatches;
        public final int configuredCapChars;
        public final long effectiveRequestCapChars;
        public final long historyBudgetChars;
        public final int historyClampedToZero;
        public final int unsafeDisable;
        /**
         * §10.1: the injected checkpoint's chars, counted as fixed non-history overhead rather than as transcript.
         * Derived from the message list under consideration, never passed in, so every nested
         * {@link #compute} stays self-consistent with the subset it was handed.
         */
        public final int checkpointChars;

        Metrics(int stableChars, int toolSchemaChars, int ephemeralChars, int currentUserChars,
                int activeBatchReserveChars, int transcriptChars, int evidenceRawChars, int evidenceChars,
                int droppedTranscript, int droppedEvidence, int droppedAssistantBatches,
                int configuredCapChars, long effectiveRequestCapChars, long historyBudgetChars,
                int historyClampedToZero, int unsafeDisable, int checkpointChars) {
            this.stableChars = stableChars;
            this.toolSchemaChars = toolSchemaChars;
            this.ephemeralChars = ephemeralChars;
            this.currentUserChars = currentUserChars;
            this.activeBatchReserveChars = activeBatchReserveChars;
            this.transcriptChars = transcriptChars;
            this.evidenceRawChars = evidenceRawChars;
            this.evidenceChars = evidenceChars;
            this.droppedTranscript = droppedTranscript;
            this.droppedEvidence = droppedEvidence;
            this.droppedAssistantBatches = droppedAssistantBatches;
            this.configuredCapChars = configuredCapChars;
            this.effectiveRequestCapChars = effectiveRequestCapChars;
            this.historyBudgetChars = historyBudgetChars;
            this.historyClampedToZero = historyClampedToZero;
            this.unsafeDisable = unsafeDisable;
            this.checkpointChars = checkpointChars;
        }

        public static Metrics compute(
                List<ChatMessage> messages,
                List<ToolDefinition> defsForRound,
                LlmUsageWireIds wireIds,
                int llmContextMaxCharsConfigured) {
            return compute(messages, defsForRound, wireIds, llmContextMaxCharsConfigured, 0L);
        }

        public static Metrics compute(
                List<ChatMessage> messages,
                List<ToolDefinition> defsForRound,
                LlmUsageWireIds wireIds,
                int llmContextMaxCharsConfigured,
                long providerRequestCapChars) {
            if (messages == null || messages.isEmpty()) {
                String apiShape = wireIds != null ? wireIds.getApiShapeId() : "";
                String model = wireIds != null ? wireIds.getModel() : "";
                int configuredCapChars = Math.max(1, llmContextMaxCharsConfigured);
                long effectiveRequestCapChars = effectiveRequestCapChars(
                        apiShape, model, configuredCapChars, providerRequestCapChars);
                long historyBudgetChars = effectiveRequestCapChars;
                int historyClampedToZero = historyBudgetChars < 0 ? 1 : 0;
                int unsafeDisable = LlmReplayCompactionGate.isUnsafeDiagnosticsDisable() ? 1 : 0;
                return new Metrics(
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        configuredCapChars,
                        effectiveRequestCapChars,
                        historyBudgetChars,
                        historyClampedToZero,
                        unsafeDisable,
                        0);
            }
            String apiShape = wireIds != null ? wireIds.getApiShapeId() : "";
            String model = wireIds != null ? wireIds.getModel() : "";
            int stableChars = computeStableChars(messages);
            int ephemeralChars = computeEphemeralChars(messages);
            int toolSchemaChars = estimateToolSchemaChars(apiShape, defsForRound);
            int lastUserIdx = findLastUserIndex(messages);
            int currentUserChars = lastUserIdx >= 0 ? len(messages.get(lastUserIdx).getContent()) : 0;
            int[] activeRange = findActiveAssistantToolBatchRange(messages);
            int activeBatchReserveChars = sumRowCharsInRange(messages, activeRange);
            int checkpointChars = computeCheckpointChars(messages);
            int transcriptChars = computeTranscriptChars(messages, lastUserIdx);
            int evidenceRawChars = computeEvidenceChars(messages, activeRange);
            int evidenceChars = evidenceRawChars;
            int configuredCapChars = Math.max(1, llmContextMaxCharsConfigured);
            long effectiveRequestCapChars = effectiveRequestCapChars(
                    apiShape, model, configuredCapChars, providerRequestCapChars);
            long historyBudgetChars = effectiveRequestCapChars
                    - (long) stableChars
                    - (long) toolSchemaChars
                    - (long) ephemeralChars
                    - (long) currentUserChars
                    - (long) activeBatchReserveChars
                    - (long) checkpointChars;
            int historyClampedToZero = historyBudgetChars < 0 ? 1 : 0;
            int unsafeDisable = LlmReplayCompactionGate.isUnsafeDiagnosticsDisable() ? 1 : 0;
            return new Metrics(
                    stableChars,
                    toolSchemaChars,
                    ephemeralChars,
                    currentUserChars,
                    activeBatchReserveChars,
                    transcriptChars,
                    evidenceRawChars,
                    evidenceChars,
                    0,
                    0,
                    0,
                    configuredCapChars,
                    effectiveRequestCapChars,
                    historyBudgetChars,
                    historyClampedToZero,
                    unsafeDisable,
                    checkpointChars);
        }

        private static long effectiveRequestCapChars(
                String apiShape, String model, int configuredCapChars, long providerRequestCapChars) {
            long effective = configuredCapChars;
            OptionalInt providerLimit = ProviderModelInputLimitRegistry.lookupInputTokenLimit(apiShape, model);
            if (providerLimit.isPresent()) {
                long scaled = Math.round(providerLimit.getAsInt() * 3.5d);
                effective = Math.min(effective, scaled);
            }
            if (providerRequestCapChars > 0L) {
                effective = Math.min(effective, providerRequestCapChars);
            }
            return Math.max(1L, effective);
        }
    }

    /** Result of Slice C planning: outbound message references for one provider call plus logged metrics. */
    public static final class PlannedOutbound {
        private final List<ChatMessage> outboundMessages;
        private final Metrics plannedMetrics;

        PlannedOutbound(List<ChatMessage> outboundMessages, Metrics plannedMetrics) {
            this.outboundMessages = outboundMessages;
            this.plannedMetrics = plannedMetrics;
        }

        public List<ChatMessage> getOutboundMessages() {
            return outboundMessages;
        }

        public Metrics getPlannedMetrics() {
            return plannedMetrics;
        }
    }

    /**
     * Slice C: builds a trimmed outbound copy (drop-only), logs {@code LLM_CONTEXT_PLAN} with post-trim metrics, and
     * never mutates {@code messages} or {@link ChatMessage} instances.
     *
     * @param taskStateInsertedIdx index from {@link TaskStateLlmInjector#insertForApiRound} on {@code messages}, or -1
     * @param utcClockInsertedIdx index from {@link LlmUtcClockInjector#insertForApiRound} on {@code messages}, or -1
     */
    public static PlannedOutbound planForProviderRound(
            Logger log,
            List<ChatMessage> messages,
            List<ToolDefinition> defsForRound,
            LlmUsageWireIds wireIds,
            int llmContextMaxCharsConfigured,
            int taskStateInsertedIdx,
            int utcClockInsertedIdx) {
        return planForProviderRound(log, messages, defsForRound, wireIds, llmContextMaxCharsConfigured, 0L,
                taskStateInsertedIdx, utcClockInsertedIdx);
    }

    public static PlannedOutbound planForProviderRound(
            Logger log,
            List<ChatMessage> messages,
            List<ToolDefinition> defsForRound,
            LlmUsageWireIds wireIds,
            int llmContextMaxCharsConfigured,
            long providerRequestCapChars,
            int taskStateInsertedIdx,
            int utcClockInsertedIdx) {
        if (messages == null || messages.isEmpty()) {
            return new PlannedOutbound(
                    new ArrayList<>(),
                    Metrics.compute(Collections.emptyList(), defsForRound, wireIds, llmContextMaxCharsConfigured,
                            providerRequestCapChars));
        }
        PlannedOutbound p = buildPlannedOutbound(
                log,
                messages,
                defsForRound,
                wireIds,
                llmContextMaxCharsConfigured,
                providerRequestCapChars,
                taskStateInsertedIdx,
                utcClockInsertedIdx);
        emitPlanInternal(log, wireIds, p, defsForRound != null ? defsForRound.size() : 0);
        return p;
    }

    /**
     * Slice A: logs {@code LLM_CONTEXT_PLAN} from {@link Metrics#compute} on the <strong>untrimmed</strong>
     * {@code messages} list. Does not trim, mutate the list, or throw {@link ContextBudgetExceededException} (Slice C
     * enforcement uses {@link #planForProviderRound}).
     */
    public static void planAndLog(
            Logger log,
            List<ChatMessage> messages,
            List<ToolDefinition> defsForRound,
            LlmUsageWireIds wireIds,
            int llmContextMaxCharsConfigured) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Metrics m = Metrics.compute(messages, defsForRound, wireIds, llmContextMaxCharsConfigured);
        PlannedOutbound p = new PlannedOutbound(messages, m);
        emitPlanInternal(log, wireIds, p, defsForRound != null ? defsForRound.size() : 0);
    }

    private static void emitPlanInternal(Logger log, LlmUsageWireIds wireIds, PlannedOutbound p, int toolCount) {
        List<ChatMessage> out = p.getOutboundMessages();
        if (out == null || out.isEmpty()) {
            return;
        }
        if (LlmReplayCompactionGate.isUnsafeDiagnosticsDisable()) {
            if (log != null && log.isWarnEnabled()) {
                log.warn(
                        "LLM_CONTEXT_PLAN: JVM property {}=true suppresses Tier A/0/B replay compaction for this process "
                                + "(diagnostic only; see docs/agent/context-compaction.md §6).",
                        LlmReplayCompactionGate.UNSAFE_DIAGNOSTICS_PROPERTY);
            }
        }
        if (log == null || !log.isInfoEnabled()) {
            return;
        }
        Metrics m = p.getPlannedMetrics();
        String conversationId = AgentToolContext.getConversationId() != null ? AgentToolContext.getConversationId() : "";
        String requestId = LlmUsageTelemetry.effectiveRequestIdForContextPlan(AgentToolContext.getParlerRequestId());
        LlmUsageTelemetry.logLlmContextPlan(
                log,
                wireIds,
                conversationId,
                requestId,
                out.size(),
                toolCount,
                m.stableChars,
                m.toolSchemaChars,
                m.ephemeralChars,
                m.currentUserChars,
                m.activeBatchReserveChars,
                m.transcriptChars,
                m.evidenceRawChars,
                m.evidenceChars,
                m.droppedTranscript,
                m.droppedEvidence,
                m.droppedAssistantBatches,
                m.configuredCapChars,
                m.effectiveRequestCapChars,
                m.historyBudgetChars,
                m.historyClampedToZero,
                m.unsafeDisable,
                m.checkpointChars);
    }

    /**
     * §9.2: whether this provider's request contract is <b>known</b> to carry the injected checkpoint as a leading
     * assistant row.
     *
     * <p><b>Affirmative, not exclusionary.</b> This returns {@code true} only for the Chat Completions shape
     * families whose acceptance is established by a vendor-contract check and a fixture; every other shape —
     * including {@code null}, blank, and any adapter added later — omits the checkpoint until it earns the same
     * evidence. Listing known rejecters instead would repeat the inference that produced the Anthropic defect:
     * absence of a known rejection is not evidence of acceptance. Omitting costs one conversation its navigation
     * aid; carrying wrongly costs the user their chat, which §8.4 forbids outright.
     *
     * <p>The Anthropic Messages API is the concrete rejecter: it requires the first message to use the
     * {@code user} role, and {@code AnthropicMessagesApi.buildAnthropicMessages} hoists {@code SYSTEM} rows out of
     * {@code messages}, so an injected checkpoint would be the first API message.
     *
     * <p>A serializer-level strip would be too late: the planner would still report {@code checkpointChars > 0}
     * for a request that carries no checkpoint, which §10.1 forbids. The decision therefore belongs here, before
     * any metric is computed.
     */
    public static boolean providerCarriesInjectedCheckpoint(LlmUsageWireIds wireIds) {
        String apiShapeId = wireIds != null ? wireIds.getApiShapeId() : null;
        if (apiShapeId == null || apiShapeId.isBlank()) {
            return false;
        }
        String shape = apiShapeId.trim().toLowerCase(java.util.Locale.ROOT);
        return shape.startsWith("openai-chat-completions-")
                || shape.startsWith("azure-openai-chat-completions-");
    }

    private static String conversationIdForTelemetry() {
        String cid = AgentToolContext.getConversationId();
        return cid != null ? cid : "";
    }

    private static String requestIdForTelemetry() {
        // The planner runs inside the turn, so turn context is still bound here — unlike post-turn normalization,
        // which runs after AgentToolContext.clear() and takes the identity as a parameter.
        return LlmUsageTelemetry.effectiveRequestIdForContextPlan(AgentToolContext.getParlerRequestId());
    }

    private static PlannedOutbound buildPlannedOutbound(
            Logger log,
            List<ChatMessage> messages,
            List<ToolDefinition> defs,
            LlmUsageWireIds wireIds,
            int cap,
            long providerRequestCapChars,
            int taskStateInsertedIdx,
            int utcClockInsertedIdx) {
        int n = messages.size();
        boolean[] keep = new boolean[n];
        Arrays.fill(keep, true);

        // §10.1 defensive newest-only normalization, BEFORE any budgeting. Normalization already guarantees at most
        // one working checkpoint, so this branch should never fire; if it does, budgeting over a stale state as well
        // as the current one would both break §5 invariant 2 and charge the request for continuity it must not send.
        List<Integer> checkpointIdx = injectedCheckpointIndices(messages);
        int candidateIdx = checkpointIdx.isEmpty() ? -1 : checkpointIdx.get(checkpointIdx.size() - 1);
        if (checkpointIdx.size() > 1) {
            for (int k = 0; k + 1 < checkpointIdx.size(); k++) {
                keep[checkpointIdx.get(k)] = false;
            }
            if (log != null && log.isWarnEnabled()) {
                // Not a §12 checkpoint event: this reports an upstream invariant breach, not a checkpoint decision.
                log.warn("LLM_CONTEXT_PLAN: {} working checkpoints present; budgeting the newest and clearing the "
                        + "older ones (§5 invariant 2 should have made this unreachable)", checkpointIdx.size());
            }
        }
        // §9.2 provider gate, ahead of budgeting so the metrics describe the request actually sent. A provider
        // whose request contract cannot carry the checkpoint as a leading assistant row omits it entirely — it is
        // never promoted to a system row, because that would give model-generated navigation the authority of the
        // server's own prompt.
        if (candidateIdx >= 0 && !providerCarriesInjectedCheckpoint(wireIds)) {
            keep[candidateIdx] = false;
            candidateIdx = -1;
            ConversationCheckpointEvents.skip(log, conversationIdForTelemetry(), requestIdForTelemetry(),
                    ConversationCheckpointEvents.REASON_PROVIDER_SHAPE_UNSUPPORTED, 0L);
        }
        Metrics m0 = Metrics.compute(materialize(messages, keep), defs, wireIds, cap, providerRequestCapChars);

        // §10.1 recovery step, inserted ahead of the existing failure: a checkpoint is a non-authoritative
        // continuity enhancement and must never turn a serviceable request into a hard failure. Omitting it clears a
        // bit in the keep mask — the mask is authoritative over ORIGINAL indices, so nothing is spliced out of
        // messages and no inserted-index parameter is recomputed. The drop helpers already skip !keep[i].
        if (m0.historyBudgetChars < 0) {
            // Only the candidate is at stake here: the older markers are already out, so CHECKPOINT_CANNOT_FIT
            // reports that the newest checkpoint itself does not fit, which is the only thing it may mean.
            if (candidateIdx >= 0) {
                keep[candidateIdx] = false;
                m0 = Metrics.compute(materialize(messages, keep), defs, wireIds, cap, providerRequestCapChars);
                ConversationCheckpointEvents.skip(log, conversationIdForTelemetry(), requestIdForTelemetry(),
                        ConversationCheckpointEvents.REASON_CHECKPOINT_CANNOT_FIT, 0L);
            }
            if (m0.historyBudgetChars < 0) {
                // Still negative once the checkpoint is out: ordinary overhead genuinely exceeds the cap, which is
                // today's failure for today's causes.
                logContextPlanFail(log, wireIds, ContextBudgetExceededException.Reason.OVERHEAD_EXCEEDS_CAP, m0, n,
                        defs);
                throw new ContextBudgetExceededException(
                        ContextBudgetExceededException.Reason.OVERHEAD_EXCEEDS_CAP,
                        "LLM_CONTEXT_PLAN: non-history overhead exceeds effectiveRequestCapChars (historyBudgetChars="
                                + m0.historyBudgetChars + ")");
            }
        }
        if (historyWithinBudget(materialize(messages, keep), defs, wireIds, cap, providerRequestCapChars)) {
            return finishPlanned(messages, keep, m0, defs, wireIds, cap, providerRequestCapChars, 0);
        }
        int[] activeRange = findActiveAssistantToolBatchRange(messages);
        int lastUser = findLastUserIndex(messages);
        boolean[] protect = buildProtectedMask(n, messages, activeRange, lastUser, taskStateInsertedIdx,
                utcClockInsertedIdx);
        int droppedBatches = 0;
        while (!historyWithinBudget(materialize(messages, keep), defs, wireIds, cap, providerRequestCapChars)) {
            if (tryDropOldestEvidenceBatch(messages, keep, protect, activeRange)) {
                droppedBatches++;
                continue;
            }
            if (tryDropOldestTranscriptPair(messages, keep, protect, lastUser)) {
                continue;
            }
            // Cumulative drop counts for the trim pass (Metrics.compute always zeros these).
            List<ChatMessage> partial = materialize(messages, keep);
            Metrics mPartialSizes = Metrics.compute(partial, defs, wireIds, cap, providerRequestCapChars);
            int lastUserFull = findLastUserIndex(messages);
            int[] activeFull = findActiveAssistantToolBatchRange(messages);
            int droppedTranscriptRows = countDroppedTranscriptRows(messages, keep, lastUserFull);
            int droppedEvidenceToolRows = countDroppedToolEvidenceRows(messages, keep, activeFull);
            Metrics mFail = new Metrics(
                    mPartialSizes.stableChars,
                    mPartialSizes.toolSchemaChars,
                    mPartialSizes.ephemeralChars,
                    mPartialSizes.currentUserChars,
                    mPartialSizes.activeBatchReserveChars,
                    mPartialSizes.transcriptChars,
                    m0.evidenceRawChars,
                    mPartialSizes.evidenceChars,
                    droppedTranscriptRows,
                    droppedEvidenceToolRows,
                    droppedBatches,
                    mPartialSizes.configuredCapChars,
                    mPartialSizes.effectiveRequestCapChars,
                    mPartialSizes.historyBudgetChars,
                    mPartialSizes.historyClampedToZero,
                    mPartialSizes.unsafeDisable,
                    mPartialSizes.checkpointChars);
            logContextPlanFail(log, wireIds, ContextBudgetExceededException.Reason.CANNOT_FIT_AFTER_TRIM, mFail, n,
                    defs);
            throw new ContextBudgetExceededException(
                    ContextBudgetExceededException.Reason.CANNOT_FIT_AFTER_TRIM,
                    "LLM_CONTEXT_PLAN: cannot trim outbound messages to fit history budget after drop-only passes");
        }
        return finishPlanned(messages, keep, m0, defs, wireIds, cap, providerRequestCapChars, droppedBatches);
    }

    private static void logContextPlanFail(
            Logger log,
            LlmUsageWireIds wireIds,
            ContextBudgetExceededException.Reason reason,
            Metrics m,
            int messageCount,
            List<ToolDefinition> defs) {
        if (log == null) {
            return;
        }
        String conversationId = AgentToolContext.getConversationId() != null ? AgentToolContext.getConversationId() : "";
        String requestId = LlmUsageTelemetry.effectiveRequestIdForContextPlan(AgentToolContext.getParlerRequestId());
        int toolCount = defs != null ? defs.size() : 0;
        LlmUsageTelemetry.logLlmContextPlanFail(
                log,
                wireIds,
                conversationId,
                requestId,
                reason.name(),
                messageCount,
                toolCount,
                m.stableChars,
                m.toolSchemaChars,
                m.ephemeralChars,
                m.currentUserChars,
                m.activeBatchReserveChars,
                m.transcriptChars,
                m.evidenceRawChars,
                m.evidenceChars,
                m.droppedTranscript,
                m.droppedEvidence,
                m.droppedAssistantBatches,
                m.configuredCapChars,
                m.effectiveRequestCapChars,
                m.historyBudgetChars,
                m.historyClampedToZero,
                m.unsafeDisable,
                m.checkpointChars);
    }

    private static List<ChatMessage> materialize(List<ChatMessage> messages, boolean[] keep) {
        List<ChatMessage> out = new ArrayList<>();
        for (int i = 0; i < keep.length; i++) {
            if (keep[i]) {
                out.add(messages.get(i));
            }
        }
        return out;
    }

    private static boolean historyWithinBudget(List<ChatMessage> subset, List<ToolDefinition> defs,
            LlmUsageWireIds wireIds, int cap, long providerRequestCapChars) {
        Metrics m = Metrics.compute(subset, defs, wireIds, cap, providerRequestCapChars);
        long b = Math.max(0L, m.historyBudgetChars);
        return (long) m.transcriptChars + (long) m.evidenceChars <= b;
    }

    private static boolean[] buildProtectedMask(int n, List<ChatMessage> messages, int[] activeRange, int lastUser,
            int taskIdx, int utcIdx) {
        boolean[] p = new boolean[n];
        if (LeadingSystemRow.isStableFirstSystemRow(messages)) {
            p[0] = true;
        }
        if (lastUser >= 0) {
            p[lastUser] = true;
        }
        if (activeRange != null) {
            for (int i = activeRange[0]; i <= activeRange[1] && i < n; i++) {
                p[i] = true;
            }
        }
        if (taskIdx >= 0 && taskIdx < n) {
            p[taskIdx] = true;
        }
        if (utcIdx >= 0 && utcIdx < n) {
            p[utcIdx] = true;
        }
        // §10.1: protect the newest working checkpoint — the candidate. Older markers are already cleared from the
        // keep mask before budgeting, and protecting them here would state the opposite of that.
        List<Integer> checkpointIdx = injectedCheckpointIndices(messages);
        if (!checkpointIdx.isEmpty()) {
            markProtectedIndex(p, checkpointIdx.get(checkpointIdx.size() - 1), n);
        }
        ParlerEphemeralSystemIndices bundle = AgentToolContext.getParlerEphemeralSystemIndices();
        if (bundle != null && !bundle.equals(ParlerEphemeralSystemIndices.NONE)) {
            markProtectedIndex(p, bundle.catalogIdx(), n);
            markProtectedIndex(p, bundle.slashIdx(), n);
            markProtectedIndex(p, bundle.timeAnchorIdx(), n);
            markProtectedIndex(p, bundle.taxonomyIdx(), n);
            markProtectedIndex(p, bundle.alertIdx(), n);
            markProtectedIndex(p, bundle.hostScopeIdx(), n);
            markProtectedIndex(p, bundle.taskStateIdx(), n);
        }
        return p;
    }

    private static void markProtectedIndex(boolean[] p, int idx, int n) {
        if (idx >= 0 && idx < n) {
            p[idx] = true;
        }
    }

    private static boolean tryDropOldestEvidenceBatch(List<ChatMessage> messages, boolean[] keep, boolean[] protect,
            int[] activeRange) {
        int n = messages.size();
        for (int i = 0; i < n; i++) {
            if (!keep[i] || protect[i]) {
                continue;
            }
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.ASSISTANT || !m.hasToolCalls()) {
                continue;
            }
            if (indexInRange(i, activeRange)) {
                continue;
            }
            int end = i;
            int t = i + 1;
            while (t < n && messages.get(t).getRole() == ChatMessage.Role.TOOL) {
                end = t;
                t++;
            }
            boolean batchOk = true;
            for (int k = i; k <= end; k++) {
                if (protect[k]) {
                    batchOk = false;
                    break;
                }
            }
            if (!batchOk) {
                continue;
            }
            for (int k = i; k <= end; k++) {
                keep[k] = false;
            }
            return true;
        }
        return false;
    }

    /**
     * Oldest-first transcript pair: historic {@code USER} + prose {@code ASSISTANT} that are adjacent in the
     * materialized outbound view. Rows already marked {@code keep=false} (e.g. an evidence batch removed by the
     * evidence-first pass) do not separate the pair; any KEPT row between them (e.g. a non-ephemeral {@code SYSTEM}
     * row, which Parler does not insert there today) still blocks the pair.
     */
    private static boolean tryDropOldestTranscriptPair(List<ChatMessage> messages, boolean[] keep, boolean[] protect,
            int lastUser) {
        int n = messages.size();
        for (int i = 0; i < n; i++) {
            if (!keep[i] || protect[i]) {
                continue;
            }
            if (messages.get(i).getRole() != ChatMessage.Role.USER) {
                continue;
            }
            if (i == lastUser) {
                continue;
            }
            int next = i + 1;
            while (next < n && !keep[next]) {
                next++;
            }
            if (next >= n || protect[next]) {
                continue;
            }
            ChatMessage a = messages.get(next);
            if (a.getRole() != ChatMessage.Role.ASSISTANT || a.hasToolCalls()) {
                continue;
            }
            keep[i] = false;
            keep[next] = false;
            return true;
        }
        return false;
    }

    private static PlannedOutbound finishPlanned(List<ChatMessage> messages, boolean[] keep, Metrics m0,
            List<ToolDefinition> defs, LlmUsageWireIds wireIds, int cap, long providerRequestCapChars,
            int droppedAssistantBatchesKnown) {
        List<ChatMessage> out = materialize(messages, keep);
        Metrics m1 = Metrics.compute(out, defs, wireIds, cap, providerRequestCapChars);
        int lastUserFull = findLastUserIndex(messages);
        int[] activeFull = findActiveAssistantToolBatchRange(messages);
        int droppedTranscriptRows = countDroppedTranscriptRows(messages, keep, lastUserFull);
        int droppedEvidenceRows = countDroppedToolEvidenceRows(messages, keep, activeFull);
        Metrics logged = new Metrics(
                m1.stableChars,
                m1.toolSchemaChars,
                m1.ephemeralChars,
                m1.currentUserChars,
                m1.activeBatchReserveChars,
                m1.transcriptChars,
                m0.evidenceRawChars,
                m1.evidenceChars,
                droppedTranscriptRows,
                droppedEvidenceRows,
                droppedAssistantBatchesKnown,
                m1.configuredCapChars,
                m1.effectiveRequestCapChars,
                m1.historyBudgetChars,
                m1.historyClampedToZero,
                m1.unsafeDisable,
                m1.checkpointChars);
        return new PlannedOutbound(out, logged);
    }

    /** Per {@code docs/agent/context-compaction.md} §13: dropped historic {@code USER} rows and prose {@code ASSISTANT} rows. */
    private static int countDroppedTranscriptRows(List<ChatMessage> messages, boolean[] keep, int lastUser) {
        int c = 0;
        for (int i = 0; i < keep.length; i++) {
            if (keep[i]) {
                continue;
            }
            ChatMessage m = messages.get(i);
            if (m.getRole() == ChatMessage.Role.USER && i != lastUser) {
                c++;
            } else if (m.getRole() == ChatMessage.Role.ASSISTANT && !m.hasToolCalls()
                    && !ConversationCheckpointCodec.isInjectedCheckpoint(m)) {
                // §10.1: the checkpoint is not a transcript pair, so neither omitting the candidate nor clearing a
                // stale marker may be reported as dropped transcript. computeTranscriptChars already excludes it;
                // this counter must agree or LLM_CONTEXT_PLAN contradicts its own char accounting.
                c++;
            }
        }
        return c;
    }

    /**
     * Per {@code docs/agent/context-compaction.md} §13: dropped paired tool-result rows (not the originating assistant
     * tool-call row; that batch is counted in {@code droppedAssistantBatches}).
     */
    private static int countDroppedToolEvidenceRows(List<ChatMessage> messages, boolean[] keep, int[] activeRange) {
        int c = 0;
        for (int i = 0; i < keep.length; i++) {
            if (keep[i]) {
                continue;
            }
            ChatMessage m = messages.get(i);
            if (m.getRole() == ChatMessage.Role.TOOL && !indexInRange(i, activeRange)) {
                c++;
            }
        }
        return c;
    }

    private static int computeStableChars(List<ChatMessage> messages) {
        if (!LeadingSystemRow.isStableFirstSystemRow(messages)) {
            return 0;
        }
        return len(messages.get(0).getContent());
    }

    private static boolean isFramedEphemeralSystem(ChatMessage m) {
        if (m == null || m.getRole() != ChatMessage.Role.SYSTEM) {
            return false;
        }
        return ParlerSuffixFraming.isClassified(m.getContent());
    }

    /**
     * {@code ephemeralChars} per {@code docs/agent/context-compaction.md} §13: per-turn {@code SYSTEM} rows stripped
     * after the turn (slash and host meta via {@link ParlerEphemeralSystemIndices} in Slice A), plus API-round rows
     * (framed time context, task state, saturation coverage guidance, empty-final recovery) inserted by
     * {@link com.thingworx.things.agent.AgentLoop}. When
     * {@link AgentToolContext#getParlerEphemeralSystemIndices()} is {@code null} (e.g. some continuation paths), fall
     * back to summing every subsequent {@code SYSTEM} row (index {@code >= 1}), matching the strip-visible surface.
     */
    private static int computeEphemeralChars(List<ChatMessage> messages) {
        int n = messages.size();
        boolean[] counted = new boolean[n];
        int sum = 0;
        ParlerEphemeralSystemIndices bundle = AgentToolContext.getParlerEphemeralSystemIndices();
        boolean useIndexBundle = bundle != null && !bundle.equals(ParlerEphemeralSystemIndices.NONE);
        boolean useFullSystemFallback = bundle == null;
        if (useIndexBundle) {
            sum += ephemeralLenAtIndex(messages, counted, bundle.catalogIdx());
            sum += ephemeralLenAtIndex(messages, counted, bundle.slashIdx());
            sum += ephemeralLenAtIndex(messages, counted, bundle.timeAnchorIdx());
            sum += ephemeralLenAtIndex(messages, counted, bundle.taxonomyIdx());
            sum += ephemeralLenAtIndex(messages, counted, bundle.alertIdx());
            sum += ephemeralLenAtIndex(messages, counted, bundle.hostScopeIdx());
            sum += ephemeralLenAtIndex(messages, counted, bundle.taskStateIdx());
        }
        for (int i = 1; i < n; i++) {
            if (counted[i]) {
                continue;
            }
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.SYSTEM) {
                continue;
            }
            if (isFramedEphemeralSystem(m)) {
                sum += len(m.getContent());
                continue;
            }
            if (useFullSystemFallback) {
                sum += len(m.getContent());
            }
        }
        return sum;
    }

    private static int ephemeralLenAtIndex(List<ChatMessage> messages, boolean[] counted, int idx) {
        if (idx < 0 || idx >= messages.size() || counted[idx]) {
            return 0;
        }
        ChatMessage m = messages.get(idx);
        if (m == null || m.getRole() != ChatMessage.Role.SYSTEM) {
            return 0;
        }
        counted[idx] = true;
        return len(m.getContent());
    }

    private static int estimateToolSchemaChars(String apiShapeId, List<ToolDefinition> tools) {
        // Delegate to the shared sizer so the per-round budget total and the per-tool LLM_TOOL_SCHEMA_USAGE
        // breakdown are measured by one code path (M1, docs/operations/tool-schema-admission-control.md).
        return ToolSchemaSizer.totalSchemaChars(apiShapeId, tools);
    }

    private static int findLastUserIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == ChatMessage.Role.USER) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Inclusive range {@code [start, end]} of the in-flight assistant tool-call batch (assistant row + trailing tool
     * results), or {@code null} when none. Trailing per-round ephemeral {@code SYSTEM} rows are ignored.
     */
    private static int[] findActiveAssistantToolBatchRange(List<ChatMessage> messages) {
        int r = lastSubstantiveIndex(messages);
        if (r < 0) {
            return null;
        }
        ChatMessage last = messages.get(r);
        if (last.getRole() == ChatMessage.Role.USER) {
            return null;
        }
        if (last.getRole() == ChatMessage.Role.ASSISTANT && last.hasToolCalls()) {
            return new int[] { r, r };
        }
        if (last.getRole() == ChatMessage.Role.TOOL) {
            int end = r;
            int t = r;
            while (t >= 0 && messages.get(t).getRole() == ChatMessage.Role.TOOL) {
                t--;
            }
            if (t >= 0) {
                ChatMessage asst = messages.get(t);
                if (asst.getRole() == ChatMessage.Role.ASSISTANT && asst.hasToolCalls()) {
                    return new int[] { t, end };
                }
            }
        }
        return null;
    }

    private static int lastSubstantiveIndex(List<ChatMessage> messages) {
        int r = messages.size() - 1;
        while (r >= 0) {
            ChatMessage m = messages.get(r);
            if (m.getRole() == ChatMessage.Role.SYSTEM && isTrailingEphemeralSystemForBatchScan(messages, r)) {
                r--;
                continue;
            }
            break;
        }
        return r;
    }

    /**
     * True for per-round {@code SYSTEM} rows that sit after substantive history and should be ignored when locating the
     * active assistant/tool batch (UTC clock, task state, and indexed turn injections when available).
     */
    private static boolean isTrailingEphemeralSystemForBatchScan(List<ChatMessage> messages, int r) {
        if (isFramedEphemeralSystem(messages.get(r))) {
            return true;
        }
        ParlerEphemeralSystemIndices bundle = AgentToolContext.getParlerEphemeralSystemIndices();
        if (bundle == null || bundle.equals(ParlerEphemeralSystemIndices.NONE)) {
            return false;
        }
        return r == bundle.catalogIdx()
                || r == bundle.slashIdx()
                || r == bundle.timeAnchorIdx()
                || r == bundle.taxonomyIdx()
                || r == bundle.alertIdx()
                || r == bundle.hostScopeIdx()
                || r == bundle.taskStateIdx();
    }

    private static int sumRowCharsInRange(List<ChatMessage> messages, int[] range) {
        if (range == null) {
            return 0;
        }
        int sum = 0;
        for (int i = range[0]; i <= range[1]; i++) {
            sum += MessageCharEstimator.rowChars(messages.get(i));
        }
        return sum;
    }

    private static boolean indexInRange(int idx, int[] range) {
        return range != null && idx >= range[0] && idx <= range[1];
    }

    private static int computeTranscriptChars(List<ChatMessage> messages, int lastUserIdx) {
        int sum = 0;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() == ChatMessage.Role.USER) {
                if (i != lastUserIdx) {
                    sum += len(m.getContent());
                }
                continue;
            }
            if (m.getRole() == ChatMessage.Role.ASSISTANT && !m.hasToolCalls()) {
                // §10.1: the injected checkpoint is a prose assistant row but is not transcript. Counting it here
                // as well as in checkpointChars would make historyWithinBudget unsatisfiable for a protected row.
                if (ConversationCheckpointCodec.isInjectedCheckpoint(m)) {
                    continue;
                }
                sum += len(m.getContent());
            }
        }
        return sum;
    }

    /** §10.1: chars of every injected working checkpoint in {@code messages}, as fixed non-history overhead. */
    private static int computeCheckpointChars(List<ChatMessage> messages) {
        int sum = 0;
        for (ChatMessage m : messages) {
            if (ConversationCheckpointCodec.isInjectedCheckpoint(m)) {
                sum += len(m.getContent());
            }
        }
        return sum;
    }

    /** Positions of every injected working checkpoint, in the coordinates of the original list. */
    private static List<Integer> injectedCheckpointIndices(List<ChatMessage> messages) {
        List<Integer> out = new ArrayList<>(1);
        for (int i = 0; i < messages.size(); i++) {
            if (ConversationCheckpointCodec.isInjectedCheckpoint(messages.get(i))) {
                out.add(i);
            }
        }
        return out;
    }

    private static int computeEvidenceChars(List<ChatMessage> messages, int[] activeRange) {
        int sum = 0;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() == ChatMessage.Role.TOOL) {
                if (!indexInRange(i, activeRange)) {
                    sum += MessageCharEstimator.rowChars(m);
                }
                continue;
            }
            if (m.getRole() == ChatMessage.Role.ASSISTANT && m.hasToolCalls()) {
                if (!indexInRange(i, activeRange)) {
                    sum += MessageCharEstimator.rowChars(m);
                }
            }
        }
        return sum;
    }

    private static int len(String s) {
        return s != null ? s.length() : 0;
    }
}
