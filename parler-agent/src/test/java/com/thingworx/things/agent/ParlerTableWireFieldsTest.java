package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerTableWireFieldsTest {

    @Test
    void putCacheIdFromRoot_copiesWhenPresent() {
        JSONObject root = new JSONObject().put("cacheId", "abc");
        JSONObject table = new JSONObject();
        ParlerTableWireFields.putCacheIdFromRoot(table, root);
        assertEquals("abc", table.getString("cacheId"));
    }

    @Test
    void putCacheIdFromRoot_nullWhenMissing() {
        JSONObject table = new JSONObject();
        ParlerTableWireFields.putCacheIdFromRoot(table, new JSONObject());
        assertTrue(table.isNull("cacheId"));
    }

    @Test
    void putPresentationTitle_truncatesLongTitles() {
        JSONObject table = new JSONObject();
        String longTitle = "x".repeat(100);
        ParlerTableWireFields.putPresentationTitle(table, longTitle);
        assertEquals(80, table.getString("presentationTitle").length());
        assertTrue(table.getString("presentationTitle").endsWith("…"));
    }

    @Test
    void putPresentationTitle_omitsBlank() {
        JSONObject table = new JSONObject();
        ParlerTableWireFields.putPresentationTitle(table, "   ");
        assertFalse(table.has("presentationTitle"));
    }
}
