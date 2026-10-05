package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * §8.2 and §8.4 of {@code docs/core/advanced-compact.md}: one direct call on the turn's own client, a fixed request,
 * a bounded input admitted whole turns at a time, and a failure path that never costs the completed answer.
 */
class ConversationCheckpointGeneratorTest {

    private static final String CID = "conv-gen";
    private static final String AGENT = "MyAgentThing";
    private static final String AMID = "amid-1";
    private static final int CONTEXT_MAX = 400_000;

    /** Records every request so the fixed-request contract can be asserted, and counts calls so retries surface. */
    private static final class RecordingClient implements LlmClient {
        private final LlmResponse response;
        private final RuntimeException failure;
        private final long providerCapChars;
        final List<LlmChatRequest> requests = new ArrayList<>();
        Long lastRequestedMaxOutputTokens;
        String lastModelOverride = "unset";

        RecordingClient(LlmResponse response, RuntimeException failure, long providerCapChars) {
            this.response = response;
            this.failure = failure;
            this.providerCapChars = providerCapChars;
        }

        static RecordingClient replying(String content) {
            return new RecordingClient(new LlmResponse(content, List.of(), LlmResponse.FinishReason.STOP, 3, 5), null,
                    0L);
        }

        static RecordingClient failing(RuntimeException e) {
            return new RecordingClient(null, e, 0L);
        }

        @Override
        public LlmResponse chat(LlmChatRequest request) throws Exception {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public LlmUsageWireIds usageWireIds() {
            return new LlmUsageWireIds("pt", "tpl", "openai-chat-completions-v1", "gpt-x");
        }

        @Override
        public OptionalLong contextPlanningInputCapChars(long requestedMaxOutputTokens, String modelOverride) {
            lastRequestedMaxOutputTokens = requestedMaxOutputTokens;
            lastModelOverride = modelOverride;
            return providerCapChars > 0 ? OptionalLong.of(providerCapChars) : OptionalLong.empty();
        }

        @Override
        public boolean healthCheck() {
            return true;
        }
    }

    private static String goodReply() {
        return "{\"goal\":\"compare pump throughput\",\"constraints\":[\"metric units\"],"
                + "\"progress\":{\"done\":[\"surveyed 3 assets\"],\"inProgress\":[],\"blocked\":[]},"
                + "\"nextSteps\":[\"chart the two candidates\"]}";
    }

    private static ConversationCheckpoint.Source source() {
        return new ConversationCheckpoint.Source(CID, AGENT, AMID);
    }

    /** system + two historical pairs + current turn, sized so the trim drops the oldest pair. */
    private static List<ChatMessage> conversation() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.user("u".repeat(1_000)));
        m.add(ChatMessage.assistant("a".repeat(1_000)));
        m.add(ChatMessage.user("v".repeat(1_000)));
        m.add(ChatMessage.assistant("b".repeat(1_000)));
        m.add(ChatMessage.user("last"));
        m.add(ChatMessage.assistant("final"));
        return m;
    }

    private static ConversationCompactionBoundarySelector.BoundaryPlan eligiblePlan() {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(conversation(), 3_000, true, CID);
        assertTrue(plan.isCheckpointEligible(), "fixture precondition: the plan must be eligible");
        return plan;
    }

    private static ConversationCheckpointGenerator.Result generate(RecordingClient client) {
        return ConversationCheckpointGenerator.generate(eligiblePlan(), client, CONTEXT_MAX, null, null,
                source(), "azure", "gpt-x", "2026-08-24T00:00:00Z", null, null);
    }

    // --- §8.2 the fixed request ---------------------------------------------------------------------

    @Test
    void issuesExactlyOneCallWithTheFixedRequest() {
        RecordingClient client = RecordingClient.replying(goodReply());
        ConversationCheckpointGenerator.Result result = generate(client);

        assertTrue(result.isCreated());
        assertEquals(1, client.requests.size(), "§8.2 permits exactly one direct call");
        assertEquals(1, result.summaryCalls());

        LlmChatRequest request = client.requests.get(0);
        assertTrue(request.getTools().isEmpty(), "no business tools are exposed to the summary");
        assertTrue(request.isToolChoiceNone());
        assertEquals(0.0d, request.getTemperature());
        assertEquals(2048L, request.getRequestedMaxOutputTokens());
        assertNull(request.getModelOverride(), "the Provider bridge owns model resolution");
        assertNull(request.getReasoningEffort());
        assertFalse(request.isEnableCacheControl());
        assertNull(request.getRateControlStatusSink());
        assertFalse(request.isProbeMode());
        // §8.2 pins usageWireIdsForEffectiveModel(null): the Provider bridge substitutes the effective model, and
        // the default interface implementation leaves it blank rather than guessing one.
        assertNotNull(request.getUsageWireIdsOverride());
        assertEquals("openai-chat-completions-v1", request.getUsageWireIdsOverride().getApiShapeId());
        assertEquals("", request.getUsageWireIdsOverride().getModel());
    }

    @Test
    void sendsOnlyAFixedSystemInstructionAndAServerRenderedPayload() {
        RecordingClient client = RecordingClient.replying(goodReply());
        generate(client);

        List<ChatMessage> sent = client.requests.get(0).getMessages();
        assertEquals(2, sent.size());
        assertEquals(ChatMessage.Role.SYSTEM, sent.get(0).getRole());
        assertEquals(ChatMessage.Role.USER, sent.get(1).getRole());
        // The turn's own stable prompt must not ride along.
        assertFalse(sent.get(0).getContent().contains("sys"));
        assertFalse(sent.get(1).getContent().contains("\nsystem:"));
        // The covered prefix does appear, since that is what the summary is about.
        assertTrue(sent.get(1).getContent().contains("u".repeat(1_000)));
    }

    @Test
    void theProviderBudgetIsQueriedForThisRequestsOutputSize() {
        RecordingClient client = RecordingClient.replying(goodReply());
        generate(client);
        assertEquals(2048L, client.lastRequestedMaxOutputTokens);
        assertNull(client.lastModelOverride);
    }

    // --- §8.2 input bounding ------------------------------------------------------------------------

    @Test
    void aProviderCapTighterThanTheCodeCapWins() {
        RecordingClient generous = new RecordingClient(
                new LlmResponse(goodReply(), List.of(), LlmResponse.FinishReason.STOP, 1, 1), null, 0L);
        assertEquals(ConversationCheckpointGenerator.MAX_SUMMARY_INPUT_CHARS,
                ConversationCheckpointGenerator.effectiveSummaryInputCap(generous, CONTEXT_MAX));

        RecordingClient tight = new RecordingClient(
                new LlmResponse(goodReply(), List.of(), LlmResponse.FinishReason.STOP, 1, 1), null, 5_000L);
        assertEquals(5_000, ConversationCheckpointGenerator.effectiveSummaryInputCap(tight, CONTEXT_MAX));
    }

    @Test
    void anInputThatCannotFitOneCompleteTurnSkipsBeforeTheCall() {
        RecordingClient client = RecordingClient.replying(goodReply());
        ConversationCheckpointGenerator.Result result = ConversationCheckpointGenerator.generate(
                eligiblePlan(), client, 900, null, null, source(), "azure", "gpt-x", "t", null, null);

        assertEquals(ConversationCheckpointGenerator.Outcome.SUMMARY_INPUT_TOO_LARGE, result.outcome());
        assertTrue(client.requests.isEmpty(), "no model call may be made when the input cannot fit");
        assertEquals(0L, result.summaryDurationMs(), "no call began, so the duration is genuinely zero");
        assertEquals(0, result.summaryCalls());
    }

    @Test
    void selectionTakesTheOldestCompleteTurnPrefixAndNeverCutsARow() {
        List<ChatMessage> prefix = new ArrayList<>();
        prefix.add(ChatMessage.user("older question"));
        prefix.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        prefix.add(ChatMessage.toolResult("t1", "{\"ok\":1}"));
        prefix.add(ChatMessage.assistant("older answer"));
        prefix.add(ChatMessage.user("newer question"));
        prefix.add(ChatMessage.assistant("newer answer"));

        // Generous cap: the whole covered span is selected.
        assertEquals(6, ConversationCheckpointGenerator
                .selectOldestCompleteTurnPrefixEnd(prefix, null, 100_000));

        // Tight cap: §8.2 takes the OLDEST complete-turn prefix, so the first turn survives whole — the tool batch
        // is included with its owning assistant rather than split. Sized from the full-span cap so the fixture
        // cannot drift: just under what both turns need.
        int bothTurns = smallestCapThatFits(prefix, prefix.size());
        int turnOneOnly = smallestCapThatFits(prefix, 4);
        assertTrue(turnOneOnly < bothTurns, "fixture precondition: turn two adds real weight");
        int end = ConversationCheckpointGenerator.selectOldestCompleteTurnPrefixEnd(prefix, null, bothTurns - 1);
        assertEquals(4, end, "the boundary falls on the next user row, keeping turn one intact");

        // Nothing fits at all.
        assertEquals(-1, ConversationCheckpointGenerator.selectOldestCompleteTurnPrefixEnd(
                prefix, null, ConversationCheckpointGenerator.SYSTEM_INSTRUCTION.length() + 1));
    }

    /** Smallest cap at which {@code selectOldestCompleteTurnPrefixEnd} reaches {@code expectedEnd}, exactly. */
    private static int smallestCapThatFits(List<ChatMessage> prefix, int expectedEnd) {
        for (int cap = ConversationCheckpointGenerator.SYSTEM_INSTRUCTION.length(); cap < 200_000; cap++) {
            if (ConversationCheckpointGenerator.selectOldestCompleteTurnPrefixEnd(prefix, null, cap)
                    >= expectedEnd) {
                return cap;
            }
        }
        throw new IllegalStateException("no cap fits " + expectedEnd);
    }

    @Test
    void everyCoveredRowIsRepresentedExactlyOnceAcrossSummaryAndRetainedRows() {
        // The cap excludes the newer covered turn, so it must survive as exact retained rows rather than being
        // summarized away — otherwise installing the checkpoint would drop it entirely.
        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system("sys"));
        conversation.add(ChatMessage.user("turn one question"));
        conversation.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        conversation.add(ChatMessage.toolResult("t1", "{\"raw\":1}", "query_entities"));
        conversation.add(ChatMessage.assistant("turn one answer " + "o".repeat(600)));
        conversation.add(ChatMessage.user("turn two question " + "p".repeat(600)));
        conversation.add(ChatMessage.assistant("turn two answer " + "q".repeat(600)));
        conversation.add(ChatMessage.user("current"));
        conversation.add(ChatMessage.assistant("current answer"));

        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(conversation, 1_200, true, CID);
        assertTrue(plan.isCheckpointEligible());
        long coveredUserRows = plan.coveredPrefix().stream()
                .filter(m -> m.getRole() == ChatMessage.Role.USER).count();
        assertTrue(coveredUserRows >= 2, "fixture precondition: the covered span holds more than one turn");

        // A provider budget that admits only the oldest covered turn, so the rest must survive as exact rows.
        int turnOneOnly = smallestCapThatFits(plan.coveredPrefix(), 1);
        RecordingClient client = new RecordingClient(
                new LlmResponse(goodReply(), List.of(), LlmResponse.FinishReason.STOP, 1, 1), null, turnOneOnly);
        ConversationCheckpointGenerator.Result result = ConversationCheckpointGenerator.generate(
                plan, client, CONTEXT_MAX, null, null, source(), "azure", "gpt-x", "t", null, null);

        assertTrue(result.isCreated(), "a partial selection must still produce a checkpoint");
        String payload = client.requests.get(0).getMessages().get(1).getContent();
        List<String> tailContents = new ArrayList<>();
        for (ConversationCheckpoint.RetainedRow r : result.checkpoint().retainedTail()) {
            tailContents.add(r.content());
        }
        boolean someCoveredRowWasRetained = plan.coveredPrefix().stream()
                .anyMatch(m -> tailContents.contains(m.getContent()));
        assertTrue(someCoveredRowWasRetained,
                "fixture precondition: the cap really did exclude part of the covered span");
        for (ChatMessage covered : plan.coveredPrefix()) {
            if (covered.hasToolCalls() || covered.getRole() == ChatMessage.Role.TOOL) {
                continue;
            }
            boolean summarized = payload.contains(covered.getContent());
            boolean retained = tailContents.contains(covered.getContent());
            assertTrue(summarized || retained,
                    "covered row represented by neither the summary nor the tail: "
                            + covered.getContent().substring(0, Math.min(30, covered.getContent().length())));
        }
    }

    @Test
    void aPriorSemanticStateCountsAgainstTheCapAndIsSentForUpdate() {
        ConversationCheckpointSemantic prior = new ConversationCheckpointSemantic(
                "earlier goal", List.of("keep metric units"), List.of("surveyed"), List.of(), List.of(),
                List.of(), List.of(), List.of());
        RecordingClient client = RecordingClient.replying(goodReply());

        ConversationCheckpointGenerator.Result result = ConversationCheckpointGenerator.generate(
                eligiblePlan(), client, CONTEXT_MAX, prior, null, source(), "azure", "gpt-x", "t", null, null);

        assertTrue(result.isCreated());
        String payload = client.requests.get(0).getMessages().get(1).getContent();
        assertTrue(payload.contains("earlier goal"), "§6.3: the model updates prior state rather than restarting");
        assertTrue(payload.contains("keep metric units"));
    }

    // --- §8.4 failure never costs the answer --------------------------------------------------------

    @Test
    void aProviderFailureIsReportedWithTheLatencyItCost() {
        RecordingClient client = RecordingClient.failing(new RuntimeException("read timed out"));
        ConversationCheckpointGenerator.Result result = generate(client);

        assertEquals(ConversationCheckpointGenerator.Outcome.MODEL_ERROR, result.outcome());
        assertNull(result.checkpoint());
        assertEquals(1, client.requests.size(), "§8.2 forbids a retry after a failed summary call");
        assertEquals(1, result.summaryCalls());
    }

    @Test
    void aNullResponseIsAModelErrorNotACheckpoint() {
        RecordingClient client = new RecordingClient(null, null, 0L);
        assertEquals(ConversationCheckpointGenerator.Outcome.MODEL_ERROR, generate(client).outcome());
    }

    @Test
    void unparseableAndProtectedRepliesAreReportedDistinctly() {
        assertEquals(ConversationCheckpointGenerator.Outcome.INVALID_JSON,
                generate(RecordingClient.replying("I cannot do that.")).outcome());
        assertEquals(ConversationCheckpointGenerator.Outcome.INVALID_JSON,
                generate(RecordingClient.replying("{\"constraints\":[\"no goal\"]}")).outcome());
        assertEquals(ConversationCheckpointGenerator.Outcome.PROTECTED_VALUE,
                generate(RecordingClient.replying(
                        "{\"goal\":\"g\",\"criticalContext\":[\"token=abc123\"]}")).outcome());
    }

    @Test
    void everyPostCallOutcomeReportsTheLatencyTheTurnPaid() {
        // §12: the duration is reported whenever the call began, whatever happened next. Recording zero for a
        // post-call rejection would undercount exactly what the field exists to measure.
        for (ConversationCheckpointGenerator.Result r : List.of(
                generate(RecordingClient.replying(goodReply())),
                generate(RecordingClient.replying("not json")),
                generate(RecordingClient.replying("{\"goal\":\"g\",\"criticalContext\":[\"secret=x\"]}")),
                generate(RecordingClient.failing(new RuntimeException("boom"))))) {
            assertEquals(1, r.summaryCalls(), "each of these began a call");
            assertTrue(r.summaryDurationMs() >= 0L);
        }
    }

    @Test
    void anIneligiblePlanNeverCallsTheModel() {
        RecordingClient client = RecordingClient.replying(goodReply());
        ConversationCompactionBoundarySelector.BoundaryPlan withinCap =
                ConversationCompactionBoundarySelector.plan(conversation(), 1_000_000, true, CID);
        assertFalse(withinCap.isCheckpointEligible());

        ConversationCheckpointGenerator.Result result = ConversationCheckpointGenerator.generate(
                withinCap, client, CONTEXT_MAX, null, null, source(), "azure", "gpt-x", "t", null, null);

        assertEquals(ConversationCheckpointGenerator.Outcome.NO_SAFE_BOUNDARY, result.outcome());
        assertTrue(client.requests.isEmpty());
    }

    @Test
    void incompleteIdentityNeverCallsTheModel() {
        RecordingClient client = RecordingClient.replying(goodReply());
        ConversationCheckpointGenerator.Result result = ConversationCheckpointGenerator.generate(
                eligiblePlan(), client, CONTEXT_MAX, null, null,
                new ConversationCheckpoint.Source(CID, AGENT, ""), "azure", "gpt-x", "t", null, null);

        assertEquals(ConversationCheckpointGenerator.Outcome.NO_SAFE_BOUNDARY, result.outcome());
        assertTrue(client.requests.isEmpty(), "a checkpoint that could never be persisted must not cost a call");
    }

    // --- only admitted evidence reaches the model -------------------------------

    private static List<ChatMessage> conversationWithToolBody(String body) {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.user("q".repeat(600)));
        m.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        m.add(ChatMessage.toolResult("t1", body, "query_entities"));
        m.add(ChatMessage.assistant("a".repeat(600)));
        m.add(ChatMessage.user("last"));
        m.add(ChatMessage.assistant("final"));
        return m;
    }

    private static String payloadForToolBody(String body) {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(conversationWithToolBody(body), 1_200, true, CID);
        assertTrue(plan.isCheckpointEligible(), "fixture precondition: eligible plan");
        RecordingClient client = RecordingClient.replying(goodReply());
        ConversationCheckpointGenerator.generate(plan, client, CONTEXT_MAX, null, null, source(),
                "azure", "gpt-x", "t", null, null);
        assertFalse(client.requests.isEmpty(), "fixture precondition: a call was made");
        return client.requests.get(0).getMessages().get(1).getContent();
    }

    @Test
    void unadmittedToolBodiesNeverReachTheModel() {
        // Being post-egress is not admission: Tier B leaves unknown formats, generic JSON, error shells and
        // non-promotable bodies unchanged, so a tool row here may still be raw.
        assertFalse(payloadForToolBody("{\"secret\":\"generic json\"}").contains("generic json"));
        assertFalse(payloadForToolBody("{\"$format\":\"parler.unknown.v1\",\"x\":\"unknown fmt\"}")
                .contains("unknown fmt"));
        assertFalse(payloadForToolBody("{\"status\":\"error\",\"code\":\"BOOM\",\"detail\":\"error shell\"}")
                .contains("error shell"));
        String passworded = "{\"status\":\"success\",\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"resultKind\":\"INFOTABLE\","
                + "\"columns\":[{\"name\":\"pw\",\"baseType\":\"PASSWORD\"}],\"rows\":[[\"leaked\"]]}";
        assertFalse(payloadForToolBody(passworded).contains("leaked"));
    }

    @Test
    void overCapEvidenceMetadataIsRejectedByTheRequestExactlyAsByTheManifest() {
        // build() rejects over-cap identity and metadata via toRef; the request predicate must agree, or a
        // producer-impossible body that mints no ref would still be copied into the model input.
        String huge = "k".repeat(ConversationCheckpointCodec.MAX_ITEM_CHARS + 1);
        String overCapCacheId = "{\"status\":\"success\",\"$format\":\""
                + LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1 + "\",\"resultKind\":\"tabular\","
                + "\"cacheId\":\"" + huge + "\",\"rowCount\":1,"
                + "\"columns\":[{\"name\":\"a\",\"baseType\":\"STRING\"}]}";

        List<ChatMessage> span = new ArrayList<>();
        span.add(ChatMessage.user("q"));
        span.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        span.add(ChatMessage.toolResult("t1", overCapCacheId, "query_entities"));
        span.add(ChatMessage.assistant("a"));

        assertTrue(ConversationCheckpointEvidenceManifest.build(span, null).isEmpty(),
                "precondition: the manifest refuses this body");
        assertFalse(ConversationCheckpointEvidenceManifest.isAdmittedEvidenceRow(span, 2),
                "the request predicate must refuse exactly what the manifest refuses");
        assertFalse(ConversationCheckpointGenerator.renderPayload(null, span).contains(huge));

        // Same parity for over-cap execution identity.
        String overCapId = "i".repeat(ConversationCheckpointCodec.MAX_ITEM_CHARS + 1);
        String body = "{\"status\":\"success\",\"$format\":\"" + LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1
                + "\",\"resultKind\":\"tabular\",\"cacheId\":\"CACHE-ID\",\"rowCount\":1,"
                + "\"columns\":[{\"name\":\"a\",\"baseType\":\"STRING\"}]}";
        List<ChatMessage> longId = new ArrayList<>();
        longId.add(ChatMessage.user("q"));
        longId.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall(overCapId, "query_entities", "{}"))));
        longId.add(ChatMessage.toolResult(overCapId, body, "query_entities"));
        longId.add(ChatMessage.assistant("a"));

        assertTrue(ConversationCheckpointEvidenceManifest.build(longId, null).isEmpty());
        assertFalse(ConversationCheckpointEvidenceManifest.isAdmittedEvidenceRow(longId, 2));
        assertFalse(ConversationCheckpointGenerator.renderPayload(null, longId).contains("CACHE-ID"));
    }

    @Test
    void admittedCompactEvidenceDoesReachTheModel() {
        // The accepted control: without it, the gate would be indistinguishable from excluding all tool rows.
        String summary = "{\"status\":\"success\",\"$format\":\"" + LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1
                + "\",\"resultKind\":\"tabular\",\"cacheId\":\"cache-ok\",\"rowCount\":3,"
                + "\"columns\":[{\"name\":\"a\",\"baseType\":\"STRING\"}]}";
        assertTrue(payloadForToolBody(summary).contains("cache-ok"));
    }

    // --- the unselected suffix must survive tail caps by identity ----------------

    /**
     * Production passes one {@code llmContextMaxChars} to both the plan and the generator, so these fixtures do
     * too; a partial summary prefix is forced with the provider budget, which is an independent knob.
     */
    private static ConversationCheckpointGenerator.Result generateWithSameCap(
            List<ChatMessage> conversation, int storageCap, String reply, boolean forcePartialPrefix) {
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(conversation, storageCap, true, CID);
        assertTrue(plan.isCheckpointEligible(), "fixture precondition: eligible");
        long providerCap = forcePartialPrefix ? smallestCapThatFits(plan.coveredPrefix(), 1) : 0L;
        if (forcePartialPrefix) {
            assertTrue(ConversationCheckpointGenerator.selectOldestCompleteTurnPrefixEnd(
                    plan.coveredPrefix(), null, (int) providerCap) < plan.coveredPrefix().size(),
                    "fixture precondition: the selection really is partial");
        }
        RecordingClient client = new RecordingClient(
                new LlmResponse(reply, List.of(), LlmResponse.FinishReason.STOP, 3, 5), null, providerCap);
        return ConversationCheckpointGenerator.generate(plan, client, storageCap, null, null, source(),
                "azure", "gpt-x", "t", null, null);
    }

    @Test
    void anUnselectedSuffixEvictedByTheTailCapFailsSoftEvenWhenLaterRowsLookIdentical() {
        // The unselected suffix and the head of the plan tail carry identical values, so a role/content/provenance
        // comparison after capping is satisfied by the survivor while one occurrence is gone. Retained rows have no
        // ids; only "nothing was evicted" is an exact proof.
        // The storage cap must clear the fixed instruction, so realistic sizes: turn one is distinct, turns two and
        // three are byte-identical, and turn two is what the summary cap excludes.
        String dupU = "dup-u " + "x".repeat(3_000);
        String dupA = "dup-a " + "y".repeat(3_000);
        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system("sys"));
        conversation.add(ChatMessage.user("first-u " + "n".repeat(3_000)));
        conversation.add(ChatMessage.assistant("first-a " + "m".repeat(3_000)));
        conversation.add(ChatMessage.user(dupU));
        conversation.add(ChatMessage.assistant(dupA));
        conversation.add(ChatMessage.user(dupU));
        conversation.add(ChatMessage.assistant(dupA));
        for (int i = 0; i < 50; i++) {
            conversation.add(ChatMessage.user("k" + i));
            conversation.add(ChatMessage.assistant("v" + i));
        }
        conversation.add(ChatMessage.user("current"));
        conversation.add(ChatMessage.assistant("current answer"));

        int storageCap = 10_000;
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(conversation, storageCap, true, CID);
        assertTrue(plan.isCheckpointEligible(), "fixture precondition: eligible");
        assertEquals(4, plan.coveredPrefix().size(), "fixture precondition: two covered turns");
        assertEquals(dupU, plan.retainedTail().get(0).getContent(),
                "fixture precondition: the plan tail begins with the same values as the excluded turn");
        assertTrue(ConversationCheckpointCodec.projectRetainedRows(plan.retainedTail()).size() + 2
                        > ConversationCheckpointCodec.MAX_RETAINED_TAIL_MESSAGES,
                "fixture precondition: adding the suffix pushes the tail past the message cap");

        ConversationCheckpointGenerator.Result result =
                generateWithSameCap(conversation, storageCap, goodReply(), true);

        assertEquals(ConversationCheckpointGenerator.Outcome.NO_SHRINK, result.outcome(),
                "an evicted suffix must not be excused by an equal-looking later pair");
        assertEquals(1, result.summaryCalls(), "a post-call failure still reports its call");
    }

    // --- admission is per row, not per tool-call id ------------------------------

    @Test
    void theRefCapDoesNotDecideWhichRowsTheSummaryMaySee() {
        // The envelope's 64-ref cap bounds what is persisted; it must not cut validated rows out of the request.
        String accepted = "{\"status\":\"success\",\"$format\":\"" + LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1
                + "\",\"resultKind\":\"tabular\",\"cacheId\":\"CACHE-%d\",\"rowCount\":1,"
                + "\"columns\":[{\"name\":\"a\",\"baseType\":\"STRING\"}]}";

        List<ChatMessage> span = new ArrayList<>();
        span.add(ChatMessage.user("one turn with many batches"));
        int batches = ConversationCheckpointEvidenceManifest.MAX_REFS + 5;
        for (int i = 0; i < batches; i++) {
            span.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("c" + i, "query_entities", "{}"))));
            span.add(ChatMessage.toolResult("c" + i, String.format(accepted, i), "query_entities"));
        }
        span.add(ChatMessage.assistant("done"));

        String payload = ConversationCheckpointGenerator.renderPayload(null, span);
        assertTrue(payload.contains("CACHE-0"));
        assertTrue(payload.contains("CACHE-" + (batches - 1)),
                "an admitted row past the ref cap must still be describable");
    }

    @Test
    void aReusedToolCallIdCannotSmuggleARawBodyIntoTheRequest() {
        // Tool-call ids are unique only inside one assistant batch. An id set built from the accepted batch would
        // admit the raw body of a later batch that reuses the id.
        String accepted = "{\"status\":\"success\",\"$format\":\"" + LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1
                + "\",\"resultKind\":\"tabular\",\"cacheId\":\"CACHE-OK\",\"rowCount\":3,"
                + "\"columns\":[{\"name\":\"a\",\"baseType\":\"STRING\"}]}";

        List<ChatMessage> span = new ArrayList<>();
        span.add(ChatMessage.user("batch one"));
        span.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        span.add(ChatMessage.toolResult("t1", accepted, "query_entities"));
        span.add(ChatMessage.assistant("answer one"));
        span.add(ChatMessage.user("batch two"));
        span.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        span.add(ChatMessage.toolResult("t1", "{\"unmarked\":\"RAW-SECRET-BODY\"}", "query_entities"));
        span.add(ChatMessage.assistant("answer two"));

        String payload = ConversationCheckpointGenerator.renderPayload(null, span);
        assertTrue(payload.contains("CACHE-OK"), "the accepted body is the control");
        assertFalse(payload.contains("RAW-SECRET-BODY"),
                "a later batch reusing an accepted id must not ride in on that id");
    }

    // --- the shrink gate has both negative branches ------------------------------

    @Test
    void theSizeGateRejectsUnderTheProductionSameCapComposition() {
        // One storage cap for both the plan and the generator, as unit 3b will pass it.
        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system("s"));
        conversation.add(ChatMessage.user("a"));
        conversation.add(ChatMessage.assistant("b"));
        for (int i = 0; i < 12; i++) {
            conversation.add(ChatMessage.user("keep u" + i + " " + "k".repeat(394)));
            conversation.add(ChatMessage.assistant("keep a" + i + " " + "m".repeat(394)));
        }
        conversation.add(ChatMessage.user("last"));
        conversation.add(ChatMessage.assistant("final"));

        // A verbose semantic state costs more than the headroom the trim left, so the published rows cannot meet
        // the same storage cap the plan used.
        String verbose = "{\"goal\":\"" + "g".repeat(500) + "\",\"constraints\":[\"" + "c".repeat(300)
                + "\"],\"nextSteps\":[\"" + "n".repeat(300) + "\"]}";
        ConversationCheckpointGenerator.Result result =
                generateWithSameCap(conversation, 9_000, verbose, false);

        assertEquals(ConversationCheckpointGenerator.Outcome.NO_SHRINK, result.outcome(),
                "a working set already at its cap has no room for a large checkpoint row");
        assertEquals(1, result.summaryCalls(), "the call happened and is still reported");
        assertEquals(3, result.promptTokens());
        assertEquals(5, result.completionTokens());
        assertTrue(result.summaryDurationMs() >= 0L, "post-call latency is preserved");
    }

    @Test
    void bothSizeConditionsAreCheckedIndependently() {
        // The two conditions cannot both be exercised end to end under one storage cap — growth implies exceeding
        // the cap once the working set already exceeded it — so each predicate branch is driven directly.
        List<ChatMessage> conversation = conversation();
        ConversationCompactionBoundarySelector.BoundaryPlan plan =
                ConversationCompactionBoundarySelector.plan(conversation, 3_000, true, CID);
        assertTrue(plan.isCheckpointEligible());
        List<ChatMessage> selected = new ArrayList<>(plan.coveredPrefix());

        ConversationCheckpoint small = new ConversationCheckpoint(source(),
                new ConversationCheckpointSemantic("g", List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of()),
                List.of(), ConversationCheckpointCodec.acceptRetainedTail(plan.retainedTail()),
                new ConversationCheckpoint.Generated("t", "p", "m"));
        assertTrue(ConversationCheckpointGenerator.materializedWorkingSetShrinks(
                plan, small, selected, List.of(), CONTEXT_MAX), "control: a small checkpoint shrinks and fits");

        // Grows: the semantic state costs more than the span it replaces.
        ConversationCheckpoint huge = new ConversationCheckpoint(source(),
                new ConversationCheckpointSemantic("g".repeat(600),
                        List.of("c".repeat(300), "d".repeat(300)), List.of("e".repeat(300)), List.of(), List.of(),
                        List.of(), List.of("f".repeat(300)), List.of("h".repeat(300))),
                List.of(), ConversationCheckpointCodec.acceptRetainedTail(plan.retainedTail()),
                new ConversationCheckpoint.Generated("t", "p", "m"));
        assertFalse(ConversationCheckpointGenerator.materializedWorkingSetShrinks(
                plan, huge, List.of(ChatMessage.user("x")), List.of(), CONTEXT_MAX),
                "a checkpoint larger than the span it replaces must be refused");

        // Over cap: the same shrinking checkpoint against a storage cap the published rows cannot meet.
        assertFalse(ConversationCheckpointGenerator.materializedWorkingSetShrinks(
                plan, small, selected, List.of(), 10),
                "published rows must fit the storage cap");
    }

    // --- §6.2 the server assembles everything the model does not own --------------------------------

    @Test
    void identityAndGenerationMetadataAreServerWritten() {
        ConversationCheckpointGenerator.Result result = generate(RecordingClient.replying(
                "{\"goal\":\"legit\",\"source\":{\"conversationId\":\"attacker\"}}"));

        assertTrue(result.isCreated());
        ConversationCheckpoint c = result.checkpoint();
        assertEquals(CID, c.source().conversationId());
        assertEquals(AGENT, c.source().agentThing());
        assertEquals(AMID, c.source().throughAssistantMessageId());
        assertEquals("azure", c.generated().provider());
        assertEquals("gpt-x", c.generated().model());
        assertEquals("2026-08-24T00:00:00Z", c.generated().at());
    }

    @Test
    void theRetainedTailIsTakenFromThePlanNotTheModel() {
        ConversationCheckpointGenerator.Result result = generate(RecordingClient.replying(goodReply()));
        List<ConversationCheckpoint.RetainedRow> tail = result.checkpoint().retainedTail();

        // The cutoff is the newest removed transcript row, so the tail is everything surviving after it: the
        // second historical turn plus the current one.
        assertFalse(tail.isEmpty());
        assertEquals(ConversationCheckpoint.RetainedRow.ROLE_USER, tail.get(0).role());
        assertEquals("v".repeat(1_000), tail.get(0).content());
        assertEquals("final", tail.get(tail.size() - 1).content());
    }

    @Test
    void aCreatedCheckpointIsAlwaysSerializable() {
        ConversationCheckpointGenerator.Result result = generate(RecordingClient.replying(goodReply()));
        assertNotNull(ConversationCheckpointCodec.serialize(result.checkpoint()),
                "CREATED must never name a checkpoint the codec would refuse");
    }

    @Test
    void carriedForwardRefsAreRevalidatedAndDeduplicatedAgainstFreshOnes() {
        ConversationCheckpoint.EvidenceRef prior = new ConversationCheckpoint.EvidenceRef(
                "call-old", "query_entities", ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY,
                "tabular", "cache-old", "complete", false, "live", "query_entities");
        ConversationCheckpoint.EvidenceRef forged = new ConversationCheckpoint.EvidenceRef(
                "call-forged", "query_entities", "not-a-family", "tabular", "c", "complete", false, "live",
                "query_entities");

        ConversationCheckpointGenerator.Result result = ConversationCheckpointGenerator.generate(
                eligiblePlan(), RecordingClient.replying(goodReply()), CONTEXT_MAX, null,
                List.of(prior, forged), source(), "azure", "gpt-x", "t", null, null);

        assertTrue(result.isCreated());
        List<ConversationCheckpoint.EvidenceRef> refs = result.checkpoint().evidenceRefs();
        assertEquals(1, refs.size(), "the forged family is dropped, the valid prior ref carries forward");
        assertEquals("call-old", refs.get(0).toolCallId());
        assertEquals(ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE, refs.get(0).liveness(),
                "a persisted live never survives without a current-JVM lookup");
    }
}
