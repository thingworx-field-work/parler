package com.thingworx.things.agent.metrics;

/** Bounded {@code outcome} dimension for provider attempt metrics. */
public enum ProviderOutcome {
    SUCCESS("success"),
    HTTP_ERROR("http_error"),
    PARSE_ERROR("parse_error"),
    TIMEOUT("timeout"),
    TRANSPORT_ERROR("transport_error");

    private final String wireValue;

    ProviderOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
