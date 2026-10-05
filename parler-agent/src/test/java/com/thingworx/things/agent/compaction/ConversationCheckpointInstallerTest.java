package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;

/**
 * §8.1 steps 6–7: installation must publish the working set the generator validated, not "the live list minus the
 * covered prefix". The two differ whenever the summary covered a strict sub-prefix, or the tail projection/caps
 * changed which rows survive.
 */
class ConversationCheckpointInstallerTest {

    private static final String CID = "conv-installer";
    private static final int CAP = 5_000;

    private static ConversationCheckpointSemantic semantic() {
        return new ConversationCheckpointSemantic("compare pump throughput", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of("chart the candidates"), List.of());
    }

    private static ConversationCheckpoint checkpointWith(List<ConversationCheckpoint.RetainedRow> tail) {
        return new ConversationCheckpoint(
                new ConversationCheckpoint.Source(CID, "AgentThing", "amid-1"),
                semantic(), List.of(), tail,
                new ConversationCheckpoint.Generated("t", "p", "m"));
    }

    private static ConversationCheckpoint.RetainedRow user(String c) {
        return new ConversationCheckpoint.RetainedRow(ConversationCheckpoint.RetainedRow.ROLE_USER, c,
                ConversationCheckpoint.RetainedRow.PROVENANCE_TRANSCRIPT);
    }

    private static ConversationCheckpoint.RetainedRow assistant(String c) {
        return new ConversationCheckpoint.RetainedRow(ConversationCheckpoint.RetainedRow.ROLE_ASSISTANT, c,
                ConversationCheckpoint.RetainedRow.PROVENANCE_TRANSCRIPT);
    }

    /** Two removable turns plus a current turn, sized so the dry run removes both older pairs. */
    private static List<ChatMessage> conversation() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("stable prompt"));
        m.add(ChatMessage.user("turn one question " + "a".repeat(3_000)));
        m.add(ChatMessage.assistant("turn one answer " + "b".repeat(3_000)));
        m.add(ChatMessage.user("turn two question " + "c".repeat(3_000)));
        m.add(ChatMessage.assistant("turn two answer " + "d".repeat(3_000)));
        m.add(ChatMessage.user("current question"));
        m.add(ChatMessage.assistant("current answer"));
        return m;
    }

    private static ConversationCompactionBoundarySelector.BoundaryPlan planFor(List<ChatMessage> messages) {
        return ConversationCompactionBoundarySelector.plan(messages, CAP, true, CID);
    }

    private static List<String> describe(List<ChatMessage> rows) {
        List<String> out = new ArrayList<>();
        for (ChatMessage m : rows) {
            out.add(m.getRole() + ":" + m.getContent());
        }
        return out;
    }

    @Test
    void aPartiallySummarizedPrefixKeepsTheUnsummarizedTurnBecauseTheTailCarriesIt() {
        // The generator may summarize a strict oldest sub-prefix and carry the rest at the front of its tail.
        // Removing the whole covered prefix instead of materializing that tail would delete a turn nobody summarized.
        List<ChatMessage> messages = conversation();
        ConversationCompactionBoundarySelector.BoundaryPlan plan = planFor(messages);
        assertTrue(plan.isCheckpointEligible(), "fixture precondition: a boundary exists");
        assertEquals(4, plan.coveredPrefix().size(), "fixture precondition: two whole turns are covered");

        // Only turn one was summarized; turn two leads the retained tail, exactly as generate() assembles it.
        List<ConversationCheckpoint.RetainedRow> tail = new ArrayList<>();
        tail.add(user(plan.coveredPrefix().get(2).getContent()));
        tail.add(assistant(plan.coveredPrefix().get(3).getContent()));
        for (ChatMessage m : plan.retainedTail()) {
            tail.add(m.getRole() == ChatMessage.Role.USER ? user(m.getContent()) : assistant(m.getContent()));
        }

        assertTrue(ConversationCheckpointInstaller.install(messages, plan, checkpointWith(tail)));

        List<String> expected = new ArrayList<>();
        expected.add("SYSTEM:stable prompt");
        expected.add(ChatMessage.Role.ASSISTANT + ":"
                + ConversationCheckpointCodec.toInjectedAssistant(semantic()).getContent());
        for (ConversationCheckpoint.RetainedRow r : tail) {
            expected.add((ConversationCheckpoint.RetainedRow.ROLE_USER.equals(r.role())
                    ? ChatMessage.Role.USER : ChatMessage.Role.ASSISTANT) + ":" + r.content());
        }
        assertEquals(expected, describe(messages), "the published list is the validated working set, row for row");
    }

    @Test
    void theTailTheCheckpointCarriesReplacesTheLiveTailEvenWhenTheyDiffer() {
        // The checkpoint tail has passed role/provenance projection and the §6.1 caps and excludes evidence the dry
        // run removed; the live list has not. Leaving the live tail in place publishes a different set from the one
        // materializedWorkingSetShrinks accepted.
        List<ChatMessage> messages = conversation();
        ConversationCompactionBoundarySelector.BoundaryPlan plan = planFor(messages);
        assertTrue(plan.isCheckpointEligible());
        assertEquals(2, plan.retainedTail().size(), "fixture precondition: the live tail has two rows");

        // A tail the caps shortened to a single row.
        List<ConversationCheckpoint.RetainedRow> tail = List.of(assistant("current answer"));
        assertTrue(ConversationCheckpointInstaller.install(messages, plan, checkpointWith(tail)));

        assertEquals(3, messages.size(), "system row, checkpoint, one retained row");
        assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole());
        assertTrue(ConversationCheckpointCodec.isInjectedCheckpoint(messages.get(1)));
        assertEquals("current answer", messages.get(2).getContent());
    }

    @Test
    void aPlanComputedAgainstADifferentListIsRefusedRatherThanMisApplied() {
        List<ChatMessage> messages = conversation();
        ConversationCompactionBoundarySelector.BoundaryPlan plan = planFor(messages);
        assertTrue(plan.isCheckpointEligible());

        // Value-equal rows, different objects: a positional/reference match must refuse them.
        List<ChatMessage> other = conversation();
        assertFalse(ConversationCheckpointInstaller.install(other, plan, checkpointWith(List.of())));
        assertEquals(describe(conversation()), describe(other), "a refusal leaves the list untouched");
    }

    @Test
    void aTailCarryingAnInjectedCheckpointIsRefused() {
        // §5 invariant 2: two working checkpoints would be counted twice by the planner and narrated twice.
        List<ChatMessage> messages = conversation();
        ConversationCompactionBoundarySelector.BoundaryPlan plan = planFor(messages);
        assertTrue(plan.isCheckpointEligible());

        ChatMessage stale = ConversationCheckpointCodec.toInjectedAssistant(semantic());
        List<ConversationCheckpoint.RetainedRow> tail = List.of(assistant(stale.getContent()));
        assertFalse(ConversationCheckpointInstaller.install(messages, plan, checkpointWith(tail)));
        assertEquals(describe(conversation()), describe(messages), "a refusal leaves the list untouched");
    }

    @Test
    void anIneligiblePlanOrMissingCheckpointInstallsNothing() {
        List<ChatMessage> messages = conversation();
        ConversationCompactionBoundarySelector.BoundaryPlan eligible = planFor(messages);
        assertFalse(ConversationCheckpointInstaller.install(messages, eligible, null));

        List<ChatMessage> small = new ArrayList<>(List.of(ChatMessage.user("hi"), ChatMessage.assistant("hello")));
        ConversationCompactionBoundarySelector.BoundaryPlan ineligible =
                ConversationCompactionBoundarySelector.plan(small, 1_000_000, true, CID);
        assertFalse(ineligible.isCheckpointEligible(), "fixture precondition");
        assertFalse(ConversationCheckpointInstaller.install(small, ineligible, checkpointWith(List.of())));
        assertEquals(2, small.size());
    }
}
