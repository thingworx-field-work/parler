package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AlertAcknowledgePlatformErrorsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsPermissionPhrases() throws Exception {
        JsonNode n = MAPPER.readTree(AlertAcknowledgePlatformErrors.normalizedPlatformJson(
                new Exception("User not authorized to invoke service")));
        assertEquals("PLATFORM_ALERT_PERMISSION", n.path("code").asText());
        assertEquals("error", n.path("status").asText());
    }

    @Test
    void mapsNotFoundPhrases() throws Exception {
        JsonNode n = MAPPER.readTree(AlertAcknowledgePlatformErrors.normalizedPlatformJson(
                new Exception("Thing MyThing not found")));
        assertEquals("PLATFORM_ALERT_NOT_FOUND", n.path("code").asText());
    }

    @Test
    void defaultCodeForUnknownMessage() throws Exception {
        JsonNode n = MAPPER.readTree(
                AlertAcknowledgePlatformErrors.normalizedPlatformJson(new Exception("Queue timeout")));
        assertEquals("PLATFORM_ALERT_SERVICE_ERROR", n.path("code").asText());
        assertTrue(n.path("message").asText().contains("timeout"));
    }
}
