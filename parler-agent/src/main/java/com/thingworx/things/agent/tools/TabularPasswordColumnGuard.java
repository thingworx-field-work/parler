package com.thingworx.things.agent.tools;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.things.agent.tools.predicate.ParlerQueryFilterParser;

/**
 * Stage 4 PASSWORD column rejection for cached tabular transforms and charts ({@code docs/agent/protection.md} §4.7).
 * Uses {@link DataShapeDefinition} only so unit tests avoid {@link com.thingworx.types.InfoTable} static init.
 */
public final class TabularPasswordColumnGuard {

    public static final class Violation {
        public final String field;
        public final String columnName;

        public Violation(String field, String columnName) {
            this.field = field;
            this.columnName = columnName;
        }
    }

    private TabularPasswordColumnGuard() {}

    public static Violation tabulate(DataShapeDefinition ds, JsonNode root, String mode) {
        if (ds == null || root == null || mode == null) {
            return null;
        }
        switch (mode) {
            case "sort_topn":
                Violation st = sweepSortsFieldNames(ds, root.get("sorts"));
                if (st != null) {
                    return st;
                }
                String sortBy = text(root, "sortBy");
                if (sortBy != null && !sortBy.isEmpty() && ParlerInfotableJsonUtil.isPasswordColumn(ds, sortBy)) {
                    return new Violation("sortBy", sortBy);
                }
                Violation sf = sweepFieldsArray(ds, root.get("fields"));
                if (sf != null) {
                    return sf;
                }
                break;
            case "group_count":
                String groupCountBy = text(root, "groupBy");
                if (groupCountBy != null && !groupCountBy.isEmpty()
                        && ParlerInfotableJsonUtil.isPasswordColumn(ds, groupCountBy)) {
                    return new Violation("groupBy", groupCountBy);
                }
                break;
            case "group_aggregate":
                String groupAggBy = text(root, "groupBy");
                if (groupAggBy != null && !groupAggBy.isEmpty()
                        && ParlerInfotableJsonUtil.isPasswordColumn(ds, groupAggBy)) {
                    return new Violation("groupBy", groupAggBy);
                }
                String aggCol = text(root, "aggregateColumn");
                String fn = text(root, "fn");
                boolean isCount = fn != null && "count".equals(fn.trim().toLowerCase(Locale.ROOT));
                if (!isCount && aggCol != null && !aggCol.isEmpty()
                        && ParlerInfotableJsonUtil.isPasswordColumn(ds, aggCol)) {
                    return new Violation("aggregateColumn", aggCol);
                }
                break;
            default:
                break;
        }
        return null;
    }

    /**
 * PASSWORD guard for {@code tabulate_cached_result} decision modes ({@code filter_count}, {@code filter_rows},
 * {@code filter_sort_topn}, {@code group_metric}) — see {@code docs/agent/cached-table-decision-tools.md}.
     */
    public static Violation tabulateDecision(DataShapeDefinition ds, JsonNode root, String mode) {
        if (ds == null || root == null || mode == null) {
            return null;
        }
        switch (mode) {
            case "filter_count":
            case "filter_rows": {
                Violation w = decisionFilters(ds, root.get("filters"), "filters");
                if (w != null) {
                    return w;
                }
                Violation s = decisionSortColumns(ds, root);
                if (s != null) {
                    return s;
                }
                Violation fe = sweepFieldsArray(ds, root.get("fields"));
                if (fe != null) {
                    return fe;
                }
                if ("filter_count".equals(mode)) {
                    return decisionGroupByString(ds, root.get("groupBy"));
                }
                return null;
            }
            case "filter_sort_topn": {
                Violation w = decisionFiltersOptional(ds, root.get("filters"));
                if (w != null) {
                    return w;
                }
                Violation s = decisionSortColumns(ds, root);
                if (s != null) {
                    return s;
                }
                return sweepFieldsArray(ds, root.get("fields"));
            }
            case "group_metric":
                return decisionGroupMetric(ds, root);
            case CachedTabularDistributionExecutor.MODE_BIN_NUMERIC:
            case CachedTabularDistributionExecutor.MODE_BOX_SUMMARY:
                return decisionDistribution(ds, root);
            default:
                return null;
        }
    }

    private static Violation decisionFilters(DataShapeDefinition ds, JsonNode filters, String fieldLabel) {
        if (filters == null || filters.isNull()) {
            return null;
        }
        Set<String> cols = new HashSet<>();
        ParlerQueryFilterParser.collectFieldNames(filters, cols);
        for (String c : cols) {
            if (ParlerInfotableJsonUtil.isPasswordColumn(ds, c)) {
                return new Violation(fieldLabel, c);
            }
        }
        return null;
    }

    /** Optional top-level filters: absent or JSON null is allowed. */
    private static Violation decisionFiltersOptional(DataShapeDefinition ds, JsonNode filters) {
        if (filters == null || filters.isNull()) {
            return null;
        }
        return decisionFilters(ds, filters, "filters");
    }

    /** D1 distribution modes: {@code filters}, the value {@code column}, and a single {@code groupBy}. */
    private static Violation decisionDistribution(DataShapeDefinition ds, JsonNode root) {
        Violation rf = decisionFiltersOptional(ds, root.get("filters"));
        if (rf != null) {
            return rf;
        }
        JsonNode colN = root.get("column");
        if (colN != null && colN.isTextual()) {
            String c = colN.asText().trim();
            if (!c.isEmpty() && ParlerInfotableJsonUtil.isPasswordColumn(ds, c)) {
                return new Violation("column", c);
            }
        }
        JsonNode gb = root.get("groupBy");
        if (gb != null && gb.isArray()) {
            for (JsonNode el : gb) {
                if (el != null && el.isTextual()) {
                    String c = el.asText().trim();
                    if (!c.isEmpty() && ParlerInfotableJsonUtil.isPasswordColumn(ds, c)) {
                        return new Violation("groupBy", c);
                    }
                }
            }
            return null;
        }
        return decisionGroupByString(ds, gb);
    }

    private static Violation decisionGroupByString(DataShapeDefinition ds, JsonNode groupBy) {
        if (groupBy == null || groupBy.isNull()) {
            return null;
        }
        if (!groupBy.isTextual()) {
            return null;
        }
        String g = groupBy.asText().trim();
        if (g.isEmpty()) {
            return null;
        }
        if (ParlerInfotableJsonUtil.isPasswordColumn(ds, g)) {
            return new Violation("groupBy", g);
        }
        return null;
    }

    private static Violation decisionSortColumns(DataShapeDefinition ds, JsonNode root) {
        Violation v = sweepSortsFieldNames(ds, root.get("sorts"));
        if (v != null) {
            return v;
        }
        String sortBy = text(root, "sortBy");
        if (sortBy != null && !sortBy.isEmpty() && ParlerInfotableJsonUtil.isPasswordColumn(ds, sortBy)) {
            return new Violation("sortBy", sortBy);
        }
        return null;
    }

    private static Violation sweepSortsFieldNames(DataShapeDefinition ds, JsonNode sorts) {
        if (sorts == null || !sorts.isArray()) {
            return null;
        }
        for (JsonNode el : sorts) {
            if (el == null || !el.isObject()) {
                continue;
            }
            JsonNode fn = el.get("fieldName");
            if (fn != null && fn.isTextual()) {
                String col = fn.asText().trim();
                if (!col.isEmpty() && ParlerInfotableJsonUtil.isPasswordColumn(ds, col)) {
                    return new Violation("sorts.fieldName", col);
                }
            }
            JsonNode c = el.get("column");
            if (c != null && c.isTextual()) {
                String col = c.asText().trim();
                if (!col.isEmpty() && ParlerInfotableJsonUtil.isPasswordColumn(ds, col)) {
                    return new Violation("sorts.column", col);
                }
            }
        }
        return null;
    }

    private static Violation sweepFieldsArray(DataShapeDefinition ds, JsonNode fields) {
        if (fields == null || !fields.isArray()) {
            return null;
        }
        for (JsonNode el : fields) {
            if (el != null && el.isTextual()) {
                String col = el.asText().trim();
                if (!col.isEmpty() && ParlerInfotableJsonUtil.isPasswordColumn(ds, col)) {
                    return new Violation("fields", col);
                }
            }
        }
        return null;
    }

    private static Violation decisionGroupMetric(DataShapeDefinition ds, JsonNode root) {
        Violation rf = decisionFiltersOptional(ds, root.get("filters"));
        if (rf != null) {
            return rf;
        }
        Violation sf = sweepFieldsArray(ds, root.get("fields"));
        if (sf != null) {
            return sf;
        }
        JsonNode gb = root.get("groupBy");
        if (gb != null && gb.isArray()) {
            for (JsonNode el : gb) {
                if (el != null && el.isTextual()) {
                    String c = el.asText().trim();
                    if (!c.isEmpty() && ParlerInfotableJsonUtil.isPasswordColumn(ds, c)) {
                        return new Violation("groupBy", c);
                    }
                }
            }
        }
        JsonNode measures = root.get("measures");
        if (measures != null && measures.isArray()) {
            for (JsonNode m : measures) {
                JsonNode colN = m.get("column");
                if (colN != null && colN.isTextual()) {
                    String c = colN.asText().trim();
                    if (!c.isEmpty() && ParlerInfotableJsonUtil.isPasswordColumn(ds, c)) {
                        return new Violation("measures.column", c);
                    }
                }
                JsonNode wcol = m.get("weightColumn");
                if (wcol != null && wcol.isTextual()) {
                    String c = wcol.asText().trim();
                    if (!c.isEmpty() && ParlerInfotableJsonUtil.isPasswordColumn(ds, c)) {
                        return new Violation("measures.weightColumn", c);
                    }
                }
                JsonNode oby = m.get("orderBy");
                if (oby != null && oby.isTextual()) {
                    String c = oby.asText().trim();
                    if (!c.isEmpty() && ParlerInfotableJsonUtil.isPasswordColumn(ds, c)) {
                        return new Violation("measures.orderBy", c);
                    }
                }
                Violation w = decisionFilters(ds, m.get("filters"), "measures.filters");
                if (w != null) {
                    return w;
                }
                Violation wLegacy = decisionFilters(ds, m.get("where"), "measures.where");
                if (wLegacy != null) {
                    return wLegacy;
                }
            }
        }
        Violation h = decisionFiltersOptional(ds, root.get("having"));
        if (h != null) {
            return h;
        }
        return decisionSortColumns(ds, root);
    }

    public static Violation percentileExplicit(DataShapeDefinition ds, List<String> percentileCols) {
        if (ds == null || percentileCols == null || percentileCols.isEmpty()) {
            return null;
        }
        for (String pc : percentileCols) {
            if (ParlerInfotableJsonUtil.isPasswordColumn(ds, pc)) {
                return new Violation("percentileColumns", pc);
            }
        }
        return null;
    }

    public static Violation chartAxes(DataShapeDefinition ds, String xColumnRaw, String ySingle,
            List<String> seriesYColumns) {
        return chartAxes(ds, xColumnRaw, ySingle, seriesYColumns, null);
    }

    public static Violation chartAxes(DataShapeDefinition ds, String xColumnRaw, String ySingle,
            List<String> seriesYColumns, String seriesColumnRaw) {
        if (ds == null) {
            return null;
        }
        if (xColumnRaw != null && !xColumnRaw.isBlank()) {
            String xc = xColumnRaw.trim();
            if (ParlerInfotableJsonUtil.isPasswordColumn(ds, xc)) {
                return new Violation("xColumn", xc);
            }
        }
        if (ySingle != null && !ySingle.isBlank()) {
            String y = ySingle.trim();
            if (ParlerInfotableJsonUtil.isPasswordColumn(ds, y)) {
                return new Violation("yColumn", y);
            }
        }
        if (seriesYColumns != null) {
            for (String yc : seriesYColumns) {
                if (yc != null && !yc.isBlank()) {
                    String t = yc.trim();
                    if (ParlerInfotableJsonUtil.isPasswordColumn(ds, t)) {
                        return new Violation("series.yColumn", t);
                    }
                }
            }
        }
        if (seriesColumnRaw != null && !seriesColumnRaw.isBlank()) {
            String sc = seriesColumnRaw.trim();
            if (ParlerInfotableJsonUtil.isPasswordColumn(ds, sc)) {
                return new Violation("seriesColumn", sc);
            }
        }
        return null;
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
    }
}
