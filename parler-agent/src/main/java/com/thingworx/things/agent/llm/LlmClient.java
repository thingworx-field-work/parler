package com.thingworx.things.agent.llm;

import java.util.OptionalLong;

/**
 * Unified interface for LLM API providers used by the agent loop.
 * Implementations: Azure OpenAI chat completions, OpenAI-compatible chat completions, Anthropic Messages.
 */
public interface LlmClient {

    /**
     * Send a chat request (see {@link LlmChatRequest}).
     */
    LlmResponse chat(LlmChatRequest request) throws Exception;

    /**
     * Wire dimensions for {@link com.thingworx.things.agent.StreamTokenUsage} and {@code LLM_*} log lines (§10.2).
     */
    LlmUsageWireIds usageWireIds();

    /**
     * Wire ids for the effective model or deployment string used on this LLM round (updates {@code model} and, for
     * legacy OpenAI/Azure Chat Completions, {@code apiShapeId} v4 vs v5). Agent paths SHOULD use this instead of
     * {@link LlmUsageWireIds#withModel(String)} on {@link #usageWireIds()} alone.
     */
    default LlmUsageWireIds usageWireIdsForEffectiveModel(String effectiveModel) {
        String m = effectiveModel != null ? effectiveModel : "";
        return usageWireIds().withModel(m);
    }

    /**
     * Optional provider/rate-control input budget for context planning, expressed in Java chars and scoped to the
     * current requested max-output setting. Non-provider clients return empty and rely on model/config caps only.
     */
    default OptionalLong contextPlanningInputCapChars(long requestedMaxOutputTokens, String modelOverride) {
        return OptionalLong.empty();
    }

    /**
     * Test connectivity to the LLM endpoint.
     */
    boolean healthCheck();
}
