package com.thingworx.things.agent.llm.ratecontrol;

/**
 * Local pre-flight rate rejection ({@code docs/agent/rate-control.md} §11).
 */
public final class LlmRateLimitAdmissionException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String providerThingName;
    private final LlmRateLimitAdmissionReason reason;
    private final long retryAfterMs;
    private final long waitMs;
    private final long estimatedInputTokens;
    private final long reservedTokens;
    private final int tokensPerMinuteLimit;
    private final int requestsPerMinuteLimit;
    private final int maxConcurrentRequests;

    public LlmRateLimitAdmissionException(
            String providerThingName,
            LlmRateLimitAdmissionReason reason,
            long retryAfterMs,
            long waitMs,
            long estimatedInputTokens,
            long reservedTokens,
            int tokensPerMinuteLimit,
            int requestsPerMinuteLimit,
            int maxConcurrentRequests) {
        super(buildMessage(providerThingName, reason, retryAfterMs, waitMs));
        this.providerThingName = providerThingName;
        this.reason = reason;
        this.retryAfterMs = retryAfterMs;
        this.waitMs = waitMs;
        this.estimatedInputTokens = estimatedInputTokens;
        this.reservedTokens = reservedTokens;
        this.tokensPerMinuteLimit = tokensPerMinuteLimit;
        this.requestsPerMinuteLimit = requestsPerMinuteLimit;
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    private static String buildMessage(
            String providerThingName,
            LlmRateLimitAdmissionReason reason,
            long retryAfterMs,
            long waitMs) {
        return "LLM provider local rate limit would be exceeded: provider="
                + providerThingName
                + " reason="
                + (reason != null ? reason.wireValue() : "unknown")
                + " retryAfterMs="
                + retryAfterMs
                + " waitMs="
                + waitMs;
    }

    public String getProviderThingName() {
        return providerThingName;
    }

    public LlmRateLimitAdmissionReason getReason() {
        return reason;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    public long getWaitMs() {
        return waitMs;
    }

    public long getEstimatedInputTokens() {
        return estimatedInputTokens;
    }

    public long getReservedTokens() {
        return reservedTokens;
    }

    public int getTokensPerMinuteLimit() {
        return tokensPerMinuteLimit;
    }

    public int getRequestsPerMinuteLimit() {
        return requestsPerMinuteLimit;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }
}
