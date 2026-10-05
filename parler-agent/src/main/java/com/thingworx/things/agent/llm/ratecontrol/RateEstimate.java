package com.thingworx.things.agent.llm.ratecontrol;

/** Pre-call admission estimate ({@code docs/agent/rate-control.md} §7). */
public final class RateEstimate {

    public final long estimatedInputTokens;
    public final long requestedMaxOutputTokens;
    public final long reservedTokens;

    public RateEstimate(long estimatedInputTokens, long requestedMaxOutputTokens, long reservedTokens) {
        this.estimatedInputTokens = estimatedInputTokens;
        this.requestedMaxOutputTokens = requestedMaxOutputTokens;
        this.reservedTokens = reservedTokens;
    }
}
