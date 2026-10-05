package com.thingworx.things.agent.llm.usage;

/** Logical LLM call category for {@link AgentLlmCallStream} events (CC-7.2). */
public enum LlmCallKind {
    AGENT_ROUND("agent_round"),
    CHECKPOINT("checkpoint"),
    PLAYBOOK("playbook"),
    PROBE("probe");

    private final String wireValue;

    LlmCallKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static LlmCallKind fromWire(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (LlmCallKind kind : values()) {
            if (kind.wireValue.equals(value)) {
                return kind;
            }
        }
        return null;
    }
}
