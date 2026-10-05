package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds a Parler {@code TableBlock} ({@code kind: entity-list}) from {@code invoke_service} **success** tool JSON when
 * {@code resultKind} is {@code INFOTABLE} or {@code INFOTABLE_LARGE} (see {@link com.thingworx.things.agent.tools.InvokeServiceExecutor}).
 * Pure {@link org.json} — offline JUnit without ThingWorx static init.
 *
 * <p>Does <b>not</b> match {@code fetch_cached_result} — use {@link ParlerFetchCachedResultTableWire}.</p>
 */
public final class ParlerInvokeServiceInfotableTableWire {

    private ParlerInvokeServiceInfotableTableWire() {}

    /**
     * @return {@code null} when not applicable or no row material
     */
    public static JSONObject tableBlockFromInvokeServiceInfotableJson(String body) {
        return tableBlockFromInvokeServiceInfotableJson(body, null);
    }

    /**
     * @param executedToolName LLM-facing tool id from stream row or live carrier; {@code null} uses body {@code tool},
     *                         invoke envelope fields, then §7 literal only (no shape-based tool-name guess)
     * @return {@code null} when not applicable or no row material
     */
    public static JSONObject tableBlockFromInvokeServiceInfotableJson(String body, String executedToolName) {
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
        String rk = root.optString("resultKind", "");
        JSONArray rawRows;
        int total = -1;
        if ("INFOTABLE".equals(rk)) {
            rawRows = root.optJSONArray("rows");
            if (rawRows == null || rawRows.length() < 1) {
                return null;
            }
            total = root.optInt("rowCount", -1);
        } else if ("INFOTABLE_LARGE".equals(rk)) {
            rawRows = root.optJSONArray("sampleRows");
            if (rawRows == null || rawRows.length() < 1) {
                return null;
            }
            total = root.optInt("totalRows", -1);
            if (total < 0) {
                total = rawRows.length();
            }
        } else {
            return null;
        }

        JSONObject first = rawRows.optJSONObject(0);
        JSONArray columns = buildColumns(root, first);
        if (columns == null || columns.length() < 1) {
            return null;
        }
        List<String> keys = columnKeys(columns);
        JSONArray rowsWire = filterRows(rawRows, keys);
        if (total < 0) {
            total = rowsWire.length();
        }

        JSONObject table = new JSONObject();
        table.put("kind", "entity-list");
        table.put("columns", columns);
        table.put("rows", rowsWire);
        table.put("shownRows", rowsWire.length());
        table.put("totalRows", total);
        ParlerTableWireFields.putCacheIdFromRoot(table, root);
        ParlerTableWireFields.putPresentationTitle(table,
                ParlerTableWirePresentationTitles.infotablePresentationTitle(root, executedToolName));
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
