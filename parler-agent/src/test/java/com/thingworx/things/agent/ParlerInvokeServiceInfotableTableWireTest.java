package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerInvokeServiceInfotableTableWireTest {

    @Test
    void infotableInline_preservesCacheIdAndPresentationTitle() {
        JSONArray cols = new JSONArray();
        cols.put(new JSONObject().put("name", "a"));
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("a", 1));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE");
        root.put("rowCount", 1);
        root.put("cacheId", "cid-inline");
        root.put("entityName", "ORD-Contacting-01");
        root.put("serviceName", "utilization_records_by_machine");
        root.put("columns", cols);
        root.put("rows", rows);

        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(root.toString());
        assertNotNull(t);
        assertEquals("cid-inline", t.getString("cacheId"));
        assertEquals("invoke_service: ORD-Contacting-01.utilization_records_by_machine", t.getString("presentationTitle"));
    }

    @Test
    void infotableInline_mapsToEntityListTable() {
        JSONArray cols = new JSONArray();
        cols.put(new JSONObject().put("name", "a"));
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("a", 1));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE");
        root.put("rowCount", 1);
        root.put("columns", cols);
        root.put("rows", rows);

        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(root.toString());
        assertNotNull(t);
        assertEquals("entity-list", t.getString("kind"));
        assertEquals(1, t.getInt("totalRows"));
    }

    @Test
    void infotableLarge_omittedCacheId_mapsToJsonNull() {
        JSONArray cols = new JSONArray();
        cols.put(new JSONObject().put("name", "x"));
        JSONArray sample = new JSONArray();
        sample.put(new JSONObject().put("x", "v"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE_LARGE");
        root.put("totalRows", 25);
        root.put("columns", cols);
        root.put("sampleRows", sample);

        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(root.toString());
        assertNotNull(t);
        assertTrue(t.isNull("cacheId"));
        assertEquals(25, t.getInt("totalRows"));
    }

    @Test
    void infotableLarge_setsCacheId() {
        JSONArray cols = new JSONArray();
        cols.put(new JSONObject().put("name", "x"));
        JSONArray sample = new JSONArray();
        sample.put(new JSONObject().put("x", "v"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE_LARGE");
        root.put("totalRows", 25);
        root.put("cacheId", "cid-inf");
        root.put("columns", cols);
        root.put("sampleRows", sample);

        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(root.toString());
        assertNotNull(t);
        assertEquals("cid-inf", t.getString("cacheId"));
        assertEquals(25, t.getInt("totalRows"));
    }

    @Test
    void infotableInline_preservesColumnBaseTypesFromToolJson() {
        JSONArray cols = new JSONArray();
        cols.put(new JSONObject().put("name", "n").put("baseType", "NUMBER"));
        cols.put(new JSONObject().put("name", "s").put("baseType", "STRING"));
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("n", 42).put("s", "x"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE");
        root.put("rowCount", 1);
        root.put("columns", cols);
        root.put("rows", rows);

        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(root.toString());
        assertNotNull(t);
        JSONArray outCols = t.getJSONArray("columns");
        assertEquals("NUMBER", outCols.getJSONObject(0).getString("baseType"));
        assertEquals("STRING", outCols.getJSONObject(1).getString("baseType"));
    }

    @Test
    void fetchCachedBody_returnsNull() {
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("cacheId", "c");
        root.put("rows", new JSONArray().put(new JSONObject().put("a", 1)));
        assertNull(ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(root.toString()));
    }

    /** §10 T1 — carrier path (not shape inference): each built-in name yields a non-bare title. */
    @Test
    void carrier_queryAlertSummary_titleUsesExecutedName() {
        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(
                minimalInfotableWithThing("T-sum", "INFOTABLE"), "query_alert_summary");
        assertNotNull(t);
        assertEquals("query_alert_summary: thingName=T-sum", t.getString("presentationTitle"));
    }

    @Test
    void carrier_queryStreamData_titleUsesExecutedName() {
        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(
                minimalInfotableWithThing("T-stream", "INFOTABLE"), "query_stream_data");
        assertNotNull(t);
        assertEquals("query_stream_data: thingName=T-stream", t.getString("presentationTitle"));
    }

    @Test
    void carrier_queryValueStreamPropertyHistory_titleUsesExecutedName() {
        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(
                minimalInfotableWithThing("T-vs", "INFOTABLE"), "query_property_history");
        assertNotNull(t);
        assertEquals("query_property_history: thingName=T-vs", t.getString("presentationTitle"));
    }

    @Test
    void carrier_queryAlertHistory_titleUsesExecutedName() {
        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(
                minimalInfotableWithThing("T-hist", "INFOTABLE"), "query_alert_history");
        assertNotNull(t);
        assertEquals("query_alert_history: thingName=T-hist", t.getString("presentationTitle"));
    }

    /** §10 T3 — extended tool id through carrier (no built-in allowlist block). */
    @Test
    void carrier_extendedToolName_isPresentationPrefix() {
        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(
                minimalInfotableWithThing("ExtThing", "INFOTABLE"), "scpa_utilization.asset_pair_health");
        assertNotNull(t);
        assertEquals("scpa_utilization.asset_pair_health", t.getString("presentationTitle"));
    }

    /** §10 T7 — invalid top-level {@code tool} must not spoof identity. */
    @Test
    void invalidBodyTool_ignoredFallsThroughToInvokeServiceLiteral() {
        JSONArray cols = new JSONArray();
        cols.put(new JSONObject().put("name", "a"));
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("a", 1));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE");
        root.put("rowCount", 1);
        root.put("tool", "query alert history");
        root.put("columns", cols);
        root.put("rows", rows);
        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(root.toString());
        assertNotNull(t);
        assertEquals("invoke_service", t.getString("presentationTitle"));
    }

    /** §10 T8 — legacy body {@code tool} when row has no executed name (null carrier). */
    @Test
    void legacy_bodyToolField_resolvesPresentationTitle() {
        JSONObject o = new JSONObject(minimalInfotableWithThing("LegacyT", "INFOTABLE"));
        o.put("tool", "query_alert_history");
        JSONObject t = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(o.toString());
        assertNotNull(t);
        assertEquals("query_alert_history: thingName=LegacyT", t.getString("presentationTitle"));
    }

    private static String minimalInfotableWithThing(String thingName, String resultKind) {
        JSONArray cols = new JSONArray();
        cols.put(new JSONObject().put("name", "n").put("baseType", "NUMBER"));
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("n", 1));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", resultKind);
        root.put("rowCount", 1);
        root.put("thingName", thingName);
        root.put("columns", cols);
        root.put("rows", rows);
        return root.toString();
    }
}
