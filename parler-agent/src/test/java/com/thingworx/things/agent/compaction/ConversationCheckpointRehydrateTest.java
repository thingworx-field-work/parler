package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.thingworx.things.agent.AgentMessageStreamAppender;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * §9.2 steps 1–5 and the §6.1.1 backward walk: which row is a candidate, what makes it valid, and what a valid
 * one restores.
 */
class ConversationCheckpointRehydrateTest {

    private static final String CID = "conv-restore";
    private static final String AGENT = "MyAgentThing";
    private static final String OTHER_AGENT = "OtherAgentThing";
    private static final String AMID = "amid-1";

    @AfterEach
    void clearTurnContext() {
        AgentToolContext.clear();
    }

    private static ValueCollection row(String role, String agentThing, String content, String toolCalls,
            String assistantMessageId) {
        ValueCollection vc = new ValueCollection();
        vc.put("role", new StringPrimitive(role));
        vc.put("agentThing", new StringPrimitive(agentThing));
        vc.put("content", new StringPrimitive(content));
        vc.put("toolCalls", new StringPrimitive(toolCalls));
        vc.put("assistantMessageId", new StringPrimitive(assistantMessageId));
        return vc;
    }

    private static ValueCollection user(String content) {
        return row("user", AGENT, content, "", "");
    }

    private static ValueCollection finalAssistant(String content, String assistantMessageId) {
        return row("assistant", AGENT, content, "", assistantMessageId);
    }

    private static ValueCollection uiFeedback(String anchoredOn) {
        return row("ui_feedback", AGENT, "{\"rating\":\"up\"}", "", anchoredOn);
    }

    private static ConversationCheckpointSemantic semantic(String goal) {
        return new ConversationCheckpointSemantic(goal, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("chart the candidates"), List.of());
    }

    private static ConversationCheckpoint checkpoint(String cid, String agent, String watermark, String goal,
            List<ConversationCheckpoint.RetainedRow> tail, List<ConversationCheckpoint.EvidenceRef> refs) {
        return new ConversationCheckpoint(
                new ConversationCheckpoint.Source(cid, agent, watermark),
                semantic(goal), refs, tail,
                new ConversationCheckpoint.Generated("2026-08-24T00:00:00Z", "Prov", "gpt-x"));
    }

    private static ValueCollection checkpointRow(ConversationCheckpoint cp, String agent) {
        return row(AgentMessageStreamAppender.ROLE_CONTEXT_CHECKPOINT, agent,
                ConversationCheckpointCodec.serialize(cp), "", "");
    }

    private static List<ConversationCheckpoint.RetainedRow> tail() {
        return List.of(
                new ConversationCheckpoint.RetainedRow(ConversationCheckpoint.RetainedRow.ROLE_USER,
                        "retained question", ConversationCheckpoint.RetainedRow.PROVENANCE_TRANSCRIPT),
                new ConversationCheckpoint.RetainedRow(ConversationCheckpoint.RetainedRow.ROLE_ASSISTANT,
                        "retained answer", ConversationCheckpoint.RetainedRow.PROVENANCE_FINAL_ASSISTANT));
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
                        String format = (String) args[0];
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

    private static String skipReason(List<String> sink) {
        for (String l : sink) {
            if (l.startsWith("CONVERSATION_CHECKPOINT_SKIP")) {
                int at = l.indexOf("reason=");
                return l.substring(at + "reason=".length(), l.indexOf(' ', at));
            }
        }
        return null;
    }

    private static Optional<ConversationCheckpointRehydrate.Restored> restore(List<ValueCollection> rows,
            List<String> sink) {
        return ConversationCheckpointRehydrate.restore(rows, CID, AGENT, null, capturingLogger(sink));
    }

    // --- the happy path -------------------------------------------------------------------------------

    @Test
    void aValidCheckpointRestoresItsProseThenItsExactTail() {
        ConversationCheckpoint cp = checkpoint(CID, AGENT, AMID, "compare pump throughput", tail(), List.of());
        List<ValueCollection> rows = List.of(
                user("older question"),
                finalAssistant("older answer", "amid-0"),
                user("retained question"),
                finalAssistant("retained answer", AMID),
                checkpointRow(cp, AGENT));
        List<String> sink = new ArrayList<>();

        Optional<ConversationCheckpointRehydrate.Restored> restored = restore(rows, sink);

        assertTrue(restored.isPresent(), "saw " + sink);
        assertEquals(List.of("CHECKPOINT", "USER:retained question", "ASSISTANT:retained answer"),
                describe(restored.get().head()),
                "the semantic prose leads, followed by the exact tail the envelope froze");
        assertEquals(4, restored.get().checkpointRowIndex(), "rows after this index are the new history");
        assertEquals(null, skipReason(sink));
    }

    @Test
    void anyNumberOfUiFeedbackRowsMayInterleaveBeforeTheCheckpoint() {
        // Supported product behaviour: wireDone publishes the assistant id before normalization runs, and the
        // summary Provider call happens before the checkpoint row is appended, so the widget can append feedback
        // — including feedback anchored on a different assistant — while the summary is in flight.
        ConversationCheckpoint cp = checkpoint(CID, AGENT, AMID, "compare pump throughput", tail(), List.of());
        List<ValueCollection> rows = List.of(
                user("retained question"),
                finalAssistant("retained answer", AMID),
                uiFeedback(AMID),
                uiFeedback("amid-unrelated"),
                uiFeedback(AMID),
                checkpointRow(cp, AGENT));
        List<String> sink = new ArrayList<>();

        assertTrue(restore(rows, sink).isPresent(), "saw " + sink);
    }

    // --- §6.1.1 selection filters ---------------------------------------------------------------------

    @Test
    void anotherAgentsCheckpointIsSkippedAndTheScanContinues() {
        // Selection filters skip; only validation stops. A newer row belonging to another AgentThing must not
        // shadow this agent's own checkpoint.
        ConversationCheckpoint mine = checkpoint(CID, AGENT, AMID, "my goal", tail(), List.of());
        ConversationCheckpoint theirs = checkpoint(CID, OTHER_AGENT, "amid-other", "their goal", tail(), List.of());
        List<ValueCollection> rows = List.of(
                user("retained question"),
                finalAssistant("retained answer", AMID),
                checkpointRow(mine, AGENT),
                checkpointRow(theirs, OTHER_AGENT));
        List<String> sink = new ArrayList<>();

        Optional<ConversationCheckpointRehydrate.Restored> restored = restore(rows, sink);

        assertTrue(restored.isPresent(), "saw " + sink);
        assertTrue(restored.get().head().get(0).getContent().contains("my goal"));
        assertEquals(2, restored.get().checkpointRowIndex());
    }

    @Test
    void aConversationWithNoCheckpointRowRestoresNothingAndReportsNothing() {
        List<String> sink = new ArrayList<>();
        assertTrue(restore(List.of(user("q"), finalAssistant("a", AMID)), sink).isEmpty());
        assertEquals(null, skipReason(sink), "an ordinary conversation is not a skipped checkpoint");
    }

    // --- validation is fail-closed --------------------------------------------------------------------

    @Test
    void anInvalidNewestCandidateIsNotReplacedByAnOlderValidOne() {
        // "Latest valid checkpoint wins" is filtering, not retrying. An older semantic was superseded because the
        // task moved on; resurrecting it would reinstate state the system already decided was stale.
        ConversationCheckpoint older = checkpoint(CID, AGENT, AMID, "older goal", tail(), List.of());
        List<ValueCollection> rows = List.of(
                user("retained question"),
                finalAssistant("retained answer", AMID),
                checkpointRow(older, AGENT),
                row(AgentMessageStreamAppender.ROLE_CONTEXT_CHECKPOINT, AGENT, "{not json", "", ""));
        List<String> sink = new ArrayList<>();

        assertTrue(restore(rows, sink).isEmpty());
        assertEquals("WATERMARK_UNVERIFIED", skipReason(sink));
    }

    @Test
    void aCheckpointNamingAnotherConversationIsRefused() {
        ConversationCheckpoint foreign = checkpoint("some-other-conversation", AGENT, AMID, "goal", tail(),
                List.of());
        List<ValueCollection> rows = List.of(
                user("retained question"),
                finalAssistant("retained answer", AMID),
                checkpointRow(foreign, AGENT));
        List<String> sink = new ArrayList<>();

        assertTrue(restore(rows, sink).isEmpty());
        assertEquals("WATERMARK_UNVERIFIED", skipReason(sink));
    }


    @Test
    void everyValidationFailureOfTheSelectedCandidateReportsOneRehydrateReason() {
        // §13 states it directly, and §12's duration semantics force it: INVALID_JSON denotes a rejection after
        // the summary Provider call began and must carry that latency. Rehydrate makes no call, so reusing it
        // would write a permanent zero into the field that exists to measure it.
        ConversationCheckpoint wrongAgent = checkpoint(CID, OTHER_AGENT, AMID, "goal", tail(), List.of());
        String oversize = "{\"$format\":\"parler.conversation_checkpoint.v1\",\"pad\":\""
                + "x".repeat(ConversationCheckpointCodec.MAX_ENVELOPE_CHARS) + "\"}";

        List<String> cases = new ArrayList<>();
        for (String content : List.of("{not json", "{}", oversize,
                ConversationCheckpointCodec.serialize(wrongAgent))) {
            List<ValueCollection> rows = List.of(
                    user("retained question"),
                    finalAssistant("retained answer", AMID),
                    row(AgentMessageStreamAppender.ROLE_CONTEXT_CHECKPOINT, AGENT, content, "", ""));
            List<String> sink = new ArrayList<>();
            assertTrue(restore(rows, sink).isEmpty(), content.substring(0, Math.min(40, content.length())));
            assertEquals("WATERMARK_UNVERIFIED", skipReason(sink),
                    content.substring(0, Math.min(40, content.length())));
            for (String l : sink) {
                if (l.startsWith("CONVERSATION_CHECKPOINT_SKIP")) {
                    assertTrue(l.contains("summaryDurationMs=0"), l);
                }
            }
            cases.add(content);
        }
        assertEquals(4, cases.size(), "parse, identity, oversize, and wrong-agent envelope all pinned");
    }

    // --- §6.1.1 the backward walk ---------------------------------------------------------------------

    @Test
    void everyNonInertRowClassAheadOfTheCheckpointFailsTheWalk() {
        ConversationCheckpoint cp = checkpoint(CID, AGENT, AMID, "goal", tail(), List.of());
        List<ValueCollection> preceding = List.of(
                user("a later user row"),
                row("tool", AGENT, "{}", "", ""),
                row("assistant", AGENT, "", "[{\"id\":\"tc-1\"}]", AMID),
                finalAssistant("a different turn's answer", "amid-different"),
                row("assistant", OTHER_AGENT, "another agent's answer", "", AMID),
                row("future_internal_role", AGENT, "", "", ""));

        for (ValueCollection bad : preceding) {
            List<ValueCollection> rows = List.of(
                    user("retained question"),
                    finalAssistant("retained answer", AMID),
                    bad,
                    checkpointRow(cp, AGENT));
            List<String> sink = new ArrayList<>();
            assertTrue(restore(rows, sink).isEmpty(),
                    "row must not authorize the watermark: " + bad.getValue("role"));
            assertEquals("WATERMARK_UNVERIFIED", skipReason(sink), String.valueOf(bad.getValue("role")));
        }
    }

    @Test
    void aWatermarkOutsideTheBoundedWindowFallsBackRatherThanWidening() {
        // Both §6.1.1 window cases land here: the checkpoint is the oldest row of the window, or enough inert rows
        // pushed the watermark past the boundary. Rehydrate must not issue a second or wider query.
        ConversationCheckpoint cp = checkpoint(CID, AGENT, AMID, "goal", tail(), List.of());

        List<String> atHead = new ArrayList<>();
        assertTrue(restore(List.of(checkpointRow(cp, AGENT)), atHead).isEmpty());
        assertEquals("WATERMARK_UNVERIFIED", skipReason(atHead));

        List<String> pushedOut = new ArrayList<>();
        assertTrue(restore(List.of(uiFeedback(AMID), uiFeedback(AMID), checkpointRow(cp, AGENT)),
                pushedOut).isEmpty());
        assertEquals("WATERMARK_UNVERIFIED", skipReason(pushedOut));
    }

    // --- §9.2 step 4 liveness -------------------------------------------------------------------------

    @Test
    void everyCacheIdLivenessIsReDecidedAndNeverInheritedFromTheEnvelope() {
        // §5 invariant 11: a persisted `live` must not survive a restart on the strength of the file still
        // existing. With no current-JVM resolver — which is what a restart means, the cache is empty — every ref
        // degrades to historical-recompute.
        ConversationCheckpoint.EvidenceRef persistedAsLive = new ConversationCheckpoint.EvidenceRef(
                "tc-1", "query_entities", ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY,
                "entity_query", "11112222-3333-4444-5555-666677778888", "complete", false,
                ConversationCheckpoint.EvidenceRef.LIVENESS_LIVE, "query_entities");
        ConversationCheckpoint cp = checkpoint(CID, AGENT, AMID, "goal", tail(), List.of(persistedAsLive));
        List<ValueCollection> rows = List.of(
                user("retained question"),
                finalAssistant("retained answer", AMID),
                checkpointRow(cp, AGENT));
        List<String> sink = new ArrayList<>();

        Optional<ConversationCheckpointRehydrate.Restored> restored = restore(rows, sink);
        assertTrue(restored.isPresent(), "saw " + sink);
        List<ConversationCheckpoint.EvidenceRef> refs = restored.get().checkpoint().evidenceRefs();
        assertEquals(1, refs.size());
        assertEquals(ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE, refs.get(0).liveness());
        assertEquals("tc-1", refs.get(0).toolCallId(), "the ref itself is carried, only its liveness re-decided");
    }

    @Test
    void aResolverThatFindsTheCacheMarksTheRefLive() {
        ConversationCheckpoint.EvidenceRef ref = new ConversationCheckpoint.EvidenceRef(
                "tc-1", "query_entities", ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY,
                "entity_query", "11112222-3333-4444-5555-666677778888", "complete", false,
                ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE, "query_entities");
        ConversationCheckpoint cp = checkpoint(CID, AGENT, AMID, "goal", tail(), List.of(ref));
        List<ValueCollection> rows = List.of(
                user("retained question"),
                finalAssistant("retained answer", AMID),
                checkpointRow(cp, AGENT));

        Optional<ConversationCheckpointRehydrate.Restored> restored = ConversationCheckpointRehydrate.restore(
                rows, CID, AGENT, cacheId -> "11112222-3333-4444-5555-666677778888".equals(cacheId), null);

        assertTrue(restored.isPresent());
        assertEquals(ConversationCheckpoint.EvidenceRef.LIVENESS_LIVE,
                restored.get().checkpoint().evidenceRefs().get(0).liveness(),
                "liveness is decided by the lookup, in both directions");
        assertFalse(restored.get().head().isEmpty());
    }
}
