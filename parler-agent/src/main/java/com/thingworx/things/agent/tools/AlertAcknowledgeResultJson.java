package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Offline-testable JSON bodies for {@code acknowledge_alerts} tool outcomes (success + structured errors).
 * Platform calls remain in {@link AlertToolsExecutor}.
 */
public final class AlertAcknowledgeResultJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlertAcknowledgeResultJson() {}

    public static String propertyAllSuccess(String thingName, String propertyName, String messageOrNull)
            throws JsonProcessingException {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("mode", "property_all");
        out.put("thingName", thingName);
        out.put("propertyName", propertyName);
        out.put("requestedCount", -1);
        out.put("acknowledgedCount", -1);
        out.put("countsAvailable", false);
        putAckMessageIfPresent(out, messageOrNull);
        out.put("note", "property_all completed; counts depend on platform (not enumerated here).");
        return MAPPER.writeValueAsString(out);
    }

    public static String specificAlertsEmpty(String thingName, String propertyName, String messageOrNull)
            throws JsonProcessingException {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("mode", "specific_alerts");
        out.put("thingName", thingName);
        out.put("propertyName", propertyName);
        out.put("requestedCount", 0);
        out.put("acknowledgedCount", 0);
        out.put("countsAvailable", true);
        putAckMessageIfPresent(out, messageOrNull);
        out.put("message", "No matching alert rows in current summary for the given filters.");
        return MAPPER.writeValueAsString(out);
    }

    /**
     * @param rowCount rows passed to {@code AcknowledgeAlertFromSummary} (unacknowledged-only probe, ≤
     *        {@link AlertSpecificAckPolicy#MAX_BATCH_ROWS})
     */
    public static String specificAlertsAfterAck(int rowCount, String thingName, String propertyName,
            String alertNameOrNull, String messageOrNull) throws JsonProcessingException {
        return specificAlertsAfterAck(rowCount, thingName, propertyName, alertNameOrNull, messageOrNull,
                "AcknowledgeAlertFromSummary");
    }

    /**
     * @param platformAckService {@code AcknowledgeAlertFromSummary} or {@code AcknowledgeAlert} (narrow property ack)
     */
    public static String specificAlertsAfterAck(int rowCount, String thingName, String propertyName,
            String alertNameOrNull, String messageOrNull, String platformAckService) throws JsonProcessingException {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("mode", "specific_alerts");
        out.put("thingName", thingName);
        out.put("propertyName", propertyName);
        if (alertNameOrNull != null && !alertNameOrNull.isEmpty()) {
            out.put("alertName", alertNameOrNull);
        }
        out.put("requestedCount", rowCount);
        out.put("acknowledgedCount", rowCount);
        out.put("countsAvailable", true);
        out.put("platformAckService", platformAckService);
        putAckMessageIfPresent(out, messageOrNull);
        if ("AcknowledgeAlert".equals(platformAckService)) {
            out.put("note",
                    "Narrow AcknowledgeAlert (property scope): exactly one unacked summary row matched propertyName without alertName filter; "
                            + "platform may still skip idempotent rows.");
        } else {
            out.put("note",
                    "Counts are rows passed to AcknowledgeAlertFromSummary (unacknowledged-only probe); platform may still skip idempotent rows.");
        }
        return MAPPER.writeValueAsString(out);
    }

    public static String specificAlertsExceedsLimitError(int probeRowCount, int maxBatchRows)
            throws JsonProcessingException {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "error");
        o.put("code", "ACK_MATCHES_EXCEED_LIMIT");
        o.put("message",
                "More than " + maxBatchRows
                        + " unacknowledged summary rows match thingName/propertyName/alertName; narrow filters (e.g. alertName) or acknowledge in smaller batches. Matching row count (probe): "
                        + probeRowCount);
        return MAPPER.writeValueAsString(o);
    }

    private static void putAckMessageIfPresent(ObjectNode out, String messageOrNull) {
        if (messageOrNull != null && !messageOrNull.isEmpty()) {
            out.put("ackMessage", messageOrNull);
        }
    }
}
