package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Slice F acceptance: converter golden output playbook + conversion reports.
 */
class PlaybookConverterFixtureTest {

    @Test
    void assetPairHealth_goldenOutput_validatesAndMatchesReport() throws Exception {
        JSONObject report = loadReport("asset_pair_health");
        assertEquals("parler-playbook-conversion-report-v1", report.getString("schema"));
        assertEquals("asset_pair_health", report.getString("sourceSkill"));
        assertEquals("cross_asset_pair_health", report.getString("targetPlaybook"));
        assertTrue(report.getBoolean("runnable"));

        PlaybookDocument doc = loadConverterPlaybook("asset_pair_health");
        assertEquals("cross_asset_pair_health", doc.playbookId());

        Set<String> opsInPlaybook = deriveOpsFrom(doc);
        Set<String> opsInReport = jsonStringSet(report.getJSONArray("deriveOpsUsed"));
        assertEquals(opsInPlaybook, opsInReport);

        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, packagingToolDefs());
        assertTrue(r.valid(), () -> String.join("; ", r.errors()));
        assertEquals("valid", report.getJSONObject("validation").getString("status"));
    }

    @Test
    void regionHealth_honestPartialReport_notRunnable() throws Exception {
        JSONObject report = loadReport("region_health");
        assertFalse(report.getBoolean("runnable"));
        assertTrue(report.getJSONArray("partiallyConverted").length() > 0);
        assertEquals("region_health", report.getString("sourceSkill"));
    }

    @Test
    void versionWarning_reportIncludesAgentMismatchAuthorAction() throws Exception {
        JSONObject report = loadReport("version_warning");
        String required = report.getString("requiredAgentVersion");
        String snapshot = report.getJSONObject("validation").getString("agentVersion");
        assertTrue(compareAgentVersions(required, snapshot) > 0, "required must exceed snapshot for warning path");
        JSONArray actions = report.getJSONArray("authorActions");
        boolean hasMismatch = false;
        for (int i = 0; i < actions.length(); i++) {
            String line = actions.getString(i).toLowerCase();
            if (line.contains("mismatch") || line.contains("below required")) {
                hasMismatch = true;
            }
        }
        assertTrue(hasMismatch, "authorActions must explain version mismatch");
    }

    @Test
    void compareAgentVersions_numericSegments_notLexicographic() {
        assertTrue(compareAgentVersions("0.1.191", "0.1.190") > 0);
        assertTrue(compareAgentVersions("0.1.10", "0.1.9") > 0);
        assertTrue(compareAgentVersions("0.1.9", "0.1.10") < 0);
    }

    static int compareAgentVersions(String required, String snapshot) {
        String[] req = required.split("\\.");
        String[] snap = snapshot.split("\\.");
        int len = Math.max(req.length, snap.length);
        for (int i = 0; i < len; i++) {
            int a = i < req.length ? Integer.parseInt(req[i]) : 0;
            int b = i < snap.length ? Integer.parseInt(snap[i]) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    private static Set<String> deriveOpsFrom(PlaybookDocument doc) {
        Set<String> ops = new LinkedHashSet<>();
        for (String nodeId : doc.nodeIdsInOrder()) {
            JSONObject node = doc.nodesById().get(nodeId);
            if ("derive".equals(node.optString("kind"))) {
                ops.add(node.getString("op"));
            }
        }
        return ops;
    }

    private static Set<String> jsonStringSet(JSONArray arr) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            out.add(arr.getString(i));
        }
        return out;
    }

    private static JSONObject loadReport(String dir) throws Exception {
        String path = "/playbook-converter-fixture/" + dir + "/conversion-report.json";
        try (InputStream in = PlaybookConverterFixtureTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource: " + path);
            }
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static PlaybookDocument loadConverterPlaybook(String dir) throws Exception {
        String path = "/playbook-converter-fixture/" + dir + "/playbook.json";
        try (InputStream in = PlaybookConverterFixtureTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource: " + path);
            }
            return PlaybookDocument.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static List<ToolDefinition> packagingToolDefs() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return List.of(
                new ToolDefinition("resolve_thing", "r", schema, true),
                new ToolDefinition("query_alert_summary", "q", schema, true),
                new ToolDefinition("get_property_values", "g", schema, true),
                new ToolDefinition("query_property_history", "h", schema, true),
                new ToolDefinition("query_entities_by_taxonomy", "t", schema, true));
    }
}
