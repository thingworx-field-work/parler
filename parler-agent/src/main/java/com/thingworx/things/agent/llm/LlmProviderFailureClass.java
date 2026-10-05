package com.thingworx.things.agent.llm;

/**
 * Typed LLM Provider failure classes for G16 retry/fallback (U7 §7.2 / D9).
 * Classification must use typed signals and HTTP/provider metadata, not message-text sniffing alone.
 */
public enum LlmProviderFailureClass {

    /** After Provider gate / Retry-After, within total budget; then fallback. */
    RATE_LIMITED(true, true),

    /** Only when no output was accepted. */
    TIMEOUT_BEFORE_RESPONSE(true, true),

    /** Reviewed 5xx / network classes. */
    TRANSIENT_UPSTREAM(true, true),

    /**
     * Same-provider: no automatic retry loop (SPR-3 may admit one delayed probe). Fallback yes
     * when the next route satisfies eligibility.
     */
    MODEL_UNAVAILABLE(false, true),

    /** Terminal-visible in v1 — do not mask broken configuration. */
    AUTHENTICATION_OR_CONFIG(false, false),

    /** Fix request/schema; another model is not a repair loop. */
    INVALID_REQUEST_OR_SCHEMA(false, false),

    /** Denial itself is never retried away; fallback only to an explicitly eligible route. */
    POLICY_OR_EGRESS_BLOCKED(false, true),

    /** Preserve partial/unknown evidence; no fallback after accepted/emitted output. */
    PARTIAL_OR_UNKNOWN_OUTCOME(false, false),

    /** Honor cancellation immediately. */
    CANCELLED(false, false);

    private final boolean sameProviderRetryEligible;
    private final boolean fallbackEligible;

    LlmProviderFailureClass(boolean sameProviderRetryEligible, boolean fallbackEligible) {
        this.sameProviderRetryEligible = sameProviderRetryEligible;
        this.fallbackEligible = fallbackEligible;
    }

    /** Bounded same-provider retry before considering the next eligible Provider. */
    public boolean sameProviderRetryEligible() {
        return sameProviderRetryEligible;
    }

    /**
     * Whether another eligible Provider may be tried for this round. Still subject to
     * no-fallback-after-partial-output and per-attempt eligibility (D12).
     */
    public boolean fallbackEligible() {
        return fallbackEligible;
    }
}
