package com.thingworx.things.agent.analysis;

/**
 * Envelope {@code metrics} key and shared outcome-code helpers for U5 (§6.3). Field landing is the
 * metrics map on the U4-owned base envelope — not a second envelope type.
 */
public final class AnalysisOutcomeCodes {

    /** Compact/tool metric key echoed on every non-ERROR U5 envelope. */
    public static final String METRIC_KEY = "outcomeCode";

    private AnalysisOutcomeCodes() {}

    public static String requireNonBlank(String outcomeCode) {
        if (outcomeCode == null || outcomeCode.isBlank()) {
            throw new IllegalArgumentException(METRIC_KEY + " required");
        }
        return outcomeCode.trim();
    }

    public static String fromMetrics(java.util.Map<String, String> metrics) {
        if (metrics == null) {
            return null;
        }
        String v = metrics.get(METRIC_KEY);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
