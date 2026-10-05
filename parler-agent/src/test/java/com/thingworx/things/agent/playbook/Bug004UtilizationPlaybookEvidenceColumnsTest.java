package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Four-tool utilization playbooks declare nested {@code result.*} evidence paths; frozen fixtures must stay aligned
 * with {@code dev_data/scpa_utilization/playbooks/*}.
 */
class Bug004UtilizationPlaybookEvidenceColumnsTest {

    @Test
    void utilizationPlaybooks_declareNestedResultEvidencePaths() throws Exception {
        String[] resources = {
                "/bug004-scpa-utilization-fixture/utilization_summary/playbook.json",
                "/bug004-scpa-utilization-fixture/machine_utilization_summary/playbook.json",
                "/bug004-scpa-utilization-fixture/utilization_overview/playbook.json"
        };
        for (String res : resources) {
            JSONObject root = loadJson(res);
            boolean foundNested = false;
            JSONArray nodes = root.getJSONArray("nodes");
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                if (!"tool_call".equals(node.optString("kind", ""))) {
                    continue;
                }
                JSONObject ev = node.optJSONObject("evidence");
                if (ev == null) {
                    continue;
                }
                JSONArray paths = ev.optJSONArray("includeToolOutputPaths");
                JSONObject table = ev.optJSONObject("table");
                if (paths != null) {
                    for (int p = 0; p < paths.length(); p++) {
                        String dot = paths.optString(p, "");
                        assertTrue(dot.startsWith("result."), res + " path must be nested under result: " + dot);
                        assertTrue(PlaybookEvidencePathSupport.isValidDotPath(dot), res + " invalid path " + dot);
                    }
                }
                if (table != null && table.has("path")) {
                    String tablePath = table.optString("path", "");
                    assertTrue(tablePath.startsWith("result."), res + " table.path must be nested: " + tablePath);
                    foundNested = true;
                }
            }
            assertTrue(foundNested, res + " must configure nested evidence.table.path on a tool_call node");
        }
    }

    @Test
    void utilizationSummaryFixture_projectsNestedEvidenceText() throws Exception {
        String docJson = new String(
                Objects.requireNonNull(getClass().getResourceAsStream(
                        "/bug004-scpa-utilization-fixture/utilization_summary/playbook.json"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunContext ctx = new PlaybookRunContext("run-util", "utilization_summary", new JSONObject());
        JSONObject resultBody = new JSONObject()
                .put("status", "success")
                .put("scope", "all")
                .put("stats", new JSONObject().put("utilizationPercent", 64.36).put("eventCount", 40931))
                .put("rows", new JSONArray().put(new JSONObject()
                        .put("utilizationState", "Running")
                        .put("percentage", 58.42)
                        .put("count", 20235)
                        .put("sumDurationHours", 57875.5)));
        JSONObject toolOut = new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", resultBody);
        ctx.putNodeOutput("state_summary", new JSONObject().put("status", "ok").put("toolOutput", toolOut));
        String text = PlaybookEvidenceFormatter.format(doc, ctx, new JSONArray().put("state_summary"), 8000);
        assertTrue(text.contains("result.stats.utilizationPercent=64.36"), text);
        assertTrue(text.contains("result.stats.eventCount=40931"), text);
        assertTrue(text.contains("utilizationState=Running"), text);
        assertFalse(text.contains("row-count only"), text);
    }

    private static JSONObject loadJson(String classpathResource) throws Exception {
        try (InputStream in = Bug004UtilizationPlaybookEvidenceColumnsTest.class.getResourceAsStream(classpathResource)) {
            Objects.requireNonNull(in, "missing " + classpathResource);
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
