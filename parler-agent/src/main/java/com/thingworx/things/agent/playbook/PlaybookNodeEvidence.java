package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds explicit compact evidence on derive node results for {@link PlaybookEvidenceFormatter}.
 * <p>
 * Contract: every derive node listed in a playbook {@code final_summary.evidenceRefs} must call
 * {@link #attach(JSONObject, JSONArray)} (or equivalent) so the node result carries
 * {@code evidenceLines} and usually {@code evidenceText}. The formatter appends those strings verbatim;
 * it does not interpret business-specific {@code output} shapes. Legacy shape fallback in the
 * formatter is only for unknown or un-instrumented nodes.
 * <p>
 * For very large derive payloads, {@link #attachLinesOnly(JSONObject, JSONArray)} may be used so the
 * node carries {@code evidenceLines} only; {@link PlaybookEvidenceFormatter} prefers
 * {@code evidenceLines} over {@code evidenceText} when both are present.
 */
public final class PlaybookNodeEvidence {

    private static final int MAX_ASSET_NAMES_PER_REGION = 8;
    private static final int MAX_ALERT_GROUPS = 6;
    private static final int MAX_TOP_PROPERTIES = 3;
    private static final double FLAT_RATIO_EPS = 0.02;

    private PlaybookNodeEvidence() {}

    public static void attach(JSONObject result, JSONArray evidenceLines) {
        if (result == null || evidenceLines == null || evidenceLines.length() == 0) {
            return;
        }
        result.put("evidenceLines", evidenceLines);
        result.put("evidenceText", joinLines(evidenceLines));
    }

    /**
     * Like {@link #attach} but omits {@code evidenceText} to avoid duplicating the same human-readable
     * payload in the serialized node JSON (used when a strict per-node byte cap applies).
     */
    public static void attachLinesOnly(JSONObject result, JSONArray evidenceLines) {
        if (result == null || evidenceLines == null || evidenceLines.length() == 0) {
            return;
        }
        result.put("evidenceLines", evidenceLines);
    }

    public static JSONArray singleLine(String line) {
        JSONArray lines = new JSONArray();
        if (line != null && !line.isBlank()) {
            lines.put(line);
        }
        return lines;
    }

    public static String joinLines(JSONArray lines) {
        if (lines == null || lines.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(lines.optString(i, ""));
        }
        return sb.toString();
    }

    public static String taxonomyLine(JSONObject taxonomy) {
        String entityType = taxonomy.optString("EntityType", taxonomy.optString("entityType", ""));
        String entityName = taxonomy.optString("EntityName", taxonomy.optString("entityName", ""));
        JSONArray critical = taxonomy.optJSONArray("CriticalPropertiesList");
        if (critical == null && taxonomy.has("CriticalProperties")) {
            critical = semicolonListToArray(taxonomy.optString("CriticalProperties", ""));
        }
        StringBuilder sb = new StringBuilder();
        if (!entityType.isEmpty()) {
            sb.append("EntityType=").append(entityType);
        }
        if (!entityName.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("EntityName=").append(entityName);
        }
        if (critical != null && critical.length() > 0) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("CriticalProperties=[");
            for (int i = 0; i < critical.length() && i < 8; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(critical.optString(i, ""));
            }
            if (critical.length() > 8) {
                sb.append(", …");
            }
            sb.append(']');
        }
        return sb.length() > 0 ? sb.toString() : "taxonomy row resolved";
    }

    public static JSONArray regionEntitiesLines(JSONArray assets, JSONArray gaps) {
        JSONArray lines = new JSONArray();
        if (assets == null || assets.length() == 0) {
            lines.put("no scoped assets");
            return lines;
        }
        java.util.LinkedHashMap<String, java.util.List<String>> byRegion = new java.util.LinkedHashMap<>();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String region = asset.optString("region", "unknown");
            String name = asset.optString("name", "");
            byRegion.computeIfAbsent(region, k -> new java.util.ArrayList<>()).add(name);
        }
        for (var e : byRegion.entrySet()) {
            StringBuilder sb = new StringBuilder();
            sb.append(e.getKey()).append(" (").append(e.getValue().size()).append(" assets");
            if (!e.getValue().isEmpty()) {
                sb.append(": ");
                int shown = 0;
                for (String n : e.getValue()) {
                    if (shown >= MAX_ASSET_NAMES_PER_REGION) {
                        sb.append(", …");
                        break;
                    }
                    if (shown > 0) {
                        sb.append(", ");
                    }
                    sb.append(n);
                    shown++;
                }
            }
            sb.append(')');
            lines.put(sb.toString());
        }
        if (gaps != null && gaps.length() > 0) {
            lines.put("gaps=" + gaps);
        }
        return lines;
    }

    public static JSONArray alertGroupsLines(JSONObject output) {
        JSONArray lines = new JSONArray();
        JSONObject byRegion = output.optJSONObject("groupsByRegion");
        if (byRegion != null && byRegion.length() > 0) {
            for (String region : byRegion.keySet()) {
                lines.put(region + " alerts: " + formatAlertGroups(byRegion.optJSONArray(region)));
            }
            appendAlertAttributionLines(output, lines);
            return lines;
        }
        JSONArray groups = output.optJSONArray("groups");
        if (groups != null) {
            lines.put("alert groups: " + formatAlertGroups(groups));
        }
        appendAlertAttributionLines(output, lines);
        return lines;
    }

    /** One line per attributed Thing so the summarizer can tie alerts to specific Things, never to samples. */
    private static void appendAlertAttributionLines(JSONObject output, JSONArray lines) {
        JSONArray attribution = output.optJSONArray("alertAttribution");
        if (attribution == null || attribution.length() == 0) {
            return;
        }
        Map<String, List<String>> byThing = new LinkedHashMap<>();
        Map<String, String> thingRegion = new LinkedHashMap<>();
        for (int i = 0; i < attribution.length(); i++) {
            JSONObject row = attribution.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String thing = row.optString("thingName", "unknown");
            thingRegion.putIfAbsent(thing, row.optString("region", ""));
            String alert = row.optString("alertName", "unknown");
            String sourceProperty = row.optString("sourceProperty", "");
            byThing.computeIfAbsent(thing, k -> new ArrayList<>())
                    .add(sourceProperty.isEmpty() ? alert : alert + "(" + sourceProperty + ")");
        }
        for (Map.Entry<String, List<String>> e : byThing.entrySet()) {
            String region = thingRegion.getOrDefault(e.getKey(), "");
            lines.put("alerts on " + e.getKey() + (region.isEmpty() ? "" : " [" + region + "]") + ": "
                    + String.join(", ", e.getValue()));
        }
        int omitted = output.optInt("alertAttributionOmitted", 0);
        if (omitted > 0) {
            // No drill-in handle is promised here: per-Thing cacheId exists only when a Thing's alert rows
            // exceeded the egress sample limit, and aggregate overflow across many small Things has none.
            lines.put("alert attribution omits " + omitted + " additional attributed alert row(s) beyond the cap.");
        }
    }

    public static JSONArray regionSummaryLines(JSONObject summary) {
        JSONArray lines = new JSONArray();
        JSONArray regions = summary.optJSONArray("regions");
        if (regions != null) {
            for (int i = 0; i < regions.length(); i++) {
                JSONObject r = regions.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                String name = r.optString("name", "region");
                sb.append(name).append(" assets=").append(r.optInt("assetCount", 0));
                JSONArray alertGroups = r.optJSONArray("alertGroups");
                if (alertGroups != null && alertGroups.length() > 0) {
                    sb.append(" alerts[").append(formatAlertGroups(alertGroups)).append(']');
                }
                JSONArray topProps = r.optJSONArray("topProperties");
                if (topProps != null && topProps.length() > 0) {
                    sb.append(" props[").append(formatTopProperties(topProps)).append(']');
                }
                JSONArray gaps = r.optJSONArray("gaps");
                if (gaps != null && gaps.length() > 0) {
                    sb.append(" gaps=").append(gaps);
                }
                lines.put(sb.toString());
            }
        }
        JSONObject comparison = summary.optJSONObject("comparison");
        if (comparison != null) {
            String higher = comparison.optString("higherAttentionRegion", null);
            if (higher != null && !higher.isEmpty() && !"null".equals(higher)) {
                lines.put("higherAttention=" + higher);
            } else if (comparison.has("higherAttentionRegion") && comparison.isNull("higherAttentionRegion")) {
                lines.put("higherAttention=tied");
            }
            JSONArray reasons = comparison.optJSONArray("reasons");
            if (reasons != null && reasons.length() > 0) {
                lines.put(reasons.optString(0, ""));
            }
        }
        return lines;
    }

    public static JSONArray pairAssetsLines(JSONArray assets) {
        JSONArray lines = new JSONArray();
        if (assets == null || assets.length() == 0) {
            lines.put("no resolved assets");
            return lines;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            String slot = asset.optString("slot", "");
            if (!slot.isEmpty()) {
                sb.append(slot).append('=');
            }
            sb.append(asset.optString("name", ""));
        }
        lines.put(sb.toString());
        return lines;
    }

    public static String primaryPropertyLine(JSONObject output) {
        return primaryPropertyLineForSummary(output, java.util.Collections.emptySet());
    }

    /**
     * Evidence line for {@code primary_property} derive output for {@code llm_summary}.
     * <p>
     * Distinguishes: (1) primaryProperty is chosen from <b>alert evidence / grouping</b>
     * when {@code alertGroups} is non-empty; (2) {@code numericNameHeuristicMatched} is only a legacy
     * <b>trend-planning name hint</b>, not the basis for which property alerts selected; (3) when later tool rows
     * prove numeric history for the same property name, surface that positively.
     */
    public static String primaryPropertyLineForSummary(JSONObject output, Set<String> provenNumericPropertyNames) {
        if (output == null) {
            return "primaryProperty=none";
        }
        JSONArray groups = output.optJSONArray("alertGroups");
        boolean hasAlertSignal = groups != null && groups.length() > 0;

        Object prop = output.opt("primaryProperty");
        String primaryStr = null;
        if (prop != null && prop != JSONObject.NULL) {
            primaryStr = String.valueOf(prop).trim();
            if (primaryStr.isEmpty()) {
                primaryStr = null;
            }
        }
        boolean heuristicNumeric = output.optBoolean("numericNameHeuristicMatched",
                output.optBoolean("isNumeric", false));
        boolean proven = primaryStr != null && provenNumericPropertyNames != null
                && propertyNameProvenNumeric(primaryStr, provenNumericPropertyNames);

        StringBuilder sb = new StringBuilder();
        if (primaryStr != null) {
            sb.append("primaryProperty=").append(primaryStr);
        } else {
            sb.append("primaryProperty=none");
        }
        if (primaryStr != null && hasAlertSignal) {
            sb.append("; selected from alert evidence");
        }

        if (proven) {
            sb.append("; numeric history observed for this property in internal tool reads");
            if (heuristicNumeric) {
                sb.append("; numeric name heuristic matched (legacy trend-planning hint only)");
            }
        } else {
            if (!heuristicNumeric) {
                sb.append("; numeric name heuristic did not match (legacy trend-planning hint only");
                if (primaryStr != null && hasAlertSignal) {
                    sb.append("; primaryProperty selection is alert-driven, not based on the numeric name heuristic)");
                } else {
                    sb.append("; not schema authority for inferred property type)");
                }
            } else {
                sb.append("; numeric name heuristic matched (legacy trend-planning hint only; not schema authority)");
            }
        }

        if (hasAlertSignal) {
            sb.append(", alertGroups[").append(formatAlertGroups(groups)).append(']');
        }
        JSONArray gaps = output.optJSONArray("gaps");
        if (gaps != null && gaps.length() > 0) {
            sb.append(", gaps=").append(gaps);
        }
        return sb.toString();
    }

    private static boolean propertyNameProvenNumeric(String primary, Set<String> proven) {
        for (String p : proven) {
            if (p != null && primary.equalsIgnoreCase(p.trim())) {
                return true;
            }
        }
        return false;
    }

    public static void enrichTrendEntry(JSONObject entry, JSONObject toolOut) {
        if (entry == null || toolOut == null) {
            return;
        }
        JSONObject agg = toolOut.optJSONObject("aggregates");
        Double first = optAggregate(agg, "FIRST");
        Double last = optAggregate(agg, "LAST");
        Double min = optAggregate(agg, "MIN");
        Double max = optAggregate(agg, "MAX");
        Double mean = optAggregate(agg, "MEAN");
        if (first == null && last == null) {
            double[] values = numericValuesFromPoints(toolOut.optJSONArray("points"));
            if (values.length == 0) {
                values = numericValuesFromSampleRows(toolOut.optJSONArray("sampleRows"));
            }
            if (values.length > 0) {
                first = values[0];
                last = values[values.length - 1];
                min = min(values);
                max = max(values);
                mean = mean(values);
            }
        }
        putIfPresent(entry, "first", first);
        putIfPresent(entry, "last", last);
        putIfPresent(entry, "min", min);
        putIfPresent(entry, "max", max);
        putIfPresent(entry, "mean", mean);
        if (first != null && last != null) {
            entry.put("direction", trendDirection(first, last));
        }
    }

    public static JSONArray trendSummaryLines(JSONArray perAsset, JSONArray gaps) {
        JSONArray lines = new JSONArray();
        if (perAsset != null) {
            for (int i = 0; i < perAsset.length(); i++) {
                JSONObject row = perAsset.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                String line = trendAssetLine(row);
                if (!line.isBlank()) {
                    lines.put(line);
                }
            }
        }
        if (gaps != null && gaps.length() > 0) {
            lines.put("gaps=" + gaps);
        }
        if (lines.length() == 0) {
            lines.put("no trend reads");
        }
        return lines;
    }

    public static String trendAssetLine(JSONObject row) {
        StringBuilder sb = new StringBuilder();
        String slot = row.optString("slot", "");
        if (!slot.isEmpty()) {
            sb.append(slot).append(':');
        }
        sb.append(row.optString("thingName", "?"))
                .append('/')
                .append(row.optString("propertyName", "?"));
        if ("failed".equals(row.optString("status", ""))) {
            sb.append(" (failed)");
            return sb.toString();
        }
        appendStat(sb, "first", row);
        appendStat(sb, "last", row);
        appendStat(sb, "min", row);
        appendStat(sb, "max", row);
        appendStat(sb, "mean", row);
        String direction = row.optString("direction", "");
        if (!direction.isEmpty()) {
            sb.append(" direction=").append(direction);
        }
        if (row.has("pointsReturned") && !row.isNull("pointsReturned")) {
            sb.append(" points=").append(row.get("pointsReturned"));
        }
        return sb.toString();
    }

    public static JSONArray pairSummaryLines(JSONObject body) {
        JSONArray lines = new JSONArray();
        Object primary = body.opt("primaryProperty");
        if (primary != null && primary != JSONObject.NULL) {
            lines.put("primaryProperty=" + primary);
        }
        JSONArray assets = body.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                String slot = a.optString("slot", "");
                if (!slot.isEmpty()) {
                    sb.append(slot).append('=');
                }
                sb.append(a.optString("name", "?"))
                        .append(" alerts=").append(a.optInt("alertRowCount", 0));
                JSONArray top = a.optJSONArray("topProperties");
                if (top != null && top.length() > 0) {
                    sb.append(" props[").append(formatTopProperties(top)).append(']');
                }
                lines.put(sb.toString());
            }
        }
        JSONObject comparison = body.optJSONObject("comparison");
        if (comparison != null) {
            Object less = comparison.opt("lessHealthyAsset");
            if (less != null && less != JSONObject.NULL) {
                lines.put("lessHealthy=" + less);
            }
            JSONArray reasons = comparison.optJSONArray("reasons");
            if (reasons != null && reasons.length() > 0) {
                lines.put(reasons.optString(0, ""));
            }
        }
        return lines;
    }

    public static String trendDirection(double first, double last) {
        if (Double.isNaN(first) || Double.isNaN(last)) {
            return "mixed";
        }
        double span = Math.max(Math.max(Math.abs(first), Math.abs(last)), 1e-9);
        double delta = last - first;
        if (Math.abs(delta) <= span * FLAT_RATIO_EPS) {
            return "flat";
        }
        return delta > 0 ? "rising" : "falling";
    }

    private static void appendStat(StringBuilder sb, String key, JSONObject row) {
        if (row.has(key) && !row.isNull(key)) {
            sb.append(' ').append(key).append('=').append(row.get(key));
        }
    }

    private static Double optAggregate(JSONObject agg, String key) {
        if (agg == null || !agg.has(key) || agg.isNull(key)) {
            return null;
        }
        return agg.optDouble(key, Double.NaN);
    }

    private static void putIfPresent(JSONObject entry, String key, Double value) {
        if (value != null && !Double.isNaN(value)) {
            entry.put(key, value);
        }
    }

    private static double[] numericValuesFromPoints(JSONArray points) {
        if (points == null || points.length() == 0) {
            return new double[0];
        }
        double[] out = new double[points.length()];
        int n = 0;
        for (int i = 0; i < points.length(); i++) {
            JSONObject pt = points.optJSONObject(i);
            if (pt == null || pt.isNull("value")) {
                continue;
            }
            double v = pt.optDouble("value", Double.NaN);
            if (!Double.isNaN(v)) {
                out[n++] = v;
            }
        }
        if (n == out.length) {
            return out;
        }
        double[] trimmed = new double[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    private static double[] numericValuesFromSampleRows(JSONArray sampleRows) {
        if (sampleRows == null || sampleRows.length() == 0) {
            return new double[0];
        }
        double[] out = new double[sampleRows.length()];
        int n = 0;
        for (int i = 0; i < sampleRows.length(); i++) {
            JSONObject row = sampleRows.optJSONObject(i);
            if (row == null || row.isNull("value")) {
                continue;
            }
            double v = row.optDouble("value", Double.NaN);
            if (!Double.isNaN(v)) {
                out[n++] = v;
            }
        }
        if (n == out.length) {
            return out;
        }
        double[] trimmed = new double[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    private static double min(double[] values) {
        double m = values[0];
        for (int i = 1; i < values.length; i++) {
            m = Math.min(m, values[i]);
        }
        return m;
    }

    private static double max(double[] values) {
        double m = values[0];
        for (int i = 1; i < values.length; i++) {
            m = Math.max(m, values[i]);
        }
        return m;
    }

    private static double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static String formatAlertGroups(JSONArray groups) {
        if (groups == null || groups.length() == 0) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.length() && i < MAX_ALERT_GROUPS; i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(g.optString("property", g.optString("sourceProperty", "?")))
                    .append('=')
                    .append(g.optInt("alertCount", 0));
        }
        if (groups.length() > MAX_ALERT_GROUPS) {
            sb.append(", …");
        }
        return sb.toString();
    }

    private static String formatTopProperties(JSONArray top) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < top.length() && p < MAX_TOP_PROPERTIES; p++) {
            JSONObject prop = top.optJSONObject(p);
            if (prop == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(prop.optString("name", prop.optString("property", "?")));
            if (prop.has("value")) {
                sb.append('=').append(prop.get("value"));
            }
        }
        return sb.toString();
    }

    private static JSONArray semicolonListToArray(String semicolonSeparated) {
        JSONArray arr = new JSONArray();
        if (semicolonSeparated == null || semicolonSeparated.isBlank()) {
            return arr;
        }
        for (String part : semicolonSeparated.split(";")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                arr.put(t);
            }
        }
        return arr;
    }
}
