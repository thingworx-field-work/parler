package com.thingworx.things.agent.metrics;

/**
 * Bounded {@code token_kind} dimension for provider token metrics. Each value is a cumulative count of one defined
 * usage field; values overlap (for example {@code input} includes cached input), so do not sum across kinds.
 */
public enum TokenKind {
    INPUT("input"),
    OUTPUT("output"),
    TOTAL("total"),
    INPUT_UNCACHED("input_uncached"),
    INPUT_CACHE_READ("input_cache_read"),
    INPUT_CACHE_WRITE("input_cache_write"),
    INPUT_CACHE_WRITE_5M("input_cache_write_5m"),
    INPUT_CACHE_WRITE_1H("input_cache_write_1h"),
    REASONING("reasoning"),
    INPUT_AUDIO("input_audio"),
    OUTPUT_AUDIO("output_audio"),
    OUTPUT_ACCEPTED_PREDICTION("output_accepted_prediction"),
    OUTPUT_REJECTED_PREDICTION("output_rejected_prediction");

    private final String wireValue;

    TokenKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
