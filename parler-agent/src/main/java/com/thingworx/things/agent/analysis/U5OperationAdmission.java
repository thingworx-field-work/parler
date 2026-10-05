package com.thingworx.things.agent.analysis;

/**
 * DIK-5 admission for the model-visible {@code analyze_cached_result} tool. When disabled, the
 * executor rejects with {@link #ANALYZE_UNAVAILABLE} and the tool is withdrawn from advertisement.
 */
public final class U5OperationAdmission {

    public static final String ANALYZE_UNAVAILABLE = "U5_ANALYZE_DISABLED";

    private static volatile boolean analyzeEnabled = true;

    private U5OperationAdmission() {}

    public static boolean analyzeEnabled() {
        return analyzeEnabled;
    }

    public static void setAnalyzeEnabled(boolean enabled) {
        analyzeEnabled = enabled;
    }

    public static void resetForTests() {
        analyzeEnabled = true;
    }
}
