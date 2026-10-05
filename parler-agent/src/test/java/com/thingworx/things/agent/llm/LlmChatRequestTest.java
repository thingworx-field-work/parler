package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ratecontrol.RateControlStatusSink;

class LlmChatRequestTest {

    @Test
    void forAgentRound_defensiveCopiesMessages() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("hello"));
        LlmChatRequest req = LlmChatRequest.forAgentRound(msgs, null, 0.0, 64, null);
        msgs.clear();
        assertEquals(1, req.getMessages().size());
        assertEquals("hello", req.getMessages().get(0).getContent());
        assertFalse(req.isEnableCacheControl(), "the shared factory remains the false request kind");
    }

    @Test
    void copyWithCacheControl_changesOnlyTheRequestKind_andToolPolicyPreservesIt() {
        RateControlStatusSink sink = (w, r, wm, ra) -> { };
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing(
                "P", "AnthropicMessagesProvider", "anthropic-messages-v1", "claude");
        LlmChatRequest base = new LlmChatRequest(
                List.of(ChatMessage.user("hello")),
                List.of(new ToolDefinition("t", "d", null)),
                0.2,
                123,
                false,
                "high",
                "override",
                ids,
                true,
                sink,
                true);

        LlmChatRequest enabled = LlmChatRequest.copyWithCacheControl(base, true);
        assertTrue(enabled.isEnableCacheControl());
        assertEquals(base.getMessages(), enabled.getMessages());
        assertEquals(base.getTools(), enabled.getTools());
        assertEquals(base.getTemperature(), enabled.getTemperature());
        assertEquals(base.getRequestedMaxOutputTokens(), enabled.getRequestedMaxOutputTokens());
        assertEquals(base.getReasoningEffort(), enabled.getReasoningEffort());
        assertEquals(base.getModelOverride(), enabled.getModelOverride());
        assertEquals(base.getUsageWireIdsOverride(), enabled.getUsageWireIdsOverride());
        assertEquals(base.isProviderResolvedOptions(), enabled.isProviderResolvedOptions());
        assertEquals(base.getRateControlStatusSink(), enabled.getRateControlStatusSink());
        assertEquals(base.isProbeMode(), enabled.isProbeMode());
        assertEquals(base.isToolChoiceNone(), enabled.isToolChoiceNone());
        assertTrue(LlmChatRequest.copyWithToolPolicy(enabled, true).isEnableCacheControl());
    }

    @Test
    void copyWithProviderAugmentation_setsResolvedValues() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("hello"));
        LlmChatRequest base = LlmChatRequest.forAgentRound(msgs, null, 0.1, -1, null);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P1", "OpenAIChatV5Provider", "openai-chat-completions-v5", "m");
        LlmChatRequest aug = LlmChatRequest.copyWithProviderAugmentation(base, ids, 8192, "medium");
        assertEquals(8192, aug.getRequestedMaxOutputTokens());
        assertEquals("medium", aug.getReasoningEffort());
        assertEquals(ids, aug.getUsageWireIdsOverride());
        assertTrue(aug.isProviderResolvedOptions());
    }

    @Test
    void copyWithProbeMode_setsFlagAndPreservesThroughAugmentation() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("probe"));
        LlmChatRequest base = LlmChatRequest.forAgentRound(msgs, null, 0.0, 32, null);
        LlmChatRequest probe = LlmChatRequest.copyWithProbeMode(base, true);
        assertTrue(probe.isProbeMode());
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "AnthropicMessagesProvider", "anthropic-messages-v1", "m");
        LlmChatRequest aug = LlmChatRequest.copyWithProviderAugmentation(probe, ids, 32, null);
        assertTrue(aug.isProbeMode());
    }

    @Test
    void copyWithProviderAugmentation_preservesRateControlStatusSink() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("hello"));
        RateControlStatusSink sink = (w, r, wm, ra) -> {
        };
        LlmChatRequest base = LlmChatRequest.forAgentRound(msgs, null, 0.1, -1, null, null, sink);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P1", "OpenAIChatV5Provider", "openai-chat-completions-v5", "m");
        LlmChatRequest aug = LlmChatRequest.copyWithProviderAugmentation(base, ids, 8192, "medium");
        assertEquals(sink, aug.getRateControlStatusSink());
    }

    @Test
    void copyWithProbeMode_preservesRateControlStatusSinkThroughAugmentation() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("probe"));
        RateControlStatusSink sink = (w, r, wm, ra) -> {
        };
        LlmChatRequest base = LlmChatRequest.forAgentRound(msgs, null, 0.0, 32, null, null, sink);
        LlmChatRequest probe = LlmChatRequest.copyWithProbeMode(base, true);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "AnthropicMessagesProvider", "anthropic-messages-v1", "m");
        LlmChatRequest aug = LlmChatRequest.copyWithProviderAugmentation(probe, ids, 32, null);
        assertEquals(sink, aug.getRateControlStatusSink());
        assertTrue(aug.isProbeMode());
    }

    @Test
    void getMessagesIsUnmodifiable() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("x"));
        LlmChatRequest req = LlmChatRequest.forAgentRound(msgs, null, 0.0, 64, null);
        assertThrows(UnsupportedOperationException.class, () -> req.getMessages().add(ChatMessage.user("y")));
    }
}
