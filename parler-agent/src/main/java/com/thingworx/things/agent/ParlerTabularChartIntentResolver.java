package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.IPrimitiveType;

/**
 * Phase 1c intent → concrete {@code line}/{@code bar}/{@code scatter} selection for tabular charts
 * (see {@code docs/agent/chart-intent.md}).
 */
public final class ParlerTabularChartIntentResolver {

    private ParlerTabularChartIntentResolver() {}

    public static boolean isKnownIntentToken(String intentLower) {
        if (intentLower == null || intentLower.isBlank()) {
            return false;
        }
        String t = intentLower.trim().toLowerCase(Locale.ROOT);
        return "time_trend".equals(t) || "rank".equals(t) || "compare_groups".equals(t) || "correlation".equals(t)
                || "distribution".equals(t) || "status_timeline".equals(t) || "composition".equals(t);
    }

    public static final class IntentOutcome {
        public final boolean fallback;
        public final String fallbackReason;
        public final String kind;
        public final boolean sortBarPrimaryYDesc;
        /** Structured tool error (not a phase fallback): the request cannot be served as asked. */
        public final String errorCode;

        private IntentOutcome(boolean fallback, String fallbackReason, String kind, boolean sortBarPrimaryYDesc) {
            this(fallback, fallbackReason, kind, sortBarPrimaryYDesc, null);
        }

        private IntentOutcome(boolean fallback, String fallbackReason, String kind, boolean sortBarPrimaryYDesc,
                String errorCode) {
            this.fallback = fallback;
            this.fallbackReason = fallbackReason;
            this.kind = kind;
            this.sortBarPrimaryYDesc = sortBarPrimaryYDesc;
            this.errorCode = errorCode;
        }

        public static IntentOutcome chart(String kind, boolean sortBarPrimaryYDesc) {
            return new IntentOutcome(false, null, kind, sortBarPrimaryYDesc);
        }

        public static IntentOutcome fb(String reason) {
            return new IntentOutcome(true, reason, null, false);
        }

        public static IntentOutcome error(String code) {
            return new IntentOutcome(false, null, null, false, code);
        }
    }

    public static IntentOutcome resolve(
            String intentRaw,
            InfoTable table,
            String xColumn,
            String yColumnSingle,
            List<ParlerTabularChartBuilder.SeriesSpec> multiY) {

        String intent = intentRaw.trim().toLowerCase(Locale.ROOT);
        String xCol = xColumn != null ? xColumn.trim() : "";
        List<String> yCols = new ArrayList<>();
        if (multiY != null && !multiY.isEmpty()) {
            for (ParlerTabularChartBuilder.SeriesSpec sp : multiY) {
                if (sp != null && sp.yColumn != null && !sp.yColumn.isBlank()) {
                    yCols.add(sp.yColumn.trim());
                }
            }
        } else if (yColumnSingle != null && !yColumnSingle.isBlank()) {
            yCols.add(yColumnSingle.trim());
        }

        // C2b-1 (design §7.4): distribution routes by source shape, before the two-row guard (one bin is legal).
        if ("distribution".equals(intent)) {
            if (ParlerTabularChartBuilder.hasHistogramSourceColumns(table)) {
                return IntentOutcome.chart("histogram", false);
            }
            if (ParlerTabularChartBuilder.hasBoxplotSourceColumns(table)) {
                return IntentOutcome.chart("boxplot", false);
            }
            return IntentOutcome.error("DISTRIBUTION_REQUIRES_BINNED_SOURCE");
        }

        int n = table != null ? table.getRowCount() : 0;
        if (n < 2) {
            return IntentOutcome.fb("INTENT_DATA_SHAPE_MISMATCH");
        }

        switch (intent) {
            case "status_timeline":
                return IntentOutcome.fb("STATUS_TIMELINE_NOT_SUPPORTED_IN_PHASE_1");
            case "time_trend": {
                ParlerTabularChartBuilder.LineScatterAxisKind ax =
                        ParlerTabularChartBuilder.classifyLineScatterAxis(table, xCol);
                if (ax == ParlerTabularChartBuilder.LineScatterAxisKind.UNSUPPORTED) {
                    return IntentOutcome.fb("INTENT_DATA_SHAPE_MISMATCH");
                }
                return IntentOutcome.chart("line", false);
            }
            case "correlation": {
                ParlerTabularChartBuilder.LineScatterAxisKind ax =
                        ParlerTabularChartBuilder.classifyLineScatterAxis(table, xCol);
                if (ax != ParlerTabularChartBuilder.LineScatterAxisKind.NUMERIC) {
                    return IntentOutcome.fb("INTENT_DATA_SHAPE_MISMATCH");
                }
                return IntentOutcome.chart("scatter", false);
            }
            case "rank":
                return IntentOutcome.chart("bar", true);
            case "composition":
                return IntentOutcome.chart("bar", true);
            case "compare_groups":
                if (extraStringAxisVariesPerX(table, xCol, yCols)) {
                    return IntentOutcome.fb("INTENT_SHAPE_NOT_SUPPORTED_IN_PHASE_1");
                }
                return IntentOutcome.chart("bar", false);
            default:
                return IntentOutcome.fb("INTENT_DATA_SHAPE_MISMATCH");
        }
    }

    /**
     * When some STRING column (other than X and Y metrics) takes multiple values for the same X label,
     * a grouped/stacked bar would be needed — not supported in Phase 1c.
     */
    private static boolean extraStringAxisVariesPerX(InfoTable table, String xCol, List<String> yCols) {
        if (table == null || xCol == null || xCol.isBlank()) {
            return false;
        }
        Set<String> ySet = new HashSet<>(yCols);
        List<String> stringCols = stringColumnsExcluding(table, xCol, ySet);
        for (String c : stringCols) {
            Map<String, Set<String>> perX = new LinkedHashMap<>();
            int rows = table.getRowCount();
            for (int r = 0; r < rows; r++) {
                ValueCollection row = table.getRow(r);
                if (row == null) {
                    continue;
                }
                String xv = categoryString(row, xCol, r);
                String cv = categoryString(row, c, r);
                perX.computeIfAbsent(xv, k -> new HashSet<>()).add(cv);
            }
            for (Set<String> vals : perX.values()) {
                if (vals.size() > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> stringColumnsExcluding(InfoTable table, String xCol, Set<String> yCols) {
        List<String> out = new ArrayList<>();
        try {
            DataShapeDefinition ds = table.getDataShape();
            if (ds != null) {
                FieldDefinitionCollection fc = ds.getFields();
                if (fc != null && fc.values() != null) {
                    for (FieldDefinition f : fc.values()) {
                        if (f == null || f.getName() == null) {
                            continue;
                        }
                        String name = f.getName();
                        if (name.equals(xCol) || yCols.contains(name)) {
                            continue;
                        }
                        BaseTypes bt = f.getBaseType();
                        if (bt == BaseTypes.STRING) {
                            out.add(name);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return out;
    }

    private static String categoryString(ValueCollection row, String col, int rowIndex) {
        Object raw = unwrapPrimitive(row.getValue(col));
        if (raw == null) {
            return "";
        }
        return String.valueOf(raw).trim();
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
}
