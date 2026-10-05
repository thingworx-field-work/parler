package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AlertAcknowledgeResultJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void propertyAllSuccessShape() throws Exception {
        String json = AlertAcknowledgeResultJson.propertyAllSuccess("T1", "P1", null);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("success", n.path("status").asText());
        assertEquals("property_all", n.path("mode").asText());
        assertEquals("T1", n.path("thingName").asText());
        assertEquals(-1, n.path("requestedCount").asInt());
        assertFalse(n.path("countsAvailable").asBoolean());
        assertFalse(n.has("ackMessage"));
    }

    @Test
    void propertyAllSuccessIncludesAckMessage() throws Exception {
        JsonNode n = MAPPER.readTree(AlertAcknowledgeResultJson.propertyAllSuccess("T1", "P1", "note to ops"));
        assertEquals("note to ops", n.path("ackMessage").asText());
    }

    @Test
    void specificAlertsEmptyShape() throws Exception {
        JsonNode n = MAPPER.readTree(AlertAcknowledgeResultJson.specificAlertsEmpty("T1", "P1", null));
        assertEquals("specific_alerts", n.path("mode").asText());
        assertEquals(0, n.path("requestedCount").asInt());
        assertTrue(n.path("countsAvailable").asBoolean());
        assertTrue(n.path("message").asText().contains("No matching"));
    }

    @Test
    void specificAlertsAfterAckShape() throws Exception {
        JsonNode n = MAPPER.readTree(
                AlertAcknowledgeResultJson.specificAlertsAfterAck(3, "T1", "P1", "AlertA", "m"));
        assertEquals(3, n.path("requestedCount").asInt());
        assertEquals(3, n.path("acknowledgedCount").asInt());
        assertEquals("AlertA", n.path("alertName").asText());
        assertEquals("m", n.path("ackMessage").asText());
        assertEquals("AcknowledgeAlertFromSummary", n.path("platformAckService").asText());
        assertTrue(n.path("note").asText().contains("AcknowledgeAlertFromSummary"));
    }

    @Test
    void specificAlertsAfterAckNarrowServiceNote() throws Exception {
        JsonNode n = MAPPER.readTree(AlertAcknowledgeResultJson.specificAlertsAfterAck(1, "T1", "P1", null, null,
                "AcknowledgeAlert"));
        assertEquals("AcknowledgeAlert", n.path("platformAckService").asText());
        assertTrue(n.path("note").asText().contains("Narrow AcknowledgeAlert"));
    }

    @Test
    void specificAlertsAfterAckOmitsEmptyAlertName() throws Exception {
        JsonNode n = MAPPER.readTree(
                AlertAcknowledgeResultJson.specificAlertsAfterAck(2, "T1", "P1", "", null));
        assertFalse(n.has("alertName"));
    }

    @Test
    void specificAlertsExceedsLimitErrorShape() throws Exception {
        JsonNode n = MAPPER.readTree(AlertAcknowledgeResultJson.specificAlertsExceedsLimitError(501, 500));
        assertEquals("error", n.path("status").asText());
        assertEquals("ACK_MATCHES_EXCEED_LIMIT", n.path("code").asText());
        assertTrue(n.path("message").asText().contains("501"));
        assertTrue(n.path("message").asText().contains("500"));
    }
}
