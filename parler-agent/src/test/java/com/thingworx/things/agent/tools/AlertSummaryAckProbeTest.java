package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Locks: {@code specific_alerts} summary probe failures use
 * {@link AlertAcknowledgePlatformErrors} (not generic {@code ACKNOWLEDGE_ALERTS_ERROR}).
 * Uses {@link AlertSummaryAckProbe.SummaryProbeCall} so the failure path does not touch ThingWorx
 * {@code ValueCollection} static init (which pulls logback / {@code ProviderConfig} in offline JUnit).
 */
class AlertSummaryAckProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void summaryProbeFailureReturnsPlatformAlertPermission() throws Exception {
        AlertSummaryAckProbe.SummaryProbeOutcome out = AlertSummaryAckProbe.runSummaryProbeForAckOrNormalize(
                () -> {
                    throw new Exception("User not authorized to invoke service");
                });
        assertNotNull(out.errorJson);
        assertNull(out.rows);
        JsonNode n = MAPPER.readTree(out.errorJson);
        assertEquals("error", n.path("status").asText());
        assertEquals("PLATFORM_ALERT_PERMISSION", n.path("code").asText());
    }

    @Test
    void summaryProbeFailureReturnsPlatformAlertNotFound() throws Exception {
        AlertSummaryAckProbe.SummaryProbeOutcome out = AlertSummaryAckProbe.runSummaryProbeForAckOrNormalize(
                () -> {
                    throw new Exception("Thing MyThing not found");
                });
        assertNotNull(out.errorJson);
        JsonNode n = MAPPER.readTree(out.errorJson);
        assertEquals("PLATFORM_ALERT_NOT_FOUND", n.path("code").asText());
    }

    /**
     * Offline guard: {@code IServiceProvider} overload must remain a thin delegate to
     * {@link AlertSummaryAckProbe#querySummaryRowsForAck} — full probe cannot run in JUnit without {@code ValueCollection}
     * static init (TW logback / {@code ProviderConfig}).
     */
    private static Path alertSummaryAckProbeSourceFile() {
        String root = System.getProperty("user.dir");
        String[] rels = new String[] {
                "src/main/java/com/thingworx/things/agent/tools/AlertSummaryAckProbe.java",
                "parler-agent/src/main/java/com/thingworx/things/agent/tools/AlertSummaryAckProbe.java",
        };
        for (String rel : rels) {
            Path p = Paths.get(root, rel);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "AlertSummaryAckProbe.java not found from user.dir=" + root + " (run Gradle from parler-agent/).");
    }

    @Test
    void providerOverloadSourceStillDelegatesToQuerySummaryRowsForAck() throws Exception {
        Path src = alertSummaryAckProbeSourceFile();
        String body = Files.readString(src, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(body.contains("public static SummaryProbeOutcome runSummaryProbeForAckOrNormalize(IServiceProvider"),
                "expected IServiceProvider overload signature");
        assertTrue(body.contains("return runSummaryProbeForAckOrNormalize("),
                "expected provider overload to delegate");
        assertTrue(body.contains("() -> querySummaryRowsForAck(alertFunctions, thingName, propertyName, alertName)"),
                "expected lambda to call querySummaryRowsForAck with captured provider");
        assertTrue(body.contains("QueryAlertSummaryForThing"),
                "expected probe to call QueryAlertSummaryForThing");
    }
}
