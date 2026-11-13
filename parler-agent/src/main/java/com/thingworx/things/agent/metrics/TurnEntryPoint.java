package com.thingworx.things.agent.metrics;

/** Bounded {@code entry_point} dimension for turn metrics. */
public enum TurnEntryPoint {
    CHAT("chat"),
    CHAT_ASYNC("chat_async"),
    STREAM("stream"),
    APPROVAL_RESUME("approval_resume");

    private final String wireValue;

    TurnEntryPoint(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
