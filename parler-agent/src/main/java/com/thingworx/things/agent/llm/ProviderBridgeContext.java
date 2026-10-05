package com.thingworx.things.agent.llm;

import java.util.Optional;

import com.thingworx.things.agent.llm.ratecontrol.LLMAPIProviderRateGate;
import com.thingworx.things.agent.llm.ratecontrol.RateControlConfig;
import com.thingworx.things.agent.llm.ratecontrol.RateEstimate;

/** Provider identity and rate-control surface used by {@link ProviderLlmClientBridge}. */
public interface ProviderBridgeContext {

    String getName();

    String getThingTemplateName();

    String getApiShapeId();

    int resolveMaxOutputTokens(LlmChatRequest request);

    Optional<String> resolveReasoningEffort(LlmChatRequest request);

    RateControlConfig loadRateControlConfig();

    RateEstimate estimateRateUsage(LlmChatRequest augmented);

    LLMAPIProviderRateGate getRateGate();
}

