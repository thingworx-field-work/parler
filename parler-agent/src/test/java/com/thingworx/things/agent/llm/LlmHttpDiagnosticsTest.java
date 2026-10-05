package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thingworx.things.agent.llm.LlmHttpFailureException;

import java.util.Locale;

import org.apache.http.message.BasicHeader;
import org.junit.jupiter.api.Test;

class LlmHttpDiagnosticsTest {

    @Test
    void httpFailureException_carriesSafeHeadersAndRateLimitedFlag() {
        BasicHeader[] headers = { new BasicHeader("Retry-After", "12") };
        LlmHttpFailureException ex = LlmHttpDiagnostics.httpFailureException(
                "OpenAI", 429, "{\"error\":\"rate\"}", headers);
        assertTrue(ex.isRateLimited());
        assertEquals(429, ex.getStatus());
        assertTrue(ex.getSafeHeaders().containsKey("Retry-After"));
        assertTrue(ex.getBodyPreview().contains("rate"));
    }

    @Test
    void allowlist_matchesAnthropicAndOpenAIRateLimitPrefixes() {
        assertTrue(LlmHttpDiagnostics.isAllowedHeaderName("retry-after"));
        assertTrue(LlmHttpDiagnostics.isAllowedHeaderName("X-Request-Id"));
        assertTrue(LlmHttpDiagnostics.isAllowedHeaderName("anthropic-ratelimit-tokens-limit"));
        assertTrue(LlmHttpDiagnostics.isAllowedHeaderName("x-ratelimit-remaining-requests"));
        assertFalse(LlmHttpDiagnostics.isAllowedHeaderName("authorization"));
        assertFalse(LlmHttpDiagnostics.isAllowedHeaderName("set-cookie"));
    }

    @Test
    void extractProviderRequestId_prefersXRequestIdOverMsRequestId() {
        BasicHeader[] headers = {
            new BasicHeader("x-ms-request-id", "ms-1"),
            new BasicHeader("X-Request-Id", "req-2")
        };
        String id = LlmHttpDiagnostics.extractProviderRequestId(headers);
        assertEquals("req-2", id, "extractProviderRequestId should prefer x-request-id over x-ms-request-id");
    }

    @Test
    void extractProviderRequestId_fallsBackToMsRequestId_whenXRequestIdAbsent() {
        BasicHeader[] headers = { new BasicHeader("x-ms-request-id", "ms-1") };
        assertEquals("ms-1", LlmHttpDiagnostics.extractProviderRequestId(headers));
    }

    @Test
    void httpFailureWarnLines_429_includeRateLimitTelemetryAndOmitDisallowedHeaders() {
        BasicHeader[] headers = {
            new BasicHeader("Retry-After", "42"),
            new BasicHeader("x-ratelimit-remaining-requests", "7"),
            new BasicHeader("Authorization", "Bearer secret"),
            new BasicHeader("Cookie", "a=b")
        };
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing(
                "", "OpenAIChatV4Provider", "openai-chat-completions-v4", "gpt-4o");
        String hugeBody = "x".repeat(2000);
        String fail = LlmHttpDiagnostics.buildLlmHttpFailureWarnLine(
                ids, "chat/completions",
                429, 50L, 120_000, 10, 3, 4096, hugeBody, headers);
        String rate = LlmHttpDiagnostics.buildLlmRateLimitWarnLine(
                ids, 429, 50L, headers);

        assertTrue(fail.contains("LLM_HTTP_FAILURE"));
        assertTrue(fail.contains("rate_limited=true"));
        assertTrue(fail.contains("model=gpt-4o"));
        assertTrue(fail.contains("apiShapeId=openai-chat-completions-v4"));
        String failLower = fail.toLowerCase(Locale.ROOT);
        assertTrue(failLower.contains("retry-after=42"));
        assertTrue(fail.length() < hugeBody.length(), "bodyPreview must be capped, not full body");
        assertFalse(failLower.contains("authorization"));
        assertFalse(failLower.contains("cookie="));

        assertTrue(rate.contains("LLM_RATE_LIMIT"));
        String rateLower = rate.toLowerCase(Locale.ROOT);
        assertTrue(rateLower.contains("retry-after=42"));
        assertTrue(rateLower.contains("x-ratelimit-remaining-requests=7"));
        assertFalse(rateLower.contains("authorization"));
    }
}
