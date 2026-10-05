package com.thingworx.things.agent.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured upstream HTTP failure for Provider rate feedback ({@code docs/agent/rate-control.md} §9).
 */
public final class LlmHttpFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final boolean rateLimited;
    private final Map<String, String> safeHeaders;
    private final String bodyPreview;

    public LlmHttpFailureException(String message, int status, boolean rateLimited,
            Map<String, String> safeHeaders, String bodyPreview) {
        super(message);
        this.status = status;
        this.rateLimited = rateLimited;
        this.safeHeaders = safeHeaders != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(safeHeaders))
                : Collections.emptyMap();
        this.bodyPreview = bodyPreview != null ? bodyPreview : "";
    }

    public int getStatus() {
        return status;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }

    public Map<String, String> getSafeHeaders() {
        return safeHeaders;
    }

    public String getBodyPreview() {
        return bodyPreview;
    }
}
