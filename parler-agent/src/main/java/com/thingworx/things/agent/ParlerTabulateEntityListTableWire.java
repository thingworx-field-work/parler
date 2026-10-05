package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds a Parler {@code TableBlock} ({@code kind: entity-list}) from {@code tabulate_cached_result} **success** tool
 * JSON (Jackson-produced bodies are compatible with {@link org.json} parsing). Pure {@link org.json} — offline JUnit
 * without ThingWorx static init.
 *
 * <p>Shape: {@code CONTRACTS/TABLE_CONTRACT.md}. Ordering vs {@code tabular.tool_success}: {@code API_CONTRACT.md}
 * (table before compact Further Insight frame).</p>
 */
public final class ParlerTabulateEntityListTableWire {

    private ParlerTabulateEntityListTableWire() {}

    /**
     * When {@code body} is a {@code tabulate_cached_result} success payload with a list-class {@code resultKind}
     * ({@code CACHED_TABULATE_*}, {@code CACHED_FILTER_ROWS_*}, {@code CACHED_FILTER_SORT_TOPN_*},
     * {@code CACHED_GROUP_METRIC_*}),
     * returns a {@code TableBlock} object suitable for {@link ParlerReceiveMessageSupport#wireTable}.
     *
     * @return {@code null} when not applicable, malformed, or columns cannot be derived
     */
    public static JSONObject tableBlockFromTabulateToolSuccessJson(String body) {
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
        if (resultKind.isEmpty()
                || !(resultKind.startsWith("CACHED_TABULATE")
                        || resultKind.startsWith("CACHED_FILTER_ROWS")
                        || resultKind.startsWith("CACHED_FILTER_SORT_TOPN")
                        || resultKind.startsWith("CACHED_GROUP_METRIC"))) {
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
        String source = root.optString("sourceCacheId", "");
        if (!source.isEmpty()) {
            table.put("sourceCacheId", source);
        }
        ParlerTableWireFields.putCacheIdFromRoot(table, root);
        ParlerTableWireFields.putPresentationTitle(table,
                ParlerTableWirePresentationTitles.fromTabulateSuccess(root));
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
        JSONArray fromEnv = null;
        JSONObject env = root.optJSONObject("insightEnvelope");
        if (env != null) {
            fromEnv = env.optJSONArray("columns");
        }
        JSONArray out = new JSONArray();
        if (fromEnv != null && fromEnv.length() > 0) {
            for (int i = 0; i < fromEnv.length(); i++) {
                JSONObject c = fromEnv.optJSONObject(i);
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
        }
        if (out.length() > 0) {
            return out;
        }
        JSONArray rootCols = root.optJSONArray("columns");
        if (rootCols == null || rootCols.length() < 1) {
            return null;
        }
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
        if ("CACHED_TABULATE_LARGE".equals(resultKind) || "CACHED_FILTER_ROWS_LARGE".equals(resultKind)
                || "CACHED_FILTER_SORT_TOPN_LARGE".equals(resultKind) || "CACHED_GROUP_METRIC_LARGE".equals(resultKind)) {
            raw = root.optJSONArray("sampleRows");
        } else if ("CACHED_TABULATE_INLINE".equals(resultKind) || "CACHED_FILTER_ROWS_INLINE".equals(resultKind)
                || "CACHED_FILTER_SORT_TOPN_INLINE".equals(resultKind) || "CACHED_GROUP_METRIC_INLINE".equals(resultKind)) {
            raw = root.optJSONArray("rows");
        } else if ("CACHED_TABULATE_EMPTY".equals(resultKind) || "CACHED_FILTER_ROWS_EMPTY".equals(resultKind)
                || "CACHED_FILTER_SORT_TOPN_EMPTY".equals(resultKind) || "CACHED_GROUP_METRIC_EMPTY".equals(resultKind)) {
            raw = new JSONArray();
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
