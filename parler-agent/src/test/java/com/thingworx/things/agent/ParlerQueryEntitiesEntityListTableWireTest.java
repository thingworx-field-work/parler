package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerQueryEntitiesEntityListTableWireTest {

    @Test
    void queryEntitiesInline_mapsToEntityListTable() {
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("name", "T1"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "ENTITY_QUERY_INLINE");
        root.put("parentKind", "ThingTemplate");
        root.put("parentName", "PTC");
        root.put("returnedRows", 1);
        root.put("totalRows", 10);
        root.put("rows", rows);

        JSONObject t = ParlerQueryEntitiesEntityListTableWire.tableBlockFromQueryEntitiesToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("entity-list", t.getString("kind"));
        assertEquals(10, t.getInt("totalRows"));
        assertEquals(1, t.getJSONArray("rows").length());
    }

    @Test
    void queryEntitiesInline_preservesCacheIdAndPresentationTitle() {
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("name", "T1"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "ENTITY_QUERY_INLINE");
        root.put("parentKind", "ThingTemplate");
        root.put("parentName", "PTC");
        root.put("returnedRows", 1);
        root.put("cacheId", "query-inline-cid");
        root.put("rows", rows);

        JSONObject t = ParlerQueryEntitiesEntityListTableWire.tableBlockFromQueryEntitiesToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("query-inline-cid", t.getString("cacheId"));
        assertEquals("query_entities: parent=ThingTemplate:PTC", t.getString("presentationTitle"));
    }

    @Test
    void queryEntitiesLarge_setsCacheId() {
        JSONArray sample = new JSONArray();
        sample.put(new JSONObject().put("name", "A"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "ENTITY_QUERY_LARGE");
        root.put("parentKind", "ThingShape");
        root.put("parentName", "Shape1");
        root.put("returnedRows", 30);
        root.put("cacheId", "c-q");
        root.put("sampleRows", sample);

        JSONObject t = ParlerQueryEntitiesEntityListTableWire.tableBlockFromQueryEntitiesToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("c-q", t.getString("cacheId"));
    }

    @Test
    void listEntitiesBody_returnsNull() {
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("entityCollectionType", "Mashup");
        root.put("resultKind", "ENTITY_LIST_INLINE");
        root.put("returnedRows", 1);
        root.put("rows", new JSONArray().put(new JSONObject().put("name", "x")));
        assertNull(ParlerQueryEntitiesEntityListTableWire.tableBlockFromQueryEntitiesToolSuccessJson(root.toString()));
    }
}
