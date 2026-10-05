package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** {@link ParlerTableWire#toWireJson} — same envelope as {@link ParlerReceiveMessageSupport#wireTable}; {@code CONTRACTS/TABLE_CONTRACT.md}. */
class ParlerTableWireTest {

    @Test
    void wireTable_includesTypeConversationRequestAndTablePayload() {
        JSONObject col = new JSONObject();
        col.put("key", "name");
        col.put("label", "name");
        col.put("baseType", "STRING");
        JSONArray columns = new JSONArray();
        columns.put(col);
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("name", "Thing-1"));
        JSONObject table = new JSONObject();
        table.put("kind", "entity-list");
        table.put("columns", columns);
        table.put("rows", rows);
        table.put("shownRows", 1);
        table.put("totalRows", 1);
        table.put("exportStatus", "none");

        String wire = ParlerTableWire.toWireJson("rid-1", "cid-1", table);
        JSONObject o = new JSONObject(wire);
        assertEquals("table", o.getString("type"));
        assertEquals("rid-1", o.getString("request_id"));
        assertEquals("cid-1", o.getString("conversation_id"));
        JSONObject t = o.getJSONObject("table");
        assertEquals("entity-list", t.getString("kind"));
        assertEquals(1, t.getJSONArray("columns").length());
        assertEquals(1, t.getJSONArray("rows").length());
        assertEquals("none", t.getString("exportStatus"));
        assertNotNull(t);
    }
}
