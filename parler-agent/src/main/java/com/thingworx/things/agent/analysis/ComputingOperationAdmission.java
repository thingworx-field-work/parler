package com.thingworx.things.agent.analysis;

/**
 * Admission flags for computing-enhancement operations. Same contract as
 * {@link com.thingworx.things.agent.join.U4OperationAdmission}: while a flag is off the matching
 * {@code tabulate_cached_result} mode and its mode-only properties leave the model-visible schema and
 * direct execution rejects with the stable unavailable reason.
 */
public final class ComputingOperationAdmission {

    public static final String COUNTER_DELTA_UNAVAILABLE = "CE_COUNTER_DELTA_DISABLED";

    public static final String ROLLING_STATS_UNAVAILABLE = "CE_ROLLING_STATS_DISABLED";

    public static final String TIME_WEIGHTED_UNAVAILABLE = "CE_TIME_WEIGHTED_DISABLED";

    public static final String CALENDAR_BUCKET_UNAVAILABLE = "CE_CALENDAR_BUCKET_DISABLED";

    private static volatile boolean counterDeltaEnabled = true;
    private static volatile boolean rollingStatsEnabled = true;
    private static volatile boolean timeWeightedEnabled = true;
    private static volatile boolean calendarBucketEnabled = true;

    private ComputingOperationAdmission() {}

    public static boolean counterDeltaEnabled() {
        return counterDeltaEnabled;
    }

    public static void setCounterDeltaEnabled(boolean enabled) {
        counterDeltaEnabled = enabled;
    }

    public static boolean rollingStatsEnabled() {
        return rollingStatsEnabled;
    }

    public static void setRollingStatsEnabled(boolean enabled) {
        rollingStatsEnabled = enabled;
    }

    public static boolean timeWeightedEnabled() {
        return timeWeightedEnabled;
    }

    public static void setTimeWeightedEnabled(boolean enabled) {
        timeWeightedEnabled = enabled;
    }

    public static boolean calendarBucketEnabled() {
        return calendarBucketEnabled;
    }

    public static void setCalendarBucketEnabled(boolean enabled) {
        calendarBucketEnabled = enabled;
    }

    /**
     * Test helper for measurements of the tool surface without any computing mode (CC-1.4 dedup criterion and
     * the computing description cap): runs {@code body} with every flag off and restores the previous flags.
     */
    public static <T> T callWithAllDisabled(java.util.concurrent.Callable<T> body) throws Exception {
        boolean[] before = {counterDeltaEnabled, rollingStatsEnabled, timeWeightedEnabled, calendarBucketEnabled};
        try {
            counterDeltaEnabled = false;
            rollingStatsEnabled = false;
            timeWeightedEnabled = false;
            calendarBucketEnabled = false;
            return body.call();
        } finally {
            counterDeltaEnabled = before[0];
            rollingStatsEnabled = before[1];
            timeWeightedEnabled = before[2];
            calendarBucketEnabled = before[3];
        }
    }

    /** Test helper: restore defaults. */
    public static void resetForTests() {
        counterDeltaEnabled = true;
        rollingStatsEnabled = true;
        timeWeightedEnabled = true;
        calendarBucketEnabled = true;
    }
}
