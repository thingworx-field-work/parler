package com.thingworx.things.agent.llm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import org.apache.http.Header;
import org.slf4j.Logger;

/**
 * Shared HTTP failure / rate-limit diagnostics for LLM providers ({@code docs/agent/llm-token-budget.md} Phase 1).
 * Never logs API keys or full bodies; headers pass an allowlist only.
 */
public final class LlmHttpDiagnostics {

    private static final int BODY_PREVIEW_MAX = 480;

    private LlmHttpDiagnostics() {}

    /**
     * Structured failure for Provider rate feedback ({@code docs/agent/rate-control.md} §9).
     */
    public static LlmHttpFailureException httpFailureException(
            String label, int status, String responseBody, Header[] headers) {
        boolean rateLimited = status == 429;
        String preview = singleLinePreview(responseBody);
        Map<String, String> safe = collectSafeHeaders(headers);
        return new LlmHttpFailureException(
                label + " request failed: HTTP " + status
                        + " (see LLM_HTTP_FAILURE log for capped body preview and safeHeaders"
                        + (rateLimited ? "; LLM_RATE_LIMIT emitted when rate_limited=true" : "")
                        + ")",
                status,
                rateLimited,
                safe,
                preview);
    }

    /** Allowlisted header names for structured exceptions and logs. */
    public static Map<String, String> collectSafeHeaders(Header[] headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        for (Header h : headers) {
            if (h == null || h.getName() == null) {
                continue;
            }
            if (!isAllowedHeaderName(h.getName())) {
                continue;
            }
            out.put(h.getName(), h.getValue() != null ? h.getValue() : "");
        }
        return out;
    }

    public static String extractProviderRequestId(Header[] headers) {
        if (headers == null) {
            return null;
        }
        for (String key : new String[] {
                "request-id", "x-request-id", "x-ms-request-id", "apim-request-id"
        }) {
            String v = firstHeaderValue(headers, key);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String firstHeaderValue(Header[] headers, String nameLower) {
        for (Header h : headers) {
            if (h != null && h.getName() != null && nameLower.equalsIgnoreCase(h.getName())) {
                return h.getValue();
            }
        }
        return null;
    }

    public static void logHttpFailure(Logger log, LlmUsageWireIds ids, String urlClass,
            int status, long elapsedMs, int configuredTimeoutMs, int messageCount, int toolCount, int maxTokens,
            String responseBody, Header[] headers) {
        log.warn(buildLlmHttpFailureWarnLine(ids, urlClass, status, elapsedMs, configuredTimeoutMs,
                messageCount, toolCount, maxTokens, responseBody, headers));
        if (status == 429) {
            log.warn(buildLlmRateLimitWarnLine(ids, status, elapsedMs, headers));
        }
    }

    /**
     * Exact {@code LLM_HTTP_FAILURE} warn line (same text as {@link #logHttpFailure}). Package-private for unit tests.
     */
    static String buildLlmHttpFailureWarnLine(LlmUsageWireIds ids, String urlClass,
            int status, long elapsedMs, int configuredTimeoutMs, int messageCount, int toolCount, int maxTokens,
            String responseBody, Header[] headers) {
        String preview = singleLinePreview(responseBody);
        String safeHeaders = formatAllowedHeaders(headers);
        return "LLM_HTTP_FAILURE providerThingName=" + nullToEmpty(ids != null ? ids.getProviderThingName() : "")
                + " providerTemplateName=" + nullToEmpty(ids != null ? ids.getProviderTemplateName() : "")
                + " apiShapeId=" + nullToEmpty(ids != null ? ids.getApiShapeId() : "")
                + " model=" + nullToEmpty(ids != null ? ids.getModel() : "")
                + " urlClass=" + nullToEmpty(urlClass)
                + " status=" + status
                + " rate_limited=" + (status == 429)
                + " elapsedMs=" + elapsedMs
                + " timeoutMs=" + configuredTimeoutMs
                + " messages=" + messageCount
                + " tools=" + toolCount
                + " maxTokens=" + maxTokens
                + " bodyPreview=" + preview
                + " safeHeaders=[" + safeHeaders + "]";
    }

    /**
     * Exact {@code LLM_RATE_LIMIT} warn line for HTTP 429 (same text as {@link #logHttpFailure}). Package-private for unit tests.
     */
    static String buildLlmRateLimitWarnLine(LlmUsageWireIds ids, int status, long elapsedMs,
            Header[] headers) {
        String safeHeaders = formatAllowedHeaders(headers);
        return "LLM_RATE_LIMIT providerThingName=" + nullToEmpty(ids != null ? ids.getProviderThingName() : "")
                + " providerTemplateName=" + nullToEmpty(ids != null ? ids.getProviderTemplateName() : "")
                + " apiShapeId=" + nullToEmpty(ids != null ? ids.getApiShapeId() : "")
                + " model=" + nullToEmpty(ids != null ? ids.getModel() : "")
                + " status=" + status
                + " elapsedMs=" + elapsedMs
                + " safeHeaders=[" + safeHeaders + "]";
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static String singleLinePreview(String body) {
        if (body == null) {
            return "";
        }
        String one = body.replace('\r', ' ').replace('\n', ' ').trim();
        if (one.length() <= BODY_PREVIEW_MAX) {
            return one;
        }
        return one.substring(0, BODY_PREVIEW_MAX) + "...";
    }

    /** Visible for unit tests; safe-header allowlist for {@link #logHttpFailure}. */
    public static boolean isAllowedHeaderName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("retry-after")
                || n.equals("request-id")
                || n.equals("x-request-id")
                || n.equals("x-ms-request-id")
                || n.equals("apim-request-id")
                || n.equals("openai-version")
                || n.equals("x-ms-deployment-target")) {
            return true;
        }
        return n.startsWith("anthropic-ratelimit-") || n.startsWith("x-ratelimit-");
    }

    private static String formatAllowedHeaders(Header[] headers) {
        if (headers == null || headers.length == 0) {
            return "";
        }
        StringJoiner j = new StringJoiner("; ");
        for (Header h : headers) {
            if (h == null || h.getName() == null) {
                continue;
            }
            if (!isAllowedHeaderName(h.getName())) {
                continue;
            }
            j.add(h.getName() + "=" + (h.getValue() != null ? h.getValue() : ""));
        }
        return j.toString();
    }
}
