package com.thingworx.things.agent.llm.ratecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Conservative character-based token estimator ({@code docs/agent/rate-control.md} §7).
 */
public final class RateControlTokenEstimator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MODEL_OVERHEAD_CHARS = 32;

    private RateControlTokenEstimator() {}

    public static RateEstimate estimate(LlmChatRequest request, RateControlConfig config) {
        long input = estimateInputTokens(request);
        long requestedOut = Math.max(0L, request.getRequestedMaxOutputTokens());
        double mult = Math.max(1.0, config.estimateSafetyMultiplier);
        long scaledInput = (long) Math.ceil(input * mult);
        long reserved;
        if (config.tokenReserveStrategy == TokenReserveStrategy.input_only) {
            reserved = scaledInput;
        } else {
            long scaledOut = (long) Math.ceil(requestedOut * mult);
            reserved = scaledInput + scaledOut;
        }
        return new RateEstimate(input, requestedOut, Math.max(0L, reserved));
    }

    private static long estimateInputTokens(LlmChatRequest request) {
        int chars = MODEL_OVERHEAD_CHARS;
        for (ChatMessage m : request.getMessages()) {
            chars += roleChars(m);
            String content = m.getContent();
            if (content != null) {
                chars += content.length();
            }
            if (m.getToolCallId() != null) {
                chars += m.getToolCallId().length();
            }
            for (ToolCall tc : m.getToolCalls()) {
                if (tc.getId() != null) {
                    chars += tc.getId().length();
                }
                if (tc.getFunctionName() != null) {
                    chars += tc.getFunctionName().length();
                }
                if (tc.getArguments() != null) {
                    chars += tc.getArguments().length();
                }
            }
        }
        for (ToolDefinition t : request.getTools()) {
            if (t.getName() != null) {
                chars += t.getName().length();
            }
            if (t.getDescription() != null) {
                chars += t.getDescription().length();
            }
            if (t.getParametersSchema() != null) {
                try {
                    chars += MAPPER.writeValueAsString(t.getParametersSchema()).length();
                } catch (Exception ignored) {
                    chars += 64;
                }
            }
        }
        return Math.max(1L, (long) Math.ceil(chars / 3.5));
    }

    private static int roleChars(ChatMessage m) {
        return m.getRole() != null ? 8 : 4;
    }
}
