package com.thingworx.things.agent.llm;

import java.util.Objects;

/**
 * Same-provider retry decision after a classified failure (SPR-3). Multi-provider fallback
 * selection is SPR-4; this plan only signals {@link Action#FALLBACK_CANDIDATE}.
 */
public final class ProviderSameProviderRetryPlan {

    public enum Action {
        /** Sleep {@link #delayMs()} then retry the same Provider. */
        RETRY_SAME,
        /** Failure class forbids same-provider retry and forbids fallback (auth/schema/cancel/partial). */
        STOP_TERMINAL,
        /** Budget / wait / wall exhausted. */
        STOP_EXHAUSTED,
        /** Same-provider retry not eligible; another Provider may be considered in SPR-4. */
        FALLBACK_CANDIDATE
    }

    private final Action action;
    private final LlmProviderFailureClass failureClass;
    private final long delayMs;
    private final String reason;
    private final String telemetryToken;

    public ProviderSameProviderRetryPlan(
            Action action,
            LlmProviderFailureClass failureClass,
            long delayMs,
            String reason,
            String telemetryToken) {
        this.action = Objects.requireNonNull(action, "action");
        this.failureClass = Objects.requireNonNull(failureClass, "failureClass");
        this.delayMs = Math.max(0L, delayMs);
        this.reason = reason != null ? reason : "";
        this.telemetryToken = telemetryToken != null ? telemetryToken : "";
    }

    public Action action() {
        return action;
    }

    public LlmProviderFailureClass failureClass() {
        return failureClass;
    }

    public long delayMs() {
        return delayMs;
    }

    public String reason() {
        return reason;
    }

    /** Sanitized retry/classification token for logs / usage correlation (no secrets). */
    public String telemetryToken() {
        return telemetryToken;
    }
}
