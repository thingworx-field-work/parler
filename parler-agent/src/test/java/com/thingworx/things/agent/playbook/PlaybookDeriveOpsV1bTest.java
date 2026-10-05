package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookDeriveOpsV1bTest {

    @Test
    void normalizeResolvedThing_identityNotFound_clarifies() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r8", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject toolOut = new JSONObject()
                .put("status", "error")
                .put("code", "IDENTITY_NOT_FOUND")
                .put("message", "No Thing matched.");
        ctx.putNodeOutput("resolve_a", new JSONObject().put("status", "ok").put("toolOutput", toolOut));
        JSONObject out = PlaybookDeriveOpsV1b.execute("normalize_resolved_thing",
                new JSONObject().put("sourceNodeId", "resolve_a"), ctx);
        assertEquals("needs_clarification", out.getString("status"));
        assertTrue(out.getString("message").contains("No Thing"), out.getString("message"));
    }

    @Test
    void normalizeResolvedThing_taxonomyUnavailable_clarifies() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r9", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject toolOut = new JSONObject()
                .put("status", "error")
                .put("code", "TAXONOMY_UNAVAILABLE")
                .put("message", "Identity rules not loaded.");
        ctx.putNodeOutput("resolve_a", new JSONObject().put("status", "ok").put("toolOutput", toolOut));
        JSONObject out = PlaybookDeriveOpsV1b.execute("normalize_resolved_thing",
                new JSONObject().put("sourceNodeId", "resolve_a"), ctx);
        assertEquals("needs_clarification", out.getString("status"));
        assertTrue(out.getString("message").contains("TAXONOMY_UNAVAILABLE")
                || out.getString("message").contains("Identity"), out.getString("message"));
    }

    @Test
    void normalizeResolvedThing_multiInline_matchesUserFacingMessage() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r10", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject toolOut = new JSONObject()
                .put("status", "success")
                .put("resultKind", "THING_RESOLVED_INLINE")
                .put("matches", new JSONArray()
                        .put(new JSONObject().put("name", "T1"))
                        .put(new JSONObject().put("name", "T2")));
        ctx.putNodeOutput("resolve_a", new JSONObject().put("status", "ok").put("toolOutput", toolOut));
        JSONObject out = PlaybookDeriveOpsV1b.execute("normalize_resolved_thing",
                new JSONObject().put("sourceNodeId", "resolve_a").put("label", "widget"), ctx);
        assertEquals("needs_clarification", out.getString("status"));
        assertTrue(out.getString("message").contains("Please choose"), out.getString("message"));
        assertEquals(2, out.getJSONArray("candidates").length());
    }

    @Test
    void pickTaxonomyRow_whenAssetTypeMissingEmpty_taxonomySeeded() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r0", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("assetType", new JSONObject().put("$input", "assetType"))
                .put("taxonomyRows", new JSONObject().put("$var", "assetTaxonomy.rows"))
                .put("whenAssetTypeMissing", "empty_taxonomy");
        JSONObject out = PlaybookDeriveOps.execute("pick_taxonomy_row", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals("", out.getJSONObject("output").getString("AssetType"));
    }

    @Test
    void normalizeResolvedThing_inlineUnique_mapsName() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r5", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject toolOut = new JSONObject()
                .put("status", "success")
                .put("resultKind", "THING_RESOLVED_INLINE")
                .put("matches", new JSONArray().put(new JSONObject()
                        .put("name", "Thing.A")
                        .put("matchedBy", new JSONObject().put("field", "displayName"))));
        ctx.putNodeOutput("resolve_a", new JSONObject().put("status", "ok").put("toolOutput", toolOut));
        JSONObject out = PlaybookDeriveOpsV1b.execute("normalize_resolved_thing",
                new JSONObject().put("sourceNodeId", "resolve_a").put("label", "asset A"), ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals("Thing.A", out.getJSONObject("output").getString("name"));
    }

    @Test
    void normalizeResolvedThing_largeResult_clarifies() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r6", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject toolOut = new JSONObject()
                .put("status", "success")
                .put("resultKind", "THING_RESOLVED_LARGE")
                .put("totalCount", 99)
                .put("sampleMatches", new JSONArray().put(new JSONObject().put("name", "T1")));
        ctx.putNodeOutput("resolve_a", new JSONObject().put("status", "ok").put("toolOutput", toolOut));
        JSONObject out = PlaybookDeriveOpsV1b.execute("normalize_resolved_thing",
                new JSONObject().put("sourceNodeId", "resolve_a"), ctx);
        assertEquals("needs_clarification", out.getString("status"));
        assertTrue(out.getString("message").contains("large result"));
    }

    @Test
    void normalizeResolvedThing_identityAmbiguous_mapsToClarify() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r7", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject toolOut = new JSONObject()
                .put("status", "error")
                .put("code", "IDENTITY_AMBIGUOUS")
                .put("candidates", new JSONArray()
                        .put(new JSONObject().put("name", "A1"))
                        .put(new JSONObject().put("name", "A2")));
        ctx.putNodeOutput("resolve_b", new JSONObject().put("status", "ok").put("toolOutput", toolOut));
        JSONObject out = PlaybookDeriveOpsV1b.execute("normalize_resolved_thing",
                new JSONObject().put("sourceNodeId", "resolve_b"), ctx);
        assertEquals("needs_clarification", out.getString("status"));
        assertEquals(2, out.getJSONArray("candidates").length());
    }

    @Test
    void matchIdentifierInRows_exactOnEquipmentId() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("m1", "machine_utilization_summary",
                new JSONObject().put("machine", "EQ-100"));
        JSONObject listingOut = listingRowsTwoMachines();
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", listingOut));
        JSONObject args = new JSONObject()
                .put("rows", new JSONObject().put("$ref", "listing.toolOutput.rows"))
                .put("identifier", new JSONObject().put("$input", "machine"))
                .put("label", "machine");
        JSONObject out = PlaybookDeriveOpsV1b.execute("match_identifier_in_rows", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals("SE.CellFab.Model.Workunit.M-ONE", out.getJSONObject("output").getString("name"));
        assertEquals("EquipmentID", out.getJSONObject("output").getJSONObject("matchedBy").getString("field"));
        assertEquals("EXACT", out.getJSONObject("output").getJSONObject("matchedBy").getString("tier"));
    }

    @Test
    void matchIdentifierInRows_ambiguousEquipmentDesc() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("m2", "machine_utilization_summary",
                new JSONObject().put("machine", "Shared Display Label"));
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", listingRowsAmbiguous()));
        JSONObject args = new JSONObject()
                .put("rows", new JSONObject().put("$ref", "listing.toolOutput.rows"))
                .put("identifier", new JSONObject().put("$input", "machine"))
                .put("label", "machine");
        JSONObject out = PlaybookDeriveOpsV1b.execute("match_identifier_in_rows", args, ctx);
        assertEquals("needs_clarification", out.getString("status"));
        assertTrue(out.getString("message").contains("Multiple rows"));
    }

    @Test
    void matchIdentifierInRows_shortSuffix_matchesCanonicalName() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("m1s", "machine_utilization_summary",
                new JSONObject().put("machine", "M-ONE"));
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", listingRowsTwoMachines()));
        JSONObject args = new JSONObject()
                .put("rows", new JSONObject().put("$ref", "listing.toolOutput.rows"))
                .put("identifier", new JSONObject().put("$input", "machine"))
                .put("label", "machine");
        JSONObject out = PlaybookDeriveOpsV1b.execute("match_identifier_in_rows", args, ctx);
        assertEquals("ok", out.getString("status"), out.toString());
        assertEquals("SE.CellFab.Model.Workunit.M-ONE", out.getJSONObject("output").getString("name"));
        assertEquals("SUFFIX", out.getJSONObject("output").getJSONObject("matchedBy").getString("tier"));
    }

    @Test
    void matchIdentifierInRows_longerContaminatedIdentifier_doesNotSuffixMatch() throws Exception {
        // Regression: the inverse suffix direction must be rejected. A longer/contaminated
        // identifier that merely ends with the canonical Thing name must NOT silently resolve to it.
        PlaybookRunContext ctx = new PlaybookRunContext("m1n", "machine_utilization_summary",
                new JSONObject().put("machine", "junk SE.CellFab.Model.Workunit.M-ONE"));
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", listingRowsTwoMachines()));
        JSONObject args = new JSONObject()
                .put("rows", new JSONObject().put("$ref", "listing.toolOutput.rows"))
                .put("identifier", new JSONObject().put("$input", "machine"))
                .put("label", "machine");
        JSONObject out = PlaybookDeriveOpsV1b.execute("match_identifier_in_rows", args, ctx);
        assertEquals("needs_clarification", out.getString("status"), out.toString());
    }

    @Test
    void matchIdentifierInRows_rowWithoutCanonicalName_clarifies() throws Exception {
        // Regression: a row matched on EquipmentDesc/EquipmentID but lacking a canonical
        // Thing-name column must clarify, not emit EquipmentID as the downstream Machine name.
        PlaybookRunContext ctx = new PlaybookRunContext("m1c", "machine_utilization_summary",
                new JSONObject().put("machine", "Lonely Display"));
        JSONObject listing = new JSONObject()
                .put("status", "success")
                .put("resultKind", "INFOTABLE")
                .put("rows", new JSONArray().put(new JSONObject()
                        .put("EquipmentID", "EQ-9")
                        .put("EquipmentDesc", "Lonely Display")));
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", listing));
        JSONObject args = new JSONObject()
                .put("rows", new JSONObject().put("$ref", "listing.toolOutput.rows"))
                .put("identifier", new JSONObject().put("$input", "machine"))
                .put("label", "machine");
        JSONObject out = PlaybookDeriveOpsV1b.execute("match_identifier_in_rows", args, ctx);
        assertEquals("needs_clarification", out.getString("status"), out.toString());
        assertTrue(out.getString("message").contains("canonical Thing name"), out.getString("message"));
    }

    @Test
    void matchIdentifierInRows_currentJsonEnvelope_matchesDescriptionAndReturnsMachineName() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("m1j", "machine_utilization_summary",
                new JSONObject().put("machine", "ORD Jet Dryer Display"));
        JSONObject result = new JSONObject().put("rows", new JSONArray().put(new JSONObject()
                .put("machineName", "SE.CellFab.Model.Workunit.M-ONE")
                .put("displayName", "ORD Jet Dryer")
                .put("description", "ORD Jet Dryer Display")));
        JSONObject listing = new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", result.toString());
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", listing));
        JSONObject args = new JSONObject()
                .put("rows", new JSONObject().put("$ref", "listing.toolOutput.result.rows"))
                .put("identifier", new JSONObject().put("$input", "machine"))
                .put("label", "machine")
                .put("fields", new JSONArray().put("machineName").put("displayName").put("description"));

        JSONObject out = PlaybookDeriveOpsV1b.execute("match_identifier_in_rows", args, ctx);

        assertEquals("ok", out.getString("status"), out.toString());
        assertEquals("SE.CellFab.Model.Workunit.M-ONE", out.getJSONObject("output").getString("name"));
        assertEquals("description", out.getJSONObject("output").getJSONObject("matchedBy").getString("field"));
    }

    @Test
    void matchIdentifierInRows_shortSuffixMatchesMachineName() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("m1k", "machine_utilization_summary",
                new JSONObject().put("machine", "M-ONE"));
        JSONObject result = new JSONObject().put("rows", new JSONArray().put(new JSONObject()
                .put("machineName", "SE.CellFab.Model.Workunit.M-ONE")
                .put("displayName", "ORD Jet Dryer")
                .put("description", "ORD Jet Dryer Display")));
        JSONObject listing = new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", result.toString());
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", listing));
        JSONObject args = new JSONObject()
                .put("rows", new JSONObject().put("$ref", "listing.toolOutput.result.rows"))
                .put("identifier", new JSONObject().put("$input", "machine"))
                .put("label", "machine")
                .put("fields", new JSONArray().put("machineName").put("displayName").put("description"));

        JSONObject out = PlaybookDeriveOpsV1b.execute("match_identifier_in_rows", args, ctx);

        assertEquals("ok", out.getString("status"), out.toString());
        assertEquals("SE.CellFab.Model.Workunit.M-ONE", out.getJSONObject("output").getString("name"));
        assertEquals("SUFFIX", out.getJSONObject("output").getJSONObject("matchedBy").getString("tier"));
    }

    @Test
    void matchIdentifierInRows_currentHintsWithoutMachineName_clarifies() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("m1l", "machine_utilization_summary",
                new JSONObject().put("machine", "Lonely Display"));
        JSONObject result = new JSONObject().put("rows", new JSONArray().put(new JSONObject()
                .put("displayName", "Lonely Display")
                .put("description", "No canonical machine name")));
        JSONObject listing = new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", result.toString());
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", listing));
        JSONObject args = new JSONObject()
                .put("rows", new JSONObject().put("$ref", "listing.toolOutput.result.rows"))
                .put("identifier", new JSONObject().put("$input", "machine"))
                .put("label", "machine")
                .put("fields", new JSONArray().put("machineName").put("displayName").put("description"));

        JSONObject out = PlaybookDeriveOpsV1b.execute("match_identifier_in_rows", args, ctx);

        assertEquals("needs_clarification", out.getString("status"), out.toString());
        assertTrue(out.getString("message").contains("canonical Thing name"), out.getString("message"));
    }

    @Test
    void pickBranchOutput_selectsThenBranch() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("m3", "machine_utilization_summary", new JSONObject());
        ctx.putNodeOutput("gate", new JSONObject().put("status", "ok").put("branch", "then"));
        ctx.putNodeOutput("norm", new JSONObject().put("status", "ok").put("output", new JSONObject().put("name", "N1")));
        ctx.putNodeOutput("match", new JSONObject().put("status", "ok").put("output", new JSONObject().put("name", "M1")));
        JSONObject out = PlaybookDeriveOpsV1b.execute("pick_branch_output", new JSONObject()
                .put("conditionNodeId", "gate")
                .put("thenNodeId", "norm")
                .put("elseNodeId", "match"), ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals("N1", out.getJSONObject("output").getString("name"));
    }

    @Test
    void pickBranchOutput_selectsElseBranch() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("m4", "machine_utilization_summary", new JSONObject());
        ctx.putNodeOutput("gate", new JSONObject().put("status", "ok").put("branch", "else"));
        ctx.putNodeOutput("norm", new JSONObject().put("status", "ok").put("output", new JSONObject().put("name", "N1")));
        ctx.putNodeOutput("match", new JSONObject().put("status", "ok").put("output", new JSONObject().put("name", "M1")));
        JSONObject out = PlaybookDeriveOpsV1b.execute("pick_branch_output", new JSONObject()
                .put("conditionNodeId", "gate")
                .put("thenNodeId", "norm")
                .put("elseNodeId", "match"), ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals("M1", out.getJSONObject("output").getString("name"));
    }

    private static JSONObject listingRowsTwoMachines() {
        return new JSONObject()
                .put("status", "success")
                .put("resultKind", "INFOTABLE")
                .put("rows", new JSONArray()
                        .put(new JSONObject()
                                .put("name", "SE.CellFab.Model.Workunit.M-ONE")
                                .put("EquipmentID", "EQ-100")
                                .put("EquipmentDesc", "ORD Jet Dryer Display"))
                        .put(new JSONObject()
                                .put("name", "SE.CellFab.Model.Workunit.M-TWO")
                                .put("EquipmentID", "EQ-200")
                                .put("EquipmentDesc", "Other Cell Line")));
    }

    private static JSONObject listingRowsAmbiguous() {
        return new JSONObject()
                .put("status", "success")
                .put("resultKind", "INFOTABLE")
                .put("rows", new JSONArray()
                        .put(new JSONObject()
                                .put("name", "SE.CellFab.Model.Workunit.M-A")
                                .put("EquipmentID", "X1")
                                .put("EquipmentDesc", "Shared Display Label"))
                        .put(new JSONObject()
                                .put("name", "SE.CellFab.Model.Workunit.M-B")
                                .put("EquipmentID", "X2")
                                .put("EquipmentDesc", "Shared Display Label")));
    }

    @Test
    void matchEntityIdentifiers_normalizedSuffixMatch() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r1", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("name", "Plant.ORD-JetDryer-02").put("PTCDisplayName", "ORD JetDryer 02"));
        JSONObject args = new JSONObject()
                .put("identifier", "ORD JetDryer 02")
                .put("label", "asset A")
                .put("candidates", rows);
        JSONObject out = PlaybookDeriveOpsV1b.execute("match_entity_identifiers", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals("Plant.ORD-JetDryer-02", out.getJSONObject("output").getString("name"));
    }

    @Test
    void normalizeIdentifier_stripsPunctuation() {
        assertEquals("ordjetdryer02", PlaybookDeriveOpsV1b.normalizeIdentifier("ORD JetDryer 02"));
    }

    @Test
    void conditionEvaluator_isEmpty_trueForEmptyValue() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r2", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject pred = new JSONObject().put("op", "is_empty").put("value", new JSONArray());
        assertTrue(PlaybookConditionEvaluator.evaluate(pred, ctx));
    }

    @Test
    void conditionEvaluator_isEmpty_falseForNonEmptyValue() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r3", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject pred = new JSONObject().put("op", "is_empty").put("value", new JSONArray().put("x"));
        assertFalse(PlaybookConditionEvaluator.evaluate(pred, ctx));
    }

    @Test
    void trendSummary_compactsFanOutChildren() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r4", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONArray children = new JSONArray()
                .put(new JSONObject()
                        .put("status", "ok")
                        .put("item", new JSONObject()
                                .put("thingName", "ThingA")
                                .put("propertyName", "temperature_Wrst1")
                                .put("slot", "A"))
                        .put("toolOutput", new JSONObject()
                                .put("pointsReturned", 42)
                                .put("rowCount", 42)
                                .put("aggregates", new JSONObject()
                                        .put("FIRST", 70)
                                        .put("LAST", 88)
                                        .put("MIN", 65)
                                        .put("MAX", 93)
                                        .put("MEAN", 78))));
        ctx.putNodeOutput("trends_by_asset", new JSONObject().put("status", "ok").put("children", children));
        JSONObject out = PlaybookDeriveOpsV1b.execute("trend_summary",
                new JSONObject().put("trendsFanOutNodeId", "trends_by_asset"), ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject asset = out.getJSONObject("output").getJSONArray("assets").getJSONObject(0);
        assertEquals(70.0, asset.getDouble("first"), 0.001);
        assertEquals("rising", asset.getString("direction"));
        String evidenceLine = out.getJSONArray("evidenceLines").optString(0, "");
        assertTrue(evidenceLine.contains("first=70"), evidenceLine);
        assertTrue(evidenceLine.contains("last=88"), evidenceLine);
        assertTrue(evidenceLine.contains("direction=rising"), evidenceLine);
    }
}
