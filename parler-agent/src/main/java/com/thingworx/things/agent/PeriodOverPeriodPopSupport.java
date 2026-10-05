package com.thingworx.things.agent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shared validation helpers retained for the live {@code build_history_overlay_chart} path (U1B S13).
 * Retired same-window multi-series redirect helpers were removed with the unregistered PoP/multi-series
 * executors.
 */
public final class PeriodOverPeriodPopSupport {

    private PeriodOverPeriodPopSupport() {}

    /**
     * When two or more Things expose non-blank unit metadata for the same property, they must agree.
     *
     * @return {@code null} when compatible; otherwise a human-readable mismatch message
     */
    public static String validateUnitCompatibility(java.util.Map<String, String> thingToUnit) {
        if (thingToUnit == null || thingToUnit.isEmpty()) {
            return null;
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String unit : thingToUnit.values()) {
            if (unit != null && !unit.isBlank()) {
                distinct.add(unit.trim());
            }
        }
        if (distinct.size() <= 1) {
            return null;
        }
        return "Property units differ across Things: " + String.join(", ", distinct);
    }
}
