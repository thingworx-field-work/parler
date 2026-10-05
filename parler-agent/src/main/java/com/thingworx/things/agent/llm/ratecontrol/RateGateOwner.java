package com.thingworx.things.agent.llm.ratecontrol;

/** Provider identity + config for {@link LLMAPIProviderRateGate}. */
public interface RateGateOwner {

    String getName();

    RateControlConfig loadRateControlConfig();
}
