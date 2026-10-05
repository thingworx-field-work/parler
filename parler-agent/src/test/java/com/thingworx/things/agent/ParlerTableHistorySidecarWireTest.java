package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Offline regression: {@code _parlerTableExport} merge into {@code TableBlock} export fields — same merge step as
 * {@link AgentMessageStreamHistoryExporter} on tool JSON, without {@code QueryStreamData} or a platform Stream.
 */
class ParlerTableHistorySidecarWireTest {

    @Test
    void listEntitiesInline_mergeSidecar_restoresExportFieldsOnTableBlock() {
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("name", "MyThing"));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("entityCollectionType", "ThingTemplate");
        root.put("resultKind", "ENTITY_LIST_INLINE");
        root.put("returnedRows", 1);
        root.put("rows", rows);

        JSONObject sidecar = new JSONObject();
        sidecar.put("exportStatus", "ok");
        sidecar.put("exportMessage", JSONObject.NULL);
        sidecar.put("exportRepository", "ParlerExportRepo");
        sidecar.put("exportFile", "/demo/20260101/out.csv");
        sidecar.put("exportDownloadUrl", JSONObject.NULL);
        root.put(ParlerTableExportSidecar.JSON_KEY, sidecar);

        String body = root.toString();
        JSONObject table = ParlerToolTableWireUtil.tableBlockFromListClassToolJson(body);
        assertNotNull(table);
        assertEquals("entity-list", table.getString("kind"));
        assertEquals("none", table.getString("exportStatus"));

        ParlerTableExportSidecar.mergeToolRootSidecarIntoTable(body, table);

        assertEquals("ok", table.getString("exportStatus"));
        assertEquals("ParlerExportRepo", table.getString("exportRepository"));
        assertEquals("/demo/20260101/out.csv", table.getString("exportFile"));
        assertTrue(table.isNull("exportMessage"));
    }
}
