package com.thingworx.things.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded {@code TableBlock.presentationTitle} strings from structured tool success JSON only
 * (per-tool allowlists).
 */
public final class ParlerTableWirePresentationTitles {

    private ParlerTableWirePresentationTitles() {}

    static String fromTabulateSuccess(JSONObject root) {
        if (root == null) {
            return null;
        }
        String rk = root.optString("resultKind", "");
        if (rk.startsWith("CACHED_GROUP_METRIC")) {
            return groupMetricTitle(root);
        }
        if (rk.startsWith("CACHED_FILTER_ROWS") || rk.startsWith("CACHED_FILTER_SORT_TOPN")) {
            String match = pair("matchCount", root.optInt("matchCount", -1));
            return match != null ? joinToolAndPairs("tabulate_cached_result", match) : "tabulate_cached_result: filter_rows";
        }
        if (rk.startsWith("CACHED_FILTER")) {
            return "tabulate_cached_result: filter";
        }
        return "tabulate_cached_result";
    }

    static String fromListEntitiesSuccess(JSONObject root) {
        if (root == null) {
            return null;
        }
        String type = root.optString("entityCollectionType", "");
        if (type.isEmpty()) {
            return "list_entities_by_type";
        }
        return joinToolAndPairs("list_entities_by_type", pair("entityCollectionType", type));
    }

    static String fromQueryEntitiesSuccess(JSONObject root) {
        if (root == null) {
            return null;
        }
        String parent = root.optString("parentName", "");
        String kind = root.optString("parentKind", "");
        if (!parent.isEmpty() && !kind.isEmpty()) {
            return joinToolAndPairs("query_entities", pair("parent", kind + ":" + parent));
        }
        if (!parent.isEmpty()) {
            return joinToolAndPairs("query_entities", pair("parentName", parent));
        }
        return "query_entities";
    }

    static String fromFetchCachedSuccess(JSONObject root) {
        if (root == null) {
            return null;
        }
        return joinToolAndPairs("fetch_cached_result", pair("cacheId", root.optString("cacheId", "")));
    }

    static String fromAnalyzeEntitySetSuccess(JSONObject root) {
        if (root == null) {
            return null;
        }
        String op = root.optString("operation", "").trim();
        int mk = root.optInt("matchedKeys", -1);
        if (!op.isEmpty() && mk >= 0) {
            return "analyze_entity_set: operation=" + op + ", matchedKeys=" + mk;
        }
        if (!op.isEmpty()) {
            return joinToolAndPairs("analyze_entity_set", pair("operation", op));
        }
        return "analyze_entity_set";
    }

    private static String groupMetricTitle(JSONObject root) {
        StringBuilder sb = new StringBuilder("tabulate_cached_result");
        JSONArray gb = root.optJSONArray("groupBy");
        if (gb != null && gb.length() > 0) {
            sb.append(": groupBy=");
            appendJsonArrayCsv(sb, gb, 3);
        }
        JSONArray measures = root.optJSONArray("measures");
        if (measures != null && measures.length() > 0) {
            sb.append(", measure=");
            appendJsonArrayCsv(sb, measures, 2);
        }
        return sb.toString();
    }

    private static void appendJsonArrayCsv(StringBuilder sb, JSONArray arr, int cap) {
        int n = Math.min(arr.length(), cap);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object el = arr.opt(i);
            sb.append(el != null ? String.valueOf(el) : "");
        }
        if (arr.length() > cap) {
            sb.append(", …");
        }
    }

    private static String pair(String key, Object value) {
        if (value == null) {
            return null;
        }
        String v = String.valueOf(value).trim();
        if (v.isEmpty() || "-1".equals(v)) {
            return null;
        }
        return key + "=" + v;
    }

    private static String joinToolAndPairs(String tool, String pair) {
        if (pair == null || pair.isEmpty()) {
            return tool;
        }
        return tool + ": " + pair;
    }

    /**
     * {@code INFOTABLE} / {@code INFOTABLE_LARGE} invoke-shaped tool success JSON (shared formatter envelope).
     *
     * @param executedToolName from stream row or live {@link com.thingworx.things.agent.llm.ChatMessage} carrier; may be
     *                         {@code null} / blank (legacy: body {@code tool} field, invoke envelope, then §7)
     */
    static String infotablePresentationTitle(JSONObject root, String executedToolName) {
        if (root == null) {
            return "invoke_service";
        }
        String ex = executedToolName != null ? executedToolName.trim() : "";
        if (!ex.isEmpty()) {
            return titleForExecutedToolAndBody(ex, root);
        }
        String bodyTool = validatedTopLevelTool(root.optString("tool", ""));
        if (bodyTool != null) {
            return titleForExecutedToolAndBody(bodyTool, root);
        }
        if (hasEntityAndService(root)) {
            return invokeServiceDisplayTitle(root);
        }
        return "invoke_service";
    }

    private static boolean hasEntityAndService(JSONObject root) {
        return !root.optString("entityName", "").trim().isEmpty()
                && !root.optString("serviceName", "").trim().isEmpty();
    }

    private static String validatedTopLevelTool(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty() || s.indexOf(' ') >= 0 || s.indexOf('\t') >= 0 || s.indexOf('\n') >= 0) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-';
            if (!ok) {
                return null;
            }
        }
        return s;
    }

    private static String titleForExecutedToolAndBody(String executedTool, JSONObject root) {
        if ("invoke_service".equals(executedTool)) {
            return invokeServiceDisplayTitle(root);
        }
        String suffix = infotableSuffixForTool(executedTool, root);
        return joinToolAndPairs(executedTool, suffix);
    }

    private static String infotableSuffixForTool(String tool, JSONObject root) {
        if ("query_alert_history".equals(tool) || "query_alert_summary".equals(tool)
                || "query_stream_data".equals(tool) || "query_value_stream_property_history".equals(tool)
                || "query_property_history".equals(tool)) {
            return pair("thingName", root.optString("thingName", ""));
        }
        return null;
    }

    /** {@code invoke_service: <entity>.<service>} using JSON value fields {@code entityName} / {@code serviceName}. */
    private static String invokeServiceDisplayTitle(JSONObject root) {
        String entity = root.optString("entityName", "").trim();
        String service = root.optString("serviceName", "").trim();
        if (!entity.isEmpty() && !service.isEmpty()) {
            return "invoke_service: " + entity + "." + service;
        }
        if (!entity.isEmpty()) {
            return "invoke_service: " + entity;
        }
        if (!service.isEmpty()) {
            return "invoke_service: " + service;
        }
        return "invoke_service";
    }

}
