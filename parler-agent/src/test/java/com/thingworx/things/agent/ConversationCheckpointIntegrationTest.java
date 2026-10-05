package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.thingworx.things.agent.compaction.ConversationCheckpoint;
import com.thingworx.things.agent.compaction.ConversationCheckpointCodec;
import com.thingworx.things.agent.compaction.ConversationCheckpointGenerator;
import com.thingworx.things.agent.compaction.ConversationCheckpointPersistence;
import com.thingworx.things.agent.compaction.ConversationCheckpointWorkingSet;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.things.agent.compaction.ConversationCheckpointSemantic;
import com.thingworx.things.agent.compaction.ConversationsReplayNormalization;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Slice B unit 3b of {@code docs/core/advanced-compact.md}: the single §8.1.1 post-turn entry, the §10.2 ordering,
 * and the mechanical guarantee that no success path can trim without passing through it.
 */
class ConversationCheckpointIntegrationTest {

    private static final String CID = "conv-integration";
    private static final String AGENT = "MyAgentThing";
    private static final String AMID = "amid-1";
    private static final int CAP = 10_000;
    private static final String RID = "rid-42";
    private static final String OTHER_AGENT = "OtherAgentThing";


    /** Records what §10.2 step 6 was handed, and whether it succeeded. */
    private static final class RecordingAppender implements ConversationCheckpointPersistence.RowAppender {
        private final boolean succeed;
        String streamThreadKey;
        String streamEntrySource;
        String agentThing;
        String envelope;
        int calls;

        RecordingAppender(boolean succeed) {
            this.succeed = succeed;
        }

        @Override
        public boolean append(String key, String source, String agentThingName, String envelopeJson) {
            calls++;
            streamThreadKey = key;
            streamEntrySource = source;
            agentThing = agentThingName;
            envelope = envelopeJson;
            return succeed;
        }
    }

    @AfterEach
    void clearPersistenceHook() {
        ConversationCheckpointPersistence.clearAllTestHooks();
    }

    @BeforeEach
    void resetWorkingCheckpoint() {
        // No Stream Thing exists in a unit test, so without a seam every creating test would take §8.4's failure
        // branch and the success path would be untestable. Tests that care about persistence override this.
        ConversationCheckpointPersistence.setAppenderForTest(new RecordingAppender(true));
        // The §8.4 JVM working checkpoint outlives a single turn by design; tests must not inherit each other's.
        ConversationCheckpointWorkingSet.clearConversation(AGENT, CID);
        ConversationCheckpointWorkingSet.clearConversation(OTHER_AGENT, CID);
    }

    private static final class StubClient implements LlmClient {
        private final String reply;
        final List<LlmChatRequest> requests = new ArrayList<>();
        int calls;

        StubClient(String reply) {
            this.reply = reply;
        }

        @Override
        public LlmResponse chat(LlmChatRequest request) {
            calls++;
            requests.add(request);
            return new LlmResponse(reply, List.of(), LlmResponse.FinishReason.STOP, 3, 5);
        }

        @Override
        public LlmUsageWireIds usageWireIds() {
            return new LlmUsageWireIds("ProviderThing", "tpl", "openai-chat-completions-v1", "gpt-x");
        }

        @Override
        public OptionalLong contextPlanningInputCapChars(long requestedMaxOutputTokens, String modelOverride) {
            return OptionalLong.empty();
        }

        @Override
        public boolean healthCheck() {
            return true;
        }
    }

    private static String goodReply() {
        return "{\"goal\":\"compare pump throughput\",\"nextSteps\":[\"chart the candidates\"]}";
    }

    /** Long enough that the storage cap forces the oldest transcript pair out. */
    private static List<ChatMessage> conversation() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("stable prompt"));
        m.add(ChatMessage.user("old question " + "o".repeat(3_000)));
        m.add(ChatMessage.assistant("old answer " + "p".repeat(3_000)));
        m.add(ChatMessage.user("recent question " + "q".repeat(2_000)));
        m.add(ChatMessage.assistant("recent answer " + "r".repeat(2_000)));
        m.add(ChatMessage.user("current question"));
        m.add(ChatMessage.assistant("current answer"));
        return m;
    }

    private static AgentLoop.AgentResult success() {
        return AgentLoop.AgentResult.success("current answer", 1, 0, 0, StreamTokenUsage.ZERO);
    }

    // --- §8.1.1 the mechanical completeness rule ------------------------------------------------------

    @Test
    void agentThingRetainsNoDirectStorageTrimCallSites() throws IOException {
        // The rule that makes "no path was missed" a build property rather than a matter of manual diligence: a future
        // success path that trims must route through the single entry, or this fails.
        String source = readAgentThingSource();
        assertFalse(source.contains("ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget"),
                "every trim must go through applyPostTurnNormalizationBeforeStore");
        assertFalse(source.contains("ConversationsReplayNormalization.applyTierBReplayPromotionBeforeStore"),
                "Tier B promotion is part of the same entry now");
        int entries = source.split("applyPostTurnNormalizationBeforeStore", -1).length - 1;
        assertEquals(8, entries, "all four success/terminal couples route through the entry");
    }

    @Test
    void playbookSlashFinalStillPerformsNoPostTurnNormalization() throws IOException {
        // §8.1.1 keeps finishPlaybookSlashTurn out of scope: it runs neither Tier B nor the trim today, and adding
        // them would be a normalization change rather than continuity recovery.
        String source = readAgentThingSource();
        int start = source.indexOf("private PlaybookSlashTurnFinish finishPlaybookSlashTurn(");
        assertTrue(start > 0, "fixture precondition: the method still exists");
        int end = source.indexOf("\n    private String playbookConversationKey(", start);
        assertTrue(end > start, "fixture precondition: the method end is locatable");
        String body = source.substring(start, end);
        assertFalse(body.contains("applyPostTurnNormalizationBeforeStore"));
        assertFalse(body.contains("maybeTrimForStorageBudget"));
    }

    private static String readAgentThingSource() throws IOException {
        Path p = Paths.get("src/main/java/com/thingworx/things/agent/AgentThing.java");
        if (!Files.exists(p)) {
            p = Paths.get("parler-agent/src/main/java/com/thingworx/things/agent/AgentThing.java");
        }
        assertTrue(Files.exists(p), "fixture precondition: AgentThing source is readable");
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    // --- §10.2 ordering and working-set replacement ---------------------------------------------------

    @Test
    void aSuccessfulTurnInstallsExactlyOneCheckpointAheadOfTheRetainedTail() {
        List<ChatMessage> messages = conversation();
        StubClient client = new StubClient(goodReply());

        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        messages, success(), true, null, CID, RID, CAP, client, AMID, AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.CREATED, outcome);
        assertEquals(1, client.calls, "one summary call for one compaction");

        int checkpoints = 0;
        int checkpointIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (ConversationCheckpointCodec.isInjectedCheckpoint(messages.get(i))) {
                checkpoints++;
                checkpointIdx = i;
            }
        }
        assertEquals(1, checkpoints, "§5 invariant 2: exactly one working checkpoint");
        assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole(), "the stable prompt keeps its position");
        assertEquals(1, checkpointIdx, "the checkpoint sits where the covered span was");
        assertTrue(messages.get(checkpointIdx).getContent().contains("compare pump throughput"));
        assertEquals("current answer", messages.get(messages.size() - 1).getContent(),
                "the current turn is never touched");
    }

    @Test
    void aSecondCompactionReplacesRatherThanAccumulatesCheckpoints() {
        List<ChatMessage> messages = conversation();
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                messages, success(), true, null, CID, RID, CAP, new StubClient(goodReply()), AMID, AGENT, "t");

        // Grow the conversation again so a second compaction is warranted.
        messages.add(ChatMessage.user("next question " + "s".repeat(4_000)));
        messages.add(ChatMessage.assistant("next answer " + "t".repeat(4_000)));
        messages.add(ChatMessage.user("newest question"));
        messages.add(ChatMessage.assistant("newest answer"));

        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                messages, success(), true, null, CID, RID, CAP,
                new StubClient("{\"goal\":\"second checkpoint goal\"}"), "amid-2", AGENT, "t");

        int checkpoints = 0;
        for (ChatMessage m : messages) {
            if (ConversationCheckpointCodec.isInjectedCheckpoint(m)) {
                checkpoints++;
            }
        }
        assertEquals(1, checkpoints, "an older working checkpoint is replaced, never accumulated");
    }

    // --- §8.4 the checkpoint never costs the answer ---------------------------------------------------

    @Test
    void aProviderFailureLeavesTheDeterministicTrimUntouched() {
        List<ChatMessage> withCheckpoint = conversation();
        List<ChatMessage> trimOnly = conversation();

        LlmClient failing = new LlmClient() {
            @Override
            public LlmResponse chat(LlmChatRequest request) throws Exception {
                throw new IllegalStateException("read timed out");
            }

            @Override
            public LlmUsageWireIds usageWireIds() {
                return new LlmUsageWireIds("p", "t", "openai-chat-completions-v1", "m");
            }

            @Override
            public boolean healthCheck() {
                return true;
            }
        };

        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        withCheckpoint, success(), true, null, CID, RID, CAP, failing, AMID, AGENT, "t");
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                trimOnly, success(), true, null, CID, RID, CAP, null, AMID, AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.MODEL_ERROR, outcome);
        assertEquals(describe(trimOnly), describe(withCheckpoint),
                "a failed checkpoint leaves exactly what the deterministic trim would have left");
    }

    @Test
    void anErrorTurnNeverCallsTheModelAndStillTrims() {
        // The four isArtifactCacheTerminal sites carry ERROR; the success gate makes them checkpoint no-ops while
        // Tier B and the trim behave exactly as before.
        List<ChatMessage> messages = conversation();
        StubClient client = new StubClient(goodReply());

        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        messages, AgentLoop.AgentResult.error("BOOM", "failed", 0, 0, 0, null),
                        true, null, CID, RID, CAP, client, null, AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.NO_SAFE_BOUNDARY, outcome);
        assertEquals(0, client.calls, "an error turn must not reach the provider");
        for (ChatMessage m : messages) {
            assertFalse(ConversationCheckpointCodec.isInjectedCheckpoint(m));
        }
        assertTrue(messages.size() < conversation().size(), "the deterministic trim still ran");
    }

    @Test
    void aMissingWatermarkOrClientSkipsCheckpointingButNotTrimming() {
        for (Object[] c : new Object[][] { { null, AMID }, { new StubClient(goodReply()), null },
                { new StubClient(goodReply()), "  " } }) {
            List<ChatMessage> messages = conversation();
            ConversationCheckpointGenerator.Outcome outcome =
                    ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                            messages, success(), true, null, CID, RID, CAP, (LlmClient) c[0], (String) c[1], AGENT, "t");

            assertEquals(ConversationCheckpointGenerator.Outcome.NO_SAFE_BOUNDARY, outcome);
            for (ChatMessage m : messages) {
                assertFalse(ConversationCheckpointCodec.isInjectedCheckpoint(m));
            }
            assertTrue(messages.size() < conversation().size(), "the deterministic trim still ran");
        }
    }

    @Test
    void compactionDisabledSkipsEverythingTheTrimmerSkips() {
        List<ChatMessage> messages = conversation();
        StubClient client = new StubClient(goodReply());

        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        messages, success(), false, null, CID, RID, CAP, client, AMID, AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.NO_SAFE_BOUNDARY, outcome);
        assertEquals(0, client.calls);
        assertEquals(describe(conversation()), describe(messages), "the working set is untouched");
    }

    @Test
    void aConversationWithinTheCapIsLeftAloneEntirely() {
        List<ChatMessage> messages = conversation();
        StubClient client = new StubClient(goodReply());

        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        messages, success(), true, null, CID, RID, 1_000_000, client, AMID, AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.NO_SAFE_BOUNDARY, outcome);
        assertEquals(0, client.calls, "no transcript is about to be deleted, so no summary is warranted");
        assertEquals(describe(conversation()), describe(messages));
    }


    // --- §8.1 step 7: the published set is the validated set ------------------------------------------

    /**
     * A conversation whose dry run drops an evidence batch <em>after</em> the semantic cutoff. Those rows are
     * absent from the checkpoint's retained tail but still present in the live list, so "remove the covered prefix
     * and keep the rest" and "materialize the checkpoint" publish different lists.
     */
    private static List<ChatMessage> conversationWithHistoricEvidence() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("stable prompt"));
        // The oldest pair is what the transcript pass drops, so it must be larger than the injected row for the
        // published set to still fit the cap the trimmer just brought it under.
        m.add(ChatMessage.user("old question " + "a".repeat(1_500)));
        m.add(ChatMessage.assistant("old answer " + "b".repeat(1_500)));
        m.add(ChatMessage.user("evidence question"));
        m.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("tc-1", "query_things", "{}"))));
        m.add(ChatMessage.toolResult("tc-1", "{\"rows\":[\"" + "z".repeat(5_000) + "\"]}"));
        m.add(ChatMessage.assistant("evidence answer"));
        m.add(ChatMessage.user("bulk question"));
        m.add(ChatMessage.assistant("bulk answer " + "c".repeat(11_000)));
        m.add(ChatMessage.user("current question"));
        m.add(ChatMessage.assistant("current answer"));
        return m;
    }

    @Test
    void evidenceRowsTheDryRunRemovedAreNotRepublishedByInstallation() {
        List<ChatMessage> messages = conversationWithHistoricEvidence();
        StubClient client = new StubClient(goodReply());

        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        messages, success(), true, null, CID, RID, 12_000, client, AMID, AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.CREATED, outcome);
        // Row for row, not "a checkpoint exists and the answer survived".
        List<String> actual = describe(messages);
        assertEquals("SYSTEM:stable prompt", actual.get(0), actual.toString());
        assertTrue(ConversationCheckpointCodec.isInjectedCheckpoint(messages.get(1)), actual.toString());
        List<String> tail = new ArrayList<>();
        for (int i = 2; i < messages.size(); i++) {
            tail.add(messages.get(i).getRole() + ":" + messages.get(i).getContent());
        }
        assertEquals(List.of("USER:evidence question", "ASSISTANT:evidence answer",
                        "USER:bulk question",
                        "ASSISTANT:bulk answer " + "c".repeat(11_000),
                        "USER:current question", "ASSISTANT:current answer"),
                tail,
                "the dropped tool-call batch is gone and every surviving transcript row is present, in order");
        for (ChatMessage m : messages) {
            assertFalse(m.getRole() == ChatMessage.Role.TOOL, "a removed TOOL row must not be republished");
            assertFalse(m.hasToolCalls(), "its assistant tool-call row must not be republished either");
        }
    }


    @Test
    void aSummaryCapThatCoversOnlyTheFirstOfTwoCoveredTurnsRefusesRatherThanLosingTheSecond() {
        // Turn one is small enough to summarize alone; the covered prefix as a whole is not. The generator
        // therefore selects a strict oldest sub-prefix and carries turn two at the front of its retained tail.
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable prompt"));
        messages.add(ChatMessage.user("first question " + "a".repeat(500)));
        messages.add(ChatMessage.assistant("first answer " + "b".repeat(500)));
        messages.add(ChatMessage.user("second question " + "c".repeat(6_000)));
        messages.add(ChatMessage.assistant("second answer " + "d".repeat(6_000)));
        messages.add(ChatMessage.user("current question"));
        messages.add(ChatMessage.assistant("current answer"));
        List<ChatMessage> trimOnly = new ArrayList<>(messages);

        StubClient client = new StubClient(goodReply());
        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        messages, success(), true, null, CID, RID, 12_000, client, AMID, AGENT, "t");
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                trimOnly, success(), true, null, CID, RID, 12_000, null, AMID, AGENT, "t");

        // A partially covered prefix always leaves the newest removed pair in the unselected suffix — the very rows
        // whose removal brought the working set under the cap — so re-materializing them can never fit it again.
        // The outcome is a refusal, and the refusal costs nothing: the deterministic trim publishes what it always
        // would have, turn two included where it survives.
        assertEquals(ConversationCheckpointGenerator.Outcome.NO_SHRINK, outcome);
        assertEquals(1, client.calls, "the refusal happens after the summary call, which is why it is measured");
        for (ChatMessage m : messages) {
            assertFalse(ConversationCheckpointCodec.isInjectedCheckpoint(m));
        }
        assertEquals(describe(trimOnly), describe(messages),
                "nothing the summary did not cover was deleted on its behalf");
    }

    // --- §6.3 repeated compaction uses the previous validated state ------------------------------------

    @Test
    void aSecondCompactionSummarizesFromThePriorValidatedSemanticRatherThanFromProse() {
        List<ChatMessage> messages = conversation();
        StubClient first = new StubClient(goodReply());
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                messages, success(), true, null, CID, RID, CAP, first, AMID, AGENT, "t");
        assertEquals("compare pump throughput",
                ConversationCheckpointWorkingSet.current(AGENT, CID).semantic().goal(),
                "the first checkpoint is retained as the JVM working checkpoint");

        messages.add(ChatMessage.user("next question " + "s".repeat(4_000)));
        messages.add(ChatMessage.assistant("next answer " + "t".repeat(4_000)));
        messages.add(ChatMessage.user("newest question"));
        messages.add(ChatMessage.assistant("newest answer"));

        StubClient second = new StubClient("{\"goal\":\"second checkpoint goal\"}");
        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        messages, success(), true, null, CID, RID, CAP, second, "amid-2", AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.CREATED, outcome);
        assertEquals(1, second.calls);
        String payload = second.requests.get(0).getMessages().get(1).getContent();
        assertTrue(payload.contains("compare pump throughput"),
                "the prior validated semantic is the §6.3 input, not the injected prose row");
        assertEquals("second checkpoint goal", ConversationCheckpointWorkingSet.current(AGENT, CID).semantic().goal(),
                "the new state replaces the prior one");

        int checkpoints = 0;
        for (ChatMessage m : messages) {
            if (ConversationCheckpointCodec.isInjectedCheckpoint(m)) {
                checkpoints++;
            }
        }
        assertEquals(1, checkpoints, "§5 invariant 2 still holds after a repeated compaction");
    }


    @Test
    void priorEvidenceRefsAreRevalidatedAndCarriedRatherThanSilentlyLost() {
        // The injected prose row carries no refs at all, so reading the prior state back from it would drop every
        // one of them without a trace. Carrying is not trusting: each ref is re-checked before it is carried.
        ConversationCheckpoint.EvidenceRef good = new ConversationCheckpoint.EvidenceRef(
                "tc-prior", "query_entities", ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY,
                "entity_query", "11112222-3333-4444-5555-666677778888", "complete", false,
                ConversationCheckpoint.EvidenceRef.LIVENESS_LIVE, "query_entities");
        ConversationCheckpoint.EvidenceRef unknownFamily = new ConversationCheckpoint.EvidenceRef(
                "tc-bogus", "query_entities", "invented-family",
                "entity_query", "99998888-7777-6666-5555-444433332222", "complete", false,
                ConversationCheckpoint.EvidenceRef.LIVENESS_LIVE, "query_entities");
        ConversationCheckpointWorkingSet.record(AGENT, CID, new ConversationCheckpoint(
                new ConversationCheckpoint.Source(CID, AGENT, "amid-0"),
                new ConversationCheckpointSemantic("prior goal", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()),
                List.of(good, unknownFamily), List.of(),
                new ConversationCheckpoint.Generated("t0", "p", "m")));

        List<ChatMessage> messages = conversation();
        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        messages, success(), true, null, CID, RID, CAP, new StubClient(goodReply()), AMID, AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.CREATED, outcome);
        List<ConversationCheckpoint.EvidenceRef> carried =
                ConversationCheckpointWorkingSet.current(AGENT, CID).evidenceRefs();
        assertEquals(1, carried.size(), "the unrecognized family is dropped, the admissible ref is kept");
        assertEquals("tc-prior", carried.get(0).toolCallId());
        assertEquals(ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE, carried.get(0).liveness(),
                "liveness is re-resolved, not inherited: 'it was live once' is an assumption, not a fact");
    }


    @Test
    void twoAgentThingsSharingAConversationIdDoNotSeeEachOthersWorkingCheckpoint() {
        // _conversations is an instance field, so the same conversation id can name two different working
        // conversations. Scoping the cache by id alone would put one agent's task state and evidence refs into the
        // other's provider request.
        List<ChatMessage> forA = conversation();
        StubClient a = new StubClient("{\"goal\":\"agent A goal\"}");
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                forA, success(), true, null, CID, RID, CAP, a, AMID, AGENT, "t");

        List<ChatMessage> forB = conversation();
        StubClient b = new StubClient("{\"goal\":\"agent B goal\"}");
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                forB, success(), true, null, CID, RID, CAP, b, AMID, OTHER_AGENT, "t");

        // Read isolation: B summarized from nothing, not from A's state.
        assertFalse(b.requests.get(0).getMessages().get(1).getContent().contains("agent A goal"),
                "B's summary request must not carry A's task state");
        // Replacement isolation: B's checkpoint did not overwrite A's.
        assertEquals("agent A goal", ConversationCheckpointWorkingSet.current(AGENT, CID).semantic().goal());
        assertEquals("agent B goal", ConversationCheckpointWorkingSet.current(OTHER_AGENT, CID).semantic().goal());
        // Clear isolation: evicting one agent's conversation leaves the other's alone.
        ConversationCheckpointWorkingSet.clearConversation(OTHER_AGENT, CID);
        assertEquals(null, ConversationCheckpointWorkingSet.current(OTHER_AGENT, CID));
        assertEquals("agent A goal", ConversationCheckpointWorkingSet.current(AGENT, CID).semantic().goal());
    }

    @Test
    void aCachedCheckpointWhoseSourceDisagreesWithItsKeyIsRefused() {
        // Defence in depth behind the composite key: an entry that claims a different owner cannot be attributed to
        // this turn, so it is refused rather than summarized from.
        ConversationCheckpointWorkingSet.record(AGENT, CID, new ConversationCheckpoint(
                new ConversationCheckpoint.Source(CID, OTHER_AGENT, "amid-0"),
                new ConversationCheckpointSemantic("mismatched owner", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()),
                List.of(), List.of(), new ConversationCheckpoint.Generated("t0", "p", "m")));
        assertEquals(null, ConversationCheckpointWorkingSet.current(AGENT, CID),
                "a mismatched envelope is never filed, and never read back");
    }

    @Test
    void clearingAConversationDropsItsWorkingCheckpoint() {
        // Same lifetime as _conversations: AgentThing clears both at the two eviction points.
        List<ChatMessage> messages = conversation();
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                messages, success(), true, null, CID, RID, CAP, new StubClient(goodReply()), AMID, AGENT, "t");
        assertTrue(ConversationCheckpointWorkingSet.current(AGENT, CID) != null);
        ConversationCheckpointWorkingSet.clearConversation(AGENT, CID);
        assertEquals(null, ConversationCheckpointWorkingSet.current(AGENT, CID));
    }

    @Test
    void agentThingClearsTheWorkingCheckpointWhereverItEvictsAConversation() throws IOException {
        String source = readAgentThingSource();
        int evictions = source.split("_conversations\\.remove\\(", -1).length - 1;
        int clears = source.split("ConversationCheckpointWorkingSet\\.clearConversation\\(", -1).length - 1;
        assertEquals(evictions, clears, "every _conversations eviction must also drop the working checkpoint");
        assertTrue(evictions > 0, "fixture precondition: eviction sites exist");
    }

    // --- §12 telemetry ---------------------------------------------------------------------------------

    @Test
    void aCreatedEventCarriesEveryFieldTwelveNames() {
        List<String> lines = new ArrayList<>();
        List<ChatMessage> messages = conversation();
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                messages, success(), true, capturingLogger(lines), CID, RID, CAP, new StubClient(goodReply()),
                AMID, AGENT, "t");

        String created = only(lines, "CONVERSATION_CHECKPOINT_CREATED");
        for (String field : new String[] { "conversationId=", "requestId=", "coveredRows=", "retainedRows=",
                "beforeChars=", "afterChars=", "checkpointChars=", "evidenceRefs=", "summaryCalls=",
                "promptTokens=", "completionTokens=", "summaryDurationMs=" }) {
            assertTrue(created.contains(field), field + " missing from: " + created);
        }
        assertTrue(created.contains("summaryCalls=1"), created);
        assertTrue(created.contains("requestId=" + RID), "the label alone would pass on an empty value: " + created);
        assertFalse(created.contains("compare pump throughput"), "semantic text must never reach the log");
    }

    @Test
    void eachIneligibleBoundaryReasonStaysDistinguishable() {
        // §12 keeps these apart precisely so an operator can tell a missing identity from an unverifiable shape.
        List<String> blank = new ArrayList<>();
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                conversation(), success(), true, capturingLogger(blank), "  ", RID, CAP, new StubClient(goodReply()),
                AMID, AGENT, "t");
        assertTrue(only(blank, "CONVERSATION_CHECKPOINT_SKIP").contains("reason=NO_CONVERSATION_ID"), blank.toString());

        List<String> noDrop = new ArrayList<>();
        List<ChatMessage> evidenceOnly = new ArrayList<>();
        evidenceOnly.add(ChatMessage.system("stable prompt"));
        evidenceOnly.add(ChatMessage.user("q"));
        evidenceOnly.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("tc-1", "query_things", "{}"))));
        evidenceOnly.add(ChatMessage.toolResult("tc-1", "x".repeat(6_000)));
        evidenceOnly.add(ChatMessage.assistant("a"));
        evidenceOnly.add(ChatMessage.user("current question"));
        evidenceOnly.add(ChatMessage.assistant("current answer"));
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                evidenceOnly, success(), true, capturingLogger(noDrop), CID, RID, 3_000, new StubClient(goodReply()),
                AMID, AGENT, "t");
        assertTrue(only(noDrop, "CONVERSATION_CHECKPOINT_SKIP").contains("reason=NO_TRANSCRIPT_DROP"),
                noDrop.toString());
    }

    @Test
    void skipDurationIsZeroBeforeTheCallAndMeasuredAfterIt() {
        List<String> preCall = new ArrayList<>();
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                conversation(), success(), true, capturingLogger(preCall), "  ", RID, CAP, new StubClient(goodReply()),
                AMID, AGENT, "t");
        assertTrue(only(preCall, "CONVERSATION_CHECKPOINT_SKIP").contains("summaryDurationMs=0"), preCall.toString());

        List<String> postCall = new ArrayList<>();
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                conversation(), success(), true, capturingLogger(postCall), CID, RID, CAP,
                new StubClient("not json at all"), AMID, AGENT, "t");
        String line = only(postCall, "CONVERSATION_CHECKPOINT_SKIP");
        assertTrue(line.contains("reason=INVALID_JSON"), line);
        assertFalse(line.contains("summaryDurationMs=-"), line);
        assertTrue(line.contains("summaryDurationMs="), line);
    }

    @Test
    void turnsWhereTheTrimmerItselfIsANoOpEmitNoCheckpointEvent() {
        // COMPACTION_DISABLED and WITHIN_CAP are not §12 reasons: an event on every ordinary turn would bury the
        // reasons that matter.
        List<String> disabled = new ArrayList<>();
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                conversation(), success(), false, capturingLogger(disabled), CID, RID, CAP,
                new StubClient(goodReply()), AMID, AGENT, "t");
        List<String> withinCap = new ArrayList<>();
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                conversation(), success(), true, capturingLogger(withinCap), CID, RID, 1_000_000,
                new StubClient(goodReply()), AMID, AGENT, "t");

        for (List<String> lines : List.of(disabled, withinCap)) {
            for (String l : lines) {
                assertFalse(l.startsWith("CONVERSATION_CHECKPOINT_"), l);
            }
        }
    }


    @Test
    void theRequestIdSurvivesTheTurnContextCleanupEveryProductionPathPerforms() {
        // Every one of the eight call sites runs after AgentToolContext.clear(). Reading the thread-local here
        // would stamp requestId= empty on every event in production while every test still passed, so the identity
        // is a parameter and this asserts it against the real post-clear boundary.
        AgentToolContext.setParlerStreamIds(RID, "RemoteThing");
        AgentToolContext.clear();
        assertEquals(null, AgentToolContext.getParlerRequestId(), "fixture precondition: the turn context is gone");

        List<String> created = new ArrayList<>();
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                conversation(), success(), true, capturingLogger(created), CID, RID, CAP,
                new StubClient(goodReply()), AMID, AGENT, "t");
        assertTrue(only(created, "CONVERSATION_CHECKPOINT_CREATED").contains("requestId=" + RID), created.toString());

        List<String> skipped = new ArrayList<>();
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                conversation(), success(), true, capturingLogger(skipped), CID, RID, CAP,
                new StubClient("not json at all"), AMID, AGENT, "t");
        String skip = only(skipped, "CONVERSATION_CHECKPOINT_SKIP");
        assertTrue(skip.contains("requestId=" + RID), skip);
        assertTrue(skip.contains("reason=INVALID_JSON"), skip);
    }

    @Test
    void aPathWithNoRequestIdentityStampsTheIntentionallyEmptyValue() {
        List<String> lines = new ArrayList<>();
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                conversation(), success(), true, capturingLogger(lines), CID, null, CAP,
                new StubClient(goodReply()), AMID, AGENT, "t");
        assertTrue(only(lines, "CONVERSATION_CHECKPOINT_CREATED").contains("requestId= "),
                "Chat and ChatAsync have no request identity; the field stays present and empty: " + lines);
    }

    @Test
    void theRemotePathsPassTheirOwnRequestIdentityRatherThanNull() throws IOException {
        // The wiring, not the plumbing, is what can silently regress: a site quietly changed back to null would
        // re-break correlation without failing any behavioural test.
        String source = readAgentThingSource();
        int withRequestId = source.split("_logger, effectiveConvId, requestId,", -1).length - 1;
        int withWireRid = source.split("_logger, wireCid, wireRid,", -1).length - 1;
        assertEquals(2, withRequestId, "both AlwaysOn sites pass the streaming requestId");
        assertEquals(2, withWireRid, "both approval-continuation sites pass wireRid");
    }


    // --- §10.2 step 6: the checkpoint Stream row ---------------------------------------------------------

    @Test
    void aCreatedCheckpointIsPersistedAsItsValidatedEnvelopeUnderTheConversationStreamKey() {
        RecordingAppender appender = new RecordingAppender(true);
        ConversationCheckpointPersistence.setAppenderForTest(appender);
        List<ChatMessage> messages = conversation();
        List<String> sink = new ArrayList<>();

        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        messages, success(), true, capturingLogger(sink), CID, RID, CAP,
                        new StubClient(goodReply()), AMID, AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.CREATED, outcome);
        assertEquals(1, appender.calls, "exactly one checkpoint row per compaction");
        assertEquals(CID, appender.streamThreadKey, "the Stream key is the conversation id, derived not passed");
        assertEquals(CID, appender.streamEntrySource, "all eight call sites use the key as the source");
        assertEquals(AGENT, appender.agentThing, "§9.3 decides ownership from this cell");
        // The persisted bytes are the validated envelope, not a re-render of the prose row.
        assertTrue(appender.envelope.contains("parler.conversation_checkpoint.v1"), appender.envelope);
        assertEquals(ConversationCheckpointCodec.serialize(
                        ConversationCheckpointWorkingSet.current(AGENT, CID)),
                appender.envelope,
                "the row carries exactly the checkpoint the working set kept");
        for (String l : sink) {
            assertFalse(l.contains("STREAM_APPEND_FAILED"), l);
        }
    }


    @Test
    void aConversationIdCarryingWhitespaceKeepsOneIdentityAcrossTheRowTheCacheAndTheEnvelope() {
        // Chat/ChatAsync gate on isEmpty(), validate the supplied id as an exact AgentThreadDataTable key, and pass
        // it on unchanged; _conversations and the per-conversation lock use exact String equality. So this id is a
        // real thread, its final-assistant watermark row is appended under this exact source, and a checkpoint
        // filed under a normalized source could never be found and validated against that row at restart.
        final String padded = "  " + CID + "  ";
        RecordingAppender appender = new RecordingAppender(true);
        ConversationCheckpointPersistence.setAppenderForTest(appender);
        ConversationCheckpointWorkingSet.clearConversation(AGENT, padded);

        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        conversation(), success(), true, null, padded, RID, CAP,
                        new StubClient(goodReply()), AMID, AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.CREATED, outcome);
        assertEquals(padded, appender.streamThreadKey, "the Stream key is the id AgentThing itself would use");
        assertEquals(padded, appender.streamEntrySource);
        assertTrue(appender.envelope.contains(padded), "the envelope records the same identity: " + appender.envelope);

        // The JVM working checkpoint is keyed the same way, so the padded and unpadded ids stay distinct.
        assertTrue(ConversationCheckpointWorkingSet.current(AGENT, padded) != null);
        assertEquals(null, ConversationCheckpointWorkingSet.current(AGENT, CID),
                "\" abc \" and \"abc\" are two conversations, and must not share a working checkpoint");
        assertEquals(padded, ConversationCheckpointWorkingSet.current(AGENT, padded).source().conversationId());
        ConversationCheckpointWorkingSet.clearConversation(AGENT, padded);
    }

    /** Slow enough that a measured duration is distinguishable from a hardcoded zero. */
    private static final class SlowStubClient extends Object implements LlmClient {
        @Override
        public LlmResponse chat(LlmChatRequest request) throws Exception {
            Thread.sleep(20L);
            return new LlmResponse(goodReply(), List.of(), LlmResponse.FinishReason.STOP, 3, 5);
        }

        @Override
        public LlmUsageWireIds usageWireIds() {
            return new LlmUsageWireIds("ProviderThing", "tpl", "openai-chat-completions-v1", "gpt-x");
        }

        @Override
        public boolean healthCheck() {
            return true;
        }
    }

    private static long durationOf(String line) {
        int at = line.indexOf("summaryDurationMs=");
        assertTrue(at >= 0, line);
        return Long.parseLong(line.substring(at + "summaryDurationMs=".length()).trim());
    }

    @Test
    void aPersistenceFailureKeepsTheJvmWorkingCheckpointAndReportsIt() {
        // §8.4: the checkpoint is a continuity enhancement. Losing the row costs restart recovery only, so rolling
        // the installation back would trade a recoverable loss for an immediate one.
        ConversationCheckpointPersistence.setAppenderForTest(new RecordingAppender(false));
        List<ChatMessage> messages = conversation();
        List<String> sink = new ArrayList<>();

        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        messages, success(), true, capturingLogger(sink), CID, RID, CAP,
                        new SlowStubClient(), AMID, AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.CREATED, outcome,
                "the checkpoint was created and installed; only its row was lost");
        assertTrue(ConversationCheckpointWorkingSet.current(AGENT, CID) != null,
                "the JVM working checkpoint survives a persistence failure");
        int checkpoints = 0;
        for (ChatMessage m : messages) {
            if (ConversationCheckpointCodec.isInjectedCheckpoint(m)) {
                checkpoints++;
            }
        }
        assertEquals(1, checkpoints, "the installed row stays in the working set");

        String skip = only(sink, "CONVERSATION_CHECKPOINT_SKIP");
        assertTrue(skip.contains("reason=STREAM_APPEND_FAILED"), skip);
        assertTrue(skip.contains("requestId=" + RID), skip);
        String created = only(sink, "CONVERSATION_CHECKPOINT_CREATED");
        assertTrue(created.contains("coveredRows="), "creation still happened and is still reported");
        // §12: STREAM_APPEND_FAILED follows a completed provider call, so it carries that call's measured latency.
        // Comparing against the CREATED line proves the value was threaded rather than written as a literal zero.
        assertTrue(durationOf(skip) > 0, skip);
        assertEquals(durationOf(created), durationOf(skip),
                "both events report the one summary call this turn paid for");
    }

    @Test
    void theRowIsAppendedBeforeTheStorageTrimAndOnlyForACreatedCheckpoint() {
        // §10.2 fixes the order: checkpoint row (step 6) then storage trim (step 7). The row must describe the
        // working set the checkpoint published, not whatever the trim later leaves behind.
        List<Integer> sizeAtAppend = new ArrayList<>();
        List<ChatMessage> messages = conversation();
        ConversationCheckpointPersistence.setAppenderForTest((k, s, a, e) -> {
            sizeAtAppend.add(messages.size());
            return true;
        });

        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                messages, success(), true, null, CID, RID, CAP, new StubClient(goodReply()), AMID, AGENT, "t");

        assertEquals(1, sizeAtAppend.size());
        assertEquals(messages.size(), sizeAtAppend.get(0).intValue(),
                "the trim is a no-op after a successful checkpoint, and the row was written before it ran");
    }

    @Test
    void aTurnThatCreatesNoCheckpointAppendsNoRow() {
        RecordingAppender appender = new RecordingAppender(true);
        ConversationCheckpointPersistence.setAppenderForTest(appender);

        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                conversation(), success(), true, null, CID, RID, CAP,
                new StubClient("not json at all"), AMID, AGENT, "t");
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                conversation(), success(), true, null, CID, RID, 1_000_000,
                new StubClient(goodReply()), AMID, AGENT, "t");
        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                conversation(), AgentLoop.AgentResult.error("BOOM", "failed", 0, 0, 0, null),
                true, null, CID, RID, CAP, new StubClient(goodReply()), null, AGENT, "t");

        assertEquals(0, appender.calls, "a rejected, unnecessary, or non-success turn persists nothing");
    }


    // --- §6.3 across a restart --------------------------------------------------------------------------

    private static ValueCollection historyRow(String role, String content, String assistantMessageId) {
        ValueCollection vc = new ValueCollection();
        vc.put("role", new StringPrimitive(role));
        vc.put("agentThing", new StringPrimitive(AGENT));
        vc.put("content", new StringPrimitive(content));
        vc.put("toolCalls", new StringPrimitive(""));
        vc.put("assistantMessageId", new StringPrimitive(assistantMessageId));
        return vc;
    }

    private static ConversationCheckpoint persistedCheckpoint(String goal, String cacheId) {
        return new ConversationCheckpoint(
                new ConversationCheckpoint.Source(CID, AGENT, "amid-restore"),
                new ConversationCheckpointSemantic(goal, List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of()),
                List.of(new ConversationCheckpoint.EvidenceRef("tc-restored", "query_entities",
                        ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY, "entity_query", cacheId,
                        "complete", false, ConversationCheckpoint.EvidenceRef.LIVENESS_LIVE, "query_entities")),
                List.of(new ConversationCheckpoint.RetainedRow(ConversationCheckpoint.RetainedRow.ROLE_USER,
                                "retained question", ConversationCheckpoint.RetainedRow.PROVENANCE_TRANSCRIPT),
                        new ConversationCheckpoint.RetainedRow(
                                ConversationCheckpoint.RetainedRow.ROLE_ASSISTANT, "retained answer",
                                ConversationCheckpoint.RetainedRow.PROVENANCE_FINAL_ASSISTANT)),
                new ConversationCheckpoint.Generated("2026-08-24T00:00:00Z", "Prov", "gpt-x"));
    }

    private static List<ValueCollection> windowWith(ConversationCheckpoint cp) {
        return List.of(
                historyRow("user", "retained question", ""),
                historyRow("assistant", "retained answer", "amid-restore"),
                historyRow("context_checkpoint", ConversationCheckpointCodec.serialize(cp), ""),
                historyRow("user", "question after restart", ""),
                historyRow("assistant", "answer after restart", "amid-after"));
    }

    @Test
    void aRestoredCheckpointBecomesTheNextCompactionsPriorState() {
        // Without this the map is empty after restart and §6.3's "previous validated checkpoint semantic + new
        // complete turns" degrades to summarizing from prose, silently dropping every carried ref.
        ConversationCheckpoint cp = persistedCheckpoint("restored goal",
                "11112222-3333-4444-5555-666677778888");
        AgentConversationRehydrator.buildRehydrated(windowWith(cp), CID, AGENT,
                ConversationRehydrateSettings.custom(true, 300, 1_000_000, false), null)
                .reconcileWorkingCheckpoint(CID, AGENT);

        assertEquals("restored goal", ConversationCheckpointWorkingSet.current(AGENT, CID).semantic().goal(),
                "the restored envelope is the working checkpoint again");

        StubClient next = new StubClient("{\"goal\":\"goal after restart\"}");
        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        conversation(), success(), true, null, CID, RID, CAP, next, AMID, AGENT, "t");

        assertEquals(ConversationCheckpointGenerator.Outcome.CREATED, outcome);
        assertTrue(next.requests.get(0).getMessages().get(1).getContent().contains("restored goal"),
                "the next summary request is given the restored prior state");
        List<ConversationCheckpoint.EvidenceRef> carried =
                ConversationCheckpointWorkingSet.current(AGENT, CID).evidenceRefs();
        assertEquals(1, carried.size(), "the restored ref is carried forward, not lost across the restart");
        assertEquals("tc-restored", carried.get(0).toolCallId());
        assertEquals(ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE, carried.get(0).liveness(),
                "and its liveness was re-decided, never inherited");
    }

    @Test
    void aCheckpointTheBudgetDropsLeavesNoHiddenWorkingEntry() {
        // The record is conditional on the injected row surviving every rehydrate step. A budget-dropped
        // checkpoint that still populated the map would have the next compaction summarize from state the model
        // was never shown.
        ConversationCheckpoint cp = persistedCheckpoint("g".repeat(600),
                "11112222-3333-4444-5555-666677778888");
        seedStaleWorkingCheckpoint();
        AgentConversationRehydrator.Rehydrated result = AgentConversationRehydrator.buildRehydrated(
                windowWith(cp), CID, AGENT, ConversationRehydrateSettings.custom(true, 300, 200, false), null);
        result.reconcileWorkingCheckpoint(CID, AGENT);
        List<ChatMessage> rehydrated = result.messages();

        for (ChatMessage m : rehydrated) {
            assertFalse(ConversationCheckpointCodec.isInjectedCheckpoint(m),
                    "fixture precondition: the budget really did drop it");
        }
        assertEquals(null, ConversationCheckpointWorkingSet.current(AGENT, CID),
                "the stale entry is cleared, not merely left unwritten");
    }

    @Test
    void aRejectedCheckpointLeavesNoWorkingEntryEither() {
        List<ValueCollection> window = List.of(
                historyRow("user", "retained question", ""),
                historyRow("assistant", "retained answer", "amid-restore"),
                historyRow("context_checkpoint", "{not json", ""));

        seedStaleWorkingCheckpoint();
        AgentConversationRehydrator.buildRehydrated(window, CID, AGENT,
                ConversationRehydrateSettings.custom(true, 300, 1_000_000, false), null)
                .reconcileWorkingCheckpoint(CID, AGENT);

        assertEquals(null, ConversationCheckpointWorkingSet.current(AGENT, CID),
                "a rejected candidate must not leave the previous instance's state behind");
    }

    @Test
    void aConversationWithNoCandidateAlsoClearsStaleWorkingState() {
        // The path that motivated the liveness fix: a same-JVM AgentThing recreation starts with an empty
        // _conversations but a populated static working set. Leaving that entry would feed the next compaction
        // semantic state and refs that are nowhere in the transcript the model receives.
        seedStaleWorkingCheckpoint();
        List<ValueCollection> window = List.of(
                historyRow("user", "only question", ""),
                historyRow("assistant", "only answer", "amid-x"));

        AgentConversationRehydrator.buildRehydrated(window, CID, AGENT,
                ConversationRehydrateSettings.custom(true, 300, 1_000_000, false), null)
                .reconcileWorkingCheckpoint(CID, AGENT);

        assertEquals(null, ConversationCheckpointWorkingSet.current(AGENT, CID));
    }

    /** State a previous AgentThing instance left in the static working set for this same conversation. */
    private static void seedStaleWorkingCheckpoint() {
        ConversationCheckpointWorkingSet.record(AGENT, CID, persistedCheckpoint("stale goal",
                "99998888-7777-6666-5555-444433332222"));
        assertTrue(ConversationCheckpointWorkingSet.current(AGENT, CID) != null,
                "fixture precondition: there really is stale state to clear");
    }


    @Test
    void publicationReconcilesWorkingStateOnlyForTheListThatWon() throws IOException {
        // putIfAbsent can lose the race. Writing JVM working state for a list that never gets published would
        // leave the next compaction summarizing from a checkpoint no model was shown — and no behavioural test
        // can see that wiring disappear, because the losing branch simply returns someone else's list.
        String source = readAgentThingSource();
        int at = source.indexOf("_conversations.putIfAbsent(conversationId, list);");
        assertTrue(at > 0, "fixture precondition: the publication boundary still exists");
        String block = source.substring(at, Math.min(source.length(), at + 600));
        assertTrue(block.contains("published == list"),
                "reconciliation must be gated on winning publication: " + block);
        assertTrue(block.contains("reconcileWorkingCheckpoint(conversationId, getName())"), block);
    }


    @Test
    void aFreshThreadFallbackClearsThePreviousInstancesWorkingCheckpoint() {
        // The turn's post-turn compaction reads this map. Handing the model a fresh transcript while the map
        // still holds another instance's semantic state and refs is the hidden-state failure the negative
        // rehydrate cases exist to prevent — and a fresh thread is the one case where nothing is published at all.
        seedStaleWorkingCheckpoint();
        AgentConversationRehydrator.reconcileForFreshThread(AGENT, CID);
        assertEquals(null, ConversationCheckpointWorkingSet.current(AGENT, CID));
    }

    @Test
    void aFreshThreadFallbackTouchesOnlyItsOwnAgentAndConversation() {
        // The envelope must name OTHER_AGENT: the working set refuses an entry whose source disagrees with the
        // key it is filed under, so a checkpoint built for AGENT would simply never be recorded here.
        ConversationCheckpointWorkingSet.record(OTHER_AGENT, CID, new ConversationCheckpoint(
                new ConversationCheckpoint.Source(CID, OTHER_AGENT, "amid-other"),
                new ConversationCheckpointSemantic("other agent goal", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()),
                List.of(), List.of(),
                new ConversationCheckpoint.Generated("2026-08-24T00:00:00Z", "Prov", "gpt-x")));
        assertTrue(ConversationCheckpointWorkingSet.current(OTHER_AGENT, CID) != null,
                "fixture precondition: the other agent's entry really was recorded");
        seedStaleWorkingCheckpoint();

        AgentConversationRehydrator.reconcileForFreshThread(AGENT, CID);

        assertEquals(null, ConversationCheckpointWorkingSet.current(AGENT, CID));
        assertTrue(ConversationCheckpointWorkingSet.current(OTHER_AGENT, CID) != null,
                "another AgentThing's working checkpoint is not this instance's to discard");
        ConversationCheckpointWorkingSet.clearConversation(OTHER_AGENT, CID);
    }

    @Test
    void everyFreshThreadRouteInResolveConversationReconciles() throws IOException {
        // Four routes reach a fresh thread — query/mapping failure, empty shaped result, agent mismatch, and
        // rehydrate disabled — and none of them can be observed from outside: each returns a plausible new
        // thread. The mechanical rule is what keeps a fifth from being added without the clear.
        String source = readAgentThingSource();
        int at = source.indexOf("private List<ChatMessage> resolveConversation(");
        assertTrue(at > 0, "fixture precondition: resolveConversation still exists");
        String body = source.substring(at, source.indexOf("\n    /**", at + 10));
        assertFalse(body.contains("return newThreadSeedMessages("),
                "every fresh-thread exit must route through the clearing helper: " + body);
        assertEquals(2, body.split("freshThreadForConversation\\(", -1).length - 1,
                "the mismatch route and the shared fallback are both covered");
        int helper = source.indexOf("private List<ChatMessage> freshThreadForConversation(");
        assertTrue(helper > 0);
        assertTrue(source.substring(helper, helper + 500).contains(
                        "AgentConversationRehydrator.reconcileForFreshThread(getName(), conversationId)"),
                "and the helper is what clears");
    }

    private static String only(List<String> lines, String prefix) {
        String found = null;
        for (String l : lines) {
            if (l.startsWith(prefix)) {
                assertEquals(null, found, "exactly one " + prefix + " line expected: " + lines);
                found = l;
            }
        }
        assertTrue(found != null, prefix + " not logged; saw " + lines);
        return found;
    }

    /** Renders slf4j {} placeholders so the assertions read the line an operator would see. */
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

    private static List<String> describe(List<ChatMessage> rows) {
        List<String> out = new ArrayList<>();
        for (ChatMessage m : rows) {
            out.add(m.getRole() + ":" + (m.hasToolCalls() ? "toolcalls" : m.getContent()));
        }
        return out;
    }
}
