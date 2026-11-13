package com.thingworx.things.agent.metrics;

/** Bounded {@code tool_kind} dimension for tool metrics. */
public enum ToolKind {
    BUILT_IN("built_in"),
    CUSTOM("custom"),
    WORKFLOW("workflow"),
    OTHER("other");

    private final String wireValue;

    ToolKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
