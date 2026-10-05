package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ToolResultEgressGatewayAlertSummaryMultiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void compactForLlmAppend_preservesFourteenThingQ2Fleet() throws Exception {
        assertByThingCountSurvivesEgress(14, "SE.CellFab.Model.Workunit.Robot-");
    }

    @Test
    void compactForLlmAppend_preservesTwentyFiveThingCeiling() throws Exception {
        String raw = worstCaseMultiRollup(25, "SE.CellFab.Model.Workunit.Asset-");
        assertTrue(raw.length() > 8192, "fixture must exceed LLM evidence soft cap");
        assertByThingCountSurvivesEgress(25, "SE.CellFab.Model.Workunit.Asset-", raw);
    }

    @Test
    void compactForLlmAppend_preservesTwentyFiveIdentityErrorsOnZeroResolveError() throws Exception {
        assertIdentityErrorsSurviveEgress(25, "bad-label-");
    }

    @Test
    void compactForLlmAppend_preservesTwentyFiveByThingOnAllServiceFailError() throws Exception {
        assertByThingSurvivesErrorEgress(25, "fail-thing-");
    }

    private static void assertByThingCountSurvivesEgress(int thingCount, String namePrefix) throws Exception {
        assertByThingCountSurvivesEgress(thingCount, namePrefix, worstCaseMultiRollup(thingCount, namePrefix));
    }

    private static void assertByThingCountSurvivesEgress(int thingCount, String namePrefix, String raw)
            throws Exception {
        ToolResultEgressGateway.EgressResult out =
                ToolResultEgressGateway.compactForLlmAppend("query_alert_summary", "call-multi", raw, null);
        JsonNode root = JSON.readTree(out.getLlmContent());
        assertEquals(thingCount, root.path("byThing").size());
        assertFalse(root.path("_egress").path("reducedFields").has("byThing"),
                root.path("_egress").toString());
        for (int i = 0; i < thingCount; i++) {
            String expected = namePrefix + String.format("%02d", i);
            assertEquals(expected, root.path("byThing").get(i).path("thingName").asText());
        }
    }

    static String worstCaseMultiRollup(int thingCount, String namePrefix) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", ToolResultEgressGateway.RESULT_KIND_ALERT_SUMMARY_MULTI);
        root.put("completeness", "complete");
        root.put("thingsRequested", thingCount);
        root.put("thingsSucceeded", thingCount);
        root.put("thingsFailedIdentity", 0);
        root.put("thingsFailedService", 0);
        ArrayNode byThing = root.putArray("byThing");
        for (int i = 0; i < thingCount; i++) {
            ObjectNode entry = byThing.addObject();
            entry.put("thingName", namePrefix + String.format("%02d", i));
            entry.put("status", "success");
            entry.put("totalAlerts", 7);
            entry.put("unackedCount", 3);
            entry.put("rowCount", 7);
            ObjectNode bp = entry.putObject("byPriority");
            bp.put("high", 2);
            bp.put("medium", 4);
            bp.put("low", 1);
            ArrayNode top = entry.putArray("topAlerts");
            for (int t = 0; t < 3; t++) {
                top.addObject()
                        .put("alertName", "HighTempAlarm-" + t)
                        .put("sourceProperty", "TemperatureSensor" + t)
                        .put("priority", 950 - t)
                        .put("timestamp", "2026-06-28T12:0" + t + ":00Z");
            }
        }
        root.putObject("extras").put("ackState", "all");
        return JSON.writeValueAsString(root);
    }

    private static void assertIdentityErrorsSurviveEgress(int count, String namePrefix) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "error");
        root.put("resultKind", ToolResultEgressGateway.RESULT_KIND_ALERT_SUMMARY_MULTI);
        root.put("code", "IDENTITY_RESOLUTION_REQUIRED");
        root.put("parameterName", "thingNames");
        root.put("thingsRequested", count);
        ArrayNode identityErrors = root.putArray("identityErrors");
        for (int i = 0; i < count; i++) {
            String label = longThingLabel(namePrefix, i);
            identityErrors.addObject()
                    .put("thingName", label)
                    .put("code", "IDENTITY_RESOLUTION_REQUIRED")
                    .put("suppliedValue", label)
                    .put("message", longErrorMessage(label));
        }
        String raw = JSON.writeValueAsString(root);
        assertTrue(raw.length() > 8192, () -> "fixture chars=" + raw.length());
        ToolResultEgressGateway.EgressResult out =
                ToolResultEgressGateway.compactForLlmAppend("query_alert_summary", "call-zero", raw, null);
        JsonNode compact = JSON.readTree(out.getLlmContent());
        assertEquals(count, compact.path("identityErrors").size());
        assertFalse(compact.path("_egress").path("reducedFields").has("identityErrors"),
                compact.path("_egress").toString());
    }

    private static void assertByThingSurvivesErrorEgress(int count, String namePrefix) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "error");
        root.put("resultKind", ToolResultEgressGateway.RESULT_KIND_ALERT_SUMMARY_MULTI);
        root.put("code", "QUERY_ALERT_SUMMARY_ERROR");
        root.put("thingsRequested", count);
        root.put("thingsSucceeded", 0);
        ArrayNode byThing = root.putArray("byThing");
        for (int i = 0; i < count; i++) {
            String label = longThingLabel(namePrefix, i);
            byThing.addObject()
                    .put("thingName", label)
                    .put("status", "error")
                    .put("code", "QUERY_ALERT_SUMMARY_ERROR")
                    .put("message", longErrorMessage(label));
        }
        String raw = JSON.writeValueAsString(root);
        assertTrue(raw.length() > 8192, () -> "fixture chars=" + raw.length());
        ToolResultEgressGateway.EgressResult out =
                ToolResultEgressGateway.compactForLlmAppend("query_alert_summary", "call-all-fail", raw, null);
        JsonNode compact = JSON.readTree(out.getLlmContent());
        assertEquals(count, compact.path("byThing").size());
        assertFalse(compact.path("_egress").path("reducedFields").has("byThing"),
                compact.path("_egress").toString());
    }

    private static String longThingLabel(String namePrefix, int index) {
        return namePrefix + String.format("%02d", index) + "-SE.CellFab.Model.Workunit.Robot";
    }

    private static String longErrorMessage(String label) {
        StringBuilder sb = new StringBuilder(320);
        sb.append("Parameter thingNames requires a canonical ThingName for supplied value ");
        sb.append(label);
        while (sb.length() < 320) {
            sb.append('.');
        }
        return sb.toString();
    }
}
