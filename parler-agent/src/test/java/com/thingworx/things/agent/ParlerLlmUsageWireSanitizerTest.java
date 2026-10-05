package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerLlmUsageWireSanitizerTest {

    @Test
    void sanitize_stripsUnknownKeysAndNestedObjects() {
        JSONObject src = new JSONObject();
        src.put("promptTokens", 10);
        src.put("completionTokens", 2);
        src.put("parlerRequestId", "parler-uuid-1");
        src.put("systemPrompt", "secret");
        src.put("nested", new JSONObject().put("x", 1));
        JSONObject out = ParlerLlmUsageWireSanitizer.sanitizeLlmUsageJson(src.toString());
        assertNotNull(out);
        assertEquals(10, out.getInt("promptTokens"));
        assertEquals(2, out.getInt("completionTokens"));
        assertEquals("parler-uuid-1", out.getString("parlerRequestId"));
        assertFalse(out.has("systemPrompt"));
        assertFalse(out.has("nested"));
    }

    @Test
    void sanitize_nullOrEmpty_returnsNull() {
        assertNull(ParlerLlmUsageWireSanitizer.sanitizeLlmUsageJson(null));
        assertNull(ParlerLlmUsageWireSanitizer.sanitizeLlmUsageJson(""));
        assertNull(ParlerLlmUsageWireSanitizer.sanitizeLlmUsageJson("   "));
    }

    @Test
    void sanitize_truncatesLongStrings() {
        StringBuilder longModel = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longModel.append('m');
        }
        JSONObject src = new JSONObject();
        src.put("model", longModel.toString());
        src.put("inputTokens", 1);
        JSONObject out = ParlerLlmUsageWireSanitizer.sanitizeLlmUsageJson(src.toString());
        assertNotNull(out);
        assertEquals(256, out.getString("model").length());
        assertTrue(out.has("inputTokens"));
    }

    @Test
    void sanitize_allowsReasoningTokensTotal() {
        JSONObject src = new JSONObject();
        src.put("completionTokensTotal", 2048);
        src.put("reasoningTokensTotal", 2048);
        JSONObject out = ParlerLlmUsageWireSanitizer.sanitizeLlmUsageJson(src.toString());
        assertNotNull(out);
        assertEquals(2048, out.getInt("completionTokensTotal"));
        assertEquals(2048, out.getInt("reasoningTokensTotal"));
    }

    @Test
    void sanitize_allowsTurnPerformanceNumericAndBooleanSubset() {
        JSONObject src = new JSONObject();
        src.put("model", "m");
        src.put("turnWallMs", 116000);
        src.put("llmWallMs", 110000);
        src.put("toolWallMs", 450);
        src.put("rateWaitMs", 82000);
        src.put("agentIterations", 6);
        src.put("toolCallCount", 4);
        src.put("markerEmitterEnabled", true);
        src.put("noToolFinalAnswerApplied", true);
        src.put("toolExecutionMaxConcurrency", 1);
        src.put("multiToolCallRoundsCount", 1);
        src.put("toolProtocolViolation", "tool_call_after_tool_none");
        src.put("chartExpectedButMissing", true);
        src.put("chartRescueAttempted", true);
        src.put("repetitionBlockedCount", 2);
        src.put("parlerChartWireEmittedCount", 3);
        src.put("presentationPhaseEntered", true);
        src.put("presentationActionsRequested", 2);
        src.put("presentationActionsExecuted", 2);
        src.put("presentationActionsBlocked", 1);
        JSONObject out = ParlerLlmUsageWireSanitizer.sanitizeLlmUsageJson(src.toString());
        assertNotNull(out);
        assertEquals(116000, out.getInt("turnWallMs"));
        assertTrue(out.getBoolean("markerEmitterEnabled"));
        assertTrue(out.getBoolean("chartExpectedButMissing"));
        assertTrue(out.getBoolean("chartRescueAttempted"));
        assertEquals(1, out.getInt("toolExecutionMaxConcurrency"));
        assertEquals(1, out.getInt("multiToolCallRoundsCount"));
        assertEquals("tool_call_after_tool_none", out.getString("toolProtocolViolation"));
        assertEquals(2, out.getInt("repetitionBlockedCount"));
        assertEquals(3, out.getInt("parlerChartWireEmittedCount"));
        assertTrue(out.getBoolean("presentationPhaseEntered"));
        assertEquals(2, out.getInt("presentationActionsRequested"));
        assertEquals(2, out.getInt("presentationActionsExecuted"));
        assertEquals(1, out.getInt("presentationActionsBlocked"));
    }
}
