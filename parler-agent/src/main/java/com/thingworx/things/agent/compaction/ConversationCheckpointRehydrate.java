package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;

import com.thingworx.things.agent.AgentMessageStreamAppender;
import com.thingworx.things.agent.AgentMessageStreamReader;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmUsageTelemetry;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.types.collections.ValueCollection;

/**
 * §9.2 steps 1–5: pick the newest candidate checkpoint row, validate it, and restore the working prefix it stands
 * for.
 *
 * <p><b>Selection and validation are separate and ordered (§6.1.1).</b> Selection <em>filters</em>: a row that is
 * not {@code role=context_checkpoint}, or belongs to another AgentThing, is not a candidate and the scan continues
 * to the next-newest. Validation then runs against the single newest survivor, and any failure falls back to
 * transcript-first — it MUST NOT walk further back for an older checkpoint that might validate. An older
 * {@code semantic} was superseded precisely because the task moved on, so resurrecting it would reinstate state
 * the system already decided was stale.
 */
public final class ConversationCheckpointRehydrate {

    /** Roles that may legitimately interleave between the watermark row and the checkpoint row — the entire set. */
    private static final String ROLE_UI_FEEDBACK = "ui_feedback";
    private static final String ROLE_ASSISTANT = "assistant";

    private ConversationCheckpointRehydrate() {}

    /** A validated checkpoint and the rows it replaces. */
    public static final class Restored {
        private final ConversationCheckpoint checkpoint;
        private final List<ChatMessage> head;
        private final int checkpointRowIndex;

        Restored(ConversationCheckpoint checkpoint, List<ChatMessage> head, int checkpointRowIndex) {
            this.checkpoint = checkpoint;
            this.head = List.copyOf(head);
            this.checkpointRowIndex = checkpointRowIndex;
        }

        /** The envelope, with every {@code cacheId} liveness re-decided for this JVM (§5 invariant 11). */
        public ConversationCheckpoint checkpoint() {
            return checkpoint;
        }

        /** Injected checkpoint prose followed by the exact retained tail, in order (§9.2 steps 3 and 5). */
        public List<ChatMessage> head() {
            return head;
        }

        /** Position of the checkpoint row; rows after it are the new history to append (§9.2 step 3). */
        public int checkpointRowIndex() {
            return checkpointRowIndex;
        }
    }

    /**
     * @param chronologicalRows the bounded newest-N window, oldest-first, exactly as
     *                          {@code AgentMessageStreamReader.queryChronologicalRows} returns it
     * @param livenessOrNull    current-JVM cache lookup; {@code null} degrades every ref to historical-recompute,
     *                          which is what a restart actually means — the in-memory cache is empty
     * @return the restored prefix, or empty when there is no candidate or the candidate fails validation
     */
    public static Optional<Restored> restore(List<ValueCollection> chronologicalRows, String conversationId,
            String agentThingName, ConversationCheckpointEvidenceManifest.LivenessResolver livenessOrNull,
            Logger log) {
        if (chronologicalRows == null || chronologicalRows.isEmpty()
                || conversationId == null || conversationId.isBlank()
                || agentThingName == null || agentThingName.isBlank()) {
            return Optional.empty();
        }
        int idx = newestCandidateIndex(chronologicalRows, agentThingName);
        if (idx < 0) {
            // No checkpoint for this agent in the window: the ordinary case, not a failure.
            return Optional.empty();
        }
        ConversationCheckpoint checkpoint = ConversationCheckpointCodec.parse(
                AgentMessageStreamReader.stringField(chronologicalRows.get(idx), "content"),
                conversationId, agentThingName);
        if (checkpoint == null) {
            skip(log, conversationId);
            return Optional.empty();
        }
        if (!watermarkVerified(chronologicalRows, idx, agentThingName,
                checkpoint.source().throughAssistantMessageId())) {
            skip(log, conversationId);
            return Optional.empty();
        }

        // §9.2 step 4 / §5 invariant 11: liveness is re-decided here, never inherited from the persisted envelope.
        ConversationCheckpoint revalidated = new ConversationCheckpoint(
                checkpoint.source(),
                checkpoint.semantic(),
                ConversationCheckpointEvidenceManifest.revalidateCarriedForward(
                        checkpoint.evidenceRefs(), livenessOrNull),
                checkpoint.retainedTail(),
                checkpoint.generated());

        // §9.2 step 5: assistant provenance. Not system — it gets no system authority; not user — it must not
        // impersonate the user's own words.
        List<ChatMessage> head = new ArrayList<>();
        head.add(ConversationCheckpointCodec.toInjectedAssistant(revalidated.semantic()));
        for (ConversationCheckpoint.RetainedRow r : revalidated.retainedTail()) {
            ChatMessage row = ConversationCheckpointInstaller.toWorkingMessage(r);
            if (row == null) {
                skip(log, conversationId);
                return Optional.empty();
            }
            head.add(row);
        }
        return Optional.of(new Restored(revalidated, head, idx));
    }

    /**
     * Newest row that survives the §6.1.1 selection filters.
     *
     * <p>Rows at or before {@code historyClearedAt} are already outside the query bound, so the filters applied
     * here are the remaining two: the role must be {@code context_checkpoint}, and the row must belong to this
     * AgentThing. A non-matching row is skipped and the scan continues — that is what "invalid rows are skipped"
     * means, and it is distinct from validation, which never continues.
     */
    private static int newestCandidateIndex(List<ValueCollection> rows, String agentThingName) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            ValueCollection row = rows.get(i);
            if (!AgentMessageStreamAppender.ROLE_CONTEXT_CHECKPOINT.equals(
                    AgentMessageStreamReader.stringField(row, "role"))) {
                continue;
            }
            if (!agentThingName.equals(AgentMessageStreamReader.stringField(row, "agentThing"))) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /**
     * §6.1.1: an allowlisted backward walk, not an adjacency assumption.
     *
     * <p>Two of the four covered paths publish the assistant id to the client before post-turn normalization runs,
     * and the checkpoint entry then makes a Provider call before appending its row — so the widget can append any
     * number of {@code ui_feedback} rows in between. That is supported product behaviour. "Nearest preceding
     * assistant" would be wrong in the other direction too: it can cross a user row, a tool row, or an assistant
     * from another turn entirely and still find a matching id, so the allowed row class is encoded rather than
     * inherited from incidental timing.
     *
     * <p>Running off the start of the bounded window is a rejection, not a search: a watermark outside the window
     * means the window is already full of rows newer than the checkpoint, so the exact recent tail alone is rich
     * and what is lost is navigation, not evidence.
     */
    private static boolean watermarkVerified(List<ValueCollection> rows, int checkpointIdx, String agentThingName,
            String expectedAssistantMessageId) {
        if (expectedAssistantMessageId == null || expectedAssistantMessageId.isBlank()) {
            return false;
        }
        for (int i = checkpointIdx - 1; i >= 0; i--) {
            ValueCollection row = rows.get(i);
            String role = AgentMessageStreamReader.stringField(row, "role");
            if (ROLE_UI_FEEDBACK.equals(role)) {
                continue;
            }
            // The first non-inert row decides. Anything that is not this turn's final assistant fails closed —
            // including an unknown or future role, which must be added to the inert set by explicit design change
            // rather than falling through as inert by default.
            if (!ROLE_ASSISTANT.equals(role)) {
                return false;
            }
            String toolCalls = AgentMessageStreamReader.stringField(row, "toolCalls");
            if (toolCalls != null && !toolCalls.trim().isEmpty()) {
                return false;
            }
            if (!agentThingName.equals(AgentMessageStreamReader.stringField(row, "agentThing"))) {
                return false;
            }
            return expectedAssistantMessageId.equals(
                    AgentMessageStreamReader.stringField(row, "assistantMessageId"));
        }
        return false;
    }

    /**
     * Every validation failure of the selected candidate reports the one rehydrate-side reason, with duration
     * zero.
     *
     * <p>§13 states it directly — "any validation failure falls back to transcript-first with
     * {@code WATERMARK_UNVERIFIED}" — and §12's duration semantics make it the only consistent choice:
     * {@code INVALID_JSON} denotes a rejection <em>after</em> the summary Provider call began and must carry that
     * call's measured latency, while rehydrate makes no such call and would report zero. Reusing it here would put
     * a permanent zero into the field that exists to measure that latency.
     */
    private static void skip(Logger log, String conversationId) {
        ConversationCheckpointEvents.skip(log, conversationId,
                LlmUsageTelemetry.effectiveRequestIdForContextPlan(AgentToolContext.getParlerRequestId()),
                ConversationCheckpointEvents.REASON_WATERMARK_UNVERIFIED, 0L);
    }
}
