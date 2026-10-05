package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds a Parler {@code TableBlock} ({@code kind: entity-list}) from {@code fetch_cached_result} **success** tool JSON
 * (see {@link com.thingworx.things.agent.tools.InvokeServiceExecutor#executeFetchCachedResult}). Pure {@link org.json}.
 *
 * <p>Discriminator: {@code status == success}, paging fields {@code offset}, {@code returnedRows}, {@code hasMore},
 * {@code totalRows}, {@code cacheId}, {@code rows}, and <b>no</b> {@code resultKind} / {@code sourceCacheId} (unlike
 * {@code tabulate_cached_result} / {@code invoke_service}).</p>
 */
public final class ParlerFetchCachedResultTableWire {

    private ParlerFetchCachedResultTableWire() {}

    /**
     * @return {@code null} when not applicable or when neither {@code columns[]} nor sample {@code rows} yield a
     *         non-empty column schema
     */
    public static JSONObject tableBlockFromFetchCachedResultJson(String body) {
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
        if (root.optString("resultKind", "").length() > 0) {
            return null;
        }
        if (root.optString("sourceCacheId", "").length() > 0) {
            return null;
        }
        if (!root.has("offset") || !root.has("returnedRows") || !root.has("hasMore") || !root.has("totalRows")) {
            return null;
        }
        String cacheId = root.optString("cacheId", "");
        if (cacheId.isEmpty()) {
            return null;
        }
        JSONArray rawRows = root.optJSONArray("rows");
        if (rawRows == null) {
            rawRows = new JSONArray();
        }
        JSONObject first = rawRows.length() > 0 ? rawRows.optJSONObject(0) : null;
        JSONArray columns = buildColumns(root, first);
        if (columns == null || columns.length() < 1) {
            return null;
        }
        List<String> keys = columnKeys(columns);
        JSONArray rowsWire = filterRows(rawRows, keys);
        int total = root.optInt("totalRows", -1);
        if (total < 0) {
            total = rowsWire.length();
        }

        JSONObject table = new JSONObject();
        table.put("kind", "entity-list");
        table.put("columns", columns);
        table.put("rows", rowsWire);
        table.put("shownRows", rowsWire.length());
        table.put("totalRows", total);
        table.put("cacheId", cacheId);
        ParlerTableWireFields.putPresentationTitle(table,
                ParlerTableWirePresentationTitles.fromFetchCachedSuccess(root));
        table.put("exportStatus", "none");
        table.put("exportMessage", JSONObject.NULL);
        table.put("exportRepository", JSONObject.NULL);
        table.put("exportFile", JSONObject.NULL);
        table.put("exportDownloadUrl", JSONObject.NULL);
        return table;
    }

    private static JSONArray buildColumns(JSONObject root, JSONObject firstRow) {
        JSONArray meta = root.optJSONArray("columns");
        if (meta != null && meta.length() > 0) {
            JSONArray out = new JSONArray();
            for (int i = 0; i < meta.length(); i++) {
                JSONObject c = meta.optJSONObject(i);
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
            if (out.length() > 0) {
                return out;
            }
        }
        return columnsFromSampleRow(firstRow);
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

    private static JSONArray columnsFromSampleRow(JSONObject firstRow) {
        if (firstRow == null || firstRow.length() == 0) {
            return null;
        }
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = firstRow.keys(); it.hasNext();) {
            keys.add(it.next());
        }
        Collections.sort(keys);
        JSONArray out = new JSONArray();
        for (String k : keys) {
            JSONObject col = new JSONObject();
            col.put("key", k);
            col.put("label", k);
            col.put("baseType", inferBaseType(firstRow.opt(k)));
            out.put(col);
        }
        return out;
    }

    private static String inferBaseType(Object v) {
        if (v == null || v == JSONObject.NULL) {
            return "STRING";
        }
        if (v instanceof Boolean) {
            return "BOOLEAN";
        }
        if (v instanceof Integer || v instanceof Long) {
            return "INTEGER";
        }
        if (v instanceof Number) {
            return "NUMBER";
        }
        if (v instanceof JSONArray || v instanceof JSONObject) {
            return "STRING";
        }
        return "STRING";
    }

    private static JSONArray filterRows(JSONArray in, List<String> keys) {
        JSONArray out = new JSONArray();
        if (in == null) {
            return out;
        }
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
