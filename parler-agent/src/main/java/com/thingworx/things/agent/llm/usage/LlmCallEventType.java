package com.thingworx.things.agent.llm.usage;

/** Immutable event kinds written to {@link AgentLlmCallStream} (CC-7.3). */
public enum LlmCallEventType {
    CALL_STARTED("call_started"),
    CALL_DISPATCHED("call_dispatched"),
    CALL_CANCEL_REQUESTED("call_cancel_requested"),
    CALL_FINISHED("call_finished"),
    USAGE_OBSERVED("usage_observed"),
    COLLECTOR_STARTED("collector_started"),
    COLLECTOR_GAP("collector_gap");

    private final String wireValue;

    LlmCallEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static LlmCallEventType fromWire(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (LlmCallEventType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
