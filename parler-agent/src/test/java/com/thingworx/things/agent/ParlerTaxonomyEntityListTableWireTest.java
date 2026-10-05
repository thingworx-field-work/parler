package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerTaxonomyEntityListTableWireTest {

    @Test
    void taxonomyInline_mapsToEntityListTable() {
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("name", "T1").put("n", 3));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultShape", "ImplementedThingsWithTotalCount");
        root.put("resultKind", "ENTITY_TAXONOMY_QUERY_INLINE");
        root.put("totalCount", 1);
        root.put("rootEntityList", rows);

        JSONObject t = ParlerTaxonomyEntityListTableWire.tableBlockFromTaxonomyToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("entity-list", t.getString("kind"));
        assertEquals(2, t.getJSONArray("columns").length());
        assertEquals(1, t.getJSONArray("rows").length());
        assertEquals(1, t.getInt("totalRows"));
        assertEquals("none", t.getString("exportStatus"));
    }

    @Test
    void taxonomyLarge_setsCacheId() {
        JSONArray sample = new JSONArray();
        sample.put(new JSONObject().put("a", "x"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultShape", "ImplementedThingsWithTotalCount");
        root.put("resultKind", "ENTITY_TAXONOMY_QUERY_LARGE");
        root.put("totalCount", 99);
        root.put("cacheId", "cid-large");
        root.put("sampleRootEntityList", sample);

        JSONObject t = ParlerTaxonomyEntityListTableWire.tableBlockFromTaxonomyToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("cid-large", t.getString("cacheId"));
        assertEquals(99, t.getInt("totalRows"));
    }

    @Test
    void tabulateBody_returnsNull() {
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "CACHED_TABULATE_INLINE");
        assertNull(ParlerTaxonomyEntityListTableWire.tableBlockFromTaxonomyToolSuccessJson(root.toString()));
    }
}
