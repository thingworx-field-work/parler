package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Builds Parler {@code ChartBlock} JSON from an {@link InfoTable} + column mapping per
 * {@code docs/architecture/flexible-chart-solution.md} (v1).
 */
public final class ParlerTabularChartBuilder {

    /** @see CONTRACTS/CHART_CONTRACT.md §3.0 chart limits */
    public static final int PIE_DEFAULT_MAX_SLICES = 8;
    public static final int PIE_HARD_MAX_SLICES = 12;
    public static final int BAR_MAX_SERIES = 6;
    public static final int BAR_MAX_CATEGORIES = 24;
    /** C2b-1 (chart-enhancement design §7.4): histogram payload cap, equal to bin_numeric's MAX_BINS. */
    public static final int HIST_MAX_BINS = 50;
    /** Fixed columns a histogram source (a {@code bin_numeric} result table) must carry. */
    public static final List<String> HISTOGRAM_SOURCE_COLUMNS = List.of("binIndex", "binStart", "binEnd", "count",
            "density", "validCount", "excludedCount", "belowRangeCount", "aboveRangeCount", "method");
    /** Registered binning method identifiers a histogram source may name. */
    public static final java.util.Set<String> HISTOGRAM_METHODS = java.util.Set.of("explicit_edges_v1",
            "equal_width_v1");
    /** Relative tolerance for the density invariant (covers JSON round-trips, rejects magnitude errors). */
    public static final double HISTOGRAM_DENSITY_REL_TOLERANCE = 1e-9;
    /** C2b-2 (design §7.4): most groups a {@code kind: "boxplot"} payload may carry (CHART_CONTRACT {@code BOX_MAX_GROUPS}). */
    public static final int BOX_MAX_GROUPS = 24;
    /** Most outliers listed per group; the rest are counted only (CHART_CONTRACT {@code BOX_MAX_SHOWN_OUTLIERS}). */
    public static final int BOX_MAX_SHOWN_OUTLIERS = 20;
    /** Fixed columns a boxplot source (a {@code box_summary} result table) must carry. */
    public static final List<String> BOXPLOT_SOURCE_COLUMNS = List.of("groupKey", "n", "excludedCount", "min", "q1",
            "median", "q3", "max", "whiskerLow", "whiskerHigh", "outlierCount", "outliers", "method");
    /** Registered box-summary method identifiers a boxplot source may name. */
    public static final java.util.Set<String> BOXPLOT_METHODS = java.util.Set.of("tukey_1_5_iqr_linear_p_v1");
    /** C2b-3 (design §7.4): most rows / columns of a {@code kind: "heatmap"} payload (CHART_CONTRACT {@code HEATMAP_MAX_*}). */
    public static final int HEATMAP_MAX_ROWS = 24;
    public static final int HEATMAP_MAX_COLS = 48;

    private static final int MAX_CHART_ROWS = 5000;
    private static final int MAX_SERIES = BAR_MAX_SERIES;
    private static final Set<String> REF_ROLES = Set.of(
            "usl", "ucl", "lcl", "lsl", "target", "limit", "warning");

    public static final class SeriesSpec {
        public final String name;
        public final String yColumn;

        public SeriesSpec(String name, String yColumn) {
            this.name = name;
            this.yColumn = yColumn;
        }
    }

    public static final class BuildException extends Exception {
        public final String code;
        public final JSONObject details;

        public BuildException(String code, String message, JSONObject details) {
            super(message);
            this.code = code;
            this.details = details;
        }
    }

    private enum XMode {
        NUMERIC, DATETIME, CATEGORY
    }

    /** Inferred JSON column types for inline INFOTABLE reconstruction (BOOLEAN → chart Y 0/1). */
    private enum ColKind {
        BOOLEAN, NUMBER, STRING
    }

    private ParlerTabularChartBuilder() {}

    /** Classifies tabular X for {@code line}/{@code scatter} (intent routing). */
    public enum LineScatterAxisKind {
        DATETIME,
        NUMERIC,
        UNSUPPORTED
    }

    public static LineScatterAxisKind classifyLineScatterAxis(InfoTable table, String xColumn) {
        if (table == null || xColumn == null || xColumn.isBlank()) {
            return LineScatterAxisKind.UNSUPPORTED;
        }
        int n = table.getRowCount();
        if (n <= 0) {
            return LineScatterAxisKind.UNSUPPORTED;
        }
        try {
            XMode m = detectLineScatterXMode(table, xColumn.trim(), n);
            if (m == XMode.DATETIME) {
                return LineScatterAxisKind.DATETIME;
            }
            if (m == XMode.NUMERIC) {
                return LineScatterAxisKind.NUMERIC;
            }
            return LineScatterAxisKind.UNSUPPORTED;
        } catch (BuildException e) {
            return LineScatterAxisKind.UNSUPPORTED;
        }
    }

    public static JSONObject buildChartBlock(
            InfoTable table,
            String kindRaw,
            String xColumn,
            String yColumnSingle,
            List<SeriesSpec> multiY,
            String title,
            String xLabel,
            String yLabel,
            JsonNode yReferenceLines,
            JsonNode requestedTimeRange) throws BuildException {
        return buildChartBlock(table, kindRaw, xColumn, yColumnSingle, multiY, title, xLabel, yLabel,
                yReferenceLines, requestedTimeRange, false, null, null, -1);
    }

    public static JSONObject buildChartBlock(
            InfoTable table,
            String kindRaw,
            String xColumn,
            String yColumnSingle,
            List<SeriesSpec> multiY,
            String title,
            String xLabel,
            String yLabel,
            JsonNode yReferenceLines,
            JsonNode requestedTimeRange,
            boolean sortBarByPrimaryYDescending) throws BuildException {
        return buildChartBlock(table, kindRaw, xColumn, yColumnSingle, multiY, title, xLabel, yLabel,
                yReferenceLines, requestedTimeRange, sortBarByPrimaryYDescending, null, null, -1);
    }

    /**
     * Bar orientation entry (chart-enhancement design §7.3, C2a-1). {@code orientation} is
     * {@code null} (field absent, default vertical) or exactly {@code "vertical"} /
     * {@code "horizontal"} (no trimming or case folding) and is only defined when {@code kind} is
     * {@code bar}; both the wide-table and the {@code seriesColumn} long-table bar paths write
     * {@code "orientation":"horizontal"} only for horizontal, so every vertical payload stays
     * byte-identical to before. Data roles never change with orientation.
     *
     * @throws BuildException {@code INVALID_PARAMETERS} when a supplied value is outside the enum
     *         (including blank or case variants) or the kind is not bar.
     */
    public static JSONObject buildChartBlock(
            InfoTable table,
            String kindRaw,
            String xColumn,
            String yColumnSingle,
            List<SeriesSpec> multiY,
            String title,
            String xLabel,
            String yLabel,
            JsonNode yReferenceLines,
            JsonNode requestedTimeRange,
            boolean sortBarByPrimaryYDescending,
            String seriesColumn,
            String pieSliceMode,
            int pieMaxSlices,
            String orientation) throws BuildException {
        String kind = kindRaw == null ? "" : kindRaw.trim().toLowerCase(Locale.ROOT);
        String o = orientation == null ? "" : orientation;
        if (orientation != null) {
            if (!"vertical".equals(o) && !"horizontal".equals(o)) {
                throw new BuildException("INVALID_PARAMETERS",
                        "orientation must be exactly \"vertical\" or \"horizontal\" when supplied.", null);
            }
            if (!"bar".equals(kind)) {
                throw new BuildException("INVALID_PARAMETERS", "orientation applies only when kind is bar.", null);
            }
        }
        JSONObject chart = buildChartBlock(table, kindRaw, xColumn, yColumnSingle, multiY, title, xLabel, yLabel,
                yReferenceLines, requestedTimeRange, sortBarByPrimaryYDescending, seriesColumn, pieSliceMode,
                pieMaxSlices);
        if ("horizontal".equals(o) && "bar".equals(chart.optString("kind"))) {
            chart.put("orientation", "horizontal");
        }
        return chart;
    }

    /** Registered {@code stackMode} values (design §7.4 C2a-2); {@code grouped} is the default and is never written. */
    public static final java.util.Set<String> STACK_MODES = java.util.Set.of("grouped", "stacked", "percent");

    /**
     * Bar stack-mode entry (chart-enhancement design §7.4, C2a-2). {@code stackMode} is {@code null}
     * (field absent, grouped) or exactly {@code "grouped"} / {@code "stacked"} / {@code "percent"} and is
     * only defined when {@code kind} is {@code bar} with at least two series; {@code percent} needs
     * non-negative values. Only {@code stacked} / {@code percent} is written, so every grouped payload
     * stays byte-identical. Data roles never change with the stack mode; shares are a client
     * presentation computation over the emitted series.
     *
     * @throws BuildException {@code INVALID_PARAMETERS} for a value outside the enum, a non-bar kind or
     *         fewer than two series; {@code STACK_PERCENT_NEGATIVE} for a negative value under percent.
     */
    public static JSONObject buildChartBlock(
            InfoTable table,
            String kindRaw,
            String xColumn,
            String yColumnSingle,
            List<SeriesSpec> multiY,
            String title,
            String xLabel,
            String yLabel,
            JsonNode yReferenceLines,
            JsonNode requestedTimeRange,
            boolean sortBarByPrimaryYDescending,
            String seriesColumn,
            String pieSliceMode,
            int pieMaxSlices,
            String orientation,
            String stackMode) throws BuildException {
        String kind = kindRaw == null ? "" : kindRaw.trim().toLowerCase(Locale.ROOT);
        if (stackMode != null) {
            if (!STACK_MODES.contains(stackMode)) {
                throw new BuildException("INVALID_PARAMETERS",
                        "stackMode must be exactly \"grouped\", \"stacked\" or \"percent\" when supplied.", null);
            }
            if (!"bar".equals(kind)) {
                throw new BuildException("INVALID_PARAMETERS", "stackMode applies only when kind is bar.", null);
            }
        }
        JSONObject chart = buildChartBlock(table, kindRaw, xColumn, yColumnSingle, multiY, title, xLabel, yLabel,
                yReferenceLines, requestedTimeRange, sortBarByPrimaryYDescending, seriesColumn, pieSliceMode,
                pieMaxSlices, orientation);
        if (stackMode == null || "grouped".equals(stackMode)) {
            return chart;
        }
        JSONArray series = chart.optJSONArray("series");
        if (series == null || series.length() < 2) {
            JSONObject d = new JSONObject();
            d.put("seriesCount", series == null ? 0 : series.length());
            throw new BuildException("INVALID_PARAMETERS",
                    "stackMode " + stackMode + " needs at least two series (seriesColumn or series[]).", d);
        }
        if ("percent".equals(stackMode)) {
            for (int si = 0; si < series.length(); si++) {
                JSONArray ys = series.getJSONObject(si).getJSONArray("y");
                for (int i = 0; i < ys.length(); i++) {
                    if (ys.getDouble(i) < 0) {
                        JSONObject d = new JSONObject();
                        d.put("series", series.getJSONObject(si).optString("name"));
                        d.put("index", i);
                        d.put("value", ys.getDouble(i));
                        throw new BuildException("STACK_PERCENT_NEGATIVE",
                                "percent stacking needs non-negative values; use stacked for signed data.", d);
                    }
                }
            }
        }
        chart.put("stackMode", stackMode);
        return chart;
    }

    /**
     * @param seriesColumn when non-null/blank and {@code kind} is {@code bar}, {@code line}, or
     *        {@code scatter}, pivot long-format rows ({@code xColumn}, {@code seriesColumn},
     *        {@code yColumn}) into multi-series charts.
     * @param pieSliceMode {@code top_with_other} or {@code all_nonzero} when {@code kind} is {@code pie}; ignored otherwise.
     * @param pieMaxSlices 2..12 for pie, or {@code -1} for default ({@link #PIE_DEFAULT_MAX_SLICES}).
     */
    public static JSONObject buildChartBlock(
            InfoTable table,
            String kindRaw,
            String xColumn,
            String yColumnSingle,
            List<SeriesSpec> multiY,
            String title,
            String xLabel,
            String yLabel,
            JsonNode yReferenceLines,
            JsonNode requestedTimeRange,
            boolean sortBarByPrimaryYDescending,
            String seriesColumn,
            String pieSliceMode,
            int pieMaxSlices) throws BuildException {

        String kind = kindRaw == null ? "" : kindRaw.trim().toLowerCase(Locale.ROOT);
        if (!"line".equals(kind) && !"bar".equals(kind) && !"scatter".equals(kind) && !"pie".equals(kind)
                && !"heatmap".equals(kind)) {
            throw new BuildException("UNSUPPORTED_CHART_KIND", "kind must be line, bar, scatter, pie, or heatmap.", null);
        }

        if (table == null || table.getRowCount() == 0) {
            throw new BuildException("EMPTY_AFTER_FILTER", "Table has no rows.", null);
        }
        if (table.getRowCount() > MAX_CHART_ROWS) {
            JSONObject d = new JSONObject();
            d.put("rowCount", table.getRowCount());
            d.put("maxRows", MAX_CHART_ROWS);
            throw new BuildException("ROW_OR_PAYLOAD_LIMIT", "Too many rows for chart.", d);
        }

        List<String> allCols = columnNames(table);
        List<String> yCols = new ArrayList<>();
        List<String> seriesNames = new ArrayList<>();

        if (multiY != null && !multiY.isEmpty()) {
            if (yColumnSingle != null && !yColumnSingle.isEmpty()) {
                throw new BuildException("INVALID_MAPPING", "Do not set yColumn when series is provided.", null);
            }
            if (multiY.size() > MAX_SERIES) {
                throw new BuildException("TOO_MANY_SERIES", "At most " + MAX_SERIES + " series.", null);
            }
            for (SeriesSpec sp : multiY) {
                if (sp.yColumn == null || sp.yColumn.isBlank() || sp.name == null || sp.name.isBlank()) {
                    throw new BuildException("INVALID_MAPPING", "series entries need name and yColumn.", null);
                }
                yCols.add(sp.yColumn.trim());
                seriesNames.add(sp.name.trim());
            }
        } else {
            if (yColumnSingle == null || yColumnSingle.isBlank()) {
                throw new BuildException("INVALID_MAPPING", "yColumn or series is required.", null);
            }
            yCols.add(yColumnSingle.trim());
            seriesNames.add(yColumnSingle.trim());
        }

        String xCol = xColumn != null ? xColumn.trim() : "";
        if (xCol.isEmpty()) {
            throw new BuildException("INVALID_MAPPING", "xColumn is required.", null);
        }
        requireColumn(allCols, xCol, "xColumn");

        if ("pie".equals(kind)) {
            if (multiY != null && !multiY.isEmpty()) {
                throw new BuildException("PIE_REQUIRES_SINGLE_SERIES", "pie requires a single yColumn, not series[].", null);
            }
            String yc = yColumnSingle != null ? yColumnSingle.trim() : "";
            if (yc.isEmpty()) {
                throw new BuildException("INVALID_MAPPING", "yColumn is required for pie.", null);
            }
            requireColumn(allCols, yc, "yColumn");
            return buildPieChartBlock(table, xCol, yc, title, xLabel, yLabel, pieSliceMode, pieMaxSlices);
        }

        String scTrim = seriesColumn == null ? "" : seriesColumn.trim();
        if ("heatmap".equals(kind)) {
            // C2b-3 (design §7.4): the long-table bar bindings name the two dimensions and the cell value.
            if (multiY != null && !multiY.isEmpty()) {
                throw new BuildException("INVALID_MAPPING", "heatmap takes yColumn, not series[].", null);
            }
            if (scTrim.isEmpty()) {
                throw new BuildException("INVALID_MAPPING", "seriesColumn (the row dimension) is required for heatmap.", null);
            }
            requireColumn(allCols, scTrim, "seriesColumn");
            String yc = yColumnSingle != null ? yColumnSingle.trim() : "";
            if (yc.isEmpty()) {
                throw new BuildException("INVALID_MAPPING", "yColumn (the cell value) is required for heatmap.", null);
            }
            requireColumn(allCols, yc, "yColumn");
            return buildHeatmapFromLongTable(table, xCol, scTrim, yc, title, xLabel, yLabel);
        }
        boolean longPivot = !scTrim.isEmpty()
                && ("bar".equals(kind) || "line".equals(kind) || "scatter".equals(kind));
        if (longPivot) {
            if (multiY != null && !multiY.isEmpty()) {
                throw new BuildException("INVALID_MAPPING", "seriesColumn cannot be combined with series[].", null);
            }
            requireColumn(allCols, scTrim, "seriesColumn");
            String yc = yColumnSingle != null ? yColumnSingle.trim() : "";
            if (yc.isEmpty()) {
                throw new BuildException("INVALID_MAPPING", "yColumn is required when seriesColumn is set.", null);
            }
            requireColumn(allCols, yc, "yColumn");
            if ("bar".equals(kind)) {
                return buildBarFromLongTable(table, xCol, scTrim, yc, title, xLabel, yLabel, yReferenceLines,
                        sortBarByPrimaryYDescending);
            }
            return buildLineScatterFromLongTable(table, kind, xCol, scTrim, yc, title, xLabel, yLabel, yReferenceLines,
                    requestedTimeRange);
        }

        for (String yc : yCols) {
            requireColumn(allCols, yc, "yColumn");
        }

        int n = table.getRowCount();
        XMode xMode;
        if ("bar".equals(kind)) {
            xMode = XMode.CATEGORY;
        } else {
            xMode = detectLineScatterXMode(table, xCol, n);
        }

        List<String> xStrings = new ArrayList<>(n);
        List<List<Double>> yMatrix = new ArrayList<>();
        for (int s = 0; s < yCols.size(); s++) {
            yMatrix.add(new ArrayList<>(n));
        }

        for (int r = 0; r < n; r++) {
            ValueCollection row = table.getRow(r);
            if (row == null) {
                throw new BuildException("ROW_ALIGN_FAILED", "Missing row " + r, null);
            }

            if ("bar".equals(kind)) {
                xStrings.add(cellToStringCategory(row, xCol, r));
            } else {
                xStrings.add(xCellToLineScatterString(row, xCol, xMode, r));
            }

            for (int si = 0; si < yCols.size(); si++) {
                Double yd = cellToNumericY(row, yCols.get(si), r, si);
                if (yd == null) {
                    JSONObject d = new JSONObject();
                    d.put("row", r);
                    d.put("yColumn", yCols.get(si));
                    throw new BuildException("Y_COLUMN_NOT_NUMERIC", "Non-numeric Y value.", d);
                }
                yMatrix.get(si).add(yd);
            }
        }

        if ("bar".equals(kind)) {
            if (n > BAR_MAX_CATEGORIES) {
                JSONObject d = new JSONObject();
                d.put("categoryCount", n);
                d.put("maxCategories", BAR_MAX_CATEGORIES);
                throw new BuildException("TOO_MANY_CATEGORIES", "Too many bar categories.", d);
            }
        }

        if ("bar".equals(kind) && sortBarByPrimaryYDescending && n > 1) {
            final List<String> xStrRef = xStrings;
            final List<List<Double>> yMatRef = yMatrix;
            List<Integer> order = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
            order.sort((a, b) -> {
                double ya = yMatRef.get(0).get(a);
                double yb = yMatRef.get(0).get(b);
                int cmp = Double.compare(yb, ya);
                if (cmp != 0) {
                    return cmp;
                }
                return Integer.compare(a, b);
            });
            List<String> xSorted = new ArrayList<>(n);
            List<List<Double>> ySorted = new ArrayList<>();
            for (int s = 0; s < yCols.size(); s++) {
                ySorted.add(new ArrayList<>(n));
            }
            for (int idx : order) {
                xSorted.add(xStrRef.get(idx));
                for (int s = 0; s < yCols.size(); s++) {
                    ySorted.get(s).add(yMatRef.get(s).get(idx));
                }
            }
            xStrings = xSorted;
            yMatrix = ySorted;
        }

        JSONArray seriesArr = new JSONArray();
        for (int si = 0; si < yCols.size(); si++) {
            JSONArray xj = new JSONArray();
            JSONArray yj = new JSONArray();
            for (int r = 0; r < n; r++) {
                xj.put(xStrings.get(r));
                yj.put(yMatrix.get(si).get(r));
            }
            JSONObject ser = new JSONObject();
            ser.put("name", seriesNames.get(si));
            ser.put("x", xj);
            ser.put("y", yj);
            seriesArr.put(ser);
        }

        JSONObject chart = new JSONObject();
        chart.put("kind", kind);
        if (title != null && !title.isBlank()) {
            chart.put("title", title.trim());
        }
        if (xLabel != null && !xLabel.isBlank()) {
            chart.put("x_label", xLabel.trim());
        }
        if (yLabel != null && !yLabel.isBlank()) {
            chart.put("y_label", yLabel.trim());
        }
        chart.put("series", seriesArr);

        JSONArray cleanedRefs = cleanYReferenceLines(yReferenceLines);
        if (cleanedRefs.length() > 0) {
            chart.put("y_reference_lines", cleanedRefs);
        }

        attachRequestedTimeRangeToChart(chart, kind, xMode, requestedTimeRange);

        JSONArray srcCols = new JSONArray();
        srcCols.put(xCol);
        for (String yc : yCols) {
            srcCols.put(yc);
        }
        attachV1Source(chart, table.getRowCount(), srcCols, n * yCols.size(), false, 0, null, null);

        return chart;
    }

    /** True when every {@link #HISTOGRAM_SOURCE_COLUMNS} name is a column of {@code table}. */
    public static boolean hasHistogramSourceColumns(InfoTable table) {
        if (table == null) {
            return false;
        }
        List<String> cols = columnNames(table);
        return cols.containsAll(HISTOGRAM_SOURCE_COLUMNS);
    }

    /**
     * C2b-1 (design §7.4): build a {@code kind: "histogram"} block from a {@code bin_numeric} result table.
     * The builder only validates and carries the operator's numbers; it never re-bins. Any violated
     * invariant is {@code SOURCE_SHAPE_MISMATCH} (the executor adds the recovery hint); nothing is
     * corrected silently.
     *
     * @param histogramMode {@code count} (default when null/blank) or {@code density}
     */
    public static JSONObject buildHistogramChartBlock(InfoTable table, String histogramMode, String title,
            String xLabel, String yLabel) throws BuildException {
        String mode = histogramMode == null || histogramMode.isBlank() ? "count" : histogramMode.trim();
        if (!"count".equals(mode) && !"density".equals(mode)) {
            throw new BuildException("INVALID_PARAMETERS", "histogramMode must be count or density.", null);
        }
        if (table == null || table.getRowCount() == 0) {
            throw shapeMismatch("The source has no rows.", null);
        }
        if (!hasHistogramSourceColumns(table)) {
            JSONObject d = new JSONObject();
            d.put("requiredColumns", new JSONArray(HISTOGRAM_SOURCE_COLUMNS));
            d.put("sourceColumns", new JSONArray(columnNames(table)));
            throw shapeMismatch("The source is not a bin_numeric result table (fixed columns missing).", d);
        }
        int n = table.getRowCount();
        if (n > HIST_MAX_BINS) {
            JSONObject d = new JSONObject();
            d.put("bins", n);
            d.put("maxBins", HIST_MAX_BINS);
            throw shapeMismatch("Too many bins.", d);
        }
        double[] starts = new double[n];
        double[] ends = new double[n];
        double[] counts = new double[n];
        double[] densities = new double[n];
        double[] scalars = null;
        String method = null;
        for (int r = 0; r < n; r++) {
            ValueCollection row = table.getRow(r);
            if (row == null) {
                throw shapeMismatch("Missing row " + r + ".", null);
            }
            starts[r] = histNumber(row, "binStart", r);
            ends[r] = histNumber(row, "binEnd", r);
            counts[r] = histNonNegativeInteger(row, "count", r);
            densities[r] = histNumber(row, "density", r);
            double[] sc = new double[] {histNonNegativeInteger(row, "validCount", r),
                    histNonNegativeInteger(row, "excludedCount", r), histNonNegativeInteger(row, "belowRangeCount", r),
                    histNonNegativeInteger(row, "aboveRangeCount", r)};
            String m = cellToStringCategory(row, "method", r);
            if (scalars == null) {
                scalars = sc;
                method = m;
            } else if (!java.util.Arrays.equals(scalars, sc) || !java.util.Objects.equals(method, m)) {
                throw shapeMismatch("Scalar columns differ between rows (row " + r + ").", null);
            }
            if (!(starts[r] < ends[r])) {
                throw shapeMismatch("binStart must be less than binEnd (row " + r + ").", null);
            }
            if (r > 0 && starts[r] != ends[r - 1]) {
                throw shapeMismatch("Bins must be contiguous and strictly increasing (row " + r + ").", null);
            }
            if (densities[r] < 0 || !Double.isFinite(densities[r])) {
                throw shapeMismatch("density must be a finite non-negative number (row " + r + ").", null);
            }
        }
        if (method == null || !HISTOGRAM_METHODS.contains(method)) {
            JSONObject d = new JSONObject();
            d.put("method", method == null ? JSONObject.NULL : method);
            throw shapeMismatch("Unregistered binning method.", d);
        }
        double sum = 0;
        for (double c : counts) {
            sum += c;
        }
        double expectedSum = scalars[0] - scalars[2] - scalars[3];
        if (sum != expectedSum) {
            JSONObject d = new JSONObject();
            d.put("sumCount", sum);
            d.put("validCount", scalars[0]);
            d.put("belowRangeCount", scalars[2]);
            d.put("aboveRangeCount", scalars[3]);
            throw shapeMismatch("Σcount must equal validCount − belowRangeCount − aboveRangeCount.", d);
        }
        if (!(sum > 0)) {
            throw shapeMismatch("Σcount must be positive.", null);
        }
        for (int r = 0; r < n; r++) {
            double expected = counts[r] / (sum * (ends[r] - starts[r]));
            boolean ok = expected == 0.0 ? densities[r] == 0.0
                    : Math.abs(densities[r] - expected) / Math.abs(expected) <= HISTOGRAM_DENSITY_REL_TOLERANCE;
            if (!ok) {
                JSONObject d = new JSONObject();
                d.put("row", r);
                d.put("density", densities[r]);
                d.put("expectedDensity", expected);
                throw shapeMismatch("density must equal count / (Σcount × width) (row " + r + ").", d);
            }
        }
        JSONArray edges = new JSONArray();
        edges.put(starts[0]);
        for (double e : ends) {
            edges.put(e);
        }
        JSONArray countsArr = new JSONArray();
        JSONArray densArr = new JSONArray();
        for (int r = 0; r < n; r++) {
            countsArr.put((long) counts[r]);
            densArr.put(densities[r]);
        }
        JSONObject hist = new JSONObject();
        hist.put("edges", edges);
        hist.put("counts", countsArr);
        hist.put("densities", densArr);
        hist.put("mode", mode);
        hist.put("validCount", (long) scalars[0]);
        hist.put("excludedCount", (long) scalars[1]);
        hist.put("belowRangeCount", (long) scalars[2]);
        hist.put("aboveRangeCount", (long) scalars[3]);
        hist.put("method", method);
        JSONObject chart = new JSONObject();
        chart.put("kind", "histogram");
        if (title != null && !title.isBlank()) {
            chart.put("title", title.trim());
        }
        if (xLabel != null && !xLabel.isBlank()) {
            chart.put("x_label", xLabel.trim());
        }
        if (yLabel != null && !yLabel.isBlank()) {
            chart.put("y_label", yLabel.trim());
        }
        chart.put("histogram", hist);
        attachV1Source(chart, n, new JSONArray(HISTOGRAM_SOURCE_COLUMNS), n, false, 0, null,
                "histogram(bin_numeric)");
        return chart;
    }

    private static BuildException shapeMismatch(String message, JSONObject details) {
        return new BuildException("SOURCE_SHAPE_MISMATCH", message, details);
    }

    /** True when every {@link #BOXPLOT_SOURCE_COLUMNS} name is a column of {@code table}. */
    public static boolean hasBoxplotSourceColumns(InfoTable table) {
        if (table == null) {
            return false;
        }
        return columnNames(table).containsAll(BOXPLOT_SOURCE_COLUMNS);
    }

    /**
     * C2b-2 (design §7.4): build a {@code kind: "boxplot"} block from a {@code box_summary} result table.
     * One group per row in source order; the builder checks the relations between the statistics the
     * operator already computed (order of the seven statistics, integral counts, the outlier list against
     * its count, the whiskers, the fences) and carries them; it never recomputes quantiles or whiskers.
     * Any violated invariant is {@code SOURCE_SHAPE_MISMATCH}; nothing is corrected silently.
     */
    public static JSONObject buildBoxplotChartBlock(InfoTable table, String title, String xLabel, String yLabel,
            JsonNode yReferenceLines) throws BuildException {
        if (table == null || table.getRowCount() == 0) {
            throw shapeMismatch("The source has no rows.", null);
        }
        if (!hasBoxplotSourceColumns(table)) {
            JSONObject d = new JSONObject();
            d.put("requiredColumns", new JSONArray(BOXPLOT_SOURCE_COLUMNS));
            d.put("sourceColumns", new JSONArray(columnNames(table)));
            throw shapeMismatch("The source is not a box_summary result table (fixed columns missing).", d);
        }
        int n = table.getRowCount();
        if (n > BOX_MAX_GROUPS) {
            JSONObject d = new JSONObject();
            d.put("groups", n);
            d.put("maxGroups", BOX_MAX_GROUPS);
            throw shapeMismatch("Too many groups.", d);
        }
        JSONArray groups = new JSONArray();
        Set<String> keys = new LinkedHashSet<>();
        String method = null;
        for (int r = 0; r < n; r++) {
            ValueCollection row = table.getRow(r);
            if (row == null) {
                throw shapeMismatch("Missing row " + r + ".", null);
            }
            String key = cellToStringCategory(row, "groupKey", r);
            if (key.isEmpty() || !keys.add(key)) {
                throw shapeMismatch("groupKey must be unique and non-empty (row " + r + ").", null);
            }
            String m = cellToStringCategory(row, "method", r);
            if (method == null) {
                method = m;
            } else if (!method.equals(m)) {
                throw shapeMismatch("method differs between rows (row " + r + ").", null);
            }
            double count = histNonNegativeInteger(row, "n", r);
            if (count < 1) {
                throw shapeMismatch("n must be at least 1 (row " + r + ").", null);
            }
            double excluded = histNonNegativeInteger(row, "excludedCount", r);
            double lo = histNumber(row, "min", r);
            double wLo = histNumber(row, "whiskerLow", r);
            double q1 = histNumber(row, "q1", r);
            double med = histNumber(row, "median", r);
            double q3 = histNumber(row, "q3", r);
            double wHi = histNumber(row, "whiskerHigh", r);
            double hi = histNumber(row, "max", r);
            if (!(lo <= wLo && wLo <= q1 && q1 <= med && med <= q3 && q3 <= wHi && wHi <= hi)) {
                throw shapeMismatch("Statistics must satisfy min ≤ whiskerLow ≤ q1 ≤ median ≤ q3 ≤ whiskerHigh ≤ max (row "
                        + r + ").", null);
            }
            double outlierCount = histNonNegativeInteger(row, "outlierCount", r);
            if (outlierCount > count) {
                throw shapeMismatch("outlierCount must not exceed n (row " + r + ").", null);
            }
            String outliersText = cellToStringCategory(row, "outliers", r);
            JSONArray outliers;
            try {
                outliers = new JSONArray(outliersText);
            } catch (RuntimeException e) {
                throw shapeMismatch("outliers must be a JSON array of numbers (row " + r + ").", null);
            }
            int expectedShown = (int) Math.min(outlierCount, BOX_MAX_SHOWN_OUTLIERS);
            if (outliers.length() != expectedShown) {
                JSONObject d = new JSONObject();
                d.put("row", r);
                d.put("outliers", outliers.length());
                d.put("expected", expectedShown);
                throw shapeMismatch("outliers must list exactly min(outlierCount, " + BOX_MAX_SHOWN_OUTLIERS
                        + ") values (row " + r + ").", d);
            }
            JSONArray cleanOutliers = new JSONArray();
            for (int i = 0; i < outliers.length(); i++) {
                Object o = outliers.opt(i);
                if (!(o instanceof Number) || !Double.isFinite(((Number) o).doubleValue())) {
                    throw shapeMismatch("outliers must be finite numbers (row " + r + ").", null);
                }
                double v = ((Number) o).doubleValue();
                if (!(v < wLo || v > wHi)) {
                    throw shapeMismatch("Every outlier must lie outside the whiskers (row " + r + ").", null);
                }
                if (v < lo || v > hi) {
                    throw shapeMismatch("Every outlier must lie within [min, max] (row " + r + ").", null);
                }
                cleanOutliers.put(v);
            }
            if (outlierCount == 0 && (wLo != lo || wHi != hi)) {
                throw shapeMismatch("Without outliers the whiskers must reach min and max (row " + r + ").", null);
            }
            JSONObject g = new JSONObject();
            g.put("key", key);
            g.put("n", (long) count);
            g.put("excludedCount", (long) excluded);
            g.put("min", lo);
            g.put("whiskerLow", wLo);
            g.put("q1", q1);
            g.put("median", med);
            g.put("q3", q3);
            g.put("whiskerHigh", wHi);
            g.put("max", hi);
            g.put("outliers", cleanOutliers);
            g.put("outlierCount", (long) outlierCount);
            groups.put(g);
        }
        if (method == null || !BOXPLOT_METHODS.contains(method)) {
            JSONObject d = new JSONObject();
            d.put("method", method == null ? JSONObject.NULL : method);
            throw shapeMismatch("Unregistered box-summary method.", d);
        }
        JSONObject box = new JSONObject();
        box.put("method", method);
        box.put("groups", groups);
        JSONObject chart = new JSONObject();
        chart.put("kind", "boxplot");
        if (title != null && !title.isBlank()) {
            chart.put("title", title.trim());
        }
        if (xLabel != null && !xLabel.isBlank()) {
            chart.put("x_label", xLabel.trim());
        }
        if (yLabel != null && !yLabel.isBlank()) {
            chart.put("y_label", yLabel.trim());
        }
        chart.put("boxplot", box);
        JSONArray cleanedRefs = cleanYReferenceLines(yReferenceLines);
        if (cleanedRefs.length() > 0) {
            chart.put("y_reference_lines", cleanedRefs);
        }
        attachV1Source(chart, n, new JSONArray(BOXPLOT_SOURCE_COLUMNS), n, false, 0, null, "boxplot(box_summary)");
        return chart;
    }

    private static double histNumber(ValueCollection row, String col, int r) throws BuildException {
        Double v = cellToNumericY(row, col, r, 0);
        if (v == null || !Double.isFinite(v)) {
            throw shapeMismatch(col + " must be a finite number (row " + r + ").", null);
        }
        return v;
    }

    private static double histNonNegativeInteger(ValueCollection row, String col, int r) throws BuildException {
        double v = histNumber(row, col, r);
        if (v < 0 || Math.rint(v) != v) {
            throw shapeMismatch(col + " must be a non-negative integer (row " + r + ").", null);
        }
        return v;
    }

    private static void attachV1Source(
            JSONObject chart,
            int rowCount,
            JSONArray sourceColumns,
            int pointCount,
            boolean truncationApplied,
            int filledMissingCombinations,
            Integer zeroValueCategoryCount,
            String transformSummary) {
        JSONObject src = new JSONObject();
        src.put("sourceColumns", sourceColumns);
        src.put("rowCount", rowCount);
        src.put("pointCount", pointCount);
        src.put("truncationApplied", truncationApplied);
        if (filledMissingCombinations > 0) {
            src.put("filledMissingCombinations", filledMissingCombinations);
        }
        if (zeroValueCategoryCount != null && zeroValueCategoryCount > 0) {
            src.put("zeroValueCategoryCount", zeroValueCategoryCount);
        }
        if (transformSummary != null && !transformSummary.isBlank()) {
            src.put("transformSummary", transformSummary.trim());
        }
        chart.put("source", src);
    }

    private static JSONObject buildLineScatterFromLongTable(
            InfoTable table,
            String kind,
            String xCol,
            String seriesCol,
            String yCol,
            String title,
            String xLabel,
            String yLabel,
            JsonNode yReferenceLines,
            JsonNode requestedTimeRange) throws BuildException {

        int nRows = table.getRowCount();
        XMode xMode = detectLineScatterXMode(table, xCol, nRows);
        LinkedHashSet<String> serOrder = new LinkedHashSet<>();
        Map<String, List<String>> seriesX = new LinkedHashMap<>();
        Map<String, List<Double>> seriesY = new LinkedHashMap<>();
        Set<String> seenPairs = new LinkedHashSet<>();
        for (int r = 0; r < nRows; r++) {
            ValueCollection row = table.getRow(r);
            if (row == null) {
                throw new BuildException("ROW_ALIGN_FAILED", "Missing row " + r, null);
            }
            String xk = xCellToLineScatterString(row, xCol, xMode, r);
            String sk = cellToStringCategory(row, seriesCol, r);
            Double yd = cellToNumericY(row, yCol, r, 0);
            if (yd == null) {
                JSONObject d = new JSONObject();
                d.put("row", r);
                d.put("yColumn", yCol);
                throw new BuildException("Y_COLUMN_NOT_NUMERIC", "Non-numeric Y value.", d);
            }
            String pairKey = xk + "\u0000" + sk;
            if (!seenPairs.add(pairKey)) {
                throw new BuildException("DUPLICATE_SERIES_CATEGORY",
                        "Duplicate xColumn+seriesColumn combination (no aggregation).", null);
            }
            serOrder.add(sk);
            seriesX.computeIfAbsent(sk, k -> new ArrayList<>()).add(xk);
            seriesY.computeIfAbsent(sk, k -> new ArrayList<>()).add(yd);
        }
        List<String> seriesNames = new ArrayList<>(serOrder);
        if (seriesNames.size() > MAX_SERIES) {
            JSONObject d = new JSONObject();
            d.put("seriesCount", seriesNames.size());
            d.put("maxSeries", MAX_SERIES);
            throw new BuildException("TOO_MANY_SERIES", "At most " + MAX_SERIES + " series.", d);
        }
        JSONArray seriesArr = new JSONArray();
        int pointCount = 0;
        for (String sn : seriesNames) {
            List<String> xs = seriesX.get(sn);
            List<Double> ys = seriesY.get(sn);
            JSONArray xj = new JSONArray();
            JSONArray yj = new JSONArray();
            for (int i = 0; i < xs.size(); i++) {
                xj.put(xs.get(i));
                yj.put(ys.get(i));
            }
            pointCount += xs.size();
            JSONObject ser = new JSONObject();
            ser.put("name", sn);
            ser.put("x", xj);
            ser.put("y", yj);
            seriesArr.put(ser);
        }
        JSONObject chart = new JSONObject();
        chart.put("kind", kind);
        if (title != null && !title.isBlank()) {
            chart.put("title", title.trim());
        }
        if (xLabel != null && !xLabel.isBlank()) {
            chart.put("x_label", xLabel.trim());
        }
        if (yLabel != null && !yLabel.isBlank()) {
            chart.put("y_label", yLabel.trim());
        }
        chart.put("series", seriesArr);
        JSONArray cleanedRefs = cleanYReferenceLines(yReferenceLines);
        if (cleanedRefs.length() > 0) {
            chart.put("y_reference_lines", cleanedRefs);
        }
        attachRequestedTimeRangeToChart(chart, kind, xMode, requestedTimeRange);
        JSONArray srcCols = new JSONArray();
        srcCols.put(xCol);
        srcCols.put(seriesCol);
        srcCols.put(yCol);
        attachV1Source(chart, nRows, srcCols, pointCount, false, 0, null,
                "long_table_pivot(seriesColumn)");
        return chart;
    }

    /**
     * C2b-3 (design §7.4): a {@code kind: "heatmap"} block from a long table such as a two-key
     * {@code group_metric} result: {@code xCol} is the column dimension, {@code seriesCol} the row
     * dimension, {@code yCol} the cell value. Rows and columns keep first-appearance order (sorting is
     * the tabulate tool's job); a repeated (row, column) pair is {@code DUPLICATE_CELL} (no silent
     * aggregation); a missing combination or a null value is an explicit {@code null} cell, never
     * zero-filled; a table with no numeric cell is {@code HEATMAP_ALL_MISSING}.
     */
    private static JSONObject buildHeatmapFromLongTable(
            InfoTable table,
            String xCol,
            String seriesCol,
            String yCol,
            String title,
            String xLabel,
            String yLabel) throws BuildException {
        LinkedHashSet<String> colOrder = new LinkedHashSet<>();
        LinkedHashSet<String> rowOrder = new LinkedHashSet<>();
        Map<String, Map<String, Double>> cell = new LinkedHashMap<>();
        int nRows = table.getRowCount();
        for (int r = 0; r < nRows; r++) {
            ValueCollection row = table.getRow(r);
            if (row == null) {
                throw new BuildException("ROW_ALIGN_FAILED", "Missing row " + r, null);
            }
            String colKey = cellToStringCategory(row, xCol, r);
            String rowKey = cellToStringCategory(row, seriesCol, r);
            Object raw = unwrapPrimitive(row.getValue(yCol));
            Double value = null;
            if (raw != null) {
                value = cellToNumericY(row, yCol, r, 0);
                if (value == null) {
                    JSONObject d = new JSONObject();
                    d.put("row", r);
                    d.put("yColumn", yCol);
                    throw new BuildException("Y_COLUMN_NOT_NUMERIC", "Non-numeric cell value.", d);
                }
                if (!Double.isFinite(value)) {
                    value = null;
                }
            }
            colOrder.add(colKey);
            rowOrder.add(rowKey);
            Map<String, Double> rowMap = cell.computeIfAbsent(rowKey, k -> new LinkedHashMap<>());
            if (rowMap.containsKey(colKey)) {
                JSONObject d = new JSONObject();
                d.put("row", rowKey);
                d.put("column", colKey);
                throw new BuildException("DUPLICATE_CELL",
                        "Duplicate (seriesColumn, xColumn) combination; aggregate first, the heatmap does not.", d);
            }
            rowMap.put(colKey, value);
        }
        if (rowOrder.size() > HEATMAP_MAX_ROWS) {
            JSONObject d = new JSONObject();
            d.put("dimension", "rows");
            d.put("count", rowOrder.size());
            d.put("max", HEATMAP_MAX_ROWS);
            throw new BuildException("TOO_MANY_CATEGORIES", "Too many heatmap rows (seriesColumn values).", d);
        }
        if (colOrder.size() > HEATMAP_MAX_COLS) {
            JSONObject d = new JSONObject();
            d.put("dimension", "cols");
            d.put("count", colOrder.size());
            d.put("max", HEATMAP_MAX_COLS);
            throw new BuildException("TOO_MANY_CATEGORIES", "Too many heatmap columns (xColumn values).", d);
        }
        JSONArray rows = new JSONArray(rowOrder);
        JSONArray cols = new JSONArray(colOrder);
        JSONArray values = new JSONArray();
        int missing = 0;
        int present = 0;
        for (String rk : rowOrder) {
            JSONArray line = new JSONArray();
            Map<String, Double> rowMap = cell.get(rk);
            for (String ck : colOrder) {
                Double v = rowMap != null ? rowMap.get(ck) : null;
                if (v == null) {
                    line.put(JSONObject.NULL);
                    missing++;
                } else {
                    line.put(v.doubleValue());
                    present++;
                }
            }
            values.put(line);
        }
        if (present == 0) {
            throw new BuildException("HEATMAP_ALL_MISSING", "Every heatmap cell is missing; nothing to draw.", null);
        }
        JSONObject heat = new JSONObject();
        heat.put("rows", rows);
        heat.put("cols", cols);
        heat.put("values", values);
        heat.put("valueLabel", yLabel != null && !yLabel.isBlank() ? yLabel.trim() : yCol);
        heat.put("missingCount", missing);
        JSONObject chart = new JSONObject();
        chart.put("kind", "heatmap");
        if (title != null && !title.isBlank()) {
            chart.put("title", title.trim());
        }
        chart.put("x_label", xLabel != null && !xLabel.isBlank() ? xLabel.trim() : xCol);
        chart.put("y_label", yLabel != null && !yLabel.isBlank() ? yLabel.trim() : seriesCol);
        chart.put("heatmap", heat);
        JSONArray sourceColumns = new JSONArray();
        sourceColumns.put(xCol);
        sourceColumns.put(seriesCol);
        sourceColumns.put(yCol);
        attachV1Source(chart, nRows, sourceColumns, present, false, 0, null,
                "heatmap(" + seriesCol + " × " + xCol + ")");
        return chart;
    }

    private static JSONObject buildBarFromLongTable(
            InfoTable table,
            String xCol,
            String seriesCol,
            String yCol,
            String title,
            String xLabel,
            String yLabel,
            JsonNode yReferenceLines,
            boolean sortBarByPrimaryYDescending) throws BuildException {

        LinkedHashSet<String> catOrder = new LinkedHashSet<>();
        LinkedHashSet<String> serOrder = new LinkedHashSet<>();
        Map<String, Map<String, Double>> cell = new LinkedHashMap<>();
        int nRows = table.getRowCount();
        List<String> allCols = columnNames(table);
        for (int r = 0; r < nRows; r++) {
            ValueCollection row = table.getRow(r);
            if (row == null) {
                throw new BuildException("ROW_ALIGN_FAILED", "Missing row " + r, null);
            }
            String xk = cellToStringCategory(row, xCol, r);
            String sk = cellToStringCategory(row, seriesCol, r);
            Double yd = cellToNumericY(row, yCol, r, 0);
            if (yd == null) {
                JSONObject d = new JSONObject();
                d.put("row", r);
                d.put("yColumn", yCol);
                throw new BuildException("Y_COLUMN_NOT_NUMERIC", "Non-numeric Y value.", d);
            }
            catOrder.add(xk);
            serOrder.add(sk);
            Map<String, Double> rowMap = cell.computeIfAbsent(xk, k -> new LinkedHashMap<>());
            if (rowMap.containsKey(sk)) {
                throw new BuildException("DUPLICATE_SERIES_CATEGORY",
                        "Duplicate xColumn+seriesColumn combination (no aggregation).", null);
            }
            rowMap.put(sk, yd);
        }
        List<String> categories = new ArrayList<>(catOrder);
        List<String> seriesNames = new ArrayList<>(serOrder);
        if (categories.size() > BAR_MAX_CATEGORIES) {
            JSONObject d = new JSONObject();
            d.put("categoryCount", categories.size());
            d.put("maxCategories", BAR_MAX_CATEGORIES);
            throw new BuildException("TOO_MANY_CATEGORIES", "Too many bar categories.", d);
        }
        if (seriesNames.size() > BAR_MAX_SERIES) {
            JSONObject d = new JSONObject();
            d.put("seriesCount", seriesNames.size());
            d.put("maxSeries", BAR_MAX_SERIES);
            throw new BuildException("TOO_MANY_SERIES", "Too many series for grouped bar.", d);
        }
        int filledMissing = 0;
        List<List<Double>> yMatrix = new ArrayList<>();
        for (String sn : seriesNames) {
            List<Double> col = new ArrayList<>();
            for (String cat : categories) {
                Map<String, Double> m = cell.get(cat);
                Double v = m != null ? m.get(sn) : null;
                if (v == null) {
                    col.add(0.0d);
                    filledMissing++;
                } else {
                    col.add(v);
                }
            }
            yMatrix.add(col);
        }
        List<String> xStrings = new ArrayList<>(categories);
        int n = xStrings.size();
        if (sortBarByPrimaryYDescending && n > 1 && !yMatrix.isEmpty()) {
            List<Integer> order = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
            List<List<Double>> yMatRef = yMatrix;
            order.sort((a, b) -> {
                double ya = yMatRef.get(0).get(a);
                double yb = yMatRef.get(0).get(b);
                int cmp = Double.compare(yb, ya);
                if (cmp != 0) {
                    return cmp;
                }
                return Integer.compare(a, b);
            });
            List<String> xSorted = new ArrayList<>(n);
            List<List<Double>> ySorted = new ArrayList<>();
            for (int s = 0; s < yMatrix.size(); s++) {
                ySorted.add(new ArrayList<>(n));
            }
            for (int idx : order) {
                xSorted.add(xStrings.get(idx));
                for (int s = 0; s < yMatrix.size(); s++) {
                    ySorted.get(s).add(yMatRef.get(s).get(idx));
                }
            }
            xStrings = xSorted;
            yMatrix = ySorted;
        }

        JSONArray seriesArr = new JSONArray();
        for (int si = 0; si < seriesNames.size(); si++) {
            JSONArray xj = new JSONArray();
            JSONArray yj = new JSONArray();
            for (int r = 0; r < n; r++) {
                xj.put(xStrings.get(r));
                yj.put(yMatrix.get(si).get(r));
            }
            JSONObject ser = new JSONObject();
            ser.put("name", seriesNames.get(si));
            ser.put("x", xj);
            ser.put("y", yj);
            seriesArr.put(ser);
        }
        JSONObject chart = new JSONObject();
        chart.put("kind", "bar");
        if (title != null && !title.isBlank()) {
            chart.put("title", title.trim());
        }
        if (xLabel != null && !xLabel.isBlank()) {
            chart.put("x_label", xLabel.trim());
        }
        if (yLabel != null && !yLabel.isBlank()) {
            chart.put("y_label", yLabel.trim());
        }
        chart.put("series", seriesArr);
        JSONArray cleanedRefs = cleanYReferenceLines(yReferenceLines);
        if (cleanedRefs.length() > 0) {
            chart.put("y_reference_lines", cleanedRefs);
        }
        JSONArray srcCols = new JSONArray();
        srcCols.put(xCol);
        srcCols.put(seriesCol);
        srcCols.put(yCol);
        attachV1Source(chart, nRows, srcCols, n * seriesNames.size(), false, filledMissing, null,
                "grouped_bar(seriesColumn)");
        return chart;
    }

    private static JSONObject buildPieChartBlock(
            InfoTable table,
            String xCol,
            String yCol,
            String title,
            String xLabel,
            String yLabel,
            String pieSliceModeRaw,
            int pieMaxSlicesIn) throws BuildException {

        String mode = pieSliceModeRaw == null || pieSliceModeRaw.isBlank()
                ? "top_with_other"
                : pieSliceModeRaw.trim().toLowerCase(Locale.ROOT);
        if (!"top_with_other".equals(mode) && !"all_nonzero".equals(mode)) {
            throw new BuildException("INVALID_PARAMETERS", "pieSliceMode must be top_with_other or all_nonzero.", null);
        }
        int maxSlices = pieMaxSlicesIn > 0 ? pieMaxSlicesIn : PIE_DEFAULT_MAX_SLICES;
        if (maxSlices < 2 || maxSlices > PIE_HARD_MAX_SLICES) {
            JSONObject d = new JSONObject();
            d.put("pieMaxSlices", maxSlices);
            d.put("min", 2);
            d.put("max", PIE_HARD_MAX_SLICES);
            throw new BuildException("INVALID_PARAMETERS", "pieMaxSlices must be between 2 and " + PIE_HARD_MAX_SLICES + ".", d);
        }

        int nRows = table.getRowCount();
        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        LinkedHashSet<String> seenNonZeroLabels = new LinkedHashSet<>();
        int zeroCount = 0;
        for (int r = 0; r < nRows; r++) {
            ValueCollection row = table.getRow(r);
            if (row == null) {
                throw new BuildException("ROW_ALIGN_FAILED", "Missing row " + r, null);
            }
            String lab = cellToStringCategory(row, xCol, r);
            Double yd = cellToNumericY(row, yCol, r, 0);
            if (yd == null) {
                JSONObject d = new JSONObject();
                d.put("row", r);
                d.put("yColumn", yCol);
                throw new BuildException("Y_COLUMN_NOT_NUMERIC", "Non-numeric Y value.", d);
            }
            if (yd < 0) {
                JSONObject d = new JSONObject();
                d.put("row", r);
                d.put("value", yd);
                throw new BuildException("PIE_NEGATIVE_VALUE", "Pie y must be non-negative.", d);
            }
            if (yd == 0.0d) {
                zeroCount++;
                continue;
            }
            if (!seenNonZeroLabels.add(lab)) {
                JSONObject d = new JSONObject();
                d.put("row", r);
                d.put("xColumn", xCol);
                d.put("label", lab);
                throw new BuildException("DUPLICATE_SLICE_LABEL", "Duplicate pie slice label (xColumn).", d);
            }
            labels.add(lab);
            values.add(yd);
        }
        int nz = labels.size();
        if (nz == 0) {
            throw new BuildException("PIE_ZERO_TOTAL", "No non-zero pie slices after excluding zeros.", null);
        }
        double sum = 0.0d;
        for (Double v : values) {
            sum += v;
        }
        if (sum <= 0.0d || !Double.isFinite(sum)) {
            throw new BuildException("PIE_ZERO_TOTAL", "Pie total is zero.", null);
        }

        List<Integer> ord = new ArrayList<>(nz);
        for (int i = 0; i < nz; i++) {
            ord.add(i);
        }
        ord.sort(Comparator.comparing((Integer i) -> values.get(i)).reversed());

        boolean truncation = false;
        List<String> outLabels = new ArrayList<>();
        List<Double> outVals = new ArrayList<>();
        if ("all_nonzero".equals(mode)) {
            if (nz > maxSlices) {
                JSONObject d = new JSONObject();
                d.put("nonZeroSliceCount", nz);
                d.put("maxAllowed", maxSlices);
                throw new BuildException("TOO_MANY_SLICES", "Too many non-zero pie slices for all_nonzero mode.", d);
            }
            for (int i : ord) {
                outLabels.add(labels.get(i));
                outVals.add(values.get(i));
            }
        } else {
            int cap = maxSlices;
            if (nz > cap) {
                truncation = true;
                int keep = cap - 1;
                double otherSum = 0.0d;
                for (int k = 0; k < nz; k++) {
                    int i = ord.get(k);
                    if (k < keep) {
                        outLabels.add(labels.get(i));
                        outVals.add(values.get(i));
                    } else {
                        otherSum += values.get(i);
                    }
                }
                outLabels.add("Other");
                outVals.add(otherSum);
            } else {
                for (int i : ord) {
                    outLabels.add(labels.get(i));
                    outVals.add(values.get(i));
                }
            }
        }

        JSONArray xj = new JSONArray();
        JSONArray yj = new JSONArray();
        for (int i = 0; i < outLabels.size(); i++) {
            xj.put(outLabels.get(i));
            yj.put(outVals.get(i));
        }
        JSONObject ser = new JSONObject();
        ser.put("name", xLabel != null && !xLabel.isBlank() ? xLabel.trim() : xCol);
        ser.put("x", xj);
        ser.put("y", yj);
        JSONArray seriesArr = new JSONArray();
        seriesArr.put(ser);

        JSONObject chart = new JSONObject();
        chart.put("kind", "pie");
        if (title != null && !title.isBlank()) {
            chart.put("title", title.trim());
        }
        if (xLabel != null && !xLabel.isBlank()) {
            chart.put("x_label", xLabel.trim());
        }
        if (yLabel != null && !yLabel.isBlank()) {
            chart.put("y_label", yLabel.trim());
        }
        chart.put("series", seriesArr);
        JSONArray srcCols = new JSONArray();
        srcCols.put(xCol);
        srcCols.put(yCol);
        attachV1Source(chart, nRows, srcCols, outLabels.size(), truncation, 0,
                zeroCount > 0 ? zeroCount : null, "pie(" + mode + ")");
        return chart;
    }

    /**
     * Optional X domain for datetime line/scatter tabular charts (see Parler {@code CONTRACTS/CHART_CONTRACT.md}).
     */
    private static void attachRequestedTimeRangeToChart(
            JSONObject chart, String kind, XMode xMode, JsonNode requestedTimeRange) {
        if (requestedTimeRange == null || !requestedTimeRange.isObject()) {
            return;
        }
        if (!"line".equals(kind) && !"scatter".equals(kind)) {
            return;
        }
        if (xMode != XMode.DATETIME) {
            return;
        }
        JsonNode s = requestedTimeRange.get("start");
        JsonNode e = requestedTimeRange.get("end");
        if (s == null || !s.isTextual() || e == null || !e.isTextual()) {
            return;
        }
        String rs = s.asText().trim();
        String re = e.asText().trim();
        if (rs.isEmpty() || re.isEmpty()) {
            return;
        }
        JSONObject o = new JSONObject();
        o.put("start", rs);
        o.put("end", re);
        chart.put("requested_time_range", o);
    }

    private static XMode detectLineScatterXMode(InfoTable table, String xCol, int n) throws BuildException {
        XMode mode = null;
        for (int r = 0; r < n; r++) {
            ValueCollection row = table.getRow(r);
            if (row == null) {
                continue;
            }
            XMode rowMode = classifyLineScatterCell(row, xCol);
            if (rowMode == null) {
                Object rawX = unwrapPrimitive(row.getValue(xCol));
                if (rawX == null) {
                    JSONObject d = new JSONObject();
                    d.put("row", r);
                    d.put("column", xCol);
                    throw new BuildException("ROW_ALIGN_FAILED",
                            "Null or missing line/scatter X at row " + r, d);
                }
                String sx = String.valueOf(rawX).trim();
                if (sx.isEmpty()) {
                    JSONObject d = new JSONObject();
                    d.put("row", r);
                    d.put("column", xCol);
                    throw new BuildException("ROW_ALIGN_FAILED",
                            "Empty line/scatter X at row " + r, d);
                }
                throw new BuildException("X_COLUMN_INVALID_LINE_SCATTER",
                        "X value is neither a finite number nor a parseable datetime at row " + r, null);
            }
            if (mode == null) {
                mode = rowMode;
            } else if (mode != rowMode) {
                throw new BuildException("X_AXIS_TYPE_MIXED",
                        "X column mixes numeric and datetime interpretation.", null);
            }
        }
        if (mode == null) {
            throw new BuildException("X_COLUMN_INVALID_LINE_SCATTER", "No X values.", null);
        }
        return mode;
    }

    private static XMode classifyLineScatterCell(ValueCollection row, String xCol) {
        Object raw = unwrapPrimitive(row.getValue(xCol));
        if (raw == null) {
            return null;
        }
        if (raw instanceof DateTime) {
            return XMode.DATETIME;
        }
        if (raw instanceof Number) {
            double d = ((Number) raw).doubleValue();
            return Double.isFinite(d) ? XMode.NUMERIC : null;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? XMode.NUMERIC : null;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        try {
            DateTime.parse(s);
            return XMode.DATETIME;
        } catch (Exception e) {
            return null;
        }
    }

    private static String xCellToLineScatterString(ValueCollection row, String xCol, XMode mode, int rowIndex)
            throws BuildException {
        Object raw = unwrapPrimitive(row.getValue(xCol));
        if (raw == null) {
            JSONObject d = new JSONObject();
            d.put("row", rowIndex);
            d.put("column", xCol);
            throw new BuildException("ROW_ALIGN_FAILED", "Null or missing line/scatter X at row " + rowIndex, d);
        }
        if (!(raw instanceof DateTime) && !(raw instanceof Number) && String.valueOf(raw).trim().isEmpty()) {
            JSONObject d = new JSONObject();
            d.put("row", rowIndex);
            d.put("column", xCol);
            throw new BuildException("ROW_ALIGN_FAILED", "Empty line/scatter X at row " + rowIndex, d);
        }
        if (mode == XMode.NUMERIC) {
            if (raw instanceof Number) {
                double d = ((Number) raw).doubleValue();
                if (!Double.isFinite(d)) {
                    throw new BuildException("X_COLUMN_INVALID_LINE_SCATTER", "Non-finite X", null);
                }
                return Double.toString(d);
            }
            try {
                double d = Double.parseDouble(String.valueOf(raw).trim());
                if (!Double.isFinite(d)) {
                    throw new BuildException("X_COLUMN_INVALID_LINE_SCATTER", "Non-finite X", null);
                }
                return Double.toString(d);
            } catch (NumberFormatException e) {
                throw new BuildException("X_COLUMN_INVALID_LINE_SCATTER", "X not numeric", null);
            }
        }
        if (mode == XMode.DATETIME) {
            if (raw instanceof DateTime) {
                return raw.toString();
            }
            try {
                return DateTime.parse(String.valueOf(raw).trim()).toString();
            } catch (Exception e) {
                throw new BuildException("X_COLUMN_INVALID_LINE_SCATTER", "X not datetime", null);
            }
        }
        throw new BuildException("CHART_BUILD_INTERNAL", "unexpected X mode", null);
    }

    private static String cellToStringCategory(ValueCollection row, String col, int rowIndex)
            throws BuildException {
        Object raw = unwrapPrimitive(row.getValue(col));
        if (raw == null) {
            JSONObject d = new JSONObject();
            d.put("row", rowIndex);
            d.put("column", col);
            throw new BuildException("ROW_ALIGN_FAILED", "Null or missing category X at row " + rowIndex, d);
        }
        if (raw instanceof DateTime) {
            return raw.toString();
        }
        return String.valueOf(raw).trim();
    }

    private static Double cellToNumericY(ValueCollection row, String col, int rowIndex, int seriesIdx)
            throws BuildException {
        Object raw = unwrapPrimitive(row.getValue(col));
        if (raw == null) {
            JSONObject d = new JSONObject();
            d.put("row", rowIndex);
            d.put("seriesIndex", seriesIdx);
            throw new BuildException("ROW_ALIGN_FAILED", "Null Y at row " + rowIndex, d);
        }
        if (raw instanceof Boolean) {
            return ((Boolean) raw) ? 1.0 : 0.0;
        }
        if (raw instanceof Number) {
            double d = ((Number) raw).doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        String asString = String.valueOf(raw).trim();
        try {
            double d = Double.parseDouble(asString);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            if ("true".equalsIgnoreCase(asString)) {
                return 1.0;
            }
            if ("false".equalsIgnoreCase(asString)) {
                return 0.0;
            }
            return null;
        }
    }

    private static Object unwrapPrimitive(Object o) {
        if (o instanceof IPrimitiveType) {
            try {
                return ((IPrimitiveType) o).getValue();
            } catch (Exception e) {
                return null;
            }
        }
        return o;
    }

    private static void requireColumn(List<String> cols, String need, String label) throws BuildException {
        if (!cols.contains(need)) {
            JSONObject d = new JSONObject();
            d.put("column", need);
            d.put("label", label);
            JSONArray avail = new JSONArray();
            for (String c : cols) {
                avail.put(c);
            }
            d.put("availableColumns", avail);
            throw new BuildException("COLUMN_NOT_FOUND", "Column not in table: " + need, d);
        }
    }

    private static List<String> columnNames(InfoTable it) {
        List<String> names = new ArrayList<>();
        try {
            DataShapeDefinition ds = it.getDataShape();
            if (ds != null) {
                FieldDefinitionCollection fc = ds.getFields();
                if (fc != null && fc.values() != null) {
                    for (FieldDefinition f : fc.values()) {
                        if (f.getName() != null) {
                            names.add(f.getName());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (names.isEmpty() && it.getRowCount() > 0) {
            ValueCollection row = it.getRow(0);
            if (row != null) {
                try {
                    for (String k : row.keySet()) {
                        names.add(k);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return names;
    }

    private static JSONArray cleanYReferenceLines(JsonNode yrl) {
        JSONArray cleaned = new JSONArray();
        if (yrl == null || !yrl.isArray()) {
            return cleaned;
        }
        for (JsonNode item : yrl) {
            if (cleaned.length() >= 12) {
                break;
            }
            if (item == null || !item.isObject() || !item.has("y") || item.get("y").isNull()) {
                continue;
            }
            double y = item.get("y").asDouble();
            if (Double.isNaN(y) || Double.isInfinite(y)) {
                continue;
            }
            JSONObject one = new JSONObject();
            one.put("y", y);
            if (item.has("label") && item.get("label").isTextual()) {
                String lab = item.get("label").asText().trim();
                if (!lab.isEmpty()) {
                    one.put("label", lab);
                }
            }
            String role = "limit";
            if (item.has("role") && item.get("role").isTextual()) {
                String rs = item.get("role").asText().trim().toLowerCase(Locale.ROOT);
                if (REF_ROLES.contains(rs)) {
                    role = rs;
                }
            }
            one.put("role", role);
            cleaned.put(one);
        }
        return cleaned;
    }

    /**
     * Convert JSON row objects into an {@link InfoTable}. Column base types are inferred from JSON cells so
     * booleans stay {@link BaseTypes#BOOLEAN} (chart Y 0/1) and numbers stay {@link BaseTypes#NUMBER}, matching
     * cached INFOTABLE behaviour (the inline path must not stringify booleans).
     */
    public static InfoTable infoTableFromJsonRows(JsonNode rows) throws BuildException {
        if (rows == null || !rows.isArray() || rows.size() == 0) {
            throw new BuildException("SOURCE_RESULT_NOT_TABULAR", "rows array missing or empty", null);
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        JsonNode row0 = rows.get(0);
        if (row0 == null || !row0.isObject()) {
            throw new BuildException("SOURCE_RESULT_NOT_TABULAR", "row 0 not an object", null);
        }
        row0.fieldNames().forEachRemaining(keys::add);
        List<String> colOrder = new ArrayList<>(keys);

        Map<String, ColKind> colKinds = new LinkedHashMap<>();
        for (String c : colOrder) {
            colKinds.put(c, inferColumnKind(rows, c));
        }

        DataShapeDefinition shape = new DataShapeDefinition();
        for (String c : colOrder) {
            FieldDefinition fd = new FieldDefinition();
            fd.setName(c);
            fd.setBaseType(baseTypeFor(colKinds.get(c)));
            shape.addFieldDefinition(fd);
        }
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < rows.size(); i++) {
            JsonNode r = rows.get(i);
            if (r == null || !r.isObject()) {
                throw new BuildException("ROW_ALIGN_FAILED", "row " + i + " invalid", null);
            }
            ValueCollection vc = new ValueCollection();
            for (String c : colOrder) {
                IPrimitiveType cell = primitiveFromJson(r.get(c), colKinds.get(c));
                vc.put(c, cell);
            }
            table.addRow(vc);
        }
        return table;
    }

    private static ColKind inferColumnKind(JsonNode rows, String col) {
        boolean hasBool = false;
        boolean hasNum = false;
        boolean hasText = false;
        for (int i = 0; i < rows.size(); i++) {
            JsonNode r = rows.get(i);
            if (r == null || !r.isObject()) {
                continue;
            }
            JsonNode v = r.get(col);
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isBoolean()) {
                hasBool = true;
            } else if (v.isNumber()) {
                hasNum = true;
            } else if (v.isTextual()) {
                hasText = true;
            } else {
                hasText = true;
            }
        }
        if (hasText) {
            return ColKind.STRING;
        }
        if (hasBool && hasNum) {
            return ColKind.NUMBER;
        }
        if (hasBool) {
            return ColKind.BOOLEAN;
        }
        if (hasNum) {
            return ColKind.NUMBER;
        }
        return ColKind.STRING;
    }

    private static BaseTypes baseTypeFor(ColKind kind) {
        switch (kind) {
            case BOOLEAN:
                return BaseTypes.BOOLEAN;
            case NUMBER:
                return BaseTypes.NUMBER;
            default:
                return BaseTypes.STRING;
        }
    }

    /**
     * @return typed primitive, or {@code null} for absent field / JSON null / value not representable as
     *         {@code kind} (no default fabrication — v1 row holes fail downstream with {@code ROW_ALIGN_FAILED}
     *         or related errors).
     */
    private static IPrimitiveType primitiveFromJson(JsonNode v, ColKind kind) {
        if (v == null || v.isNull()) {
            return null;
        }
        switch (kind) {
            case BOOLEAN:
                if (v.isBoolean()) {
                    return new BooleanPrimitive(v.asBoolean());
                }
                if (v.isNumber()) {
                    return new BooleanPrimitive(v.asDouble() != 0.0d);
                }
                if (v.isTextual()) {
                    String t = v.asText().trim();
                    if ("true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t)) {
                        return new BooleanPrimitive(Boolean.parseBoolean(t));
                    }
                }
                return null;
            case NUMBER:
                if (v.isBoolean()) {
                    return new NumberPrimitive(v.asBoolean() ? 1.0 : 0.0);
                }
                if (v.isNumber()) {
                    return new NumberPrimitive(v.asDouble());
                }
                if (v.isTextual()) {
                    try {
                        double d = Double.parseDouble(v.asText().trim());
                        if (Double.isFinite(d)) {
                            return new NumberPrimitive(d);
                        }
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
                return null;
            default:
                return new StringPrimitive(jsonCellToText(v));
        }
    }

    private static String jsonCellToText(JsonNode v) {
        if (v == null || v.isNull()) {
            return "";
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? "true" : "false";
        }
        if (v.isIntegralNumber()) {
            return String.valueOf(v.longValue());
        }
        if (v.isNumber()) {
            return String.valueOf(v.doubleValue());
        }
        return v.asText("");
    }
}
