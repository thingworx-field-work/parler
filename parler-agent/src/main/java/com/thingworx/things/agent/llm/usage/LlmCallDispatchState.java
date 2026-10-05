package com.thingworx.things.agent.llm.usage;

public enum LlmCallDispatchState {
    NOT_SENT("not_sent"),
    ATTEMPTED("attempted"),
    UNKNOWN("unknown");

    private final String wireValue;

    LlmCallDispatchState(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static LlmCallDispatchState fromWire(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        for (LlmCallDispatchState state : values()) {
            if (state.wireValue.equals(value)) {
                return state;
            }
        }
        return UNKNOWN;
    }
}
