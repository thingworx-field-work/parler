package com.thingworx.things.agent.llm.ratecontrol;

/**
 * Best-effort UI downlink for local provider rate-control waits ({@code docs/agent/rate-control-ui-status.md}).
 * Implementations must not throw; failures are logged by the caller or implementation.
 */
@FunctionalInterface
public interface RateControlStatusSink {

    /**
     * @param waiting {@code true} before a bounded wait; {@code false} when capacity resumes or wait path ends
     * @param reason required when {@code waiting} is {@code true} and known
     * @param waitMs optional wait slice or estimate (non-negative)
     * @param retryAfterMs optional retry hint (non-negative)
     */
    void emit(boolean waiting, LlmRateLimitAdmissionReason reason, long waitMs, long retryAfterMs);
}
