package com.thingworx.things.agent;

import java.util.Optional;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Builds a {@code ChartBlock} JSON object (Parler repo {@code CONTRACTS/CHART_CONTRACT.md}) from
 * {@code query_numeric_property_history} tool results so {@link ParlerReceiveMessageSupport#wireChart}
 * can emit {@code type: "chart"}. Parity with Python {@code build_chart_from_history}: {@code kind}
 * {@code line|bar|scatter}, optional {@code title}/{@code x_label}/{@code y_label}, optional
 * {@code y_reference_lines} (SPC-style roles: usl, ucl, lcl, lsl, target, limit, warning).
 */
public final class ParlerChartWireSupport {

    private static final Set<String> REF_ROLES = Set.of(
            "usl", "ucl", "lcl", "lsl", "target", "limit", "warning");

    private ParlerChartWireSupport() {}

    /** Time {@code line}/{@code scatter} only: copy tool {@code requested_time_range} onto the wire chart. */
    private static void attachRequestedTimeRangeFromToolRoot(JSONObject root, String kind, JSONObject chart) {
        if (!"line".equals(kind) && !"scatter".equals(kind)) {
            return;
        }
        JSONObject rtr = root.optJSONObject("requested_time_range");
        if (rtr == null) {
            return;
        }
        String rs = rtr.optString("start", "").trim();
        String re = rtr.optString("end", "").trim();
        if (rs.isEmpty() || re.isEmpty()) {
            return;
        }
        JSONObject out = new JSONObject();
        out.put("start", rs);
        out.put("end", re);
        chart.put("requested_time_range", out);
    }

    /**
     * Maps {@code query_numeric_property_history} success JSON to a {@code ChartBlock} when
     * {@code points} is non-empty with numeric {@code value}s.
     * <p>
     * Live tool path: assigns a fresh {@code chartId}. History replay must pass {@code false} for
     * {@code assignNewChartId} so reconstructed charts do not mint unstable ids.
     */
    public static Optional<JSONObject> chartBlockFromNumericHistoryToolResult(String toolResultBody) {
        return chartBlockFromNumericHistoryToolResult(toolResultBody, true);
    }

    /**
     * @param assignNewChartId when {@code true}, sets {@code chartId} via {@link AgentToolContext#nextParlerChartId()};
     *                          when {@code false}, omits {@code chartId} (e.g. history exporter replay).
     */
    public static Optional<JSONObject> chartBlockFromNumericHistoryToolResult(
            String toolResultBody, boolean assignNewChartId) {
        if (toolResultBody == null || toolResultBody.isEmpty()) {
            return Optional.empty();
        }
        try {
            JSONObject root = new JSONObject(toolResultBody);
            if (!"success".equals(root.optString("status"))) {
                return Optional.empty();
            }
            JSONArray pts = root.optJSONArray("points");
            if (pts == null || pts.length() == 0) {
                return Optional.empty();
            }
            String thingName = root.optString("thingName", "");
            String propertyName = root.optString("propertyName", "");
            JSONArray xArr = new JSONArray();
            JSONArray yArr = new JSONArray();
            for (int i = 0; i < pts.length(); i++) {
                JSONObject p = pts.optJSONObject(i);
                if (p == null || !p.has("value") || p.isNull("value")) {
                    return Optional.empty();
                }
                double y = p.getDouble("value");
                if (Double.isNaN(y) || Double.isInfinite(y)) {
                    return Optional.empty();
                }
                String ts = p.optString("timestamp", "");
                if (ts.isEmpty()) {
                    ts = Integer.toString(i);
                }
                xArr.put(ts);
                yArr.put(y);
            }
            if (xArr.length() == 0) {
                return Optional.empty();
            }

            String kind = root.optString("chart_kind", "line").trim().toLowerCase();
            if (!"line".equals(kind) && !"bar".equals(kind) && !"scatter".equals(kind)) {
                kind = "line";
            }

            JSONObject series = new JSONObject();
            series.put("name", propertyName.isEmpty() ? "series" : propertyName);
            series.put("x", xArr);
            series.put("y", yArr);
            JSONArray seriesArr = new JSONArray();
            seriesArr.put(series);

            JSONObject chart = new JSONObject();
            chart.put("kind", kind);
            String titleOverride = root.optString("chart_title", "").trim();
            if (!titleOverride.isEmpty()) {
                chart.put("title", titleOverride);
            } else if (thingName.isEmpty()) {
                chart.put("title", propertyName.isEmpty() ? "Trend" : propertyName);
            } else if (propertyName.isEmpty()) {
                chart.put("title", thingName);
            } else {
                chart.put("title", propertyName + " (" + thingName + ")");
            }
            String xLabel = root.optString("chart_x_label", "").trim();
            chart.put("x_label", xLabel.isEmpty() ? "Time" : xLabel);
            String yLabel = root.optString("chart_y_label", "").trim();
            chart.put("y_label", yLabel.isEmpty()
                    ? (propertyName.isEmpty() ? "Value" : propertyName)
                    : yLabel);
            chart.put("series", seriesArr);

            attachRequestedTimeRangeFromToolRoot(root, kind, chart);

            JSONArray yrl = root.optJSONArray("y_reference_lines");
            if (yrl != null && yrl.length() > 0) {
                JSONArray cleaned = new JSONArray();
                for (int i = 0; i < yrl.length() && cleaned.length() < 12; i++) {
                    JSONObject src = yrl.optJSONObject(i);
                    if (src == null || !src.has("y")) {
                        continue;
                    }
                    try {
                        double yy = src.getDouble("y");
                        if (Double.isNaN(yy) || Double.isInfinite(yy)) {
                            continue;
                        }
                        JSONObject one = new JSONObject();
                        one.put("y", yy);
                        String lab = src.optString("label", "").trim();
                        if (!lab.isEmpty()) {
                            one.put("label", lab);
                        }
                        String role = src.optString("role", "limit").trim().toLowerCase();
                        if (!REF_ROLES.contains(role)) {
                            role = "limit";
                        }
                        one.put("role", role);
                        cleaned.put(one);
                    } catch (Exception ignored) {
                        // skip invalid row
                    }
                }
                if (cleaned.length() > 0) {
                    chart.put("y_reference_lines", cleaned);
                }
            }

            if (assignNewChartId) {
                chart.put("chartId", AgentToolContext.nextParlerChartId());
            }

            return Optional.of(chart);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Reconstructs a numeric-history chart from compact Stream evidence that carries a persisted {@code chartBlock}.
     * Only rows that reported {@code chartEmitted=true} hydrate into UI history charts; aggregate-only numeric evidence
     * may still carry {@code chartBlock} for cache restore without displaying a chart that was not shown live.
     */
    public static Optional<JSONObject> chartBlockFromNumericCompactToolResult(
            String toolResultBody, boolean assignNewChartId) {
        if (toolResultBody == null || toolResultBody.isEmpty()) {
            return Optional.empty();
        }
        try {
            JSONObject root = new JSONObject(toolResultBody);
            if (!"success".equals(root.optString("status")) || !root.optBoolean("chartEmitted", false)) {
                return Optional.empty();
            }
            String kind = root.optString("resultKind", "");
            if (!"NUMERIC_HISTORY_INLINE".equals(kind) && !"NUMERIC_HISTORY_AGGREGATES".equals(kind)) {
                return Optional.empty();
            }
            JSONObject block = root.optJSONObject("chartBlock");
            if (block == null || !block.has("kind") || block.optJSONArray("series") == null
                    || block.getJSONArray("series").length() == 0) {
                return Optional.empty();
            }
            JSONObject chart = new JSONObject(block.toString());
            if (!assignNewChartId) {
                chart.remove("chartId");
            } else if (!chart.has("chartId") || chart.optString("chartId", "").isEmpty()) {
                chart.put("chartId", AgentToolContext.nextParlerChartId());
            }
            return Optional.of(chart);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Reconstructs a {@code ChartBlock} from persisted {@code build_chart_from_tabular_result} success JSON
     * ({@code code=CHART_EMITTED}, optional {@code chartBlock}) for history export
     * ({@link AgentMessageStreamHistoryExporter}).
     *
     * <p>The persisted {@code chartId} is always kept. A chart group's {@code ready} members refer to their
     * charts by that id (CHART_CONTRACT §2.6 / §3.5), so dropping it on replay leaves every exported member
     * unresolvable: the group card then shows a loading placeholder per member and the charts render outside it.
     * The numeric-history paths still omit the id on replay; their charts cannot be group members.</p>
     *
     * @param assignNewChartId when {@code true} and the persisted block carries no id, assigns the next live id;
     *        when {@code false} (history export) no id is invented.
     */
    public static Optional<JSONObject> chartBlockFromChartEmittedToolResult(
            String toolResultBody, boolean assignNewChartId) {
        if (toolResultBody == null || toolResultBody.isEmpty()) {
            return Optional.empty();
        }
        try {
            JSONObject root = new JSONObject(toolResultBody);
            if (!"success".equals(root.optString("status"))
                    || !"CHART_EMITTED".equals(root.optString("code"))) {
                return Optional.empty();
            }
            JSONObject block = root.optJSONObject("chartBlock");
            if (block == null) {
                return Optional.empty();
            }
            // Series kinds carry series[]; the C2b kinds carry a kind-specific payload object instead.
            boolean hasSeries = block.optJSONArray("series") != null && block.getJSONArray("series").length() > 0;
            String kind = block.optString("kind");
            boolean hasKindPayload = ("histogram".equals(kind) && block.optJSONObject("histogram") != null)
                    || ("boxplot".equals(kind) && block.optJSONObject("boxplot") != null)
                    || ("heatmap".equals(kind) && block.optJSONObject("heatmap") != null);
            if (!block.has("kind") || (!hasSeries && !hasKindPayload)) {
                return Optional.empty();
            }
            JSONObject chart = new JSONObject(block.toString());
            boolean hasId = !chart.optString("chartId", "").isEmpty();
            if (!hasId) {
                chart.remove("chartId");
                if (assignNewChartId) {
                    chart.put("chartId", AgentToolContext.nextParlerChartId());
                }
            }
            return Optional.of(chart);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
