package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.joda.time.DateTime;
import org.slf4j.Logger;

import com.thingworx.things.agent.compaction.ConversationCheckpointCodec;
import com.thingworx.things.agent.compaction.ConversationCheckpoint;
import com.thingworx.things.agent.compaction.ConversationCheckpointRehydrate;
import com.thingworx.things.agent.compaction.ConversationCheckpointWorkingSet;
import com.thingworx.things.agent.compaction.ConversationCheckpointEvents;
import com.thingworx.things.agent.cache.ArtifactCacheLiveness;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmUsageTelemetry;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.CompactFetchStreamRehydrate;
import com.thingworx.types.collections.ValueCollection;

/**
 * Transcript-first LLM history rebuild from {@link AgentMessageStreamAppender} ({@code docs/agent/conversation-continuity.md}).
 */
public final class AgentConversationRehydrator {

    private AgentConversationRehydrator() {}

    /**
     * A rehydrated transcript and the working-checkpoint state that belongs with it.
     *
     * <p>The reconciliation is deliberately <b>not</b> applied while building: {@code _conversations.putIfAbsent}
     * can lose the race, and writing JVM working state for a list that never gets published would leave the next
     * compaction summarizing from a checkpoint no model was shown. The caller applies it for the list that wins.
     */
    public static final class Rehydrated {
        private final List<ChatMessage> messages;
        private final ConversationCheckpoint survivingCheckpointOrNull;

        Rehydrated(List<ChatMessage> messages, ConversationCheckpoint survivingCheckpointOrNull) {
            this.messages = messages;
            this.survivingCheckpointOrNull = survivingCheckpointOrNull;
        }

        public List<ChatMessage> messages() {
            return messages;
        }

        /**
         * Makes the JVM working checkpoint agree with what this transcript actually contains — recording the
         * revalidated envelope when its injected row survived, and <b>clearing</b> the entry otherwise.
         *
         * <p>Clearing is the half that is easy to omit and impossible to see. A same-JVM AgentThing recreation
         * starts with an empty {@code _conversations} but a populated static working set, so a rejected,
         * budget-dropped, or absent checkpoint would otherwise leave stale semantic state and refs that the next
         * compaction reads and the model was never shown.
         */
        public void reconcileWorkingCheckpoint(String conversationId, String agentThingName) {
            if (survivingCheckpointOrNull != null) {
                ConversationCheckpointWorkingSet.record(agentThingName, conversationId, survivingCheckpointOrNull);
            } else {
                ConversationCheckpointWorkingSet.clearConversation(agentThingName, conversationId);
            }
        }
    }

    /**
     * The reconciliation that belongs with a fresh-thread fallback.
     *
     * <p>A fresh thread publishes no checkpoint, so no JVM working entry may outlive it. This is the same
     * reconciliation {@link Rehydrated#reconcileWorkingCheckpoint} performs for a rejected candidate, applied to
     * the routes where there is no rehydrated list at all — a query or mapping failure, an empty shaped result,
     * an agent mismatch, or rehydrate being disabled. In the same-JVM AgentThing-recreation case, skipping it
     * hands the model a fresh transcript while the turn's post-turn compaction still reads the previous
     * instance's semantic state and refs.
     */
    public static void reconcileForFreshThread(String agentThingName, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        ConversationCheckpointWorkingSet.clearConversation(agentThingName, conversationId);
    }

    public static Optional<Rehydrated> rehydrateTranscript(String conversationId, String agentThingName,
            ConversationMetadata metadata, ConversationRehydrateSettings settings, Logger log) {
        if (!settings.isStreamRehydrationEnabled()) {
            return Optional.empty();
        }
        try {
            DateTime startExclusive = StreamHistoryBounds.queryStartAfterClear(metadata.getHistoryClearedAtOrNull());
            List<ValueCollection> rows = AgentMessageStreamReader.queryChronologicalRows(conversationId,
                    settings.getMaxRehydrateMessages(), startExclusive);
            return Optional.of(buildRehydrated(rows, conversationId, agentThingName, settings, log));
        } catch (Exception e) {
            if (log != null) {
                log.warn("AgentConversationRehydrator: failed conversationId={}: {}", conversationId, e.getMessage());
            }
            return Optional.empty();
        }
    }

    /**
     * Everything §9.2 specifies once the bounded window has been read: restore, compose, shape, and — when the
     * checkpoint survived — repopulate the JVM working checkpoint.
     *
     * <p>Package-private so the whole pipeline is testable without a live Stream Thing; {@code rehydrateTranscript}
     * is this plus the query.
     */
    static Rehydrated buildRehydrated(List<ValueCollection> rows, String conversationId,
            String agentThingName, ConversationRehydrateSettings settings, Logger log) {
        Optional<ConversationCheckpointRehydrate.Restored> restored =
                ConversationCheckpointRehydrate.restore(rows, conversationId, agentThingName,
                        cacheId -> ArtifactCacheLiveness.isIndexedForConversation(conversationId, cacheId), log);
        List<ChatMessage> mapped = compose(restored, rows, conversationId, agentThingName, settings, log);
        applyCoherentTranscriptBoundary(mapped, settings, log);
        applyCharBudget(mapped, settings.getMaxRehydrateChars(), settings, log, conversationId);
        applyCoherentTranscriptBoundary(mapped, settings, log);
        // §6.3 across a restart: the working checkpoint is whatever this transcript actually carries — the
        // revalidated envelope when its injected row survived every step above, and nothing at all otherwise.
        boolean survived = restored.isPresent() && containsInjectedCheckpoint(mapped);
        return new Rehydrated(mapped, survived ? restored.get().checkpoint() : null);
    }

    /**
     * §9.2 steps 1–5, then step 3's tail: the validated checkpoint's prefix followed by the rows appended after
     * its Stream row.
     *
     * <p>When no checkpoint validates this is exactly today's transcript-first path over the whole window —
     * §9.2 step 7 — so a rejected or absent checkpoint costs navigation, never history.
     */
    static List<ChatMessage> compose(Optional<ConversationCheckpointRehydrate.Restored> restored,
            List<ValueCollection> rows, String conversationId, String agentThingName,
            ConversationRehydrateSettings settings, Logger log) {
        if (restored.isEmpty()) {
            return mapAndFilterRows(rows, conversationId, agentThingName, settings, log);
        }
        List<ChatMessage> out = new ArrayList<>(restored.get().head());
        out.addAll(mapAndFilterRows(rows.subList(restored.get().checkpointRowIndex() + 1, rows.size()),
                conversationId, agentThingName, settings, log));
        return out;
    }

    private static boolean containsInjectedCheckpoint(List<ChatMessage> messages) {
        for (ChatMessage m : messages) {
            if (ConversationCheckpointCodec.isInjectedCheckpoint(m)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Package-private for compact-fetch Stream rehydration tests ({@code docs/agent/context-compaction.md} §9 Stage 2).
     */
    static List<ChatMessage> mapAndFilterRows(List<ValueCollection> chronologicalRows, String conversationId,
            String agentThingName, ConversationRehydrateSettings settings, Logger log) {
        List<ChatMessage> out = new ArrayList<>();
        int agentMismatch = 0;
        for (ValueCollection row : chronologicalRows) {
            String role = AgentMessageStreamReader.stringField(row, "role").trim().toLowerCase(Locale.ROOT);
            if ("system".equals(role)) {
                continue;
            }
            if ("ui_feedback".equals(role)) {
                continue;
            }
            String rowAgent = AgentMessageStreamReader.stringField(row, "agentThing").trim();
            if (!rowAgent.isEmpty() && agentThingName != null && !agentThingName.equals(rowAgent)) {
                agentMismatch++;
                continue;
            }
            if ("tool".equals(role)) {
                String toolContent = AgentMessageStreamReader.stringField(row, "content");
                if (shouldOmitPlaybookInternalToolFromLlmRehydrate(toolContent)) {
                    continue;
                }
                String toolCallId = AgentMessageStreamReader.stringField(row, "toolCallId").trim();
                String prepared = CompactFetchStreamRehydrate.prepareRehydratedToolContent(conversationId, toolContent);
                if (prepared == null) {
                    continue;
                }
                if (toolCallId.isEmpty()) {
                    if (settings.isRehydrateWarnOnSkippedRows() && log != null) {
                        log.debug("AgentConversationRehydrator: skip compact tool row with empty toolCallId");
                    }
                    continue;
                }
                // §11 pairing: never emit Role.TOOL without a preceding assistant tool_calls row (skipped per §11 MVP).
                out.add(ChatMessage.assistant(
                        CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX + prepared));
                continue;
            }
            String toolCalls = AgentMessageStreamReader.stringField(row, "toolCalls");
            if ("assistant".equals(role)) {
                if (toolCalls != null && !toolCalls.trim().isEmpty()) {
                    continue;
                }
                String content = AgentMessageStreamReader.stringField(row, "content");
                if (content == null || content.isEmpty()) {
                    continue;
                }
                out.add(ChatMessage.assistant(content));
                continue;
            }
            if ("user".equals(role)) {
                String content = AgentMessageStreamReader.stringField(row, "content");
                if (content == null || content.isEmpty()) {
                    continue;
                }
                out.add(ChatMessage.user(content));
                continue;
            }
            if (settings.isRehydrateWarnOnSkippedRows() && log != null) {
                log.debug("AgentConversationRehydrator: skip unknown role {}", role);
            }
        }
        if (agentMismatch > 0 && log != null) {
            log.warn("AgentConversationRehydrator: skipped {} rows with agentThing mismatch", agentMismatch);
        }
        return out;
    }

    private static boolean shouldOmitPlaybookInternalToolFromLlmRehydrate(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String t = content.trim();
        if (!t.startsWith("{")) {
            return false;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(t);
            return o.optBoolean(ParlerPlaybookArtifactWireConstants.OMIT_FROM_LLM_REHYDRATE_JSON_KEY, false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * §13 — drop leading assistant rows until first user.
     *
     * <p><b>§9.2 step 6 exemption.</b> This helper exists to stop a restored transcript from starting with an
     * assistant row, and step 5 places the injected checkpoint at index 0 as exactly such a row — so without the
     * exemption the continuity payload is the first thing deleted, on a path that logs nothing and returns
     * successfully. When the leading row is the checkpoint, the normal strip resumes at the row after it.
     */
    static void applyCoherentTranscriptBoundary(List<ChatMessage> messages, ConversationRehydrateSettings settings,
            Logger log) {
        int start = !messages.isEmpty() && ConversationCheckpointCodec.isInjectedCheckpoint(messages.get(0)) ? 1 : 0;
        while (messages.size() > start && messages.get(start).getRole() == ChatMessage.Role.ASSISTANT) {
            messages.remove(start);
        }
        ChatMessage.Role prev = null;
        for (ChatMessage m : messages) {
            if (prev != null && prev == m.getRole() && settings.isRehydrateWarnOnSkippedRows() && log != null) {
                log.debug("AgentConversationRehydrator: consecutive {} rows in restored transcript", m.getRole());
            }
            prev = m.getRole();
        }
    }

    /**
     * §13 char budget, evicting oldest-first.
     *
     * <p><b>§9.2 step 6 exemption.</b> The checkpoint sits at the head, so it is otherwise the first row this
     * budget discards. It is protected here — but not unconditionally: a history of nothing but a checkpoint is
     * worse than no checkpoint, because the model would be told where the work stands with no transcript to
     * continue from. When the budget cannot hold the checkpoint plus at least one complete user-led pair, the
     * checkpoint is dropped explicitly, reported, and the budget re-applied over the freed space.
     */
    static void applyCharBudget(List<ChatMessage> messages, int maxChars, ConversationRehydrateSettings settings,
            Logger log, String conversationIdOrNull) {
        boolean hasCheckpoint = !messages.isEmpty()
                && ConversationCheckpointCodec.isInjectedCheckpoint(messages.get(0));
        boolean keepCheckpoint = false;
        if (hasCheckpoint) {
            // Decided on a trial before anything is removed. Evicting first and asking afterwards would already
            // have spent the transcript making room for a checkpoint that then has to be dropped anyway, leaving
            // less history than never protecting it at all.
            List<ChatMessage> trial = new ArrayList<>(messages);
            evictOldestUntilWithinBudget(trial, maxChars, 1, settings, null);
            keepCheckpoint = transcriptCharCount(trial) <= maxChars && hasCompleteUserLedPair(trial);
            if (!keepCheckpoint) {
                messages.remove(0);
                ConversationCheckpointEvents.skip(log, conversationIdOrNull != null ? conversationIdOrNull : "",
                        LlmUsageTelemetry.effectiveRequestIdForContextPlan(AgentToolContext.getParlerRequestId()),
                        ConversationCheckpointEvents.REASON_CHECKPOINT_CANNOT_FIT, 0L);
            }
        }
        evictOldestUntilWithinBudget(messages, maxChars, keepCheckpoint ? 1 : 0, settings, log);
    }

    /** Removes rows at {@code from} until the whole list fits {@code maxChars}; rows before {@code from} are kept. */
    private static void evictOldestUntilWithinBudget(List<ChatMessage> messages, int maxChars, int from,
            ConversationRehydrateSettings settings, Logger log) {
        int total = transcriptCharCount(messages);
        while (total > maxChars && messages.size() > from) {
            ChatMessage removed = messages.remove(from);
            total -= messageCharCount(removed);
            if (settings.isRehydrateWarnOnSkippedRows() && log != null) {
                log.debug("AgentConversationRehydrator: dropped oldest row under char budget");
            }
        }
    }

    /**
     * A user row with a later <em>final assistant</em> row: the minimum history a checkpoint can usefully sit in
     * front of.
     *
     * <p>Non-adjacent on purpose — compact evidence legitimately sits between a user and the answer it supported —
     * but the completing row must be a real answer. {@link #mapAndFilterRows} restores accepted compact tool rows
     * as {@code ChatMessage.assistant} carrying the Stage-2 frame, so counting any assistant would let
     * server-framed evidence stand in for an answer that was never persisted: a restart between the evidence and
     * the final assistant would keep the checkpoint while no completed pair survives, which is precisely the
     * give-up condition this predicate exists to detect. The injected checkpoint is excluded for the same reason —
     * it is not history either.
     */
    private static boolean hasCompleteUserLedPair(List<ChatMessage> messages) {
        boolean sawUser = false;
        for (ChatMessage m : messages) {
            if (m.getRole() == ChatMessage.Role.USER) {
                sawUser = true;
                continue;
            }
            if (m.getRole() != ChatMessage.Role.ASSISTANT) {
                continue;
            }
            if (ConversationCheckpointCodec.isInjectedCheckpoint(m)
                    || ConversationCheckpointCodec.isStage2Framed(m.getContent())) {
                continue;
            }
            if (sawUser) {
                return true;
            }
        }
        return false;
    }

    private static int transcriptCharCount(List<ChatMessage> messages) {
        int n = 0;
        for (ChatMessage m : messages) {
            n += messageCharCount(m);
        }
        return n;
    }

    private static int messageCharCount(ChatMessage m) {
        String c = m.getContent();
        return c != null ? c.length() : 0;
    }

}
