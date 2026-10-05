package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.tools.CompactFetchStreamRehydrate;

/**
 * Request-shape guard for Stream-rehydrated histories: compact fetch evidence must not appear as
 * orphan {@link ChatMessage.Role#TOOL} rows after serialization.
 */
class AgentConversationRehydrationProviderShapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anthropicPayload_afterStage2FramedEvidence_hasNoToolResultBlocks() throws Exception {
        List<ChatMessage> messages = rehydratedHistoryWithFramedCompactEvidence();
        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(messages, null, "claude-test", 0.0, 256);
        String wire = JSON.writeValueAsString(body.get("messages"));
        assertFalse(wire.contains("\"type\":\"tool_result\""), wire);
        JsonNode rows = JSON.readTree(wire);
        assertEquals("first user", rows.get(0).get("content").asText(),
                "the nullable request remains the false kind after rehydration");
    }

    @Test
    void openAiStylePayload_afterStage2FramedEvidence_hasNoToolRoleRows() throws Exception {
        List<ChatMessage> messages = rehydratedHistoryWithFramedCompactEvidence();
        List<Map<String, Object>> api = ChatCompletionsApiMessages.toApiMessages(messages);
        String wire = JSON.writeValueAsString(api);
        assertFalse(wire.contains("\"role\":\"tool\""), wire);
        assertTrue(wire.contains(CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX.trim()));
    }

    @Test
    void providerPayload_afterStage2FramedNumericEvidence_hasNoToolRows() throws Exception {
        String evidence = "{\"status\":\"success\",\"$format\":\"parler.numeric_history.compact.v1\","
                + "\"resultKind\":\"NUMERIC_HISTORY_AGGREGATES\",\"cacheId\":\"cid\","
                + "\"columns\":[{\"name\":\"timestamp\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"value\",\"baseType\":\"NUMBER\"}],\"sampleRows\":[],"
                + "\"parlerRehydratedCacheHistorical\":true}";
        List<ChatMessage> messages = rehydratedHistoryWithFramedCompactEvidence(evidence);

        Map<String, Object> anthropic = AnthropicMessagesApi.buildRequestPayload(messages, null, "claude-test", 0.0, 256);
        String anthropicWire = JSON.writeValueAsString(anthropic.get("messages"));
        assertFalse(anthropicWire.contains("\"type\":\"tool_result\""), anthropicWire);
        List<Map<String, Object>> openAi = ChatCompletionsApiMessages.toApiMessages(messages);
        String openAiWire = JSON.writeValueAsString(openAi);
        assertFalse(openAiWire.contains("\"role\":\"tool\""), openAiWire);
    }

    private static List<ChatMessage> rehydratedHistoryWithFramedCompactEvidence() {
        String evidence = "{\"status\":\"success\",\"cacheId\":\"cid\",\"sampleOnly\":true,"
                + "\"parlerRehydratedCacheHistorical\":true}";
        return rehydratedHistoryWithFramedCompactEvidence(evidence);
    }

    private static List<ChatMessage> rehydratedHistoryWithFramedCompactEvidence(String evidence) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable system for tests"));
        messages.add(ChatMessage.user("first user"));
        messages.add(ChatMessage.assistant("prior assistant prose"));
        messages.add(ChatMessage.assistant(CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX
                + evidence));
        messages.add(ChatMessage.user("follow-up after restart"));
        return messages;
    }
}
