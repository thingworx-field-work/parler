package com.thingworx.things.agent.join;

/**
 * TQJ-5 admission flags for U4 operations. When disabled, executors reject with a stable unavailable
 * reason and the matching model-visible {@code tabulate_cached_result} mode enum value is withdrawn
 * from advertisement (design §12). Demoted executor-only aliases stay registered but reject the same
 * way.
 */
public final class U4OperationAdmission {

    public static final String EXACT_JOIN_UNAVAILABLE = "U4_EXACT_JOIN_DISABLED";
    public static final String QUALITY_UNAVAILABLE = "U4_QUALITY_DISABLED";
    public static final String RESAMPLE_UNAVAILABLE = "U4_RESAMPLE_DISABLED";
    public static final String ROLLING_UNAVAILABLE = "U4_ROLLING_DISABLED";
    public static final String RATE_OF_CHANGE_UNAVAILABLE = "U4_RATE_OF_CHANGE_DISABLED";
    public static final String PERIOD_COMPARE_UNAVAILABLE = "U4_PERIOD_COMPARE_DISABLED";

    private static volatile boolean exactJoinEnabled = true;
    private static volatile boolean qualityEnabled = true;
    private static volatile boolean resampleEnabled = true;
    private static volatile boolean rollingEnabled = true;
    private static volatile boolean rateOfChangeEnabled = true;
    private static volatile boolean periodCompareEnabled = true;

    private U4OperationAdmission() {}

    public static boolean exactJoinEnabled() {
        return exactJoinEnabled;
    }

    public static void setExactJoinEnabled(boolean enabled) {
        exactJoinEnabled = enabled;
    }

    public static boolean qualityEnabled() {
        return qualityEnabled;
    }

    public static void setQualityEnabled(boolean enabled) {
        qualityEnabled = enabled;
    }

    public static boolean resampleEnabled() {
        return resampleEnabled;
    }

    public static void setResampleEnabled(boolean enabled) {
        resampleEnabled = enabled;
    }

    public static boolean rollingEnabled() {
        return rollingEnabled;
    }

    public static void setRollingEnabled(boolean enabled) {
        rollingEnabled = enabled;
    }

    public static boolean rateOfChangeEnabled() {
        return rateOfChangeEnabled;
    }

    public static void setRateOfChangeEnabled(boolean enabled) {
        rateOfChangeEnabled = enabled;
    }

    public static boolean periodCompareEnabled() {
        return periodCompareEnabled;
    }

    public static void setPeriodCompareEnabled(boolean enabled) {
        periodCompareEnabled = enabled;
    }

    /** Test helper: restore defaults. */
    public static void resetForTests() {
        exactJoinEnabled = true;
        qualityEnabled = true;
        resampleEnabled = true;
        rollingEnabled = true;
        rateOfChangeEnabled = true;
        periodCompareEnabled = true;
    }
}
