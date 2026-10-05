package com.thingworx.things.agent.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.logging.LogUtilities;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.HistoryOverlayChartBuilder;
import com.thingworx.things.agent.HistoryOverlayReferenceLines;
import com.thingworx.things.agent.HistorySeriesComposerSupport;
import com.thingworx.things.agent.PeriodOverPeriodPopSupport;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;

import org.slf4j.Logger;

/**
 * Built-in {@code build_history_overlay_chart} — {@code docs/agent/history-overlay-chart.md}.
 */
public final class BuildHistoryOverlayChartExecutor {

    private static final Logger LOG =
            LogUtilities.getInstance().getApplicationLogger(BuildHistoryOverlayChartExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BuildHistoryOverlayChartExecutor() {}

    public static String execute(ToolCall call) {
        try {
            return doExecute(call);
        } catch (HistoryOverlayChartBuilder.BuildException e) {
            return errorJson(e.code, e.getMessage());
        } catch (HistoryOverlayReferenceLines.ParseException e) {
            return errorJson(e.code, e.getMessage());
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("build_history_overlay_chart: {}", e.getMessage(), e);
            return errorJson("HISTORY_OVERLAY_INTERNAL", e.getMessage());
        }
    }

    private static String doExecute(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());

        String propertyName = text(root, "propertyName");
        if (propertyName == null || propertyName.isBlank()) {
            return errorJson("HISTORY_OVERLAY_MISSING_PROPERTY", "propertyName is required.");
        }
        propertyName = propertyName.trim();

        JsonNode seriesNode = root.get("series");
        if (seriesNode == null || !seriesNode.isArray() || seriesNode.size() < 2) {
            return errorJson("HISTORY_OVERLAY_TOO_FEW_SERIES",
                    "series must be an array with at least two entries.");
        }
        if (seriesNode.size() > HistoryOverlayChartBuilder.HISTORY_OVERLAY_MAX_SERIES) {
            return errorJson("HISTORY_OVERLAY_TOO_MANY_SERIES",
                    "At most " + HistoryOverlayChartBuilder.HISTORY_OVERLAY_MAX_SERIES + " series allowed.");
        }

        Instant anchor = Instant.now();
        String anchorStr = firstNonBlank(text(root, "anchorTime"), text(root, "anchorInstant"));
        if (anchorStr != null && !anchorStr.isBlank()) {
            try {
                anchor = Instant.parse(anchorStr.trim());
            } catch (Exception e) {
                return errorJson(HistoryOverlayToolErrors.INVALID_ANCHOR_TIME,
                        "anchorTime must be a valid ISO-8601 instant.");
            }
        }

        JSONArray yReferenceLines = HistoryOverlayReferenceLines.parseStrict(root.get("yReferenceLines"));

        Map<String, Thing> thingByName = new LinkedHashMap<>();
        Map<String, String> unitByThing = new LinkedHashMap<>();
        List<HistoryOverlayChartBuilder.ResolvedSeriesInput> resolved = new ArrayList<>();

        for (JsonNode sNode : seriesNode) {
            if (sNode == null || !sNode.isObject()) {
                return errorJson(HistoryOverlayToolErrors.INVALID_SERIES_ENTRY,
                        "Each series entry must be an object.");
            }
            String label = text(sNode, "label");
            if (label == null || label.isBlank()) {
                return errorJson(HistoryOverlayToolErrors.MISSING_SERIES_LABEL,
                        "Each series requires a non-empty label.");
            }
            String thingName = text(sNode, "thingName");
            if (thingName == null || thingName.isBlank()) {
                return errorJson("HISTORY_OVERLAY_MISSING_THING", "Each series requires thingName.");
            }
            thingName = thingName.trim();

            Thing thing = thingByName.get(thingName);
            if (thing == null) {
                ScalarThingnamePreflight.ApplicationThingGateOutcome gate =
                        ScalarThingnamePreflight.gateApplicationThing("series[].thingName", thingName);
                if (gate.isError()) {
                    return gate.errorJson;
                }
                if (!PropertyToolsExecutor.isChartableNumericProperty(gate.thing, propertyName)) {
                    return errorJson("HISTORY_OVERLAY_PROPERTY_NOT_NUMERIC",
                            "Property \"" + propertyName + "\" is not chartable on " + gate.canonicalThingName + ".");
                }
                thing = gate.thing;
                thingByName.put(gate.canonicalThingName, thing);
                thingName = gate.canonicalThingName;
                unitByThing.putIfAbsent(thingName,
                        PropertyToolsExecutor.readPropertyUnitAspect(thing, propertyName));
            }

            PeriodOverPeriodPeriodResolver.Outcome window = PeriodOverPeriodPeriodResolver.resolve(sNode, anchor);
            if (window.isError()) {
                return HistoryOverlayToolErrors.windowResolutionError(window);
            }

            resolved.add(new HistoryOverlayChartBuilder.ResolvedSeriesInput(
                    label.trim(), thingName, propertyName, window.startUtc, window.endUtc,
                    window.resolvedTimeZone, List.of()));
        }

        String unitErr = PeriodOverPeriodPopSupport.validateUnitCompatibility(unitByThing);
        if (unitErr != null) {
            return errorJson("HISTORY_OVERLAY_UNIT_MISMATCH", unitErr);
        }

        List<HistoryOverlayChartBuilder.ResolvedSeriesInput> withHistory = new ArrayList<>();
        for (HistoryOverlayChartBuilder.ResolvedSeriesInput s : resolved) {
            Thing thing = thingByName.get(s.thingName);
            HistorySeriesComposerSupport.FetchOutcome extract =
                    HistorySeriesComposerSupport.fetchNumericHistory(thing, propertyName, s.windowStart, s.windowEnd);
            if (extract.isError()) {
                return errorJson(
                        HistorySeriesComposerSupport.overlayErrorCode(extract.failureReason),
                        extract.errorMessage);
            }
            withHistory.add(new HistoryOverlayChartBuilder.ResolvedSeriesInput(
                    s.label, s.thingName, s.propertyName, s.windowStart, s.windowEnd, s.resolvedTimeZone,
                    extract.points, extract.readLimit));
        }

        String xAxisMode = text(root, "xAxisMode");
        String chartKind = firstNonBlank(text(root, "chart_kind"), text(root, "chartKind"));
        String title = text(root, "title");
        String yLabel = text(root, "yLabel");
        // S4: store seriesCaches only after chart build succeeds (no partial artifact on fault).
        return finishAfterFetchedSeries(withHistory, xAxisMode, chartKind, title, yLabel, yReferenceLines);
    }

    /**
     * Package-visible post-fetch path: validate/build chart first, then publish {@code seriesCaches}
     * only on {@code CHART_EMITTED} success.
     */
    static String finishAfterFetchedSeries(
            List<HistoryOverlayChartBuilder.ResolvedSeriesInput> withHistory,
            String xAxisMode,
            String chartKind,
            String title,
            String yLabel,
            JSONArray yReferenceLines) throws Exception {
        JSONObject chart = HistoryOverlayChartBuilder.buildChart(
                xAxisMode, chartKind, withHistory, title, yLabel, yReferenceLines);

        JSONArray seriesCaches = publishSeriesCaches(withHistory);

        String chartId = AgentToolContext.nextParlerChartId();
        chart.put("chartId", chartId);
        AgentToolContext.addPendingParlerChartBlock(chart);

        JSONObject ok = new JSONObject();
        ok.put("status", "success");
        ok.put("code", "CHART_EMITTED");
        ok.put("chartBlock", new JSONObject(chart.toString()));
        ok.put("chartId", chartId);
        ok.put("kind", chart.optString("kind", "line"));
        JSONObject source = chart.getJSONObject("source");
        ok.put("source", new JSONObject(source.toString()));
        ok.put("sourceResolved", "history_overlay");
        ok.put("pointCount", source.optInt("pointCount"));
        ok.put("rowCount", source.optInt("rowCount"));
        boolean trunc = source.optBoolean("truncationApplied", false);
        ok.put("truncationApplied", trunc);
        ok.put("truncated", trunc);
        if (source.has("missingPeriods")) {
            ok.put("missingPeriods", source.getJSONArray("missingPeriods"));
        }
        if (source.has("missingSeries")) {
            ok.put("missingSeries", source.getJSONArray("missingSeries"));
        }
        if (seriesCaches.length() > 0) {
            ok.put("seriesCaches", seriesCaches);
        }
        putReadLimitEvidence(ok, withHistory);
        LOG.info("build_history_overlay_chart ok property={} series={} points={} xAxisMode={}",
                withHistory.isEmpty() ? "" : withHistory.get(0).propertyName,
                chart.getJSONArray("series").length(), source.optInt("pointCount"),
                chart.optString("xAxisMode", "absolute"));
        return ok.toString();
    }

    /**
     * Echo of the overlay's fixed per-series read limit, and the hint when any series' own read reached it. The
     * hint lists the series in its own field, because only those may be incomplete, and claims no common row
     * count. It is written on the tool result whether or not that series was cached, so it never depends on a
     * store succeeding.
     */
    static void putReadLimitEvidence(JSONObject ok, List<HistoryOverlayChartBuilder.ResolvedSeriesInput> withHistory) {
        ok.put("maxItemsRequested", HistorySeriesComposerSupport.MAX_HISTORY_ROWS);
        ok.put("maxItemsEffective", HistorySeriesComposerSupport.MAX_HISTORY_ROWS);
        List<String> labels = new ArrayList<>();
        for (HistoryOverlayChartBuilder.ResolvedSeriesInput s : withHistory) {
            if (s != null && s.readLimit.reached()) {
                labels.add(s.label);
            }
        }
        if (labels.isEmpty()) {
            return;
        }
        ok.put(com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED, true);
        ok.put(com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED_SERIES, new JSONArray(labels));
        ok.put(com.thingworx.things.agent.source.ReadLimitFact.FIELD_NOTE,
                com.thingworx.things.agent.source.ReadLimitFact.seriesNote(
                        HistorySeriesComposerSupport.MAX_HISTORY_ROWS));
    }

    /** Publish per-series caches only for non-empty fetched point lists (soft-skip store failures). */
    static JSONArray publishSeriesCaches(List<HistoryOverlayChartBuilder.ResolvedSeriesInput> withHistory) {
        JSONArray seriesCaches = new JSONArray();
        if (withHistory == null) {
            return seriesCaches;
        }
        for (HistoryOverlayChartBuilder.ResolvedSeriesInput s : withHistory) {
            if (s == null || s.points == null || s.points.isEmpty()) {
                continue;
            }
            // CM-4: identity comes from the resolved series input, never from a later request.
            StoredSeriesCache stored = HistorySeriesComposerSupport.storeNumericHistoryPointsInConversationCache(
                    s.points, s.thingName, s.propertyName, s.readLimit);
            if (stored == null) {
                continue;
            }
            JSONObject entry = new JSONObject();
            entry.put("label", s.label);
            entry.put("thingName", s.thingName);
            entry.put("propertyName", s.propertyName);
            entry.put("cacheId", stored.cacheId());
            entry.put("totalRows", s.points.size());
            if (s.readLimit.reached()) {
                entry.put(com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED, true);
            }
            // CM-1: real written columns and writer-declared roles from the same store, plus the
            // resolved request window (not coverage; completeness stays UNKNOWN).
            JSONArray columns = new JSONArray();
            for (com.thingworx.things.agent.cache.TypedColumn c : stored.columns()) {
                JSONObject col = new JSONObject();
                col.put("name", c.name());
                col.put("baseType", c.baseType().name());
                columns.put(col);
            }
            entry.put("columns", columns);
            if (stored.hasRoles()) {
                entry.put("timeColumn", stored.timeColumn());
                entry.put("valueColumn", stored.valueColumn());
            }
            if (s.windowStart != null) {
                entry.put("windowStart", s.windowStart.toString());
            }
            if (s.windowEnd != null) {
                entry.put("windowEnd", s.windowEnd.toString());
            }
            if (s.resolvedTimeZone != null && !s.resolvedTimeZone.isBlank()) {
                entry.put("resolvedTimeZone", s.resolvedTimeZone);
            }
            seriesCaches.put(entry);
        }
        return seriesCaches;
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        return n.asText();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private static String errorJson(String code, String message) {
        JSONObject o = new JSONObject();
        o.put("status", "error");
        o.put("code", code);
        o.put("message", message != null ? message : "");
        return o.toString();
    }
}
