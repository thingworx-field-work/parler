package com.thingworx.things.agent.llm.ratecontrol;

/** Closed rejection reasons ({@code docs/agent/rate-control.md} §4). */
public enum LlmRateLimitAdmissionReason {
    tokens_per_minute,
    requests_per_minute,
    concurrency,
    upstream_blocked,
    single_request_too_large;

    public String wireValue() {
        return name();
    }

    public static LlmRateLimitAdmissionReason parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String n = raw.trim().toLowerCase();
        for (LlmRateLimitAdmissionReason r : values()) {
            if (r.name().equals(n)) {
                return r;
            }
        }
        return null;
    }
}
