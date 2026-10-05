package com.thingworx.things.agent.llm.ratecontrol;

/** Provider rate-control mode ({@code docs/agent/rate-control.md} §4). */
public enum RateControlMode {
    disabled,
    observe,
    enforce;

    public static RateControlMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return disabled;
        }
        String n = raw.trim().toLowerCase();
        for (RateControlMode m : values()) {
            if (m.name().equals(n)) {
                return m;
            }
        }
        return disabled;
    }
}
