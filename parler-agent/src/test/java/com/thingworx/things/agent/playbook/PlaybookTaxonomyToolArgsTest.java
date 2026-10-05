package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Regression: taxonomy vars must stay compatible with {@code query_entities_by_taxonomy}. */
class PlaybookTaxonomyToolArgsTest {

    @Test
    void assetsByRegion_resolvesCriticalPropertiesAsSemicolonString() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-args", PlaybookIds.V1A_PLAYBOOK_ID,
                new JSONObject().put("assetType", "Stacking Robot")
                        .put("regions", new JSONArray().put("USA").put("Germany")));
        ctx.putVar("assetTaxonomy.rows", new JSONArray().put(new JSONObject()
                .put("assetType", "Stacking Robot")
                .put("entityType", "ThingTemplate")
                .put("entityName", "StackingRobotTemplate")
                .put("criticalProperties", "PTCDisplayName;PTCMake;PTCModel")));
        PlaybookDeriveOps.execute("pick_taxonomy_row", new JSONObject()
                .put("assetType", "Stacking Robot")
                .put("taxonomyRows", new JSONObject().put("$var", "assetTaxonomy.rows")), ctx);

        JSONObject playbook = loadReferencePlaybook();
        JSONObject assetsByRegion = findNode(playbook, "assets_by_region");
        JSONObject toolArgs = assetsByRegion.getJSONObject("node").getJSONObject("args");
        JSONObject resolved = PlaybookExpressionResolver.resolveArgs(toolArgs, ctx);

        Object critical = resolved.get("CriticalProperties");
        assertInstanceOf(String.class, critical,
                "query_entities_by_taxonomy expects semicolon-separated CriticalProperties text");
        assertEquals("PTCDisplayName;PTCMake;PTCModel", critical);
    }

    private static JSONObject loadReferencePlaybook() throws Exception {
        try (InputStream in = PlaybookTaxonomyToolArgsTest.class.getResourceAsStream(
                "/playbook-packaging-fixture/cross_region_health/playbook.json")) {
            Objects.requireNonNull(in, "fixture");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new JSONObject(json);
        }
    }

    private static JSONObject findNode(JSONObject playbook, String nodeId) {
        JSONArray nodes = playbook.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (nodeId.equals(node.optString("id", ""))) {
                return node;
            }
        }
        throw new IllegalArgumentException("node not found: " + nodeId);
    }
}
