package com.thingworx.things.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON Schema fragment for {@code build_chart_from_tabular_result} parameters (provider-safe root
 * {@code type: object}; see {@code docs/agent/chart-intent.md}). Extracted so unit tests can
 * validate the shape without loading {@link BuiltInTools}.
 */
public final class BuildChartFromTabularResultToolSchema {

    private BuildChartFromTabularResultToolSchema() {}

    public static Map<String, Object> parametersSchema() {
        Map<String, Object> kindField = Map.of("type", "string",
                "enum", java.util.List.of("line", "bar", "scatter", "pie", "histogram", "boxplot", "heatmap"),
                "description", "Explicit chart kind (line|bar|scatter|pie|histogram|boxplot|heatmap). histogram takes a "
                        + "bin_numeric result table and boxplot a box_summary result table as its source, with no column "
                        + "bindings. heatmap takes a long table (e.g. group_metric with two groupBy keys) with xColumn = "
                        + "column dimension, seriesColumn = row dimension, yColumn = cell value; missing combinations "
                        + "stay empty cells. Optional when using intent instead. "
                        + "Mutually exclusive with intent — supply exactly one of kind or intent; runtime rejects both or neither.");
        Map<String, Object> intentField = Map.of("type", "string",
                "enum", java.util.List.of(
                        "time_trend", "rank", "compare_groups", "correlation",
                        "distribution", "status_timeline", "composition"),
                "description", "Visual intent; server selects line|bar|scatter (or CHART_FALLBACK for phase-only intents). "
                        + "Optional when using explicit kind instead. Mutually exclusive with kind.");

        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("source", Map.of("type", "string",
                "description", "last_invoke — latest qualifying tabular result this turn (latest-wins). "
                        + "cache_id — chart a prior table via top-level cacheId (same cache as fetch_cached_result). "
                        + "Multiple charts in one turn: cache_id + each result's cacheId."));
        shared.put("cacheId", Map.of("type", "string",
                "description", "Required when source=cache_id: cacheId from a prior tabular tool envelope — not tool call ids."));
        shared.put("xColumn", Map.of("type", "string",
                "description", "Column name for X (category for bar; numeric or ISO time for line/scatter; the column "
                        + "dimension for heatmap). Required for line, bar, scatter, pie and heatmap; must be omitted for "
                        + "histogram and boxplot (and for intent distribution), whose source table already carries the "
                        + "bins or the box statistics."));
        shared.put("yColumn", Map.of("type", "string",
                "description", "Column name for Y (single series). Omit when using series[]."));
        shared.put("series", Map.of(
                "type", "array",
                "description", "Multi-series: [{ name, yColumn }], shared xColumn. Mutually exclusive with yColumn.",
                "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "yColumn", Map.of("type", "string")),
                        "required", new String[]{"name", "yColumn"})));
        shared.put("title", Map.of("type", "string"));
        shared.put("xLabel", Map.of("type", "string"));
        shared.put("yLabel", Map.of("type", "string"));
        Map<String, Object> yRefRole = Map.of(
                "type", "string",
                "enum", java.util.List.of("usl", "ucl", "lcl", "lsl", "target", "limit", "warning"),
                "description", "Optional line role (SPC / threshold).");
        Map<String, Object> yRefProps = new LinkedHashMap<>();
        yRefProps.put("y", Map.of("type", "number"));
        yRefProps.put("label", Map.of("type", "string"));
        yRefProps.put("role", yRefRole);
        Map<String, Object> yRefItem = new LinkedHashMap<>();
        yRefItem.put("type", "object");
        yRefItem.put("properties", yRefProps);
        yRefItem.put("required", new String[]{"y"});
        Map<String, Object> yRefArr = new LinkedHashMap<>();
        yRefArr.put("type", "array");
        yRefArr.put("maxItems", 12);
        yRefArr.put("description", "Horizontal lines (max 12): { y, label?, role? }. Allowed on line, bar, scatter "
                + "and boxplot; INVALID_PARAMETERS on histogram and heatmap.");
        yRefArr.put("items", yRefItem);
        shared.put("yReferenceLines", yRefArr);
        Map<String, Object> requestedTimeRange = new LinkedHashMap<>();
        requestedTimeRange.put("type", "object");
        requestedTimeRange.put("description",
                "Optional ISO-8601 bounds for time line/scatter X domain (tabular charts).");
        requestedTimeRange.put("properties", Map.of(
                "start", Map.of("type", "string",
                        "description", "Inclusive range start (ISO-8601). Alias key: startTime."),
                "end", Map.of("type", "string",
                        "description", "Exclusive or inclusive range end per chart contract (ISO-8601). "
                                + "Alias key: endTime.")));
        shared.put("requestedTimeRange", requestedTimeRange);
        shared.put("seriesColumn", Map.of("type", "string",
                "description", "Optional for kind bar, line, or scatter: long-format column whose distinct values "
                        + "become multiple series (grouped bar or line/scatter pivot). Required for kind heatmap: the "
                        + "row dimension (xColumn is the column dimension). Mutually exclusive with series[]. Requires "
                        + "yColumn."));
        shared.put("pieSliceMode", Map.of("type", "string",
                "enum", java.util.List.of("top_with_other", "all_nonzero"),
                "description", "Optional for kind pie: slice policy (default top_with_other). Ignored for other kinds."));
        shared.put("pieMaxSlices", Map.of("type", "integer", "minimum", 2, "maximum", 12,
                "description", "Optional for kind pie: max slices 2..12 (default 8). Ignored for other kinds."));
        shared.put("histogramMode", Map.of("type", "string",
                "enum", java.util.List.of("count", "density"),
                "description", "Optional, only when the explicit or intent-resolved kind is histogram: which bin value "
                        + "the bar height shows (default count). Use density to compare distribution shape when bins "
                        + "have unequal widths. Rejected (INVALID_PARAMETERS) for other kinds or other values."));
        shared.put("orientation", Map.of("type", "string",
                "enum", java.util.List.of("vertical", "horizontal"),
                "description", "bar only (default vertical). Use horizontal only when the user explicitly asks; "
                        + "INVALID_PARAMETERS for other kinds."));
        shared.put("groupMemberKey", Map.of("type", "string",
                "description", "Optional: the member key of the chart group declared this turn with declare_chart_group; "
                        + "binds this chart to that slot (each key once). INVALID_PARAMETERS when no group is declared, "
                        + "the key is unknown, or the member is already filled."));
        shared.put("stackMode", Map.of("type", "string",
                "enum", java.util.List.of("grouped", "stacked", "percent"),
                "description", "bar only, with at least two series (seriesColumn or series[]); default grouped. "
                        + "stacked accumulates positive and negative values separately; percent shows each series' "
                        + "share of the category total and needs non-negative values (STACK_PERCENT_NEGATIVE). Use "
                        + "only when the user explicitly asks for a stacked or 100% chart; INVALID_PARAMETERS for "
                        + "other kinds or a single series. May be combined with orientation."));

        Map<String, Object> props = new LinkedHashMap<>(shared);
        props.put("kind", kindField);
        props.put("intent", intentField);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        // C2b-1/2: histogram and boxplot (and intent distribution) sources carry their own statistics, so xColumn
        // is required by the per-kind rules (INVALID_MAPPING for line/bar/scatter/pie) rather than by the root schema.
        schema.put("required", new String[]{"source"});
        return schema;
    }
}
