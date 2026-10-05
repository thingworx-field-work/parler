package com.thingworx.things.agent.llm;

/**
 * Per-Provider Thing circuit state in the current JVM (U7 / G16 §7.4 / D11).
 * Resets on JVM restart/save; does not claim cross-node coordination.
 */
public enum ProviderCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
