package com.thingworx.things.agent.llm;

/** Anthropic Messages sampling-parameter emission mode. */
public enum AnthropicSamplingParametersMode {
    legacy,
    omit;

    public static AnthropicSamplingParametersMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return legacy;
        }
        String normalized = raw.trim().toLowerCase();
        for (AnthropicSamplingParametersMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "samplingParametersMode must be legacy or omit (got " + raw.trim() + ")");
    }
}
