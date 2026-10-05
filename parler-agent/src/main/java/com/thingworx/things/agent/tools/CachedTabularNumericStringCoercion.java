package com.thingworx.things.agent.tools;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;

/**
 * Design §8: treat STRING/TEXT columns as numeric for comparisons only when every non-null cell parses as a number
 * ({@link Locale#ROOT} double syntax).
 */
public final class CachedTabularNumericStringCoercion {

    /** Column is not subject to string→numeric coercion (typed numeric, boolean, etc.). */
    public static final byte MODE_SKIP = 0;
    /** All non-null values parse as numbers; ordered numeric ops may coerce. */
    public static final byte MODE_COERCE = 1;
    /** At least one non-null fails parse, or mix of parseable and non-parseable non-nulls. */
    public static final byte MODE_MIXED = 2;

    private CachedTabularNumericStringCoercion() {}

    /**
     * Builds per-column coercion mode for every leaf column referenced in {@code predicate}.
     */
    public static Map<String, Byte> policiesForPredicate(InfoTable src, DataShapeDefinition shape, JsonNode predicate) {
        Set<String> cols = new HashSet<>();
        CachedTabularDecisionPredicate.collectSourceColumns(predicate, cols);
        Map<String, Byte> out = new HashMap<>();
        for (String c : cols) {
            BaseTypes bt = CachedTabularDecisionPredicate.columnBaseType(shape, src, c);
            if (bt == BaseTypes.STRING || bt == BaseTypes.TEXT) {
                out.put(c, scanStringColumn(src, c));
            }
        }
        return out;
    }

    /**
     * Coercion mode for a STRING/TEXT column (design §8). Non-string columns should not call this for measure typing;
     * callers may treat non-STRING/TEXT as {@link #MODE_SKIP}.
     */
    public static byte stringColumnNumericMode(InfoTable src, String col) {
        return scanStringColumn(src, col);
    }

    private static byte scanStringColumn(InfoTable src, String col) {
        boolean anyNonNull = false;
        boolean anyFail = false;
        int n = src.getRowCount();
        for (int i = 0; i < n; i++) {
            ValueCollection row = src.getRow(i);
            if (row == null) {
                continue;
            }
            Object v = row.getValue(col);
            if (v == null) {
                continue;
            }
            anyNonNull = true;
            String s = cellString(v);
            if (s == null) {
                anyFail = true;
                continue;
            }
            s = s.trim();
            if (s.isEmpty()) {
                anyFail = true;
                continue;
            }
            try {
                Double.parseDouble(s);
            } catch (NumberFormatException e) {
                anyFail = true;
            }
        }
        if (!anyNonNull) {
            return MODE_SKIP;
        }
        return anyFail ? MODE_MIXED : MODE_COERCE;
    }

    private static String cellString(Object v) {
        if (v == null) {
            return null;
        }
        try {
            if (v instanceof com.thingworx.types.primitives.IPrimitiveType) {
                Object inner = ((com.thingworx.types.primitives.IPrimitiveType) v).getValue();
                return inner == null ? null : String.valueOf(inner);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return String.valueOf(v);
    }
}
