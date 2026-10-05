package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.things.agent.ParlerProtectionAudit;
import com.thingworx.things.agent.ParlerTabularChartBuilder;
import com.thingworx.things.agent.ParlerTabularChartBuilder.BuildException;
import com.thingworx.things.agent.ParlerTabularChartBuilder.SeriesSpec;
import com.thingworx.things.agent.ParlerTabularChartIntentResolver;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.InfoTable;

import org.slf4j.Logger;

/**
 * Built-in {@code build_chart_from_tabular_result} — see Parler {@code docs/architecture/flexible-chart-solution.md}.
 * <p>
 * Per-turn {@code chartBuildAttemptedThisTurn} / recoverable-failure flags used for chart rescue and
 * {@code chartExpectedButMissing} telemetry are recorded in {@link com.thingworx.things.agent.AgentLoop} when this
 * tool executes and when recoverable error JSON (e.g. {@code DUPLICATE_SLICE_LABEL}) is returned — not in this class.
 */
public final class BuildChartFromTabularResultExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(BuildChartFromTabularResultExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BuildChartFromTabularResultExecutor() {}

    public static String execute(ToolCall call) {
        String memberKey = groupMemberKey(call);
        if (memberKey != null) {
            ChartGroupState group = AgentToolContext.tabularChartRoundState().getChartGroup();
            String reason = group == null ? "no_group_declared" : group.bindReason(memberKey);
            if (reason != null) {
                return errorJson("INVALID_PARAMETERS", "groupMemberKey \"" + memberKey + "\" cannot be bound (" + reason
                        + "): declare the group first and use each member key once.", new JSONObject().put("reason", reason));
            }
        }
        String out = executeUnbound(call);
        if (memberKey != null) {
            bindGroupMember(memberKey, out);
        }
        return out;
    }

    /** C3b-1: the optional {@code groupMemberKey} argument, or null when absent / not a string. */
    static String groupMemberKey(ToolCall call) {
        try {
            JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
            JsonNode k = root.get("groupMemberKey");
            if (k == null || !k.isTextual() || k.asText().trim().isEmpty()) {
                return null;
            }
            return k.asText().trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** C3b-1: after a bound build, the member owns the new chartId or converges to no-data / error. */
    static void bindGroupMember(String memberKey, String resultJson) {
        ChartGroupState group = AgentToolContext.tabularChartRoundState().getChartGroup();
        if (group == null) {
            return;
        }
        try {
            JSONObject r = new JSONObject(resultJson);
            String code = r.optString("code", "");
            if ("success".equals(r.optString("status")) && "CHART_EMITTED".equals(code)) {
                group.assignChartId(memberKey, r.optString("chartId", null));
                // C3b-2a (design §8.7): append this chart's category keys to the shared colour map and mark the
                // member colour-shared, so the mapping revision precedes this member's chart frame.
                group.bindMemberCategories(memberKey, r.optJSONObject("chartBlock"));
            } else {
                group.failMember(memberKey, code.isEmpty() ? "CHART_BUILD_INTERNAL" : code, r.optString("message", null));
            }
        } catch (Exception e) {
            group.failMember(memberKey, "CHART_BUILD_INTERNAL", e.getMessage());
        }
    }

    /** C3b-1: the agent loop blocked a bound build by the presentation budget before execution. */
    public static void noteBlockedBuild(ToolCall call, String code) {
        String memberKey = groupMemberKey(call);
        ChartGroupState group = AgentToolContext.tabularChartRoundState().getChartGroup();
        if (memberKey != null && group != null) {
            group.failMember(memberKey, code, null);
        }
    }

    private static String executeUnbound(ToolCall call) {
        try {
            return doExecute(call);
        } catch (BuildException e) {
            String hint = null;
            if ("DUPLICATE_SLICE_LABEL".equals(e.code)) {
                hint = "Group or aggregate by the pie xColumn first, then call build_chart_from_tabular_result on the "
                        + "aggregated table (use source cache_id with the aggregated result's top-level cacheId).";
            } else if ("SOURCE_SHAPE_MISMATCH".equals(e.code)) {
                hint = HISTOGRAM_RECOVERY_HINT;
            }
            return errorJson(e.code, e.getMessage(), e.details, hint);
        } catch (ArtifactCacheException e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            return errorJson(e.code().name(), e.getMessage(), null, null);
        } catch (Exception e) {
            LOG.warn("build_chart_from_tabular_result: {}", e.getMessage(), e);
            return errorJson("CHART_BUILD_INTERNAL", e.getMessage(), null, null);
        }
    }

    /** C2b-1 (design §7.4): how to obtain a valid histogram source; never correct the table by hand. */
    static final String HISTOGRAM_RECOVERY_HINT =
            "Run tabulate_cached_result with mode bin_numeric (column plus binEdges or binCount) on the numeric "
                    + "column first, then chart that result table with source cache_id (its cacheId) or last_invoke; "
                    + "do not pass column bindings and do not edit the table.";
    static final String DISTRIBUTION_RECOVERY_HINT =
            "Run tabulate_cached_result with mode bin_numeric (histogram) or box_summary (boxplot) on the numeric "
                    + "column first, then chart that result table with source cache_id.";
    /** C2b-2 (design §7.4): how to obtain a valid boxplot source; never correct the table by hand. */
    static final String BOXPLOT_RECOVERY_HINT =
            "Run tabulate_cached_result with mode box_summary (column, optional groupBy) on the numeric column first, "
                    + "then chart that result table with source cache_id (its cacheId) or last_invoke; do not pass "
                    + "column bindings and do not edit the table.";

    private static String doExecute(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());

        String source = TabularChartSourceResolver.text(root, "source");
        if (source == null || source.isBlank()) {
            return errorJson("MISSING_SOURCE", "source is required (last_invoke or cache_id).", null);
        }
        source = source.trim().toLowerCase(Locale.ROOT);
        if (!"last_invoke".equals(source) && !"cache_id".equals(source)) {
            return errorJson("MISSING_SOURCE", "source must be last_invoke or cache_id.", null);
        }

        String kindInput = TabularChartSourceResolver.text(root, "kind");
        String intentInput = TabularChartSourceResolver.text(root, "intent");
        boolean hasKind = kindInput != null && !kindInput.isBlank();
        boolean hasIntent = intentInput != null && !intentInput.isBlank();
        if (hasKind && hasIntent) {
            return errorJson("CHART_INVALID_PARAMETERS", "Supply either kind or intent, not both.", null);
        }
        if (!hasKind && !hasIntent) {
            return errorJson("MISSING_KIND_OR_INTENT",
                    "Supply kind (explicit mode) or intent (intent mode) together with source and xColumn.", null);
        }

        if (hasIntent) {
            String normIntentEarly = intentInput.trim().toLowerCase(Locale.ROOT);
            if (!ParlerTabularChartIntentResolver.isKnownIntentToken(normIntentEarly)) {
                JSONObject o = new JSONObject();
                o.put("status", "error");
                o.put("code", "INTENT_UNKNOWN");
                o.put("message",
                        "intent must be one of: time_trend, rank, compare_groups, correlation, distribution, status_timeline, composition");
                return o.toString();
            }
            String early = BuildChartFromTabularResultPrecheck.intentModeCacheShapeAndPhaseFallbackOrNull(root, normIntentEarly);
            if (early != null) {
                return early;
            }
        }

        // C2b-1/2 (design §7.4): histogram, boxplot, and intent distribution, chart an already summarised source
        // without column bindings, so they leave the column-mapping path here.
        String kindNormEarly = hasKind ? kindInput.trim().toLowerCase(Locale.ROOT) : null;
        String intentNormEarly = hasIntent ? intentInput.trim().toLowerCase(Locale.ROOT) : null;
        if ("histogram".equals(kindNormEarly) || "boxplot".equals(kindNormEarly)
                || "distribution".equals(intentNormEarly)) {
            return distributionChart(root, kindNormEarly, intentNormEarly);
        }
        if (root.has("histogramMode") && !root.get("histogramMode").isNull()) {
            return errorJson("INVALID_PARAMETERS",
                    "histogramMode applies only when kind is histogram (explicit or resolved from intent distribution).",
                    null);
        }

        String xCol = TabularChartSourceResolver.text(root, "xColumn");
        String yCol = TabularChartSourceResolver.text(root, "yColumn");
        List<SeriesSpec> multi = TabularChartSourceResolver.parseSeriesSpecs(root);

        boolean hasY = yCol != null && !yCol.isBlank();
        boolean hasSer = !multi.isEmpty();
        if (hasY && hasSer) {
            return errorJson("INVALID_MAPPING", "Provide either yColumn or series, not both.", null);
        }
        if (!hasY && !hasSer) {
            return errorJson("INVALID_MAPPING", "yColumn or non-empty series is required.", null);
        }
        JsonNode ser = root.get("series");
        if (hasSer && ser != null && ser.isArray() && ser.size() > 0 && multi.isEmpty()) {
            return errorJson("INVALID_MAPPING", "series array must contain objects with name and yColumn.", null);
        }

        JsonNode yRef = root.get("yReferenceLines");
        if (yRef != null && yRef.isArray() && yRef.size() > 12) {
            return errorJson("TOO_MANY_REFERENCE_LINES", "At most 12 yReferenceLines.", null);
        }

        String title = TabularChartSourceResolver.text(root, "title");
        String xLabel = TabularChartSourceResolver.text(root, "xLabel");
        String yLabel = TabularChartSourceResolver.text(root, "yLabel");
        String seriesColOpt = TabularChartSourceResolver.text(root, "seriesColumn");

        String[] err = new String[1];
        TabularChartSourceResolver.Resolved src = TabularChartSourceResolver.resolveOrError(root, err);
        if (src == null) {
            return err[0];
        }
        InfoTable table = src.table;
        String sourceResolved = src.sourceResolved;

        List<SeriesSpec> seriesArg = hasSer ? multi : null;
        String ySingle = hasY ? yCol.trim() : null;

        List<String> seriesYCols = new ArrayList<>();
        if (seriesArg != null) {
            for (SeriesSpec sp : seriesArg) {
                if (sp != null && sp.yColumn != null && !sp.yColumn.isBlank()) {
                    seriesYCols.add(sp.yColumn.trim());
                }
            }
        }
        DataShapeDefinition chartDs = chartShape(table);
        TabularPasswordColumnGuard.Violation cv = TabularPasswordColumnGuard.chartAxes(chartDs, xCol, ySingle, seriesYCols,
                seriesColOpt);
        if (cv != null) {
            ParlerProtectionAudit.blocked(ProtectedValuePolicy.CODE_TABULAR_PROTECTED_COLUMN,
                    "build_chart_from_tabular_result", "field=" + cv.field + " column=" + cv.columnName);
            return errorJson(ProtectedValuePolicy.CODE_TABULAR_PROTECTED_COLUMN,
                    "Cannot use PASSWORD column \"" + cv.columnName + "\" for " + cv.field + ".", null);
        }

        String kind;
        boolean intentMode = hasIntent;
        boolean sortBar = false;
        String requestedIntentNorm = null;
        if (intentMode) {
            requestedIntentNorm = intentInput.trim().toLowerCase(Locale.ROOT);
            ParlerTabularChartIntentResolver.IntentOutcome out =
                    ParlerTabularChartIntentResolver.resolve(requestedIntentNorm, table, xCol, ySingle, seriesArg);
            if (out.fallback) {
                JSONObject o = new JSONObject();
                o.put("status", "success");
                o.put("code", "CHART_FALLBACK");
                o.put("requestedIntent", requestedIntentNorm);
                o.put("fallback", true);
                o.put("fallbackReason", out.fallbackReason);
                return o.toString();
            }
            kind = out.kind;
            sortBar = out.sortBarPrimaryYDesc;
        } else {
            kind = kindInput.trim().toLowerCase(Locale.ROOT);
        }

        JsonNode requestedTimeRange = root.get("requestedTimeRange");

        String pieSliceMode = TabularChartSourceResolver.text(root, "pieSliceMode");
        JsonNode pieMaxNode = root.get("pieMaxSlices");
        boolean hasPieSliceMode = pieSliceMode != null && !pieSliceMode.isBlank();
        boolean hasPieMax = pieMaxNode != null && !pieMaxNode.isNull() && pieMaxNode.isIntegralNumber();
        if ((hasPieSliceMode || hasPieMax) && !"pie".equals(kind)) {
            return errorJson("INVALID_PARAMETERS", "pieSliceMode and pieMaxSlices apply only when kind is pie.", null);
        }
        if (seriesColOpt != null && !seriesColOpt.isBlank()
                && !"bar".equals(kind) && !"line".equals(kind) && !"scatter".equals(kind) && !"heatmap".equals(kind)) {
            return errorJson("INVALID_PARAMETERS",
                    "seriesColumn applies only when kind is bar, line, scatter, or heatmap.", null);
        }
        if ("heatmap".equals(kind) && yRef != null && !yRef.isNull()) {
            return errorJson("INVALID_PARAMETERS", "yReferenceLines are not supported on heatmap.", null);
        }
        int pieMaxSlices = -1;
        if (hasPieMax) {
            pieMaxSlices = pieMaxNode.asInt();
        }

        // C2a-1 (chart-enhancement design §7.3): bar-only orientation. A supplied field (any JSON
        // value, including null, blank or a case variant) must be exactly one of the two enum
        // strings and the resolved kind must be bar; only a missing field means the default.
        JsonNode orientationNode = root.get("orientation");
        String orientation = null;
        if (orientationNode != null) {
            String supplied = orientationNode.isTextual() ? orientationNode.asText() : null;
            if (!"vertical".equals(supplied) && !"horizontal".equals(supplied)) {
                return errorJson("INVALID_PARAMETERS",
                        "orientation must be exactly \"vertical\" or \"horizontal\" when supplied.", null);
            }
            if (!"bar".equals(kind)) {
                return errorJson("INVALID_PARAMETERS",
                        "orientation applies only when kind is bar (explicit or resolved from intent); resolved kind is "
                                + kind + ".", null);
            }
            orientation = supplied;
        }

        // C2a-2 (design §7.4): bar-only stackMode with the same strictness as orientation; grouped is the
        // default and is never written; stacking is only ever requested explicitly, never by intent.
        JsonNode stackNode = root.get("stackMode");
        String stackMode = null;
        if (stackNode != null) {
            String supplied = stackNode.isTextual() ? stackNode.asText() : null;
            if (supplied == null || !ParlerTabularChartBuilder.STACK_MODES.contains(supplied)) {
                return errorJson("INVALID_PARAMETERS",
                        "stackMode must be exactly \"grouped\", \"stacked\" or \"percent\" when supplied.", null);
            }
            if (!"bar".equals(kind)) {
                return errorJson("INVALID_PARAMETERS",
                        "stackMode applies only when kind is bar (explicit or resolved from intent); resolved kind is "
                                + kind + ".", null);
            }
            stackMode = supplied;
        }

        JSONObject chart = ParlerTabularChartBuilder.buildChartBlock(
                table, kind, xCol, ySingle, seriesArg, title, xLabel, yLabel, yRef, requestedTimeRange, sortBar,
                seriesColOpt, pieSliceMode, pieMaxSlices, orientation, stackMode);

        JSONObject chartSrc = chart.optJSONObject("source");
        if (chartSrc != null) {
            chartSrc.put("sourceResolved", sourceResolved);
            if (src.cacheId != null && !src.cacheId.isBlank()) {
                chartSrc.put("sourceCacheId", src.cacheId);
            }
        }

        int pointCount = chart.optJSONObject("source") != null
                ? chart.getJSONObject("source").optInt("pointCount", table.getRowCount())
                : table.getRowCount();
        int seriesCount = chart.optJSONArray("series") != null ? chart.getJSONArray("series").length()
                : chart.has("heatmap") ? 0 : (hasSer ? multi.size() : 1);

        String chartId = AgentToolContext.nextParlerChartId();
        chart.put("chartId", chartId);

        AgentToolContext.addPendingParlerChartBlock(chart);

        JSONObject ok = new JSONObject();
        ok.put("status", "success");
        ok.put("code", "CHART_EMITTED");
        ok.put("chartBlock", new JSONObject(chart.toString()));
        ok.put("chartId", chartId);
        ok.put("kind", chart.optString("kind", kind));
        if (chart.has("orientation")) {
            ok.put("orientation", chart.getString("orientation"));
        }
        if (chart.has("stackMode")) {
            ok.put("stackMode", chart.getString("stackMode"));
        }
        if (chart.has("heatmap")) {
            JSONObject heat = chart.getJSONObject("heatmap");
            ok.put("rowCount2d", heat.getJSONArray("rows").length());
            ok.put("colCount", heat.getJSONArray("cols").length());
            ok.put("missingCount", heat.getInt("missingCount"));
        }
        ok.put("seriesCount", seriesCount);
        ok.put("pointCount", pointCount);
        if (chartSrc != null) {
            JSONObject sourceCopy = new JSONObject(chartSrc.toString());
            ok.put("source", sourceCopy);
            boolean trunc = sourceCopy.optBoolean("truncationApplied", false);
            ok.put("truncationApplied", trunc);
            ok.put("truncated", trunc);
            ok.put("sourceResolved", sourceCopy.optString("sourceResolved", sourceResolved));
            if (sourceCopy.has("sourceCacheId") && !sourceCopy.isNull("sourceCacheId")) {
                String scid = sourceCopy.optString("sourceCacheId", "");
                if (!scid.isEmpty()) {
                    ok.put("sourceCacheId", scid);
                }
            }
            ok.put("sourceColumns", sourceCopy.getJSONArray("sourceColumns"));
            ok.put("rowCount", sourceCopy.optInt("rowCount", table.getRowCount()));
            int pc = sourceCopy.optInt("pointCount", pointCount);
            ok.put("pointCount", pc);
            if (sourceCopy.has("filledMissingCombinations")) {
                ok.put("filledMissingCombinations", sourceCopy.optInt("filledMissingCombinations"));
            }
            if (sourceCopy.has("zeroValueCategoryCount")) {
                ok.put("zeroValueCategoryCount", sourceCopy.optInt("zeroValueCategoryCount"));
            }
            if (sourceCopy.has("transformSummary") && !sourceCopy.isNull("transformSummary")) {
                String ts = sourceCopy.optString("transformSummary", "");
                if (!ts.isEmpty()) {
                    ok.put("transformSummary", ts);
                }
            }
        } else {
            ok.put("sourceResolved", sourceResolved);
            ok.put("truncated", false);
            ok.put("truncationApplied", false);
        }
        if (intentMode) {
            ok.put("requestedIntent", requestedIntentNorm);
            ok.put("selectedKind", kind);
            ok.put("fallback", false);
        }
        LOG.info("build_chart_from_tabular_result ok source={} kind={} points={} series={}",
                sourceResolved, kind, ok.optInt("pointCount", pointCount), seriesCount);
        return ok.toString();
    }

    /**
     * Distribution path (explicit {@code kind: histogram} / {@code kind: boxplot}, or {@code intent: distribution}):
     * the source must be a {@code bin_numeric} or {@code box_summary} result table; column bindings and the
     * bar/pie parameters are rejected. Reference lines are accepted on boxplot only; {@code histogramMode} on
     * histogram only. With {@code intent} the kind comes from the source shape.
     */
    private static String distributionChart(JsonNode root, String kindNorm, String intentNorm) throws Exception {
        String what = kindNorm != null ? kindNorm : "distribution";
        for (String p : new String[] {"xColumn", "yColumn", "series", "seriesColumn"}) {
            if (root.has(p) && !root.get(p).isNull()) {
                return errorJson("INVALID_PARAMETERS", "A " + what + " source is already summarised; do not pass " + p
                        + ". " + ("boxplot".equals(kindNorm) ? BOXPLOT_RECOVERY_HINT : DISTRIBUTION_RECOVERY_HINT), null);
            }
        }
        boolean hasRefs = root.has("yReferenceLines") && !root.get("yReferenceLines").isNull();
        if (hasRefs && "histogram".equals(kindNorm)) {
            return errorJson("INVALID_PARAMETERS", "yReferenceLines are not supported on histogram.", null);
        }
        if (hasRefs && root.get("yReferenceLines").isArray() && root.get("yReferenceLines").size() > 12) {
            return errorJson("TOO_MANY_REFERENCE_LINES", "At most 12 yReferenceLines.", null);
        }
        for (String p : new String[] {"pieSliceMode", "pieMaxSlices", "orientation", "requestedTimeRange"}) {
            if (root.has(p) && !root.get(p).isNull()) {
                return errorJson("INVALID_PARAMETERS", p + " does not apply to " + what + ".", null);
            }
        }
        JsonNode modeNode = root.get("histogramMode");
        if (modeNode != null && !modeNode.isNull() && "boxplot".equals(kindNorm)) {
            return errorJson("INVALID_PARAMETERS", "histogramMode applies only to histogram, not boxplot.", null);
        }
        String histogramMode = null;
        if (modeNode != null) {
            String supplied = modeNode.isTextual() ? modeNode.asText() : null;
            if (!"count".equals(supplied) && !"density".equals(supplied)) {
                return errorJson("INVALID_PARAMETERS",
                        "histogramMode must be exactly \"count\" or \"density\" when supplied.", null);
            }
            histogramMode = supplied;
        }
        String title = TabularChartSourceResolver.text(root, "title");
        String xLabel = TabularChartSourceResolver.text(root, "xLabel");
        String yLabel = TabularChartSourceResolver.text(root, "yLabel");
        String[] err = new String[1];
        TabularChartSourceResolver.Resolved src = TabularChartSourceResolver.resolveOrError(root, err);
        if (src == null) {
            return err[0];
        }
        InfoTable table = src.table;
        String kind = kindNorm != null ? kindNorm : "histogram";
        if (intentNorm != null) {
            ParlerTabularChartIntentResolver.IntentOutcome out =
                    ParlerTabularChartIntentResolver.resolve(intentNorm, table, null, null, null);
            if (out.errorCode != null) {
                return errorJson(out.errorCode,
                        "intent distribution needs a binned source: the table carries neither bin_numeric columns "
                                + "(histogram) nor box_summary columns (boxplot).", null, DISTRIBUTION_RECOVERY_HINT);
            }
            if (out.fallback) {
                JSONObject o = new JSONObject();
                o.put("status", "success");
                o.put("code", "CHART_FALLBACK");
                o.put("requestedIntent", intentNorm);
                o.put("fallback", true);
                o.put("fallbackReason", out.fallbackReason);
                return o.toString();
            }
            kind = out.kind;
        }
        if (!"histogram".equals(kind) && !"boxplot".equals(kind)) {
            return errorJson("UNSUPPORTED_CHART_KIND", "kind " + kind + " is not available yet.", null);
        }
        if ("boxplot".equals(kind) && histogramMode != null) {
            return errorJson("INVALID_PARAMETERS", "histogramMode applies only to histogram, not boxplot.", null);
        }
        if ("histogram".equals(kind) && hasRefs) {
            return errorJson("INVALID_PARAMETERS", "yReferenceLines are not supported on histogram.", null);
        }
        JSONObject chart;
        try {
            chart = "boxplot".equals(kind)
                    ? ParlerTabularChartBuilder.buildBoxplotChartBlock(table, title, xLabel, yLabel, root.get("yReferenceLines"))
                    : ParlerTabularChartBuilder.buildHistogramChartBlock(table, histogramMode, title, xLabel, yLabel);
        } catch (BuildException e) {
            if ("SOURCE_SHAPE_MISMATCH".equals(e.code)) {
                return errorJson(e.code, e.getMessage(), e.details,
                        "boxplot".equals(kind) ? BOXPLOT_RECOVERY_HINT : HISTOGRAM_RECOVERY_HINT);
            }
            throw e;
        }
        JSONObject chartSrc = chart.optJSONObject("source");
        if (chartSrc != null) {
            chartSrc.put("sourceResolved", src.sourceResolved);
            if (src.cacheId != null && !src.cacheId.isBlank()) {
                chartSrc.put("sourceCacheId", src.cacheId);
            }
        }
        int pointCount = chartSrc != null ? chartSrc.optInt("pointCount", table.getRowCount()) : table.getRowCount();
        String chartId = AgentToolContext.nextParlerChartId();
        chart.put("chartId", chartId);
        AgentToolContext.addPendingParlerChartBlock(chart);
        JSONObject ok = new JSONObject();
        ok.put("status", "success");
        ok.put("code", "CHART_EMITTED");
        ok.put("chartBlock", new JSONObject(chart.toString()));
        ok.put("chartId", chartId);
        ok.put("kind", kind);
        if (chart.has("histogram")) {
            ok.put("histogramMode", chart.getJSONObject("histogram").getString("mode"));
        } else {
            ok.put("groupCount", chart.getJSONObject("boxplot").getJSONArray("groups").length());
        }
        ok.put("seriesCount", 0);
        ok.put("pointCount", pointCount);
        if (chartSrc != null) {
            JSONObject sourceCopy = new JSONObject(chartSrc.toString());
            ok.put("source", sourceCopy);
            ok.put("truncationApplied", false);
            ok.put("truncated", false);
            ok.put("sourceResolved", sourceCopy.optString("sourceResolved", src.sourceResolved));
            if (sourceCopy.has("sourceCacheId")) {
                ok.put("sourceCacheId", sourceCopy.optString("sourceCacheId", ""));
            }
            ok.put("sourceColumns", sourceCopy.getJSONArray("sourceColumns"));
            ok.put("rowCount", sourceCopy.optInt("rowCount", table.getRowCount()));
            ok.put("transformSummary", sourceCopy.optString("transformSummary", kind + "(...)"));
        }
        if (intentNorm != null) {
            ok.put("requestedIntent", intentNorm);
            ok.put("selectedKind", kind);
            ok.put("fallback", false);
        }
        LOG.info("build_chart_from_tabular_result ok source={} kind={} groups={}", src.sourceResolved, kind, pointCount);
        return ok.toString();
    }

    private static DataShapeDefinition chartShape(InfoTable table) {
        try {
            return table != null ? table.getDataShape() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorJson(String code, String message, JSONObject details) {
        return errorJson(code, message, details, null);
    }

    private static String errorJson(String code, String message, JSONObject details, String recoveryHint) {
        JSONObject o = new JSONObject();
        o.put("status", "error");
        o.put("code", code);
        o.put("message", message != null ? message : "");
        if (details != null) {
            o.put("details", details);
        }
        if (recoveryHint != null && !recoveryHint.isBlank()) {
            o.put("recoveryHint", recoveryHint.trim());
        }
        return o.toString();
    }
}
