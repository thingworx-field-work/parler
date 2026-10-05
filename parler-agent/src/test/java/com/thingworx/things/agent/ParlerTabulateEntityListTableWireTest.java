package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerTabulateEntityListTableWireTest {

    @Test
    void tabulateInline_mapsToEntityListTable() {
        JSONObject envCol = new JSONObject();
        envCol.put("name", "a");
        envCol.put("baseType", "STRING");
        JSONArray envCols = new JSONArray();
        envCols.put(envCol);
        JSONObject env = new JSONObject();
        env.put("schemaVersion", "1");
        env.put("sourceCacheId", "src-1");
        env.put("rowEstimate", 1);
        env.put("columns", envCols);
        JSONObject row = new JSONObject();
        row.put("a", "x");
        JSONArray rows = new JSONArray();
        rows.put(row);
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("sourceCacheId", "src-1");
        root.put("resultKind", "CACHED_TABULATE_INLINE");
        root.put("totalRows", 1);
        root.put("rows", rows);
        root.put("insightEnvelope", env);

        JSONObject t = ParlerTabulateEntityListTableWire.tableBlockFromTabulateToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("entity-list", t.getString("kind"));
        assertEquals(1, t.getJSONArray("columns").length());
        assertEquals("a", t.getJSONArray("columns").getJSONObject(0).getString("key"));
        assertEquals("STRING", t.getJSONArray("columns").getJSONObject(0).getString("baseType"));
        assertEquals(1, t.getJSONArray("rows").length());
        assertEquals("x", t.getJSONArray("rows").getJSONObject(0).getString("a"));
        assertEquals(1, t.getInt("shownRows"));
        assertEquals(1, t.getInt("totalRows"));
        assertEquals("src-1", t.getString("sourceCacheId"));
        assertEquals("none", t.getString("exportStatus"));
        assertTrue(t.isNull("cacheId"));
    }

    @Test
    void tabulateLarge_setsCacheIdAndUsesSampleRows() {
        JSONObject env = new JSONObject();
        env.put("schemaVersion", "1");
        env.put("sourceCacheId", "src-9");
        env.put("rowEstimate", 100);
        JSONArray envCols = new JSONArray();
        JSONObject c0 = new JSONObject();
        c0.put("name", "n");
        c0.put("baseType", "NUMBER");
        envCols.put(c0);
        env.put("columns", envCols);
        JSONArray sample = new JSONArray();
        sample.put(new JSONObject().put("n", 1));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("sourceCacheId", "src-9");
        root.put("resultKind", "CACHED_TABULATE_LARGE");
        root.put("totalRows", 100);
        root.put("cacheId", "new-cache-uuid");
        root.put("sampleRows", sample);
        root.put("insightEnvelope", env);

        JSONObject t = ParlerTabulateEntityListTableWire.tableBlockFromTabulateToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("new-cache-uuid", t.getString("cacheId"));
        assertEquals(1, t.getJSONArray("rows").length());
        assertEquals(100, t.getInt("totalRows"));
    }

    @Test
    void filterRowsInline_mapsToEntityListTable() {
        JSONObject envCol = new JSONObject();
        envCol.put("name", "a");
        envCol.put("baseType", "NUMBER");
        JSONArray envCols = new JSONArray();
        envCols.put(envCol);
        JSONObject env = new JSONObject();
        env.put("schemaVersion", "1");
        env.put("sourceCacheId", "src-f");
        env.put("rowEstimate", 1);
        env.put("columns", envCols);
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("a", 3));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("sourceCacheId", "src-f");
        root.put("resultKind", "CACHED_FILTER_ROWS_INLINE");
        root.put("rowCount", 10);
        root.put("matchCount", 5);
        root.put("totalRows", 1);
        root.put("rows", rows);
        root.put("insightEnvelope", env);

        JSONObject t = ParlerTabulateEntityListTableWire.tableBlockFromTabulateToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals(1, t.getInt("totalRows"));
        assertTrue(t.isNull("cacheId"));
    }

    @Test
    void groupMetricInline_preservesCacheIdAndPresentationTitle() {
        JSONObject envCol = new JSONObject();
        envCol.put("name", "UtilizationState");
        envCol.put("baseType", "STRING");
        JSONArray envCols = new JSONArray();
        envCols.put(envCol);
        envCol = new JSONObject();
        envCol.put("name", "TotalDuration");
        envCol.put("baseType", "NUMBER");
        envCols.put(envCol);
        JSONObject env = new JSONObject();
        env.put("schemaVersion", "1");
        env.put("sourceCacheId", "src-gm");
        env.put("rowEstimate", 1);
        env.put("columns", envCols);
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("UtilizationState", "Down").put("TotalDuration", 100));
        JSONArray groupBy = new JSONArray();
        groupBy.put("UtilizationState");
        JSONArray measures = new JSONArray();
        measures.put("TotalDuration");
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("sourceCacheId", "src-gm");
        root.put("resultKind", "CACHED_GROUP_METRIC_INLINE");
        root.put("cacheId", "agg-cache-1");
        root.put("groupBy", groupBy);
        root.put("measures", measures);
        root.put("totalRows", 1);
        root.put("rows", rows);
        root.put("insightEnvelope", env);

        JSONObject t = ParlerTabulateEntityListTableWire.tableBlockFromTabulateToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("agg-cache-1", t.getString("cacheId"));
        assertTrue(t.getString("presentationTitle").contains("tabulate_cached_result"));
        assertTrue(t.getString("presentationTitle").contains("UtilizationState"));
    }

    @Test
    void groupMetricInline_mapsToEntityListTable() {
        JSONObject envCol = new JSONObject();
        envCol.put("name", "total_cnt");
        envCol.put("baseType", "NUMBER");
        JSONArray envCols = new JSONArray();
        envCols.put(envCol);
        JSONObject env = new JSONObject();
        env.put("schemaVersion", "1");
        env.put("sourceCacheId", "src-gm");
        env.put("rowEstimate", 1);
        env.put("columns", envCols);
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("total_cnt", 42));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("sourceCacheId", "src-gm");
        root.put("resultKind", "CACHED_GROUP_METRIC_INLINE");
        root.put("rowCount", 100);
        root.put("groupCount", 1);
        root.put("matchCount", 1);
        root.put("totalRows", 1);
        root.put("rows", rows);
        root.put("insightEnvelope", env);

        JSONObject t = ParlerTabulateEntityListTableWire.tableBlockFromTabulateToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("entity-list", t.getString("kind"));
        assertEquals(1, t.getJSONArray("columns").length());
        assertEquals("total_cnt", t.getJSONArray("columns").getJSONObject(0).getString("key"));
        assertEquals(42, t.getJSONArray("rows").getJSONObject(0).getInt("total_cnt"));
        assertEquals(1, t.getInt("totalRows"));
        assertEquals("src-gm", t.getString("sourceCacheId"));
        assertTrue(t.isNull("cacheId"));
        assertEquals("tabulate_cached_result", t.getString("presentationTitle"));
    }

    @Test
    void summarizeSuccess_returnsNull() {
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("sourceCacheId", "x");
        root.put("resultKind", "CACHED_SUMMARY_INLINE");
        assertNull(ParlerTabulateEntityListTableWire.tableBlockFromTabulateToolSuccessJson(root.toString()));
    }

    @Test
    void errorStatus_returnsNull() {
        JSONObject root = new JSONObject();
        root.put("status", "error");
        root.put("resultKind", "CACHED_TABULATE_INLINE");
        assertNull(ParlerTabulateEntityListTableWire.tableBlockFromTabulateToolSuccessJson(root.toString()));
    }
}
