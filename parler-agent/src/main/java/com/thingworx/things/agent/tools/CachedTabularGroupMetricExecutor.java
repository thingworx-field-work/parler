package com.thingworx.things.agent.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;

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
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.LongPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.types.data.filters.IFilter;
import com.thingworx.things.agent.tools.predicate.ParlerQueryFilterParser;
import com.thingworx.things.agent.tools.predicate.PredicateErrorMessages;

import org.slf4j.Logger;

/**
 * {@code tabulate_cached_result} {@code group_metric} mode (design {@code docs/agent/cached-table-decision-tools.md} §6.4).
 */
public final class CachedTabularGroupMetricExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(CachedTabularGroupMetricExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * E4: closed derived-op set shared with {@link TabulateCachedResultToolSchema} (schema/executor parity).
     * Order matches the advertised JSON-Schema enum.
     */
    public static final List<String> DERIVED_OPS = List.of(
            "ratio", "ratio_percent", "difference", "sum_values", "multiply", "scale",
            "percent_of_total", "percent_of_group");

    /** {@code docs/agent/query-spec.md} §11 — complete-answer marker eligibility. */
    private static final int ANSWER_SET_COMPLETE_MAX_ROWS = 50;
    private static final int ANSWER_SET_COMPLETE_MAX_COLS = 8;
    private static final int ANSWER_SET_COMPLETE_MAX_ROWS_JSON_UTF8_BYTES = 8192;
    private static final String ANSWER_SET_COMPLETE_ENABLED_PROP = "parler.agent.answerSetComplete.enabled";

    private static final int MAX_MEASURES = 10;
    private static final int MAX_DERIVED = 10;
    private static final int MAX_GROUP_KEYS = 5;
    private static final int MAX_DERIVED_INPUTS = 10;
    private static final int MAX_DERIVED_DEPTH = 3;
    /** Max distinct scalar values per group for {@code count_distinct} (design §6.4). */
    private static final int MAX_COUNT_DISTINCT_VALUES = 10_000;

    private CachedTabularGroupMetricExecutor() {}

    /**
     * Stable grouping identity: list of per-column values (Java {@code null} = SQL null cell), distinct from
     * empty string primitives and from delimiter-based string encoding.
     */
    private static final class GroupKey {
        final List<Object> parts;

        GroupKey(List<Object> parts) {
            this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof GroupKey)) {
                return false;
            }
            return parts.equals(((GroupKey) o).parts);
        }

        @Override
        public int hashCode() {
            return parts.hashCode();
        }
    }

    private static GroupKey buildGroupKey(InfoTable src, int rowIndex, List<String> groupCols, DataShapeDefinition shape)
            throws CachedTabularDecisionToolException {
        if (groupCols.isEmpty()) {
            return new GroupKey(Collections.emptyList());
        }
        ValueCollection row = src.getRow(rowIndex);
        List<Object> part = new ArrayList<>(groupCols.size());
        for (String c : groupCols) {
            part.add(copyGroupKeyCell(row, c, shape, src));
        }
        return new GroupKey(part);
    }

    public static String tabulate(String readCacheId, InfoTable src, DataShapeDefinition shape, JsonNode root)
            throws Exception {
        JsonNode measures = root.get("measures");
        if (measures == null || !measures.isArray() || measures.size() < 1 || measures.size() > MAX_MEASURES) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    "group_metric requires measures as a non-empty array (max " + MAX_MEASURES + ").");
        }
        List<String> groupCols = parseGroupBy(src, root.get("groupBy"), shape);
        JsonNode topFilters = root.get("filters");
        IFilter topRowFilter = null;
        if (topFilters != null && !topFilters.isNull()) {
            topRowFilter = ParlerQueryFilterParser.parse(topFilters, shape);
            topRowFilter.resolveFields(shape);
        }
        Map<GroupKey, List<Integer>> groups = new LinkedHashMap<>();
        int n = src.getRowCount();
        for (int i = 0; i < n; i++) {
            if (topRowFilter != null && !CachedTabularDecisionPredicate.evaluateIfilter(topRowFilter, src.getRow(i))) {
                continue;
            }
            GroupKey gk = buildGroupKey(src, i, groupCols, shape);
            if (!groups.containsKey(gk) && groups.size() >= CachedTabularToolsExecutor.MAX_GROUP_CARDINALITY) {
                throw new CachedTabularDecisionToolException("CARDINALITY_TOO_HIGH",
                        "Distinct group count exceeds " + CachedTabularToolsExecutor.MAX_GROUP_CARDINALITY + ".");
            }
            groups.computeIfAbsent(gk, k -> new ArrayList<>()).add(i);
        }
        if (groupCols.isEmpty() && groups.isEmpty()) {
            groups.put(new GroupKey(Collections.emptyList()), Collections.emptyList());
        }
        int distinctGroups = groups.size();

        List<String> measureNames = new ArrayList<>();
        for (int mi = 0; mi < measures.size(); mi++) {
            JsonNode m = measures.get(mi);
            String nm = text(m, "name");
            if (nm == null || nm.isBlank()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Each measure requires name.");
            }
            nm = nm.trim();
            if (measureNames.contains(nm)) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Duplicate measure name: " + nm);
            }
            measureNames.add(nm);
        }
        JsonNode derivedArr = root.get("derived");
        List<JsonNode> derivedList = new ArrayList<>();
        if (derivedArr != null) {
            if (!derivedArr.isArray() || derivedArr.size() > MAX_DERIVED) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "derived must be an array (max " + MAX_DERIVED + ").");
            }
            for (JsonNode d : derivedArr) {
                derivedList.add(d);
            }
        }
        validateOutputColumnNamespace(groupCols, measureNames, derivedList);
        validateMeasures(shape, src, measures);
        validateDerivedOrder(groupCols, measureNames, derivedList);

        List<JsonNode> localDerived = new ArrayList<>();
        List<JsonNode> percentDerived = new ArrayList<>();
        for (JsonNode d : derivedList) {
            String op = text(d, "op");
            if (op != null) {
                String norm = op.trim().toLowerCase(Locale.ROOT);
                if ("percent_of_total".equals(norm) || "percent_of_group".equals(norm)) {
                    percentDerived.add(d);
                    continue;
                }
            }
            localDerived.add(d);
        }

        List<MetricRow> built = new ArrayList<>();
        for (Map.Entry<GroupKey, List<Integer>> e : groups.entrySet()) {
            List<Integer> ridx = e.getValue();
            Map<String, Double> mv = new LinkedHashMap<>();
            for (int mi = 0; mi < measures.size(); mi++) {
                JsonNode ms = measures.get(mi);
                String nm = measureNames.get(mi);
                mv.put(nm, computeMeasure(src, shape, ridx, ms));
            }
            Map<String, ScaleKind> scaleKind = new HashMap<>();
            LinkedHashMap<String, Double> dv = new LinkedHashMap<>(computeDerived(mv, localDerived, scaleKind));
            List<Object> gPrim;
            if (ridx.isEmpty()) {
                gPrim = new ArrayList<>();
                for (int ig = 0; ig < groupCols.size(); ig++) {
                    gPrim.add(null);
                }
            } else {
                gPrim = groupKeyPrimitives(src.getRow(ridx.get(0)), groupCols, shape, src);
            }
            built.add(new MetricRow(gPrim, mv, dv));
        }
        applyPercentDerivedOps(built, percentDerived, groupCols, measureNames);

        JsonNode having = root.get("having");
        List<MetricRow> afterHaving = built;
        int matchCount = built.size();
        DataShapeDefinition outShape = buildOutputShape(shape, src, groupCols, measureNames, derivedList);
        if (having != null && !having.isNull()) {
            IFilter havingFilter = ParlerQueryFilterParser.parse(having, outShape);
            havingFilter.resolveFields(outShape);
            InfoTable tmp = metricRowsToTable(outShape, groupCols, measureNames, derivedList, built);
            CachedTabularDecisionPredicate.setStringNumericCoercion(Collections.emptyMap());
            try {
                CachedTabularDecisionPredicate.checkAmbiguousPercentScale(tmp, outShape, having, Collections.emptyMap());
            } finally {
                CachedTabularDecisionPredicate.clearStringNumericCoercion();
            }
            List<MetricRow> kept = new ArrayList<>();
            CachedTabularDecisionPredicate.setStringNumericCoercion(Collections.emptyMap());
            try {
                for (int i = 0; i < tmp.getRowCount(); i++) {
                    if (CachedTabularDecisionPredicate.evaluateIfilter(havingFilter, tmp.getRow(i))) {
                        kept.add(built.get(i));
                    }
                }
            } finally {
                CachedTabularDecisionPredicate.clearStringNumericCoercion();
            }
            afterHaving = kept;
            matchCount = kept.size();
        }

        final List<MetricRow> rowsForSort = afterHaving;

        int[] limOff = CachedTabularTabulatePaging.parseMaxItemsAndOffset(root, CachedTabularTabulatePaging.SORT_TOPN_DEFAULT_LIMIT);
        int limit = limOff[0];
        int offset = limOff[1];

        InfoTable sortTable = metricRowsToTable(outShape, groupCols, measureNames, derivedList, rowsForSort);
        List<SortKeySpec> sortKeys = parseOutputSort(root, sortTable);
        Integer[] ord = new Integer[rowsForSort.size()];
        for (int i = 0; i < ord.length; i++) {
            ord[i] = i;
        }
        if (!sortKeys.isEmpty()) {
            Arrays.sort(ord, (ia, ib) -> {
                for (SortKeySpec sk : sortKeys) {
                    int c = compareByColumn(sortTable, sk.column, ia, ib, sk.stringSortCaseSensitive);
                    if (c != 0) {
                        return sk.descending ? -c : c;
                    }
                }
                int g = compareGroupKeys(groupCols, shape, src, rowsForSort.get(ia).groupPrimitives,
                        rowsForSort.get(ib).groupPrimitives);
                if (g != 0) {
                    return g;
                }
                return Integer.compare(ia, ib);
            });
        } else {
            Arrays.sort(ord, (ia, ib) -> {
                int c = compareGroupKeys(groupCols, shape, src, rowsForSort.get(ia).groupPrimitives,
                        rowsForSort.get(ib).groupPrimitives);
                if (c != 0) {
                    return c;
                }
                return Integer.compare(ia, ib);
            });
        }

        List<MetricRow> sliced = new ArrayList<>();
        int from = Math.min(offset, ord.length);
        int to = Math.min(from + limit, ord.length);
        for (int j = from; j < to; j++) {
            sliced.add(rowsForSort.get(ord[j]));
        }

        InfoTable out = metricRowsToTable(outShape, groupCols, measureNames, derivedList, sliced);
        List<String> outColNames = columnNames(out);
        List<String> fieldProj = CachedTabularFieldsProjection.parseFieldsOrNull(root.get("fields"), outColNames);
        if (fieldProj != null) {
            out = CachedTabularFieldsProjection.project(out, fieldProj);
        }
        return formatSuccess(readCacheId, src, out, n, distinctGroups, matchCount, groupCols, measureNames,
                derivedList, having);
    }

    private static final class SortKeySpec {
        final String column;
        final boolean descending;
        final boolean stringSortCaseSensitive;

        SortKeySpec(String column, boolean descending, boolean stringSortCaseSensitive) {
            this.column = column;
            this.descending = descending;
            this.stringSortCaseSensitive = stringSortCaseSensitive;
        }
    }

    private static List<SortKeySpec> parseOutputSort(JsonNode root, InfoTable out) throws CachedTabularDecisionToolException {
        JsonNode sorts = root.get("sorts");
        List<String> cols = columnNames(out);
        List<SortKeySpec> specs = new ArrayList<>();
        if (sorts == null || !sorts.isArray() || sorts.size() == 0) {
            return specs;
        }
        if (sorts.size() > 3) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "sorts accepts at most 3 keys.");
        }
        Set<String> seenSortFields = new HashSet<>();
        for (int i = 0; i < sorts.size(); i++) {
            JsonNode el = sorts.get(i);
            if (el == null || !el.isObject()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "sorts[" + i + "] must be an object.");
            }
            JsonNode fn = el.get("fieldName");
            if (fn == null || !fn.isTextual() || fn.asText().trim().isEmpty()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        PredicateErrorMessages.sortEntryRequiresFieldName(el));
            }
            String col = fn.asText().trim();
            if (!seenSortFields.add(col)) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "sorts contains duplicate fieldName \"" + col + "\".");
            }
            if (!cols.contains(col)) {
                throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + col);
            }
            if (!isSortableOutput(out, col)) {
                throw new CachedTabularDecisionToolException("UNSORTABLE_COLUMN",
                        "Column \"" + col + "\" is not sortable for group_metric.");
            }
            boolean ascending = true;
            if (el.has("isAscending")) {
                JsonNode ascN = el.get("isAscending");
                if (ascN == null || ascN.isNull() || !ascN.isBoolean()) {
                    throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                            "sorts[" + i + "].isAscending must be a boolean when present.");
                }
                ascending = ascN.booleanValue();
            }
            boolean caseSens = el.path("isCaseSensitive").asBoolean(true);
            specs.add(new SortKeySpec(col, !ascending, caseSens));
        }
        return specs;
    }

    private static boolean isSortableOutput(InfoTable out, String col) {
        BaseTypes bt = columnBaseTypeOf(out, col);
        if (bt == null) {
            return false;
        }
        if (bt == BaseTypes.JSON || bt == BaseTypes.TAGS || bt == BaseTypes.INFOTABLE) {
            return false;
        }
        return bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG || bt == BaseTypes.DATETIME
                || bt == BaseTypes.STRING || bt == BaseTypes.TEXT || bt == BaseTypes.GUID || bt == BaseTypes.HTML
                || bt == BaseTypes.XML || bt == BaseTypes.BOOLEAN;
    }

    private static int compareByColumn(InfoTable out, String col, int ia, int ib, boolean stringSortCaseSensitive) {
        ValueCollection ra = out.getRow(ia);
        ValueCollection rb = out.getRow(ib);
        BaseTypes bt = columnBaseTypeOf(out, col);
        if (bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG) {
            Double da = extractDouble(ra, col);
            Double db = extractDouble(rb, col);
            if (da == null && db == null) {
                return 0;
            }
            if (da == null) {
                return 1;
            }
            if (db == null) {
                return -1;
            }
            return Double.compare(da, db);
        }
        if (bt == BaseTypes.DATETIME) {
            DateTime ta = extractDateTime(ra, col);
            DateTime tb = extractDateTime(rb, col);
            if (ta == null && tb == null) {
                return 0;
            }
            if (ta == null) {
                return 1;
            }
            if (tb == null) {
                return -1;
            }
            return Long.compare(ta.getMillis(), tb.getMillis());
        }
        String sa = ra != null ? cellToSortKey(ra, col) : "";
        String sb = rb != null ? cellToSortKey(rb, col) : "";
        if (!stringSortCaseSensitive && isStringLikeBaseTypeForSort(bt)) {
            return sa.compareToIgnoreCase(sb);
        }
        return sa.compareTo(sb);
    }

    private static boolean isStringLikeBaseTypeForSort(BaseTypes bt) {
        if (bt == null) {
            return false;
        }
        return bt == BaseTypes.STRING || bt == BaseTypes.TEXT || bt == BaseTypes.GUID || bt == BaseTypes.HTML
                || bt == BaseTypes.XML || bt.name().endsWith("NAME");
    }

    private static final class MetricRow {
        final List<Object> groupPrimitives;
        final Map<String, Double> measures;
        final LinkedHashMap<String, Double> derived;

        MetricRow(List<Object> groupPrimitives, Map<String, Double> measures, LinkedHashMap<String, Double> derived) {
            this.groupPrimitives = groupPrimitives;
            this.measures = measures;
            this.derived = derived;
        }
    }

    private static List<String> parseGroupBy(InfoTable src, JsonNode groupBy, DataShapeDefinition shape)
            throws CachedTabularDecisionToolException {
        List<String> out = new ArrayList<>();
        if (groupBy == null || groupBy.isNull()) {
            return out;
        }
        if (!groupBy.isArray()) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "groupBy must be a JSON array of column names.");
        }
        if (groupBy.size() > MAX_GROUP_KEYS) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    "groupBy accepts at most " + MAX_GROUP_KEYS + " keys.");
        }
        List<String> known = columnNames(src);
        for (JsonNode el : groupBy) {
            if (el == null || !el.isTextual()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "groupBy entries must be strings.");
            }
            String c = el.asText().trim();
            if (c.isEmpty()) {
                continue;
            }
            if (!known.contains(c)) {
                throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + c);
            }
            BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, c);
            if (CachedTabularDecisionPredicate.isUnsupportedComplexBaseType(bt)) {
                throw new CachedTabularDecisionToolException("UNSUPPORTED_COLUMN_TYPE",
                        "groupBy column \"" + c + "\" has an unsupported base type for grouping.");
            }
            out.add(c);
        }
        return out;
    }

    private static void validateOutputColumnNamespace(List<String> groupCols, List<String> measureNames,
            List<JsonNode> derivedList) throws CachedTabularDecisionToolException {
        Set<String> occupied = new HashSet<>();
        for (String g : groupCols) {
            if (!occupied.add(g)) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Duplicate groupBy column: " + g);
            }
        }
        for (String mn : measureNames) {
            if (!occupied.add(mn)) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "Measure name \"" + mn + "\" collides with a groupBy column or another output name.");
            }
        }
        for (JsonNode d : derivedList) {
            String dn = text(d, "name");
            if (dn == null || dn.isBlank()) {
                continue;
            }
            dn = dn.trim();
            if (!occupied.add(dn)) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "Derived name \"" + dn + "\" collides with a groupBy column, measure name, or another derived name.");
            }
        }
    }

    private static List<Object> groupKeyPrimitives(ValueCollection row, List<String> groupCols,
            DataShapeDefinition shape, InfoTable src) throws CachedTabularDecisionToolException {
        List<Object> v = new ArrayList<>();
        for (String c : groupCols) {
            v.add(copyGroupKeyCell(row, c, shape, src));
        }
        return v;
    }

    private static int compareGroupKeys(List<String> groupCols, DataShapeDefinition shape, InfoTable src, List<Object> a,
            List<Object> b) {
        int n = Math.min(a.size(), b.size());
        int m = Math.min(n, groupCols.size());
        for (int i = 0; i < m; i++) {
            BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, groupCols.get(i));
            int c = compareGroupKeyValues(bt, a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int compareGroupKeyValues(BaseTypes bt, Object va, Object vb) {
        if (bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG) {
            Double da = primitiveToDouble(va);
            Double db = primitiveToDouble(vb);
            if (da == null && db == null) {
                return 0;
            }
            if (da == null) {
                return 1;
            }
            if (db == null) {
                return -1;
            }
            return Double.compare(da, db);
        }
        if (bt == BaseTypes.DATETIME) {
            DateTime ta = primitiveToDateTime(va);
            DateTime tb = primitiveToDateTime(vb);
            if (ta == null && tb == null) {
                return 0;
            }
            if (ta == null) {
                return 1;
            }
            if (tb == null) {
                return -1;
            }
            return Long.compare(ta.getMillis(), tb.getMillis());
        }
        if (bt == BaseTypes.BOOLEAN) {
            Boolean ba = primitiveToBoolean(va);
            Boolean bb = primitiveToBoolean(vb);
            if (ba == null && bb == null) {
                return 0;
            }
            if (ba == null) {
                return 1;
            }
            if (bb == null) {
                return -1;
            }
            return Boolean.compare(ba, bb);
        }
        String sa = primitiveToSortKey(va);
        String sb = primitiveToSortKey(vb);
        return sa.compareTo(sb);
    }

    private static Double primitiveToDouble(Object p) {
        if (p == null) {
            return null;
        }
        if (p instanceof NumberPrimitive) {
            try {
                return ((NumberPrimitive) p).getValue();
            } catch (Exception e) {
                return null;
            }
        }
        if (p instanceof IntegerPrimitive) {
            try {
                return (double) ((IntegerPrimitive) p).getValue();
            } catch (Exception e) {
                return null;
            }
        }
        if (p instanceof LongPrimitive) {
            try {
                return (double) ((LongPrimitive) p).getValue();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static DateTime primitiveToDateTime(Object p) {
        if (p instanceof DatetimePrimitive) {
            try {
                return ((DatetimePrimitive) p).getValue();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Boolean primitiveToBoolean(Object p) {
        if (p instanceof BooleanPrimitive) {
            try {
                return ((BooleanPrimitive) p).getValue();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String primitiveToSortKey(Object p) {
        if (p == null) {
            return "";
        }
        if (p instanceof IPrimitiveType) {
            try {
                Object inner = ((IPrimitiveType) p).getValue();
                return inner == null ? "" : String.valueOf(inner);
            } catch (Exception e) {
                return p.toString();
            }
        }
        return String.valueOf(p);
    }

    private enum ScaleKind {
        NONE,
        FRACTION,
        PERCENT
    }

    private static boolean isNumericAggregateBaseType(BaseTypes bt) {
        return bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG;
    }

    private static void validateMeasures(DataShapeDefinition shape, InfoTable src, JsonNode measures)
            throws CachedTabularDecisionToolException {
        List<String> known = columnNames(src);
        for (int mi = 0; mi < measures.size(); mi++) {
            JsonNode m = measures.get(mi);
            String op = text(m, "op");
            if (op == null || op.isBlank()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Measure requires op.");
            }
            op = op.trim().toLowerCase(Locale.ROOT);
            String mcol = text(m, "column");
            switch (op) {
                case "count":
                    if (mcol != null && !mcol.isEmpty()) {
                        requireKnownColumn(known, mcol);
                        assertScalarMeasureColumn(shape, src, mcol, "count");
                    }
                    break;
                case "count_non_null":
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "count_non_null requires column.");
                    }
                    requireKnownColumn(known, mcol);
                    assertScalarMeasureColumn(shape, src, mcol, "count_non_null");
                    break;
                case "sum":
                case "avg":
                case "min":
                case "max":
                case "median":
                case "variance":
                case "stddev":
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", op + " requires column.");
                    }
                    requireKnownColumn(known, mcol);
                    assertNumericOrCoercibleStringMeasureColumn(shape, src, mcol, op);
                    break;
                case "percentile":
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "percentile requires column.");
                    }
                    requireKnownColumn(known, mcol);
                    assertNumericOrCoercibleStringMeasureColumn(shape, src, mcol, "percentile");
                    assertPercentileParameter(m);
                    break;
                case "count_distinct":
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "count_distinct requires column.");
                    }
                    requireKnownColumn(known, mcol);
                    assertScalarMeasureColumn(shape, src, mcol, "count_distinct");
                    break;
                case "weighted_avg": {
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "weighted_avg requires column.");
                    }
                    requireKnownColumn(known, mcol);
                    assertNumericOrCoercibleStringMeasureColumn(shape, src, mcol, "weighted_avg");
                    String wcol = text(m, "weightColumn");
                    if (wcol == null || wcol.isBlank()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "weighted_avg requires weightColumn.");
                    }
                    wcol = wcol.trim();
                    requireKnownColumn(known, wcol);
                    assertNumericOrCoercibleStringMeasureColumn(shape, src, wcol, "weighted_avg weightColumn");
                    break;
                }
                case "first":
                case "last": {
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", op + " requires column.");
                    }
                    requireKnownColumn(known, mcol);
                    assertFirstLastValueColumn(shape, src, mcol, op);
                    String orderBy = text(m, "orderBy");
                    if (orderBy == null || orderBy.isBlank()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", op + " requires orderBy.");
                    }
                    orderBy = orderBy.trim();
                    requireKnownColumn(known, orderBy);
                    assertOrderByColumn(shape, src, orderBy);
                    JsonNode dirN = m.get("direction");
                    if (dirN != null && !dirN.isNull() && dirN.isTextual()) {
                        String d = dirN.asText().trim();
                        if (!d.isEmpty()) {
                            String t = d.toLowerCase(Locale.ROOT);
                            if (!"asc".equals(t) && !"ascending".equals(t) && !"desc".equals(t) && !"descending".equals(t)) {
                                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                        op + " direction must be asc or desc when present.");
                            }
                        }
                    }
                    break;
                }
                case "mode":
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "mode requires column.");
                    }
                    requireKnownColumn(known, mcol);
                    assertModeMeasureColumn(shape, src, mcol);
                    break;
                default:
                    throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Unsupported measure op: " + op);
            }
        }
    }

    private static void requireKnownColumn(List<String> known, String col) throws CachedTabularDecisionToolException {
        if (!known.contains(col)) {
            throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + col);
        }
    }

    private static void assertScalarMeasureColumn(DataShapeDefinition shape, InfoTable src, String col, String ctx)
            throws CachedTabularDecisionToolException {
        BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, col);
        if (CachedTabularDecisionPredicate.isUnsupportedComplexBaseType(bt)) {
            throw new CachedTabularDecisionToolException("UNSUPPORTED_COLUMN_TYPE",
                    ctx + ": column \"" + col + "\" has an unsupported base type.");
        }
    }

    private static void assertNumericOrCoercibleStringMeasureColumn(DataShapeDefinition shape, InfoTable src, String col,
            String ctx) throws CachedTabularDecisionToolException {
        BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, col);
        if (bt == null) {
            throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + col);
        }
        if (isNumericAggregateBaseType(bt)) {
            return;
        }
        if (bt == BaseTypes.STRING || bt == BaseTypes.TEXT) {
            if (CachedTabularNumericStringCoercion.stringColumnNumericMode(src, col) != CachedTabularNumericStringCoercion.MODE_COERCE) {
                throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                        ctx + ": column \"" + col + "\" is not uniformly numeric-parseable as required for aggregates.");
            }
            return;
        }
        throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                ctx + ": column \"" + col + "\" must be numeric or uniformly numeric-parseable STRING/TEXT.");
    }

    private static void assertPercentileParameter(JsonNode m) throws CachedTabularDecisionToolException {
        JsonNode p = m.get("p");
        if (p == null || p.isNull() || !p.isNumber()) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "percentile requires numeric p in [0,100].");
        }
        double pv = p.doubleValue();
        if (Double.isNaN(pv) || pv < 0.0 || pv > 100.0) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "percentile p must be in [0,100].");
        }
    }

    private static void assertOrderByColumn(DataShapeDefinition shape, InfoTable src, String col)
            throws CachedTabularDecisionToolException {
        BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, col);
        if (bt == null) {
            throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + col);
        }
        if (bt == BaseTypes.JSON || bt == BaseTypes.TAGS || bt == BaseTypes.INFOTABLE) {
            throw new CachedTabularDecisionToolException("UNSORTABLE_COLUMN",
                    "orderBy column \"" + col + "\" is not sortable.");
        }
        if (CachedTabularDecisionPredicate.isUnsupportedComplexBaseType(bt)) {
            throw new CachedTabularDecisionToolException("UNSUPPORTED_COLUMN_TYPE",
                    "orderBy column \"" + col + "\" has an unsupported base type.");
        }
        if (!(bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG || bt == BaseTypes.DATETIME
                || bt == BaseTypes.STRING || bt == BaseTypes.TEXT || bt == BaseTypes.GUID || bt == BaseTypes.HTML
                || bt == BaseTypes.XML || bt == BaseTypes.BOOLEAN)) {
            throw new CachedTabularDecisionToolException("UNSORTABLE_COLUMN",
                    "orderBy column \"" + col + "\" is not sortable.");
        }
    }

    private static void assertFirstLastValueColumn(DataShapeDefinition shape, InfoTable src, String col, String ctx)
            throws CachedTabularDecisionToolException {
        BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, col);
        if (bt == null) {
            throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + col);
        }
        if (bt == BaseTypes.DATETIME || bt == BaseTypes.BOOLEAN) {
            return;
        }
        assertNumericOrCoercibleStringMeasureColumn(shape, src, col, ctx);
    }

    private static void assertModeMeasureColumn(DataShapeDefinition shape, InfoTable src, String col)
            throws CachedTabularDecisionToolException {
        BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, col);
        if (bt == null) {
            throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + col);
        }
        if (bt == BaseTypes.STRING || bt == BaseTypes.TEXT) {
            throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                    "mode requires numeric, boolean, or datetime column; \"" + col + "\" is STRING/TEXT.");
        }
        if (bt == BaseTypes.DATETIME || bt == BaseTypes.BOOLEAN) {
            return;
        }
        if (isNumericAggregateBaseType(bt)) {
            return;
        }
        throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                "mode requires numeric, boolean, or datetime column \"" + col + "\".");
    }

    private static Object copyGroupKeyCell(ValueCollection row, String col, DataShapeDefinition shape, InfoTable src)
            throws CachedTabularDecisionToolException {
        if (row == null) {
            return null;
        }
        BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, col);
        Object v = row.getValue(col);
        if (v == null) {
            return null;
        }
        if (bt == BaseTypes.BOOLEAN) {
            if (v instanceof BooleanPrimitive) {
                return v;
            }
            if (v instanceof IPrimitiveType) {
                try {
                    Object inner = ((IPrimitiveType) v).getValue();
                    if (inner instanceof Boolean) {
                        return new BooleanPrimitive((Boolean) inner);
                    }
                } catch (Exception ignored) {
                    // fall through
                }
            }
            return new BooleanPrimitive(Boolean.parseBoolean(String.valueOf(v)));
        }
        if (bt == BaseTypes.DATETIME) {
            DateTime dt = extractDateTime(row, col);
            return dt == null ? null : new DatetimePrimitive(dt);
        }
        if (bt == BaseTypes.INTEGER) {
            Double d = extractDouble(row, col);
            return d == null ? null : new IntegerPrimitive(d.intValue());
        }
        if (bt == BaseTypes.LONG) {
            Double d = extractDouble(row, col);
            return d == null ? null : new LongPrimitive(d.longValue());
        }
        if (bt == BaseTypes.NUMBER) {
            Double d = extractDouble(row, col);
            return d == null ? null : new NumberPrimitive(d);
        }
        if (v instanceof StringPrimitive) {
            return v;
        }
        if (v instanceof IPrimitiveType) {
            try {
                Object inner = ((IPrimitiveType) v).getValue();
                return new StringPrimitive(inner == null ? "" : String.valueOf(inner));
            } catch (Exception e) {
                return new StringPrimitive(v.toString());
            }
        }
        return new StringPrimitive(String.valueOf(v));
    }

    private static void validateDerivedOrder(List<String> groupCols, List<String> measureNames,
            List<JsonNode> derivedList) throws CachedTabularDecisionToolException {
        List<String> known = new ArrayList<>(measureNames);
        Map<String, Integer> depth = new HashMap<>();
        for (String m : measureNames) {
            depth.put(m, 0);
        }
        for (JsonNode d : derivedList) {
            String name = text(d, "name");
            if (name == null || name.isBlank()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Each derived requires name.");
            }
            name = name.trim();
            String op = text(d, "op");
            if (op == null || op.isBlank()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Each derived requires op.");
            }
            op = op.trim().toLowerCase(Locale.ROOT);
            int depBase = 0;
            switch (op) {
                case "ratio":
                case "ratio_percent": {
                    String num = text(d, "numerator");
                    String den = text(d, "denominator");
                    requireKnown(known, num);
                    requireKnown(known, den);
                    depBase = Math.max(depthOf(depth, num), depthOf(depth, den));
                    break;
                }
                case "difference": {
                    String left = text(d, "left");
                    String right = text(d, "right");
                    requireKnown(known, left);
                    requireKnown(known, right);
                    depBase = Math.max(depthOf(depth, left), depthOf(depth, right));
                    break;
                }
                case "sum_values":
                case "multiply": {
                    JsonNode ins = d.get("inputs");
                    if (ins == null || !ins.isArray()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", op + " requires inputs array.");
                    }
                    if (ins.size() > MAX_DERIVED_INPUTS) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                op + " accepts at most " + MAX_DERIVED_INPUTS + " inputs.");
                    }
                    if (ins.size() < 1) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", op + " requires non-empty inputs.");
                    }
                    int mx = -1;
                    for (int ii = 0; ii < ins.size(); ii++) {
                        JsonNode in = ins.get(ii);
                        if (in == null || !in.isTextual()) {
                            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                    op + " inputs[" + ii + "] must be a non-blank string naming a measure or earlier derived value.");
                        }
                        String r = in.asText().trim();
                        if (r.isEmpty()) {
                            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                    op + " inputs[" + ii + "] must be a non-blank string naming a measure or earlier derived value.");
                        }
                        requireKnown(known, r);
                        mx = Math.max(mx, depthOf(depth, r));
                    }
                    depBase = mx;
                    break;
                }
                case "scale": {
                    String inp = text(d, "input");
                    requireKnown(known, inp);
                    depBase = depthOf(depth, inp);
                    break;
                }
                case "percent_of_total": {
                    String inp = text(d, "input");
                    if (inp == null || inp.isBlank()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                "percent_of_total requires input naming a measure.");
                    }
                    inp = inp.trim();
                    if (!measureNames.contains(inp)) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                "percent_of_total input must name a measure column.");
                    }
                    depBase = depthOf(depth, inp);
                    break;
                }
                case "percent_of_group": {
                    String inp = text(d, "input");
                    if (inp == null || inp.isBlank()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                "percent_of_group requires input naming a measure.");
                    }
                    inp = inp.trim();
                    if (!measureNames.contains(inp)) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                "percent_of_group input must name a measure column.");
                    }
                    JsonNode gb = d.get("groupBy");
                    if (gb == null || !gb.isArray() || gb.size() == 0) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                "percent_of_group requires non-empty groupBy array.");
                    }
                    for (int gi = 0; gi < gb.size(); gi++) {
                        JsonNode el = gb.get(gi);
                        if (el == null || !el.isTextual()) {
                            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                    "percent_of_group.groupBy[" + gi + "] must be a string.");
                        }
                        String c = el.asText().trim();
                        if (!groupCols.contains(c)) {
                            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                    "percent_of_group.groupBy column \"" + c + "\" must be a subset of groupBy output columns.");
                        }
                    }
                    depBase = depthOf(depth, inp);
                    break;
                }
                default:
                    throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Unsupported derived op: " + op);
            }
            int myDepth = 1 + depBase;
            if (myDepth > MAX_DERIVED_DEPTH) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "derived chain depth exceeds " + MAX_DERIVED_DEPTH + " for \"" + name + "\".");
            }
            known.add(name);
            depth.put(name, myDepth);
        }
    }

    private static int depthOf(Map<String, Integer> depth, String key) throws CachedTabularDecisionToolException {
        if (key == null || key.isBlank()) {
            return 0;
        }
        String k = key.trim();
        Integer d = depth.get(k);
        if (d == null) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "derived references unknown name \"" + k + "\".");
        }
        return d;
    }

    private static void requireKnown(List<String> known, String ref) throws CachedTabularDecisionToolException {
        if (ref == null || ref.isBlank()) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "derived field references a blank name.");
        }
        ref = ref.trim();
        if (!known.contains(ref)) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    "derived references unknown name \"" + ref + "\" (must be a measure or an earlier derived).");
        }
    }

    private static void applyPercentDerivedOps(List<MetricRow> rows, List<JsonNode> percentDerived,
            List<String> groupCols, List<String> measureNames) throws CachedTabularDecisionToolException {
        for (JsonNode d : percentDerived) {
            String name = text(d, "name");
            if (name == null || name.isBlank()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Each derived requires name.");
            }
            name = name.trim();
            String op = text(d, "op");
            if (op == null || op.isBlank()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Each derived requires op.");
            }
            op = op.trim().toLowerCase(Locale.ROOT);
            String input = text(d, "input");
            if (input == null || input.isBlank()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "percent derived ops require input naming a measure.");
            }
            input = input.trim();
            if (!measureNames.contains(input)) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "percent derived input must name a measure column.");
            }
            if ("percent_of_total".equals(op)) {
                double sum = 0.0d;
                for (MetricRow mr : rows) {
                    Double v = mr.measures.get(input);
                    if (v != null) {
                        sum += v;
                    }
                }
                if (sum == 0.0d) {
                    throw new CachedTabularDecisionToolException("ZERO_DENOMINATOR",
                            "percent_of_total: sum of input measure is zero.");
                }
                for (MetricRow mr : rows) {
                    Double v = mr.measures.get(input);
                    mr.derived.put(name, v == null ? null : (100.0d * v / sum));
                }
            } else if ("percent_of_group".equals(op)) {
                JsonNode gb = d.get("groupBy");
                if (gb == null || !gb.isArray() || gb.size() == 0) {
                    throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                            "percent_of_group requires non-empty groupBy array.");
                }
                List<String> gbCols = new ArrayList<>();
                for (int i = 0; i < gb.size(); i++) {
                    JsonNode el = gb.get(i);
                    if (el == null || !el.isTextual()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                "percent_of_group.groupBy[" + i + "] must be a string.");
                    }
                    String c = el.asText().trim();
                    if (!groupCols.contains(c)) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                "percent_of_group.groupBy column \"" + c + "\" must be a subset of groupBy output columns.");
                    }
                    gbCols.add(c);
                }
                Map<String, Double> partSum = new LinkedHashMap<>();
                LinkedHashSet<String> partitionKeys = new LinkedHashSet<>();
                for (MetricRow mr : rows) {
                    String pk = partitionKeyForPercentGroup(mr, groupCols, gbCols);
                    partitionKeys.add(pk);
                    Double v = mr.measures.get(input);
                    if (v != null) {
                        partSum.merge(pk, v, Double::sum);
                    }
                }
                for (String pk : partitionKeys) {
                    double den = partSum.getOrDefault(pk, 0.0d);
                    if (den == 0.0d) {
                        throw new CachedTabularDecisionToolException("ZERO_DENOMINATOR",
                                "percent_of_group: partition has no non-null input values or sum is zero.");
                    }
                }
                for (MetricRow mr : rows) {
                    String pk = partitionKeyForPercentGroup(mr, groupCols, gbCols);
                    double den = partSum.getOrDefault(pk, 0.0d);
                    Double v = mr.measures.get(input);
                    if (v != null) {
                        mr.derived.put(name, 100.0d * v / den);
                    } else {
                        mr.derived.put(name, null);
                    }
                }
            } else {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Unsupported percent derived op: " + op);
            }
        }
    }

    private static String partitionKeyForPercentGroup(MetricRow mr, List<String> groupCols, List<String> gbCols)
            throws CachedTabularDecisionToolException {
        StringBuilder sb = new StringBuilder();
        for (String col : gbCols) {
            int idx = groupCols.indexOf(col);
            if (idx < 0) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Unknown group column " + col);
            }
            Object o = idx < mr.groupPrimitives.size() ? mr.groupPrimitives.get(idx) : null;
            sb.append('\u0001');
            sb.append(col);
            sb.append('\u0002');
            sb.append(String.valueOf(o));
        }
        return sb.toString();
    }

    private static Double computeMeasure(InfoTable src, DataShapeDefinition shape, List<Integer> rows, JsonNode m)
            throws CachedTabularDecisionToolException {
        String op = text(m, "op");
        if (op == null || op.isBlank()) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Measure requires op.");
        }
        op = op.trim().toLowerCase(Locale.ROOT);
        JsonNode mfNode = m.get("filters");
        IFilter mf = null;
        if (mfNode != null && !mfNode.isNull()) {
            mf = ParlerQueryFilterParser.parse(mfNode, shape);
            mf.resolveFields(shape);
        }
        java.util.Map<String, Byte> co = (mfNode == null || mfNode.isNull())
                ? Collections.emptyMap()
                : CachedTabularNumericStringCoercion.policiesForPredicate(src, shape, mfNode);
        CachedTabularDecisionPredicate.setStringNumericCoercion(co);
        try {
            if (mfNode != null && !mfNode.isNull()) {
                CachedTabularDecisionPredicate.checkAmbiguousPercentScale(src, shape, mfNode, co);
            }
            String mcol = text(m, "column");
            switch (op) {
                case "count": {
                    int c = 0;
                    for (int ri : rows) {
                        if (mf != null && !CachedTabularDecisionPredicate.evaluateIfilter(mf, src.getRow(ri))) {
                            continue;
                        }
                        if (mcol != null && !mcol.isEmpty()) {
                            if (src.getRow(ri).getValue(mcol) == null) {
                                continue;
                            }
                        }
                        c++;
                    }
                    return (double) c;
                }
                case "count_non_null": {
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "count_non_null requires column.");
                    }
                    int c = 0;
                    for (int ri : rows) {
                        if (mf != null && !CachedTabularDecisionPredicate.evaluateIfilter(mf, src.getRow(ri))) {
                            continue;
                        }
                        if (src.getRow(ri).getValue(mcol) != null) {
                            c++;
                        }
                    }
                    return (double) c;
                }
                case "sum":
                case "avg":
                case "min":
                case "max": {
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", op + " requires column.");
                    }
                    List<Double> vals = new ArrayList<>();
                    for (int ri : rows) {
                        if (mf != null && !CachedTabularDecisionPredicate.evaluateIfilter(mf, src.getRow(ri))) {
                            continue;
                        }
                        Double d = extractDouble(src.getRow(ri), mcol);
                        if (d != null && !d.isNaN()) {
                            vals.add(d);
                        }
                    }
                    if (vals.isEmpty()) {
                        // Option G (Bug 005): conditional sum with measure-level filters and zero matching rows
                        // in a non-empty group is 0, not null. Plain sum (no filters) over all-null numerics stays null.
                        if ("sum".equals(op) && mf != null && !rows.isEmpty()) {
                            boolean anyPassesMeasureFilter = false;
                            for (int ri : rows) {
                                if (CachedTabularDecisionPredicate.evaluateIfilter(mf, src.getRow(ri))) {
                                    anyPassesMeasureFilter = true;
                                    break;
                                }
                            }
                            if (!anyPassesMeasureFilter) {
                                return 0.0;
                            }
                        }
                        return null;
                    }
                    if ("sum".equals(op)) {
                        double s = 0;
                        for (double v : vals) {
                            s += v;
                        }
                        return s;
                    }
                    if ("avg".equals(op)) {
                        double s = 0;
                        for (double v : vals) {
                            s += v;
                        }
                        return s / vals.size();
                    }
                    if ("min".equals(op)) {
                        return Collections.min(vals);
                    }
                    return Collections.max(vals);
                }
                case "median": {
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "median requires column.");
                    }
                    double[] arr = collectNumericMeasureValues(src, shape, rows, mcol, mf);
                    if (arr.length == 0) {
                        return null;
                    }
                    Arrays.sort(arr);
                    return percentileLinearSorted(arr, 0.50);
                }
                case "percentile": {
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "percentile requires column.");
                    }
                    double p = m.get("p").doubleValue() / 100.0;
                    double[] arr = collectNumericMeasureValues(src, shape, rows, mcol, mf);
                    if (arr.length == 0) {
                        return null;
                    }
                    Arrays.sort(arr);
                    return percentileLinearSorted(arr, p);
                }
                case "variance":
                case "stddev": {
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", op + " requires column.");
                    }
                    double[] arr = collectNumericMeasureValues(src, shape, rows, mcol, mf);
                    if (arr.length == 0) {
                        return null;
                    }
                    double mean = 0;
                    for (double v : arr) {
                        mean += v;
                    }
                    mean /= arr.length;
                    double var = 0;
                    for (double v : arr) {
                        double d = v - mean;
                        var += d * d;
                    }
                    var /= arr.length;
                    if ("variance".equals(op)) {
                        return var;
                    }
                    return Math.sqrt(var);
                }
                case "count_distinct": {
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "count_distinct requires column.");
                    }
                    Set<String> seen = new HashSet<>();
                    for (int ri : rows) {
                        if (mf != null && !CachedTabularDecisionPredicate.evaluateIfilter(mf, src.getRow(ri))) {
                            continue;
                        }
                        ValueCollection row = src.getRow(ri);
                        if (row == null || row.getValue(mcol) == null) {
                            continue;
                        }
                        String k = cellToSortKey(row, mcol);
                        if (!seen.add(k)) {
                            continue;
                        }
                        if (seen.size() > MAX_COUNT_DISTINCT_VALUES) {
                            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                    "count_distinct: more than " + MAX_COUNT_DISTINCT_VALUES + " distinct values in group.");
                        }
                    }
                    return (double) seen.size();
                }
                case "weighted_avg": {
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "weighted_avg requires column.");
                    }
                    String wcol = text(m, "weightColumn");
                    if (wcol == null || wcol.isBlank()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "weighted_avg requires weightColumn.");
                    }
                    wcol = wcol.trim();
                    double sumWv = 0;
                    double sumW = 0;
                    for (int ri : rows) {
                        if (mf != null && !CachedTabularDecisionPredicate.evaluateIfilter(mf, src.getRow(ri))) {
                            continue;
                        }
                        ValueCollection row = src.getRow(ri);
                        Double w = extractDouble(row, wcol);
                        if (w == null || w.isNaN()) {
                            continue;
                        }
                        if (w < 0.0) {
                            throw new CachedTabularDecisionToolException("INVALID_WEIGHT", "weighted_avg: negative weight.");
                        }
                        Double v = extractDouble(row, mcol);
                        if (v == null || v.isNaN()) {
                            continue;
                        }
                        sumWv += v * w;
                        sumW += w;
                    }
                    if (sumW == 0.0) {
                        return null;
                    }
                    return sumWv / sumW;
                }
                case "first":
                case "last": {
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", op + " requires column.");
                    }
                    String orderBy = text(m, "orderBy").trim();
                    String dirRaw = text(m, "direction");
                    final boolean desc;
                    if (dirRaw != null && !dirRaw.isBlank()) {
                        String t = dirRaw.trim().toLowerCase(Locale.ROOT);
                        desc = "desc".equals(t) || "descending".equals(t);
                    } else {
                        desc = false;
                    }
                    List<Integer> eligible = new ArrayList<>();
                    for (int ri : rows) {
                        if (mf != null && !CachedTabularDecisionPredicate.evaluateIfilter(mf, src.getRow(ri))) {
                            continue;
                        }
                        eligible.add(ri);
                    }
                    if (eligible.isEmpty()) {
                        return null;
                    }
                    BaseTypes obt = CachedTabularDecisionPredicate.columnBaseType(shape, src, orderBy);
                    eligible.sort((ia, ib) -> {
                        ValueCollection ra = src.getRow(ia);
                        ValueCollection rb = src.getRow(ib);
                        boolean na = ra == null || ra.getValue(orderBy) == null;
                        boolean nb = rb == null || rb.getValue(orderBy) == null;
                        if (na && nb) {
                            return Integer.compare(ia, ib);
                        }
                        if (na) {
                            return 1;
                        }
                        if (nb) {
                            return -1;
                        }
                        int c = compareRowsForOrderBy(src, shape, orderBy, obt, ia, ib);
                        if (c != 0) {
                            return desc ? -c : c;
                        }
                        return Integer.compare(ia, ib);
                    });
                    int pick = "first".equals(op) ? eligible.get(0) : eligible.get(eligible.size() - 1);
                    return extractMeasureValueAsDouble(src, shape, mcol, pick);
                }
                case "mode": {
                    if (mcol == null || mcol.isEmpty()) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "mode requires column.");
                    }
                    BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, mcol);
                    Map<Double, Integer> freq = new HashMap<>();
                    for (int ri : rows) {
                        if (mf != null && !CachedTabularDecisionPredicate.evaluateIfilter(mf, src.getRow(ri))) {
                            continue;
                        }
                        ValueCollection row = src.getRow(ri);
                        if (row == null || row.getValue(mcol) == null) {
                            continue;
                        }
                        Double key;
                        if (bt == BaseTypes.DATETIME) {
                            DateTime dt = extractDateTime(row, mcol);
                            if (dt == null) {
                                continue;
                            }
                            key = (double) dt.getMillis();
                        } else if (bt == BaseTypes.BOOLEAN) {
                            Boolean b = extractBooleanCell(row, mcol);
                            if (b == null) {
                                continue;
                            }
                            key = b ? 1.0 : 0.0;
                        } else {
                            Double d = extractDouble(row, mcol);
                            if (d == null || d.isNaN()) {
                                continue;
                            }
                            key = d;
                        }
                        freq.merge(key, 1, Integer::sum);
                    }
                    if (freq.isEmpty()) {
                        return null;
                    }
                    int bestC = -1;
                    double bestV = 0;
                    for (Map.Entry<Double, Integer> e : freq.entrySet()) {
                        int c = e.getValue();
                        double v = e.getKey();
                        if (c > bestC || (c == bestC && Double.compare(v, bestV) < 0)) {
                            bestC = c;
                            bestV = v;
                        }
                    }
                    return bestV;
                }
                default:
                    throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Unsupported measure op: " + op);
            }
        } finally {
            CachedTabularDecisionPredicate.clearStringNumericCoercion();
        }
    }

    private static Map<String, Double> computeDerived(Map<String, Double> measures, List<JsonNode> derivedList,
            Map<String, ScaleKind> scaleKind) throws CachedTabularDecisionToolException {
        Map<String, Double> combined = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : measures.entrySet()) {
            combined.put(e.getKey(), e.getValue());
            scaleKind.put(e.getKey(), ScaleKind.NONE);
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (JsonNode d : derivedList) {
            String name = text(d, "name").trim();
            String op = text(d, "op").trim().toLowerCase(Locale.ROOT);
            Double val;
            switch (op) {
                case "ratio": {
                    Double n = pickNullable(combined, text(d, "numerator"));
                    Double den = pickNullable(combined, text(d, "denominator"));
                    if (n == null || den == null || den == 0.0) {
                        val = null;
                    } else {
                        val = n / den;
                    }
                    scaleKind.put(name, ScaleKind.FRACTION);
                    break;
                }
                case "ratio_percent": {
                    Double n = pickNullable(combined, text(d, "numerator"));
                    Double den = pickNullable(combined, text(d, "denominator"));
                    if (n == null || den == null || den == 0.0) {
                        val = null;
                    } else {
                        val = 100.0 * n / den;
                    }
                    scaleKind.put(name, ScaleKind.PERCENT);
                    break;
                }
                case "difference": {
                    String leftK = text(d, "left");
                    String rightK = text(d, "right");
                    Double left = pickNullable(combined, leftK);
                    Double right = pickNullable(combined, rightK);
                    val = (left == null || right == null) ? null : (left - right);
                    ScaleKind skL = scaleKind.getOrDefault(leftK == null ? "" : leftK.trim(), ScaleKind.NONE);
                    ScaleKind skR = scaleKind.getOrDefault(rightK == null ? "" : rightK.trim(), ScaleKind.NONE);
                    if (skL == ScaleKind.PERCENT || skR == ScaleKind.PERCENT) {
                        if (skL != ScaleKind.PERCENT || skR != ScaleKind.PERCENT) {
                            throw new CachedTabularDecisionToolException("DERIVED_SCALE_MISMATCH",
                                    "difference requires both operands to be percent-scaled when either is ratio_percent or scale(ratio,100).");
                        }
                        scaleKind.put(name, ScaleKind.PERCENT);
                    } else {
                        scaleKind.put(name, ScaleKind.NONE);
                    }
                    break;
                }
                case "scale": {
                    String inpKey = text(d, "input");
                    Double in = pickNullable(combined, inpKey);
                    JsonNode fac = d.get("factor");
                    Double f = fac == null || fac.isNull() || !fac.isNumber() ? null : fac.doubleValue();
                    if (f == null) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "scale requires numeric factor.");
                    }
                    val = in == null ? null : (in * f);
                    ScaleKind inSk = scaleKind.getOrDefault(inpKey == null ? "" : inpKey.trim(), ScaleKind.NONE);
                    if (inSk == ScaleKind.FRACTION && almost100(f)) {
                        scaleKind.put(name, ScaleKind.PERCENT);
                    } else if (inSk == ScaleKind.PERCENT) {
                        scaleKind.put(name, ScaleKind.PERCENT);
                    } else {
                        scaleKind.put(name, ScaleKind.NONE);
                    }
                    break;
                }
                case "sum_values":
                case "multiply": {
                    JsonNode ins = d.get("inputs");
                    if (ins == null || !ins.isArray() || ins.size() < 1) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", op + " requires non-empty inputs.");
                    }
                    if (ins.size() > MAX_DERIVED_INPUTS) {
                        throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                                op + " accepts at most " + MAX_DERIVED_INPUTS + " inputs.");
                    }
                    if ("multiply".equals(op)) {
                        for (JsonNode in : ins) {
                            if (in == null || !in.isTextual()) {
                                continue;
                            }
                            String ref = in.asText().trim();
                            if (scaleKind.getOrDefault(ref, ScaleKind.NONE) == ScaleKind.PERCENT) {
                                throw new CachedTabularDecisionToolException("DERIVED_SCALE_MISMATCH",
                                        "multiply cannot combine percent-scaled (0..100) inputs (ratio_percent, scale(ratio,100), sum_values of percents, or difference of percents).");
                            }
                        }
                    }
                    if ("multiply".equals(op)) {
                        Double acc = null;
                        boolean broken = false;
                        boolean any = false;
                        for (JsonNode in : ins) {
                            if (in == null || !in.isTextual()) {
                                continue;
                            }
                            Double x = pickNullable(combined, in.asText().trim());
                            if (x == null) {
                                broken = true;
                                break;
                            }
                            acc = acc == null ? x : (acc * x);
                            any = true;
                        }
                        val = broken || !any ? null : acc;
                        scaleKind.put(name, ScaleKind.NONE);
                    } else {
                        double s = 0.0;
                        boolean any = false;
                        for (JsonNode in : ins) {
                            if (in == null || !in.isTextual()) {
                                continue;
                            }
                            Double x = pickNullable(combined, in.asText().trim());
                            if (x != null) {
                                s += x;
                                any = true;
                            }
                        }
                        val = any ? s : null;
                        boolean hasPercent = false;
                        boolean hasNonPercent = false;
                        for (JsonNode in : ins) {
                            if (in == null || !in.isTextual()) {
                                continue;
                            }
                            ScaleKind sk = scaleKind.getOrDefault(in.asText().trim(), ScaleKind.NONE);
                            if (sk == ScaleKind.PERCENT) {
                                hasPercent = true;
                            } else {
                                hasNonPercent = true;
                            }
                        }
                        if (hasPercent && hasNonPercent) {
                            throw new CachedTabularDecisionToolException("DERIVED_SCALE_MISMATCH",
                                    "sum_values cannot mix percent-scaled inputs with other scales.");
                        }
                        scaleKind.put(name, hasPercent ? ScaleKind.PERCENT : ScaleKind.NONE);
                    }
                    break;
                }
                default:
                    throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "Unsupported derived op: " + op);
            }
            out.put(name, val);
            combined.put(name, val);
        }
        return out;
    }

    private static boolean almost100(double f) {
        return Math.abs(f - 100.0) < 1e-4;
    }

    private static Double pickNullable(Map<String, Double> m, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return m.get(key.trim());
    }

    private static DataShapeDefinition buildOutputShape(DataShapeDefinition shape, InfoTable src, List<String> groupCols,
            List<String> measureNames, List<JsonNode> derivedList) {
        DataShapeDefinition dsd = new DataShapeDefinition();
        int ord = 0;
        for (String gc : groupCols) {
            BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, gc);
            addField(dsd, gc, bt != null ? bt : BaseTypes.STRING, ord++);
        }
        for (String mn : measureNames) {
            addField(dsd, mn, BaseTypes.NUMBER, ord++);
        }
        for (JsonNode d : derivedList) {
            addField(dsd, text(d, "name").trim(), BaseTypes.NUMBER, ord++);
        }
        return dsd;
    }

    private static void addField(DataShapeDefinition dsd, String name, BaseTypes bt, int ordinal) {
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(bt);
        fd.setOrdinal(ordinal);
        dsd.addFieldDefinition(fd);
    }

    private static InfoTable metricRowsToTable(DataShapeDefinition outShape, List<String> groupCols,
            List<String> measureNames, List<JsonNode> derivedList, List<MetricRow> rows) {
        InfoTable out = new InfoTable(outShape);
        for (MetricRow mr : rows) {
            ValueCollection vc = new ValueCollection();
            for (int i = 0; i < groupCols.size(); i++) {
                String gcol = groupCols.get(i);
                Object pv = i < mr.groupPrimitives.size() ? mr.groupPrimitives.get(i) : null;
                if (pv instanceof IPrimitiveType) {
                    vc.put(gcol, (IPrimitiveType) pv);
                } else {
                    vc.put(gcol, null);
                }
            }
            for (String mn : measureNames) {
                Double v = mr.measures.get(mn);
                if (v == null) {
                    vc.put(mn, null);
                } else {
                    vc.put(mn, new NumberPrimitive(v));
                }
            }
            for (JsonNode d : derivedList) {
                String dn = text(d, "name").trim();
                Double v = mr.derived.get(dn);
                if (v == null) {
                    vc.put(dn, null);
                } else {
                    vc.put(dn, new NumberPrimitive(v));
                }
            }
            out.addRow(vc);
        }
        return out;
    }

    private static String formatSuccess(String readCacheId, InfoTable src, InfoTable out, int sourceRows,
            int groupCount, int matchCount, List<String> groupCols, List<String> measureNames, List<JsonNode> derivedList,
            JsonNode having) throws Exception {
        int threshold = InvokeServiceExecutor.largeTableRowThreshold();
        List<String> cols = columnNames(out);
        int rc = out.getRowCount();
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "success");
        o.put("sourceCacheId", readCacheId);
        o.put("rowCount", sourceRows);
        o.put("groupCount", groupCount);
        o.put("matchCount", matchCount);
        ArrayNode gb = MAPPER.createArrayNode();
        for (String c : groupCols) {
            gb.add(c);
        }
        o.set("groupBy", gb);
        ArrayNode mn = MAPPER.createArrayNode();
        for (String m : measureNames) {
            mn.add(m);
        }
        o.set("measures", mn);
        ArrayNode dn = MAPPER.createArrayNode();
        for (JsonNode d : derivedList) {
            dn.add(text(d, "name").trim());
        }
        o.set("derived", dn);
        if (having != null && !having.isNull()) {
            o.set("predicate", MAPPER.readTree(MAPPER.writeValueAsString(having)));
        }
        if (rc == 0) {
            o.put("resultKind", "CACHED_GROUP_METRIC_EMPTY");
            o.put("totalRows", matchCount);
            o.putArray("rows");
            o.set("columns", columnMeta(cols));
            CachedTabularToolsExecutor.attachInsightEnvelope(o, readCacheId, out);
            CachedTabularToolsExecutor.attachBp6PublicFields(o,
                    CachedTabularToolsExecutor.descriptorForScannedSource(readCacheId, src, matchCount,
                            "tabulate_cached_result.group_metric"));
            return MAPPER.writeValueAsString(o);
        }
        /** Post-having grouped row count (§11.3); {@code rc} is the sliced page placed in {@code out}. */
        boolean noTruncation = rc == matchCount;
        boolean killAnswerSetComplete = answerSetCompleteKillSwitchOn();
        if (rc > ANSWER_SET_COMPLETE_MAX_ROWS) {
            writeGroupMetricLargeBranch(o, readCacheId, out, cols, rc, matchCount, threshold);
        } else if (rc <= threshold) {
            ArrayNode allRows = buildGroupMetricRowsJson(out, cols, rc);
            writeGroupMetricInlineBranch(o, readCacheId, out, cols, rc, matchCount, noTruncation, allRows,
                    killAnswerSetComplete);
        } else {
            // threshold < rc <= ANSWER_SET_COMPLETE_MAX_ROWS
            if (!killAnswerSetComplete) {
                writeGroupMetricLargeBranch(o, readCacheId, out, cols, rc, matchCount, threshold);
            } else {
                ArrayNode allRows = buildGroupMetricRowsJson(out, cols, rc);
                if (!noTruncation || !answerSetCompletePayloadEligible(rc, cols, out, allRows)) {
                    writeGroupMetricLargeBranch(o, readCacheId, out, cols, rc, matchCount, threshold);
                } else {
                    writeGroupMetricInlineBranch(o, readCacheId, out, cols, rc, matchCount, noTruncation, allRows, true);
                }
            }
        }
        CachedTabularToolsExecutor.attachBp6PublicFields(o,
                CachedTabularToolsExecutor.descriptorForScannedSource(readCacheId, src, matchCount,
                        "tabulate_cached_result.group_metric"));
        LOG.info("tabulate_cached_result group_metric ok sourceCacheId={} groups={} matchCount={}", readCacheId, groupCount,
                matchCount);
        return MAPPER.writeValueAsString(o);
    }

    private static boolean answerSetCompleteKillSwitchOn() {
        return Boolean.parseBoolean(System.getProperty(ANSWER_SET_COMPLETE_ENABLED_PROP, "true"));
    }

    /** Effective marker emitter for {@code LLM_TURN_PERFORMANCE.markerEmitterEnabled} ({@code docs/agent/llm-performance.md}). */
    public static boolean isAnswerSetCompleteMarkerEmitterEnabled() {
        return answerSetCompleteKillSwitchOn();
    }

    private static ArrayNode buildGroupMetricRowsJson(InfoTable out, List<String> cols, int rc) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < rc; i++) {
            rows.add(CachedTabularToolsExecutor.rowToObject(out, i, cols));
        }
        return rows;
    }

    private static void writeGroupMetricInlineBranch(ObjectNode o, String readCacheId, InfoTable out, List<String> cols,
            int rc, int matchCount, boolean noTruncation, ArrayNode allRows, boolean killAnswerSetComplete)
            throws Exception {
        o.put("resultKind", "CACHED_GROUP_METRIC_INLINE");
        o.put("totalRows", matchCount);
        o.set("rows", allRows);
        o.set("columns", columnMeta(cols));
        String transformedCacheId = InvokeServiceExecutor.storeInfotableInConversationCache(out);
        o.put("cacheId", transformedCacheId);
        if (killAnswerSetComplete && noTruncation && answerSetCompletePayloadEligible(rc, cols, out, allRows)) {
            o.put("answerSetComplete", true);
            o.put("sampleOnly", false);
            o.put("rowsOmitted", false);
            o.put("returnedRows", rc);
        }
        CachedTabularToolsExecutor.attachInsightEnvelope(o, readCacheId, out);
    }

    private static void writeGroupMetricLargeBranch(ObjectNode o, String readCacheId, InfoTable out, List<String> cols,
            int rc, int matchCount, int threshold) throws Exception {
        String newCacheId = InvokeServiceExecutor.storeInfotableInConversationCache(out);
        o.put("resultKind", "CACHED_GROUP_METRIC_LARGE");
        o.put("totalRows", matchCount);
        o.put("cacheId", newCacheId);
        ArrayNode sample = MAPPER.createArrayNode();
        for (int i = 0; i < Math.min(threshold, rc); i++) {
            sample.add(CachedTabularToolsExecutor.rowToObject(out, i, cols));
        }
        o.set("sampleRows", sample);
        o.set("columns", columnMeta(cols));
        o.put("hint", InvokeServiceExecutor.FETCH_CACHED_LARGE_TABLE_HINT);
        CachedTabularToolsExecutor.attachInsightEnvelope(o, readCacheId, out);
    }

    private static boolean outputHasPasswordColumn(InfoTable out, List<String> cols) {
        DataShapeDefinition ds;
        try {
            ds = out.getDataShape();
        } catch (Exception e) {
            return false;
        }
        if (ds == null || ds.getFields() == null) {
            return false;
        }
        Set<String> colSet = new HashSet<>(cols);
        for (FieldDefinition fd : ds.getFields().values()) {
            if (fd == null || fd.getName() == null) {
                continue;
            }
            if (!colSet.contains(fd.getName())) {
                continue;
            }
            if (fd.getBaseType() == BaseTypes.PASSWORD) {
                return true;
            }
        }
        return false;
    }

    private static boolean answerSetCompletePayloadEligible(int rowCount, List<String> cols, InfoTable out,
            ArrayNode rowsJson) {
        if (rowCount < 1 || rowCount > ANSWER_SET_COMPLETE_MAX_ROWS) {
            return false;
        }
        if (cols == null || cols.size() > ANSWER_SET_COMPLETE_MAX_COLS) {
            return false;
        }
        if (outputHasPasswordColumn(out, cols)) {
            return false;
        }
        try {
            String serialized = MAPPER.writeValueAsString(rowsJson);
            return serialized.getBytes(StandardCharsets.UTF_8).length <= ANSWER_SET_COMPLETE_MAX_ROWS_JSON_UTF8_BYTES;
        } catch (Exception e) {
            return false;
        }
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

    private static List<String> columnNames(InfoTable it) {
        List<String> names = new ArrayList<>();
        try {
            DataShapeDefinition ds = it.getDataShape();
            if (ds != null && ds.getFields() != null) {
                for (FieldDefinition f : ds.getFields().values()) {
                    if (f.getName() != null) {
                        names.add(f.getName());
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return names;
    }

    private static BaseTypes columnBaseTypeOf(InfoTable it, String col) {
        try {
            DataShapeDefinition ds = it.getDataShape();
            if (ds != null && ds.getFields() != null) {
                for (FieldDefinition f : ds.getFields().values()) {
                    if (col.equals(f.getName())) {
                        return f.getBaseType();
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private static String cellToSortKey(ValueCollection row, String col) {
        if (row == null) {
            return "";
        }
        Object v = row.getValue(col);
        if (v == null) {
            return "";
        }
        if (v instanceof com.thingworx.types.primitives.IPrimitiveType) {
            try {
                Object inner = ((com.thingworx.types.primitives.IPrimitiveType) v).getValue();
                return inner == null ? "" : String.valueOf(inner);
            } catch (Exception e) {
                return v.toString();
            }
        }
        return String.valueOf(v);
    }

    private static Double extractDouble(ValueCollection row, String col) {
        if (row == null) {
            return null;
        }
        Object v = row.getValue(col);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof com.thingworx.types.primitives.IPrimitiveType) {
            try {
                if (v instanceof com.thingworx.types.primitives.NumberPrimitive) {
                    return ((com.thingworx.types.primitives.NumberPrimitive) v).getValue();
                }
                if (v instanceof com.thingworx.types.primitives.IntegerPrimitive) {
                    return (double) ((com.thingworx.types.primitives.IntegerPrimitive) v).getValue();
                }
                if (v instanceof com.thingworx.types.primitives.LongPrimitive) {
                    return (double) ((com.thingworx.types.primitives.LongPrimitive) v).getValue();
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static DateTime extractDateTime(ValueCollection row, String col) {
        if (row == null) {
            return null;
        }
        Object v = row.getValue(col);
        if (v == null) {
            return null;
        }
        if (v instanceof com.thingworx.types.primitives.DatetimePrimitive) {
            try {
                return ((com.thingworx.types.primitives.DatetimePrimitive) v).getValue();
            } catch (Exception ignored) {
                return null;
            }
        }
        if (v instanceof DateTime) {
            return (DateTime) v;
        }
        return null;
    }

    /** Linear interpolation on sorted array; {@code p} in {@code [0,1]} — delegates to U5-shared {@code h=(n-1)p}. */
    private static double percentileLinearSorted(double[] sorted, double p) {
        return com.thingworx.things.agent.analysis.stats.LinearPercentile.ofSorted(sorted, p);
    }

    private static double[] collectNumericMeasureValues(InfoTable src, DataShapeDefinition shape, List<Integer> rows,
            String mcol, IFilter mf) throws CachedTabularDecisionToolException {
        List<Double> tmp = new ArrayList<>();
        for (int ri : rows) {
            if (mf != null && !CachedTabularDecisionPredicate.evaluateIfilter(mf, src.getRow(ri))) {
                continue;
            }
            Double d = extractDouble(src.getRow(ri), mcol);
            if (d != null && !d.isNaN()) {
                tmp.add(d);
            }
        }
        double[] out = new double[tmp.size()];
        for (int i = 0; i < tmp.size(); i++) {
            out[i] = tmp.get(i);
        }
        return out;
    }

    private static Boolean extractBooleanCell(ValueCollection row, String col) {
        if (row == null) {
            return null;
        }
        Object v = row.getValue(col);
        if (v == null) {
            return null;
        }
        if (v instanceof BooleanPrimitive) {
            try {
                return ((BooleanPrimitive) v).getValue();
            } catch (Exception e) {
                return null;
            }
        }
        if (v instanceof IPrimitiveType) {
            try {
                Object inner = ((IPrimitiveType) v).getValue();
                if (inner instanceof Boolean) {
                    return (Boolean) inner;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static Double extractMeasureValueAsDouble(InfoTable src, DataShapeDefinition shape, String mcol, int ri) {
        ValueCollection row = src.getRow(ri);
        BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, mcol);
        if (bt == BaseTypes.DATETIME) {
            DateTime dt = extractDateTime(row, mcol);
            return dt == null ? null : (double) dt.getMillis();
        }
        if (bt == BaseTypes.BOOLEAN) {
            Boolean b = extractBooleanCell(row, mcol);
            return b == null ? null : (b ? 1.0 : 0.0);
        }
        return extractDouble(row, mcol);
    }

    private static int compareRowsForOrderBy(InfoTable src, DataShapeDefinition shape, String orderCol, BaseTypes bt, int ia,
            int ib) {
        ValueCollection ra = src.getRow(ia);
        ValueCollection rb = src.getRow(ib);
        boolean na = ra == null || ra.getValue(orderCol) == null;
        boolean nb = rb == null || rb.getValue(orderCol) == null;
        if (na && nb) {
            return 0;
        }
        if (na) {
            return 1;
        }
        if (nb) {
            return -1;
        }
        if (bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG) {
            Double da = extractDouble(ra, orderCol);
            Double db = extractDouble(rb, orderCol);
            if (da == null && db == null) {
                return 0;
            }
            if (da == null) {
                return 1;
            }
            if (db == null) {
                return -1;
            }
            return Double.compare(da, db);
        }
        if (bt == BaseTypes.DATETIME) {
            DateTime ta = extractDateTime(ra, orderCol);
            DateTime tb = extractDateTime(rb, orderCol);
            if (ta == null && tb == null) {
                return 0;
            }
            if (ta == null) {
                return 1;
            }
            if (tb == null) {
                return -1;
            }
            return Long.compare(ta.getMillis(), tb.getMillis());
        }
        if (bt == BaseTypes.BOOLEAN) {
            Boolean ba = extractBooleanCell(ra, orderCol);
            Boolean bb = extractBooleanCell(rb, orderCol);
            if (ba == null && bb == null) {
                return 0;
            }
            if (ba == null) {
                return 1;
            }
            if (bb == null) {
                return -1;
            }
            return Boolean.compare(ba, bb);
        }
        String sa = cellToSortKey(ra, orderCol);
        String sb = cellToSortKey(rb, orderCol);
        return sa.compareTo(sb);
    }

    private static String text(JsonNode n, String field) {
        JsonNode x = n == null ? null : n.get(field);
        if (x == null || x.isNull()) {
            return null;
        }
        return x.asText();
    }
}
