package com.thingworx.things.agent.tools;

/**
 * Whether {@code specific_alerts} may call {@code AcknowledgeAlert} (property-scoped) instead of
 * {@code AcknowledgeAlertFromSummary}. Per {@code docs/operations/alert-solution.md} §11: narrow path is only safe when
 * exactly one matching unacknowledged row exists <b>and</b> the probe was not narrowed by {@code alertName} (otherwise
 * other unacked alerts on the same property could be widened into the ack).
 */
public final class AlertNarrowAckPolicy {

    private AlertNarrowAckPolicy() {}

    /**
     * @param unackedRowCount rows from the unacknowledged-only summary probe (already ≤ batch limit)
     * @param alertName       optional filter from the tool call (may be null / blank)
     */
    public static boolean useAcknowledgeAlertForSpecificAlerts(int unackedRowCount, String alertName) {
        if (unackedRowCount != 1) {
            return false;
        }
        return alertName == null || alertName.isBlank();
    }
}
