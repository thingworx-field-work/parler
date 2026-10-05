package com.thingworx.things.agent.compaction;

import java.util.List;

import org.slf4j.Logger;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.tools.PendingApprovalStore;

/**
 * Slice D phase 2: post-turn in-memory trim of {@code messages} before {@code _conversations.put} when aggregate
 * char estimate exceeds {@code maxStorageChars} ({@code docs/agent/context-compaction.md} §8 step 4 subset).
 *
 * <p>Drop order matches §8 for this v1 subset: (1) oldest complete historic assistant tool-call batches (assistant +
 * contiguous trailing {@code TOOL} rows) entirely <strong>before</strong> the last {@code USER} row, then (2) oldest
 * adjacent historic {@code USER} + prose {@code ASSISTANT} pairs, also strictly before that last user. Leading stable
 * system row (when present) and the last user row and everything after it are never removed.
 *
 * <p>Skipped when {@link PendingApprovalStore#hasPendingForConversationId} is true (same §8 option (b) as Tier B
 * deferral) or when replay compaction is not effective. Does not throw: if the list still exceeds the cap after
 * exhaustive drops, emits a single {@code WARN} (no {@code LLM_CONTEXT_PLAN_FAIL}; reserved for any future fail-closed
 * normalization throw per §13).
 */
public final class ConversationsStorageBudgetTrimmer {

    /** Upper bound on inner-loop iterations to avoid pathological spin on unexpected shapes. */
    private static final int MAX_TRIM_PASSES = 10_000;

    private ConversationsStorageBudgetTrimmer() {}

    /** Counters for structured logging after a trim pass. */
    public static final class TrimResult {
        public final int droppedAssistantBatches;
        public final int droppedToolResultRows;
        public final int droppedTranscriptRows;
        public final int charsBefore;
        public final int charsAfter;

        TrimResult(int droppedAssistantBatches, int droppedToolResultRows, int droppedTranscriptRows,
                int charsBefore, int charsAfter) {
            this.droppedAssistantBatches = droppedAssistantBatches;
            this.droppedToolResultRows = droppedToolResultRows;
            this.droppedTranscriptRows = droppedTranscriptRows;
            this.charsBefore = charsBefore;
            this.charsAfter = charsAfter;
        }

        static TrimResult empty(int chars) {
            return new TrimResult(0, 0, 0, chars, chars);
        }

        public boolean anyRemoval() {
            return droppedAssistantBatches > 0 || droppedToolResultRows > 0 || droppedTranscriptRows > 0;
        }
    }

    /**
     * Mutates {@code messages} in place when over budget; otherwise no-op.
     *
     * @param maxStorageChars inclusive cap on {@link #sumMessageChars}; same configured knob as outbound planning in
     *                        v1 ({@code AgentThing} passes {@code _llmContextMaxChars}).
     */
    public static TrimResult maybeTrimForStorageBudget(
            List<ChatMessage> messages,
            int maxStorageChars,
            boolean compactionEffective,
            Logger log,
            String conversationIdOrNull) {
        if (!compactionEffective || messages == null || messages.isEmpty()) {
            return TrimResult.empty(messages != null ? sumMessageChars(messages) : 0);
        }
        if (conversationIdOrNull != null && !conversationIdOrNull.isBlank()
                && PendingApprovalStore.hasPendingForConversationId(conversationIdOrNull)) {
            if (log != null && log.isDebugEnabled()) {
                log.debug("Slice D: skip storage-budget trim (active pending for conversationId={})",
                        conversationIdOrNull.trim());
            }
            return TrimResult.empty(sumMessageChars(messages));
        }
        int cap = Math.max(1, maxStorageChars);
        int before = sumMessageChars(messages);
        if (before <= cap) {
            return TrimResult.empty(before);
        }
        TrimResult r = applyRemovalPasses(messages, null, cap, before);
        if (r.anyRemoval() && log != null && log.isInfoEnabled()) {
            log.info("{}", storageTrimInfoLine(r.droppedAssistantBatches, r.droppedToolResultRows,
                    r.droppedTranscriptRows, r.charsBefore, r.charsAfter, cap));
        }
        if (r.charsAfter > cap && log != null && log.isWarnEnabled()) {
            log.warn(
                    "CONVERSATIONS_STORAGE_TRIM: messages still exceed cap after trim (charsAfter={} cap={})",
                    r.charsAfter, cap);
        }
        return r;
    }

    /**
     * Pure removal core shared by {@link #maybeTrimForStorageBudget} and
     * {@link ConversationCompactionBoundarySelector}'s dry run, so the two can never disagree about drop order,
     * surviving rows, or counters ({@code docs/core/advanced-compact.md} §7.2 equivalence target).
     *
     * <p>Mutates {@code messages} in place. When {@code originalIndices} is non-null it MUST be a parallel,
     * same-size, mutable list of the caller's original positions; every physical removal from {@code messages} is
     * mirrored into it, so after the call it reports exactly which original rows survived. The production trim path
     * passes {@code null} and pays nothing for the bookkeeping.
     *
     * @param charsBefore pre-computed {@link #sumMessageChars} of {@code messages}, already known to exceed {@code cap}
     */
    static TrimResult applyRemovalPasses(List<ChatMessage> messages, List<Integer> originalIndices, int cap,
            int charsBefore) {
        int batches = 0;
        int toolRows = 0;
        int transcriptRows = 0;
        int passes = 0;
        int totalChars = charsBefore;
        while (totalChars > cap && passes < MAX_TRIM_PASSES) {
            passes++;
            int lastUser = findLastUserIndex(messages);
            if (lastUser < 0) {
                break;
            }
            int[] removed = removeOldestHistoricEvidenceBatch(messages, originalIndices, lastUser);
            if (removed != null) {
                batches++;
                toolRows += removed[0];
                totalChars -= removed[1];
                continue;
            }
            int pairChars = removeOldestHistoricTranscriptPair(messages, originalIndices, lastUser);
            if (pairChars > 0) {
                transcriptRows += 2;
                totalChars -= pairChars;
                continue;
            }
            break;
        }
        return new TrimResult(batches, toolRows, transcriptRows, charsBefore, sumMessageChars(messages));
    }

    /** Removes index {@code k} from {@code messages} and mirrors it into {@code originalIndices} when tracking. */
    private static void removeAt(List<ChatMessage> messages, List<Integer> originalIndices, int k) {
        messages.remove(k);
        if (originalIndices != null) {
            originalIndices.remove(k);
        }
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
     * @return {@code [toolResultRowCount, batchChars]} for telemetry and running total, or {@code null} if no batch
     *         removed
     */
    private static int[] removeOldestHistoricEvidenceBatch(List<ChatMessage> messages, List<Integer> originalIndices,
            int lastUser) {
        int n = messages.size();
        for (int i = 0; i < n; i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.ASSISTANT || !m.hasToolCalls()) {
                continue;
            }
            if (i >= lastUser) {
                continue;
            }
            int end = i;
            int t = i + 1;
            while (t < n && messages.get(t).getRole() == ChatMessage.Role.TOOL) {
                end = t;
                t++;
            }
            if (end >= lastUser) {
                continue;
            }
            int toolCount = end - i;
            int batchChars = 0;
            for (int k = i; k <= end; k++) {
                batchChars += MessageCharEstimator.rowChars(messages.get(k));
            }
            for (int k = end; k >= i; k--) {
                removeAt(messages, originalIndices, k);
            }
            return new int[] { toolCount, batchChars };
        }
        return null;
    }

    /** @return sum of removed row char estimates, or {@code 0} if none removed */
    private static int removeOldestHistoricTranscriptPair(List<ChatMessage> messages, List<Integer> originalIndices,
            int lastUser) {
        int n = messages.size();
        for (int i = 0; i + 1 < n; i++) {
            if (messages.get(i).getRole() != ChatMessage.Role.USER) {
                continue;
            }
            if (i >= lastUser) {
                continue;
            }
            if (i + 1 >= lastUser) {
                continue;
            }
            ChatMessage a = messages.get(i + 1);
            if (a.getRole() != ChatMessage.Role.ASSISTANT || a.hasToolCalls()) {
                continue;
            }
            int pairChars = MessageCharEstimator.rowChars(messages.get(i))
                    + MessageCharEstimator.rowChars(messages.get(i + 1));
            removeAt(messages, originalIndices, i + 1);
            removeAt(messages, originalIndices, i);
            return pairChars;
        }
        return 0;
    }

    static int sumMessageChars(List<ChatMessage> messages) {
        int s = 0;
        for (ChatMessage m : messages) {
            s += MessageCharEstimator.rowChars(m);
        }
        return s;
    }

    /**
     * Stable single-line {@code INFO} shape for storage trim (log-format lock tests assert these
     * substrings).
     */
    static String storageTrimInfoLine(int batches, int toolRows, int transcriptRows, int before, int after, int cap) {
        return "CONVERSATIONS_STORAGE_TRIM: droppedAssistantBatches=" + batches + " droppedToolResultRows=" + toolRows
                + " droppedTranscriptRows=" + transcriptRows + " charsBefore=" + before + " charsAfter=" + after
                + " cap=" + cap;
    }
}
