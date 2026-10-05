package com.thingworx.things.agent;

import org.json.JSONObject;

/**
 * Persists optional CSV export metadata on TOOL rows in {@link AgentMessageStreamAppender} so
 * {@link AgentMessageStreamHistoryExporter} can rebuild {@code tables[]} with the same {@code export*} fields as live
 * {@code type: "table"} (see {@link ParlerTableFileExportHook}). Not sent as its own wire frame; ignored by LLM-focused
 * parsers that only read tool-native keys.
 */
public final class ParlerTableExportSidecar {

    /** Top-level key on persisted TOOL JSON (AgentMessageStream). */
    public static final String JSON_KEY = "_parlerTableExport";

    private static final String[] EXPORT_KEYS = {
            "exportStatus", "exportMessage", "exportFile", "exportRepository", "exportDownloadUrl"
    };

    private ParlerTableExportSidecar() {}

    /**
     * When {@code body} contains {@value #JSON_KEY}, copies export fields onto {@code table} before
     * {@link ParlerTableFileExportHook#apply} so a second export pass can no-op.
     */
    public static void mergeToolRootSidecarIntoTable(String body, JSONObject table) {
        if (body == null || body.isEmpty() || table == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject(body);
            if (!root.has(JSON_KEY) || root.isNull(JSON_KEY)) {
                return;
            }
            Object raw = root.get(JSON_KEY);
            if (!(raw instanceof JSONObject)) {
                return;
            }
            copyExportFields((JSONObject) raw, table);
        } catch (Exception ignored) {
        }
    }

    /** Snapshot of export fields after {@link ParlerTableFileExportHook} for stream persistence. */
    public static JSONObject sidecarFromTable(JSONObject table) {
        JSONObject sc = new JSONObject();
        if (table == null) {
            return sc;
        }
        for (String k : EXPORT_KEYS) {
            if (!table.has(k)) {
                continue;
            }
            if (table.isNull(k)) {
                sc.put(k, JSONObject.NULL);
            } else {
                sc.put(k, table.get(k));
            }
        }
        return sc;
    }

    private static void copyExportFields(JSONObject from, JSONObject to) {
        for (String k : EXPORT_KEYS) {
            if (!from.has(k)) {
                continue;
            }
            if (from.isNull(k)) {
                to.put(k, JSONObject.NULL);
            } else {
                to.put(k, from.get(k));
            }
        }
    }
}
