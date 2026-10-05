package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Regression: alert built-ins reuse the same INFOTABLE / INFOTABLE_LARGE envelope as {@code invoke_service}, so
 * {@link ParlerToolTableWireUtil} must reconstruct {@code TableBlock}s for history replay / export parity (§13 S5).
 */
class ParlerToolTableWireUtilAlertWireTest {

    @Test
    void queryAlertHistoryLargeSampleMapsToTableBlock() {
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE_LARGE");
        root.put("totalRows", 100);
        root.put("cacheId", "cache-alert-1");
        root.put("hint", "fetch more");
        JSONArray cols = new JSONArray();
        JSONObject c = new JSONObject();
        c.put("name", "timestamp");
        c.put("baseType", "DATETIME");
        cols.put(c);
        root.put("columns", cols);
        JSONArray sample = new JSONArray();
        JSONObject row = new JSONObject();
        row.put("timestamp", "2026-04-01T00:00:00Z");
        sample.put(row);
        root.put("sampleRows", sample);
        root.put("thingName", "DemoThing");
        root.put("historyQueryResource", "AlertFunctions.QueryAlertHistory");

        JSONObject table = ParlerToolTableWireUtil.tableBlockFromListClassToolJson(root.toString(), "query_alert_history");
        assertNotNull(table);
        assertEquals("cache-alert-1", table.getString("cacheId"));
        assertEquals(100, table.getInt("totalRows"));
        assertEquals("entity-list", table.getString("kind"));
        assertEquals("query_alert_history: thingName=DemoThing", table.getString("presentationTitle"));
    }
}
