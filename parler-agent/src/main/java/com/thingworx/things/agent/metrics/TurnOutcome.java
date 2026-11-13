package com.thingworx.things.agent.metrics;

/** Bounded {@code outcome} dimension for turn metrics. */
public enum TurnOutcome {
    SUCCESS("success"),
    ERROR("error"),
    CANCELLED("cancelled"),
    AWAITING_APPROVAL("awaiting_approval");

    private final String wireValue;

    TurnOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
