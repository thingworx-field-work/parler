package com.thingworx.things.agent.tools;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

/**
 * Column typing for tool JSON built from {@link InfoTable} rows (wire {@code baseType}, PASSWORD masking).
 * <p>No static {@code LogUtilities} here: bare JUnit must load this class without ThingWorx security providers.</p>
 */
public final class ParlerInfotableJsonUtil {

    private ParlerInfotableJsonUtil() {}

    /**
     * ThingWorx {@link BaseTypes#name()} for wire / {@code TableBlock} column metadata (e.g. {@code STRING},
     * {@code PASSWORD}).
     */
    public static String wireBaseTypeForColumn(InfoTable it, String colName) {
        try {
            DataShapeDefinition ds = it.getDataShape();
            if (ds != null && ds.getFields() != null) {
                for (FieldDefinition f : ds.getFields().values()) {
                    if (colName.equals(f.getName())) {
                        BaseTypes bt = f.getBaseType();
                        return bt != null ? bt.name() : "STRING";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "STRING";
    }

    /**
     * Pure predicate for wire / {@code TableBlock} {@code columns[].baseType} strings — safe for offline JUnit
     * (no {@link InfoTable} / ThingWorx static init). Production row masking uses {@link #isPasswordColumn}.
     */
    public static boolean isPasswordBaseTypeName(String wireBaseTypeName) {
        return wireBaseTypeName != null && BaseTypes.PASSWORD.name().equals(wireBaseTypeName);
    }

    /**
     * Shape-only predicate (safe for offline JUnit — no {@link InfoTable} static init).
     */
    public static boolean isPasswordColumn(DataShapeDefinition ds, String colName) {
        if (ds == null || colName == null) {
            return false;
        }
        try {
            if (ds.getFields() != null) {
                for (FieldDefinition f : ds.getFields().values()) {
                    if (colName.equals(f.getName())) {
                        BaseTypes bt = f.getBaseType();
                        return bt == BaseTypes.PASSWORD;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static boolean isPasswordColumn(InfoTable it, String colName) {
        if (it == null || colName == null) {
            return false;
        }
        try {
            return isPasswordColumn(it.getDataShape(), colName);
        } catch (Exception e) {
            return false;
        }
    }

    /** Literal written to tool JSON / LLM-bound rows for PASSWORD columns (see {@code docs/ui/table-view-solution.md} §8). */
    public static String passwordColumnLlmPlaceholder() {
        return "***";
    }
}
