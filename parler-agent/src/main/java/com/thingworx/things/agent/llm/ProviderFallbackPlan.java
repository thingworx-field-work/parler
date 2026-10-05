package com.thingworx.things.agent.llm;

import java.util.Objects;
import java.util.Optional;

/**
 * Multi-provider fallback decision after a same-provider {@code FALLBACK_CANDIDATE} (SPR-4).
 */
public final class ProviderFallbackPlan {

    public enum Action {
        /** Try the next eligible Provider Thing name. */
        TRY_PROVIDER,
        /** No eligible Provider remains — emit {@link ProviderDegradedOutcome}. */
        DEGRADED,
        /** Fallback forbidden (partial/unknown output, terminal class, or cancelled). */
        FORBIDDEN
    }

    private final Action action;
    private final String nextProviderThingName;
    private final String reason;
    private final ProviderDegradedOutcome degraded;

    private ProviderFallbackPlan(
            Action action, String nextProviderThingName, String reason, ProviderDegradedOutcome degraded) {
        this.action = Objects.requireNonNull(action, "action");
        this.nextProviderThingName = nextProviderThingName;
        this.reason = reason != null ? reason : "";
        this.degraded = degraded;
    }

    public static ProviderFallbackPlan tryProvider(String providerThingName, String reason) {
        return new ProviderFallbackPlan(Action.TRY_PROVIDER, providerThingName, reason, null);
    }

    public static ProviderFallbackPlan degraded(ProviderDegradedOutcome outcome, String reason) {
        return new ProviderFallbackPlan(Action.DEGRADED, null, reason, outcome);
    }

    public static ProviderFallbackPlan forbidden(String reason) {
        return new ProviderFallbackPlan(Action.FORBIDDEN, null, reason, null);
    }

    public Action action() {
        return action;
    }

    public Optional<String> nextProviderThingName() {
        return Optional.ofNullable(nextProviderThingName);
    }

    public String reason() {
        return reason;
    }

    public Optional<ProviderDegradedOutcome> degraded() {
        return Optional.ofNullable(degraded);
    }
}
