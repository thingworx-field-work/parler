package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerListEntitiesEntityListTableWireTest {

    @Test
    void listEntitiesInline_mapsToEntityListTable() {
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("name", "MyThing"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("entityCollectionType", "ThingTemplate");
        root.put("resultKind", "ENTITY_LIST_INLINE");
        root.put("returnedRows", 1);
        root.put("rows", rows);

        JSONObject t = ParlerListEntitiesEntityListTableWire.tableBlockFromListEntitiesToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("entity-list", t.getString("kind"));
        assertEquals(1, t.getJSONArray("rows").length());
        assertEquals(1, t.getInt("totalRows"));
    }

    @Test
    void listEntitiesInline_preservesCacheIdAndPresentationTitle() {
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("name", "MyThing"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("entityCollectionType", "ThingTemplate");
        root.put("resultKind", "ENTITY_LIST_INLINE");
        root.put("returnedRows", 1);
        root.put("cacheId", "list-inline-cid");
        root.put("rows", rows);

        JSONObject t = ParlerListEntitiesEntityListTableWire.tableBlockFromListEntitiesToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("list-inline-cid", t.getString("cacheId"));
        assertEquals("list_entities_by_type: entityCollectionType=ThingTemplate", t.getString("presentationTitle"));
    }

    @Test
    void listEntitiesLarge_setsCacheId() {
        JSONArray sample = new JSONArray();
        sample.put(new JSONObject().put("name", "A"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("entityCollectionType", "Mashup");
        root.put("resultKind", "ENTITY_LIST_LARGE");
        root.put("returnedRows", 50);
        root.put("cacheId", "cache-1");
        root.put("sampleRows", sample);

        JSONObject t = ParlerListEntitiesEntityListTableWire.tableBlockFromListEntitiesToolSuccessJson(root.toString());
        assertNotNull(t);
        assertEquals("cache-1", t.getString("cacheId"));
        assertEquals(50, t.getInt("totalRows"));
    }

    @Test
    void taxonomyBody_returnsNull() {
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultShape", "ImplementedThingsWithTotalCount");
        root.put("resultKind", "ENTITY_TAXONOMY_QUERY_INLINE");
        root.put("rootEntityList", new JSONArray().put(new JSONObject().put("x", 1)));
        assertNull(ParlerListEntitiesEntityListTableWire.tableBlockFromListEntitiesToolSuccessJson(root.toString()));
    }
}
