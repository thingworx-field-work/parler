package com.thingworx.things.agent.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed inputs for {@link LlmProviderFailureClassifier}. Prefer HTTP/provider metadata and
 * explicit flags over message-text sniffing (U7 §7.2 / D9).
 */
public final class LlmProviderFailureSignal {

    private final Integer httpStatus;
    private final boolean rateLimited;
    private final boolean cancelled;
    private final boolean timeoutBeforeResponse;
    private final boolean outputAccepted;
    private final boolean policyOrEgressBlocked;
    private final LlmProviderResolveErrorCode resolveError;
    private final Map<String, String> safeHeaders;

    private LlmProviderFailureSignal(
            Integer httpStatus,
            boolean rateLimited,
            boolean cancelled,
            boolean timeoutBeforeResponse,
            boolean outputAccepted,
            boolean policyOrEgressBlocked,
            LlmProviderResolveErrorCode resolveError,
            Map<String, String> safeHeaders) {
        this.httpStatus = httpStatus;
        this.rateLimited = rateLimited;
        this.cancelled = cancelled;
        this.timeoutBeforeResponse = timeoutBeforeResponse;
        this.outputAccepted = outputAccepted;
        this.policyOrEgressBlocked = policyOrEgressBlocked;
        this.resolveError = resolveError;
        this.safeHeaders = safeHeaders == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(safeHeaders));
    }

    public static LlmProviderFailureSignal forCancelled() {
        return new LlmProviderFailureSignal(null, false, true, false, false, false, null, Map.of());
    }

    public static LlmProviderFailureSignal fromHttp(LlmHttpFailureException ex, boolean outputAccepted) {
        Objects.requireNonNull(ex, "ex");
        return new LlmProviderFailureSignal(
                ex.getStatus(),
                ex.isRateLimited() || ex.getStatus() == 429,
                false,
                false,
                outputAccepted,
                false,
                null,
                ex.getSafeHeaders());
    }

    public static LlmProviderFailureSignal forTimeoutBeforeResponse(boolean outputAccepted) {
        return new LlmProviderFailureSignal(null, false, false, true, outputAccepted, false, null, Map.of());
    }

    public static LlmProviderFailureSignal fromResolve(LlmProviderResolveErrorCode code) {
        Objects.requireNonNull(code, "code");
        return new LlmProviderFailureSignal(null, false, false, false, false, false, code, Map.of());
    }

    public static LlmProviderFailureSignal forPolicyOrEgressBlocked() {
        return new LlmProviderFailureSignal(null, false, false, false, false, true, null, Map.of());
    }

    public static LlmProviderFailureSignal forHttpStatus(int status, boolean outputAccepted) {
        return new LlmProviderFailureSignal(
                status, status == 429, false, false, outputAccepted, false, null, Map.of());
    }

    public static LlmProviderFailureSignal forHttpStatus(
            int status, boolean rateLimited, Map<String, String> safeHeaders, boolean outputAccepted) {
        return new LlmProviderFailureSignal(
                status, rateLimited || status == 429, false, false, outputAccepted, false, null, safeHeaders);
    }

    public Optional<Integer> httpStatus() {
        return Optional.ofNullable(httpStatus);
    }

    public boolean rateLimited() {
        return rateLimited;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public boolean timeoutBeforeResponse() {
        return timeoutBeforeResponse;
    }

    public boolean outputAccepted() {
        return outputAccepted;
    }

    public boolean policyOrEgressBlocked() {
        return policyOrEgressBlocked;
    }

    public Optional<LlmProviderResolveErrorCode> resolveError() {
        return Optional.ofNullable(resolveError);
    }

    public Map<String, String> safeHeaders() {
        return safeHeaders;
    }
}
