package com.thingworx.things.agent.llm.ratecontrol;

import java.util.OptionalLong;

/** Normalized rate-control configuration ({@code docs/agent/rate-control.md} §4). */
public final class RateControlConfig {

    private static final int CONTEXT_PLANNING_SINGLE_REQUEST_MARGIN_TOKENS = 1024;
    private static final double TOKEN_ESTIMATOR_CHARS_PER_TOKEN = 3.5d;

    public final RateControlMode mode;
    final int tokensPerMinuteLimit;
    final int requestsPerMinuteLimit;
    final int maxConcurrentRequests;
    final int maxLocalWaitMs;
    final TokenReserveStrategy tokenReserveStrategy;
    final double estimateSafetyMultiplier;
    final int maxSingleRequestTokens;
    final boolean logAdmissions;

    public RateControlConfig(
            RateControlMode mode,
            int tokensPerMinuteLimit,
            int requestsPerMinuteLimit,
            int maxConcurrentRequests,
            int maxLocalWaitMs,
            TokenReserveStrategy tokenReserveStrategy,
            double estimateSafetyMultiplier,
            int maxSingleRequestTokens,
            boolean logAdmissions) {
        this.mode = mode;
        this.tokensPerMinuteLimit = tokensPerMinuteLimit;
        this.requestsPerMinuteLimit = requestsPerMinuteLimit;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.maxLocalWaitMs = maxLocalWaitMs;
        this.tokenReserveStrategy = tokenReserveStrategy;
        this.estimateSafetyMultiplier = estimateSafetyMultiplier;
        this.maxSingleRequestTokens = maxSingleRequestTokens;
        this.logAdmissions = logAdmissions;
    }

    boolean tokenChecksEnabled() {
        return tokensPerMinuteLimit > 0;
    }

    boolean requestChecksEnabled() {
        return requestsPerMinuteLimit > 0;
    }

    boolean concurrencyChecksEnabled() {
        return maxConcurrentRequests > 0;
    }

    int effectiveMaxSingleRequestTokens() {
        if (maxSingleRequestTokens > 0) {
            return maxSingleRequestTokens;
        }
        if (tokenChecksEnabled()) {
            return tokensPerMinuteLimit;
        }
        return 0;
    }

    /**
     * Returns a conservative input-side char budget compatible with the local single-request token reservation cap.
     * The planner still subtracts tool schema, stable prompt, ephemeral prompt, current user, and active tool batch
     * chars from this cap before deciding how much historic transcript/evidence may survive replay.
     */
    public OptionalLong contextPlanningInputCapChars(long resolvedMaxOutputTokens) {
        if (mode != RateControlMode.enforce) {
            return OptionalLong.empty();
        }
        int singleCap = effectiveMaxSingleRequestTokens();
        if (singleCap <= 0) {
            return OptionalLong.empty();
        }
        double mult = Math.max(1.0, estimateSafetyMultiplier);
        long usableCap = Math.max(1L, (long) singleCap - CONTEXT_PLANNING_SINGLE_REQUEST_MARGIN_TOKENS);
        long inputReserveCap = usableCap;
        if (tokenReserveStrategy != TokenReserveStrategy.input_only) {
            long outputReserve = (long) Math.ceil(Math.max(0L, resolvedMaxOutputTokens) * mult);
            inputReserveCap -= outputReserve;
        }
        if (inputReserveCap <= 0) {
            return OptionalLong.of(1L);
        }
        long inputTokens = (long) Math.floor(inputReserveCap / mult);
        long inputChars = Math.max(1L, Math.round(inputTokens * TOKEN_ESTIMATOR_CHARS_PER_TOKEN));
        return OptionalLong.of(inputChars);
    }

    int effectiveMaxLocalWaitMs() {
        return maxLocalWaitMs;
    }
}
