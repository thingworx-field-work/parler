package com.thingworx.things.agent.hostcontext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded formatters for host-context templates (docs/architecture/host-context.md §8).
 */
public final class HostContextFormatters {

    public static final int MAX_ITEMS_SHOWN = 3;
    public static final int MAX_ITEM_CHARS = 120;
    public static final int MAX_RENDERED_CHARS_PER_FORMATTER = 600;
    public static final int MAX_FENCED_JSON_CHARS = 3000;
    public static final String UNAVAILABLE = "unavailable";

    private HostContextFormatters() {
    }

    public static String jsonFence(Object value, String blockName, List<String> diagnostics) {
        String bnErr = HostContextBlockNameValidator.validateOrReason(blockName);
        if (bnErr != null) {
            if (diagnostics != null) {
                diagnostics.add("format.jsonFence blockName: " + bnErr);
            }
            return UNAVAILABLE;
        }
        JSONObject obj = asObjectOrNull(value);
        JSONArray arr = obj == null ? asArrayOrNull(value) : null;
        if (obj == null && arr == null) {
            if (diagnostics != null) {
                diagnostics.add("format.jsonFence: value is not JSON object or array");
            }
            return UNAVAILABLE;
        }
        String json = obj != null ? obj.toString(2) : arr.toString(2);
        json = escapeFenceBreakingSequences(json);
        boolean truncated = false;
        if (json.length() > MAX_FENCED_JSON_CHARS) {
            json = json.substring(0, MAX_FENCED_JSON_CHARS);
            truncated = true;
            if (diagnostics != null) {
                diagnostics.add("format.jsonFence(" + blockName + "): truncated at " + MAX_FENCED_JSON_CHARS);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Block: ").append(blockName).append('\n');
        sb.append(HostContextTemplate.JSON_FENCE_PREAMBLE).append('\n').append('\n');
        sb.append("```json\n").append(json);
        if (truncated) {
            sb.append("\n… [truncated]");
        }
        sb.append("\n```");
        return sb.toString();
    }

    /** Break markdown fence closure if JSON string values contain backtick runs. */
    static String escapeFenceBreakingSequences(String json) {
        if (json == null || json.indexOf('`') < 0) {
            return json;
        }
        return json.replace("`", "`\u200b");
    }

    public static String typedList(Object value, String labelSingular, String typeField, String nameField,
            List<String> diagnostics) {
        JSONArray arr = asArrayOrNull(value);
        if (arr == null) {
            if (diagnostics != null) {
                diagnostics.add("format.typedList: expected array");
            }
            return UNAVAILABLE;
        }
        if (arr.length() == 0) {
            return "No " + labelSingular + " is selected.";
        }
        List<String> parts = new ArrayList<>();
        int n = Math.min(arr.length(), MAX_ITEMS_SHOWN);
        for (int i = 0; i < n; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String et = o.optString(typeField, "").trim();
            String en = o.optString(nameField, "").trim();
            parts.add(trunc(et + " " + en, MAX_ITEM_CHARS));
        }
        if (parts.isEmpty()) {
            return UNAVAILABLE;
        }
        String joined = joinNatural(parts);
        String countWord = arr.length() == 1 ? "One" : String.valueOf(arr.length());
        String plural = arr.length() == 1 ? labelSingular : labelSingular + "s";
        String suffix = arr.length() > MAX_ITEMS_SHOWN ? " (showing first " + MAX_ITEMS_SHOWN + ")" : "";
        return countWord + " " + plural + " are selected: " + joined + "." + suffix;
    }

    public static String filters(Object value, List<String> diagnostics) {
        JSONObject root = asObjectOrNull(value);
        if (root == null) {
            if (diagnostics != null) {
                diagnostics.add("format.filters: expected object");
            }
            return UNAVAILABLE;
        }
        JSONArray filters = root.optJSONArray("filters");
        if (filters == null || filters.length() == 0) {
            return "No active filters.";
        }
        List<String> clauses = new ArrayList<>();
        int n = Math.min(filters.length(), MAX_ITEMS_SHOWN);
        for (int i = 0; i < n; i++) {
            JSONObject f = filters.optJSONObject(i);
            if (f == null) {
                continue;
            }
            String field = f.optString("fieldName", "");
            String type = f.optString("type", "EQ");
            Object val = f.has("value") ? f.get("value") : null;
            clauses.add(field + " " + filterOp(type) + " " + String.valueOf(val));
        }
        if (clauses.isEmpty()) {
            return UNAVAILABLE;
        }
        String joinType = root.optString("type", "AND");
        return "Active filters: " + String.join(" " + joinType + " ", clauses) + ".";
    }

    public static String timeWindow(Object value, List<String> diagnostics) {
        JSONObject o = asObjectOrNull(value);
        if (o == null) {
            if (diagnostics != null) {
                diagnostics.add("format.timeWindow: expected object");
            }
            return UNAVAILABLE;
        }
        String kind = o.optString("kind", "relative");
        String v = o.optString("value", "");
        if (v.isEmpty()) {
            return UNAVAILABLE;
        }
        if ("relative".equalsIgnoreCase(kind)) {
            return "past " + v;
        }
        return kind + " window " + v;
    }

    public static String hierarchy(Object networkName, Object selectedNode, List<String> diagnostics) {
        String net = stringOrNull(networkName);
        String node = stringOrNull(selectedNode);
        if (node == null || node.isEmpty()) {
            return "No hierarchy node is selected; this is equivalent to the root scope.";
        }
        if (net == null || net.isEmpty()) {
            return "Current hierarchy scope: node " + node + ".";
        }
        return "Current hierarchy scope: node " + node + " in network " + net + ".";
    }

    public static String list(Object value, String labelSingular, List<String> diagnostics) {
        JSONArray arr = asArrayOrNull(value);
        if (arr == null) {
            List<String> fromStrings = stringListOrNull(value);
            if (fromStrings == null) {
                if (diagnostics != null) {
                    diagnostics.add("format.list: expected string array");
                }
                return UNAVAILABLE;
            }
            return formatStringList(fromStrings, labelSingular);
        }
        List<String> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            Object el = arr.get(i);
            if (el instanceof String) {
                items.add((String) el);
            } else if (el instanceof JSONObject) {
                JSONObject o = (JSONObject) el;
                if (o.has("ColumnName")) {
                    items.add(o.optString("ColumnName", ""));
                }
            }
        }
        return formatStringList(items, labelSingular);
    }

    public static String kv(Object value, List<String> diagnostics) {
        JSONObject o = asObjectOrNull(value);
        if (o == null) {
            if (diagnostics != null) {
                diagnostics.add("format.kv: expected object");
            }
            return UNAVAILABLE;
        }
        Iterator<String> keys = o.keys();
        List<String> parts = new ArrayList<>();
        int count = 0;
        while (keys.hasNext() && count < 16) {
            String k = keys.next();
            Object v = o.get(k);
            parts.add(k + "=" + trunc(String.valueOf(v), 120));
            count++;
        }
        if (parts.isEmpty()) {
            return UNAVAILABLE;
        }
        return "Additional page context: " + String.join("; ", parts) + ".";
    }

    private static String formatStringList(List<String> items, String labelSingular) {
        if (items.isEmpty()) {
            return "No " + labelSingular + " is selected.";
        }
        List<String> shown = new ArrayList<>();
        int n = Math.min(items.size(), MAX_ITEMS_SHOWN);
        for (int i = 0; i < n; i++) {
            shown.add(trunc(items.get(i), MAX_ITEM_CHARS));
        }
        String countWord = items.size() == 1 ? "One" : String.valueOf(items.size());
        String plural = items.size() == 1 ? labelSingular : labelSingular + "s";
        return countWord + " " + plural + " are selected: " + joinNatural(shown) + ".";
    }

    private static String filterOp(String type) {
        if (type == null) {
            return "equals";
        }
        switch (type.toUpperCase(Locale.ROOT)) {
            case "EQ":
                return "equals";
            case "NE":
                return "not equals";
            case "GT":
                return "greater than";
            case "LT":
                return "less than";
            default:
                return type.toLowerCase(Locale.ROOT);
        }
    }

    private static String joinNatural(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        if (parts.size() == 2) {
            return parts.get(0) + " and " + parts.get(1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size() - 1; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parts.get(i));
        }
        sb.append(", and ").append(parts.get(parts.size() - 1));
        return sb.toString();
    }

    private static JSONObject asObjectOrNull(Object value) {
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        return null;
    }

    private static JSONArray asArrayOrNull(Object value) {
        if (value instanceof JSONArray) {
            return (JSONArray) value;
        }
        return null;
    }

    private static List<String> stringListOrNull(Object value) {
        if (!(value instanceof JSONArray)) {
            return null;
        }
        JSONArray arr = (JSONArray) value;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            if (!(arr.get(i) instanceof String)) {
                return null;
            }
            out.add((String) arr.get(i));
        }
        return out;
    }

    private static String stringOrNull(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        return String.valueOf(value);
    }

    private static String trunc(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
