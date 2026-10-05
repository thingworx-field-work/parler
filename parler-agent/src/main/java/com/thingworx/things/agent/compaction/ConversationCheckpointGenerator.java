package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.usage.LlmCallContext;
import com.thingworx.things.agent.llm.usage.LlmCallKind;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;

/**
 * Slice B unit 3 of {@code docs/core/advanced-compact.md}: turns an eligible
 * {@link ConversationCompactionBoundarySelector.BoundaryPlan} into a validated
 * {@link ConversationCheckpoint}, using exactly one direct call on the turn's own {@link LlmClient}.
 *
 * <p><b>One call, no machinery (§8.2, §8.4).</b> No {@code AgentLoop}, no business tools, no retry, no fallback
 * provider, no second summary call, no executor and no cancellation lifecycle. Any exception — including the
 * Provider's configured HTTP timeout — becomes {@code MODEL_ERROR} and the completed answer is preserved. A
 * checkpoint is a continuity enhancement, never a chat availability gate.
 *
 * <p><b>The client is the turn's, not a fresh one.</b> Callers pass the same {@code LlmClient} the turn already
 * resolved, so provider Thing, effective model, rate-control, and HTTP timeout snapshot stay identical within one
 * turn. {@code modelOverride} is null precisely so the Provider bridge keeps owning model and max-output
 * resolution.
 *
 * <p><b>Input is bounded before the call, never truncated during it (§8.2).</b> The class selects the oldest
 * complete-turn prefix of the covered span that fits the effective cap. Nothing is cut mid-row, no tool batch is
 * split, and if not even one complete turn fits, the reason is {@code SUMMARY_INPUT_TOO_LARGE} and no call is made.
 *
 * <p><b>The summarized span and the replaced span are the same span.</b> Whatever the cap excludes is preserved as
 * exact rows ahead of the plan's retained tail, so every covered row ends up represented either by the new semantic
 * state or by a retained row — never by neither.
 *
 * <p><b>Success means the working set actually shrinks.</b> A serializable envelope is necessary but not
 * sufficient: before reporting {@code CREATED} this class materializes the rows the integration would publish and
 * requires both a real reduction and compliance with the same storage cap the trimmer uses (§7.2, §8.3 step 8).
 */
public final class ConversationCheckpointGenerator {

    /** §8.2: code-owned hard maximum on estimated summary-input chars. Not a setting, not a trigger. */
    public static final int MAX_SUMMARY_INPUT_CHARS = 100_000;

    /** §8.2 fixed request parameters. */
    public static final long REQUESTED_MAX_OUTPUT_TOKENS = 2048L;
    public static final double TEMPERATURE = 0.0d;

    /**
     * The checkpoint-only system instruction. Fixed text, no business tools, no stable system prompt: this request
     * exists to restate task navigation, not to answer anything.
     */
    static final String SYSTEM_INSTRUCTION = String.join("\n",
            "You maintain a conversation checkpoint for an industrial assistant.",
            "Read the conversation excerpt and return ONLY a JSON object with this shape:",
            "{\"goal\":\"...\",\"constraints\":[\"...\"],"
                    + "\"progress\":{\"done\":[\"...\"],\"inProgress\":[\"...\"],\"blocked\":[\"...\"]},"
                    + "\"decisions\":[{\"decision\":\"...\",\"rationale\":\"...\","
                    + "\"rejectedAlternatives\":[\"...\"]}],"
                    + "\"nextSteps\":[\"...\"],\"criticalContext\":[\"...\"]}",
            "Rules:",
            "- Update the prior state rather than appending to it: move finished work into done, drop blockers that",
            "  no longer apply, keep constraints and decisions that still hold, and add what is new.",
            "- State only what the excerpt shows. Never infer a value, a device state, a count, a completeness",
            "  class, or an authorization that is not written there.",
            "- goal is required and must say what the user is trying to accomplish.",
            "- rationale is a short business reason already stated in the conversation. Do not write private",
            "  reasoning or chain-of-thought.",
            "- Never include passwords, tokens, keys, credentials, file paths, or raw tool output.",
            "- Output the JSON object and nothing else.");

    /** Why generation did not produce a checkpoint; maps onto the §12 skip reasons. */
    public enum Outcome {
        CREATED,
        NO_SAFE_BOUNDARY,
        SUMMARY_INPUT_TOO_LARGE,
        MODEL_ERROR,
        INVALID_JSON,
        PROTECTED_VALUE,
        NO_SHRINK
    }

    /** Result of one generation attempt, carrying the §12 reason and the latency the turn actually paid. */
    public static final class Result {
        private final Outcome outcome;
        private final ConversationCheckpoint checkpoint;
        private final long summaryDurationMs;
        private final int promptTokens;
        private final int completionTokens;
        private final int summaryCalls;

        private Result(Outcome outcome, ConversationCheckpoint checkpoint, long summaryDurationMs,
                int promptTokens, int completionTokens, int summaryCalls) {
            this.outcome = outcome;
            this.checkpoint = checkpoint;
            this.summaryDurationMs = summaryDurationMs;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.summaryCalls = summaryCalls;
        }

        static Result beforeCall(Outcome outcome) {
            return new Result(outcome, null, 0L, 0, 0, 0);
        }

        static Result afterCall(Outcome outcome, ConversationCheckpoint checkpoint, long durationMs,
                int promptTokens, int completionTokens) {
            return new Result(outcome, checkpoint, durationMs, promptTokens, completionTokens, 1);
        }

        public Outcome outcome() {
            return outcome;
        }

        public boolean isCreated() {
            return outcome == Outcome.CREATED && checkpoint != null;
        }

        /** {@code null} unless {@link #isCreated()}. */
        public ConversationCheckpoint checkpoint() {
            return checkpoint;
        }

        /**
         * §12: wall-clock of the single summary call, reported whenever that call <em>began</em>, whatever happened
         * afterwards. Zero only when no call was made — recording zero for a post-call rejection would undercount
         * exactly the latency this field exists to measure.
         */
        public long summaryDurationMs() {
            return summaryDurationMs;
        }

        public int promptTokens() {
            return promptTokens;
        }

        public int completionTokens() {
            return completionTokens;
        }

        /** Always 0 or 1: §8.2 forbids a second call. */
        public int summaryCalls() {
            return summaryCalls;
        }
    }

    private ConversationCheckpointGenerator() {}

    /**
     * Generates a checkpoint for an eligible plan.
     *
     * @param plan                    an eligible {@link ConversationCompactionBoundarySelector.BoundaryPlan}
     * @param turnLlm                 the client this turn already resolved and used
     * @param llmContextMaxChars      the same configured cap the planner and trimmer use
     * @param priorSemanticOrNull     the previous checkpoint's semantic state, for §6.3 repeated compaction
     * @param priorRefsOrNull         the previous checkpoint's refs, revalidated and carried forward
     * @param source                  server-written identity, including the §6.1.1 watermark
     * @param provider                provider id for {@code generated}
     * @param model                   effective model for {@code generated}
     * @param generatedAt             ISO timestamp for {@code generated}; supplied so this class stays deterministic
     * @param liveness                current-JVM cache lookup, or {@code null} for historical-recompute
     */
    public static Result generate(
            ConversationCompactionBoundarySelector.BoundaryPlan plan,
            LlmClient turnLlm,
            int llmContextMaxChars,
            ConversationCheckpointSemantic priorSemanticOrNull,
            List<ConversationCheckpoint.EvidenceRef> priorRefsOrNull,
            ConversationCheckpoint.Source source,
            String provider,
            String model,
            String generatedAt,
            ConversationCheckpointEvidenceManifest.LivenessResolver liveness,
            LlmCallContext parentCallContext) {

        if (plan == null || !plan.isCheckpointEligible() || turnLlm == null || source == null
                || !source.isComplete()) {
            return Result.beforeCall(Outcome.NO_SAFE_BOUNDARY);
        }

        List<ChatMessage> coveredPrefix = plan.coveredPrefix();
        int cap = effectiveSummaryInputCap(turnLlm, llmContextMaxChars);
        int selectedEnd = selectOldestCompleteTurnPrefixEnd(coveredPrefix, priorSemanticOrNull, cap);
        if (selectedEnd <= 0) {
            return Result.beforeCall(Outcome.SUMMARY_INPUT_TOO_LARGE);
        }
        // The span the summary describes and the span the checkpoint replaces must be the same span. Anything the
        // cap excluded stays in the working set ahead of the plan's tail rather than being summarized away.
        List<ChatMessage> selectedSpan = new ArrayList<>(coveredPrefix.subList(0, selectedEnd));
        List<ChatMessage> unselectedSuffix =
                new ArrayList<>(coveredPrefix.subList(selectedEnd, coveredPrefix.size()));

        List<ConversationCheckpoint.EvidenceRef> freshRefs =
                ConversationCheckpointEvidenceManifest.build(selectedSpan, liveness);

        List<ChatMessage> request = List.of(
                ChatMessage.system(SYSTEM_INSTRUCTION),
                ChatMessage.user(renderPayload(priorSemanticOrNull, selectedSpan)));

        long startedAt = System.currentTimeMillis();
        LlmResponse response;
        try {
            response = turnLlm.chat(fixedRequest(request, turnLlm, parentCallContext));
        } catch (Exception e) {
            // Includes the Provider's configured HTTP timeout. No retry, no fallback provider (§8.4).
            return Result.afterCall(Outcome.MODEL_ERROR, null, elapsedSince(startedAt), 0, 0);
        }
        long durationMs = elapsedSince(startedAt);
        if (response == null) {
            return Result.afterCall(Outcome.MODEL_ERROR, null, durationMs, 0, 0);
        }
        int promptTokens = response.getPromptTokens();
        int completionTokens = response.getCompletionTokens();

        ConversationCheckpointCodec.SemanticParseResult parsed =
                ConversationCheckpointCodec.parseModelSemantic(response.getContent());
        if (!parsed.isAccepted()) {
            Outcome outcome = parsed.rejection() == ConversationCheckpointCodec.SemanticRejection.PROTECTED_VALUE
                    ? Outcome.PROTECTED_VALUE
                    : Outcome.INVALID_JSON;
            return Result.afterCall(outcome, null, durationMs, promptTokens, completionTokens);
        }

        // Server assembles everything the model does not own (§6.2). The tail carries the unselected suffix first,
        // so every covered row is represented either by the new semantic state or by an exact retained row.
        List<ConversationCheckpoint.EvidenceRef> refs =
                mergeCarriedForward(freshRefs, priorRefsOrNull, liveness);
        List<ChatMessage> tailSource = new ArrayList<>(unselectedSuffix);
        tailSource.addAll(plan.retainedTail());
        List<ConversationCheckpoint.RetainedRow> uncapped =
                ConversationCheckpointCodec.projectRetainedRows(tailSource);
        List<ConversationCheckpoint.RetainedRow> tail = ConversationCheckpointCodec.applyTailCaps(uncapped);
        if (!ConversationCheckpointCodec.projectRetainedRows(unselectedSuffix).isEmpty()
                && tail.size() != uncapped.size()) {
            // The tail could not carry everything the summary did not cover; failing soft is the only honest
            // outcome, because installing this checkpoint would drop those turns entirely.
            return Result.afterCall(Outcome.NO_SHRINK, null, durationMs, promptTokens, completionTokens);
        }

        ConversationCheckpoint checkpoint = new ConversationCheckpoint(
                source, parsed.semantic(), refs, tail,
                new ConversationCheckpoint.Generated(generatedAt, provider, model));

        // §8.3 step 8: the envelope must serialize, *and* the rows this would publish must actually shrink the
        // working set within the same storage cap. Serializability alone proves neither.
        if (ConversationCheckpointCodec.serialize(checkpoint) == null
                || !materializedWorkingSetShrinks(plan, checkpoint, selectedSpan, unselectedSuffix,
                        llmContextMaxChars)) {
            return Result.afterCall(Outcome.NO_SHRINK, null, durationMs, promptTokens, completionTokens);
        }
        return Result.afterCall(Outcome.CREATED, checkpoint, durationMs, promptTokens, completionTokens);
    }

    // Suffix survival is proven by "no eviction happened at all", not by comparing values afterwards. Retained rows
    // carry no ids, so two identical exchanges are indistinguishable by role/content/provenance: if the caps evict
    // the protected suffix and the following plan tail happens to begin with an equal pair, a value comparison is
    // satisfied while one occurrence is gone from both the summary and history. Because applyTailCaps always evicts
    // from the oldest end, any eviction at all while a non-empty unselected projection leads the candidate
    // necessarily removes part of that projection — so equal sizes is an exact proof and needs no value equality.

    /**
     * §7.2 / §8.3 step 8: materializes the rows unit 3b would publish and requires both a real reduction and
     * compliance with the same storage cap the trimmer uses.
     *
     * <p>Published working set = the surviving stable system rows + the injected checkpoint + the retained tail.
     * The comparison baseline is the same set with the covered prefix still present instead of the checkpoint, so
     * "shrinks" means what it says rather than "serialized successfully".
     */
    static boolean materializedWorkingSetShrinks(
            ConversationCompactionBoundarySelector.BoundaryPlan plan,
            ConversationCheckpoint checkpoint,
            List<ChatMessage> selectedSpan,
            List<ChatMessage> unselectedSuffix,
            int llmContextMaxChars) {
        int systemChars = 0;
        for (ChatMessage m : plan.materializedSurvivors()) {
            if (m != null && m.getRole() == ChatMessage.Role.SYSTEM) {
                systemChars += MessageCharEstimator.rowChars(m);
            }
        }
        ChatMessage injected = ConversationCheckpointCodec.toInjectedAssistant(checkpoint.semantic());
        int checkpointChars = MessageCharEstimator.rowChars(injected);

        int tailChars = 0;
        for (ConversationCheckpoint.RetainedRow r : checkpoint.retainedTail()) {
            tailChars += MessageCharEstimator.rowChars(
                    ConversationCheckpoint.RetainedRow.ROLE_USER.equals(r.role())
                            ? ChatMessage.user(r.content())
                            : ChatMessage.assistant(r.content()));
        }
        int publishedChars = systemChars + checkpointChars + tailChars;

        // Baseline: the same working set with the covered prefix intact and no checkpoint row, summed over the
        // actual rows rather than reconstructed by subtraction — once tail acceptance filters or caps rows, a
        // derived term stops describing the same set and can reject a checkpoint for the wrong reason.
        int baselineChars = systemChars;
        for (ChatMessage m : selectedSpan) {
            baselineChars += MessageCharEstimator.rowChars(m);
        }
        for (ChatMessage m : unselectedSuffix) {
            baselineChars += MessageCharEstimator.rowChars(m);
        }
        for (ChatMessage m : plan.retainedTail()) {
            baselineChars += MessageCharEstimator.rowChars(m);
        }

        return publishedChars < baselineChars && publishedChars <= Math.max(1, llmContextMaxChars);
    }

    /** §8.2's fixed request, field for field. */
    static LlmChatRequest fixedRequest(List<ChatMessage> messages, LlmClient turnLlm, LlmCallContext parentCallContext) {
        LlmUsageWireIds wireIds = turnLlm.usageWireIdsForEffectiveModel(null);
        LlmChatRequest base = new LlmChatRequest(
                messages,
                Collections.emptyList(),
                TEMPERATURE,
                REQUESTED_MAX_OUTPUT_TOKENS,
                false,
                null,
                null,
                wireIds,
                false,
                null,
                false);
        LlmChatRequest withPolicy = LlmChatRequest.copyWithToolPolicy(base, true);
        return LlmChatRequest.copyWithCallContext(withPolicy,
                LlmCallRecorder.subCallContext(LlmCallKind.CHECKPOINT, parentCallContext, wireIds));
    }

    /**
     * §8.2's effective cap: the code-owned maximum, further limited by whatever budget the provider and
     * rate-control already impose for a request of this output size.
     */
    static int effectiveSummaryInputCap(LlmClient turnLlm, int llmContextMaxChars) {
        long providerCap = turnLlm.contextPlanningInputCapChars(REQUESTED_MAX_OUTPUT_TOKENS, null).orElse(0L);
        ContextBudgetPlanner.Metrics metrics = ContextBudgetPlanner.Metrics.compute(
                Collections.emptyList(),
                Collections.emptyList(),
                turnLlm.usageWireIdsForEffectiveModel(null),
                llmContextMaxChars,
                providerCap);
        long effective = Math.min(MAX_SUMMARY_INPUT_CHARS, metrics.effectiveRequestCapChars);
        return (int) Math.max(0L, effective);
    }

    /**
     * §8.2: the <b>oldest</b> complete-turn prefix that fits the cap, measured over the whole request.
     *
     * <p>A prefix, not a suffix, and the distinction is a continuity property rather than a preference. The span
     * the summary describes becomes the span the checkpoint replaces, so anything outside it must survive as exact
     * rows. Choosing a newest-first suffix would leave the older turns described by neither the new semantic state
     * nor the retained tail — on a first checkpoint there is no prior state that could cover them.
     *
     * <p>Ends fall on turn boundaries (user rows), so no assistant/tool batch is ever split. Sizing counts every
     * row; the rendered request can only be smaller once unadmitted tool bodies are gated out.
     *
     * @return exclusive end index into {@code coveredPrefix}, or {@code -1} when no complete turn fits
     */
    static int selectOldestCompleteTurnPrefixEnd(List<ChatMessage> coveredPrefix,
            ConversationCheckpointSemantic priorSemanticOrNull, int cap) {
        if (coveredPrefix == null || coveredPrefix.isEmpty()) {
            return -1;
        }
        int fixedOverhead = MessageCharEstimator.rowChars(ChatMessage.system(SYSTEM_INSTRUCTION))
                + priorSemanticChars(priorSemanticOrNull);
        if (fixedOverhead >= cap) {
            return -1;
        }
        List<Integer> candidateEnds = new ArrayList<>();
        for (int i = 1; i < coveredPrefix.size(); i++) {
            ChatMessage m = coveredPrefix.get(i);
            if (m != null && m.getRole() == ChatMessage.Role.USER) {
                candidateEnds.add(i);
            }
        }
        candidateEnds.add(coveredPrefix.size());
        for (int i = candidateEnds.size() - 1; i >= 0; i--) {
            int end = candidateEnds.get(i);
            if (fixedOverhead + renderedChars(coveredPrefix.subList(0, end)) <= cap) {
                return end;
            }
        }
        return -1;
    }

    private static int priorSemanticChars(ConversationCheckpointSemantic prior) {
        return prior == null ? 0 : prior.modelFacingChars() + PRIOR_STATE_HEADING.length();
    }

    private static int renderedChars(List<ChatMessage> rows) {
        int chars = EXCERPT_HEADING.length();
        for (ChatMessage m : rows) {
            chars += ROW_PREFIX_CHARS + MessageCharEstimator.rowChars(m);
        }
        return chars;
    }

    private static final String PRIOR_STATE_HEADING = "Prior checkpoint state:\n";
    private static final String EXCERPT_HEADING = "Conversation excerpt (oldest first):\n";
    /** {@code "\n<role>: "} — the per-row framing {@link #renderPayload} adds. */
    private static final int ROW_PREFIX_CHARS = 13;

    /**
     * Renders the server-authored user payload. Only roles the summary is allowed to see appear: user prose, final
     * assistant prose, and tool bodies that passed §6.2 admission (§8.2, §8.3).
     *
     * <p><b>Being post-egress is not admission.</b> Tier B promotes only specific eligible shapes and deliberately
     * leaves unknown formats, unmarked generic JSON, error shells, and non-promotable bodies unchanged, so a
     * {@code Role.TOOL} row reaching this point may still be raw. A tool body is included only when it also produced
     * an evidence ref, which means it passed the closed family, success-shape, PASSWORD, row-bound, and pairing
     * policy. Framing an unchecked body as {@code evidence:} would not make it compact evidence.
     */
    static String renderPayload(ConversationCheckpointSemantic priorOrNull, List<ChatMessage> rows) {
        StringBuilder sb = new StringBuilder();
        if (priorOrNull != null) {
            sb.append(PRIOR_STATE_HEADING).append(priorOrNull.renderForModel()).append("\n\n");
        }
        sb.append(EXCERPT_HEADING);
        for (int i = 0; i < rows.size(); i++) {
            ChatMessage m = rows.get(i);
            if (m == null || m.getContent() == null || m.getContent().isEmpty()) {
                continue;
            }
            switch (m.getRole()) {
                case USER:
                    sb.append("\nuser: ").append(m.getContent());
                    break;
                case ASSISTANT:
                    if (!m.hasToolCalls()) {
                        sb.append("\nassistant: ").append(m.getContent());
                    }
                    break;
                case TOOL:
                    // Asked per concrete row: a tool-call id is unique only inside one batch, so an id set built
                    // from accepted rows can be satisfied by a different, unaccepted row reusing that id later.
                    if (ConversationCheckpointEvidenceManifest.isAdmittedEvidenceRow(rows, i)) {
                        sb.append("\nevidence: ").append(m.getContent());
                    }
                    break;
                default:
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * Fresh refs win over carried ones for the same tool call: the fresh build saw the body, while a carried ref is
     * only a prior admission decision. Carried refs are revalidated before merging (§6.2).
     */
    private static List<ConversationCheckpoint.EvidenceRef> mergeCarriedForward(
            List<ConversationCheckpoint.EvidenceRef> fresh,
            List<ConversationCheckpoint.EvidenceRef> priorOrNull,
            ConversationCheckpointEvidenceManifest.LivenessResolver liveness) {
        if (priorOrNull == null || priorOrNull.isEmpty()) {
            return fresh;
        }
        List<ConversationCheckpoint.EvidenceRef> out = new ArrayList<>(fresh);
        List<String> seen = new ArrayList<>();
        for (ConversationCheckpoint.EvidenceRef r : fresh) {
            seen.add(r.toolCallId());
        }
        for (ConversationCheckpoint.EvidenceRef carried
                : ConversationCheckpointEvidenceManifest.revalidateCarriedForward(priorOrNull, liveness)) {
            if (out.size() >= ConversationCheckpointEvidenceManifest.MAX_REFS) {
                break;
            }
            if (!seen.contains(carried.toolCallId())) {
                out.add(carried);
                seen.add(carried.toolCallId());
            }
        }
        return out;
    }

    private static long elapsedSince(long startedAtMillis) {
        return Math.max(0L, System.currentTimeMillis() - startedAtMillis);
    }
}
