package com.thingworx.things.agent.llm.ratecontrol;

/** Token reservation / debit strategy ({@code docs/agent/rate-control.md} §7–§8). */
public enum TokenReserveStrategy {
    input_only,
    input_plus_requested_output;

    public static TokenReserveStrategy parse(String raw, TokenReserveStrategy defaultStrategy) {
        if (raw == null || raw.isBlank()) {
            return defaultStrategy;
        }
        String n = raw.trim().toLowerCase();
        for (TokenReserveStrategy s : values()) {
            if (s.name().equals(n)) {
                return s;
            }
        }
        return defaultStrategy;
    }
}
