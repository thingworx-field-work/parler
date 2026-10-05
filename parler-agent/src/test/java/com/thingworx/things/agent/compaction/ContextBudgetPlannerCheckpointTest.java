package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * §10.1: the working checkpoint is fixed non-history overhead, is protected, and is <em>omitted</em> rather than
 * allowed to fail a request the planner could otherwise serve.
 */
class ContextBudgetPlannerCheckpointTest {

    private static final LlmUsageWireIds IDS =
            LlmUsageWireIds.forProviderThing("Prov", "Tpl", "openai-chat-completions-v4", "gpt-4o");

    @AfterEach
    void tearDown() {
        LlmReplayCompactionGate.clearAllTestHooks();
        AgentToolContext.clear();
    }

    private static ChatMessage checkpointRow(String goal) {
        return ConversationCheckpointCodec.toInjectedAssistant(new ConversationCheckpointSemantic(
                goal, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    private static Logger capturingLogger(List<String> out) {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] { Logger.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("is") && name.endsWith("Enabled")) {
                        return true;
                    }
                    if ((name.equals("info") || name.equals("warn") || name.equals("error") || name.equals("debug"))
                            && args != null && args.length > 0 && args[0] instanceof String) {
                        out.add(render((String) args[0], args));
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    private static String render(String format, Object[] args) {
        Object[] params = args.length == 2 && args[1] instanceof Object[]
                ? (Object[]) args[1]
                : java.util.Arrays.copyOfRange(args, 1, args.length);
        StringBuilder sb = new StringBuilder();
        int at = 0;
        int from = 0;
        int idx;
        while ((idx = format.indexOf("{}", from)) >= 0) {
            sb.append(format, from, idx);
            sb.append(at < params.length ? String.valueOf(params[at++]) : "{}");
            from = idx + 2;
        }
        sb.append(format.substring(from));
        return sb.toString();
    }

    private static String only(List<String> sink, String prefix) {
        String found = null;
        for (String l : sink) {
            if (l.startsWith(prefix)) {
                found = l;
            }
        }
        assertTrue(found != null, prefix + " not logged; saw " + sink);
        return found;
    }

    private static boolean hasCheckpoint(List<ChatMessage> rows) {
        for (ChatMessage m : rows) {
            if (ConversationCheckpointCodec.isInjectedCheckpoint(m)) {
                return true;
            }
        }
        return false;
    }

    // --- accounting -----------------------------------------------------------------------------------

    @Test
    void theCheckpointIsFixedOverheadRatherThanTranscript() {
        // The dangerous half of the coordinated change: marking the row protected while still counting it as
        // transcript makes historyWithinBudget unsatisfiable for a row nothing may drop.
        ChatMessage cp = checkpointRow("compare pump throughput");
        List<ChatMessage> messages = List.of(
                ChatMessage.system("stable prompt"),
                cp,
                ChatMessage.user("older question"),
                ChatMessage.assistant("older answer"),
                ChatMessage.user("current question"));

        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(
                messages, Collections.emptyList(), IDS, 100_000);

        assertEquals(cp.getContent().length(), m.checkpointChars);
        assertEquals("older question".length() + "older answer".length(), m.transcriptChars,
                "the checkpoint must not be counted twice");
        long expected = m.effectiveRequestCapChars - m.stableChars - m.toolSchemaChars - m.ephemeralChars
                - m.currentUserChars - m.activeBatchReserveChars - m.checkpointChars;
        assertEquals(expected, m.historyBudgetChars, "checkpointChars is subtracted from the history budget");
    }

    @Test
    void aConversationWithoutACheckpointIsAccountedExactlyAsBefore() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("stable prompt"),
                ChatMessage.user("older question"),
                ChatMessage.assistant("older answer"),
                ChatMessage.user("current question"));
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(
                messages, Collections.emptyList(), IDS, 100_000);
        assertEquals(0, m.checkpointChars);
    }

    // --- §10.1 the omission path ----------------------------------------------------------------------

    @Test
    void aCheckpointThatDoesNotFitIsOmittedAndTheRequestStillPlans() {
        // Fixed overhead alone (stable prompt + current user) leaves room, but adding the checkpoint does not.
        // Before this change the planner threw OVERHEAD_EXCEEDS_CAP and failed a serviceable user request.
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("s".repeat(400)),
                checkpointRow("g".repeat(400)),
                ChatMessage.user("older question"),
                ChatMessage.assistant("older answer"),
                ChatMessage.user("u".repeat(400))));
        List<String> sink = new ArrayList<>();

        ContextBudgetPlanner.PlannedOutbound planned = ContextBudgetPlanner.planForProviderRound(
                capturingLogger(sink), messages, Collections.emptyList(), IDS, 1_000, -1, -1);

        List<ChatMessage> out = planned.getOutboundMessages();
        assertFalse(hasCheckpoint(out), "the checkpoint is omitted from this provider request");
        assertEquals("u".repeat(400), out.get(out.size() - 1).getContent(), "the user's request still goes out");
        assertEquals(0, planned.getPlannedMetrics().checkpointChars,
                "the recomputed metrics describe the request that was actually planned");
        assertEquals(0, planned.getPlannedMetrics().droppedTranscript,
                "the checkpoint is not a transcript pair, so omitting it drops no transcript row");
        String skip = only(sink, "CONVERSATION_CHECKPOINT_SKIP");
        assertTrue(skip.contains("reason=CHECKPOINT_CANNOT_FIT"), skip);
        assertTrue(skip.contains("summaryDurationMs=0"), "no summary call began on the planner side: " + skip);
    }

    @Test
    void theSameConversationWithoutACheckpointPlansWithNoOmissionAtAll() {
        // The control: identical shape with an ordinary assistant row in place of the checkpoint. Overhead is
        // smaller here, so the request plans — which is what makes the previous test's failure attributable to the
        // checkpoint rather than to the fixture being over-sized in general.
        List<ChatMessage> withoutCheckpoint = new ArrayList<>(List.of(
                ChatMessage.system("s".repeat(400)),
                ChatMessage.user("older question"),
                ChatMessage.assistant("older answer"),
                ChatMessage.user("u".repeat(400))));
        List<String> sink = new ArrayList<>();
        ContextBudgetPlanner.PlannedOutbound planned = ContextBudgetPlanner.planForProviderRound(
                capturingLogger(sink), withoutCheckpoint, Collections.emptyList(), IDS, 1_000, -1, -1);
        assertEquals("u".repeat(400), planned.getOutboundMessages()
                .get(planned.getOutboundMessages().size() - 1).getContent());
        for (String l : sink) {
            assertFalse(l.startsWith("CONVERSATION_CHECKPOINT_"), l);
        }
    }

    @Test
    void ordinaryOverheadStillFailsWithTodaysReasonWhenNoCheckpointIsPresent() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("x".repeat(5_000)),
                ChatMessage.user("u"));
        ContextBudgetExceededException ex = assertThrows(ContextBudgetExceededException.class,
                () -> ContextBudgetPlanner.planForProviderRound(
                        null, messages, Collections.emptyList(), IDS, 800, -1, -1));
        assertEquals(ContextBudgetExceededException.Reason.OVERHEAD_EXCEEDS_CAP, ex.getReason());
    }

    @Test
    void ordinaryOverheadStillFailsEvenAfterTheCheckpointIsOmitted() {
        // Step 4 of §10.1: omission is a recovery for the checkpoint's own cost, never a way to mask a cap that
        // ordinary overhead exceeds on its own.
        List<ChatMessage> messages = List.of(
                ChatMessage.system("x".repeat(5_000)),
                checkpointRow("g".repeat(200)),
                ChatMessage.user("u"));
        List<String> sink = new ArrayList<>();
        ContextBudgetExceededException ex = assertThrows(ContextBudgetExceededException.class,
                () -> ContextBudgetPlanner.planForProviderRound(
                        capturingLogger(sink), messages, Collections.emptyList(), IDS, 800, -1, -1));
        assertEquals(ContextBudgetExceededException.Reason.OVERHEAD_EXCEEDS_CAP, ex.getReason());
        assertTrue(only(sink, "CONVERSATION_CHECKPOINT_SKIP").contains("reason=CHECKPOINT_CANNOT_FIT"),
                "the checkpoint was still tried and reported before the ordinary failure: " + sink);
        String fail = only(sink, "LLM_CONTEXT_PLAN_FAIL");
        assertTrue(fail.contains("reason=OVERHEAD_EXCEEDS_CAP"), fail);
        assertTrue(fail.contains(" checkpointChars=0"),
                "the fail line reports the recomputed metrics, not the pre-omission ones: " + fail);
    }

    // --- protection and index discipline ---------------------------------------------------------------

    @Test
    void aFittingCheckpointSurvivesTheDropPassesThatRemoveOlderTurns() {
        ChatMessage cp = checkpointRow("compare pump throughput");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("stable prompt"),
                cp,
                ChatMessage.user("older question " + "a".repeat(600)),
                ChatMessage.assistant("older answer " + "b".repeat(600)),
                ChatMessage.user("newer question " + "c".repeat(600)),
                ChatMessage.assistant("newer answer " + "d".repeat(600)),
                ChatMessage.user("current question")));

        ContextBudgetPlanner.PlannedOutbound planned = ContextBudgetPlanner.planForProviderRound(
                null, messages, Collections.emptyList(), IDS, 2_000, -1, -1);

        List<ChatMessage> out = planned.getOutboundMessages();
        assertTrue(hasCheckpoint(out), "the checkpoint is protected while older turns are dropped");
        assertTrue(out.size() < messages.size(), "fixture precondition: the drop passes actually ran");
        assertEquals("current question", out.get(out.size() - 1).getContent());
        assertEquals(cp.getContent().length(), planned.getPlannedMetrics().checkpointChars);
    }

    @Test
    void omissionAndTheDropPassesShareOneKeepMask() {
        // §10.1 step 3 ends with "continue into the existing fit-check and drop passes with that mask". Rebuilding
        // the mask afterwards — the obvious way to write this — would silently restore the omitted checkpoint.
        // Sized so stable + currentUser + checkpoint exceeds the cap (the omission fires) while stable +
        // currentUser alone leaves a budget the remaining transcript still exceeds (the drop passes then run).
        ChatMessage cp = checkpointRow("g".repeat(600));
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("s".repeat(300)),
                cp,
                ChatMessage.user("older question " + "a".repeat(300)),
                ChatMessage.assistant("older answer " + "b".repeat(300)),
                ChatMessage.user("newer question " + "c".repeat(300)),
                ChatMessage.assistant("newer answer " + "d".repeat(300)),
                ChatMessage.user("u".repeat(200))));

        ContextBudgetPlanner.PlannedOutbound planned = ContextBudgetPlanner.planForProviderRound(
                null, messages, Collections.emptyList(), IDS, 1_000, -1, -1);

        List<ChatMessage> out = planned.getOutboundMessages();
        assertFalse(hasCheckpoint(out), "the omitted checkpoint must not reappear once the drop passes run");
        assertTrue(out.size() < messages.size() - 1, "fixture precondition: drops ran on top of the omission");
        assertEquals("u".repeat(200), out.get(out.size() - 1).getContent());
        assertEquals(0, planned.getPlannedMetrics().checkpointChars);
        assertEquals(4, planned.getPlannedMetrics().droppedTranscript,
                "both older turns, and only them: the omitted checkpoint is not counted among them");
    }

    // --- §10.1 defensive newest-only normalization ------------------------------------------------------

    @Test
    void twoMarkersUnderAGenerousCapLeaveExactlyTheNewestAndNoSkip() {
        // Normalization guarantees one checkpoint, so this branch should be unreachable — but budgeting a stale
        // state alongside the current one would send both to the model and break §5 invariant 2.
        ChatMessage stale = checkpointRow("stale goal");
        ChatMessage newest = checkpointRow("current goal");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("stable prompt"),
                stale,
                newest,
                ChatMessage.user("older question"),
                ChatMessage.assistant("older answer"),
                ChatMessage.user("current question")));
        List<String> sink = new ArrayList<>();

        ContextBudgetPlanner.PlannedOutbound planned = ContextBudgetPlanner.planForProviderRound(
                capturingLogger(sink), messages, Collections.emptyList(), IDS, 100_000, -1, -1);

        List<ChatMessage> out = planned.getOutboundMessages();
        assertTrue(out.contains(newest), "the newest checkpoint is the candidate");
        assertFalse(out.contains(stale), "the older marker is cleared before budgeting");
        assertEquals(newest.getContent().length(), planned.getPlannedMetrics().checkpointChars,
                "only the candidate is charged to the request");
        assertEquals(0, planned.getPlannedMetrics().droppedTranscript,
                "clearing a stale marker is not a dropped transcript row");
        for (String l : sink) {
            assertFalse(l.startsWith("CONVERSATION_CHECKPOINT_"), "nothing was skipped: " + l);
        }
    }

    @Test
    void twoMarkersWhoseCombinedCostFailsStillKeepTheNewestWhenItAloneFits() {
        // The case that separates "clear the older ones first" from "omit them all": their combined cost makes the
        // budget negative, but the candidate alone fits. Omitting everything would discard valid continuity and
        // report CHECKPOINT_CANNOT_FIT for a checkpoint that fits perfectly well.
        ChatMessage stale = checkpointRow("s".repeat(600));
        ChatMessage newest = checkpointRow("n".repeat(200));
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("s".repeat(200)),
                stale,
                newest,
                ChatMessage.user("older question"),
                ChatMessage.assistant("older answer"),
                ChatMessage.user("u".repeat(200))));
        List<String> sink = new ArrayList<>();

        // Precondition: both together really do exceed the cap, so the fixture exercises the intended branch.
        assertTrue(ContextBudgetPlanner.Metrics.compute(messages, Collections.emptyList(), IDS, 1_000)
                .historyBudgetChars < 0, "fixture precondition: the combined cost is over budget");

        ContextBudgetPlanner.PlannedOutbound planned = ContextBudgetPlanner.planForProviderRound(
                capturingLogger(sink), messages, Collections.emptyList(), IDS, 1_000, -1, -1);

        List<ChatMessage> out = planned.getOutboundMessages();
        assertTrue(out.contains(newest), "the candidate fits once the stale marker is gone");
        assertFalse(out.contains(stale));
        assertEquals(newest.getContent().length(), planned.getPlannedMetrics().checkpointChars);
        for (String l : sink) {
            assertFalse(l.contains("CHECKPOINT_CANNOT_FIT"),
                    "the candidate fits, so this reason would be wrong: " + l);
        }
    }
}
