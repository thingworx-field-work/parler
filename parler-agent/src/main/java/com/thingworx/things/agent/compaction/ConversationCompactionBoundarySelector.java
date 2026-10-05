package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.PendingApprovalStore;

/**
 * Slice A of {@code docs/core/advanced-compact.md}: pure, non-mutating dry run of the post-turn storage trim that
 * reports <em>what would be lost</em> instead of removing it.
 *
 * <p>This class generates no checkpoint, calls no model, writes no Stream row, and mutates nothing the caller owns.
 * It answers one question — "is a semantic transcript about to be deleted, and if so which rows does a checkpoint
 * have to cover?" — so §7.1's single automatic trigger can be evaluated before
 * {@link ConversationsStorageBudgetTrimmer} does the deletion.
 *
 * <p><b>Equivalence is structural, not asserted (§7.2).</b> The removal passes are not reimplemented here: this
 * class calls {@link ConversationsStorageBudgetTrimmer#applyRemovalPasses} on a defensive copy, so evidence-first
 * drop order, surviving rows, and removal counters are the same code and cannot drift from the production trim.
 * The compaction, pending-approval, and cap gates likewise call the same helpers the trimmer calls rather than
 * re-deriving the conditions.
 *
 * <p><b>Stricter in exactly one direction.</b> The trimmer treats physically contiguous {@code TOOL} rows as a
 * batch. §5 invariant 4 requires a checkpoint boundary to be validated by tool-call <em>ids</em>, not adjacency, so
 * this selector additionally fails closed to {@link Outcome#NO_SAFE_BOUNDARY} on a broken batch anywhere in the
 * list or an orphan tool row. When a plan <em>is</em> returned it matches the trimmer exactly; when the selector
 * fails closed, no checkpoint is attempted and the trimmer runs unchanged. The selector never widens what the
 * trimmer would remove.
 *
 * <p><b>The semantic cutoff is not the removed set (§7.2).</b> The trim drains every historical evidence batch
 * before it removes any transcript pair, so once a transcript drop is required, evidence from a newer turn whose
 * transcript still survives has already been removed. {@link BoundaryPlan#coveredPrefix()} is therefore derived
 * from the newest removed transcript pair and is a genuine prefix of complete turns, while the trimmer's counters
 * and {@link BoundaryPlan#materializedSurvivors()} are preserved separately for the equivalence check.
 *
 * <p><b>One gate is deliberately stricter than the trimmer (§7.1).</b> A blank conversation id lets the trimmer
 * skip its pending check and trim anyway; a checkpoint cannot be persisted or re-selected without an identity, so
 * the selector reports {@link Outcome#NO_CONVERSATION_ID} instead. That can only suppress a checkpoint, never
 * change what the trimmer removes.
 */
public final class ConversationCompactionBoundarySelector {

    /** Why a checkpoint is or is not warranted for this turn. */
    public enum Outcome {
        /** A transcript row would be deleted; {@link BoundaryPlan#coveredPrefix()} must be covered by a checkpoint. */
        CHECKPOINT_ELIGIBLE,
        /** Replay compaction is disabled for this JVM; the trimmer itself is a no-op (§7.1 inherited gates). */
        COMPACTION_DISABLED,
        /** An approval is pending for this conversation; the trimmer defers and so does the checkpoint. */
        PENDING_HITL,
        /** No usable conversation identity, so there is nothing to persist a checkpoint against. */
        NO_CONVERSATION_ID,
        /** Already at or below the storage cap; nothing would be removed. */
        WITHIN_CAP,
        /** Only historical tool evidence would be dropped; deterministic evidence compaction already covers it. */
        NO_TRANSCRIPT_DROP,
        /** Tool-call pairing could not be verified across the covered region; fail closed (§5 invariant 4). */
        NO_SAFE_BOUNDARY
    }

    /**
     * Immutable result. {@code coveredPrefix} and {@code retainedTail} are in original document order, never in
     * removal order, so a checkpoint's semantic state and retained tail read as the conversation actually ran.
     */
    public static final class BoundaryPlan {

        private final Outcome outcome;
        private final List<ChatMessage> coveredPrefix;
        private final List<ChatMessage> retainedTail;
        private final List<ChatMessage> materializedSurvivors;
        private final int droppedAssistantBatches;
        private final int droppedToolResultRows;
        private final int droppedTranscriptRows;
        private final int charsBefore;
        private final int charsAfter;

        private BoundaryPlan(Outcome outcome, List<ChatMessage> coveredPrefix, List<ChatMessage> retainedTail,
                List<ChatMessage> materializedSurvivors, int droppedAssistantBatches, int droppedToolResultRows,
                int droppedTranscriptRows, int charsBefore, int charsAfter) {
            this.outcome = outcome;
            this.coveredPrefix = Collections.unmodifiableList(coveredPrefix);
            this.retainedTail = Collections.unmodifiableList(retainedTail);
            this.materializedSurvivors = Collections.unmodifiableList(materializedSurvivors);
            this.droppedAssistantBatches = droppedAssistantBatches;
            this.droppedToolResultRows = droppedToolResultRows;
            this.droppedTranscriptRows = droppedTranscriptRows;
            this.charsBefore = charsBefore;
            this.charsAfter = charsAfter;
        }

        /** Gate that fired before any dry run happened: no removal occurred, so all counters are genuinely zero. */
        static BoundaryPlan beforeDryRun(Outcome outcome, List<ChatMessage> messages, int chars) {
            List<ChatMessage> survivors = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
            return new BoundaryPlan(outcome, new ArrayList<>(), new ArrayList<>(), survivors, 0, 0, 0, chars, chars);
        }

        /**
         * Ineligible for a checkpoint, but the dry run already ran: the trimmer's counters and materialized survivor
         * view are real and are preserved, because §7.2's equivalence requirement does not depend on whether a
         * checkpoint was warranted.
         */
        static BoundaryPlan ineligibleAfterDryRun(Outcome outcome, List<ChatMessage> survivors,
                ConversationsStorageBudgetTrimmer.TrimResult trim) {
            return new BoundaryPlan(outcome, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(survivors),
                    trim.droppedAssistantBatches, trim.droppedToolResultRows, trim.droppedTranscriptRows,
                    trim.charsBefore, trim.charsAfter);
        }

        public Outcome outcome() {
            return outcome;
        }

        public boolean isCheckpointEligible() {
            return outcome == Outcome.CHECKPOINT_ELIGIBLE;
        }

        /**
         * The semantic span a checkpoint must replace: every non-{@code SYSTEM} row at or before the cutoff, in
         * document order. This is a genuine prefix of complete turns — not "the rows the trim removed" (§7.2). It
         * includes rows in that span the trim did not itself remove, and it excludes evidence the trim removed from
         * newer turns whose transcript survives, because that evidence belongs to a turn the checkpoint does not
         * cover. Empty unless {@link #isCheckpointEligible()}.
         */
        public List<ChatMessage> coveredPrefix() {
            return coveredPrefix;
        }

        /**
         * Surviving non-{@code SYSTEM} rows <em>after</em> the cutoff, in document order — what a checkpoint would
         * sit in front of. The stable prompt is not transcript and is not rehydrate-safe tail material (§6.2), so it
         * is excluded here even though the char counters, which mirror the trimmer, still count it. Slice B applies
         * the §6.2 acceptance policy to this list to build the persisted {@code retainedTail}. Empty unless
         * {@link #isCheckpointEligible()}.
         */
        public List<ChatMessage> retainedTail() {
            return retainedTail;
        }

        /**
         * The full row list the production trimmer would leave behind, system row included, for the §7.2 equivalence
         * check. Unlike {@link #retainedTail()} this is not cutoff-scoped and is populated for every outcome,
         * including the ineligible ones — a dry run that happened is evidence whether or not a checkpoint followed.
         */
        public List<ChatMessage> materializedSurvivors() {
            return materializedSurvivors;
        }

        public int droppedAssistantBatches() {
            return droppedAssistantBatches;
        }

        public int droppedToolResultRows() {
            return droppedToolResultRows;
        }

        public int droppedTranscriptRows() {
            return droppedTranscriptRows;
        }

        public int charsBefore() {
            return charsBefore;
        }

        public int charsAfter() {
            return charsAfter;
        }
    }

    private ConversationCompactionBoundarySelector() {}

    /**
     * Dry-runs the storage trim without touching {@code messages}.
     *
     * @param messages           the caller's live list; never mutated, never retained
     * @param maxStorageChars    same cap the trimmer receives ({@code AgentThing._llmContextMaxChars})
     * @param compactionEffective same gate the trimmer receives
     * @param conversationIdOrNull same conversation id the trimmer receives
     */
    public static BoundaryPlan plan(
            List<ChatMessage> messages,
            int maxStorageChars,
            boolean compactionEffective,
            String conversationIdOrNull) {
        int chars = messages != null ? ConversationsStorageBudgetTrimmer.sumMessageChars(messages) : 0;
        if (!compactionEffective) {
            return BoundaryPlan.beforeDryRun(Outcome.COMPACTION_DISABLED, messages, chars);
        }
        if (messages == null || messages.isEmpty()) {
            return BoundaryPlan.beforeDryRun(Outcome.WITHIN_CAP, messages, chars);
        }
        if (conversationIdOrNull == null || conversationIdOrNull.isBlank()) {
            return BoundaryPlan.beforeDryRun(Outcome.NO_CONVERSATION_ID, messages, chars);
        }
        if (PendingApprovalStore.hasPendingForConversationId(conversationIdOrNull)) {
            return BoundaryPlan.beforeDryRun(Outcome.PENDING_HITL, messages, chars);
        }
        int cap = Math.max(1, maxStorageChars);
        if (chars <= cap) {
            return BoundaryPlan.beforeDryRun(Outcome.WITHIN_CAP, messages, chars);
        }

        List<ChatMessage> working = new ArrayList<>(messages);
        List<Integer> survivingOriginalIndices = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            survivingOriginalIndices.add(i);
        }
        ConversationsStorageBudgetTrimmer.TrimResult trim =
                ConversationsStorageBudgetTrimmer.applyRemovalPasses(working, survivingOriginalIndices, cap, chars);

        if (trim.droppedTranscriptRows == 0) {
            return BoundaryPlan.ineligibleAfterDryRun(Outcome.NO_TRANSCRIPT_DROP, working, trim);
        }

        boolean[] survives = new boolean[messages.size()];
        for (Integer idx : survivingOriginalIndices) {
            survives[idx] = true;
        }
        if (!toolPairingIsComplete(messages, survives)) {
            return BoundaryPlan.ineligibleAfterDryRun(Outcome.NO_SAFE_BOUNDARY, working, trim);
        }

        int cutoff = newestRemovedTranscriptIndex(messages, survives);
        if (cutoff < 0) {
            return BoundaryPlan.ineligibleAfterDryRun(Outcome.NO_SAFE_BOUNDARY, working, trim);
        }

        List<ChatMessage> coveredPrefix = new ArrayList<>();
        for (int i = 0; i <= cutoff; i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.SYSTEM) {
                coveredPrefix.add(m);
            }
        }
        List<ChatMessage> retainedTail = new ArrayList<>();
        for (int i = cutoff + 1; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (survives[i] && m.getRole() != ChatMessage.Role.SYSTEM) {
                retainedTail.add(m);
            }
        }
        return new BoundaryPlan(Outcome.CHECKPOINT_ELIGIBLE, coveredPrefix, retainedTail, new ArrayList<>(working),
                trim.droppedAssistantBatches, trim.droppedToolResultRows, trim.droppedTranscriptRows,
                trim.charsBefore, trim.charsAfter);
    }

    /**
     * §5 invariant 4: a boundary is usable only when every assistant tool-call batch touching the covered region is
     * complete and id-matched, and no {@code TOOL} row is orphaned. Verified against the original list so a batch
     * split by the removal passes is caught rather than hidden.
     */
    private static boolean toolPairingIsComplete(List<ChatMessage> messages, boolean[] survives) {
        int n = messages.size();
        for (int i = 0; i < n; i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.ASSISTANT || !m.hasToolCalls()) {
                continue;
            }
            int end = i;
            while (end + 1 < n && messages.get(end + 1).getRole() == ChatMessage.Role.TOOL) {
                end++;
            }
            // Completeness is global: a surviving or current-turn batch can be incomplete without being an orphan,
            // and §5 invariant 4 admits no incomplete batch anywhere in the candidate list.
            if (!batchIdsMatch(m, messages, i, end)) {
                return false;
            }
            boolean anyRemoved = false;
            boolean anySurvived = false;
            for (int k = i; k <= end; k++) {
                if (survives[k]) {
                    anySurvived = true;
                } else {
                    anyRemoved = true;
                }
            }
            // Whole-removal stays scoped to the cutoff: only there can partial removal split a batch.
            if (anyRemoved && anySurvived) {
                return false;
            }
        }
        return !hasOrphanToolRow(messages);
    }

    /**
     * Highest original index among rows the transcript pass removed. The evidence pass only ever removes an
     * assistant tool-call row and its {@code TOOL} results, so a removed {@code USER} row or a removed prose
     * {@code ASSISTANT} row is necessarily a transcript-pair row. That index is the semantic cutoff (§7.2).
     *
     * @return {@code -1} when no transcript row was removed, which the caller treats as fail-closed
     */
    private static int newestRemovedTranscriptIndex(List<ChatMessage> messages, boolean[] survives) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (survives[i]) {
                continue;
            }
            ChatMessage m = messages.get(i);
            if (m.getRole() == ChatMessage.Role.USER
                    || (m.getRole() == ChatMessage.Role.ASSISTANT && !m.hasToolCalls())) {
                return i;
            }
        }
        return -1;
    }

    /** Declared tool-call ids and the following {@code TOOL} rows must be the same non-empty set, one row each. */
    private static boolean batchIdsMatch(ChatMessage assistant, List<ChatMessage> messages, int start, int end) {
        Set<String> declared = new HashSet<>();
        for (ToolCall tc : assistant.getToolCalls()) {
            if (tc == null || tc.getId() == null || tc.getId().isBlank()) {
                return false;
            }
            if (!declared.add(tc.getId())) {
                return false;
            }
        }
        if (declared.isEmpty()) {
            return false;
        }
        Set<String> observed = new HashSet<>();
        for (int k = start + 1; k <= end; k++) {
            String id = messages.get(k).getToolCallId();
            if (id == null || id.isBlank() || !observed.add(id)) {
                return false;
            }
        }
        return declared.equals(observed);
    }

    /**
     * A {@code TOOL} row whose nearest preceding non-{@code TOOL} row is not an assistant tool-call row. Checked
     * across the whole list, not just the covered region: an orphan anywhere means the working set is malformed, and
     * §7.2 fails closed on that rather than checkpointing around it. Batch <em>completeness</em> above is scoped to
     * the covered region, because that is what the boundary actually cuts.
     */
    private static boolean hasOrphanToolRow(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getRole() != ChatMessage.Role.TOOL) {
                continue;
            }
            int j = i - 1;
            while (j >= 0 && messages.get(j).getRole() == ChatMessage.Role.TOOL) {
                j--;
            }
            if (j < 0) {
                return true;
            }
            ChatMessage owner = messages.get(j);
            if (owner.getRole() != ChatMessage.Role.ASSISTANT || !owner.hasToolCalls()) {
                return true;
            }
        }
        return false;
    }
}
