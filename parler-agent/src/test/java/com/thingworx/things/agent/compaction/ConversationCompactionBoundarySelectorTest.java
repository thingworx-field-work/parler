package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.PendingApprovalRecord;
import com.thingworx.things.agent.tools.PendingApprovalStore;

/**
 * Slice A of {@code docs/core/advanced-compact.md}: the boundary dry run must agree with
 * {@link ConversationsStorageBudgetTrimmer} exactly, fire only when semantic transcript is about to be lost, and
 * fail closed on unverifiable tool pairing.
 */
class ConversationCompactionBoundarySelectorTest {

    private static final String CID = "conv-boundary-selector";

    @AfterEach
    void clearPending() {
        PendingApprovalStore.remove("pid-boundary-selector");
    }

    private static String big(char c) {
        return String.valueOf(c).repeat(1_000);
    }

    /** system + two complete historical transcript pairs + current turn. */
    private static List<ChatMessage> twoHistoricalPairs() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.user(big('u')));
        m.add(ChatMessage.assistant(big('a')));
        m.add(ChatMessage.user(big('v')));
        m.add(ChatMessage.assistant(big('b')));
        m.add(ChatMessage.user("last"));
        m.add(ChatMessage.assistant("final"));
        return m;
    }

    // --- §7.2 equivalence with the production trimmer -------------------------------------------------

    @Test
    void planMatchesTrimmerCountersAndSurvivingRows() {
        List<ChatMessage> forSelector = twoHistoricalPairs();
        List<ChatMessage> forTrimmer = twoHistoricalPairs();

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(forSelector, 3_000, true, CID);
        ConversationsStorageBudgetTrimmer.TrimResult trim =
                ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget(forTrimmer, 3_000, true, null, CID);

        assertEquals(trim.droppedAssistantBatches, plan.droppedAssistantBatches());
        assertEquals(trim.droppedToolResultRows, plan.droppedToolResultRows());
        assertEquals(trim.droppedTranscriptRows, plan.droppedTranscriptRows());
        assertEquals(trim.charsBefore, plan.charsBefore());
        assertEquals(trim.charsAfter, plan.charsAfter());

        assertEquals(describe(forTrimmer), describe(plan.materializedSurvivors()));
    }

    private static List<String> describe(List<ChatMessage> rows) {
        List<String> out = new ArrayList<>();
        for (ChatMessage m : rows) {
            out.add(m.getRole() + ":" + (m.hasToolCalls() ? "toolcalls" : m.getContent()));
        }
        return out;
    }

    @Test
    void planDoesNotMutateCallerList() {
        List<ChatMessage> messages = twoHistoricalPairs();
        int sizeBefore = messages.size();
        ChatMessage firstBefore = messages.get(0);

        ConversationCompactionBoundarySelector.plan(messages, 3_000, true, CID);

        assertEquals(sizeBefore, messages.size());
        assertSame(firstBefore, messages.get(0));
    }

    @Test
    void returnedListsAreUnmodifiable() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(twoHistoricalPairs(), 3_000, true, CID);
        assertThrows(UnsupportedOperationException.class,
                () -> plan.coveredPrefix().add(ChatMessage.user("x")));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.retainedTail().add(ChatMessage.user("x")));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.materializedSurvivors().add(ChatMessage.user("x")));
    }

    // --- §7.1 trigger ---------------------------------------------------------------------------------

    @Test
    void transcriptDrop_isCheckpointEligible_withCoveredPrefixInDocumentOrder() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(twoHistoricalPairs(), 3_000, true, CID);

        assertTrue(plan.isCheckpointEligible());
        assertEquals(2, plan.droppedTranscriptRows());
        assertEquals(2, plan.coveredPrefix().size());
        assertEquals(ChatMessage.Role.USER, plan.coveredPrefix().get(0).getRole());
        assertEquals(ChatMessage.Role.ASSISTANT, plan.coveredPrefix().get(1).getRole());
        assertEquals(big('u'), plan.coveredPrefix().get(0).getContent());
        assertEquals(big('a'), plan.coveredPrefix().get(1).getContent());
    }

    @Test
    void evidenceOnlyDrop_doesNotWarrantCheckpoint() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        m.add(ChatMessage.toolResult("t1", big('z')));
        m.add(ChatMessage.user("last"));
        m.add(ChatMessage.assistant("final"));

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(m, 500, true, CID);

        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_TRANSCRIPT_DROP, plan.outcome());
        assertFalse(plan.isCheckpointEligible());
        assertTrue(plan.coveredPrefix().isEmpty());
    }

    @Test
    void withinCap_isNotEligible() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(twoHistoricalPairs(), 1_000_000, true, CID);
        assertEquals(ConversationCompactionBoundarySelector.Outcome.WITHIN_CAP, plan.outcome());
    }

    // --- §7.1 inherited gates -------------------------------------------------------------------------

    @Test
    void compactionDisabled_matchesTrimmerNoOp() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(twoHistoricalPairs(), 3_000, false, CID);
        assertEquals(ConversationCompactionBoundarySelector.Outcome.COMPACTION_DISABLED, plan.outcome());
    }

    @Test
    void blankConversationId_isNotEligible() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(twoHistoricalPairs(), 3_000, true, "  ");
        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_CONVERSATION_ID, plan.outcome());
    }

    @Test
    void pendingHitl_isNeverSummarized() {
        PendingApprovalStore.put(new PendingApprovalRecord(
                "pid-boundary-selector",
                "rid",
                CID,
                "p",
                "AgentThing",
                "r",
                new ToolCall("g", "invoke_service", "{}"),
                Collections.emptyList(),
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000));
        try {
            ConversationCompactionBoundarySelector.BoundaryPlan plan =
                    ConversationCompactionBoundarySelector.plan(twoHistoricalPairs(), 3_000, true, CID);
            assertEquals(ConversationCompactionBoundarySelector.Outcome.PENDING_HITL, plan.outcome());
        } finally {
            PendingApprovalStore.remove("pid-boundary-selector");
        }
    }

    // --- §5 invariant 4: fail closed on unverifiable pairing ------------------------------------------

    @Test
    void incompleteBatchInCoveredRegion_failsClosed() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.user(big('u')));
        m.add(ChatMessage.assistant(big('a')));
        // Declares two calls, only one result row follows.
        m.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("t1", "query_entities", "{}"), new ToolCall("t2", "fetch_cached_result", "{}"))));
        m.add(ChatMessage.toolResult("t1", big('z')));
        m.add(ChatMessage.user("last"));
        m.add(ChatMessage.assistant("final"));

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(m, 1_500, true, CID);

        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_SAFE_BOUNDARY, plan.outcome());
        assertFalse(plan.isCheckpointEligible());
    }

    @Test
    void mismatchedToolCallIds_failClosed() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.user(big('u')));
        m.add(ChatMessage.assistant(big('a')));
        m.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        m.add(ChatMessage.toolResult("OTHER-ID", big('z')));
        m.add(ChatMessage.user("last"));
        m.add(ChatMessage.assistant("final"));

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(m, 1_500, true, CID);

        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_SAFE_BOUNDARY, plan.outcome());
    }

    @Test
    void orphanToolRow_failsClosed() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.toolResult("ghost", "orphaned result"));
        m.add(ChatMessage.user(big('u')));
        m.add(ChatMessage.assistant(big('a')));
        m.add(ChatMessage.user("last"));
        m.add(ChatMessage.assistant("final"));

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(m, 1_200, true, CID);

        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_SAFE_BOUNDARY, plan.outcome());
    }

    @Test
    void completeBatchPlusTranscriptDrop_staysEligible() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.user(big('u')));
        m.add(ChatMessage.assistant(big('a')));
        m.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("t1", "query_entities", "{}"), new ToolCall("t2", "fetch_cached_result", "{}"))));
        m.add(ChatMessage.toolResult("t1", big('y')));
        m.add(ChatMessage.toolResult("t2", big('z')));
        m.add(ChatMessage.user("last"));
        m.add(ChatMessage.assistant("final"));

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(m, 1_500, true, CID);

        assertTrue(plan.isCheckpointEligible());
        assertEquals(1, plan.droppedAssistantBatches());
        assertEquals(2, plan.droppedToolResultRows());
        assertEquals(2, plan.droppedTranscriptRows());
        // The evidence batch sits after the newest removed transcript row, so it is deterministic evidence
        // compaction rather than semantic content the checkpoint must narrate. The prefix stops at the cutoff.
        assertEquals(List.of("USER:" + big('u'), "ASSISTANT:" + big('a')), describe(plan.coveredPrefix()));
        assertEquals(List.of("USER:last", "ASSISTANT:final"), describe(plan.retainedTail()));
    }

    // --- the semantic cutoff is not the removed set -------------------------------

    /**
     * Old turn A (transcript only), newer turn B (transcript + tool batch), current turn C. The trim drains B's
     * evidence first, then removes only A's pair. B's transcript survives, so B's evidence must NOT appear in the
     * semantic prefix — otherwise the summary model sees evidence from a turn presented later as exact tail.
     */
    private static List<ChatMessage> turnAThenEvidenceTurnBThenCurrent() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.user(big('u')));                       // 1  turn A user
        m.add(ChatMessage.assistant(big('a')));                  // 2  turn A final
        m.add(ChatMessage.user("bu"));                           // 3  turn B user
        m.add(ChatMessage.assistantWithToolCalls(                // 4  turn B tool call
                List.of(new ToolCall("t1", "query_entities", "{}"))));
        m.add(ChatMessage.toolResult("t1", big('z')));           // 5  turn B evidence
        m.add(ChatMessage.assistant("bfinal"));                  // 6  turn B final
        m.add(ChatMessage.user("last"));                         // 7  current user
        m.add(ChatMessage.assistant("final"));                   // 8  current final
        return m;
    }

    @Test
    void evidenceFromNewerRetainedTurnIsNotSmuggledIntoCoveredPrefix() {
        List<ChatMessage> messages = turnAThenEvidenceTurnBThenCurrent();

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(messages, 1_500, true, CID);

        assertTrue(plan.isCheckpointEligible());
        // Precondition for this regression: B's evidence really was removed, and A's pair really was dropped.
        assertEquals(1, plan.droppedAssistantBatches());
        assertEquals(2, plan.droppedTranscriptRows());

        // Covered prefix is turn A only: a genuine prefix of complete turns.
        assertEquals(List.of("USER:" + big('u'), "ASSISTANT:" + big('a')), describe(plan.coveredPrefix()));

        // Turn B's surviving transcript is the tail; its removed evidence is in neither list.
        assertEquals(List.of("USER:bu", "ASSISTANT:bfinal", "USER:last", "ASSISTANT:final"),
                describe(plan.retainedTail()));
        for (ChatMessage m : plan.coveredPrefix()) {
            assertFalse(m.hasToolCalls(), "newer turn's evidence must not enter the semantic prefix");
            assertTrue(m.getRole() != ChatMessage.Role.TOOL, "newer turn's tool rows must not enter the prefix");
        }
    }

    @Test
    void coveredPrefixIncludesNonRemovedRowsBeforeCutoff() {
        // A Stage-2 rehydrated compact-evidence row is framed as a prose assistant and is never removed by any trim
        // pass. It sits before the cutoff, so it belongs to the span the checkpoint replaces.
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.assistant("[rehydrated compact evidence]"));
        m.add(ChatMessage.user(big('u')));
        m.add(ChatMessage.assistant(big('a')));
        m.add(ChatMessage.user("last"));
        m.add(ChatMessage.assistant("final"));

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(m, 1_200, true, CID);

        assertTrue(plan.isCheckpointEligible());
        assertEquals(List.of("ASSISTANT:[rehydrated compact evidence]", "USER:" + big('u'), "ASSISTANT:" + big('a')),
                describe(plan.coveredPrefix()));
        assertEquals(List.of("USER:last", "ASSISTANT:final"), describe(plan.retainedTail()));
    }

    // --- pairing completeness is global ------------------------------------------

    @Test
    void incompleteSurvivingBatch_failsClosed() {
        // The old pair is dropped, but the surviving/current turn declares two calls and has one result. The tool row
        // is not an orphan and the batch is untouched by the trim, so only a global scan catches it.
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.user(big('u')));
        m.add(ChatMessage.assistant(big('a')));
        m.add(ChatMessage.user("last"));
        m.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("t1", "query_entities", "{}"), new ToolCall("t2", "fetch_cached_result", "{}"))));
        m.add(ChatMessage.toolResult("t1", "ok"));
        m.add(ChatMessage.assistant("final"));

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(m, 1_200, true, CID);

        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_SAFE_BOUNDARY, plan.outcome());
    }

    @Test
    void survivingBatchWithExtraResultId_failsClosed() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(
                        currentTurnBatch(List.of(new ToolCall("t1", "query_entities", "{}")),
                                List.of(ChatMessage.toolResult("t1", "ok"), ChatMessage.toolResult("t9", "extra"))),
                        1_200, true, CID);
        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_SAFE_BOUNDARY, plan.outcome());
    }

    @Test
    void survivingBatchWithDuplicateResultId_failsClosed() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(
                        currentTurnBatch(
                                List.of(new ToolCall("t1", "query_entities", "{}"),
                                        new ToolCall("t2", "fetch_cached_result", "{}")),
                                List.of(ChatMessage.toolResult("t1", "ok"), ChatMessage.toolResult("t1", "again"))),
                        1_200, true, CID);
        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_SAFE_BOUNDARY, plan.outcome());
    }

    @Test
    void survivingBatchWithBlankResultId_failsClosed() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(
                        currentTurnBatch(List.of(new ToolCall("t1", "query_entities", "{}")),
                                List.of(ChatMessage.toolResult("  ", "ok"))),
                        1_200, true, CID);
        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_SAFE_BOUNDARY, plan.outcome());
    }

    @Test
    void survivingBatchWithBlankDeclaredId_failsClosed() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(
                        currentTurnBatch(List.of(new ToolCall("", "query_entities", "{}")),
                                List.of(ChatMessage.toolResult("t1", "ok"))),
                        1_200, true, CID);
        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_SAFE_BOUNDARY, plan.outcome());
    }

    @Test
    void completeSurvivingBatch_isTheControlAndStaysEligible() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(
                        currentTurnBatch(
                                List.of(new ToolCall("t1", "query_entities", "{}"),
                                        new ToolCall("t2", "fetch_cached_result", "{}")),
                                List.of(ChatMessage.toolResult("t1", "ok"), ChatMessage.toolResult("t2", "ok"))),
                        1_200, true, CID);
        assertTrue(plan.isCheckpointEligible(), "a complete surviving batch must not block a checkpoint");
    }

    /** One droppable historical pair, then a current turn whose batch shape is the variable under test. */
    private static List<ChatMessage> currentTurnBatch(List<ToolCall> declared, List<ChatMessage> results) {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.user(big('u')));
        m.add(ChatMessage.assistant(big('a')));
        m.add(ChatMessage.user("last"));
        m.add(ChatMessage.assistantWithToolCalls(declared));
        m.addAll(results);
        m.add(ChatMessage.assistant("final"));
        return m;
    }

    // --- a dry run that happened is evidence, eligible or not --------------------

    @Test
    void evidenceOnlyDrop_preservesTrimmerCountersAndSurvivors() {
        List<ChatMessage> forSelector = new ArrayList<>();
        forSelector.add(ChatMessage.system("sys"));
        forSelector.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        forSelector.add(ChatMessage.toolResult("t1", big('z')));
        forSelector.add(ChatMessage.user("last"));
        forSelector.add(ChatMessage.assistant("final"));
        List<ChatMessage> forTrimmer = new ArrayList<>(forSelector);

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(forSelector, 500, true, CID);
        ConversationsStorageBudgetTrimmer.TrimResult trim =
                ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget(forTrimmer, 500, true, null, CID);

        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_TRANSCRIPT_DROP, plan.outcome());
        assertEquals(trim.droppedAssistantBatches, plan.droppedAssistantBatches());
        assertEquals(trim.droppedToolResultRows, plan.droppedToolResultRows());
        assertEquals(trim.droppedTranscriptRows, plan.droppedTranscriptRows());
        assertEquals(trim.charsBefore, plan.charsBefore());
        assertEquals(trim.charsAfter, plan.charsAfter());
        assertTrue(plan.droppedAssistantBatches() > 0, "the dry run really did remove evidence");
        assertTrue(plan.charsAfter() < plan.charsBefore(), "charsAfter must reflect the dry run, not the input");
        assertEquals(describe(forTrimmer), describe(plan.materializedSurvivors()));
    }

    @Test
    void unsafeBoundary_preservesTrimmerCountersAndSurvivors() {
        List<ChatMessage> forSelector = new ArrayList<>();
        forSelector.add(ChatMessage.system("sys"));
        forSelector.add(ChatMessage.user(big('u')));
        forSelector.add(ChatMessage.assistant(big('a')));
        forSelector.add(ChatMessage.user("last"));
        forSelector.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("t1", "query_entities", "{}"), new ToolCall("t2", "fetch_cached_result", "{}"))));
        forSelector.add(ChatMessage.toolResult("t1", "ok"));
        forSelector.add(ChatMessage.assistant("final"));
        List<ChatMessage> forTrimmer = new ArrayList<>(forSelector);

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(forSelector, 1_200, true, CID);
        ConversationsStorageBudgetTrimmer.TrimResult trim =
                ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget(forTrimmer, 1_200, true, null, CID);

        assertEquals(ConversationCompactionBoundarySelector.Outcome.NO_SAFE_BOUNDARY, plan.outcome());
        assertEquals(trim.droppedTranscriptRows, plan.droppedTranscriptRows());
        assertEquals(trim.charsAfter, plan.charsAfter());
        assertTrue(plan.charsAfter() < plan.charsBefore());
        assertEquals(describe(forTrimmer), describe(plan.materializedSurvivors()));
    }

    @Test
    void preDryRunGates_reportZeroRemovalsAndTheUntouchedList() {
        List<ChatMessage> messages = twoHistoricalPairs();
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(messages, 3_000, false, CID);

        assertEquals(ConversationCompactionBoundarySelector.Outcome.COMPACTION_DISABLED, plan.outcome());
        assertEquals(0, plan.droppedAssistantBatches());
        assertEquals(0, plan.droppedTranscriptRows());
        assertEquals(plan.charsBefore(), plan.charsAfter());
        assertEquals(describe(messages), describe(plan.materializedSurvivors()));
    }

    // --- retained tail shape --------------------------------------------------------------------------

    @Test
    void retainedTailExcludesSystemRowsButCountersDoNot() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(twoHistoricalPairs(), 3_000, true, CID);

        for (ChatMessage m : plan.retainedTail()) {
            assertTrue(m.getRole() != ChatMessage.Role.SYSTEM, "retained tail must not carry stable prompt rows");
        }
        // charsAfter mirrors the trimmer, which counts the surviving system row.
        int tailChars = 0;
        for (ChatMessage m : plan.retainedTail()) {
            tailChars += MessageCharEstimator.rowChars(m);
        }
        assertTrue(plan.charsAfter() > tailChars, "counters mirror the trimmer, including the system row");
    }
}
