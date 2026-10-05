package com.thingworx.things.agent.playbook;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.ToolResultEgressGateway;

/**
 * Reads alert rows from {@code query_alert_summary} tool JSON ({@code rows} or {@code sampleRows} when
 * {@code INFOTABLE_LARGE}).
 */
final class PlaybookAlertSummaryRows {

    /** Matches {@code InvokeServiceExecutor} large-table inline threshold for built-in INFOTABLE tools. */
    private static final int LARGE_INLINE_ROW_THRESHOLD = 20;

    static final class Extracted {
        private final JSONArray rows;
        private final boolean partial;
        private final String gapNote;

        Extracted(JSONArray rows, boolean partial, String gapNote) {
            this.rows = rows != null ? rows : new JSONArray();
            this.partial = partial;
            this.gapNote = gapNote;
        }

        JSONArray rows() {
            return rows;
        }

        boolean partial() {
            return partial;
        }

        String gapNote() {
            return gapNote;
        }
    }

    private PlaybookAlertSummaryRows() {}

    static Extracted fromToolOutput(JSONObject toolOut) {
        if (toolOut == null) {
            return new Extracted(new JSONArray(), false, null);
        }
        String kind = toolOut.optString("resultKind", "");
        if (ToolResultEgressGateway.RESULT_KIND_ALERT_SUMMARY_MULTI.equals(kind)) {
            return fromMultiThingRollup(toolOut);
        }
        if ("INFOTABLE_LARGE".equals(kind)) {
            JSONArray sample = toolOut.optJSONArray("sampleRows");
            if (sample == null) {
                sample = toolOut.optJSONArray("rows");
            }
            int total = toolOut.optInt("totalRows", sample != null ? sample.length() : 0);
            String note = "Alert summary truncated at " + LARGE_INLINE_ROW_THRESHOLD + " rows (totalRows=" + total
                    + "); counts use sample only.";
            return new Extracted(sample != null ? sample : new JSONArray(), true, note);
        }
        JSONArray rows = toolOut.optJSONArray("rows");
        if (rows == null) {
            rows = toolOut.optJSONArray("alertRows");
        }
        return new Extracted(rows != null ? rows : new JSONArray(), false, null);
    }

    private static Extracted fromMultiThingRollup(JSONObject toolOut) {
        JSONArray rows = new JSONArray();
        JSONArray byThing = toolOut.optJSONArray("byThing");
        if (byThing != null) {
            for (int i = 0; i < byThing.length(); i++) {
                JSONObject entry = byThing.optJSONObject(i);
                if (entry == null || !"success".equals(entry.optString("status"))) {
                    continue;
                }
                JSONArray top = entry.optJSONArray("topAlerts");
                if (top != null) {
                    String thingName = entry.optString("thingName", "");
                    for (int j = 0; j < top.length(); j++) {
                        JSONObject alert = top.optJSONObject(j);
                        if (alert == null) {
                            continue;
                        }
                        String[] names = JSONObject.getNames(alert);
                        JSONObject row = names != null ? new JSONObject(alert, names) : new JSONObject();
                        if (!thingName.isEmpty() && !row.has("thingName")) {
                            row.put("thingName", thingName);
                        }
                        rows.put(row);
                    }
                }
            }
        }
        boolean partial = "partial".equals(toolOut.optString("completeness"));
        String gap = partial
                ? "Multi-Thing alert summary partial (identity or service failures); counts use successful Things only."
                : null;
        return new Extracted(rows, partial, gap);
    }
}
