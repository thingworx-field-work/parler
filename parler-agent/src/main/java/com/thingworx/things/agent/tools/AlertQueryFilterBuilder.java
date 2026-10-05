package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds ThingWorx QUERY JSON for {@code AlertFunctions} summary/history from typed tool parameters, merged with
 * optional {@code advancedQuery} (AND). Pure org.json — unit-testable without ThingWorx static init.
 *
 * <p>Summary {@code QueryAlertSummaryForThing}: {@code propertyName} maps to the service {@code property} parameter,
 * not QUERY — only {@code alertName}, {@code alertType}, and priority bounds appear in QUERY.</p>
 *
 * <p>History {@code QueryAlertHistory}: {@code propertyName} maps to QUERY EQ on {@code sourceProperty}.</p>
 */
public final class AlertQueryFilterBuilder {

    private AlertQueryFilterBuilder() {}

    /** Typed filters for summary (excludes propertyName — use platform {@code property} param separately). */
    public static JSONObject buildSummaryQuery(
            String alertName,
            String alertType,
            Integer priorityMin,
            Integer priorityMax,
            String advancedQueryJson) throws Exception {
        return buildSummaryQuery(alertName, alertType, priorityMin, priorityMax, advancedQueryJson, null);
    }

    /**
     * @param summarySort optional first-class sort: {@code default} / blank = none; {@code timestamp_asc},
     *        {@code timestamp_desc}, {@code priority_asc}, {@code priority_desc}. Mutually exclusive with
     *        {@code advancedQuery} {@code sorts} array (throws {@link IllegalArgumentException}).
     */
    public static JSONObject buildSummaryQuery(
            String alertName,
            String alertType,
            Integer priorityMin,
            Integer priorityMax,
            String advancedQueryJson,
            String summarySort) throws Exception {
        JSONArray typedSorts = buildTypedSummarySorts(summarySort);
        return buildMerged(alertName, null, null, alertType, priorityMin, priorityMax, advancedQueryJson, typedSorts);
    }

    /** Typed filters for history ({@code sourceProperty} for property name). */
    public static JSONObject buildHistoryQuery(
            String alertName,
            String propertyName,
            String alertType,
            Integer priorityMin,
            Integer priorityMax,
            String advancedQueryJson) throws Exception {
        return buildMerged(alertName, propertyName, "sourceProperty", alertType, priorityMin, priorityMax,
                advancedQueryJson, null);
    }

    static JSONArray buildTypedSummarySorts(String summarySort) {
        if (summarySort == null || summarySort.isBlank()) {
            return null;
        }
        String m = summarySort.trim().toLowerCase();
        if ("default".equals(m)) {
            return null;
        }
        JSONArray sorts = new JSONArray();
        switch (m) {
            case "timestamp_asc":
                sorts.put(new JSONObject().put("fieldName", "timestamp").put("isAscending", true));
                return sorts;
            case "timestamp_desc":
                sorts.put(new JSONObject().put("fieldName", "timestamp").put("isAscending", false));
                return sorts;
            case "priority_asc":
                sorts.put(new JSONObject().put("fieldName", "priority").put("isAscending", true));
                return sorts;
            case "priority_desc":
                sorts.put(new JSONObject().put("fieldName", "priority").put("isAscending", false));
                return sorts;
            default:
                throw new IllegalArgumentException(
                        "Unknown summary sort: " + summarySort + " (use default, timestamp_asc, timestamp_desc, "
                                + "priority_asc, priority_desc)");
        }
    }

    private static JSONObject buildMerged(
            String alertName,
            String propertyName,
            String propertyFieldName,
            String alertType,
            Integer priorityMin,
            Integer priorityMax,
            String advancedQueryJson,
            JSONArray typedSummarySorts) throws Exception {

        List<JSONObject> typed = new ArrayList<>();
        if (alertName != null && !alertName.isBlank()) {
            typed.add(eq("name", alertName.trim()));
        }
        if (propertyName != null && !propertyName.isBlank() && propertyFieldName != null) {
            typed.add(eq(propertyFieldName, propertyName.trim()));
        }
        if (alertType != null && !alertType.isBlank()) {
            typed.add(eq("alertType", alertType.trim()));
        }
        if (priorityMin != null) {
            typed.add(new JSONObject().put("type", "GE").put("fieldName", "priority").put("value", priorityMin));
        }
        if (priorityMax != null) {
            typed.add(new JSONObject().put("type", "LE").put("fieldName", "priority").put("value", priorityMax));
        }

        JSONObject adv = parseAdvanced(advancedQueryJson);
        JSONArray advSorts = adv != null && adv.has("sorts") ? adv.optJSONArray("sorts") : null;

        if (typedSummarySorts != null
                && typedSummarySorts.length() > 0
                && advSorts != null
                && advSorts.length() > 0) {
            throw new IllegalArgumentException("Use summary sort parameter or advancedQuery.sorts, not both");
        }
        JSONArray finalSorts;
        if (typedSummarySorts != null && typedSummarySorts.length() > 0) {
            finalSorts = typedSummarySorts;
        } else {
            finalSorts = advSorts;
        }

        JSONArray andParts = new JSONArray();
        for (JSONObject t : typed) {
            andParts.put(t);
        }
        if (adv != null) {
            if (adv.has("filters")) {
                Object af = adv.get("filters");
                if (af instanceof JSONObject) {
                    andParts.put((JSONObject) af);
                } else if (af instanceof JSONArray) {
                    JSONArray arr = (JSONArray) af;
                    for (int i = 0; i < arr.length(); i++) {
                        Object o = arr.opt(i);
                        if (o instanceof JSONObject) {
                            andParts.put((JSONObject) o);
                        }
                    }
                }
            } else if (adv.has("type")) {
                JSONObject clone = new JSONObject(adv.toString());
                clone.remove("sorts");
                if (clone.length() > 0) {
                    andParts.put(clone);
                }
            }
        }

        if (andParts.length() == 0) {
            if (finalSorts != null && finalSorts.length() > 0) {
                JSONObject out = new JSONObject();
                out.put("sorts", finalSorts);
                return out;
            }
            return null;
        }

        JSONObject filters;
        if (andParts.length() == 1) {
            filters = andParts.getJSONObject(0);
        } else {
            filters = new JSONObject().put("type", "AND").put("filters", andParts);
        }
        JSONObject out = new JSONObject();
        out.put("filters", filters);
        if (finalSorts != null && finalSorts.length() > 0) {
            out.put("sorts", finalSorts);
        }
        return out;
    }

    private static JSONObject eq(String field, String value) {
        return new JSONObject().put("type", "EQ").put("fieldName", field).put("value", value);
    }

    private static JSONObject parseAdvanced(String advancedQueryJson) throws Exception {
        if (advancedQueryJson == null) {
            return null;
        }
        String t = advancedQueryJson.trim();
        if (t.isEmpty()) {
            return null;
        }
        return new JSONObject(t);
    }
}
