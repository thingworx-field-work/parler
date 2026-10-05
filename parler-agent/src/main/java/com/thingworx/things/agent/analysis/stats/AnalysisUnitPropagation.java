package com.thingworx.things.agent.analysis.stats;

/**
 * Deterministic unit propagation helpers for U5 slopes/effects (DIK-1). Does not invent units.
 */
public final class AnalysisUnitPropagation {

    private AnalysisUnitPropagation() {}

    /** Slope unit form {@code valueUnit / timeUnit}, or null if either side is blank. */
    public static String slopeUnit(String valueUnit, String timeUnit) {
        String v = normalize(valueUnit);
        String t = normalize(timeUnit);
        if (v == null || t == null) {
            return null;
        }
        return v + " / " + t;
    }

    /** Echo a single series unit, or null when blank/unknown. */
    public static String echo(String unit) {
        return normalize(unit);
    }

    /** Require identical units for paired series; mismatch → null and {@code mismatch=true}. */
    public static String requireSame(String leftUnit, String rightUnit, boolean[] mismatchOut) {
        String a = normalize(leftUnit);
        String b = normalize(rightUnit);
        if (a == null && b == null) {
            if (mismatchOut != null && mismatchOut.length > 0) {
                mismatchOut[0] = false;
            }
            return null;
        }
        if (a == null || b == null || !a.equals(b)) {
            if (mismatchOut != null && mismatchOut.length > 0) {
                mismatchOut[0] = true;
            }
            return null;
        }
        if (mismatchOut != null && mismatchOut.length > 0) {
            mismatchOut[0] = false;
        }
        return a;
    }

    private static String normalize(String unit) {
        return unit == null || unit.isBlank() ? null : unit.trim();
    }
}
