package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookDeriveOpsEvidenceTest {

    @Test
    void groupAndSummarize_populatesPerRegionAlertGroupsAndComparison() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-1", PlaybookIds.V1A_PLAYBOOK_ID,
                new JSONObject().put("regions", new JSONArray().put("USA").put("Germany")));

        JSONObject regionEntities = new JSONObject();
        regionEntities.put("status", "ok");
        regionEntities.put("output", new JSONObject().put("assets", new JSONArray()
                .put(asset("USA", "Thing-A"))
                .put(asset("Germany", "Thing-B"))));
        ctx.putNodeOutput("region_entities", regionEntities);

        ctx.putNodeOutput("alerts_by_asset", alertFanOut(
                alertChild("USA", "Thing-A", inlineAlertRows(3, "WristTemp")),
                alertChild("Germany", "Thing-B", inlineAlertRows(1, "WristTemp"))));

        JSONObject groupArgs = new JSONObject()
                .put("fanOutNodeId", "alerts_by_asset")
                .put("assetsRef", "region_entities.output.assets");
        JSONObject grouped = PlaybookDeriveOps.execute("group_alerts_by_source_property", groupArgs, ctx);
        ctx.putNodeOutput("alert_groups", grouped);

        JSONObject summarizeArgs = new JSONObject()
                .put("assetsRef", "region_entities.output.assets")
                .put("alertGroupsRef", "alert_groups.output.groups")
                .put("valuesFanOutNodeId", "values_by_asset");
        ctx.putNodeOutput("values_by_asset", valuesFanOut(
                valuesChild("Thing-A", propertyValues("Speed", 12)),
                valuesChild("Thing-B", propertyValues("Voltage", 220))));

        JSONObject summary = PlaybookDeriveOps.execute("summarize_region_health", summarizeArgs, ctx);
        JSONObject output = summary.getJSONObject("output");
        JSONArray regions = output.getJSONArray("regions");

        JSONObject usa = findRegion(regions, "USA");
        assertEquals(1, usa.getInt("assetCount"));
        assertEquals(1, usa.getJSONArray("alertGroups").length());
        assertEquals("WristTemp", usa.getJSONArray("alertGroups").getJSONObject(0).getString("property"));
        assertEquals(3, usa.getJSONArray("alertGroups").getJSONObject(0).getInt("alertCount"));
        assertTrue(usa.getJSONArray("topProperties").length() <= 5);

        JSONObject germany = findRegion(regions, "Germany");
        assertEquals(1, germany.getInt("assetCount"));
        assertEquals(1, germany.getJSONArray("alertGroups").length());
        assertTrue(germany.getJSONArray("topProperties").length() <= 5);

        JSONObject comparison = output.getJSONObject("comparison");
        assertEquals("USA", comparison.getString("higherAttentionRegion"));
        assertTrue(comparison.getJSONArray("reasons").length() > 0);
    }

    @Test
    void largeAlertSummary_usesSampleRowsAndRecordsGap() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-2", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets",
                        new JSONArray().put(asset("USA", "Thing-A")))));

        JSONObject largeTool = new JSONObject();
        largeTool.put("resultKind", "INFOTABLE_LARGE");
        largeTool.put("totalRows", 50);
        largeTool.put("sampleRows", inlineAlertRows(2, "EmergencyStop").getJSONArray("rows"));
        ctx.putNodeOutput("alerts_by_asset", alertFanOut(alertChild("USA", "Thing-A", largeTool)));

        JSONObject grouped = PlaybookDeriveOps.execute("group_alerts_by_source_property",
                new JSONObject()
                        .put("fanOutNodeId", "alerts_by_asset")
                        .put("assetsRef", "region_entities.output.assets"),
                ctx);
        JSONObject groupsByRegion = grouped.getJSONObject("output").getJSONObject("groupsByRegion");
        JSONArray usaGroups = groupsByRegion.getJSONArray("USA");
        assertEquals(1, usaGroups.length());
        assertEquals("EmergencyStop", usaGroups.getJSONObject(0).getString("property"));
        assertTrue(grouped.getJSONObject("output").has("regionAlertGaps"));
    }

    @Test
    void extractField_returnsValues() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-3", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putVar("rows", new JSONArray()
                .put(new JSONObject().put("name", "A"))
                .put(new JSONObject().put("name", "B")));
        JSONObject out = PlaybookDeriveOps.execute("extract_field",
                new JSONObject()
                        .put("rows", new JSONObject().put("$var", "rows"))
                        .put("fieldName", "name"),
                ctx);
        JSONArray values = out.getJSONObject("output").getJSONArray("values");
        assertEquals(2, values.length());
        assertEquals("A", values.getString(0));
    }

    @Test
    void comparisonNullOnTiedAlertTotals() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-tie", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray()
                        .put(asset("USA", "A1"))
                        .put(asset("Germany", "B1")))));
        ctx.putNodeOutput("alerts_by_asset", alertFanOut(
                alertChild("USA", "A1", inlineAlertRows(2, "Speed")),
                alertChild("Germany", "B1", inlineAlertRows(2, "Speed"))));
        JSONObject grouped = PlaybookDeriveOps.execute("group_alerts_by_source_property",
                new JSONObject()
                        .put("fanOutNodeId", "alerts_by_asset")
                        .put("assetsRef", "region_entities.output.assets"),
                ctx);
        ctx.putNodeOutput("alert_groups", grouped);
        JSONObject summary = PlaybookDeriveOps.execute("summarize_region_health",
                new JSONObject().put("assetsRef", "region_entities.output.assets"), ctx);
        JSONObject comparison = summary.getJSONObject("output").getJSONObject("comparison");
        Object higher = comparison.get("higherAttentionRegion");
        assertTrue(higher == null || higher == JSONObject.NULL);
        assertTrue(comparison.getJSONArray("reasons").optString(0, "").contains("tied"));
    }

    @Test
    void flattenRegionEntities_recordsGapOnLargeTaxonomyResult() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-large", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        JSONObject largeTool = new JSONObject();
        largeTool.put("resultKind", "ENTITY_TAXONOMY_QUERY_LARGE");
        largeTool.put("totalCount", 120);
        largeTool.put("sampleRootEntityList", new JSONArray()
                .put(new JSONObject().put("name", "Thing-Only")));
        ctx.putNodeOutput("assets_by_region", new JSONObject()
                .put("status", "ok")
                .put("children", new JSONArray()
                        .put(new JSONObject()
                                .put("region", "USA")
                                .put("status", "ok")
                                .put("toolOutput", largeTool))));
        JSONObject flat = PlaybookDeriveOps.execute("flatten_region_entities",
                new JSONObject().put("fanOutNodeId", "assets_by_region"), ctx);
        JSONArray gaps = flat.getJSONObject("output").getJSONArray("gaps");
        assertEquals(1, gaps.length());
        assertTrue(gaps.getJSONObject(0).getString("message").contains("truncated"));
        assertEquals("TAXONOMY_PARTIAL", gaps.getJSONObject(0).getString("code"));
        assertEquals(1, flat.getJSONObject("output").getJSONArray("assets").length());
    }

    @Test
    void pickTaxonomyRow_keepsSemicolonStringForToolsAndListForFormatter() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-tax", PlaybookIds.V1A_PLAYBOOK_ID,
                new JSONObject().put("assetType", "Stacking Robot"));
        ctx.putVar("assetTaxonomy.rows", new JSONArray().put(new JSONObject()
                .put("assetType", "Stacking Robot")
                .put("entityType", "ThingTemplate")
                .put("entityName", "StackingRobotTemplate")
                .put("criticalProperties", "PTCDisplayName;Speed;Voltage")));
        JSONObject out = PlaybookDeriveOps.execute("pick_taxonomy_row", new JSONObject()
                .put("assetType", "Stacking Robot")
                .put("taxonomyRows", new JSONObject().put("$var", "assetTaxonomy.rows")), ctx);
        JSONObject taxonomyOut = out.getJSONObject("output");
        assertEquals("PTCDisplayName;Speed;Voltage", taxonomyOut.getString("CriticalProperties"));
        JSONArray criticalList = taxonomyOut.getJSONArray("CriticalPropertiesList");
        assertEquals(3, criticalList.length());
        assertEquals("PTCDisplayName", criticalList.getString(0));
        assertEquals("Speed", criticalList.getString(1));
    }

    @Test
    void summarizeCurrentValuesByRegion_rollsUpPropertyExamples() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-cv", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        // Match production: build_property_union stores List<String> on propertyUnion.names
        ctx.putVar("propertyUnion.names", new ArrayList<>(List.of("robotSpeed", "operationalVoltage")));
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray()
                        .put(asset("USA", "Thing-A"))
                        .put(asset("USA", "Thing-B"))
                        .put(asset("Germany", "Thing-C")))));
        JSONArray propsA = new JSONArray()
                .put(new JSONObject().put("name", "robotSpeed").put("ok", true).put("value", 10.0))
                .put(new JSONObject().put("name", "operationalVoltage").put("ok", true).put("value", 22.0));
        JSONArray propsB = new JSONArray()
                .put(new JSONObject().put("name", "robotSpeed").put("ok", true).put("value", 12.0))
                .put(new JSONObject().put("name", "operationalVoltage").put("ok", true).put("value", 21.0));
        JSONArray propsC = new JSONArray()
                .put(new JSONObject().put("name", "robotSpeed").put("ok", true).put("value", 7.5))
                .put(new JSONObject().put("name", "operationalVoltage").put("ok", true).put("value", 30.0));
        ctx.putNodeOutput("values_by_asset", valuesFanOut(
                valuesChild("Thing-A", propertyValuesSuccess("Thing-A", propsA)),
                valuesChild("Thing-B", propertyValuesSuccess("Thing-B", propsB)),
                valuesChild("Thing-C", propertyValuesSuccess("Thing-C", propsC))));

        JSONObject out = PlaybookDeriveOps.execute("summarize_current_values_by_region",
                new JSONObject()
                        .put("assetsRef", "region_entities.output.assets")
                        .put("valuesFanOutNodeId", "values_by_asset")
                        .put("propertyNames", new JSONObject().put("$var", "propertyUnion.names"))
                        .put("maxPropertiesPerRegion", 24)
                        .put("maxExamplesPerProperty", 2),
                ctx);
        assertEquals("ok", out.optString("status"));
        JSONArray regions = out.getJSONObject("output").getJSONArray("regions");
        assertEquals(2, regions.length());
        JSONObject usa = findRegionByKey(regions, "USA");
        JSONArray usaProps = usa.getJSONArray("properties");
        assertEquals(2, usaProps.length());
        assertEquals("robotSpeed", usaProps.getJSONObject(0).getString("name"));
        assertEquals(2, usaProps.getJSONObject(0).getInt("successfulReads"));
        assertEquals(0, usaProps.getJSONObject(0).getInt("failedReads"));
        assertEquals(2, usaProps.getJSONObject(0).getJSONArray("examples").length());
        assertTrue(usaProps.getJSONObject(0).has("numericMean"));
    }

    @Test
    void coercePropertyNames_acceptsListJsonArrayAndSemicolonString() {
        assertEquals(List.of("a", "b"),
                PlaybookDeriveOps.coercePropertyNames(new JSONArray().put(" a ").put("b"), 10));
        assertEquals(List.of("x", "y"),
                PlaybookDeriveOps.coercePropertyNames(new ArrayList<>(List.of("x", " y ")), 10));
        assertEquals(List.of("p", "q"), PlaybookDeriveOps.coercePropertyNames("p;q", 10));
        assertEquals(List.of("u"), PlaybookDeriveOps.coercePropertyNames(List.of("u", "v"), 1));
    }

    @Test
    void summarizeCurrentValuesByRegion_examplesCapDoesNotTruncateNumericAggregates() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-cv4", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putVar("propertyUnion.names", new ArrayList<>(List.of("robotSpeed")));
        JSONArray assets = new JSONArray()
                .put(asset("USA", "R1"))
                .put(asset("USA", "R2"))
                .put(asset("USA", "R3"))
                .put(asset("USA", "R4"));
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", assets)));
        // Intentionally out of example order: mean must include R4 even when maxExamples=2
        ctx.putNodeOutput("values_by_asset", valuesFanOut(
                valuesChild("R1", propertyValuesSuccess("R1", singleNumericProp("robotSpeed", 10.0))),
                valuesChild("R2", propertyValuesSuccess("R2", singleNumericProp("robotSpeed", 12.0))),
                valuesChild("R3", propertyValuesSuccess("R3", singleNumericProp("robotSpeed", 14.0))),
                valuesChild("R4", propertyValuesSuccess("R4", singleNumericProp("robotSpeed", 18.0)))));
        JSONObject out = PlaybookDeriveOps.execute("summarize_current_values_by_region",
                new JSONObject()
                        .put("assetsRef", "region_entities.output.assets")
                        .put("valuesFanOutNodeId", "values_by_asset")
                        .put("propertyNames", new JSONObject().put("$var", "propertyUnion.names"))
                        .put("maxExamplesPerProperty", 2),
                ctx);
        JSONObject usa = findRegionByKey(out.getJSONObject("output").getJSONArray("regions"), "USA");
        JSONObject rs = usa.getJSONArray("properties").getJSONObject(0);
        assertEquals(4, rs.getInt("successfulReads"));
        assertEquals(2, rs.getJSONArray("examples").length());
        assertEquals(10.0, rs.getDouble("numericMin"), 1e-9);
        assertEquals(18.0, rs.getDouble("numericMax"), 1e-9);
        assertEquals(13.5, rs.getDouble("numericMean"), 1e-9);
    }

    @Test
    void summarizeCurrentValuesByRegion_failedOkIncrementsFailedReads() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-cv-fail", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putVar("propertyUnion.names", new ArrayList<>(List.of("robotSpeed")));
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray()
                        .put(asset("USA", "T1"))
                        .put(asset("USA", "T2")))));
        JSONArray okRow = new JSONArray().put(new JSONObject()
                .put("name", "robotSpeed").put("ok", true).put("value", 5.0));
        JSONArray badRow = new JSONArray().put(new JSONObject()
                .put("name", "robotSpeed").put("ok", false).put("message", "no permission"));
        ctx.putNodeOutput("values_by_asset", valuesFanOut(
                valuesChild("T1", propertyValuesSuccess("T1", okRow)),
                valuesChild("T2", propertyValuesSuccess("T2", badRow))));
        JSONObject out = PlaybookDeriveOps.execute("summarize_current_values_by_region",
                new JSONObject()
                        .put("assetsRef", "region_entities.output.assets")
                        .put("propertyNames", new JSONObject().put("$var", "propertyUnion.names")),
                ctx);
        JSONObject pr = findRegionByKey(out.getJSONObject("output").getJSONArray("regions"), "USA")
                .getJSONArray("properties").getJSONObject(0);
        assertEquals(1, pr.getInt("successfulReads"));
        assertEquals(1, pr.getInt("failedReads"));
    }

    @Test
    void summarizeCurrentValuesByRegion_okTrueNullValueCountsAsSuccessfulRead() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-cv-null", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putVar("propertyUnion.names", new ArrayList<>(List.of("robotSpeed")));
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray().put(asset("USA", "T1")))));
        JSONArray row = new JSONArray().put(new JSONObject()
                .put("name", "robotSpeed").put("ok", true).put("value", JSONObject.NULL));
        ctx.putNodeOutput("values_by_asset", valuesFanOut(
                valuesChild("T1", propertyValuesSuccess("T1", row))));
        JSONObject out = PlaybookDeriveOps.execute("summarize_current_values_by_region",
                new JSONObject()
                        .put("assetsRef", "region_entities.output.assets")
                        .put("propertyNames", new JSONObject().put("$var", "propertyUnion.names")),
                ctx);
        JSONObject pr = findRegionByKey(out.getJSONObject("output").getJSONArray("regions"), "USA")
                .getJSONArray("properties").getJSONObject(0);
        assertEquals(1, pr.getInt("successfulReads"));
        assertFalse(pr.has("numericMean"));
    }

    @Test
    void buildPropertyUnion_thenSummarizeCurrentValues_readsListBackedNames() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-chain", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putVar("taxonomy", Map.of("CriticalProperties", "robotSpeed"));
        ctx.putNodeOutput("alert_groups", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("groups", new JSONArray())));
        JSONObject union = PlaybookDeriveOps.execute("build_property_union", new JSONObject()
                .put("criticalProperties", new JSONObject().put("$var", "taxonomy.CriticalProperties"))
                .put("alertGroupsRef", "alert_groups.output.groups"), ctx);
        assertEquals("ok", union.optString("status"));
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray().put(asset("USA", "A1")))));
        JSONArray props = new JSONArray().put(new JSONObject()
                .put("name", "robotSpeed").put("ok", true).put("value", 42.0));
        ctx.putNodeOutput("values_by_asset", valuesFanOut(
                valuesChild("A1", propertyValuesSuccess("A1", props))));
        JSONObject stats = PlaybookDeriveOps.execute("summarize_current_values_by_region",
                new JSONObject()
                        .put("assetsRef", "region_entities.output.assets")
                        .put("propertyNames", new JSONObject().put("$var", "propertyUnion.names")),
                ctx);
        JSONObject pr = findRegionByKey(stats.getJSONObject("output").getJSONArray("regions"), "USA")
                .getJSONArray("properties").getJSONObject(0);
        assertEquals("robotSpeed", pr.getString("name"));
        assertEquals(1, pr.getInt("successfulReads"));
        assertEquals(42.0, pr.getDouble("numericMean"), 1e-9);
        assertFalse(stats.has("evidenceText"));
        assertTrue(stats.getJSONArray("evidenceLines").length() > 0);
    }

    @Test
    void summarizeCurrentValuesByRegion_skipsPropertiesWithNoReadsInRegion() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-skip", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putVar("propertyUnion.names", new ArrayList<>(List.of("hasData", "missingEverywhere")));
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray().put(asset("USA", "T1")))));
        JSONArray onlyHasData = new JSONArray().put(new JSONObject()
                .put("name", "hasData").put("ok", true).put("value", 9.0));
        ctx.putNodeOutput("values_by_asset", valuesFanOut(
                valuesChild("T1", propertyValuesSuccess("T1", onlyHasData))));
        JSONObject out = PlaybookDeriveOps.execute("summarize_current_values_by_region",
                new JSONObject()
                        .put("assetsRef", "region_entities.output.assets")
                        .put("propertyNames", new JSONObject().put("$var", "propertyUnion.names")),
                ctx);
        JSONArray props = findRegionByKey(out.getJSONObject("output").getJSONArray("regions"), "USA")
                .getJSONArray("properties");
        assertEquals(1, props.length());
        assertEquals("hasData", props.getJSONObject(0).getString("name"));
    }

    @Test
    void summarizeCurrentValuesByRegion_appliesExcludePropertyNames() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-excl", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putVar("propertyUnion.names", new ArrayList<>(List.of("robotSpeed", "operationalVoltage")));
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray().put(asset("USA", "T1")))));
        JSONArray both = new JSONArray()
                .put(new JSONObject().put("name", "robotSpeed").put("ok", true).put("value", 5.0))
                .put(new JSONObject().put("name", "operationalVoltage").put("ok", true).put("value", 9.0));
        ctx.putNodeOutput("values_by_asset", valuesFanOut(
                valuesChild("T1", propertyValuesSuccess("T1", both))));
        JSONObject out = PlaybookDeriveOps.execute("summarize_current_values_by_region",
                new JSONObject()
                        .put("assetsRef", "region_entities.output.assets")
                        .put("propertyNames", new JSONObject().put("$var", "propertyUnion.names"))
                        .put("excludePropertyNames", new JSONArray().put("robotSpeed")),
                ctx);
        JSONArray props = findRegionByKey(out.getJSONObject("output").getJSONArray("regions"), "USA")
                .getJSONArray("properties");
        assertEquals(1, props.length());
        assertEquals("operationalVoltage", props.getJSONObject(0).getString("name"));
    }

    @Test
    void summarizeCurrentValuesByRegion_nodeOutputFitsSummaryByteCap() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-cap", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        List<String> propNames = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            propNames.add("wideProp_" + i);
        }
        ctx.putVar("propertyUnion.names", propNames);
        JSONArray assets = new JSONArray();
        String longThingPrefix = "ORD-StackingRobot-ABCDEFGHIJKLMNOP";
        for (int r = 0; r < 4; r++) {
            assets.put(asset("USA", longThingPrefix + "-USA-" + r));
        }
        for (int r = 0; r < 2; r++) {
            assets.put(asset("Germany", longThingPrefix + "-DE-" + r));
        }
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", assets)));
        JSONArray children = new JSONArray();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String nm = a.getString("name");
            JSONArray propArr = new JSONArray();
            for (int p = 0; p < 12; p++) {
                propArr.put(new JSONObject()
                        .put("name", "wideProp_" + p)
                        .put("ok", true)
                        .put("value", 1.0 + p * 0.01));
            }
            children.put(valuesChild(nm, propertyValuesSuccess(nm, propArr)));
        }
        ctx.putNodeOutput("values_by_asset", new JSONObject().put("status", "ok").put("children", children));
        JSONObject out = PlaybookDeriveOps.execute("summarize_current_values_by_region",
                new JSONObject()
                        .put("assetsRef", "region_entities.output.assets")
                        .put("propertyNames", new JSONObject().put("$var", "propertyUnion.names"))
                        .put("maxPropertiesPerRegion", 12)
                        .put("maxExamplesPerProperty", 1),
                ctx);
        assertEquals("ok", out.optString("status"));
        int len = out.toString().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(len <= 8192, "serialized node len=" + len);
        assertTrue(out.getJSONArray("evidenceLines").length() > 0);
        assertFalse(out.has("evidenceText"));
    }

    @Test
    void buildPropertyUnion_alertPropertiesBeforeCritical() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-union", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putVar("taxonomy", Map.of(
                "CriticalProperties", "PTCDisplayName;Speed",
                "CriticalPropertiesList", new JSONArray().put("PTCDisplayName").put("Speed")));
        ctx.putNodeOutput("alert_groups", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("groups", new JSONArray()
                        .put(new JSONObject().put("property", "WristTemp").put("alertCount", 2)))));
        JSONObject out = PlaybookDeriveOps.execute("build_property_union", new JSONObject()
                .put("criticalProperties", new JSONObject().put("$var", "taxonomy.CriticalProperties"))
                .put("alertGroupsRef", "alert_groups.output.groups"), ctx);
        JSONArray names = out.getJSONObject("output").getJSONArray("names");
        assertEquals("WristTemp", names.getString(0));
        assertEquals("PTCDisplayName", names.getString(1));
    }

    @Test
    void groupAlertsFromMultiSummaryRollup() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-multi-alert", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray()
                        .put(asset("USA", "Thing-A"))
                        .put(asset("Germany", "Thing-B")))));
        JSONObject multi = new JSONObject()
                .put("status", "success")
                .put("resultKind", "ALERT_SUMMARY_MULTI")
                .put("completeness", "complete")
                .put("byThing", new JSONArray()
                        .put(multiThingEntry("Thing-A", "Speed", 2))
                        .put(multiThingEntry("Thing-B", "Speed", 1)));
        ctx.putNodeOutput("alerts_by_region", new JSONObject().put("status", "ok").put("toolOutput", multi));
        JSONObject grouped = PlaybookDeriveOps.execute("group_alerts_by_source_property",
                new JSONObject()
                        .put("alertSummaryNodeId", "alerts_by_region")
                        .put("assetsRef", "region_entities.output.assets"),
                ctx);
        assertEquals("ok", grouped.optString("status"));
        assertEquals(3, grouped.getJSONObject("output").getJSONArray("groups").getJSONObject(0).getInt("alertCount"));
    }

    @Test
    void groupAlertsFromMultiSummaryRollup_emitsGapWhenTopAlertsTruncated() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-multi-gap", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray()
                        .put(asset("USA", "Thing-A")))));
        JSONArray top = new JSONArray();
        for (int i = 0; i < 3; i++) {
            top.put(new JSONObject().put("sourceProperty", "Speed"));
        }
        JSONObject multi = new JSONObject()
                .put("status", "success")
                .put("resultKind", "ALERT_SUMMARY_MULTI")
                .put("completeness", "complete")
                .put("byThing", new JSONArray()
                        .put(new JSONObject()
                                .put("thingName", "Thing-A")
                                .put("status", "success")
                                .put("totalAlerts", 10)
                                .put("topAlerts", top)));
        ctx.putNodeOutput("alerts_by_region", new JSONObject().put("status", "ok").put("toolOutput", multi));
        JSONObject grouped = PlaybookDeriveOps.execute("group_alerts_by_source_property",
                new JSONObject()
                        .put("alertSummaryNodeId", "alerts_by_region")
                        .put("assetsRef", "region_entities.output.assets"),
                ctx);
        assertEquals(3, grouped.getJSONObject("output").getJSONArray("groups").getJSONObject(0).getInt("alertCount"));
        JSONObject gaps = grouped.getJSONObject("output").getJSONObject("regionAlertGaps");
        assertTrue(gaps.has("USA"));
        assertTrue(gaps.getJSONArray("USA").getString(0).contains("top 3 alert sample"));
        assertTrue(gaps.getJSONArray("USA").getString(0).contains("10 total alerts"));
        assertTrue(gaps.getJSONArray("USA").getString(0).contains("remainder not retained in evidence"),
                "without a retained per-Thing cacheId the gap note must not promise one");
        assertFalse(gaps.getJSONArray("USA").getString(0).contains("cacheId"));
    }

    @Test
    void groupAlertsFromMultiSummaryRollup_gapNoteClaimsCacheIdOnlyWhenRetained() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-multi-gap-cache", PlaybookIds.V1A_PLAYBOOK_ID,
                new JSONObject());
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray()
                        .put(asset("USA", "Thing-A")))));
        JSONArray top = new JSONArray();
        for (int i = 0; i < 3; i++) {
            top.put(new JSONObject().put("sourceProperty", "Speed"));
        }
        JSONObject multi = new JSONObject()
                .put("status", "success")
                .put("resultKind", "ALERT_SUMMARY_MULTI")
                .put("completeness", "complete")
                .put("byThing", new JSONArray()
                        .put(new JSONObject()
                                .put("thingName", "Thing-A")
                                .put("status", "success")
                                .put("totalAlerts", 30)
                                .put("cacheId", "cache-123")
                                .put("topAlerts", top)));
        ctx.putNodeOutput("alerts_by_region", new JSONObject().put("status", "ok").put("toolOutput", multi));
        JSONObject grouped = PlaybookDeriveOps.execute("group_alerts_by_source_property",
                new JSONObject()
                        .put("alertSummaryNodeId", "alerts_by_region")
                        .put("assetsRef", "region_entities.output.assets"),
                ctx);
        JSONObject gaps = grouped.getJSONObject("output").getJSONObject("regionAlertGaps");
        assertTrue(gaps.getJSONArray("USA").getString(0).contains("remainder via per-Thing cacheId"),
                "with a retained handle the gap note may direct drill-in to it");
    }

    @Test
    void groupAlertsFromMultiSummaryRollup_attributionDistinguishesThings() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-multi-attr", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray()
                        .put(asset("USA", "Thing-A"))
                        .put(asset("USA", "Thing-C"))
                        .put(asset("Germany", "Thing-B")))));
        JSONObject multi = new JSONObject()
                .put("status", "success")
                .put("resultKind", "ALERT_SUMMARY_MULTI")
                .put("completeness", "complete")
                .put("byThing", new JSONArray()
                        .put(new JSONObject()
                                .put("thingName", "Thing-A")
                                .put("status", "success")
                                .put("totalAlerts", 1)
                                .put("topAlerts", new JSONArray().put(new JSONObject()
                                        .put("alertName", "Voltage_Low")
                                        .put("sourceProperty", "operationalVoltage"))))
                        .put(new JSONObject()
                                .put("thingName", "Thing-C")
                                .put("status", "success")
                                .put("totalAlerts", 0)
                                .put("topAlerts", new JSONArray()))
                        .put(new JSONObject()
                                .put("thingName", "Thing-B")
                                .put("status", "success")
                                .put("totalAlerts", 1)
                                .put("topAlerts", new JSONArray().put(new JSONObject()
                                        .put("alertName", "Seal_Time_High")
                                        .put("sourceProperty", "sealingTime")))));
        ctx.putNodeOutput("alerts_by_region", new JSONObject().put("status", "ok").put("toolOutput", multi));
        JSONObject grouped = PlaybookDeriveOps.execute("group_alerts_by_source_property",
                new JSONObject()
                        .put("alertSummaryNodeId", "alerts_by_region")
                        .put("assetsRef", "region_entities.output.assets"),
                ctx);
        JSONArray attribution = grouped.getJSONObject("output").getJSONArray("alertAttribution");
        assertEquals(2, attribution.length());
        assertEquals("Thing-A", attribution.getJSONObject(0).getString("thingName"));
        assertEquals("Voltage_Low", attribution.getJSONObject(0).getString("alertName"));
        assertEquals("USA", attribution.getJSONObject(0).getString("region"));
        assertEquals("Thing-B", attribution.getJSONObject(1).getString("thingName"));
        assertEquals("Seal_Time_High", attribution.getJSONObject(1).getString("alertName"));
        for (int i = 0; i < attribution.length(); i++) {
            assertFalse("Thing-C".equals(attribution.getJSONObject(i).optString("thingName")),
                    "zero-alert Thing must never appear in attribution");
        }
        assertFalse(grouped.getJSONObject("output").has("alertAttributionOmitted"));
        JSONArray evidence = grouped.getJSONArray("evidenceLines");
        boolean sawA = false;
        boolean sawB = false;
        for (int i = 0; i < evidence.length(); i++) {
            String line = evidence.getString(i);
            assertFalse(line.contains("Thing-C:"), "zero-alert Thing must not gain an attribution line");
            sawA |= line.contains("alerts on Thing-A [USA]: Voltage_Low(operationalVoltage)");
            sawB |= line.contains("alerts on Thing-B [Germany]: Seal_Time_High(sealingTime)");
        }
        assertTrue(sawA);
        assertTrue(sawB);
    }

    @Test
    void groupAlertsFromMultiSummaryRollup_attributionCapTruncatesTruthfully() throws Exception {
        // Production-shaped overflow: the rollup caps topAlerts at 3 per Thing, so the aggregate 40-row cap is
        // crossed by many small Things (15 x 3 = 45), none of which exposes a per-Thing cacheId.
        PlaybookRunContext ctx = new PlaybookRunContext("run-multi-attr-cap", PlaybookIds.V1A_PLAYBOOK_ID,
                new JSONObject());
        JSONArray assets = new JSONArray();
        JSONArray byThing = new JSONArray();
        for (int t = 0; t < 15; t++) {
            String name = "Thing-" + t;
            assets.put(asset(t % 2 == 0 ? "USA" : "Germany", name));
            JSONArray top = new JSONArray();
            for (int a = 0; a < 3; a++) {
                top.put(new JSONObject().put("alertName", "A" + t + "_" + a).put("sourceProperty", "Speed"));
            }
            byThing.put(new JSONObject()
                    .put("thingName", name)
                    .put("status", "success")
                    .put("totalAlerts", 3)
                    .put("topAlerts", top));
        }
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", assets)));
        JSONObject multi = new JSONObject()
                .put("status", "success")
                .put("resultKind", "ALERT_SUMMARY_MULTI")
                .put("completeness", "complete")
                .put("byThing", byThing);
        ctx.putNodeOutput("alerts_by_region", new JSONObject().put("status", "ok").put("toolOutput", multi));
        JSONObject grouped = PlaybookDeriveOps.execute("group_alerts_by_source_property",
                new JSONObject()
                        .put("alertSummaryNodeId", "alerts_by_region")
                        .put("assetsRef", "region_entities.output.assets"),
                ctx);
        JSONObject output = grouped.getJSONObject("output");
        assertEquals(40, output.getJSONArray("alertAttribution").length());
        assertEquals(5, output.getInt("alertAttributionOmitted"));
        assertEquals(45, output.getJSONArray("groups").getJSONObject(0).getInt("alertCount"));
        JSONArray evidence = grouped.getJSONArray("evidenceLines");
        boolean sawOmitted = false;
        for (int i = 0; i < evidence.length(); i++) {
            String line = evidence.getString(i);
            sawOmitted |= line.contains("alert attribution omits 5 additional attributed alert row(s) beyond the cap.");
            assertFalse(line.contains("alert attribution omits") && line.contains("cacheId"),
                    "omission evidence must not promise drill-in handles that are not present");
        }
        assertTrue(sawOmitted);
    }

    @Test
    void comparisonNullWhenOnlyOneRegion() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-4", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets",
                        new JSONArray().put(asset("USA", "Thing-A")))));
        ctx.putNodeOutput("alerts_by_asset", alertFanOut(
                alertChild("USA", "Thing-A", inlineAlertRows(1, "Speed"))));
        JSONObject grouped = PlaybookDeriveOps.execute("group_alerts_by_source_property",
                new JSONObject()
                        .put("fanOutNodeId", "alerts_by_asset")
                        .put("assetsRef", "region_entities.output.assets"),
                ctx);
        ctx.putNodeOutput("alert_groups", grouped);
        JSONObject summary = PlaybookDeriveOps.execute("summarize_region_health",
                new JSONObject().put("assetsRef", "region_entities.output.assets"), ctx);
        Object higher = summary.getJSONObject("output").getJSONObject("comparison").get("higherAttentionRegion");
        assertTrue(higher == null || higher == JSONObject.NULL);
        assertEquals(0, summary.getJSONObject("output").getJSONObject("comparison").getJSONArray("reasons").length());
    }

    private static JSONObject asset(String region, String name) {
        return new JSONObject().put("region", region).put("name", name);
    }

    private static JSONObject alertFanOut(JSONObject... children) {
        return new JSONObject().put("status", "ok").put("children", new JSONArray(children));
    }

    private static JSONObject alertChild(String region, String thingName, JSONObject toolOutput) {
        return new JSONObject()
                .put("region", region)
                .put("status", "ok")
                .put("item", new JSONObject().put("region", region).put("name", thingName))
                .put("toolOutput", toolOutput);
    }

    private static JSONObject valuesFanOut(JSONObject... children) {
        return new JSONObject().put("status", "ok").put("children", new JSONArray(children));
    }

    private static JSONObject valuesChild(String thingName, JSONObject toolOutput) {
        return new JSONObject()
                .put("status", "ok")
                .put("item", new JSONObject().put("name", thingName))
                .put("toolOutput", toolOutput);
    }

    private static JSONObject propertyValues(String name, Object value) {
        JSONArray props = new JSONArray();
        props.put(new JSONObject().put("name", name).put("value", value));
        return new JSONObject().put("properties", props);
    }

    private static JSONObject propertyValuesSuccess(String thingName, JSONArray properties) {
        return new JSONObject()
                .put("status", "success")
                .put("thingName", thingName)
                .put("properties", properties);
    }

    private static JSONArray singleNumericProp(String name, double value) {
        return new JSONArray().put(new JSONObject()
                .put("name", name).put("ok", true).put("value", value));
    }

    private static JSONObject inlineAlertRows(int count, String sourceProperty) {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < count; i++) {
            rows.put(new JSONObject().put("sourceProperty", sourceProperty));
        }
        JSONObject tool = new JSONObject();
        tool.put("resultKind", "INFOTABLE");
        tool.put("rows", rows);
        return tool;
    }

    private static JSONObject findRegion(JSONArray regions, String name) {
        for (int i = 0; i < regions.length(); i++) {
            JSONObject r = regions.getJSONObject(i);
            if (name.equals(r.getString("name"))) {
                return r;
            }
        }
        throw new AssertionError("region not found: " + name);
    }

    private static JSONObject findRegionByKey(JSONArray regions, String regionKey) {
        for (int i = 0; i < regions.length(); i++) {
            JSONObject r = regions.getJSONObject(i);
            if (regionKey.equals(r.optString("region", ""))) {
                return r;
            }
        }
        throw new AssertionError("region not found: " + regionKey);
    }

    private static JSONObject multiThingEntry(String thingName, String sourceProperty, int alertCount) {
        JSONArray top = new JSONArray();
        for (int i = 0; i < alertCount; i++) {
            top.put(new JSONObject().put("sourceProperty", sourceProperty));
        }
        return new JSONObject()
                .put("thingName", thingName)
                .put("status", "success")
                .put("totalAlerts", alertCount)
                .put("topAlerts", top);
    }
}
