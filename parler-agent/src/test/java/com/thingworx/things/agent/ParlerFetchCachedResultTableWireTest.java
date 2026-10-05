package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerFetchCachedResultTableWireTest {

    @Test
    void fetchCached_mapsPageToEntityListTable() {
        JSONArray cols = new JSONArray();
        cols.put(new JSONObject().put("name", "a").put("baseType", "NUMBER"));
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("a", 1));
        rows.put(new JSONObject().put("a", 2));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("cacheId", "cid-1");
        root.put("offset", 0);
        root.put("returnedRows", 2);
        root.put("totalRows", 100);
        root.put("hasMore", true);
        root.put("columns", cols);
        root.put("rows", rows);

        JSONObject t = ParlerFetchCachedResultTableWire.tableBlockFromFetchCachedResultJson(root.toString());
        assertNotNull(t);
        assertEquals("entity-list", t.getString("kind"));
        assertEquals("cid-1", t.getString("cacheId"));
        assertEquals(100, t.getInt("totalRows"));
        assertEquals(2, t.getInt("shownRows"));
        assertEquals("NUMBER", t.getJSONArray("columns").getJSONObject(0).getString("baseType"));
    }

    @Test
    void fetchCached_withoutColumns_infersFromFirstRow() {
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("x", true));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("cacheId", "c2");
        root.put("offset", 10);
        root.put("returnedRows", 1);
        root.put("totalRows", 50);
        root.put("hasMore", true);
        root.put("rows", rows);

        JSONObject t = ParlerFetchCachedResultTableWire.tableBlockFromFetchCachedResultJson(root.toString());
        assertNotNull(t);
        assertEquals("BOOLEAN", t.getJSONArray("columns").getJSONObject(0).getString("baseType"));
    }

    @Test
    void tabulateBody_returnsNull() {
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("sourceCacheId", "src");
        root.put("resultKind", "CACHED_TABULATE_INLINE");
        root.put("totalRows", 1);
        root.put("rows", new JSONArray().put(new JSONObject().put("a", 1)));
        assertNull(ParlerFetchCachedResultTableWire.tableBlockFromFetchCachedResultJson(root.toString()));
    }

    @Test
    void invokeInfotable_returnsNull() {
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE");
        root.put("rowCount", 1);
        root.put("rows", new JSONArray().put(new JSONObject().put("a", 1)));
        assertNull(ParlerFetchCachedResultTableWire.tableBlockFromFetchCachedResultJson(root.toString()));
    }

    @Test
    void emptyRows_withColumnsMeta_buildsZeroRowTable() {
        JSONArray cols = new JSONArray();
        cols.put(new JSONObject().put("name", "a").put("baseType", "STRING"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("cacheId", "c");
        root.put("offset", 100);
        root.put("returnedRows", 0);
        root.put("totalRows", 8);
        root.put("hasMore", false);
        root.put("columns", cols);
        root.put("rows", new JSONArray());

        JSONObject t = ParlerFetchCachedResultTableWire.tableBlockFromFetchCachedResultJson(root.toString());
        assertNotNull(t);
        assertEquals(0, t.getInt("shownRows"));
        assertEquals(8, t.getInt("totalRows"));
        assertEquals(1, t.getJSONArray("columns").length());
        assertEquals(0, t.getJSONArray("rows").length());
    }

    @Test
    void emptyRows_noColumnSchema_returnsNull() {
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("cacheId", "c");
        root.put("offset", 0);
        root.put("returnedRows", 0);
        root.put("totalRows", 0);
        root.put("hasMore", false);
        root.put("rows", new JSONArray());
        assertNull(ParlerFetchCachedResultTableWire.tableBlockFromFetchCachedResultJson(root.toString()));
    }
}
