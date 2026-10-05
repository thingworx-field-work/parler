package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.thingworx.things.agent.analysis.ComputingOperationAdmission;
import com.thingworx.things.agent.join.U4OperationAdmission;

/**
 * JSON parameters schema for {@code tabulate_cached_result} (Azure / OpenAI strict mode requires every
 * {@code type: array} to declare {@code items}; see {@link com.thingworx.things.agent.llm.LlmJsonSchemaCompat}).
 *
 * <p>Canonical argument shape matches {@code docs/agent/query-spec.md}: ThingWorx-style {@code filters},
 * {@code sorts}, {@code maxItems}, {@code offset}, optional {@code fields}. Legacy root keys
 * {@code where}, {@code sort}, {@code limit}, {@code sortBy}, {@code direction} and measure-level {@code where}
 * are rejected at runtime — do not use them in tool calls.</p>
 *
 * <p>U4 governed modes ({@code exact_join}, {@code quality}, {@code resample}) are advertised only
 * while the matching {@link U4OperationAdmission} flag is enabled (TQJ-5 / B9 / §12).</p>
 */
public final class TabulateCachedResultToolSchema {

    /** Advertised decision/transform modes (always present). */
    public static final String MODE_UNION_ROWS = "union_rows";

    public static final List<String> BASE_MODES = List.of(
            "filter_count", "filter_rows", "filter_sort_topn", "group_metric",
            CachedTabularDistributionExecutor.MODE_BIN_NUMERIC, CachedTabularDistributionExecutor.MODE_BOX_SUMMARY,
            MODE_UNION_ROWS);

    /** U4 governed sub-operations on this tool (TQJ-5 Option A). */
    public static final String MODE_EXACT_JOIN = "exact_join";
    public static final String MODE_QUALITY = "quality";
    public static final String MODE_RESAMPLE = "resample";
    public static final String MODE_ROLLING = "rolling";
    public static final String MODE_RATE_OF_CHANGE = "rate_of_change";
    public static final String MODE_PERIOD_COMPARE = "period_compare";
    /** Computing-enhancement CF-05: counter increments between real readings. */
    public static final String MODE_COUNTER_DELTA = "counter_delta";
    /** Computing-enhancement CF-52: one statistic over record-anchored rolling windows. */
    public static final String MODE_ROLLING_STATS = "rolling_stats";
    /** Computing-enhancement CF-01: time-weighted integral and mean of one property-history series. */
    public static final String MODE_TIME_WEIGHTED = "time_weighted";
    /** Computing-enhancement CF-03: local calendar day/hour columns per row. Not a single-window series mode. */
    public static final String MODE_CALENDAR_BUCKET = "calendar_bucket";

    /**
     * U4 series modes that share {@code timeColumn} / {@code valueColumn} / single-window props.
     * Descriptions are derived from advertisement (design §12). {@link #MODE_PERIOD_COMPARE} uses
     * dual windows and is advertised separately.
     */
    public static final List<String> SERIES_MODES = List.of(
            MODE_QUALITY, MODE_RESAMPLE, MODE_ROLLING, MODE_RATE_OF_CHANGE, MODE_COUNTER_DELTA,
            MODE_ROLLING_STATS, MODE_TIME_WEIGHTED);

    private TabulateCachedResultToolSchema() {}

    /** Model-visible {@code mode} enum for the current admission state. */
    public static List<String> advertisedModes() {
        List<String> modes = new ArrayList<>(BASE_MODES);
        if (U4OperationAdmission.exactJoinEnabled()) {
            modes.add(MODE_EXACT_JOIN);
        }
        if (U4OperationAdmission.qualityEnabled()) {
            modes.add(MODE_QUALITY);
        }
        if (U4OperationAdmission.resampleEnabled()) {
            modes.add(MODE_RESAMPLE);
        }
        if (U4OperationAdmission.rollingEnabled()) {
            modes.add(MODE_ROLLING);
        }
        if (U4OperationAdmission.rateOfChangeEnabled()) {
            modes.add(MODE_RATE_OF_CHANGE);
        }
        if (U4OperationAdmission.periodCompareEnabled()) {
            modes.add(MODE_PERIOD_COMPARE);
        }
        if (ComputingOperationAdmission.counterDeltaEnabled()) {
            modes.add(MODE_COUNTER_DELTA);
        }
        if (ComputingOperationAdmission.rollingStatsEnabled()) {
            modes.add(MODE_ROLLING_STATS);
        }
        if (ComputingOperationAdmission.timeWeightedEnabled()) {
            modes.add(MODE_TIME_WEIGHTED);
        }
        if (ComputingOperationAdmission.calendarBucketEnabled()) {
            modes.add(MODE_CALENDAR_BUCKET);
        }
        return List.copyOf(modes);
    }

    /** Currently advertised members of {@link #SERIES_MODES}. */
    public static List<String> advertisedSeriesModes() {
        List<String> advertised = advertisedModes();
        List<String> series = new ArrayList<>();
        for (String mode : SERIES_MODES) {
            if (advertised.contains(mode)) {
                series.add(mode);
            }
        }
        return List.copyOf(series);
    }

    /**
     * Label for shared series-arg descriptions, e.g. {@code mode=quality / mode=resample} or
     * {@code mode=resample} alone — never names a disabled mode.
     */
    static String seriesModeLabel(List<String> advertisedSeriesModes) {
        if (advertisedSeriesModes == null || advertisedSeriesModes.isEmpty()) {
            return "";
        }
        return advertisedSeriesModes.stream()
                .map(m -> "mode=" + m)
                .collect(Collectors.joining(" / "));
    }

    private static final String TWX_FILTER_BRIEF =
            "ThingWorx-shaped Query dialect (query-spec §3): leaf \"type\" (EQ, NE, LT, LE, GT, GE, LIKE, IN, BETWEEN, …), "
                    + "\"fieldName\", type keys \"value\", \"from\"/\"to\", \"values\"; "
                    + "composites {\"type\":\"AND\",\"filters\":[...]} (OR/NOT similarly); optional \"isCaseSensitive\".";

    /** Root JSON Schema object ({@code type: object}) for tool parameters. */
    public static Map<String, Object> parametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("cacheId", Map.of("type", "string",
                "description", "Source cacheId — entire cached InfoTable is transformed. "
                        + "May be __PARLER_LAST_QUALIFYING_TABULAR_CACHE__ (see docs/agent/p2_last_tabular_cache.md). "
                        + "Omit for mode=union_rows (use sourceCacheIds instead)."));
        Map<String, Object> sourceCacheIdsArr = new LinkedHashMap<>();
        sourceCacheIdsArr.put("type", "array");
        sourceCacheIdsArr.put("items", Map.of("type", "string"));
        sourceCacheIdsArr.put("description",
                "Required for mode=union_rows: 2–31 conversation cacheIds to append in order.");
        props.put("sourceCacheIds", sourceCacheIdsArr);
        Map<String, Object> labelValuesArr = new LinkedHashMap<>();
        labelValuesArr.put("type", "array");
        labelValuesArr.put("items", Map.of("type", "string"));
        labelValuesArr.put("description",
                "Optional for mode=union_rows: one label value per sourceCacheIds entry (same length when present).");
        props.put("labelValues", labelValuesArr);
        props.put("labelColumn", Map.of("type", "string",
                "description", "Required for mode=union_rows: name of the appended label column (must not exist in "
                        + "the input tables)."));
        // B16: sort_topn / group_count / group_aggregate unpublished from model-visible enum; executor
        // retains them as replay aliases → filter_sort_topn / group_metric for the compatibility window.
        // TQJ-5: U4 modes advertised only while their admission flags are enabled.
        List<String> modes = advertisedModes();
        List<String> seriesModes = advertisedSeriesModes();
        boolean exactJoinAdvertised = modes.contains(MODE_EXACT_JOIN);
        boolean periodCompareAdvertised = modes.contains(MODE_PERIOD_COMPARE);
        boolean resampleAdvertised = modes.contains(MODE_RESAMPLE);
        boolean rollingAdvertised = modes.contains(MODE_ROLLING);
        boolean anyU4 = exactJoinAdvertised || periodCompareAdvertised || !seriesModes.isEmpty();
        String seriesLabel = seriesModeLabel(seriesModes);
        // timeColumn/valueColumn shared by single-window series modes AND period_compare
        List<String> timeValueModes = new ArrayList<>(seriesModes);
        if (periodCompareAdvertised) {
            timeValueModes.add(MODE_PERIOD_COMPARE);
        }
        String timeValueLabel = seriesModeLabel(timeValueModes);
        StringBuilder modeDesc = new StringBuilder(
                "filter_count: required filters; optional groupBy (string). "
                        + "filter_rows: required filters; optional sorts, maxItems, offset, fields. "
                        + "filter_sort_topn: required sorts; optional filters, maxItems, offset, fields "
                        + "(preferred Top-N / sort path). "
                        + "group_metric: required measures; optional filters, groupBy, derived, having, sorts, "
                        + "maxItems, offset, fields (preferred group count/aggregate path). "
                        + "bin_numeric: required column plus exactly one of binEdges or binCount (rangeMin/rangeMax "
                        + "only with binCount); optional filters; no groupBy. Returns one row per bin with count and "
                        + "density (histogram source). "
                        + "box_summary: required column; optional groupBy (one column) and filters. Returns per-group "
                        + "n, min/q1/median/q3/max, whiskers and outliers (boxplot source). "
                        + "union_rows: required sourceCacheIds (2–31 cache ids) and labelColumn; optional labelValues "
                        + "(one label per input, same order). Appends rows in order with the label column; columns must "
                        + "match by name and base type across inputs. Rows are not de-duplicated. ");
        if (exactJoinAdvertised) {
            modeDesc.append("exact_join: required rightCacheId; optional joinType (INNER|LEFT, default INNER); "
                    + "cacheId is the left table. ");
        }
        if (modes.contains(MODE_QUALITY)) {
            modeDesc.append("quality: required timeColumn, windowStart, windowEnd; optional valueColumn. "
                    + "Assessment-only (no findingCacheId). ");
        }
        if (resampleAdvertised) {
            modeDesc.append("resample: required timeColumn, windowStart, windowEnd; optional valueColumn, "
                    + "aggregation (COUNT|SUM|MEAN|MIN|MAX|FIRST|LAST, default MEAN). ");
        }
        if (rollingAdvertised) {
            modeDesc.append("rolling: required timeColumn, windowStart, windowEnd; optional valueColumn, "
                    + "rollingKind, observationWindow, durationWindowSeconds, minSupport. ");
        }
        if (modes.contains(MODE_RATE_OF_CHANGE)) {
            modeDesc.append("rate_of_change: required timeColumn, windowStart, windowEnd; optional valueColumn. ");
        }
        boolean counterDeltaAdvertised = modes.contains(MODE_COUNTER_DELTA);
        boolean rollingStatsAdvertised = modes.contains(MODE_ROLLING_STATS);
        boolean timeWeightedAdvertised = modes.contains(MODE_TIME_WEIGHTED);
        // The measurement modes share one scope sentence; it names enabled modes only.
        List<String> measurementModes = new ArrayList<>();
        if (counterDeltaAdvertised) {
            measurementModes.add(MODE_COUNTER_DELTA);
        }
        if (rollingStatsAdvertised) {
            measurementModes.add(MODE_ROLLING_STATS);
        }
        if (timeWeightedAdvertised) {
            measurementModes.add(MODE_TIME_WEIGHTED);
        }
        if (!measurementModes.isEmpty()) {
            modeDesc.append(String.join(" / ", measurementModes)).append(": valueColumn required; results "
                    + "cover only what was read, never a window or shift total. ");
        }
        if (counterDeltaAdvertised) {
            modeDesc.append("counter_delta: optional entityColumn, counter rules; increments between real "
                    + "readings. Give a rule only when the user or App states it; never guess. ");
        }
        if (rollingStatsAdvertised) {
            modeDesc.append("rolling_stats: required statistic; optional entityColumn, rollingKind and its window "
                    + "props. One statistic per source record; warmedUp is not coverage. ");
        }
        if (timeWeightedAdvertised) {
            modeDesc.append("time_weighted: required integrationMethod, maxGapSeconds, timeUnit. Integral and time "
                    + "mean of one property-history cache; held time is an estimate. ");
        }
        boolean calendarBucketAdvertised = modes.contains(MODE_CALENDAR_BUCKET);
        if (calendarBucketAdvertised) {
            modeDesc.append("calendar_bucket: required timeColumn, timeZone, calendarBucket. Adds local day/hour "
                    + "columns by each row's own time; aggregate with group_metric. ");
        }
        if (periodCompareAdvertised) {
            modeDesc.append("period_compare: required timeColumn, originalWindowStart/End, "
                    + "currentWindowStart/End; optional valueColumn, aggregation. Assessment-only. ");
        }
        if (anyU4) {
            modeDesc.append("U4 modes return analysisEnvelope (not insightEnvelope). See query-spec.md.");
        } else {
            modeDesc.append("See query-spec.md.");
        }
        props.put("mode", Map.of("type", "string",
                "enum", modes,
                "description", modeDesc.toString()));

        // Withdraw U4-only properties with their modes when admission disables them (§12).
        if (exactJoinAdvertised) {
            props.put("rightCacheId", Map.of("type", "string",
                    "description", "Required for mode=exact_join: conversation cacheId of the right join table. "
                            + "Must be an explicit cache id (not the last-tabular sentinel)."));
            props.put("joinType", Map.of("type", "string",
                    "description", "Optional for mode=exact_join: INNER (default when omitted) or LEFT. "
                            + "Any other present value fails fast."));
        }
        // calendar_bucket reads the time column only: it joins this label and none of the others.
        List<String> timeColumnModes = new ArrayList<>(timeValueModes);
        if (calendarBucketAdvertised) {
            timeColumnModes.add(MODE_CALENDAR_BUCKET);
        }
        if (!timeColumnModes.isEmpty()) {
            props.put("timeColumn", Map.of("type", "string",
                    "description", "Required for " + seriesModeLabel(timeColumnModes) + ": temporal column name."));
        }
        if (!timeValueModes.isEmpty()) {
            props.put("valueColumn", Map.of("type", "string",
                    "description", "Optional numeric value column for " + timeValueLabel + "."));
        }
        // Single-window props: only currently advertised SERIES_MODES (never disabled siblings).
        if (!seriesModes.isEmpty()) {
            props.put("windowStart", Map.of("type", "string",
                    "description", "Required for " + seriesLabel + ": ISO-8601 half-open window start."));
            props.put("windowEnd", Map.of("type", "string",
                    "description", "Required for " + seriesLabel + ": ISO-8601 half-open window end "
                            + "(exclusive)."));
        }
        List<String> aggModes = new ArrayList<>();
        if (resampleAdvertised) {
            aggModes.add(MODE_RESAMPLE);
        }
        if (periodCompareAdvertised) {
            aggModes.add(MODE_PERIOD_COMPARE);
        }
        if (!aggModes.isEmpty()) {
            props.put("aggregation", Map.of("type", "string",
                    "description", "Optional for " + seriesModeLabel(aggModes)
                            + ": COUNT|SUM|MEAN|MIN|MAX|FIRST|LAST (default MEAN). "
                            + "Present invalid values fail fast."));
        }
        // Shared by rolling and rolling_stats; the label names enabled modes only.
        List<String> rollingWindowModes = new ArrayList<>();
        if (rollingAdvertised) {
            rollingWindowModes.add(MODE_ROLLING);
        }
        if (rollingStatsAdvertised) {
            rollingWindowModes.add(MODE_ROLLING_STATS);
        }
        if (!rollingWindowModes.isEmpty()) {
            String rollingLabel = seriesModeLabel(rollingWindowModes);
            props.put("rollingKind", Map.of("type", "string",
                    "description", "Optional for " + rollingLabel + ": OBSERVATION_COUNT (default) or "
                            + "ELAPSED_DURATION. Present invalid values fail fast."));
            props.put("observationWindow", Map.of("type", "integer",
                    "description", "Optional for " + rollingLabel + " OBSERVATION_COUNT: window size (default 5)."));
            props.put("durationWindowSeconds", Map.of("type", "integer",
                    "description", "Optional for " + rollingLabel
                            + " ELAPSED_DURATION: window seconds (default 3600)."));
            props.put("minSupport", Map.of("type", "integer",
                    "description", "Optional for " + rollingLabel + ": minimum support (default 1)."));
        }
        if (rollingStatsAdvertised) {
            // The mode description names it as required; the enum needs no further text.
            props.put("statistic", Map.of("type", "string",
                    "enum", List.of("mean", "sum", "min", "max", "stddev", "count_values", "count_records")));
        }
        List<String> entityModes = new ArrayList<>();
        if (counterDeltaAdvertised) {
            entityModes.add(MODE_COUNTER_DELTA);
        }
        if (rollingStatsAdvertised) {
            entityModes.add(MODE_ROLLING_STATS);
        }
        if (!entityModes.isEmpty()) {
            props.put("entityColumn", Map.of("type", "string",
                    "description", String.join(" / ", entityModes) + ": column separating series."));
        }
        if (counterDeltaAdvertised) {
            props.put("counterModulus", Map.of("type", "number",
                    "description", "counter_delta: rollover modulus; needs maxRatePerSecond."));
            props.put("maxRatePerSecond", Map.of("type", "number",
                    "description", "counter_delta: largest plausible increase per second."));
            props.put("resetBaseline", Map.of("type", "number",
                    "description", "counter_delta: restart value; not with counterModulus."));
        }
        List<String> gapModes = new ArrayList<>();
        if (counterDeltaAdvertised) {
            gapModes.add(MODE_COUNTER_DELTA);
        }
        if (timeWeightedAdvertised) {
            gapModes.add(MODE_TIME_WEIGHTED);
        }
        if (!gapModes.isEmpty()) {
            props.put("maxGapSeconds", Map.of("type", "integer",
                    "description", String.join(" / ", gapModes)
                            + ": longest usable interval between readings."));
        }
        if (timeWeightedAdvertised) {
            // The mode description names both as required; the enums need no further text.
            props.put("integrationMethod", Map.of("type", "string", "enum", List.of("step_hold", "trapezoid")));
            props.put("timeUnit", Map.of("type", "string", "enum", List.of("seconds", "minutes", "hours")));
        }
        if (calendarBucketAdvertised) {
            props.put("timeZone", Map.of("type", "string",
                    "description", "IANA zone id or UTC; no default."));
            // The mode description names it as required; the enum needs no further text.
            props.put("calendarBucket", Map.of("type", "string", "enum", List.of("day", "hour")));
        }
        if (periodCompareAdvertised) {
            props.put("originalWindowStart", Map.of("type", "string",
                    "description", "Required for mode=period_compare: ISO-8601 original window start."));
            props.put("originalWindowEnd", Map.of("type", "string",
                    "description", "Required for mode=period_compare: ISO-8601 original window end (exclusive)."));
            props.put("currentWindowStart", Map.of("type", "string",
                    "description", "Required for mode=period_compare: ISO-8601 current window start."));
            props.put("currentWindowEnd", Map.of("type", "string",
                    "description", "Required for mode=period_compare: ISO-8601 current window end (exclusive)."));
        }

        props.put("filters", Map.of("type", "object", "description", "Row predicate (filter_* modes; optional for filter_sort_topn, group_metric, bin_numeric, box_summary). " + TWX_FILTER_BRIEF));
        props.put("column", Map.of("type", "string",
                "description", "bin_numeric / box_summary: numeric source column (or uniformly numeric-parseable "
                        + "STRING) whose distribution is computed."));
        Map<String, Object> binEdgesArr = new LinkedHashMap<>();
        binEdgesArr.put("type", "array");
        binEdgesArr.put("items", Map.of("type", "number"));
        binEdgesArr.put("description", "bin_numeric: 2–51 strictly increasing finite bin edges (explicit_edges_v1). "
                + "Bins are [edge[i], edge[i+1]); the last bin includes its right endpoint. Mutually exclusive "
                + "with binCount.");
        props.put("binEdges", binEdgesArr);
        props.put("binCount", Map.of("type", "integer", "minimum", 1, "maximum", CachedTabularDistributionExecutor.MAX_BINS,
                "description", "bin_numeric: number of equal-width bins 1–50 (equal_width_v1) over "
                        + "[rangeMin, rangeMax], defaulting to the valid values' min/max. Mutually exclusive with "
                        + "binEdges."));
        props.put("rangeMin", Map.of("type", "number",
                "description", "bin_numeric with binCount only: lower bound of the equal-width range."));
        props.put("rangeMax", Map.of("type", "number",
                "description", "bin_numeric with binCount only: upper bound of the equal-width range (must exceed "
                        + "rangeMin)."));
        Map<String, Object> sortKeyProps = new LinkedHashMap<>();
        sortKeyProps.put("fieldName", Map.of("type", "string"));
        sortKeyProps.put("isAscending", Map.of("type", "boolean",
                "description", "Optional; default true (ascending) per query-spec §4.1."));
        sortKeyProps.put("isCaseSensitive", Map.of("type", "boolean",
                "description", "Optional; default true for string sort keys (query-spec §4)."));
        Map<String, Object> sortItem = new LinkedHashMap<>();
        sortItem.put("type", "object");
        sortItem.put("properties", sortKeyProps);
        sortItem.put("required", new String[] {"fieldName"});
        Map<String, Object> sortsArr = new LinkedHashMap<>();
        sortsArr.put("type", "array");
        sortsArr.put("description", "1–3 sort keys for filter_rows / filter_sort_topn / group_metric output.");
        sortsArr.put("items", sortItem);
        props.put("sorts", sortsArr);

        props.put("maxItems", Map.of("type", "integer",
                "description", "Output row cap (1–500) after sort/filter; default 50 for sort-style modes."));
        props.put("offset", Map.of("type", "integer", "description", "Non-negative slice offset after sort (default 0)."));
        Map<String, Object> fieldsArr = new LinkedHashMap<>();
        fieldsArr.put("type", "array");
        fieldsArr.put("description",
                "Optional output column projection (query-spec §5.3): array of source or output column names, "
                        + "order preserved. Allowed on filter_rows, filter_sort_topn, group_metric only.");
        fieldsArr.put("items", Map.of("type", "string"));
        props.put("fields", fieldsArr);

        Map<String, Object> measureProps = new LinkedHashMap<>();
        measureProps.put("name", Map.of("type", "string"));
        measureProps.put("op", Map.of("type", "string",
                "enum", List.of("count", "count_non_null", "sum", "avg", "min", "max", "count_distinct", "weighted_avg",
                        "median", "percentile", "variance", "stddev", "first", "last", "mode")));
        measureProps.put("column", Map.of("type", "string"));
        measureProps.put("filters", Map.of("type", "object",
                "description", "Optional per-measure row filter; omit this field entirely when unconditional "
                        + "(no TRUE/ALL/MATCH_ALL placeholders). " + TWX_FILTER_BRIEF));
        measureProps.put("weightColumn", Map.of("type", "string"));
        measureProps.put("p", Map.of("type", "number", "description", "percentile only: p in [0,100]."));
        measureProps.put("orderBy", Map.of("type", "string", "description", "first / last only: sortable column name."));
        measureProps.put("direction", Map.of("type", "string",
                "enum", List.of("asc", "desc", "ascending", "descending"),
                "description", "first / last only; default asc when omitted or blank."));
        Map<String, Object> measureItem = new LinkedHashMap<>();
        measureItem.put("type", "object");
        measureItem.put("properties", measureProps);
        measureItem.put("required", new String[] {"name", "op"});
        Map<String, Object> measuresArr = new LinkedHashMap<>();
        measuresArr.put("type", "array");
        measuresArr.put("description", "group_metric: up to 10 measures.");
        measuresArr.put("items", measureItem);
        props.put("measures", measuresArr);

        Map<String, Object> inputsArr = new LinkedHashMap<>();
        inputsArr.put("type", "array");
        inputsArr.put("items", Map.of("type", "string"));
        inputsArr.put("description", "sum_values / multiply: non-blank measure or derived names (max 10).");

        Map<String, Object> derivedGroupBy = new LinkedHashMap<>();
        derivedGroupBy.put("type", "array");
        derivedGroupBy.put("items", Map.of("type", "string"));
        derivedGroupBy.put("description", "percent_of_group only: non-empty subset of the mode-level "
                + "groupBy columns used as the percent partition.");

        Map<String, Object> derivedProps = new LinkedHashMap<>();
        derivedProps.put("name", Map.of("type", "string"));
        // E4: publish executor-supported percent_* ops (+ derived groupBy); enum = DERIVED_OPS.
        derivedProps.put("op", Map.of("type", "string",
                "enum", CachedTabularGroupMetricExecutor.DERIVED_OPS,
                "description", "Derived metric op. percent_of_total / percent_of_group require input "
                        + "(measure name); percent_of_group also requires groupBy[]."));
        derivedProps.put("numerator", Map.of("type", "string"));
        derivedProps.put("denominator", Map.of("type", "string"));
        derivedProps.put("left", Map.of("type", "string"));
        derivedProps.put("right", Map.of("type", "string"));
        derivedProps.put("inputs", inputsArr);
        derivedProps.put("input", Map.of("type", "string",
                "description", "scale: measure or prior derived name. "
                        + "percent_of_total / percent_of_group: measure name only."));
        derivedProps.put("factor", Map.of("type", "number", "description", "scale: numeric factor."));
        derivedProps.put("groupBy", derivedGroupBy);
        Map<String, Object> derivedItem = new LinkedHashMap<>();
        derivedItem.put("type", "object");
        derivedItem.put("properties", derivedProps);
        derivedItem.put("required", new String[] {"name", "op"});
        Map<String, Object> derivedArr = new LinkedHashMap<>();
        derivedArr.put("type", "array");
        derivedArr.put("description", "group_metric: derived metrics (max 10), including percent_of_total "
                + "/ percent_of_group.");
        derivedArr.put("items", derivedItem);
        props.put("derived", derivedArr);

        props.put("having", Map.of("type", "object", "description",
                "group_metric only: post-aggregate filter on grouped output rows. **Prefer `having`** for metric "
                        + "equality or threshold questions; do **not** sort and read **`sampleRows`** — previews only; "
                        + "**`totalRows`** is output size, not full membership proof. " + TWX_FILTER_BRIEF));

        props.put("groupBy", Map.of("oneOf", List.of(
                        Map.of("type", "string"),
                        Map.of("type", "array", "items", Map.of("type", "string"))),
                "description", "filter_count: optional string column. "
                        + "group_metric: array of 0–5 source column names (or []). "
                        + "box_summary: optional single column (string or one-element array). "
                        + "Not supported by bin_numeric."));
        // B16: aggregateColumn / fn are executor/replay-parse-only for unpublished group_* aliases —
        // MUST NOT appear on the advertised schema.

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        // cacheId is required by every mode except union_rows, which reads sourceCacheIds. JSON Schema
        // "required" is structural and cannot be relaxed by a description, so only mode is required here;
        // the executor still answers MISSING_CACHE_ID for every other mode.
        schema.put("required", new String[] {"mode"});
        return schema;
    }
}
