package com.thingworx.things.agent.llm.usage;

public enum LlmCallOutcome {
    SUCCESS("success"),
    ERROR("error"),
    TIMEOUT("timeout"),
    NOT_SENT("not_sent");

    private final String wireValue;

    LlmCallOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static LlmCallOutcome fromWire(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (LlmCallOutcome outcome : values()) {
            if (outcome.wireValue.equals(value)) {
                return outcome;
            }
        }
        return null;
    }
}
