package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext;

class StreamTokenUsageTest {

    @Test
    void zeroHasEmptyUsageJson() {
        assertEquals("", StreamTokenUsage.ZERO.getLlmUsageJson());
        assertEquals(0, StreamTokenUsage.ZERO.getPromptTokens());
    }

    @Test
    void legacyTwoIntConstructorLeavesUsageJsonEmpty() {
        StreamTokenUsage u = new StreamTokenUsage(3, 4);
        assertEquals("", u.getLlmUsageJson());
        assertEquals(3, u.getPromptTokens());
        assertEquals(4, u.getCompletionTokens());
    }

    @Test
    void fromLlmResponseSerializesStableKeys() {
        LlmResponse r = new LlmResponse(
                "hi",
                Collections.<ToolCall>emptyList(),
                LlmResponse.FinishReason.STOP,
                100,
                20,
                50,
                20,
                10,
                5,
                30,
                "req-xyz",
                0L);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing(
                "", "AnthropicMessagesProvider", "anthropic-messages-v1", "claude-test");
        StreamTokenUsage u = StreamTokenUsage.fromLlmResponse(r, ids);
        assertEquals(100, u.getPromptTokens());
        assertEquals(20, u.getCompletionTokens());
        String j = u.getLlmUsageJson();
        assertTrue(j.contains("\"providerThingName\":\"\""));
        assertTrue(j.contains("\"providerTemplateName\":\"AnthropicMessagesProvider\""));
        assertTrue(j.contains("\"apiShapeId\":\"anthropic-messages-v1\""));
        assertTrue(j.contains("\"model\":\"claude-test\""));
        assertTrue(j.contains("\"requestId\":\"req-xyz\""));
        assertTrue(j.contains("\"parlerRequestId\":\"\""));
        assertTrue(j.contains("\"inputTokens\":50"));
        assertTrue(j.contains("\"cacheReadInputTokens\":10"));
        assertTrue(j.contains("\"cacheCreationInputTokens\":5"));
        assertFalse(j.contains("\"cachedPromptTokens\""));
        assertFalse(j.contains("\"reasoningTokens\""));
    }

    @Test
    void fromLlmResponse_includesParlerRequestIdFromAgentToolContext() {
        try {
            AgentToolContext.setParlerStreamIds("parler-turn-join-key", "RemoteThingName");
            LlmResponse r = new LlmResponse(
                    "hi",
                    Collections.<ToolCall>emptyList(),
                    LlmResponse.FinishReason.STOP,
                    1,
                    1,
                    1,
                    1,
                    0,
                    0,
                    0,
                    "prov-req-1",
                    0L);
            LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing(
                    "", "T", "openai-chat-completions-v5", "m");
            StreamTokenUsage u = StreamTokenUsage.fromLlmResponse(r, ids);
            String j = u.getLlmUsageJson();
            assertTrue(j.contains("\"parlerRequestId\":\"parler-turn-join-key\""), j);
            assertTrue(j.contains("\"requestId\":\"prov-req-1\""), j);
        } finally {
            AgentToolContext.clear();
        }
    }

    @Test
    void fromLlmResponseSerializesOpenAiStyleKeys() {
        LlmResponse r = new LlmResponse(
                "hi",
                Collections.<ToolCall>emptyList(),
                LlmResponse.FinishReason.STOP,
                30000,
                800,
                30000,
                800,
                0,
                0,
                24000,
                "req-azure",
                0L);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing(
                "", "AzureOpenAIChatV5Provider", "azure-openai-chat-completions-v5", "gpt-5.4-mini");
        StreamTokenUsage u = StreamTokenUsage.fromLlmResponse(r, ids);
        assertEquals(30000, u.getPromptTokens());
        assertEquals(800, u.getCompletionTokens());
        String j = u.getLlmUsageJson();
        assertTrue(j.contains("\"providerThingName\":\"\""));
        assertTrue(j.contains("\"providerTemplateName\":\"AzureOpenAIChatV5Provider\""));
        assertTrue(j.contains("\"apiShapeId\":\"azure-openai-chat-completions-v5\""));
        assertTrue(j.contains("\"cachedPromptTokens\":24000"));
        assertFalse(j.contains("\"cacheReadInputTokens\""));
        assertFalse(j.contains("\"cacheCreationInputTokens\""));
        assertFalse(j.contains("\"reasoningTokens\""));
        assertTrue(j.contains("\"parlerRequestId\":\"\""));
    }

    @Test
    void combine_sumsNumericTokensAndPrefersLatestStringMetadata() {
        LlmResponse r1 = new LlmResponse(
                "a",
                Collections.<ToolCall>emptyList(),
                LlmResponse.FinishReason.STOP,
                10,
                2,
                10,
                2,
                0,
                0,
                0,
                "req-a",
                0L);
        LlmResponse r2 = new LlmResponse(
                "b",
                Collections.<ToolCall>emptyList(),
                LlmResponse.FinishReason.STOP,
                5,
                1,
                5,
                1,
                0,
                0,
                0,
                "req-b",
                0L);
        LlmUsageWireIds ids1 = LlmUsageWireIds.forProviderThing("A", "T1", "openai-chat-completions-v5", "m1");
        LlmUsageWireIds ids2 = LlmUsageWireIds.forProviderThing("B", "T2", "openai-chat-completions-v5", "m2");
        StreamTokenUsage u1 = StreamTokenUsage.fromLlmResponse(r1, ids1);
        StreamTokenUsage u2 = StreamTokenUsage.fromLlmResponse(r2, ids2);
        StreamTokenUsage c = StreamTokenUsage.combine(u1, u2);
        assertEquals(15, c.getPromptTokens());
        assertEquals(3, c.getCompletionTokens());
        String j = c.getLlmUsageJson();
        assertTrue(j.contains("\"providerThingName\":\"B\""), j);
        assertTrue(j.contains("\"requestId\":\"req-b\""), j);
        assertTrue(j.contains("\"parlerRequestId\":\"\""), j);
        assertTrue(j.contains("\"promptTokens\":15"), j);
    }

    @Test
    void combine_prefersLatestNonEmptyParlerRequestId() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v5", "m");
        StreamTokenUsage u1;
        try {
            AgentToolContext.setParlerStreamIds("parler-a", "r");
            u1 = StreamTokenUsage.fromLlmResponse(
                    new LlmResponse("a", Collections.<ToolCall>emptyList(), LlmResponse.FinishReason.STOP, 1, 1, 1, 1, 0,
                            0, 0, "req-a", 0L),
                    ids);
        } finally {
            AgentToolContext.clear();
        }
        StreamTokenUsage u2;
        try {
            AgentToolContext.setParlerStreamIds("parler-b", "r");
            u2 = StreamTokenUsage.fromLlmResponse(
                    new LlmResponse("b", Collections.<ToolCall>emptyList(), LlmResponse.FinishReason.STOP, 1, 1, 1, 1, 0,
                            0, 0, "req-b", 0L),
                    ids);
        } finally {
            AgentToolContext.clear();
        }
        String j = StreamTokenUsage.combine(u1, u2).getLlmUsageJson();
        assertTrue(j.contains("\"parlerRequestId\":\"parler-b\""), j);
        assertTrue(j.contains("\"requestId\":\"req-b\""), j);
    }

    @Test
    void combine_withZero_returnsOther() {
        StreamTokenUsage a = new StreamTokenUsage(7, 1);
        assertEquals(7, StreamTokenUsage.combine(a, StreamTokenUsage.ZERO).getPromptTokens());
        assertEquals(7, StreamTokenUsage.combine(StreamTokenUsage.ZERO, a).getPromptTokens());
    }

    @Test
    void fromLlmResponse_includesRateWaitWhenPositive() {
        LlmResponse r = new LlmResponse(
                "x",
                Collections.<ToolCall>emptyList(),
                LlmResponse.FinishReason.STOP,
                1,
                1,
                1,
                1,
                0,
                0,
                0,
                "rid-w",
                44L);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("", "T", "openai-chat-completions-v5", "m");
        StreamTokenUsage u = StreamTokenUsage.fromLlmResponse(r, ids);
        assertTrue(u.getLlmUsageJson().contains("\"rateWaitMs\":44"));
    }

    @Test
    void combine_sumsReasoningTokens_preservesCompletionTotals() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("", "T", "azure-openai-chat-completions-v5", "m");
        LlmResponse r1 = new LlmResponse(
                "a",
                Collections.<ToolCall>emptyList(),
                LlmResponse.FinishReason.STOP,
                10,
                2048,
                10,
                2048,
                0,
                0,
                0,
                "req-a",
                500,
                0L);
        LlmResponse r2 = new LlmResponse(
                "b",
                Collections.<ToolCall>emptyList(),
                LlmResponse.FinishReason.STOP,
                5,
                2048,
                5,
                2048,
                0,
                0,
                0,
                "req-b",
                1200,
                0L);
        StreamTokenUsage c = StreamTokenUsage.combine(
                StreamTokenUsage.fromLlmResponse(r1, ids),
                StreamTokenUsage.fromLlmResponse(r2, ids));
        assertEquals(4096, c.getCompletionTokens());
        String j = c.getLlmUsageJson();
        assertTrue(j.contains("\"reasoningTokens\":1700"), j);
        assertTrue(j.contains("\"completionTokens\":4096"), j);
    }

    @Test
    void fromLlmResponse_includesReasoningTokensWhenPositive() {
        LlmResponse r = new LlmResponse(
                "x",
                Collections.<ToolCall>emptyList(),
                LlmResponse.FinishReason.LENGTH,
                1,
                2048,
                1,
                2048,
                0,
                0,
                0,
                "rid-r",
                2048,
                0L);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("", "T", "azure-openai-chat-completions-v5", "m");
        String j = StreamTokenUsage.fromLlmResponse(r, ids).getLlmUsageJson();
        assertTrue(j.contains("\"reasoningTokens\":2048"), j);
        assertTrue(j.contains("\"completionTokens\":2048"), j);
    }

    @Test
    void withParlerTurnDiagnostics_mergesCacheHit() {
        LlmResponse r = new LlmResponse(
                "x",
                Collections.<ToolCall>emptyList(),
                LlmResponse.FinishReason.STOP,
                2,
                1,
                2,
                1,
                0,
                0,
                0,
                "rid-d",
                0L);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("", "T", "openai-chat-completions-v5", "m");
        StreamTokenUsage u = StreamTokenUsage.fromLlmResponse(r, ids);
        StreamTokenUsage d = StreamTokenUsage.withParlerTurnDiagnostics(u, 9L, "unknown");
        String j = d.getLlmUsageJson();
        assertTrue(j.contains("\"rateWaitMs\":9"), j);
        assertTrue(j.contains("\"firstToolCallCacheHit\":\"unknown\""), j);
    }
}
