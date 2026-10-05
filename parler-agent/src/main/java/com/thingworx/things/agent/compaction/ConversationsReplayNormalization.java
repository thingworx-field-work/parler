package com.thingworx.things.agent.compaction;

import java.util.List;

import org.slf4j.Logger;

import com.thingworx.things.agent.AgentLoop;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmUsageTelemetry;
import com.thingworx.things.agent.tools.PendingApprovalStore;

/**
 * Slice D: single persist-boundary helper for replay shaping immediately before
 * {@code AgentThing} writes the mutable in-memory transcript to {@code _conversations}.
 *
 * <p>Ordering matches {@code docs/agent/context-compaction.md} §8: per-turn ephemeral system rows are removed
 * earlier by {@code AgentThing#applySkillTurnMutationFinish} / {@code PlaybookSlashTurnFinalize} on paths that use
 * {@link com.thingworx.things.agent.LlmTurnContext}; HITL continuations omit ephemeral index bundles and
 * rely on stripped pending snapshots plus {@code AgentLoop}'s per-round UTC/task-state cleanup. Tier B promotion
 * runs only after a terminal {@link AgentLoop.AgentResult.Status#SUCCESS}, when replay compaction is effective, and
 * when the pre-normalization replay exceeds the same storage cap enforced by the deterministic trimmer. Keeping
 * within-cap replay byte-stable avoids invalidating an already-written provider prompt-cache prefix on every turn.
 *
 * <p><b>Local list vs map publish:</b> helpers here mutate the caller's in-memory {@code messages} list (the same
 * reference that will be passed to {@code _conversations.put}). They MUST NOT replace entries inside the live
 * {@code ConcurrentMap} value for a conversation id without holding {@code AgentThing}'s per-conversation lock used
 * at publish time; Phase 0/1 publish remains “mutate local list, then put under lock”.
 *
 * <p><b>Active HITL pending (§8):</b> post-turn normalization that <em>drops</em> rows or otherwise reshapes replay in
 * ways that can desynchronize {@link com.thingworx.things.agent.tools.PendingApprovalRecord#getMessagesCopy} from the
 * list {@code applyParlerApprovalExpiredToConversation} compares via {@code conversationMatchesSnapshot} MUST either
 * (a) re-snapshot active pendings under {@code AgentThing#parlerConversationLock(conversationId)}, or (b) skip that
 * normalization until no non-expired pending exists for the conversation. Silent loss of expired-pending synthetic
 * tool delivery is not acceptable. Slice D Phase 1 applies option (b) conservatively to <em>Tier B</em> as well
 * (defer promotion when {@link PendingApprovalStore#hasPendingForConversationId} is true) so in-memory replay bodies
 * stay aligned with pending snapshots until the gate clears.
 *
 * <p><b>Storage budget (phase 2):</b> when the replay exceeds its cap, Tier B runs first and
 * {@link ConversationsStorageBudgetTrimmer#maybeTrimForStorageBudget} may then drop oldest historic rows in place
 * before {@code put} (see §8 step 4); same pending skip and compaction gate.
 *
 * <p>Any future fail-closed normalization that throws here must follow §13 cumulative fail-telemetry (same field tail
 * as {@code LLM_CONTEXT_PLAN_FAIL}), mirroring {@link ContextBudgetPlanner} {@code CANNOT_FIT_AFTER_TRIM}: synthetic
 * {@code Metrics} from {@code Metrics.compute} on partial outbound materialization, pre-trim raw evidence from the
 * original list, and cumulative drop counters from trim helpers — not all-zero defaults alone.
 */
public final class ConversationsReplayNormalization {

    private ConversationsReplayNormalization() {}

    /**
     * §8.1.1 single post-turn entry: storage-pressure-gated Tier B promotion, then the optional checkpoint, then the
     * storage trim — in the §10.2 order, for every success path.
     *
     * <p><b>Why one entry.</b> Every {@code AgentThing} site that promotes Tier B immediately trims for the storage
     * budget. Routing all of them through one method makes "no path was missed" a property of the code rather than
     * of a reviewer's diligence: a new success path that trims must call this, and
     * {@code ConversationCheckpointIntegrationTest} asserts no direct trim call sites remain.
     *
     * <p><b>The checkpoint never costs the answer (§8.4).</b> Generation is attempted only when the boundary dry run
     * says a transcript row is about to be deleted after any pressure-triggered Tier B shrink. Any skip reason, any
     * provider failure, and any validation refusal leaves {@code messages} exactly as Tier B left it, and the
     * deterministic trim then runs unchanged. The whole attempt is wrapped so an unexpected exception cannot escape
     * into the post-turn path.
     *
     * <p><b>The request identity is a parameter, not turn context.</b> Every path clears
     * {@link com.thingworx.things.agent.tools.AgentToolContext} before reaching here, so reading the thread-local
     * would stamp an empty {@code requestId} on every event and break correlation with the same request's
     * {@code LLM_CONTEXT_PLAN} and {@code LLM_USAGE} lines — the very thing §12's field exists for. Paths without a
     * request identity ({@code Chat}, {@code ChatAsync}) pass {@code null}, which is the intentionally empty value.
     *
     * @param requestIdOrNull      the turn's bounded request identity, or {@code null} where the path has none
     * @param conversationIdOrNull when non-blank and {@link PendingApprovalStore#hasPendingForConversationId} is
     *                             true, Tier B/checkpoint/trim are skipped (Slice D phase 1 deferral)
     * @param turnLlmOrNull        the client this turn already resolved; {@code null} disables checkpointing
     * @param assistantMessageId   the id just minted for this turn's final assistant Stream row (§6.1.1 watermark)
     * @param agentThingName       owning AgentThing, written into checkpoint identity
     * @param generatedAt          ISO timestamp for {@code generated}
     * @return the outcome, for telemetry; never {@code null}
     */
    public static ConversationCheckpointGenerator.Outcome applyPostTurnNormalizationBeforeStore(
            List<ChatMessage> messages,
            AgentLoop.AgentResult result,
            boolean compactionEffective,
            Logger log,
            String conversationIdOrNull,
            String requestIdOrNull,
            int llmContextMaxChars,
            LlmClient turnLlmOrNull,
            String assistantMessageId,
            String agentThingName,
            String generatedAt) {

        String requestId = LlmUsageTelemetry.effectiveRequestIdForContextPlan(requestIdOrNull);
        if (exceedsStorageBudget(messages, llmContextMaxChars)) {
            applyTierBReplayPromotionBeforeStore(messages, result, compactionEffective, log, conversationIdOrNull);
        }

        ConversationCheckpointGenerator.Outcome outcome =
                ConversationCheckpointGenerator.Outcome.NO_SAFE_BOUNDARY;
        try {
            outcome = maybeInstallCheckpoint(messages, result, compactionEffective, conversationIdOrNull,
                    requestId, llmContextMaxChars, turnLlmOrNull, assistantMessageId, agentThingName, generatedAt,
                    log);
        } catch (RuntimeException e) {
            // §8.4: a checkpoint is a continuity enhancement, never a chat availability gate.
            ConversationCheckpointEvents.skip(log, conversationIdOrNull, requestId, "MODEL_ERROR", 0L);
            outcome = ConversationCheckpointGenerator.Outcome.MODEL_ERROR;
        }

        ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget(messages, llmContextMaxChars,
                compactionEffective, log, conversationIdOrNull);
        return outcome;
    }

    /**
     * Uses the exact character measure and inclusive cap semantics owned by the storage trimmer. This gate is checked
     * before Tier B mutates any historic tool-result body, so ordinary within-cap turns remain append-only for prompt
     * caching. The trimmer still owns all removal behavior and the post-Tier-B over-cap warning.
     */
    private static boolean exceedsStorageBudget(List<ChatMessage> messages, int llmContextMaxChars) {
        return messages != null && !messages.isEmpty()
                && ConversationsStorageBudgetTrimmer.sumMessageChars(messages) > Math.max(1, llmContextMaxChars);
    }

    private static ConversationCheckpointGenerator.Outcome maybeInstallCheckpoint(
            List<ChatMessage> messages,
            AgentLoop.AgentResult result,
            boolean compactionEffective,
            String conversationIdOrNull,
            String requestId,
            int llmContextMaxChars,
            LlmClient turnLlmOrNull,
            String assistantMessageId,
            String agentThingName,
            String generatedAt,
            Logger log) {

        // Success-only, matching the existing Tier B gate: the four isArtifactCacheTerminal sites carry ERROR and
        // stay no-ops without a special case. Neither this nor a missing client/watermark is a §12 reason — §12's
        // reason set describes turns where a checkpoint was attemptable — so no skip event is emitted here.
        if (result == null || result.getStatus() != AgentLoop.AgentResult.Status.SUCCESS) {
            return ConversationCheckpointGenerator.Outcome.NO_SAFE_BOUNDARY;
        }
        if (turnLlmOrNull == null || assistantMessageId == null || assistantMessageId.isBlank()) {
            return ConversationCheckpointGenerator.Outcome.NO_SAFE_BOUNDARY;
        }
        ConversationCompactionBoundarySelector.BoundaryPlan plan = ConversationCompactionBoundarySelector.plan(
                messages, llmContextMaxChars, compactionEffective, conversationIdOrNull);
        if (!plan.isCheckpointEligible()) {
            // §12 keeps these distinct: an operator reading the log must be able to tell a missing identity from a
            // deferred approval from an unverifiable message shape. Collapsing them into one reason destroys
            // exactly the distinction at the moment it matters.
            String reason = skipReasonFor(plan.outcome());
            if (reason != null) {
                ConversationCheckpointEvents.skip(log, conversationIdOrNull, requestId, reason, 0L);
            }
            return ConversationCheckpointGenerator.Outcome.NO_SAFE_BOUNDARY;
        }

        // §6.3 repeated compaction: the previous validated semantic and its server-authored refs, as objects. The
        // injected row is prose for the model and carries no refs, so reading it back would lose them silently.
        ConversationCheckpoint prior =
                ConversationCheckpointWorkingSet.current(agentThingName, conversationIdOrNull);
        com.thingworx.things.agent.llm.usage.LlmCallContext parentCallContext =
                com.thingworx.things.agent.llm.usage.LlmCallContext.builder(
                        com.thingworx.things.agent.llm.usage.LlmCallKind.AGENT_ROUND,
                        com.thingworx.things.agent.llm.usage.LlmCallEvent.newId())
                        .turnRequestId(requestId)
                        .conversationId(conversationIdOrNull)
                        .agentThing(agentThingName)
                        .build();
        ConversationCheckpointGenerator.Result generated = ConversationCheckpointGenerator.generate(
                plan, turnLlmOrNull, llmContextMaxChars,
                prior != null ? prior.semantic() : null,
                prior != null ? prior.evidenceRefs() : null,
                new ConversationCheckpoint.Source(conversationIdOrNull, agentThingName, assistantMessageId),
                turnLlmOrNull.usageWireIds() != null ? turnLlmOrNull.usageWireIds().getProviderThingName() : "",
                turnLlmOrNull.usageWireIds() != null ? turnLlmOrNull.usageWireIds().getModel() : "",
                generatedAt,
                null,
                parentCallContext);
        if (!generated.isCreated()) {
            ConversationCheckpointEvents.skip(log, conversationIdOrNull, requestId, generated.outcome().name(),
                    generated.summaryDurationMs());
            return generated.outcome();
        }

        int beforeChars = ConversationsStorageBudgetTrimmer.sumMessageChars(messages);
        if (!ConversationCheckpointInstaller.install(messages, plan, generated.checkpoint())) {
            ConversationCheckpointEvents.skip(log, conversationIdOrNull, requestId,
                    ConversationCheckpointGenerator.Outcome.NO_SHRINK.name(), generated.summaryDurationMs());
            return ConversationCheckpointGenerator.Outcome.NO_SHRINK;
        }
        ConversationCheckpointWorkingSet.record(agentThingName, conversationIdOrNull, generated.checkpoint());
        ConversationCheckpointEvents.created(log, conversationIdOrNull, requestId,
                plan.coveredPrefix().size(), generated.checkpoint().retainedTail().size(),
                beforeChars, ConversationsStorageBudgetTrimmer.sumMessageChars(messages),
                MessageCharEstimator.rowChars(
                        ConversationCheckpointCodec.toInjectedAssistant(generated.checkpoint().semantic())),
                generated.checkpoint().evidenceRefs().size(), generated.summaryCalls(),
                generated.promptTokens(), generated.completionTokens(), generated.summaryDurationMs());

        // §10.2 step 6: the checkpoint Stream row, after the final assistant row and before the storage trim.
        // §8.4: a persistence failure keeps the JVM working checkpoint — this turn and every later turn in this JVM
        // still get continuity — and costs only restart recovery, which falls back to the bounded transcript. Rolling
        // the installation back would trade a recoverable loss for an immediate one.
        String envelope = ConversationCheckpointCodec.serialize(generated.checkpoint());
        if (envelope == null
                || !ConversationCheckpointPersistence.append(conversationIdOrNull, agentThingName, envelope)) {
            ConversationCheckpointEvents.skip(log, conversationIdOrNull, requestId,
                    ConversationCheckpointEvents.REASON_STREAM_APPEND_FAILED, generated.summaryDurationMs());
        }
        return ConversationCheckpointGenerator.Outcome.CREATED;
    }

    /**
     * The §12 {@code CONVERSATION_CHECKPOINT_SKIP} reason for an ineligible boundary, or {@code null} when nothing
     * was going to happen at all.
     *
     * <p>{@code COMPACTION_DISABLED} and {@code WITHIN_CAP} are not §12 reasons: in both the deterministic trimmer
     * is itself a no-op, so there is no skipped checkpoint to report and an event on every ordinary turn would bury
     * the reasons that matter.
     */
    private static String skipReasonFor(ConversationCompactionBoundarySelector.Outcome outcome) {
        switch (outcome) {
            case PENDING_HITL:
            case NO_CONVERSATION_ID:
            case NO_TRANSCRIPT_DROP:
            case NO_SAFE_BOUNDARY:
                return outcome.name();
            default:
                return null;
        }
    }


    public static void applyTierBReplayPromotionBeforeStore(
            List<ChatMessage> messages,
            AgentLoop.AgentResult result,
            boolean compactionEffective,
            Logger log,
            String conversationIdOrNull) {
        if (!compactionEffective || result == null
                || result.getStatus() != AgentLoop.AgentResult.Status.SUCCESS) {
            return;
        }
        if (conversationIdOrNull != null && !conversationIdOrNull.isBlank()
                && PendingApprovalStore.hasPendingForConversationId(conversationIdOrNull)) {
            if (log != null && log.isDebugEnabled()) {
                log.debug("Slice D: skip Tier B before _conversations.put (active pending for conversationId={})",
                        conversationIdOrNull.trim());
            }
            return;
        }
        LlmToolResultTierBPromoter.apply(messages, log);
    }
}
