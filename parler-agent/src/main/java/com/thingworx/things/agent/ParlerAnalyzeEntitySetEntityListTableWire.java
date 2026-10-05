package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds a Parler {@code TableBlock} ({@code kind: entity-list}) from {@code analyze_entity_set} **success** tool JSON.
 */
public final class ParlerAnalyzeEntitySetEntityListTableWire {

    private ParlerAnalyzeEntitySetEntityListTableWire() {}

    /**
     * @return {@code null} when not applicable or malformed
     */
    public static JSONObject tableBlockFromAnalyzeEntitySetToolSuccessJson(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
        if (!"success".equals(root.optString("status", ""))) {
            return null;
        }
        String resultKind = root.optString("resultKind", "");
        if (!resultKind.startsWith("ENTITY_SET_")) {
            return null;
        }
        JSONArray columns = buildWireColumns(root);
        if (columns == null || columns.length() < 1) {
            return null;
        }
        List<String> keys = columnKeys(columns);
        JSONArray rowsWire = buildRowsForKind(root, resultKind, keys);
        if (rowsWire == null) {
            return null;
        }
        int shown = rowsWire.length();
        int total = root.optInt("totalRows", shown);

        JSONObject table = new JSONObject();
        table.put("kind", "entity-list");
        table.put("columns", columns);
        table.put("rows", rowsWire);
        table.put("shownRows", shown);
        table.put("totalRows", total);
        ParlerTableWireFields.putCacheIdFromRoot(table, root);
        ParlerTableWireFields.putPresentationTitle(table,
                ParlerTableWirePresentationTitles.fromAnalyzeEntitySetSuccess(root));
        table.put("exportStatus", "none");
        table.put("exportMessage", JSONObject.NULL);
        table.put("exportRepository", JSONObject.NULL);
        table.put("exportFile", JSONObject.NULL);
        table.put("exportDownloadUrl", JSONObject.NULL);
        return table;
    }

    private static List<String> columnKeys(JSONArray cols) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < cols.length(); i++) {
            JSONObject c = cols.optJSONObject(i);
            if (c != null) {
                String k = c.optString("key", "");
                if (!k.isEmpty()) {
                    keys.add(k);
                }
            }
        }
        return keys;
    }

    private static JSONArray buildWireColumns(JSONObject root) {
        JSONArray rootCols = root.optJSONArray("columns");
        if (rootCols == null || rootCols.length() < 1) {
            return null;
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < rootCols.length(); i++) {
            JSONObject c = rootCols.optJSONObject(i);
            if (c == null) {
                continue;
            }
            String name = c.optString("name", "");
            if (name.isEmpty()) {
                continue;
            }
            String bt = c.optString("baseType", "STRING");
            if (bt.isEmpty()) {
                bt = "STRING";
            }
            JSONObject col = new JSONObject();
            col.put("key", name);
            col.put("label", name);
            col.put("baseType", bt);
            out.put(col);
        }
        return out.length() > 0 ? out : null;
    }

    private static JSONArray buildRowsForKind(JSONObject root, String resultKind, List<String> keys) {
        JSONArray raw;
        if ("ENTITY_SET_LARGE".equals(resultKind)) {
            raw = root.optJSONArray("sampleRows");
        } else if ("ENTITY_SET_INLINE".equals(resultKind) || "ENTITY_SET_EMPTY".equals(resultKind)) {
            raw = root.optJSONArray("rows");
        } else {
            return null;
        }
        if (raw == null) {
            raw = new JSONArray();
        }
        return filterRows(raw, keys);
    }

    private static JSONArray filterRows(JSONArray in, List<String> keys) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject r = in.optJSONObject(i);
            if (r == null) {
                continue;
            }
            JSONObject o = new JSONObject();
            for (String k : keys) {
                if (r.has(k)) {
                    o.put(k, r.get(k));
                }
            }
            out.put(o);
        }
        return out;
    }
}
