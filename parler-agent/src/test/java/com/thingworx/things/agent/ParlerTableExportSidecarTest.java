package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerTableExportSidecarTest {

    @Test
    void mergeToolRootSidecarIntoTable_copiesExportFields() {
        JSONObject sidecar = new JSONObject();
        sidecar.put("exportStatus", "ok");
        sidecar.put("exportMessage", JSONObject.NULL);
        sidecar.put("exportRepository", "Repo1");
        sidecar.put("exportFile", "/a/b.csv");
        sidecar.put("exportDownloadUrl", JSONObject.NULL);

        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put(ParlerTableExportSidecar.JSON_KEY, sidecar);

        JSONObject table = new JSONObject();
        table.put("exportStatus", "none");
        table.put("kind", "entity-list");

        ParlerTableExportSidecar.mergeToolRootSidecarIntoTable(root.toString(), table);

        assertEquals("ok", table.getString("exportStatus"));
        assertEquals("Repo1", table.getString("exportRepository"));
        assertEquals("/a/b.csv", table.getString("exportFile"));
        assertTrue(table.isNull("exportMessage"));
    }

    @Test
    void sidecarFromTable_roundTripsThroughMerge() {
        JSONObject table = new JSONObject();
        table.put("exportStatus", "repo_missing");
        table.put("exportMessage", "no repo");
        table.put("exportFile", JSONObject.NULL);
        table.put("exportRepository", JSONObject.NULL);
        table.put("exportDownloadUrl", JSONObject.NULL);

        JSONObject sc = ParlerTableExportSidecar.sidecarFromTable(table);
        JSONObject root = new JSONObject();
        root.put("x", 1);
        root.put(ParlerTableExportSidecar.JSON_KEY, sc);

        JSONObject rebuilt = new JSONObject();
        rebuilt.put("exportStatus", "none");
        ParlerTableExportSidecar.mergeToolRootSidecarIntoTable(root.toString(), rebuilt);

        assertEquals("repo_missing", rebuilt.getString("exportStatus"));
        assertEquals("no repo", rebuilt.getString("exportMessage"));
    }
}
