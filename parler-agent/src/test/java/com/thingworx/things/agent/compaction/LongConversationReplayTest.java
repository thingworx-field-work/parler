package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.AgentLoopTestFixtures;
import com.thingworx.things.agent.compaction.ConversationCheckpointCodec;
import com.thingworx.things.agent.compaction.ConversationCheckpointGenerator;
import com.thingworx.things.agent.compaction.ConversationCheckpointPersistence;
import com.thingworx.things.agent.compaction.ConversationCheckpointWorkingSet;
import com.thingworx.things.agent.compaction.ConversationsReplayNormalization;
import com.thingworx.things.agent.compaction.ConversationsStorageBudgetTrimmer;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.AnthropicMessagesApi;
import com.thingworx.things.agent.llm.ChatCompletionsApi;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ParlerSuffixFraming;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * CC-8 long multi-round replay baseline: normal append with history markers, drop-only trimming,
 * tool-face changes, and structured projection stability on both provider wires.
 */
class LongConversationReplayTest {

    private static final String STABLE_SYSTEM = "stable-system-anchor-for-cc8";
    private static final String MATRIX_BODY = CompactionTestFixtures.largeCacheSampleMatrixToolJson();
    private static final List<ToolDefinition> MAIN_LOOP_TOOLS = List.of(
            new ToolDefinition("tabulate_cached_result", "Tabulate cached matrix", Map.of("type", "object")),
            new ToolDefinition("invoke_service", "Invoke platform service", Map.of("type", "object")));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TOOL_ROWS_PER_TURN = 3;
    private static final int TOOL_RESULT_ENVELOPE_OVERHEAD = 40;
    private static final int TOOL_CALL_ENVELOPE_OVERHEAD = 80;
    private static final String TABULATE_ARGS = "{\"cacheId\":\"tabular-cache-abc\"}";
    private static final int EXPECTED_EVIDENCE_CHARS_PER_TURN = pinnedEvidenceCharsForTurn(99);
    private static final int PINNED_FIRST_DROP_TURN_INDEX = 1;
    private static final int PINNED_DROP_ONLY_DROPPED_ROWS = 13;
    private static final String PINNED_LATEST_FRONTIER_KEY = "58:0";
    private static final String PINNED_SECOND_LATEST_FRONTIER_KEY = "56:1";
    private static final String PINNED_THIRD_LATEST_FRONTIER_KEY = "54:0";
    private static final String CHECKPOINT_GOAL = "compare pump throughput across candidate lines";
    private static final LlmUsageWireIds OPENAI_IDS =
            LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
    private static final LlmUsageWireIds ANTHROPIC_IDS =
            LlmUsageWireIds.forProviderThing("P", "Anthropic", "anthropic-messages-v1", "claude-sonnet-4-20250514");

    @BeforeEach
    void installCheckpointPersistenceStub() {
        ConversationCheckpointPersistence.setAppenderForTest((key, source, agentThing, envelope) -> true);
    }

    @AfterEach
    void clearAgentToolContext() {
        AgentToolContext.clear();
        ConversationCheckpointWorkingSet.clearConversation("AgentThing", "cid-checkpoint");
        ConversationCheckpointPersistence.clearAllTestHooks();
    }

    @Test
    void normalAppend_tenTurnChain_withHistoryMarkers_keepsStablePrefixAndPartitionHashes() {
        String toolsHashOpenAi = null;
        String toolsHashAnthropic = null;
        String systemHashOpenAi = null;
        String systemHashAnthropic = null;
        List<Map<String, Object>> previousOpenAiProjection = null;
        List<Map<String, Object>> previousAnthropicProjection = null;

        for (int completedTurns = 1; completedTurns <= 10; completedTurns++) {
            WireSnapshot snapshot = snapshotAfterPlanning(buildPlanningMessages(completedTurns), 750_000, 0L);

            if (toolsHashOpenAi == null) {
                toolsHashOpenAi = snapshot.openAiToolsHash();
                toolsHashAnthropic = snapshot.anthropicToolsHash();
                systemHashOpenAi = snapshot.openAiSystemHash();
                systemHashAnthropic = snapshot.anthropicSystemHash();
            } else {
                assertEquals(toolsHashOpenAi, snapshot.openAiToolsHash(), "OpenAI tools partition at turn " + completedTurns);
                assertEquals(toolsHashAnthropic, snapshot.anthropicToolsHash(),
                        "Anthropic tools partition at turn " + completedTurns);
                assertEquals(systemHashOpenAi, snapshot.openAiSystemHash(), "OpenAI system partition at turn " + completedTurns);
                assertEquals(systemHashAnthropic, snapshot.anthropicSystemHash(),
                        "Anthropic system partition at turn " + completedTurns);
            }

            if (previousOpenAiProjection != null) {
                int openPrefix = WirePartitionHasher.commonPrefixLength(previousOpenAiProjection, snapshot.openAiMessages());
                int anthropicPrefix = WirePartitionHasher.commonPrefixLength(
                        previousAnthropicProjection, snapshot.anthropicMessages());
                assertTrue(openPrefix >= previousOpenAiProjection.size() - 2,
                        "OpenAI projection prefix should remain stable except volatile tail at turn " + completedTurns);
                assertTrue(anthropicPrefix >= previousAnthropicProjection.size() - 2,
                        "Anthropic projection prefix should remain stable except volatile tail at turn " + completedTurns);
                assertEquals(previousOpenAiProjection.subList(0, openPrefix), snapshot.openAiMessages().subList(0, openPrefix),
                        "OpenAI shared prefix must be identical at turn " + completedTurns);
                assertEquals(previousAnthropicProjection.subList(0, anthropicPrefix),
                        snapshot.anthropicMessages().subList(0, anthropicPrefix),
                        "Anthropic shared prefix must be identical at turn " + completedTurns);
            }

            assertHistoryMarkerRules(snapshot.anthropicWire(), completedTurns);
            if (completedTurns == 10) {
                assertPinnedFrontierIndexes(snapshot.anthropicWire());
            }
            previousOpenAiProjection = snapshot.openAiMessages();
            previousAnthropicProjection = snapshot.anthropicMessages();
        }
    }

    @Test
    void dropOnly_lowCap_preservesProjectionBeforeFirstDroppedRow() {
        List<ChatMessage> messages = buildConversationThroughTurn(4);
        messages.add(ChatMessage.user("current-user-drop-only"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("active-call", "invoke_service", "{}"))));
        messages.add(ChatMessage.toolResult("active-call", MATRIX_BODY));

        List<String> storageBefore = snapshotContents(messages);
        ContextBudgetPlanner.PlannedOutbound full = plan(messages, 750_000, 0L);
        ContextBudgetPlanner.PlannedOutbound trimmed = plan(messages, 3_500, 0L);

        assertTrue(trimmed.getPlannedMetrics().droppedEvidence > 0
                        || trimmed.getPlannedMetrics().droppedTranscript > 0,
                "drop-only scenario must drop at least one row");
        assertEquals(storageBefore, snapshotContents(messages), "planning must not mutate stored message bodies");

        int firstChanged = firstMatchingOutboundPrefixLength(
                full.getOutboundMessages(), trimmed.getOutboundMessages());
        assertTrue(firstChanged > 0, "trimmed plan must share outbound prefix with full plan");
        assertEquals(
                buildWireSnapshot(full.getOutboundMessages().subList(0, firstChanged)).openAiMessages(),
                buildWireSnapshot(trimmed.getOutboundMessages().subList(0, firstChanged)).openAiMessages());
        assertEquals(
                buildWireSnapshot(full.getOutboundMessages().subList(0, firstChanged)).anthropicMessages(),
                buildWireSnapshot(trimmed.getOutboundMessages().subList(0, firstChanged)).anthropicMessages());
        assertEquals(PINNED_DROP_ONLY_DROPPED_ROWS,
                trimmed.getPlannedMetrics().droppedEvidence
                        + trimmed.getPlannedMetrics().droppedTranscript
                        + trimmed.getPlannedMetrics().droppedAssistantBatches,
                "pinned drop-only row count");
        assertTrue(trimmed.getOutboundMessages().size() < full.getOutboundMessages().size(),
                "trimmed outbound list must be shorter than full plan");
        int firstDroppedTurn = detectFirstDroppedTurn(full.getOutboundMessages(), trimmed.getOutboundMessages());
        assertEquals(PINNED_FIRST_DROP_TURN_INDEX, firstDroppedTurn,
                "first dropped row must match pinned turn index");
    }

    @Test
    void toolFaceChange_emptyTools_omitsToolsAndToolChoice_onBothWires() {
        List<ChatMessage> messages = buildPlanningMessages(3);
        WireSnapshot mainLoop = buildWireSnapshot(messages, MAIN_LOOP_TOOLS, false);

        List<ToolDefinition> emptyTools = Collections.emptyList();
        LlmChatRequest restricted = LlmChatRequest.copyWithToolPolicy(
                LlmChatRequest.forAgentRound(messages, emptyTools, 0.0, 1024, null), true);
        Map<String, Object> openAi = ChatCompletionsApi.buildRequestBody(
                "gpt-4o", messages, emptyTools, restricted, false, 1024);
        Map<String, Object> anthropic = AnthropicMessagesApi.buildRequestPayload(
                messages, emptyTools, "claude", 0.0, 1024, 0, restricted);

        assertFalse(openAi.containsKey("tools"));
        assertFalse(openAi.containsKey("tool_choice"));
        assertFalse(anthropic.containsKey("tools"));
        assertFalse(anthropic.containsKey("tool_choice"));

        assertTrue(mainLoop.openAiToolsHash().length() > 0);
        assertTrue(mainLoop.anthropicToolsHash().length() > 0);
        assertTrue(StructuredMessageProjection.projectTools(openAi, StructuredMessageProjection.WireKind.CHAT_COMPLETIONS)
                .isEmpty());
        assertTrue(StructuredMessageProjection.projectTools(anthropic, StructuredMessageProjection.WireKind.ANTHROPIC)
                .isEmpty());
        assertFalse(mainLoop.openAiToolsHash().equals(
                WirePartitionHasher.hashTools(openAi, StructuredMessageProjection.WireKind.CHAT_COMPLETIONS)));
        assertFalse(mainLoop.anthropicToolsHash().equals(
                WirePartitionHasher.hashTools(anthropic, StructuredMessageProjection.WireKind.ANTHROPIC)));

        WireSnapshot restrictedSnap = buildWireSnapshot(messages, emptyTools, true);
        assertEquals(mainLoop.openAiMessages(), restrictedSnap.openAiMessages(),
                "messages projection matches main-loop shape for same round");
        assertEquals(mainLoop.anthropicMessages(), restrictedSnap.anthropicMessages(),
                "messages projection matches main-loop shape for same round");
    }

    @Test
    void metricsCompute_tracksPerTurnEvidenceGrowth() {
        int previousEvidence = -1;
        for (int turn = 1; turn <= 10; turn++) {
            List<ChatMessage> messages = buildConversationThroughTurn(turn);
            ContextBudgetPlanner.Metrics metrics = ContextBudgetPlanner.Metrics.compute(
                    messages, MAIN_LOOP_TOOLS, ANTHROPIC_IDS, 750_000);
            if (previousEvidence >= 0) {
                assertTrue(metrics.evidenceRawChars > previousEvidence,
                        "evidenceRawChars must grow each turn");
            }
            assertTrue(metrics.transcriptChars > 0);
            assertTrue(metrics.historyBudgetChars > 0);
            previousEvidence = metrics.evidenceRawChars;
        }
        List<ChatMessage> nine = buildConversationThroughTurn(9);
        List<ChatMessage> ten = buildConversationThroughTurn(10);
        ContextBudgetPlanner.Metrics m9 = ContextBudgetPlanner.Metrics.compute(
                nine, MAIN_LOOP_TOOLS, ANTHROPIC_IDS, 750_000);
        ContextBudgetPlanner.Metrics m10 = ContextBudgetPlanner.Metrics.compute(
                ten, MAIN_LOOP_TOOLS, ANTHROPIC_IDS, 750_000);
        assertEquals(EXPECTED_EVIDENCE_CHARS_PER_TURN, m10.evidenceRawChars - m9.evidenceRawChars,
                "each additional turn must add the fixed evidence increment");
    }

    @ParameterizedTest(name = "turns={0} cap={1} providerCap={2}")
    @CsvSource({
            "10,750000,0",
            "30,750000,0",
            "60,750000,0",
            "10,120000,0",
            "30,120000,0",
            "60,120000,0",
            "10,750000,118622",
            "30,750000,118622",
            "10,120000,118622",
            "30,120000,118622",
            "60,750000,118622",
            "60,120000,118622",
    })
    void budgetMatrix_recordsMetricsForCapCombinations(int turns, int configuredCap, long providerCap) {
        for (int completed = 1; completed <= turns; completed++) {
            ContextBudgetPlanner.Metrics perTurn = ContextBudgetPlanner.Metrics.compute(
                    buildConversationThroughTurn(completed),
                    MAIN_LOOP_TOOLS,
                    ANTHROPIC_IDS,
                    configuredCap,
                    providerCap);
            assertTrue(perTurn.transcriptChars > 0);
            assertTrue(perTurn.evidenceRawChars > 0);
            assertTrue(perTurn.historyBudgetChars > 0);
        }
        List<ChatMessage> fullConversation = buildConversationThroughTurn(turns);
        ContextBudgetPlanner.Metrics rawMetrics = ContextBudgetPlanner.Metrics.compute(
                fullConversation, MAIN_LOOP_TOOLS, ANTHROPIC_IDS, configuredCap, providerCap);
        assertTrue(rawMetrics.evidenceRawChars >= pinnedTotalEvidenceChars(turns));

        ContextBudgetPlanner.PlannedOutbound planned = plan(buildPressurePlanningMessages(turns), configuredCap, providerCap);
        ContextBudgetPlanner.Metrics metrics = planned.getPlannedMetrics();
        BudgetMatrixPins pins = pinnedBudgetMatrix(turns, configuredCap, providerCap);
        assertEquals(pins.droppedTranscript, metrics.droppedTranscript,
                () -> "pinned droppedTranscript for turns=" + turns + " cap=" + configuredCap
                        + " providerCap=" + providerCap);
        assertEquals(pins.droppedEvidence, metrics.droppedEvidence,
                () -> "pinned droppedEvidence for turns=" + turns + " cap=" + configuredCap
                        + " providerCap=" + providerCap);
        assertEquals(pins.droppedAssistantBatches, metrics.droppedAssistantBatches,
                () -> "pinned droppedAssistantBatches for turns=" + turns + " cap=" + configuredCap
                        + " providerCap=" + providerCap);
        assertEquals(pins.effectiveRequestCapChars, metrics.effectiveRequestCapChars,
                () -> "pinned effectiveRequestCapChars for turns=" + turns + " cap=" + configuredCap
                        + " providerCap=" + providerCap);
        assertEquals(pins.historyBudgetChars, metrics.historyBudgetChars,
                () -> "pinned historyBudgetChars for turns=" + turns + " cap=" + configuredCap
                        + " providerCap=" + providerCap);
    }

    private static BudgetMatrixPins pinnedBudgetMatrix(int turns, int configuredCap, long providerCap) {
        if (turns == 10 && configuredCap == 750_000 && providerCap == 0L) {
            return new BudgetMatrixPins(0, 0, 0, 448_000, 447_649);
        }
        if (turns == 30 && configuredCap == 750_000 && providerCap == 0L) {
            return new BudgetMatrixPins(0, 0, 0, 448_000, 447_649);
        }
        if (turns == 60 && configuredCap == 750_000 && providerCap == 0L) {
            return new BudgetMatrixPins(0, 0, 0, 448_000, 447_649);
        }
        if (turns == 10 && configuredCap == 120_000 && providerCap == 0L) {
            return new BudgetMatrixPins(0, 0, 0, 120_000, 119_649);
        }
        if (turns == 30 && configuredCap == 120_000 && providerCap == 0L) {
            return new BudgetMatrixPins(0, 0, 0, 120_000, 119_649);
        }
        if (turns == 60 && configuredCap == 120_000 && providerCap == 0L) {
            return new BudgetMatrixPins(0, 147, 98, 120_000, 119_649);
        }
        if (turns == 10 && configuredCap == 750_000 && providerCap == 118_622L) {
            return new BudgetMatrixPins(0, 0, 0, 118_622, 118_271);
        }
        if (turns == 30 && configuredCap == 750_000 && providerCap == 118_622L) {
            return new BudgetMatrixPins(0, 0, 0, 118_622, 118_271);
        }
        if (turns == 10 && configuredCap == 120_000 && providerCap == 118_622L) {
            return new BudgetMatrixPins(0, 0, 0, 118_622, 118_271);
        }
        if (turns == 30 && configuredCap == 120_000 && providerCap == 118_622L) {
            return new BudgetMatrixPins(0, 0, 0, 118_622, 118_271);
        }
        if (turns == 60 && configuredCap == 750_000 && providerCap == 118_622L) {
            return new BudgetMatrixPins(0, 150, 100, 118_622, 118_271);
        }
        if (turns == 60 && configuredCap == 120_000 && providerCap == 118_622L) {
            return new BudgetMatrixPins(0, 150, 100, 118_622, 118_271);
        }
        throw new IllegalArgumentException("unpinned budget matrix combo");
    }

    private static final class BudgetMatrixPins {
        private final int droppedTranscript;
        private final int droppedEvidence;
        private final int droppedAssistantBatches;
        private final long effectiveRequestCapChars;
        private final long historyBudgetChars;

        private BudgetMatrixPins(
                int droppedTranscript,
                int droppedEvidence,
                int droppedAssistantBatches,
                long effectiveRequestCapChars,
                long historyBudgetChars) {
            this.droppedTranscript = droppedTranscript;
            this.droppedEvidence = droppedEvidence;
            this.droppedAssistantBatches = droppedAssistantBatches;
            this.effectiveRequestCapChars = effectiveRequestCapChars;
            this.historyBudgetChars = historyBudgetChars;
        }
    }

    @Test
    void latestTwoFrontierMarkers_rejectWrongPlacementOnOldestFrontiers() {
        List<ChatMessage> messages = buildConversationThroughTurn(3);
        Map<String, Object> anthropicWire = snapshotAfterPlanning(buildPlanningMessages(3), 750_000, 0L).anthropicWire();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apiMessages = (List<Map<String, Object>>) anthropicWire.get("messages");
        List<String> frontierKeys = eligibleFrontierKeys(apiMessages);
        assertTrue(frontierKeys.size() >= 4, "fixture must expose at least four eligible frontiers");
        clearCacheControls(anthropicWire, apiMessages);
        markSystemCacheControl(anthropicWire);
        markLastToolDefinition(anthropicWire);
        markCacheControlAt(apiMessages, frontierKeys.get(0));
        markCacheControlAt(apiMessages, frontierKeys.get(1));
        assertMarkerPartition(anthropicWire, apiMessages, 1, 1, 2);
        assertEquals(4, countCacheControls(anthropicWire),
                "wrong-placement fixture must preserve four total markers");
        assertThrows(AssertionError.class, () -> assertLatestTwoFrontierMarkerPositions(anthropicWire));
    }

    @Test
    void tierB_storagePressure_promotesMatrixSummary_andChangesBothProjectionsOnce() throws Exception {
        List<ChatMessage> messages = buildConversationThroughTurn(6);
        messages.add(ChatMessage.user("pressure-user"));
        messages.add(ChatMessage.assistant("pressure-answer"));
        int storageCap = ConversationsStorageBudgetTrimmer.sumMessageChars(messages) - 1;

        String openAiBefore = WirePartitionHasher.hashMessages(
                buildWireSnapshot(messages).openAiWire(), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS);
        String anthropicBefore = WirePartitionHasher.hashMessages(
                buildWireSnapshot(messages).anthropicWire(), StructuredMessageProjection.WireKind.ANTHROPIC);

        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                messages,
                AgentLoopTestFixtures.successResult("pressure-answer"),
                true,
                null,
                "cid-tierb",
                "rid-tierb",
                storageCap,
                null,
                "amid-tierb",
                "AgentThing",
                "2026-09-11T00:00:00Z");

        boolean promoted = false;
        for (ChatMessage message : messages) {
            if (message.getRole() != ChatMessage.Role.TOOL || message.getContent() == null) {
                continue;
            }
            JsonNode body = JSON.readTree(message.getContent());
            if ("parler.infotable.summary.v1".equals(body.path("$format").asText())) {
                promoted = true;
                break;
            }
        }
        assertTrue(promoted, "Tier B must replace a matrix tool body with summary.v1");

        String openAiAfter = WirePartitionHasher.hashMessages(
                buildWireSnapshot(messages).openAiWire(), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS);
        String anthropicAfter = WirePartitionHasher.hashMessages(
                buildWireSnapshot(messages).anthropicWire(), StructuredMessageProjection.WireKind.ANTHROPIC);
        assertNotEquals(openAiBefore, openAiAfter);
        assertNotEquals(anthropicBefore, anthropicAfter);

        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                messages,
                AgentLoopTestFixtures.successResult("pressure-answer"),
                true,
                null,
                "cid-tierb",
                "rid-tierb",
                storageCap,
                null,
                "amid-tierb",
                "AgentThing",
                "2026-09-11T00:00:00Z");
        assertEquals(openAiAfter, WirePartitionHasher.hashMessages(
                buildWireSnapshot(messages).openAiWire(), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS));
    }

    @Test
    void checkpointStorageTrim_installsCheckpoint_andChangesProjectionOnce() throws Exception {
        List<ChatMessage> messages = buildCheckpointPressureConversation();

        String projectionBefore = WirePartitionHasher.hashMessages(
                buildWireSnapshot(messages).openAiWire(), StructuredMessageProjection.WireKind.CHAT_COMPLETIONS);

        CheckpointStubClient client = new CheckpointStubClient(CHECKPOINT_GOAL);
        ConversationCheckpointGenerator.Outcome outcome =
                ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                        messages,
                        AgentLoopTestFixtures.successResult("newest-answer"),
                        true,
                        null,
                        "cid-checkpoint",
                        "rid-checkpoint",
                        10_000,
                        client,
                        "amid-checkpoint",
                        "AgentThing",
                        "2026-09-11T00:00:00Z");

        assertEquals(ConversationCheckpointGenerator.Outcome.CREATED, outcome);
        assertEquals(1, client.calls);
        int checkpoints = 0;
        for (ChatMessage message : messages) {
            if (ConversationCheckpointCodec.isInjectedCheckpoint(message)) {
                checkpoints++;
                assertTrue(message.getContent().contains(CHECKPOINT_GOAL));
            }
        }
        assertEquals(1, checkpoints);

        ContextBudgetPlanner.PlannedOutbound openAiPlan = plan(messages, 750_000, 0L);
        ContextBudgetPlanner.PlannedOutbound anthropicPlan = ContextBudgetPlanner.planForProviderRound(
                infoEnabledLogger(), messages, MAIN_LOOP_TOOLS, ANTHROPIC_IDS, 750_000, 0L, -1, -1);

        String projectionAfter = WirePartitionHasher.hashMessages(
                buildWireSnapshot(openAiPlan.getOutboundMessages()).openAiWire(),
                StructuredMessageProjection.WireKind.CHAT_COMPLETIONS);
        assertNotEquals(projectionBefore, projectionAfter, "checkpoint install must change projection once");

        String openAiWire = JSON.writeValueAsString(buildWireSnapshot(openAiPlan.getOutboundMessages()).openAiWire());
        String anthropicMessages = JSON.writeValueAsString(
                buildWireSnapshot(anthropicPlan.getOutboundMessages()).anthropicWire().get("messages"));
        assertTrue(openAiWire.contains(CHECKPOINT_GOAL));
        assertFalse(anthropicMessages.contains(CHECKPOINT_GOAL));
        assertTrue(openAiPlan.getPlannedMetrics().checkpointChars > 0);
        assertEquals(0, anthropicPlan.getPlannedMetrics().checkpointChars);
    }

    @Test
    void normalAppend_thirtyAndSixtyTurnChains_remainStableUnderDefaultCap() {
        for (int turns : new int[] { 30, 60 }) {
            String toolsHash = null;
            List<Map<String, Object>> previousProjection = null;
            for (int completedTurns = 1; completedTurns <= turns; completedTurns++) {
                WireSnapshot snapshot = snapshotAfterPlanning(buildPlanningMessages(completedTurns), 750_000, 0L);
                if (toolsHash == null) {
                    toolsHash = snapshot.openAiToolsHash();
                } else {
                    assertEquals(toolsHash, snapshot.openAiToolsHash(), "tools partition at turn " + completedTurns);
                }
                if (previousProjection != null) {
                    int prefix = WirePartitionHasher.commonPrefixLength(previousProjection, snapshot.openAiMessages());
                    assertTrue(prefix >= previousProjection.size() - 2,
                            "projection prefix stable except volatile tail at turn " + completedTurns);
                }
                if (completedTurns >= 3) {
                    assertHistoryMarkerRules(snapshot.anthropicWire(), completedTurns);
                }
                previousProjection = snapshot.openAiMessages();
            }
        }
    }

    private static List<ChatMessage> buildCheckpointPressureConversation() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(STABLE_SYSTEM));
        messages.add(ChatMessage.user("old question " + "o".repeat(3_000)));
        messages.add(ChatMessage.assistant("old answer " + "p".repeat(3_000)));
        messages.add(ChatMessage.user("recent question " + "q".repeat(2_000)));
        messages.add(ChatMessage.assistant("recent answer " + "r".repeat(2_000)));
        messages.add(ChatMessage.user("newest-user"));
        messages.add(ChatMessage.assistant("newest-answer"));
        return messages;
    }

    private static List<ChatMessage> buildPlanningMessages(int completedTurns) {
        List<ChatMessage> messages = buildConversationThroughTurn(completedTurns);
        messages.add(ChatMessage.system(ParlerSuffixFraming.TIME_CONTEXT + "\n- now_utc: turn-" + completedTurns));
        if (completedTurns < 10) {
            messages.add(ChatMessage.user("user-turn-" + (completedTurns + 1)));
        }
        return messages;
    }

    private static int pinnedEvidenceCharsForTurn(int turnIndex) {
        String callA = "t" + turnIndex + "a";
        String callB = "t" + turnIndex + "b";
        String callC = "t" + turnIndex + "c";
        String invokeArgs = "{\"entityName\":\"Thing.T" + turnIndex + "\"}";
        int sum = 0;
        sum += toolCallRowChars(callA, "tabulate_cached_result", TABULATE_ARGS);
        sum += toolCallRowChars(callB, "tabulate_cached_result", TABULATE_ARGS);
        sum += toolResultRowChars(callA);
        sum += toolResultRowChars(callB);
        sum += toolCallRowChars(callC, "invoke_service", invokeArgs);
        sum += toolResultRowChars(callC);
        return sum;
    }

    private static int toolCallRowChars(String callId, String functionName, String args) {
        return callId.length() + functionName.length() + args.length() + TOOL_CALL_ENVELOPE_OVERHEAD;
    }

    private static int toolResultRowChars(String callId) {
        return callId.length() + MATRIX_BODY.length() + TOOL_RESULT_ENVELOPE_OVERHEAD;
    }

    private static int pinnedTotalEvidenceChars(int turns) {
        int sum = 0;
        for (int turn = 1; turn <= turns; turn++) {
            sum += pinnedEvidenceCharsForTurn(turn);
        }
        return sum;
    }

    private static List<ChatMessage> buildPressurePlanningMessages(int completedTurns) {
        List<ChatMessage> messages = buildPlanningMessages(completedTurns);
        if (completedTurns >= 60) {
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage message = messages.get(i);
                if (message.getRole() == ChatMessage.Role.USER && "user-turn-1".equals(message.getContent())) {
                    messages.set(i, ChatMessage.user(message.getContent() + " " + "q".repeat(100_000)));
                    break;
                }
            }
        }
        return messages;
    }

    private static List<ChatMessage> buildConversationThroughTurn(int turnCount) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(STABLE_SYSTEM));
        for (int turn = 1; turn <= turnCount; turn++) {
            appendTurn(messages, turn);
        }
        return messages;
    }

    private static void appendTurn(List<ChatMessage> messages, int turnIndex) {
        String callA = "t" + turnIndex + "a";
        String callB = "t" + turnIndex + "b";
        String callC = "t" + turnIndex + "c";
        messages.add(ChatMessage.user("user-turn-" + turnIndex));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall(callA, "tabulate_cached_result", "{\"cacheId\":\"tabular-cache-abc\"}"),
                new ToolCall(callB, "tabulate_cached_result", "{\"cacheId\":\"tabular-cache-abc\"}"))));
        messages.add(ChatMessage.toolResult(callA, MATRIX_BODY));
        messages.add(ChatMessage.toolResult(callB, MATRIX_BODY));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall(callC, "invoke_service", "{\"entityName\":\"Thing.T" + turnIndex + "\"}"))));
        messages.add(ChatMessage.toolResult(callC, MATRIX_BODY));
        messages.add(ChatMessage.assistant("assistant-summary-turn-" + turnIndex));
    }

    private static ContextBudgetPlanner.PlannedOutbound plan(
            List<ChatMessage> messages, int configuredCap, long providerCap) {
        return ContextBudgetPlanner.planForProviderRound(
                infoEnabledLogger(), messages, MAIN_LOOP_TOOLS, OPENAI_IDS, configuredCap, providerCap, -1, -1);
    }

    private static WireSnapshot snapshotAfterPlanning(List<ChatMessage> messages, int configuredCap, long providerCap) {
        ContextBudgetPlanner.PlannedOutbound planned = plan(messages, configuredCap, providerCap);
        return buildWireSnapshot(planned.getOutboundMessages());
    }

    private static WireSnapshot buildWireSnapshot(List<ChatMessage> plannedMessages) {
        return buildWireSnapshot(plannedMessages, MAIN_LOOP_TOOLS, false);
    }

    private static WireSnapshot buildWireSnapshot(
            List<ChatMessage> plannedMessages,
            List<ToolDefinition> tools,
            boolean toolChoiceNone) {
        LlmChatRequest base = LlmChatRequest.forAgentRound(plannedMessages, tools, 0.0, 4096, null);
        LlmChatRequest openAiRequest = toolChoiceNone ? LlmChatRequest.copyWithToolPolicy(base, true) : base;
        LlmChatRequest anthropicRequest = LlmChatRequest.copyWithCacheControl(openAiRequest, true);

        Map<String, Object> openAiWire = ChatCompletionsApi.buildRequestBody(
                "gpt-4o", plannedMessages, tools, openAiRequest, false, 4096);
        Map<String, Object> anthropicWire = AnthropicMessagesApi.buildRequestPayload(
                plannedMessages, tools, "claude", 0.0, 4096, 0, anthropicRequest);
        return new WireSnapshot(openAiWire, anthropicWire);
    }

    private static int detectFirstDroppedTurn(List<ChatMessage> full, List<ChatMessage> trimmed) {
        int prefix = firstMatchingOutboundPrefixLength(full, trimmed);
        if (prefix >= full.size()) {
            return -1;
        }
        ChatMessage dropped = full.get(prefix);
        String content = nullSafeContent(dropped);
        for (int turn = 1; turn <= 99; turn++) {
            if (content.contains("user-turn-" + turn)
                    || content.contains("assistant-summary-turn-" + turn)
                    || content.contains("Thing.T" + turn)) {
                return turn;
            }
        }
        for (ToolCall toolCall : dropped.getToolCalls()) {
            String callId = toolCall.getId();
            if (callId != null && callId.startsWith("t") && callId.length() > 2) {
                String turnDigits = callId.substring(1, callId.length() - 1);
                try {
                    return Integer.parseInt(turnDigits);
                } catch (NumberFormatException ignored) {
                    // continue
                }
            }
        }
        return -1;
    }

    private static int firstMatchingOutboundPrefixLength(List<ChatMessage> full, List<ChatMessage> trimmed) {
        int limit = Math.min(full.size(), trimmed.size());
        int prefix = 0;
        for (; prefix < limit; prefix++) {
            if (!outboundMessagesEqual(full.get(prefix), trimmed.get(prefix))) {
                break;
            }
        }
        return prefix;
    }

    private static boolean outboundMessagesEqual(ChatMessage a, ChatMessage b) {
        return a.getRole() == b.getRole()
                && nullSafeContent(a).equals(nullSafeContent(b))
                && a.getToolCalls().equals(b.getToolCalls());
    }

    private static String nullSafeContent(ChatMessage message) {
        return message.getContent() != null ? message.getContent() : "";
    }

    private static void assertHistoryMarkerRules(Map<String, Object> anthropicWire, int completedTurns) {
        assertVolatileSuffixUnmarked(anthropicWire);
        int markerCount = countCacheControls(anthropicWire);
        if (completedTurns >= 3) {
            assertEquals(4, markerCount,
                    "stable system, last tool, and exactly two history frontiers must carry cache_control");
            assertLatestTwoFrontierMarkerPositions(anthropicWire);
        } else {
            assertTrue(markerCount >= 2 && markerCount <= 4,
                    "early turns may mark fewer surviving frontiers");
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertLatestTwoFrontierMarkerPositions(Map<String, Object> anthropicWire) {
        List<Map<String, Object>> apiMessages = (List<Map<String, Object>>) anthropicWire.get("messages");
        assertNotNull(apiMessages);
        List<String> frontierKeys = eligibleFrontierKeys(apiMessages);
        assertTrue(frontierKeys.size() >= 2, "expected at least two frontier candidates");
        String latest = frontierKeys.get(frontierKeys.size() - 1);
        String secondLatest = frontierKeys.get(frontierKeys.size() - 2);
        String thirdLatest = frontierKeys.size() >= 3 ? frontierKeys.get(frontierKeys.size() - 3) : null;
        assertTrue(hasCacheControlAt(apiMessages, latest),
                "latest frontier must carry cache_control at " + latest);
        assertTrue(hasCacheControlAt(apiMessages, secondLatest),
                "second-latest frontier must carry cache_control at " + secondLatest);
        if (thirdLatest != null) {
            assertFalse(hasCacheControlAt(apiMessages, thirdLatest),
                    "third-latest frontier must not retain cache_control at " + thirdLatest);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertPinnedFrontierIndexes(Map<String, Object> anthropicWire) {
        List<Map<String, Object>> apiMessages = (List<Map<String, Object>>) anthropicWire.get("messages");
        List<String> frontierKeys = eligibleFrontierKeys(apiMessages);
        assertEquals(PINNED_LATEST_FRONTIER_KEY, frontierKeys.get(frontierKeys.size() - 1));
        assertEquals(PINNED_SECOND_LATEST_FRONTIER_KEY, frontierKeys.get(frontierKeys.size() - 2));
        assertEquals(PINNED_THIRD_LATEST_FRONTIER_KEY, frontierKeys.get(frontierKeys.size() - 3));
    }

    @SuppressWarnings("unchecked")
    private static List<String> eligibleFrontierKeys(List<Map<String, Object>> apiMessages) {
        List<String> frontierKeys = new ArrayList<>();
        for (int messageIndex = 0; messageIndex < apiMessages.size(); messageIndex++) {
            Map<String, Object> row = apiMessages.get(messageIndex);
            if (!"user".equals(row.get("role"))) {
                continue;
            }
            Object content = row.get("content");
            if (!(content instanceof List<?>)) {
                continue;
            }
            List<?> blocks = (List<?>) content;
            if (blocks.isEmpty()) {
                continue;
            }
            Object lastBlockObj = blocks.get(blocks.size() - 1);
            if (lastBlockObj instanceof Map<?, ?>) {
                Map<?, ?> lastBlock = (Map<?, ?>) lastBlockObj;
                if ("tool_result".equals(lastBlock.get("type"))) {
                    frontierKeys.add(messageIndex + ":" + (blocks.size() - 1));
                    continue;
                }
            }
            for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                Object blockObj = blocks.get(blockIndex);
                if (!(blockObj instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> block = (Map<String, Object>) blockObj;
                if (!"text".equals(block.get("type"))) {
                    continue;
                }
                Object text = block.get("text");
                if (text instanceof String && ((String) text).startsWith(ParlerSuffixFraming.TIME_CONTEXT)) {
                    continue;
                }
                frontierKeys.add(messageIndex + ":" + blockIndex);
            }
        }
        return frontierKeys;
    }

    @SuppressWarnings("unchecked")
    private static void markSystemCacheControl(Map<String, Object> anthropicWire) {
        Object system = anthropicWire.get("system");
        if (system instanceof String) {
            Map<String, Object> block = new java.util.LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", system);
            block.put("cache_control", Map.of("type", "ephemeral"));
            anthropicWire.put("system", List.of(block));
            return;
        }
        if (system instanceof List<?>) {
            List<?> blocks = (List<?>) system;
            if (!blocks.isEmpty() && blocks.get(0) instanceof Map<?, ?>) {
                ((Map<String, Object>) blocks.get(0)).put("cache_control", Map.of("type", "ephemeral"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void markLastToolDefinition(Map<String, Object> anthropicWire) {
        Object tools = anthropicWire.get("tools");
        if (!(tools instanceof List<?>)) {
            return;
        }
        List<?> toolList = (List<?>) tools;
        if (toolList.isEmpty()) {
            return;
        }
        Object lastTool = toolList.get(toolList.size() - 1);
        if (lastTool instanceof Map<?, ?>) {
            ((Map<String, Object>) lastTool).put("cache_control", Map.of("type", "ephemeral"));
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertMarkerPartition(
            Map<String, Object> anthropicWire,
            List<Map<String, Object>> apiMessages,
            int expectedSystem,
            int expectedTools,
            int expectedHistory) {
        int systemMarkers = 0;
        Object system = anthropicWire.get("system");
        if (system instanceof Map<?, ?>) {
            systemMarkers = ((Map<?, ?>) system).containsKey("cache_control") ? 1 : 0;
        } else if (system instanceof List<?>) {
            for (Object blockObj : (List<?>) system) {
                if (blockObj instanceof Map<?, ?> && ((Map<?, ?>) blockObj).containsKey("cache_control")) {
                    systemMarkers++;
                }
            }
        }
        int toolMarkers = 0;
        Object tools = anthropicWire.get("tools");
        if (tools instanceof List<?>) {
            for (Object toolObj : (List<?>) tools) {
                if (toolObj instanceof Map<?, ?> && ((Map<?, ?>) toolObj).containsKey("cache_control")) {
                    toolMarkers++;
                }
            }
        }
        int historyMarkers = 0;
        for (Map<String, Object> row : apiMessages) {
            Object content = row.get("content");
            if (!(content instanceof List<?>)) {
                continue;
            }
            for (Object blockObj : (List<?>) content) {
                if (blockObj instanceof Map<?, ?> && ((Map<?, ?>) blockObj).containsKey("cache_control")) {
                    historyMarkers++;
                }
            }
        }
        assertEquals(expectedSystem, systemMarkers, "system marker partition");
        assertEquals(expectedTools, toolMarkers, "tool-definition marker partition");
        assertEquals(expectedHistory, historyMarkers, "history marker partition");
    }

    @SuppressWarnings("unchecked")
    private static void markLastToolBatchEnd(List<Map<String, Object>> apiMessages) {
        for (int messageIndex = apiMessages.size() - 1; messageIndex >= 0; messageIndex--) {
            Map<String, Object> row = apiMessages.get(messageIndex);
            Object content = row.get("content");
            if (!(content instanceof List<?>)) {
                continue;
            }
            List<?> blocks = (List<?>) content;
            if (blocks.isEmpty()) {
                continue;
            }
            Object lastBlockObj = blocks.get(blocks.size() - 1);
            if (lastBlockObj instanceof Map<?, ?>) {
                Map<?, ?> lastBlock = (Map<?, ?>) lastBlockObj;
                if ("tool_result".equals(lastBlock.get("type"))) {
                    ((Map<String, Object>) lastBlockObj).put("cache_control", Map.of("type", "ephemeral"));
                    return;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearCacheControls(Map<String, Object> anthropicWire, List<Map<String, Object>> apiMessages) {
        Object system = anthropicWire.get("system");
        if (system instanceof Map<?, ?>) {
            ((Map<String, Object>) system).remove("cache_control");
        } else if (system instanceof List<?>) {
            for (Object blockObj : (List<?>) system) {
                if (blockObj instanceof Map<?, ?>) {
                    ((Map<String, Object>) blockObj).remove("cache_control");
                }
            }
        }
        Object tools = anthropicWire.get("tools");
        if (tools instanceof List<?>) {
            for (Object toolObj : (List<?>) tools) {
                if (toolObj instanceof Map<?, ?>) {
                    ((Map<String, Object>) toolObj).remove("cache_control");
                }
            }
        }
        clearCacheControls(apiMessages);
    }

    @SuppressWarnings("unchecked")
    private static void clearCacheControls(List<Map<String, Object>> apiMessages) {
        for (Map<String, Object> row : apiMessages) {
            Object content = row.get("content");
            if (!(content instanceof List<?>)) {
                continue;
            }
            for (Object blockObj : (List<?>) content) {
                if (blockObj instanceof Map<?, ?>) {
                    ((Map<String, Object>) blockObj).remove("cache_control");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void markCacheControlAt(List<Map<String, Object>> apiMessages, String messageBlockKey) {
        String[] parts = messageBlockKey.split(":");
        Map<String, Object> block = (Map<String, Object>) ((List<?>) apiMessages.get(Integer.parseInt(parts[0]))
                .get("content")).get(Integer.parseInt(parts[1]));
        block.put("cache_control", Map.of("type", "ephemeral"));
    }

    @SuppressWarnings("unchecked")
    private static boolean hasCacheControlAt(List<Map<String, Object>> apiMessages, String messageBlockKey) {
        String[] parts = messageBlockKey.split(":");
        int messageIndex = Integer.parseInt(parts[0]);
        int blockIndex = Integer.parseInt(parts[1]);
        Map<String, Object> row = apiMessages.get(messageIndex);
        Object content = row.get("content");
        if (!(content instanceof List<?>)) {
            return false;
        }
        Object blockObj = ((List<?>) content).get(blockIndex);
        return blockObj instanceof Map<?, ?> && ((Map<?, ?>) blockObj).containsKey("cache_control");
    }

    private static void assertVolatileSuffixUnmarked(Map<String, Object> anthropicWire) {
        Object raw = anthropicWire.get("messages");
        if (!(raw instanceof List<?>)) {
            return;
        }
        List<?> rows = (List<?>) raw;
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> row = (Map<?, ?>) rowObj;
            Object content = row.get("content");
            if (!(content instanceof List<?>)) {
                continue;
            }
            for (Object blockObj : (List<?>) content) {
                if (!(blockObj instanceof Map<?, ?>)) {
                    continue;
                }
                Map<?, ?> block = (Map<?, ?>) blockObj;
                String text = String.valueOf(block.get("text"));
                if (text.startsWith(ParlerSuffixFraming.TIME_CONTEXT)
                        && block.containsKey("cache_control")) {
                    throw new AssertionError("volatile time suffix must not carry cache_control");
                }
            }
        }
    }

    private static List<String> snapshotContents(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::getContent).collect(Collectors.toList());
    }

    private static final class CheckpointStubClient implements LlmClient {
        private final String goal;
        int calls;

        private CheckpointStubClient(String goal) {
            this.goal = goal;
        }

        @Override
        public LlmResponse chat(LlmChatRequest request) {
            calls++;
            return new LlmResponse(
                    "{\"goal\":\"" + goal + "\",\"nextSteps\":[\"chart\"]}",
                    List.of(),
                    LlmResponse.FinishReason.STOP,
                    3,
                    5);
        }

        @Override
        public LlmUsageWireIds usageWireIds() {
            return OPENAI_IDS;
        }

        @Override
        public boolean healthCheck() {
            return true;
        }
    }

    private static int countCacheControls(Object value) {
        if (value instanceof Map<?, ?>) {
            int count = ((Map<?, ?>) value).containsKey("cache_control") ? 1 : 0;
            for (Object child : ((Map<?, ?>) value).values()) {
                count += countCacheControls(child);
            }
            return count;
        }
        if (value instanceof List<?>) {
            int count = 0;
            for (Object child : (List<?>) value) {
                count += countCacheControls(child);
            }
            return count;
        }
        return 0;
    }

    private static Logger infoEnabledLogger() {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) {
                    return "LongConversationReplayTestLogger";
                }
            }
            if ("isInfoEnabled".equals(method.getName())) {
                return true;
            }
            if (method.getReturnType() == void.class) {
                return null;
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType().isPrimitive()) {
                return 0;
            }
            return null;
        };
        @SuppressWarnings("unchecked")
        Logger log = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] { Logger.class },
                handler);
        return log;
    }

    private static final class WireSnapshot {
        private final Map<String, Object> openAiWire;
        private final Map<String, Object> anthropicWire;
        private final List<Map<String, Object>> openAiMessages;
        private final List<Map<String, Object>> anthropicMessages;
        private final String openAiToolsHash;
        private final String anthropicToolsHash;
        private final String openAiSystemHash;
        private final String anthropicSystemHash;

        private WireSnapshot(Map<String, Object> openAiWire, Map<String, Object> anthropicWire) {
            this.openAiWire = openAiWire;
            this.anthropicWire = anthropicWire;
            this.openAiMessages = StructuredMessageProjection.projectMessages(
                    openAiWire, StructuredMessageProjection.WireKind.CHAT_COMPLETIONS);
            this.anthropicMessages = StructuredMessageProjection.projectMessages(
                    anthropicWire, StructuredMessageProjection.WireKind.ANTHROPIC);
            this.openAiToolsHash = WirePartitionHasher.hashTools(
                    openAiWire, StructuredMessageProjection.WireKind.CHAT_COMPLETIONS);
            this.anthropicToolsHash = WirePartitionHasher.hashTools(
                    anthropicWire, StructuredMessageProjection.WireKind.ANTHROPIC);
            this.openAiSystemHash = WirePartitionHasher.hashSystem(
                    openAiWire, StructuredMessageProjection.WireKind.CHAT_COMPLETIONS);
            this.anthropicSystemHash = WirePartitionHasher.hashSystem(
                    anthropicWire, StructuredMessageProjection.WireKind.ANTHROPIC);
        }

        Map<String, Object> anthropicWire() {
            return anthropicWire;
        }

        Map<String, Object> openAiWire() {
            return openAiWire;
        }

        List<Map<String, Object>> openAiMessages() {
            return openAiMessages;
        }

        List<Map<String, Object>> anthropicMessages() {
            return anthropicMessages;
        }

        String openAiToolsHash() {
            return openAiToolsHash;
        }

        String anthropicToolsHash() {
            return anthropicToolsHash;
        }

        String openAiSystemHash() {
            return openAiSystemHash;
        }

        String anthropicSystemHash() {
            return anthropicSystemHash;
        }
    }
}
