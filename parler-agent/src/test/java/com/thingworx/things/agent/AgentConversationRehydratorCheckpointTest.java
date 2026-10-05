package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.thingworx.things.agent.compaction.ConversationCheckpoint;
import com.thingworx.things.agent.compaction.ConversationCheckpointCodec;
import com.thingworx.things.agent.compaction.ConversationCheckpointRehydrate;
import com.thingworx.things.agent.compaction.ConversationCheckpointSemantic;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.CompactFetchStreamRehydrate;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * §9.2 step 6: both shipped helpers actively delete a leading assistant row, and the injected checkpoint is
 * placed at index 0 as exactly such a row. Either helper reverting to unconditional head removal would produce a
 * rehydrate that succeeds, logs nothing, and has quietly dropped the continuity payload — so there is one focused
 * regression per helper.
 */
class AgentConversationRehydratorCheckpointTest {

    private static final String CID = "conv-rehydrate";
    private static final String AGENT = "MyAgentThing";

    @AfterEach
    void clearTurnContext() {
        AgentToolContext.clear();
    }

    private static ChatMessage checkpoint(String goal) {
        return ConversationCheckpointCodec.toInjectedAssistant(new ConversationCheckpointSemantic(
                goal, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    /** Exactly what mapAndFilterRows restores an accepted compact tool row as: assistant prose, Stage-2 framed. */
    private static ChatMessage framedEvidence(String body) {
        return ChatMessage.assistant(
                CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX + body);
    }

    private static ConversationRehydrateSettings settings(int maxChars) {
        return ConversationRehydrateSettings.custom(true, 300, maxChars, false);
    }

    private static List<String> describe(List<ChatMessage> rows) {
        List<String> out = new ArrayList<>();
        for (ChatMessage m : rows) {
            out.add(ConversationCheckpointCodec.isInjectedCheckpoint(m)
                    ? "CHECKPOINT" : m.getRole() + ":" + m.getContent());
        }
        return out;
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
                        Object[] params = args.length == 2 && args[1] instanceof Object[]
                                ? (Object[]) args[1]
                                : java.util.Arrays.copyOfRange(args, 1, args.length);
                        StringBuilder sb = new StringBuilder();
                        String format = (String) args[0];
                        int at = 0;
                        int from = 0;
                        int idx;
                        while ((idx = format.indexOf("{}", from)) >= 0) {
                            sb.append(format, from, idx);
                            sb.append(at < params.length ? String.valueOf(params[at++]) : "{}");
                            from = idx + 2;
                        }
                        sb.append(format.substring(from));
                        out.add(sb.toString());
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


    // --- §9.2 step 3: the restored prefix, then the rows appended after the checkpoint --------------------

    private static ValueCollection streamRow(String role, String content, String toolCalls,
            String assistantMessageId) {
        ValueCollection vc = new ValueCollection();
        vc.put("role", new StringPrimitive(role));
        vc.put("agentThing", new StringPrimitive(AGENT));
        vc.put("content", new StringPrimitive(content));
        vc.put("toolCalls", new StringPrimitive(toolCalls));
        vc.put("assistantMessageId", new StringPrimitive(assistantMessageId));
        return vc;
    }

    @Test
    void aRestoredCheckpointIsFollowedByTheHistoryAppendedAfterItsRow() {
        ConversationCheckpoint cp = new ConversationCheckpoint(
                new ConversationCheckpoint.Source(CID, AGENT, "amid-1"),
                new ConversationCheckpointSemantic("compare pump throughput", List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of()),
                List.of(),
                List.of(new ConversationCheckpoint.RetainedRow(ConversationCheckpoint.RetainedRow.ROLE_USER,
                                "retained question", ConversationCheckpoint.RetainedRow.PROVENANCE_TRANSCRIPT),
                        new ConversationCheckpoint.RetainedRow(
                                ConversationCheckpoint.RetainedRow.ROLE_ASSISTANT, "retained answer",
                                ConversationCheckpoint.RetainedRow.PROVENANCE_FINAL_ASSISTANT)),
                new ConversationCheckpoint.Generated("2026-08-24T00:00:00Z", "Prov", "gpt-x"));

        List<ValueCollection> rows = List.of(
                streamRow("user", "summarized away", "", ""),
                streamRow("assistant", "also summarized away", "", "amid-0"),
                streamRow("user", "retained question", "", ""),
                streamRow("assistant", "retained answer", "", "amid-1"),
                streamRow("context_checkpoint", ConversationCheckpointCodec.serialize(cp), "", ""),
                streamRow("user", "question after the checkpoint", "", ""),
                streamRow("assistant", "answer after the checkpoint", "", "amid-2"));

        List<ChatMessage> restored = AgentConversationRehydrator.compose(
                ConversationCheckpointRehydrate.restore(rows, CID, AGENT, null, null),
                rows, CID, AGENT, settings(100_000), null);

        assertEquals(List.of("CHECKPOINT", "USER:retained question", "ASSISTANT:retained answer",
                        "USER:question after the checkpoint", "ASSISTANT:answer after the checkpoint"),
                describe(restored),
                "the envelope's exact tail, then the rows appended after its Stream row — and the two turns the "
                        + "checkpoint summarized are gone");
    }

    @Test
    void aRejectedCheckpointFallsBackToTheWholeTranscript() {
        // §9.2 step 7: a rejected or absent checkpoint costs navigation, never history. Every row still maps.
        List<ValueCollection> rows = List.of(
                streamRow("user", "first question", "", ""),
                streamRow("assistant", "first answer", "", "amid-0"),
                streamRow("context_checkpoint", "{not json", "", ""),
                streamRow("user", "second question", "", ""),
                streamRow("assistant", "second answer", "", "amid-1"));

        List<ChatMessage> restored = AgentConversationRehydrator.compose(
                ConversationCheckpointRehydrate.restore(rows, CID, AGENT, null, null),
                rows, CID, AGENT, settings(100_000), null);

        assertEquals(List.of("USER:first question", "ASSISTANT:first answer",
                        "USER:second question", "ASSISTANT:second answer"),
                describe(restored));
    }

    // --- applyCoherentTranscriptBoundary ----------------------------------------------------------------

    @Test
    void theBoundaryHelperKeepsTheCheckpointAndResumesStrippingAfterIt() {
        // Its entire purpose is "do not start with an assistant row", and the checkpoint is one. Without the
        // exemption the first thing a restored conversation loses is the reason it was compacted.
        List<ChatMessage> messages = new ArrayList<>(List.of(
                checkpoint("compare pump throughput"),
                ChatMessage.assistant("orphaned answer"),
                ChatMessage.user("first question"),
                ChatMessage.assistant("first answer")));

        AgentConversationRehydrator.applyCoherentTranscriptBoundary(messages, settings(100_000), null);

        assertEquals(List.of("CHECKPOINT", "USER:first question", "ASSISTANT:first answer"), describe(messages),
                "the checkpoint survives and the leading-assistant strip resumes at the row after it");
    }

    @Test
    void withoutACheckpointTheBoundaryHelperBehavesExactlyAsBefore() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.assistant("orphaned answer"),
                ChatMessage.assistant("another orphan"),
                ChatMessage.user("first question"),
                ChatMessage.assistant("first answer")));

        AgentConversationRehydrator.applyCoherentTranscriptBoundary(messages, settings(100_000), null);

        assertEquals(List.of("USER:first question", "ASSISTANT:first answer"), describe(messages));
    }

    // --- applyCharBudget --------------------------------------------------------------------------------

    @Test
    void theCharBudgetEvictsOldTranscriptRatherThanTheCheckpoint() {
        // The checkpoint is at the head, so it is otherwise the first row this budget discards.
        ChatMessage cp = checkpoint("compare pump throughput");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                cp,
                ChatMessage.user("old question " + "a".repeat(400)),
                ChatMessage.assistant("old answer " + "b".repeat(400)),
                ChatMessage.user("recent question"),
                ChatMessage.assistant("recent answer")));

        AgentConversationRehydrator.applyCharBudget(messages, cp.getContent().length() + 200, settings(0), null, CID);

        assertTrue(ConversationCheckpointCodec.isInjectedCheckpoint(messages.get(0)),
                "the checkpoint is protected from head eviction: " + describe(messages));
        assertEquals(List.of("CHECKPOINT", "USER:recent question", "ASSISTANT:recent answer"), describe(messages),
                "the oldest transcript pair went instead");
    }

    @Test
    void aBudgetTooSmallForAnyTranscriptDropsTheCheckpointExplicitly() {
        // §9.2: a checkpoint-only history is worse than no checkpoint — the model would be told where the work
        // stands with nothing to continue from. Dropping it also frees its chars for the transcript.
        ChatMessage cp = checkpoint("g".repeat(600));
        List<ChatMessage> messages = new ArrayList<>(List.of(
                cp,
                ChatMessage.user("only question"),
                ChatMessage.assistant("only answer")));
        List<String> sink = new ArrayList<>();

        AgentConversationRehydrator.applyCharBudget(messages, 200, settings(0), capturingLogger(sink), CID);

        assertFalse(describe(messages).contains("CHECKPOINT"),
                "a history of nothing but a checkpoint is not returned: " + describe(messages));
        assertEquals(List.of("USER:only question", "ASSISTANT:only answer"), describe(messages),
                "and the freed space keeps the transcript the budget could not hold beside it");
        String skip = null;
        for (String l : sink) {
            if (l.startsWith("CONVERSATION_CHECKPOINT_SKIP")) {
                skip = l;
            }
        }
        assertTrue(skip != null, "the drop is reported, not silent; saw " + sink);
        assertTrue(skip.contains("reason=CHECKPOINT_CANNOT_FIT"), skip);
        assertTrue(skip.contains("conversationId=" + CID), skip);
    }


    @Test
    void framedCompactEvidenceDoesNotStandInForAnAnswerThatWasNeverPersisted() {
        // Reachable restart: an older checkpoint and exact pair, then a new user row and accepted compact evidence,
        // then the JVM fails before the turn's final assistant is persisted. If the budget evicts the older pair but
        // holds checkpoint + user + evidence, counting any assistant would keep the checkpoint while no completed
        // pair survives — the exact condition §9.2 asks this helper to detect.
        ChatMessage cp = checkpoint("compare pump throughput");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                cp,
                ChatMessage.user("old question " + "a".repeat(400)),
                ChatMessage.assistant("old answer " + "b".repeat(400)),
                ChatMessage.user("new question"),
                framedEvidence("{\"rows\":[]}")));
        List<String> sink = new ArrayList<>();

        AgentConversationRehydrator.applyCharBudget(messages, cp.getContent().length() + 200, settings(0),
                capturingLogger(sink), CID);

        assertFalse(describe(messages).contains("CHECKPOINT"),
                "server-framed evidence is not a final assistant: " + describe(messages));
        String skip = null;
        for (String l : sink) {
            if (l.startsWith("CONVERSATION_CHECKPOINT_SKIP")) {
                skip = l;
            }
        }
        assertTrue(skip != null && skip.contains("reason=CHECKPOINT_CANNOT_FIT"), "saw " + sink);
    }

    @Test
    void aRealAnswerAfterTheEvidenceStillCompletesThePair() {
        // The positive control for the same predicate: compact evidence legitimately sits between a user and the
        // answer it supported, so the pair must stay non-adjacent rather than requiring the answer to follow the
        // user directly.
        ChatMessage cp = checkpoint("compare pump throughput");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                cp,
                ChatMessage.user("old question " + "a".repeat(400)),
                ChatMessage.assistant("old answer " + "b".repeat(400)),
                ChatMessage.user("new question"),
                framedEvidence("{\"rows\":[]}"),
                ChatMessage.assistant("new answer")));
        List<String> sink = new ArrayList<>();

        AgentConversationRehydrator.applyCharBudget(messages, cp.getContent().length() + 200, settings(0),
                capturingLogger(sink), CID);

        assertTrue(ConversationCheckpointCodec.isInjectedCheckpoint(messages.get(0)),
                "a completed pair survives, so the checkpoint is protected: " + describe(messages));
        assertEquals(List.of("CHECKPOINT", "USER:new question",
                        "ASSISTANT:" + CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX
                                + "{\"rows\":[]}",
                        "ASSISTANT:new answer"),
                describe(messages));
        for (String l : sink) {
            assertFalse(l.startsWith("CONVERSATION_CHECKPOINT_SKIP"), l);
        }
    }

    @Test
    void withoutACheckpointTheCharBudgetBehavesExactlyAsBefore() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.user("old question " + "a".repeat(400)),
                ChatMessage.assistant("old answer " + "b".repeat(400)),
                ChatMessage.user("recent question"),
                ChatMessage.assistant("recent answer")));

        AgentConversationRehydrator.applyCharBudget(messages, 200, settings(0), null, CID);

        assertEquals(List.of("USER:recent question", "ASSISTANT:recent answer"), describe(messages));
    }
}
