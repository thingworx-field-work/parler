package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.compaction.CompactionTestFixtures;
import com.thingworx.things.agent.compaction.ConversationsReplayNormalization;
import com.thingworx.things.agent.compaction.InfoTableMatrixCodec;
import com.thingworx.things.agent.compaction.MessageCharEstimator;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.StreamTokenUsage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.PendingApprovalRecord;
import com.thingworx.things.agent.tools.PendingApprovalStore;

/**
 * Same package as {@link AgentLoop} so tests can construct package-private {@link AgentLoop.AgentResult} fixtures.
 */
class ConversationsReplayNormalizationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void removeTestPending() {
        PendingApprovalStore.remove("pid-crn-gate");
    }

    @Test
    void applyBeforeStore_successAndCompaction_runsTierB() throws Exception {
        String matrix = CompactionTestFixtures.largeCacheSampleMatrixToolJson();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("first question"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tc1", "query_entities", "{\"thingTemplate\":\"T\"}"))));
        messages.add(ChatMessage.toolResult("tc1", matrix));
        messages.add(ChatMessage.assistant("first answer"));
        messages.add(ChatMessage.user("second question"));
        messages.add(ChatMessage.assistant("final"));

        AgentLoop.AgentResult ok = AgentLoop.AgentResult.success("final", 1, 0, 0, StreamTokenUsage.ZERO);
        ConversationsReplayNormalization.applyTierBReplayPromotionBeforeStore(messages, ok, true, null, null);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(body.path("$format").asText().contains("summary"));
    }

    @Test
    void postTurnWithinStorageCap_keepsHistoricToolResultByteIdentical() {
        String matrix = CompactionTestFixtures.largeCacheSampleMatrixToolJson();
        List<ChatMessage> messages = promotableConversation(matrix, "tc-within");
        int rawReplayChars = messages.stream().mapToInt(MessageCharEstimator::rowChars).sum();

        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                messages,
                AgentLoop.AgentResult.success("final", 1, 0, 0, StreamTokenUsage.ZERO),
                true,
                null,
                "cid-within",
                "rid-within",
                rawReplayChars,
                null,
                "amid-within",
                "AgentThing",
                "2026-08-25T00:00:00Z");

        assertEquals(matrix, messages.get(2).getContent(),
                "within-cap post-turn replay must stay byte-identical for the provider cache prefix");
    }

    @Test
    void postTurnOverStorageCap_promotesBeforeCheckpointAndTrim() throws Exception {
        String matrix = CompactionTestFixtures.largeCacheSampleMatrixToolJson();
        List<ChatMessage> messages = promotableConversation(matrix, "tc-pressure");
        int rawReplayChars = messages.stream().mapToInt(MessageCharEstimator::rowChars).sum();

        ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore(
                messages,
                AgentLoop.AgentResult.success("final", 1, 0, 0, StreamTokenUsage.ZERO),
                true,
                null,
                "cid-pressure",
                "rid-pressure",
                rawReplayChars - 1,
                null,
                "amid-pressure",
                "AgentThing",
                "2026-08-25T00:00:00Z");

        assertEquals(6, messages.size(), "Tier B savings bring this fixture under cap without deleting a batch");
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(body.path("$format").asText().contains("summary"));
    }

    private static List<ChatMessage> promotableConversation(String matrix, String toolCallId) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("first question"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall(toolCallId, "query_entities", "{\"thingTemplate\":\"T\"}"))));
        messages.add(ChatMessage.toolResult(toolCallId, matrix));
        messages.add(ChatMessage.assistant("first answer"));
        messages.add(ChatMessage.user("second question"));
        messages.add(ChatMessage.assistant("final"));
        return messages;
    }

    @Test
    void applyBeforeStore_awaitingApproval_doesNotPromote() throws Exception {
        String matrix = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"x\",\"baseType\":\"STRING\"}],\"rows\":[[\"a\"]]}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("t1", matrix));
        messages.add(ChatMessage.assistant("a1"));

        AgentLoop.AgentResult awaiting = AgentLoop.AgentResult.awaitingApproval("pid", 1, 0, 0, StreamTokenUsage.ZERO);
        ConversationsReplayNormalization.applyTierBReplayPromotionBeforeStore(messages, awaiting, true, null, "any");
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(body.path("$format").asText().contains("matrix"));
    }

    @Test
    void applyBeforeStore_compactionOff_doesNotPromote() throws Exception {
        String matrix = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"x\",\"baseType\":\"STRING\"}],\"rows\":[[\"a\"]]}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("t1", matrix));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));

        AgentLoop.AgentResult ok = AgentLoop.AgentResult.success("final", 1, 0, 0, StreamTokenUsage.ZERO);
        ConversationsReplayNormalization.applyTierBReplayPromotionBeforeStore(messages, ok, false, null, null);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(body.path("$format").asText().contains("matrix"));
    }

    @Test
    void applyBeforeStore_nullResult_doesNotPromote() throws Exception {
        String matrix = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"x\",\"baseType\":\"STRING\"}],\"rows\":[[\"a\"]]}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("t1", matrix));
        messages.add(ChatMessage.assistant("final"));

        ConversationsReplayNormalization.applyTierBReplayPromotionBeforeStore(messages, null, true, null, "cid");
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertEquals(InfoTableMatrixCodec.FORMAT_MATRIX_V1, body.path("$format").asText());
    }

    @Test
    void applyBeforeStore_activePendingForConversation_skipsTierB() throws Exception {
        String matrix = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"x\",\"baseType\":\"STRING\"}],\"rows\":[[\"a\"],[\"b\"]]}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("first question"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tc1", "query_entities", "{\"thingTemplate\":\"T\"}"))));
        messages.add(ChatMessage.toolResult("tc1", matrix));
        messages.add(ChatMessage.assistant("first answer"));
        messages.add(ChatMessage.user("second question"));
        messages.add(ChatMessage.assistant("final"));

        List<ChatMessage> pendingMsgs = Collections.emptyList();
        ToolCall tc = new ToolCall("gate-id", "invoke_service", "{}");
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "pid-crn-gate",
                "rid-gate",
                "crn-p1",
                "user1",
                "AgentThing",
                "remote",
                tc,
                pendingMsgs,
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000);
        PendingApprovalStore.put(rec);

        AgentLoop.AgentResult ok = AgentLoop.AgentResult.success("final", 1, 0, 0, StreamTokenUsage.ZERO);
        ConversationsReplayNormalization.applyTierBReplayPromotionBeforeStore(messages, ok, true, null, "crn-p1");
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertEquals(InfoTableMatrixCodec.FORMAT_MATRIX_V1, body.path("$format").asText());
    }

    @Test
    void applyBeforeStore_activePendingOnOtherConversation_stillPromotes() throws Exception {
        String matrix = CompactionTestFixtures.largeCacheSampleMatrixToolJson();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("first question"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tc1", "query_entities", "{\"thingTemplate\":\"T\"}"))));
        messages.add(ChatMessage.toolResult("tc1", matrix));
        messages.add(ChatMessage.assistant("first answer"));
        messages.add(ChatMessage.user("second question"));
        messages.add(ChatMessage.assistant("final"));

        List<ChatMessage> pendingMsgs = Collections.emptyList();
        ToolCall tc = new ToolCall("gate-id", "invoke_service", "{}");
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "pid-crn-gate",
                "rid-gate",
                "crn-other",
                "user1",
                "AgentThing",
                "remote",
                tc,
                pendingMsgs,
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000);
        PendingApprovalStore.put(rec);

        AgentLoop.AgentResult ok = AgentLoop.AgentResult.success("final", 1, 0, 0, StreamTokenUsage.ZERO);
        ConversationsReplayNormalization.applyTierBReplayPromotionBeforeStore(messages, ok, true, null, "crn-p1");
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(body.path("$format").asText().contains("summary"));
    }

    @Test
    void applyBeforeStore_tinyMatrix_noShrinkLeavesMatrixUnchanged() throws Exception {
        String matrix = CompactionTestFixtures.smallTwoRowMatrixToolJson();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("first question"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tc-tiny", "query_entities", "{\"thingTemplate\":\"T\"}"))));
        messages.add(ChatMessage.toolResult("tc-tiny", matrix));
        messages.add(ChatMessage.assistant("first answer"));
        messages.add(ChatMessage.user("second question"));
        messages.add(ChatMessage.assistant("final"));

        AgentLoop.AgentResult ok = AgentLoop.AgentResult.success("final", 1, 0, 0, StreamTokenUsage.ZERO);
        ConversationsReplayNormalization.applyTierBReplayPromotionBeforeStore(messages, ok, true, null, null);
        assertEquals(matrix, messages.get(2).getContent());
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(body.path("$format").asText().contains("matrix"));
        assertFalse(body.path("$format").asText().contains("summary"));
    }
}
