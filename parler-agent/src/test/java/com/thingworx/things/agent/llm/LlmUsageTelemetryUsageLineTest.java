package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LlmUsageTelemetryUsageLineTest {

    @Test
    void formatLlmUsageLine_emitsParlerRequestId_alongside_provider_requestId() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        LlmResponse response = new LlmResponse(
                "",
                Collections.emptyList(),
                LlmResponse.FinishReason.STOP,
                1,
                2,
                1,
                2,
                0,
                0,
                0,
                "prov-http-1");
        String line = LlmUsageTelemetry.formatLlmUsageLine(ids, response, 3, 0, 1, null, null, null, "parler-turn-7");
        assertTrue(line.contains(" requestId=prov-http-1"), line);
        assertTrue(line.contains(" parlerRequestId=parler-turn-7"), line);
    }

    @Test
    void formatLlmUsageLine_emitsEmptyParlerRequestIdField_whenNull() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        LlmResponse response = new LlmResponse("", Collections.emptyList(), LlmResponse.FinishReason.STOP, 0, 0);
        String line = LlmUsageTelemetry.formatLlmUsageLine(ids, response, 1, 0, null, null, null, null, null);
        assertTrue(line.contains(" parlerRequestId="), line);
        int idx = line.indexOf(" parlerRequestId=");
        int next = line.indexOf(' ', idx + 1);
        if (next < 0) {
            next = line.length();
        }
        String fieldTail = line.substring(idx + " parlerRequestId=".length(), next);
        assertTrue(fieldTail.isEmpty(), "expected empty value, got: " + fieldTail);
    }

    @Test
    void formatLlmUsageLine_additivityGuard_completionAndReasoningCoexist() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "azure-openai-chat-completions-v5", "gpt-5");
        LlmResponse response = new LlmResponse(
                "",
                Collections.emptyList(),
                LlmResponse.FinishReason.LENGTH,
                100,
                2048,
                100,
                2048,
                0,
                0,
                0,
                "req-sat",
                2048,
                0L);
        String line = LlmUsageTelemetry.formatLlmUsageLine(ids, response, 5, 0, 5, null, null, null, "parler-rid");
        assertTrue(line.contains(" output=2048"), line);
        assertTrue(line.contains(" completionTokens=2048"), line);
        assertTrue(line.contains(" reasoningTokens=2048"), line);
    }

    @Test
    void formatLlmUsageLine_omitsReasoningTokensWhenZero() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        LlmResponse response = new LlmResponse("", Collections.emptyList(), LlmResponse.FinishReason.STOP, 1, 2);
        String line = LlmUsageTelemetry.formatLlmUsageLine(ids, response, 1, 0, null, null, null, null, null);
        assertTrue(line.contains(" completionTokens=2"), line);
        assertFalse(line.contains(" reasoningTokens="), line);
    }

    @Test
    void formatToolSchemaUsageLine_emitsParlerRequestId() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        List<ToolDefinition> defs = Collections.singletonList(
                new ToolDefinition("invoke_service", "d", Collections.singletonMap("type", "object")));
        List<ToolCall> calls = Collections.singletonList(new ToolCall("c1", "invoke_service", "{}"));
        String line = LlmUsageTelemetry.formatToolSchemaUsageLine(ids, 2, defs, calls, "parler-rid-9");
        assertTrue(line.contains(" parlerRequestId=parler-rid-9"), line);
        assertTrue(line.startsWith("LLM_TOOL_SCHEMA_USAGE "), line);
    }
}
