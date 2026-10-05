package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.data.filters.IFilter;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import com.thingworx.things.agent.analysis.stats.LinearPercentile;
import com.thingworx.things.agent.tools.predicate.ParlerQueryFilterParser;

import org.slf4j.Logger;

/**
 * D1 distribution operators of {@code tabulate_cached_result} (chart-enhancement design §7.4):
 * {@code bin_numeric} and {@code box_summary}. Both are decision-class modes (same scan and cell
 * budgets as {@code group_metric}), accept the shared {@code filters}, and emit a self-describing
 * fixed-column table whose scalar columns repeat per row, so a chart builder can validate the
 * source shape without any cache side metadata. The statistics live here only; renderers and
 * chart builders never re-bin, re-quantile or re-fence.
 *
 * <p>All numeric output columns are {@code NUMBER}; the count columns hold integral values (they
 * serialise as integral JSON numbers such as {@code 7.0}) so consumers validate integrality, not a
 * base type. Method identifiers are persisted with every result and change whenever the rule changes:
 * {@link #METHOD_EXPLICIT_EDGES}, {@link #METHOD_EQUAL_WIDTH}, {@link #METHOD_BOX}.</p>
 *
 * <p>Envelope: non-empty output is stored as its own derived cache ({@code cacheId}) with the
 * input table as {@code sourceCacheId}; the result kinds are {@code _INLINE} / {@code _EMPTY} only
 * (at most 50 bins or 24 groups). The EMPTY kinds clear the chartable {@code last_invoke} target
 * in {@link TabularChartRoundHooks} so an empty distribution never charts the previous table.</p>
 */
public final class CachedTabularDistributionExecutor {

    private static final Logger LOG = LogUtilities.getInstance()
            .getApplicationLogger(CachedTabularDistributionExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String MODE_BIN_NUMERIC = "bin_numeric";
    public static final String MODE_BOX_SUMMARY = "box_summary";

    public static final String METHOD_EXPLICIT_EDGES = "explicit_edges_v1";
    public static final String METHOD_EQUAL_WIDTH = "equal_width_v1";
    public static final String METHOD_BOX = "tukey_1_5_iqr_linear_p_v1";

    public static final int MAX_BINS = 50;
    public static final int MAX_BOX_GROUPS = 24;
    public static final int MAX_SHOWN_OUTLIERS = 20;

    public static final String RESULT_BIN_INLINE = "CACHED_BIN_NUMERIC_INLINE";
    public static final String RESULT_BIN_EMPTY = "CACHED_BIN_NUMERIC_EMPTY";
    public static final String RESULT_BOX_INLINE = "CACHED_BOX_SUMMARY_INLINE";
    public static final String RESULT_BOX_EMPTY = "CACHED_BOX_SUMMARY_EMPTY";

    /** Output columns, in order, of {@code bin_numeric}. */
    public static final List<String> BIN_COLUMNS = List.of("binIndex", "binStart", "binEnd", "count", "density",
            "validCount", "excludedCount", "belowRangeCount", "aboveRangeCount", "method");
    /** Output columns, in order, of {@code box_summary}. */
    public static final List<String> BOX_COLUMNS = List.of("groupKey", "n", "excludedCount", "min", "q1", "median",
            "q3", "max", "whiskerLow", "whiskerHigh", "outlierCount", "outliers", "method");

    private CachedTabularDistributionExecutor() {}

    // ------------------------------------------------------------------ bin_numeric

    public static String binNumeric(String readCacheId, InfoTable src, DataShapeDefinition shape, JsonNode root)
            throws Exception {
        if (root.has("groupBy") && !root.get("groupBy").isNull()) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    "bin_numeric does not support groupBy.");
        }
        String column = requiredNumericColumn(src, shape, root, MODE_BIN_NUMERIC);
        JsonNode edgesNode = root.get("binEdges");
        JsonNode countNode = root.get("binCount");
        boolean hasEdges = edgesNode != null && !edgesNode.isNull();
        boolean hasCount = countNode != null && !countNode.isNull();
        if (hasEdges == hasCount) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    "bin_numeric requires exactly one of binEdges or binCount.");
        }
        JsonNode rangeMinNode = root.get("rangeMin");
        JsonNode rangeMaxNode = root.get("rangeMax");
        boolean hasRangeMin = rangeMinNode != null && !rangeMinNode.isNull();
        boolean hasRangeMax = rangeMaxNode != null && !rangeMaxNode.isNull();
        if ((hasRangeMin || hasRangeMax) && !hasCount) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    "rangeMin / rangeMax apply only together with binCount.");
        }
        double[] explicitEdges = null;
        int binCount = 0;
        if (hasEdges) {
            explicitEdges = parseEdges(edgesNode);
        } else {
            if (!countNode.isIntegralNumber()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "binCount must be an integer in 1.." + MAX_BINS + ".");
            }
            binCount = countNode.intValue();
            if (binCount < 1 || binCount > MAX_BINS) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "binCount must be in 1.." + MAX_BINS + ".");
            }
        }
        Double rangeMin = null;
        Double rangeMax = null;
        if (hasRangeMin) {
            rangeMin = finiteOrThrow(rangeMinNode, "rangeMin");
        }
        if (hasRangeMax) {
            rangeMax = finiteOrThrow(rangeMaxNode, "rangeMax");
        }
        if (rangeMin != null && rangeMax != null && rangeMin >= rangeMax) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "rangeMin must be less than rangeMax.");
        }

        IFilter filter = parseFilters(root, shape);
        Scan scan = scanNumeric(src, column, filter);
        int validCount = scan.values.size();
        int excludedCount = scan.excludedCount;

        double[] edges;
        String method;
        if (explicitEdges != null) {
            edges = explicitEdges;
            method = METHOD_EXPLICIT_EDGES;
        } else {
            method = METHOD_EQUAL_WIDTH;
            if (validCount == 0 && (rangeMin == null || rangeMax == null)) {
                return emptyEnvelope(readCacheId, src, BIN_COLUMNS, RESULT_BIN_EMPTY, binScalars(validCount,
                        excludedCount, 0, 0, method, column));
            }
            double lo = rangeMin != null ? rangeMin : min(scan.values);
            double hi = rangeMax != null ? rangeMax : max(scan.values);
            if (rangeMin != null && rangeMax == null && hi < lo) {
                hi = lo;
            }
            if (rangeMax != null && rangeMin == null && lo > hi) {
                lo = hi;
            }
            if (lo == hi) {
                edges = new double[] {lo - 0.5, lo + 0.5};
            } else {
                edges = new double[binCount + 1];
                for (int i = 0; i <= binCount; i++) {
                    edges[i] = lo + i * (hi - lo) / binCount;
                }
                edges[binCount] = hi;
            }
        }
        int k = edges.length - 1;
        long[] counts = new long[k];
        int below = 0;
        int above = 0;
        double first = edges[0];
        double last = edges[k];
        for (double v : scan.values) {
            if (v < first) {
                below++;
                continue;
            }
            if (v > last) {
                above++;
                continue;
            }
            int idx = binIndexOf(edges, v);
            counts[idx]++;
        }
        long inRange = 0;
        for (long c : counts) {
            inRange += c;
        }
        ObjectNode scalars = binScalars(validCount, excludedCount, below, above, method, column);
        if (inRange == 0) {
            return emptyEnvelope(readCacheId, src, BIN_COLUMNS, RESULT_BIN_EMPTY, scalars);
        }
        InfoTable out = newTable(BIN_COLUMNS, List.of(BaseTypes.NUMBER, BaseTypes.NUMBER, BaseTypes.NUMBER,
                BaseTypes.NUMBER, BaseTypes.NUMBER, BaseTypes.NUMBER, BaseTypes.NUMBER, BaseTypes.NUMBER,
                BaseTypes.NUMBER, BaseTypes.STRING));
        for (int i = 0; i < k; i++) {
            double width = edges[i + 1] - edges[i];
            double density = width > 0 ? counts[i] / (inRange * width) : 0.0;
            ValueCollection row = new ValueCollection();
            row.put("binIndex", new NumberPrimitive((double) (i)));
            row.put("binStart", new NumberPrimitive(edges[i]));
            row.put("binEnd", new NumberPrimitive(edges[i + 1]));
            row.put("count", new NumberPrimitive((double) counts[i]));
            row.put("density", new NumberPrimitive(density));
            row.put("validCount", new NumberPrimitive((double) (validCount)));
            row.put("excludedCount", new NumberPrimitive((double) (excludedCount)));
            row.put("belowRangeCount", new NumberPrimitive((double) (below)));
            row.put("aboveRangeCount", new NumberPrimitive((double) (above)));
            row.put("method", new StringPrimitive(method));
            out.addRow(row);
        }
        scalars.put("inRangeCount", inRange);
        scalars.put("binCount", k);
        return inlineEnvelope(readCacheId, src, out, BIN_COLUMNS, RESULT_BIN_INLINE,
                "tabulate_cached_result.bin_numeric", scalars);
    }

    /** {@code edges[i] <= v < edges[i+1]}; the last bin also contains its right endpoint. */
    static int binIndexOf(double[] edges, double v) {
        int k = edges.length - 1;
        if (v >= edges[k]) {
            return k - 1;
        }
        int lo = 0;
        int hi = k - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (edges[mid] <= v) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private static double[] parseEdges(JsonNode edgesNode) throws CachedTabularDecisionToolException {
        if (!edgesNode.isArray() || edgesNode.size() < 2 || edgesNode.size() > MAX_BINS + 1) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    "binEdges must be an array of 2.." + (MAX_BINS + 1) + " strictly increasing finite numbers.");
        }
        double[] edges = new double[edgesNode.size()];
        for (int i = 0; i < edges.length; i++) {
            JsonNode e = edgesNode.get(i);
            if (e == null || !e.isNumber() || !Double.isFinite(e.doubleValue())) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "binEdges[" + i + "] must be a finite number.");
            }
            edges[i] = e.doubleValue();
            if (i > 0 && !(edges[i] > edges[i - 1])) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "binEdges must be strictly increasing.");
            }
        }
        return edges;
    }

    private static ObjectNode binScalars(int validCount, int excludedCount, int below, int above, String method,
            String column) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("column", column);
        o.put("method", method);
        o.put("validCount", validCount);
        o.put("excludedCount", excludedCount);
        o.put("belowRangeCount", below);
        o.put("aboveRangeCount", above);
        return o;
    }

    // ------------------------------------------------------------------ box_summary

    public static String boxSummary(String readCacheId, InfoTable src, DataShapeDefinition shape, JsonNode root)
            throws Exception {
        String column = requiredNumericColumn(src, shape, root, MODE_BOX_SUMMARY);
        String groupCol = parseSingleGroupBy(src, shape, root.get("groupBy"), column);
        IFilter filter = parseFilters(root, shape);

        Map<String, List<Double>> groups = new LinkedHashMap<>();
        Map<String, Integer> excluded = new LinkedHashMap<>();
        int n = src.getRowCount();
        for (int i = 0; i < n; i++) {
            ValueCollection row = src.getRow(i);
            if (row == null) {
                continue;
            }
            if (filter != null && !CachedTabularDecisionPredicate.evaluateIfilter(filter, row)) {
                continue;
            }
            String key = groupCol == null ? "All" : groupKeyText(row.getValue(groupCol));
            if (!groups.containsKey(key)) {
                if (groups.size() >= MAX_BOX_GROUPS) {
                    throw new CachedTabularDecisionToolException("TOO_MANY_GROUPS",
                            "box_summary supports at most " + MAX_BOX_GROUPS + " groups; narrow with filters or "
                                    + "group on a coarser column.");
                }
                groups.put(key, new ArrayList<>());
                excluded.put(key, 0);
            }
            Double v = numericCell(row.getValue(column));
            if (v == null) {
                excluded.put(key, excluded.get(key) + 1);
            } else {
                groups.get(key).add(v);
            }
        }
        InfoTable out = newTable(BOX_COLUMNS, List.of(BaseTypes.STRING, BaseTypes.NUMBER, BaseTypes.NUMBER,
                BaseTypes.NUMBER, BaseTypes.NUMBER, BaseTypes.NUMBER, BaseTypes.NUMBER, BaseTypes.NUMBER,
                BaseTypes.NUMBER, BaseTypes.NUMBER, BaseTypes.NUMBER, BaseTypes.STRING, BaseTypes.STRING));
        int groupsWithValues = 0;
        int totalExcluded = 0;
        for (Map.Entry<String, List<Double>> g : groups.entrySet()) {
            List<Double> values = g.getValue();
            int ex = excluded.get(g.getKey());
            totalExcluded += ex;
            if (values.isEmpty()) {
                continue;
            }
            groupsWithValues++;
            double[] sorted = new double[values.size()];
            for (int i = 0; i < sorted.length; i++) {
                sorted[i] = values.get(i);
            }
            Arrays.sort(sorted);
            BoxStats st = boxStats(sorted);
            ValueCollection row = new ValueCollection();
            row.put("groupKey", new StringPrimitive(g.getKey()));
            row.put("n", new NumberPrimitive((double) (sorted.length)));
            row.put("excludedCount", new NumberPrimitive((double) (ex)));
            row.put("min", new NumberPrimitive(st.min));
            row.put("q1", new NumberPrimitive(st.q1));
            row.put("median", new NumberPrimitive(st.median));
            row.put("q3", new NumberPrimitive(st.q3));
            row.put("max", new NumberPrimitive(st.max));
            row.put("whiskerLow", new NumberPrimitive(st.whiskerLow));
            row.put("whiskerHigh", new NumberPrimitive(st.whiskerHigh));
            row.put("outlierCount", new NumberPrimitive((double) (st.outlierCount)));
            row.put("outliers", new StringPrimitive(MAPPER.writeValueAsString(st.shownOutliers)));
            row.put("method", new StringPrimitive(METHOD_BOX));
            out.addRow(row);
        }
        ObjectNode scalars = MAPPER.createObjectNode();
        scalars.put("column", column);
        if (groupCol != null) {
            scalars.put("groupBy", groupCol);
        }
        scalars.put("method", METHOD_BOX);
        scalars.put("groupCount", groupsWithValues);
        scalars.put("excludedCount", totalExcluded);
        if (groupsWithValues == 0) {
            return emptyEnvelope(readCacheId, src, BOX_COLUMNS, RESULT_BOX_EMPTY, scalars);
        }
        return inlineEnvelope(readCacheId, src, out, BOX_COLUMNS, RESULT_BOX_INLINE,
                "tabulate_cached_result.box_summary", scalars);
    }

    static final class BoxStats {
        double min;
        double q1;
        double median;
        double q3;
        double max;
        double whiskerLow;
        double whiskerHigh;
        int outlierCount;
        ArrayNode shownOutliers;
    }

    /**
     * Tukey 1.5 × IQR with type-7 linear quartiles ({@link LinearPercentile}): whiskers are the most
     * extreme <em>observed</em> values inside the fences; values outside are outliers, all counted,
     * the {@value #MAX_SHOWN_OUTLIERS} farthest from their nearest fence listed.
     */
    static BoxStats boxStats(double[] sorted) {
        BoxStats st = new BoxStats();
        st.min = sorted[0];
        st.max = sorted[sorted.length - 1];
        st.q1 = LinearPercentile.ofSorted(sorted, 0.25);
        st.median = LinearPercentile.ofSorted(sorted, 0.5);
        st.q3 = LinearPercentile.ofSorted(sorted, 0.75);
        double iqr = st.q3 - st.q1;
        double fenceLow = st.q1 - 1.5 * iqr;
        double fenceHigh = st.q3 + 1.5 * iqr;
        st.whiskerLow = st.q1;
        st.whiskerHigh = st.q3;
        boolean lowSet = false;
        boolean highSet = false;
        List<double[]> outliers = new ArrayList<>();
        for (double v : sorted) {
            if (v >= fenceLow && !lowSet) {
                st.whiskerLow = v;
                lowSet = true;
            }
            if (v <= fenceHigh) {
                st.whiskerHigh = v;
                highSet = true;
            }
            if (v < fenceLow) {
                outliers.add(new double[] {v, fenceLow - v});
            } else if (v > fenceHigh) {
                outliers.add(new double[] {v, v - fenceHigh});
            }
        }
        if (!lowSet) {
            st.whiskerLow = st.q1;
        }
        if (!highSet) {
            st.whiskerHigh = st.q3;
        }
        st.outlierCount = outliers.size();
        outliers.sort((a, b) -> {
            int c = Double.compare(b[1], a[1]);
            return c != 0 ? c : Double.compare(a[0], b[0]);
        });
        ArrayNode shown = MAPPER.createArrayNode();
        for (int i = 0; i < Math.min(MAX_SHOWN_OUTLIERS, outliers.size()); i++) {
            shown.add(outliers.get(i)[0]);
        }
        st.shownOutliers = shown;
        return st;
    }

    private static String parseSingleGroupBy(InfoTable src, DataShapeDefinition shape, JsonNode groupBy,
            String valueColumn) throws CachedTabularDecisionToolException {
        if (groupBy == null || groupBy.isNull()) {
            return null;
        }
        String col;
        if (groupBy.isTextual()) {
            col = groupBy.asText().trim();
        } else if (groupBy.isArray()) {
            if (groupBy.size() == 0) {
                return null;
            }
            if (groupBy.size() > 1 || !groupBy.get(0).isTextual()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "box_summary groupBy accepts at most one column name.");
            }
            col = groupBy.get(0).asText().trim();
        } else {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    "box_summary groupBy must be a column name or a one-element array.");
        }
        if (col.isEmpty()) {
            return null;
        }
        BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, col);
        if (bt == null) {
            throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown groupBy column: " + col);
        }
        if (bt == BaseTypes.JSON || bt == BaseTypes.INFOTABLE || bt == BaseTypes.TAGS) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    "groupBy column \"" + col + "\" has an unsupported base type for grouping.");
        }
        if (col.equals(valueColumn)) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    "groupBy column must differ from column.");
        }
        return col;
    }

    private static String groupKeyText(Object v) {
        if (v == null) {
            return "(null)";
        }
        if (v instanceof com.thingworx.types.primitives.IPrimitiveType) {
            Object inner = ((com.thingworx.types.primitives.IPrimitiveType) v).getValue();
            return inner == null ? "(null)" : String.valueOf(inner);
        }
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------ shared

    private static final class Scan {
        final List<Double> values = new ArrayList<>();
        int excludedCount;
    }

    private static Scan scanNumeric(InfoTable src, String column, IFilter filter) {
        Scan scan = new Scan();
        int n = src.getRowCount();
        for (int i = 0; i < n; i++) {
            ValueCollection row = src.getRow(i);
            if (row == null) {
                continue;
            }
            if (filter != null && !CachedTabularDecisionPredicate.evaluateIfilter(filter, row)) {
                continue;
            }
            Double v = numericCell(row.getValue(column));
            if (v == null) {
                scan.excludedCount++;
            } else {
                scan.values.add(v);
            }
        }
        return scan;
    }

    /** Finite numeric value of a cell, else {@code null} (null, text, NaN and infinities are excluded). */
    static Double numericCell(Object v) {
        if (v == null) {
            return null;
        }
        Double d = null;
        if (v instanceof Number) {
            d = ((Number) v).doubleValue();
        } else if (v instanceof com.thingworx.types.primitives.IPrimitiveType) {
            Object inner = ((com.thingworx.types.primitives.IPrimitiveType) v).getValue();
            if (inner instanceof Number) {
                d = ((Number) inner).doubleValue();
            } else if (inner instanceof String) {
                d = parseDoubleOrNull((String) inner);
            }
        } else if (v instanceof String) {
            d = parseDoubleOrNull((String) v);
        }
        return d != null && Double.isFinite(d) ? d : null;
    }

    private static Double parseDoubleOrNull(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String requiredNumericColumn(InfoTable src, DataShapeDefinition shape, JsonNode root,
            String mode) throws CachedTabularDecisionToolException {
        JsonNode c = root.get("column");
        String column = c != null && c.isTextual() ? c.asText().trim() : "";
        if (column.isEmpty()) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", mode + " requires column.");
        }
        BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, column);
        if (bt == null) {
            throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + column);
        }
        if (bt == BaseTypes.NUMBER || bt == BaseTypes.NUMBER || bt == BaseTypes.LONG) {
            return column;
        }
        if ((bt == BaseTypes.STRING || bt == BaseTypes.TEXT)
                && CachedTabularNumericStringCoercion.stringColumnNumericMode(src, column)
                        == CachedTabularNumericStringCoercion.MODE_COERCE) {
            return column;
        }
        throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                mode + ": column \"" + column + "\" must be numeric or uniformly numeric-parseable STRING/TEXT.");
    }

    private static IFilter parseFilters(JsonNode root, DataShapeDefinition shape)
            throws CachedTabularDecisionToolException {
        JsonNode filters = root.get("filters");
        if (filters == null || filters.isNull()) {
            return null;
        }
        IFilter f = ParlerQueryFilterParser.parse(filters, shape);
        f.resolveFields(shape);
        return f;
    }

    private static double finiteOrThrow(JsonNode node, String name) throws CachedTabularDecisionToolException {
        if (!node.isNumber() || !Double.isFinite(node.doubleValue())) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", name + " must be a finite number.");
        }
        return node.doubleValue();
    }

    private static double min(List<Double> values) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static double max(List<Double> values) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static InfoTable newTable(List<String> names, List<BaseTypes> types) {
        DataShapeDefinition ds = new DataShapeDefinition();
        for (int i = 0; i < names.size(); i++) {
            FieldDefinition fd = new FieldDefinition();
            fd.setName(names.get(i));
            fd.setBaseType(types.get(i));
            ds.addFieldDefinition(fd);
        }
        return new InfoTable(ds);
    }

    private static ArrayNode columnMeta(List<String> cols) {
        ArrayNode colMeta = MAPPER.createArrayNode();
        for (String c : cols) {
            ObjectNode cn = MAPPER.createObjectNode();
            cn.put("name", c);
            colMeta.add(cn);
        }
        return colMeta;
    }

    /**
     * INLINE success: the output table is its own derived cache; {@code sourceCacheId} is the input;
     * the answer set is complete by construction (every bin or group is present), while the input's
     * completeness travels through the source descriptor unchanged.
     */
    private static String inlineEnvelope(String readCacheId, InfoTable src, InfoTable out, List<String> cols,
            String resultKind, String routeId, ObjectNode scalars) throws Exception {
        int rc = out.getRowCount();
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "success");
        o.put("sourceCacheId", readCacheId);
        o.put("rowCount", src.getRowCount());
        o.put("resultKind", resultKind);
        com.thingworx.things.agent.source.SourceDescriptor parent =
                InvokeServiceExecutor.lookupSourceDescriptor(readCacheId);
        com.thingworx.things.agent.source.SourceDescriptor derived =
                com.thingworx.things.agent.source.SourceDescriptorSupport.forDerivedStore(parent, readCacheId, out,
                        routeId);
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(out, derived);
        o.put("cacheId", cacheId);
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < rc; i++) {
            rows.add(CachedTabularToolsExecutor.rowToObject(out, i, cols));
        }
        o.set("rows", rows);
        o.set("columns", columnMeta(cols));
        o.put("totalRows", rc);
        o.put("returnedRows", rc);
        o.put("rowsOmitted", false);
        o.put("sampleOnly", false);
        o.put("answerSetComplete", true);
        o.setAll(scalars);
        CachedTabularToolsExecutor.attachInsightEnvelope(o, readCacheId, out);
        CachedTabularToolsExecutor.attachBp6PublicFields(o,
                CachedTabularToolsExecutor.descriptorForScannedSource(readCacheId, src, rc, routeId));
        LOG.info("tabulate_cached_result {} ok sourceCacheId={} rows={}", resultKind, readCacheId, rc);
        return MAPPER.writeValueAsString(o);
    }

    private static String emptyEnvelope(String readCacheId, InfoTable src, List<String> cols, String resultKind,
            ObjectNode scalars) throws Exception {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "success");
        o.put("sourceCacheId", readCacheId);
        o.put("rowCount", src.getRowCount());
        o.put("resultKind", resultKind);
        o.put("totalRows", 0);
        o.putArray("rows");
        o.set("columns", columnMeta(cols));
        o.setAll(scalars);
        CachedTabularToolsExecutor.attachInsightEnvelope(o, readCacheId, newTable(cols, typesFor(cols)));
        CachedTabularToolsExecutor.attachBp6PublicFields(o,
                CachedTabularToolsExecutor.descriptorForScannedSource(readCacheId, src, 0,
                        "tabulate_cached_result." + (RESULT_BIN_EMPTY.equals(resultKind) ? MODE_BIN_NUMERIC
                                : MODE_BOX_SUMMARY)));
        LOG.info("tabulate_cached_result {} sourceCacheId={}", resultKind, readCacheId);
        return MAPPER.writeValueAsString(o);
    }

    private static List<BaseTypes> typesFor(List<String> cols) {
        List<BaseTypes> types = new ArrayList<>();
        for (String c : cols) {
            if ("method".equals(c) || "groupKey".equals(c) || "outliers".equals(c)) {
                types.add(BaseTypes.STRING);
            } else {
                types.add(BaseTypes.NUMBER);
            }
        }
        return types;
    }
}
