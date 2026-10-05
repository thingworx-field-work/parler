package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds a Parler {@code TableBlock} ({@code kind: entity-list}) from {@code list_entities_by_type} **success** tool
 * JSON. Pure {@link org.json} — offline JUnit without ThingWorx static init.
 *
 * <p>Shape: {@code CONTRACTS/TABLE_CONTRACT.md}. Column order is **sorted keys** of the first sample row.</p>
 */
public final class ParlerListEntitiesEntityListTableWire {

    private ParlerListEntitiesEntityListTableWire() {}

    /**
     * When {@code body} is a {@code list_entities_by_type} success payload with INLINE or LARGE {@code resultKind},
     * returns a {@code TableBlock} for {@link ParlerReceiveMessageSupport#wireTable}.
     *
     * @return {@code null} when not applicable, malformed, or empty with no column schema
     */
    public static JSONObject tableBlockFromListEntitiesToolSuccessJson(String body) {
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
        if (!root.has("entityCollectionType")) {
            return null;
        }
        String rk = root.optString("resultKind", "");
        int total = root.optInt("returnedRows", -1);
        JSONArray rawRows;
        if ("ENTITY_LIST_INLINE".equals(rk)) {
            rawRows = root.optJSONArray("rows");
            if (rawRows == null || rawRows.length() < 1) {
                return null;
            }
        } else if ("ENTITY_LIST_LARGE".equals(rk)) {
            rawRows = root.optJSONArray("sampleRows");
            if (rawRows == null || rawRows.length() < 1) {
                return null;
            }
            if (total < 0) {
                total = rawRows.length();
            }
        } else {
            return null;
        }

        JSONObject first = rawRows.optJSONObject(0);
        JSONArray columns = columnsFromSampleRow(first);
        if (columns == null || columns.length() < 1) {
            return null;
        }
        List<String> keys = columnKeys(columns);
        JSONArray rowsWire = filterRows(rawRows, keys);

        JSONObject table = new JSONObject();
        table.put("kind", "entity-list");
        table.put("columns", columns);
        table.put("rows", rowsWire);
        table.put("shownRows", rowsWire.length());
        table.put("totalRows", total >= 0 ? total : rowsWire.length());
        ParlerTableWireFields.putCacheIdFromRoot(table, root);
        ParlerTableWireFields.putPresentationTitle(table,
                ParlerTableWirePresentationTitles.fromListEntitiesSuccess(root));
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
