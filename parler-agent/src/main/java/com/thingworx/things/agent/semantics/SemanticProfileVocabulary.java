package com.thingworx.things.agent.semantics;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Closed unit / dimension / grain / cadence vocabulary (SP4). Unknown tokens fail validation.
 * Not a unit-conversion engine.
 */
public final class SemanticProfileVocabulary {

    public static final Set<String> DIMENSIONS = Set.of(
            "temperature", "energy", "power", "length", "mass", "time", "pressure", "speed", "volume",
            "electric_current", "voltage", "frequency", "count", "ratio", "dimensionless");

    public static final Set<String> GRAINS = Set.of(
            "sample", "cycle", "batch", "event", "window", "aggregate");

    private static final Map<String, String> UNIT_TO_DIMENSION;

    static {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("Cel", "temperature");
        m.put("K", "temperature");
        m.put("kWh", "energy");
        m.put("J", "energy");
        m.put("W", "power");
        m.put("m", "length");
        m.put("mm", "length");
        m.put("kg", "mass");
        m.put("g", "mass");
        m.put("s", "time");
        m.put("min", "time");
        m.put("h", "time");
        m.put("Pa", "pressure");
        m.put("bar", "pressure");
        m.put("m/s", "speed");
        m.put("L", "volume");
        m.put("A", "electric_current");
        m.put("V", "voltage");
        m.put("Hz", "frequency");
        m.put("1", "dimensionless");
        m.put("%", "ratio");
        UNIT_TO_DIMENSION = Collections.unmodifiableMap(m);
    }

    public static final Set<String> UNITS = Collections.unmodifiableSet(new LinkedHashSet<>(UNIT_TO_DIMENSION.keySet()));

    private SemanticProfileVocabulary() {}

    public static boolean isDimension(String token) {
        return token != null && DIMENSIONS.contains(token);
    }

    public static boolean isGrain(String token) {
        return token != null && GRAINS.contains(token);
    }

    public static boolean isUnit(String token) {
        return token != null && UNIT_TO_DIMENSION.containsKey(token);
    }

    public static String dimensionForUnit(String unit) {
        return unit == null ? null : UNIT_TO_DIMENSION.get(unit);
    }

    /**
     * Validates optional ISO-8601 duration cadence. {@code null}/blank means unknown (allowed).
     * Zero or negative durations are rejected.
     */
    public static String validateCadence(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        try {
            Duration d = Duration.parse(t);
            if (d.isZero() || d.isNegative()) {
                return "expectedCadence must be a positive ISO-8601 duration";
            }
            return null;
        } catch (Exception e) {
            return "expectedCadence must be an ISO-8601 duration (e.g. PT5S)";
        }
    }
}
