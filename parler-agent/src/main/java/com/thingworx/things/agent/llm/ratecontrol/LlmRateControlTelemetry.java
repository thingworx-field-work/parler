package com.thingworx.things.agent.llm.ratecontrol;

import org.slf4j.Logger;

import com.thingworx.things.agent.llm.LlmUsageWireIds;

/**
 * Local gate telemetry ({@code docs/agent/rate-control.md} §10).
 */
final class LlmRateControlTelemetry {

    private LlmRateControlTelemetry() {}

    static void logAdmission(
            Logger log,
            LlmUsageWireIds ids,
            RateControlMode mode,
            String action,
            RateEstimate estimate,
            RateControlConfig config,
            double tokensRemaining,
            double requestsRemaining,
            int inflight,
            long waitMs) {
        if (log == null) {
            return;
        }
        log.warn("LLM_RATE_ADMISSION providerThingName=" + nz(ids != null ? ids.getProviderThingName() : "")
                + " providerTemplateName=" + nz(ids != null ? ids.getProviderTemplateName() : "")
                + " apiShapeId=" + nz(ids != null ? ids.getApiShapeId() : "")
                + " model=" + nz(ids != null ? ids.getModel() : "")
                + " mode=" + mode.name()
                + " action=" + action
                + " estimatedInputTokens=" + estimate.estimatedInputTokens
                + " reservedTokens=" + estimate.reservedTokens
                + " requestedMaxOutputTokens=" + estimate.requestedMaxOutputTokens
                + " tokensPerMinuteLimit=" + config.tokensPerMinuteLimit
                + " requestsPerMinuteLimit=" + config.requestsPerMinuteLimit
                + " maxConcurrentRequests=" + config.maxConcurrentRequests
                + " tokensRemaining=" + tokensRemaining
                + " requestsRemaining=" + requestsRemaining
                + " inflight=" + inflight
                + " waitMs=" + waitMs);
    }

    static void logRejection(
            Logger log,
            LlmUsageWireIds ids,
            LlmRateLimitAdmissionReason reason,
            RateEstimate estimate,
            RateControlConfig config,
            double tokensRemaining,
            double requestsRemaining,
            long retryAfterMs,
            long waitMs) {
        if (log == null) {
            return;
        }
        log.warn("LLM_RATE_REJECTION providerThingName=" + nz(ids != null ? ids.getProviderThingName() : "")
                + " providerTemplateName=" + nz(ids != null ? ids.getProviderTemplateName() : "")
                + " apiShapeId=" + nz(ids != null ? ids.getApiShapeId() : "")
                + " model=" + nz(ids != null ? ids.getModel() : "")
                + " reason=" + (reason != null ? reason.wireValue() : "")
                + " estimatedInputTokens=" + estimate.estimatedInputTokens
                + " reservedTokens=" + estimate.reservedTokens
                + " tokensPerMinuteLimit=" + config.tokensPerMinuteLimit
                + " requestsPerMinuteLimit=" + config.requestsPerMinuteLimit
                + " maxConcurrentRequests=" + config.maxConcurrentRequests
                + " tokensRemaining=" + tokensRemaining
                + " requestsRemaining=" + requestsRemaining
                + " retryAfterMs=" + retryAfterMs
                + " waitMs=" + waitMs
                + " maxLocalWaitMs=" + config.effectiveMaxLocalWaitMs());
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }
}
