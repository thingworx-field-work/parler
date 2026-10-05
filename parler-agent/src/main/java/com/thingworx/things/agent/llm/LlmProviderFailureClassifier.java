package com.thingworx.things.agent.llm;

/**
 * Maps typed {@link LlmProviderFailureSignal} values to {@link LlmProviderFailureClass} per U7 §7.2.
 * Does not sniff exception message text as the sole classifier.
 */
public final class LlmProviderFailureClassifier {

    private LlmProviderFailureClassifier() {}

    public static LlmProviderFailureClass classify(LlmProviderFailureSignal signal) {
        if (signal == null) {
            return LlmProviderFailureClass.PARTIAL_OR_UNKNOWN_OUTCOME;
        }
        // Cancellation and accepted output outrank HTTP status for retry/fallback safety.
        if (signal.cancelled()) {
            return LlmProviderFailureClass.CANCELLED;
        }
        if (signal.outputAccepted()) {
            return LlmProviderFailureClass.PARTIAL_OR_UNKNOWN_OUTCOME;
        }
        if (signal.policyOrEgressBlocked()) {
            return LlmProviderFailureClass.POLICY_OR_EGRESS_BLOCKED;
        }
        if (signal.resolveError().isPresent()) {
            return LlmProviderFailureClass.AUTHENTICATION_OR_CONFIG;
        }
        if (signal.rateLimited()) {
            return LlmProviderFailureClass.RATE_LIMITED;
        }
        if (signal.timeoutBeforeResponse()) {
            return LlmProviderFailureClass.TIMEOUT_BEFORE_RESPONSE;
        }
        Integer status = signal.httpStatus().orElse(null);
        if (status == null) {
            return LlmProviderFailureClass.PARTIAL_OR_UNKNOWN_OUTCOME;
        }
        if (status == 429) {
            return LlmProviderFailureClass.RATE_LIMITED;
        }
        if (status == 408) {
            return LlmProviderFailureClass.TIMEOUT_BEFORE_RESPONSE;
        }
        if (status == 401 || status == 403) {
            return LlmProviderFailureClass.AUTHENTICATION_OR_CONFIG;
        }
        if (status == 400 || status == 422) {
            return LlmProviderFailureClass.INVALID_REQUEST_OR_SCHEMA;
        }
        if (status == 404) {
            return LlmProviderFailureClass.MODEL_UNAVAILABLE;
        }
        if (status >= 500 && status <= 599) {
            return LlmProviderFailureClass.TRANSIENT_UPSTREAM;
        }
        return LlmProviderFailureClass.PARTIAL_OR_UNKNOWN_OUTCOME;
    }
}
