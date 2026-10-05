package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

class LlmToolResultMatrixSealerTest {

    @Test
    void sealBatch_rewritesEligibleToolBodies() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("x"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("c1", "query_entities", "{}"))));
        int asstIdx = 1;
        StringBuilder longVal = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longVal.append('y');
        }
        String pad = longVal.toString();
        StringBuilder rows = new StringBuilder();
        rows.append("\"rows\":[");
        for (int r = 0; r < 40; r++) {
            if (r > 0) {
                rows.append(',');
            }
            rows.append("{\"entityType\":\"Thing\",\"name\":\"T").append(r).append("\",\"description\":\"")
                    .append(pad).append(r % 5).append("\",\"thingTemplate\":\"SomeTemplate\",\"tags\":\"a\",")
                    .append("\"isSystemObject\":false}");
        }
        rows.append(']');
        String toolJson = "{\"status\":\"success\",\"resultKind\":\"ENTITY_QUERY_INLINE\",\"columns\":["
                + "{\"name\":\"entityType\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"name\",\"baseType\":\"THINGNAME\"},"
                + "{\"name\":\"description\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"thingTemplate\",\"baseType\":\"THINGTEMPLATENAME\"},"
                + "{\"name\":\"tags\",\"baseType\":\"TAGS\"},"
                + "{\"name\":\"isSystemObject\",\"baseType\":\"BOOLEAN\"}],"
                + rows + "}";
        messages.add(ChatMessage.toolResult("c1", toolJson));

        LlmToolResultMatrixSealer.SealStats stats =
                LlmToolResultMatrixSealer.sealCompletedToolBatch(messages, asstIdx, true, null);
        assertTrue(stats.getRawReplayChars() > 0);
        assertTrue(stats.getReplayChars() > 0);
        assertNotEquals(toolJson, messages.get(2).getContent());
        assertTrue(messages.get(2).getContent().contains("parler.infotable.matrix.v1"));
        assertEquals(stats.getRawReplayChars(), toolJson.length());
    }

    @Test
    void sealBatch_disabled_noop() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("c1", "t", "{}"))));
        String body = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"columns\":[{\"name\":\"a\",\"baseType\":\"STRING\"}],"
                + "\"rows\":[{\"a\":\"1\"}]}";
        messages.add(ChatMessage.toolResult("c1", body));
        LlmToolResultMatrixSealer.SealStats stats =
                LlmToolResultMatrixSealer.sealCompletedToolBatch(messages, 0, false, null);
        assertTrue(stats.isEmpty());
        assertEquals(body, messages.get(1).getContent());
    }

    @Test
    void sealBatch_numericHistoryInlineIsMatrixEligible() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("hist", "query_property_history", "{}"))));
        StringBuilder rows = new StringBuilder();
        rows.append("\"sampleRows\":[");
        for (int r = 0; r < 40; r++) {
            if (r > 0) {
                rows.append(',');
            }
            rows.append("{\"timestamp\":\"2026-06-04T00:").append(String.format("%02d", r))
                    .append(":00Z\",\"value\":").append(r + 0.5d).append('}');
        }
        rows.append(']');
        String body = "{\"status\":\"success\",\"$format\":\"parler.numeric_history.compact.v1\","
                + "\"resultKind\":\"NUMERIC_HISTORY_INLINE\",\"totalRows\":40,\"returnedRows\":40,"
                + "\"columns\":[{\"name\":\"timestamp\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"value\",\"baseType\":\"NUMBER\"}],"
                + rows + "}";
        messages.add(ChatMessage.toolResult("hist", body));

        LlmToolResultMatrixSealer.SealStats stats =
                LlmToolResultMatrixSealer.sealCompletedToolBatch(messages, 0, true, null);

        assertTrue(stats.getToolBodiesRewritten() > 0);
        assertTrue(messages.get(1).getContent().contains("\"$format\":\"parler.infotable.matrix.v1\""));
        assertTrue(messages.get(1).getContent().contains("\"sampleRows\":[["));
    }
}
