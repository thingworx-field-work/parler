package com.thingworx.things.agent.tools;

/**
 * Offline-testable rules for {@code acknowledge_alerts} / {@code specific_alerts} summary probe row counts.
 *
 * <p>Platform calls stay in {@link AlertToolsExecutor}; this class only classifies integers so JUnit can lock
 * regression-prone branches without {@code AlertFunctions}.</p>
 */
public final class AlertSpecificAckPolicy {

    /** Maximum rows we will pass to {@code AcknowledgeAlertFromSummary} in one call (matches tool limit). */
    public static final int MAX_BATCH_ROWS = 500;

    /** {@code QueryAlertSummaryForThing.maxItems} for the ack probe (one extra row to detect {@code > MAX_BATCH_ROWS}). */
    public static final int PROBE_MAX_ITEMS = MAX_BATCH_ROWS + 1;

    /** Outcome of classifying the unacknowledged-only summary row count from the probe query. */
    public enum ProbeOutcome {
        /** No rows to acknowledge. */
        EMPTY,
        /** Row count is in {@code [1, MAX_BATCH_ROWS]}; safe to call {@code AcknowledgeAlertFromSummary}. */
        WITHIN_LIMIT,
        /** Row count exceeds {@link #MAX_BATCH_ROWS}; caller must not truncate silently. */
        EXCEEDS_LIMIT
    }

    private AlertSpecificAckPolicy() {}

    /**
     * @param unackedRowCount non-negative row count from {@code QueryAlertSummaryForThing} with
     *        {@code maxItems == PROBE_MAX_ITEMS} and {@code onlyUnacknowledged == true}
     */
    public static ProbeOutcome classifyUnackedRowCount(int unackedRowCount) {
        if (unackedRowCount < 0) {
            throw new IllegalArgumentException("unackedRowCount must be non-negative: " + unackedRowCount);
        }
        if (unackedRowCount == 0) {
            return ProbeOutcome.EMPTY;
        }
        if (unackedRowCount > MAX_BATCH_ROWS) {
            return ProbeOutcome.EXCEEDS_LIMIT;
        }
        return ProbeOutcome.WITHIN_LIMIT;
    }
}
