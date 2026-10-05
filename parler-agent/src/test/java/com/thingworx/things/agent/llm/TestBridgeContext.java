package com.thingworx.things.agent.llm;

import java.util.Optional;

import com.thingworx.things.agent.llm.ratecontrol.LLMAPIProviderRateGate;
import com.thingworx.things.agent.llm.ratecontrol.RateControlConfig;
import com.thingworx.things.agent.llm.ratecontrol.RateControlMode;
import com.thingworx.things.agent.llm.ratecontrol.RateControlTokenEstimator;
import com.thingworx.things.agent.llm.ratecontrol.RateEstimate;
import com.thingworx.things.agent.llm.ratecontrol.RateGateOwner;
import com.thingworx.things.agent.llm.ratecontrol.TokenReserveStrategy;

/** Fake {@link ProviderBridgeContext} for bridge unit tests (no ThingWorx Thing). */
public final class TestBridgeContext implements ProviderBridgeContext, RateGateOwner {

    private final RateControlConfig rateConfig;
    private final LLMAPIProviderRateGate rateGate;

    public TestBridgeContext(RateControlConfig rateConfig) {
        this.rateConfig = rateConfig;
        this.rateGate = new LLMAPIProviderRateGate(this);
    }

    @Override
    public String getName() {
        return "TestBridgeProvider";
    }

    @Override
    public String getThingTemplateName() {
        return "TestBridgeProviderTemplate";
    }

    @Override
    public String getApiShapeId() {
        return "openai-chat-completions-v4";
    }

    @Override
    public int resolveMaxOutputTokens(LlmChatRequest request) {
        return request != null && request.getRequestedMaxOutputTokens() > 0
                ? (int) request.getRequestedMaxOutputTokens()
                : 32;
    }

    @Override
    public Optional<String> resolveReasoningEffort(LlmChatRequest request) {
        return Optional.empty();
    }

    @Override
    public RateControlConfig loadRateControlConfig() {
        return rateConfig;
    }

    @Override
    public RateEstimate estimateRateUsage(LlmChatRequest augmented) {
        return RateControlTokenEstimator.estimate(augmented, rateConfig);
    }

    @Override
    public LLMAPIProviderRateGate getRateGate() {
        return rateGate;
    }

    public static RateControlConfig disabledRateConfig() {
        return new RateControlConfig(
                RateControlMode.disabled, 0, 0, 0, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
    }
}

