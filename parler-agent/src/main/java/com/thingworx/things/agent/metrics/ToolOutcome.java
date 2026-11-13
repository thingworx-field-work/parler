package com.thingworx.things.agent.metrics;

/** Bounded {@code outcome} dimension for tool metrics. */
public enum ToolOutcome {
    SUCCESS("success"),
    ERROR("error");

    private final String wireValue;

    ToolOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
