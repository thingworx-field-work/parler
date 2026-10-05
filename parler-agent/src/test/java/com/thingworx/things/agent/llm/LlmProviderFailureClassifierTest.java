package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** SPR-3: §7.2 failure-class table from typed signals (not message sniffing). */
class LlmProviderFailureClassifierTest {

    @Test
    void http429AndRateLimitedFlag() {
        assertEquals(
                LlmProviderFailureClass.RATE_LIMITED,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forHttpStatus(429, false)));
        LlmHttpFailureException ex = new LlmHttpFailureException(
                "limited", 429, true, Map.of("Retry-After", "2"), "");
        assertEquals(
                LlmProviderFailureClass.RATE_LIMITED,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.fromHttp(ex, false)));
    }

    @Test
    void timeoutBeforeResponseAnd408() {
        assertEquals(
                LlmProviderFailureClass.TIMEOUT_BEFORE_RESPONSE,
                LlmProviderFailureClassifier.classify(
                        LlmProviderFailureSignal.forTimeoutBeforeResponse(false)));
        assertEquals(
                LlmProviderFailureClass.TIMEOUT_BEFORE_RESPONSE,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forHttpStatus(408, false)));
    }

    @Test
    void transient5xx() {
        assertEquals(
                LlmProviderFailureClass.TRANSIENT_UPSTREAM,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forHttpStatus(503, false)));
        assertEquals(
                LlmProviderFailureClass.TRANSIENT_UPSTREAM,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forHttpStatus(500, false)));
    }

    @Test
    void authAndInvalidSchemaTerminalNoFallback() {
        assertEquals(
                LlmProviderFailureClass.AUTHENTICATION_OR_CONFIG,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forHttpStatus(401, false)));
        assertEquals(
                LlmProviderFailureClass.AUTHENTICATION_OR_CONFIG,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forHttpStatus(403, false)));
        assertEquals(
                LlmProviderFailureClass.INVALID_REQUEST_OR_SCHEMA,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forHttpStatus(400, false)));
        assertEquals(
                LlmProviderFailureClass.INVALID_REQUEST_OR_SCHEMA,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forHttpStatus(422, false)));
        assertFalse(LlmProviderFailureClass.AUTHENTICATION_OR_CONFIG.fallbackEligible());
        assertFalse(LlmProviderFailureClass.INVALID_REQUEST_OR_SCHEMA.fallbackEligible());
    }

    @Test
    void modelUnavailableAndPolicyEgress() {
        assertEquals(
                LlmProviderFailureClass.MODEL_UNAVAILABLE,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forHttpStatus(404, false)));
        assertEquals(
                LlmProviderFailureClass.POLICY_OR_EGRESS_BLOCKED,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forPolicyOrEgressBlocked()));
        assertFalse(LlmProviderFailureClass.MODEL_UNAVAILABLE.sameProviderRetryEligible());
        assertTrue(LlmProviderFailureClass.MODEL_UNAVAILABLE.fallbackEligible());
    }

    @Test
    void cancellationAndPartialOutputOutrankStatus() {
        assertEquals(
                LlmProviderFailureClass.CANCELLED,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forCancelled()));
        assertEquals(
                LlmProviderFailureClass.PARTIAL_OR_UNKNOWN_OUTCOME,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forHttpStatus(503, true)));
        assertEquals(
                LlmProviderFailureClass.PARTIAL_OR_UNKNOWN_OUTCOME,
                LlmProviderFailureClassifier.classify(
                        LlmProviderFailureSignal.forTimeoutBeforeResponse(true)));
    }

    @Test
    void resolveErrorsAreAuthenticationOrConfig() {
        for (LlmProviderResolveErrorCode code : LlmProviderResolveErrorCode.values()) {
            assertEquals(
                    LlmProviderFailureClass.AUTHENTICATION_OR_CONFIG,
                    LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.fromResolve(code)));
        }
    }

    @Test
    void unknownStatusIsPartialOrUnknown() {
        assertEquals(
                LlmProviderFailureClass.PARTIAL_OR_UNKNOWN_OUTCOME,
                LlmProviderFailureClassifier.classify(LlmProviderFailureSignal.forHttpStatus(418, false)));
    }
}
