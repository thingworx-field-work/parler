package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.data.filters.FilterFactory;
import com.thingworx.types.data.filters.IFilter;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.LongPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.things.agent.tools.predicate.ParlerQueryFilterParser;

/**
 * Predicate validation and evaluation for cached-table decision modes (see {@code docs/agent/cached-table-decision-tools.md}).
 */
public final class CachedTabularDecisionPredicate {

    static final int MAX_BOOL_DEPTH = 3;
    static final int MAX_LEAVES = 20;
    static final int MAX_IN_VALUES = 100;
    /** Max raw {@code like} / {@code not_like} pattern length (bounded matcher; no exponential backtracking). */
    static final int MAX_LIKE_PATTERN_LENGTH = 256;
    /** Max text length evaluated for {@code like} / {@code not_like} per cell. */
    static final int MAX_LIKE_CELL_CHARS = 8192;
    /** Max recursive steps for {@code like} matching (aborts with {@code INVALID_PREDICATE}). */
    static final int MAX_LIKE_STEPS = 200_000;

    private static final ThreadLocal<java.util.Map<String, Byte>> STRING_NUMERIC_COERCION = new ThreadLocal<>();

    /**
     * Per-call string→numeric coercion policy ({@link CachedTabularNumericStringCoercion}); set by
     * {@link CachedTabularToolsExecutor} around decision scans.
     */
    public static void setStringNumericCoercion(java.util.Map<String, Byte> m) {
        STRING_NUMERIC_COERCION.set(m);
    }

    public static void clearStringNumericCoercion() {
        STRING_NUMERIC_COERCION.remove();
    }

    static java.util.Map<String, Byte> stringNumericCoercion() {
        java.util.Map<String, Byte> m = STRING_NUMERIC_COERCION.get();
        return m == null ? java.util.Collections.emptyMap() : m;
    }

    private CachedTabularDecisionPredicate() {}

    /** Collects source column names referenced by the TWX filter tree (leaf {@code fieldName} only). */
    public static void collectSourceColumns(JsonNode predicate, Set<String> out) {
        if (predicate == null || !predicate.isObject()) {
            return;
        }
        ParlerQueryFilterParser.collectFieldNames(predicate, out);
    }

    /**
     * Validates caps and shape; throws {@link CachedTabularDecisionToolException} with {@link CachedTabularDecisionToolException#code}
     * on failure.
     */
    public static void validatePredicateTree(JsonNode predicate) throws CachedTabularDecisionToolException {
        if (predicate == null || predicate.isNull()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "where must be a JSON object.");
        }
        if (!predicate.isObject()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "where must be a JSON object.");
        }
        int leaves = countLeaves(predicate);
        if (leaves > MAX_LEAVES) {
            throw new CachedTabularDecisionToolException("TOO_MANY_PREDICATES",
                    "Predicate exceeds maximum of " + MAX_LEAVES + " leaf conditions.");
        }
        int depth = maxDepth(predicate);
        if (depth > MAX_BOOL_DEPTH) {
            throw new CachedTabularDecisionToolException("TOO_MANY_PREDICATES",
                    "Predicate boolean depth exceeds maximum of " + MAX_BOOL_DEPTH + ".");
        }
        validateNode(predicate);
    }

    private static int countLeaves(JsonNode n) {
        if (n == null || !n.isObject()) {
            return 0;
        }
        if (n.has("all") && n.get("all").isArray()) {
            int s = 0;
            for (JsonNode c : n.get("all")) {
                s += countLeaves(c);
            }
            return s;
        }
        if (n.has("any") && n.get("any").isArray()) {
            int s = 0;
            for (JsonNode c : n.get("any")) {
                s += countLeaves(c);
            }
            return s;
        }
        if (n.has("not")) {
            return countLeaves(n.get("not"));
        }
        return 1;
    }

    private static int maxDepth(JsonNode n) {
        if (n == null || !n.isObject()) {
            return 0;
        }
        if (n.has("all") && n.get("all").isArray()) {
            int m = 1;
            for (JsonNode c : n.get("all")) {
                m = Math.max(m, 1 + maxDepth(c));
            }
            return m;
        }
        if (n.has("any") && n.get("any").isArray()) {
            int m = 1;
            for (JsonNode c : n.get("any")) {
                m = Math.max(m, 1 + maxDepth(c));
            }
            return m;
        }
        if (n.has("not")) {
            return 1 + maxDepth(n.get("not"));
        }
        return 1;
    }

    private static void validateNode(JsonNode n) throws CachedTabularDecisionToolException {
        if (n == null || !n.isObject()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Predicate node must be an object.");
        }
        int composite = 0;
        if (n.has("all")) {
            composite++;
        }
        if (n.has("any")) {
            composite++;
        }
        if (n.has("not")) {
            composite++;
        }
        if (composite > 1) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Predicate may not combine all, any, and not.");
        }
        if (n.has("all")) {
            JsonNode arr = n.get("all");
            if (!arr.isArray() || arr.size() < 1) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "all must be a non-empty array.");
            }
            for (JsonNode c : arr) {
                validateNode(c);
            }
            return;
        }
        if (n.has("any")) {
            JsonNode arr = n.get("any");
            if (!arr.isArray() || arr.size() < 1) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "any must be a non-empty array.");
            }
            for (JsonNode c : arr) {
                validateNode(c);
            }
            return;
        }
        if (n.has("not")) {
            validateNode(n.get("not"));
            return;
        }
        validateLeafShape(n);
    }

    private static void validateLeafShape(JsonNode n) throws CachedTabularDecisionToolException {
        JsonNode col = n.get("column");
        if (col == null || !col.isTextual() || col.asText().trim().isEmpty()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Leaf predicate requires non-empty column.");
        }
        JsonNode opN = n.get("op");
        if (opN == null || !opN.isTextual()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Leaf predicate requires op.");
        }
        String op = opN.asText().trim().toLowerCase(Locale.ROOT);
        switch (op) {
            case "is_null":
            case "not_null":
            case "is_empty":
            case "not_empty":
                return;
            case "in":
            case "not_in":
                validateInValues(n.get("values"));
                return;
            case "between":
            case "not_between":
                if (!n.has("low") || n.get("low").isNull() || !n.has("high") || n.get("high").isNull()) {
                    throw new CachedTabularDecisionToolException("INVALID_PREDICATE", op + " requires low and high.");
                }
                return;
            case "lt":
            case "lte":
            case "gt":
            case "gte":
            case "eq":
            case "ne":
            case "contains":
            case "not_contains":
            case "starts_with":
            case "not_starts_with":
            case "ends_with":
            case "not_ends_with":
                if (!n.has("value") || n.get("value").isNull()) {
                    throw new CachedTabularDecisionToolException("INVALID_PREDICATE", op + " requires value.");
                }
                return;
            case "like":
            case "not_like": {
                JsonNode val = n.get("value");
                if (!n.has("value") || n.get("value").isNull()) {
                    throw new CachedTabularDecisionToolException("INVALID_PREDICATE", op + " requires value.");
                }
                if (val.isTextual() && val.asText().length() > MAX_LIKE_PATTERN_LENGTH) {
                    throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                            "like pattern exceeds max length " + MAX_LIKE_PATTERN_LENGTH + ".");
                }
                return;
            }
            default:
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Unknown predicate op: " + opN.asText());
        }
    }

    private static void validateInValues(JsonNode values) throws CachedTabularDecisionToolException {
        if (values == null || !values.isArray() || values.size() < 1) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "in/not_in requires non-empty values array.");
        }
        if (values.size() > MAX_IN_VALUES) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "in/not_in values exceed cap " + MAX_IN_VALUES + ".");
        }
    }

    /**
     * If a numeric column's values are all in {@code [0,1]} and the predicate uses a numeric bound {@code > 1}, fail with
     * {@code AMBIGUOUS_PERCENT_SCALE} (see design §8). {@code stringCoercionByColumn} comes from
     * {@link CachedTabularNumericStringCoercion#policiesForPredicate}.
     */
    public static void checkAmbiguousPercentScale(InfoTable src, DataShapeDefinition shape, JsonNode predicate,
            java.util.Map<String, Byte> stringCoercionByColumn) throws CachedTabularDecisionToolException {
        Set<String> cols = new HashSet<>();
        collectNumericColumnsWithLargeBounds(predicate, cols);
        for (String col : cols) {
            BaseTypes bt = columnBaseType(shape, src, col);
            if (isNumericType(bt)) {
                if (!allNonNullNumericValuesInUnitInterval(src, col)) {
                    continue;
                }
                throw new CachedTabularDecisionToolException("AMBIGUOUS_PERCENT_SCALE",
                        "Column \"" + col + "\" values lie in 0..1 but the predicate uses a bound greater than 1; "
                                + "use an explicit 0..1 threshold or a ratio_percent derived column (0..100).");
            }
            if (bt == BaseTypes.STRING || bt == BaseTypes.TEXT) {
                Byte mode = stringCoercionByColumn == null ? null : stringCoercionByColumn.get(col);
                if (mode == null || mode.byteValue() != CachedTabularNumericStringCoercion.MODE_COERCE) {
                    continue;
                }
                if (!allNonNullCoercedStringDoublesInUnitInterval(src, col)) {
                    continue;
                }
                throw new CachedTabularDecisionToolException("AMBIGUOUS_PERCENT_SCALE",
                        "Column \"" + col + "\" values lie in 0..1 but the predicate uses a bound greater than 1; "
                                + "use an explicit 0..1 threshold or a ratio_percent derived column (0..100).");
            }
        }
    }

    private static void collectNumericColumnsWithLargeBounds(JsonNode n, Set<String> out) {
        if (n == null || !n.isObject()) {
            return;
        }
        JsonNode typeN = n.get("type");
        if (typeN == null || !typeN.isTextual()) {
            return;
        }
        String type = typeN.asText().trim().toUpperCase(Locale.ROOT);
        if (FilterFactory.FILTER_COMPOSITE_AND.equals(type) || FilterFactory.FILTER_COMPOSITE_OR.equals(type)) {
            JsonNode arr = n.get("filters");
            if (arr != null && arr.isArray()) {
                for (JsonNode c : arr) {
                    collectNumericColumnsWithLargeBounds(c, out);
                }
            }
            return;
        }
        if (FilterFactory.FILTER_COMPOSITE_NOT.equals(type)) {
            JsonNode arr = n.get("filters");
            if (arr != null && arr.isArray() && arr.size() == 1) {
                collectNumericColumnsWithLargeBounds(arr.get(0), out);
            }
            return;
        }
        JsonNode colN = n.get("fieldName");
        if (colN == null || !colN.isTextual()) {
            return;
        }
        String col = colN.asText().trim();
        switch (type) {
            case "LT":
            case "LE":
            case "GT":
            case "GE":
                if (hasNumberGreaterThanOne(n.get("value"))) {
                    out.add(col);
                }
                return;
            case "EQ":
            case "NE":
                if (hasNumberGreaterThanOne(n.get("value"))) {
                    out.add(col);
                }
                return;
            case "BETWEEN":
            case "NOTBETWEEN":
                if (hasNumberGreaterThanOne(n.get("from")) || hasNumberGreaterThanOne(n.get("to"))) {
                    out.add(col);
                }
                return;
            case "IN":
            case "NOTIN":
                if (valuesContainNumberGreaterThanOne(n.get("values"))) {
                    out.add(col);
                }
                return;
            default:
        }
    }

    private static boolean valuesContainNumberGreaterThanOne(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return false;
        }
        for (JsonNode v : arr) {
            if (hasNumberGreaterThanOne(v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNumberGreaterThanOne(JsonNode v) {
        if (v == null || v.isNull()) {
            return false;
        }
        Double d = jsonToDouble(v);
        return d != null && d > 1.0;
    }

    private static boolean allNonNullNumericValuesInUnitInterval(InfoTable src, String col) {
        int n = src.getRowCount();
        boolean any = false;
        for (int i = 0; i < n; i++) {
            Double d = extractDouble(src.getRow(i), col);
            if (d == null || d.isNaN()) {
                continue;
            }
            any = true;
            if (d < 0.0 || d > 1.0) {
                return false;
            }
        }
        return any;
    }

    private static boolean allNonNullCoercedStringDoublesInUnitInterval(InfoTable src, String col) {
        int n = src.getRowCount();
        boolean any = false;
        for (int i = 0; i < n; i++) {
            String s = extractString(src.getRow(i), col, true);
            if (s == null || s.trim().isEmpty()) {
                continue;
            }
            any = true;
            double d;
            try {
                d = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (d < 0.0 || d > 1.0) {
                return false;
            }
        }
        return any;
    }

    public static boolean evaluate(InfoTable src, int rowIndex, DataShapeDefinition shape, JsonNode predicate)
            throws CachedTabularDecisionToolException {
        if (predicate == null || !predicate.isObject()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "where must be a JSON object.");
        }
        if (predicate.has("all")) {
            JsonNode arr = predicate.get("all");
            for (JsonNode c : arr) {
                if (!evaluate(src, rowIndex, shape, c)) {
                    return false;
                }
            }
            return true;
        }
        if (predicate.has("any")) {
            JsonNode arr = predicate.get("any");
            for (JsonNode c : arr) {
                if (evaluate(src, rowIndex, shape, c)) {
                    return true;
                }
            }
            return false;
        }
        if (predicate.has("not")) {
            return !evaluate(src, rowIndex, shape, predicate.get("not"));
        }
        return evaluateLeaf(src, rowIndex, shape, predicate);
    }

    private static boolean evaluateLeaf(InfoTable src, int rowIndex, DataShapeDefinition shape, JsonNode n)
            throws CachedTabularDecisionToolException {
        String col = n.get("column").asText().trim();
        List<String> names = columnNames(src);
        if (!names.contains(col)) {
            throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + col);
        }
        BaseTypes bt = columnBaseType(shape, src, col);
        if (bt == null) {
            throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + col);
        }
        if (isUnsupportedComplexBaseType(bt)) {
            throw new CachedTabularDecisionToolException("UNSUPPORTED_COLUMN_TYPE",
                    "Column \"" + col + "\" has an unsupported type for predicates.");
        }
        if (ParlerInfotableJsonUtil.isPasswordColumn(shape, col)) {
            throw new CachedTabularDecisionToolException("PROTECTED_TABULAR_COLUMN_BLOCKED",
                    "Cannot use PASSWORD column \"" + col + "\" in where.");
        }
        String op = n.get("op").asText().trim().toLowerCase(Locale.ROOT);
        ValueCollection row = src.getRow(rowIndex);
        switch (op) {
            case "is_null":
                return isNullCell(row, col);
            case "not_null":
                return !isNullCell(row, col);
            case "is_empty":
                return stringIsEmpty(row, col, bt, boolOrDefault(n, "trim", true));
            case "not_empty":
                return !stringIsEmpty(row, col, bt, boolOrDefault(n, "trim", true));
            case "lt":
            case "lte":
            case "gt":
            case "gte":
                return compareOrdered(row, col, bt, op, n.get("value"));
            case "eq":
            case "ne":
                return compareEquality(row, col, bt, op, n.get("value"), boolOrDefault(n, "caseSensitive", true),
                        boolOrDefault(n, "trim", true));
            case "between":
            case "not_between": {
                boolean incLo = boolOrDefault(n, "includeLow", true);
                boolean incHi = boolOrDefault(n, "includeHigh", true);
                boolean b = between(row, col, bt, n.get("low"), n.get("high"), incLo, incHi);
                return "not_between".equals(op) ? !b : b;
            }
            case "in":
            case "not_in": {
                boolean in = inSet(row, col, bt, n.get("values"), boolOrDefault(n, "caseSensitive", true),
                        boolOrDefault(n, "trim", true));
                return "not_in".equals(op) ? !in : in;
            }
            case "contains":
            case "not_contains":
            case "starts_with":
            case "not_starts_with":
            case "ends_with":
            case "not_ends_with":
            case "like":
            case "not_like":
                return stringPattern(row, col, bt, op, textValue(n, "value"), boolOrDefault(n, "caseSensitive", false),
                        boolOrDefault(n, "trim", true));
            default:
                throw new CachedTabularDecisionToolException("UNSUPPORTED_OPERATOR", "Unsupported op: " + op);
        }
    }

    private static boolean boolOrDefault(JsonNode n, String field, boolean def) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return def;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        return def;
    }

    private static String textValue(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isNumber() || v.isBoolean()) {
            return v.asText();
        }
        return null;
    }

    private static boolean isNullCell(ValueCollection row, String col) {
        if (row == null) {
            return true;
        }
        return row.getValue(col) == null;
    }

    private static boolean stringIsEmpty(ValueCollection row, String col, BaseTypes bt, boolean trim)
            throws CachedTabularDecisionToolException {
        if (!isStringLike(bt)) {
            throw new CachedTabularDecisionToolException("TYPE_MISMATCH", "is_empty applies to string columns only.");
        }
        if (row == null) {
            return true;
        }
        Object v = row.getValue(col);
        if (v == null) {
            return true;
        }
        String s = primitiveToString(v);
        if (s == null) {
            return true;
        }
        if (trim) {
            s = s.trim();
        }
        return s.isEmpty();
    }

    private static boolean compareOrdered(ValueCollection row, String col, BaseTypes bt, String op, JsonNode valueNode)
            throws CachedTabularDecisionToolException {
        if (isNumericType(bt)) {
            Double cell = extractDouble(row, col);
            Double bound = jsonToDouble(valueNode);
            if (bound == null) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Numeric comparison requires a numeric value.");
            }
            if (cell == null || cell.isNaN()) {
                return false;
            }
            int c = Double.compare(cell, bound);
            if ("lt".equals(op)) {
                return c < 0;
            }
            if ("lte".equals(op)) {
                return c <= 0;
            }
            if ("gt".equals(op)) {
                return c > 0;
            }
            if ("gte".equals(op)) {
                return c >= 0;
            }
            return false;
        }
        if (bt == BaseTypes.DATETIME) {
            DateTime cell = extractDateTime(row, col);
            DateTime bound = jsonToDateTime(valueNode);
            if (bound == null) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Datetime comparison requires ISO-8601 value.");
            }
            if (cell == null) {
                return false;
            }
            int c = Long.compare(cell.getMillis(), bound.getMillis());
            if ("lt".equals(op)) {
                return c < 0;
            }
            if ("lte".equals(op)) {
                return c <= 0;
            }
            if ("gt".equals(op)) {
                return c > 0;
            }
            if ("gte".equals(op)) {
                return c >= 0;
            }
            return false;
        }
        if (isStringLike(bt)) {
            return compareOrderedStringNumeric(row, col, op, valueNode);
        }
        throw new CachedTabularDecisionToolException("UNSUPPORTED_OPERATOR", "Ordered comparison not supported for type " + bt);
    }

    private static boolean compareOrderedStringNumeric(ValueCollection row, String col, String op, JsonNode valueNode)
            throws CachedTabularDecisionToolException {
        Byte bmode = stringNumericCoercion().get(col);
        byte mode = bmode == null ? CachedTabularNumericStringCoercion.MODE_SKIP : bmode.byteValue();
        if (mode == CachedTabularNumericStringCoercion.MODE_MIXED) {
            throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                    "Ordered comparison requires a uniformly numeric string column; column \"" + col
                            + "\" has mixed non-numeric values.");
        }
        if (mode != CachedTabularNumericStringCoercion.MODE_COERCE) {
            throw new CachedTabularDecisionToolException("UNSUPPORTED_OPERATOR",
                    "Ordered comparison not supported for string column \"" + col + "\" unless all non-null values parse as numbers.");
        }
        Double bound = jsonToDouble(valueNode);
        if (bound == null) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Numeric comparison requires a numeric value.");
        }
        String s = extractString(row, col, true);
        if (s == null || s.trim().isEmpty()) {
            return false;
        }
        double cell;
        try {
            cell = Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        int c = Double.compare(cell, bound);
        if ("lt".equals(op)) {
            return c < 0;
        }
        if ("lte".equals(op)) {
            return c <= 0;
        }
        if ("gt".equals(op)) {
            return c > 0;
        }
        if ("gte".equals(op)) {
            return c >= 0;
        }
        return false;
    }

    private static boolean compareEquality(ValueCollection row, String col, BaseTypes bt, String op, JsonNode valueNode,
            boolean caseSensitive, boolean trim) throws CachedTabularDecisionToolException {
        if (bt == BaseTypes.BOOLEAN) {
            Boolean cell = extractBoolean(row, col);
            Boolean bound = jsonToBoolean(valueNode);
            if (bound == null) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Boolean eq/ne requires a boolean value.");
            }
            if (cell == null) {
                return "ne".equals(op);
            }
            boolean eq = cell.equals(bound);
            return "ne".equals(op) ? !eq : eq;
        }
        if (isNumericType(bt)) {
            Double cell = extractDouble(row, col);
            Double bound = jsonToDouble(valueNode);
            if (bound == null) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Numeric eq/ne requires a numeric value.");
            }
            if (cell == null || cell.isNaN()) {
                return false;
            }
            boolean eq = Double.compare(cell, bound) == 0;
            return "ne".equals(op) ? !eq : eq;
        }
        if (bt == BaseTypes.DATETIME) {
            DateTime cell = extractDateTime(row, col);
            DateTime bound = jsonToDateTime(valueNode);
            if (bound == null) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Datetime eq/ne requires ISO-8601 value.");
            }
            if (cell == null) {
                return false;
            }
            boolean eq = cell.getMillis() == bound.getMillis();
            return "ne".equals(op) ? !eq : eq;
        }
        if (isStringLike(bt)) {
            Byte bmode = stringNumericCoercion().get(col);
            byte mode = bmode == null ? CachedTabularNumericStringCoercion.MODE_SKIP : bmode.byteValue();
            if (mode == CachedTabularNumericStringCoercion.MODE_MIXED && jsonLooksNumeric(valueNode)) {
                throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                        "Numeric equality on column \"" + col + "\" requires uniformly numeric string values.");
            }
            if (mode == CachedTabularNumericStringCoercion.MODE_COERCE && jsonLooksNumeric(valueNode)) {
                Double cellD = parseTrimmedStringCellToDouble(row, col, trim);
                Double boundD = jsonToDouble(valueNode);
                if (boundD == null) {
                    throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Numeric eq/ne requires a numeric value.");
                }
                if (cellD == null || cellD.isNaN()) {
                    return false;
                }
                boolean eq = Double.compare(cellD, boundD) == 0;
                return "ne".equals(op) ? !eq : eq;
            }
            String cell = extractString(row, col, trim);
            String bound = jsonToString(valueNode);
            if (bound == null) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "String eq/ne requires a string value.");
            }
            if (trim) {
                bound = bound.trim();
            }
            if (!caseSensitive && cell != null) {
                cell = cell.toLowerCase(Locale.ROOT);
                bound = bound.toLowerCase(Locale.ROOT);
            }
            boolean eq = cell != null && cell.equals(bound);
            return "ne".equals(op) ? !eq : eq;
        }
        throw new CachedTabularDecisionToolException("UNSUPPORTED_OPERATOR", "eq/ne not supported for type " + bt);
    }

    private static boolean between(ValueCollection row, String col, BaseTypes bt, JsonNode lowN, JsonNode highN,
            boolean incLo, boolean incHi) throws CachedTabularDecisionToolException {
        if (isNumericType(bt)) {
            Double cell = extractDouble(row, col);
            Double lo = jsonToDouble(lowN);
            Double hi = jsonToDouble(highN);
            if (lo == null || hi == null) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "between requires numeric low and high.");
            }
            if (cell == null || cell.isNaN()) {
                return false;
            }
            boolean ge = incLo ? cell >= lo : cell > lo;
            boolean le = incHi ? cell <= hi : cell < hi;
            return ge && le;
        }
        if (bt == BaseTypes.DATETIME) {
            DateTime cell = extractDateTime(row, col);
            DateTime lo = jsonToDateTime(lowN);
            DateTime hi = jsonToDateTime(highN);
            if (lo == null || hi == null) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "between requires ISO-8601 low and high.");
            }
            if (cell == null) {
                return false;
            }
            long t = cell.getMillis();
            long a = lo.getMillis();
            long b = hi.getMillis();
            boolean ge = incLo ? t >= a : t > a;
            boolean le = incHi ? t <= b : t < b;
            return ge && le;
        }
        if (isStringLike(bt)) {
            return betweenStringNumeric(row, col, bt, lowN, highN, incLo, incHi);
        }
        throw new CachedTabularDecisionToolException("UNSUPPORTED_OPERATOR", "between not supported for type " + bt);
    }

    private static boolean betweenStringNumeric(ValueCollection row, String col, BaseTypes bt, JsonNode lowN, JsonNode highN,
            boolean incLo, boolean incHi) throws CachedTabularDecisionToolException {
        Byte bmode = stringNumericCoercion().get(col);
        byte mode = bmode == null ? CachedTabularNumericStringCoercion.MODE_SKIP : bmode.byteValue();
        if (mode == CachedTabularNumericStringCoercion.MODE_MIXED) {
            throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                    "between on column \"" + col + "\" requires uniformly numeric string values.");
        }
        if (mode != CachedTabularNumericStringCoercion.MODE_COERCE) {
            throw new CachedTabularDecisionToolException("UNSUPPORTED_OPERATOR",
                    "between not supported for string column \"" + col + "\" unless all non-null values parse as numbers.");
        }
        Double lo = jsonToDouble(lowN);
        Double hi = jsonToDouble(highN);
        if (lo == null || hi == null) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "between requires numeric low and high.");
        }
        Double cell = parseTrimmedStringCellToDouble(row, col, true);
        if (cell == null || cell.isNaN()) {
            return false;
        }
        boolean ge = incLo ? cell >= lo : cell > lo;
        boolean le = incHi ? cell <= hi : cell < hi;
        return ge && le;
    }

    private static boolean inSet(ValueCollection row, String col, BaseTypes bt, JsonNode values, boolean caseSensitive,
            boolean trim) throws CachedTabularDecisionToolException {
        if (values == null || !values.isArray()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "in/not_in requires values array.");
        }
        if (bt == BaseTypes.BOOLEAN) {
            Boolean cell = extractBoolean(row, col);
            if (cell == null) {
                return false;
            }
            for (JsonNode v : values) {
                Boolean b = jsonToBoolean(v);
                if (b != null && b.equals(cell)) {
                    return true;
                }
            }
            return false;
        }
        if (isNumericType(bt)) {
            Double cell = extractDouble(row, col);
            if (cell == null || cell.isNaN()) {
                return false;
            }
            for (JsonNode v : values) {
                Double d = jsonToDouble(v);
                if (d != null && Double.compare(cell, d) == 0) {
                    return true;
                }
            }
            return false;
        }
        if (isStringLike(bt)) {
            Byte bmode = stringNumericCoercion().get(col);
            byte mode = bmode == null ? CachedTabularNumericStringCoercion.MODE_SKIP : bmode.byteValue();
            if (mode == CachedTabularNumericStringCoercion.MODE_MIXED && valuesAllNumericLike(values)) {
                throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                        "Numeric in/not_in on column \"" + col + "\" requires uniformly numeric string values.");
            }
            if (mode == CachedTabularNumericStringCoercion.MODE_COERCE && valuesAllNumericLike(values)) {
                Double cell = parseTrimmedStringCellToDouble(row, col, trim);
                if (cell == null || cell.isNaN()) {
                    return false;
                }
                for (JsonNode v : values) {
                    Double d = jsonToDouble(v);
                    if (d != null && Double.compare(cell, d) == 0) {
                        return true;
                    }
                }
                return false;
            }
            String cell = extractString(row, col, trim);
            if (cell == null) {
                return false;
            }
            String cellCmp = !caseSensitive ? cell.toLowerCase(Locale.ROOT) : cell;
            for (JsonNode v : values) {
                String s = jsonToString(v);
                if (s == null) {
                    continue;
                }
                if (trim) {
                    s = s.trim();
                }
                if (!caseSensitive) {
                    s = s.toLowerCase(Locale.ROOT);
                }
                if (cellCmp.equals(s)) {
                    return true;
                }
            }
            return false;
        }
        throw new CachedTabularDecisionToolException("UNSUPPORTED_OPERATOR", "in/not_in not supported for type " + bt);
    }

    private static boolean stringPattern(ValueCollection row, String col, BaseTypes bt, String op, String pattern,
            boolean caseSensitive, boolean trim) throws CachedTabularDecisionToolException {
        if (!isStringLike(bt)) {
            throw new CachedTabularDecisionToolException("TYPE_MISMATCH", "String pattern operators require a string column.");
        }
        if (pattern == null) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Pattern value is required.");
        }
        String cell = extractString(row, col, trim);
        if (cell == null) {
            cell = "";
        }
        String c = caseSensitive ? cell : cell.toLowerCase(Locale.ROOT);
        String p = caseSensitive ? pattern : pattern.toLowerCase(Locale.ROOT);
        boolean positive;
        if ("contains".equals(op)) {
            positive = c.contains(p);
        } else if ("not_contains".equals(op)) {
            positive = !c.contains(p);
        } else if ("starts_with".equals(op)) {
            positive = c.startsWith(p);
        } else if ("not_starts_with".equals(op)) {
            positive = !c.startsWith(p);
        } else if ("ends_with".equals(op)) {
            positive = c.endsWith(p);
        } else if ("not_ends_with".equals(op)) {
            positive = !c.endsWith(p);
        } else if ("like".equals(op)) {
            positive = sqlLikeMatch(c, p);
        } else if ("not_like".equals(op)) {
            positive = !sqlLikeMatch(c, p);
        } else {
            throw new CachedTabularDecisionToolException("UNSUPPORTED_OPERATOR", "Unknown string op " + op);
        }
        return positive;
    }

    /**
     * SQL-like: {@code %} any run, {@code _} one char, backslash escapes the next char. Bounded by
     * {@link #MAX_LIKE_PATTERN_LENGTH}, {@link #MAX_LIKE_CELL_CHARS}, and {@link #MAX_LIKE_STEPS}.
     *
     * <p>Implementation: tokenize the pattern (honouring escapes), then run an iterative DP over
     * {@code (textIndex, tokenIndex)} — no recursion, stack depth is O(1).</p>
     */
    static boolean sqlLikeMatch(String text, String pattern) throws CachedTabularDecisionToolException {
        if (pattern.length() > MAX_LIKE_PATTERN_LENGTH) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                    "like pattern exceeds max length " + MAX_LIKE_PATTERN_LENGTH + ".");
        }
        if (text.length() > MAX_LIKE_CELL_CHARS) {
            throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                    "like: cell text exceeds max length " + MAX_LIKE_CELL_CHARS + " for safe evaluation.");
        }
        java.util.List<SqlLikeToken> tokens = tokenizeSqlLikePattern(pattern);
        int m = text.length();
        boolean[] cur = new boolean[m + 1];
        cur[0] = true;
        int steps = 0;
        for (SqlLikeToken tok : tokens) {
            boolean[] next = new boolean[m + 1];
            switch (tok.kind) {
                case LITERAL: {
                    String lit = tok.literal;
                    int l = lit.length();
                    for (int i = 0; i + l <= m; i++) {
                        if (steps++ > MAX_LIKE_STEPS) {
                            throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                                    "like evaluation exceeded step budget.");
                        }
                        if (cur[i] && text.regionMatches(i, lit, 0, l)) {
                            next[i + l] = true;
                        }
                    }
                    break;
                }
                case UNDERSCORE:
                    for (int i = 0; i < m; i++) {
                        if (steps++ > MAX_LIKE_STEPS) {
                            throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                                    "like evaluation exceeded step budget.");
                        }
                        if (cur[i]) {
                            next[i + 1] = true;
                        }
                    }
                    break;
                case PERCENT: {
                    boolean prefix = false;
                    for (int k = 0; k <= m; k++) {
                        if (steps++ > MAX_LIKE_STEPS) {
                            throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                                    "like evaluation exceeded step budget.");
                        }
                        prefix = prefix || cur[k];
                        next[k] = prefix;
                    }
                    break;
                }
                default:
                    break;
            }
            cur = next;
        }
        return cur[m];
    }

    private enum SqlLikeKind {
        LITERAL,
        UNDERSCORE,
        PERCENT
    }

    private static final class SqlLikeToken {
        final SqlLikeKind kind;
        final String literal;

        SqlLikeToken(SqlLikeKind kind, String literal) {
            this.kind = kind;
            this.literal = literal == null ? "" : literal;
        }
    }

    private static java.util.List<SqlLikeToken> tokenizeSqlLikePattern(String pattern) {
        java.util.ArrayList<SqlLikeToken> out = new java.util.ArrayList<>();
        StringBuilder lit = new StringBuilder();
        Runnable flush = () -> {
            if (lit.length() > 0) {
                out.add(new SqlLikeToken(SqlLikeKind.LITERAL, lit.toString()));
                lit.setLength(0);
            }
        };
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                if (i + 1 >= pattern.length()) {
                    flush.run();
                    out.add(new SqlLikeToken(SqlLikeKind.LITERAL, "\\"));
                    break;
                }
                lit.append(pattern.charAt(++i));
                continue;
            }
            if (c == '%') {
                flush.run();
                if (out.isEmpty() || out.get(out.size() - 1).kind != SqlLikeKind.PERCENT) {
                    out.add(new SqlLikeToken(SqlLikeKind.PERCENT, ""));
                }
                continue;
            }
            if (c == '_') {
                flush.run();
                out.add(new SqlLikeToken(SqlLikeKind.UNDERSCORE, ""));
                continue;
            }
            lit.append(c);
        }
        flush.run();
        return out;
    }

    private static boolean jsonLooksNumeric(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull()) {
            return false;
        }
        if (valueNode.isNumber()) {
            return true;
        }
        if (valueNode.isTextual()) {
            try {
                Double.parseDouble(valueNode.asText().trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean valuesAllNumericLike(JsonNode values) {
        if (values == null || !values.isArray() || values.size() < 1) {
            return false;
        }
        for (JsonNode v : values) {
            if (!jsonLooksNumeric(v)) {
                return false;
            }
        }
        return true;
    }

    private static Double parseTrimmedStringCellToDouble(ValueCollection row, String col, boolean trim) {
        String s = extractString(row, col, trim);
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isStringLike(BaseTypes bt) {
        return bt == BaseTypes.STRING || bt == BaseTypes.TEXT || bt == BaseTypes.HTML || bt == BaseTypes.XML
                || bt == BaseTypes.GUID;
    }

    private static boolean isNumericType(BaseTypes bt) {
        return bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG;
    }

    /**
     * JSON / INFOTABLE / tags / vectors / blob / etc. — invalid for predicates and {@code group_metric} group keys.
     */
    public static boolean isUnsupportedComplexBaseType(BaseTypes bt) {
        if (bt == null) {
            return false;
        }
        return bt == BaseTypes.INFOTABLE || bt == BaseTypes.JSON || bt == BaseTypes.TAGS || bt == BaseTypes.IMAGE
                || bt == BaseTypes.HYPERLINK || bt == BaseTypes.LOCATION || bt == BaseTypes.VEC2 || bt == BaseTypes.VEC3
                || bt == BaseTypes.VEC4 || bt == BaseTypes.BLOB || bt == BaseTypes.NOTHING;
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
            // fall through
        }
        if (names.isEmpty() && it.getRowCount() > 0) {
            ValueCollection row = it.getRow(0);
            if (row != null) {
                try {
                    names.addAll(row.keySet());
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return names;
    }

    public static BaseTypes columnBaseType(DataShapeDefinition shape, InfoTable it, String col) {
        try {
            if (shape != null && shape.getFields() != null) {
                for (FieldDefinition f : shape.getFields().values()) {
                    if (col.equals(f.getName())) {
                        return f.getBaseType();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /**
     * {@code docs/agent/query-spec.md} §3.9 — string-family columns for {@code ISEMPTY} / {@code NOTEMPTY}
     * (plain string-like category 3 + rich-string category 4).
     */
    public static boolean isStringFamilyForIsempty(BaseTypes bt) {
        if (bt == null) {
            return false;
        }
        if (bt == BaseTypes.STRING || bt == BaseTypes.TEXT || bt == BaseTypes.GUID || bt == BaseTypes.HYPERLINK
                || bt == BaseTypes.IMAGELINK || bt == BaseTypes.HTML || bt == BaseTypes.XML) {
            return true;
        }
        return bt.name().endsWith("NAME");
    }

    /** Columns that accept Parler literal substring extension filters ({@code CONTAINS} family). */
    public static boolean isPlainOrRichStringColumnForSubstringFilters(BaseTypes bt) {
        return isStringFamilyForIsempty(bt);
    }

    public static boolean evaluateIfilter(IFilter filter, ValueCollection row) {
        ValueCollection effective = applyNumericStringCoercionToRow(row, stringNumericCoercion());
        return filter.evaluateFilter(effective);
    }

    /**
     * ThingWorx {@link IFilter} evaluation uses column base types; for STRING columns whose cells are uniformly
     * numeric (§8 / {@link CachedTabularNumericStringCoercion}), substitute {@link NumberPrimitive} cells so
     * ordered comparisons match numeric semantics.
     */
    private static ValueCollection applyNumericStringCoercionToRow(ValueCollection row, Map<String, Byte> coercion) {
        if (row == null || coercion == null || coercion.isEmpty()) {
            return row;
        }
        boolean anyCoerce = false;
        for (Byte b : coercion.values()) {
            if (b != null && b.byteValue() == CachedTabularNumericStringCoercion.MODE_COERCE) {
                anyCoerce = true;
                break;
            }
        }
        if (!anyCoerce) {
            return row;
        }
        ValueCollection out = row.clone();
        for (Map.Entry<String, Byte> e : coercion.entrySet()) {
            if (e.getValue() == null || e.getValue().byteValue() != CachedTabularNumericStringCoercion.MODE_COERCE) {
                continue;
            }
            String col = e.getKey();
            IPrimitiveType v = out.get(col);
            if (!(v instanceof StringPrimitive)) {
                continue;
            }
            try {
                String s = ((StringPrimitive) v).getValue();
                if (s == null) {
                    continue;
                }
                s = s.trim();
                if (s.isEmpty()) {
                    continue;
                }
                out.put(col, new NumberPrimitive(Double.parseDouble(s)));
            } catch (Exception ignored) {
                // leave cell as declared type
            }
        }
        return out;
    }

    private static String primitiveToString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof IPrimitiveType) {
            try {
                Object inner = ((IPrimitiveType) v).getValue();
                return inner == null ? null : String.valueOf(inner);
            } catch (Exception e) {
                return v.toString();
            }
        }
        return String.valueOf(v);
    }

    private static String extractString(ValueCollection row, String col, boolean trim) {
        if (row == null) {
            return null;
        }
        Object v = row.getValue(col);
        if (v == null) {
            return null;
        }
        String s = primitiveToString(v);
        if (s == null) {
            return null;
        }
        return trim ? s.trim() : s;
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
        if (v instanceof IPrimitiveType) {
            try {
                if (v instanceof NumberPrimitive) {
                    return ((NumberPrimitive) v).getValue();
                }
                if (v instanceof IntegerPrimitive) {
                    return (double) ((IntegerPrimitive) v).getValue();
                }
                if (v instanceof LongPrimitive) {
                    return (double) ((LongPrimitive) v).getValue();
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
        if (v instanceof DatetimePrimitive) {
            try {
                return ((DatetimePrimitive) v).getValue();
            } catch (Exception ignored) {
                return null;
            }
        }
        if (v instanceof DateTime) {
            return (DateTime) v;
        }
        if (v instanceof IPrimitiveType) {
            try {
                Object inner = ((IPrimitiveType) v).getValue();
                if (inner instanceof DateTime) {
                    return (DateTime) inner;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return null;
    }

    private static Boolean extractBoolean(ValueCollection row, String col) {
        if (row == null) {
            return null;
        }
        Object v = row.getValue(col);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
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
        return null;
    }

    private static Double jsonToDouble(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        if (n.isTextual()) {
            try {
                return Double.parseDouble(n.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static DateTime jsonToDateTime(JsonNode n) {
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        try {
            return DateTime.parse(n.asText().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean jsonToBoolean(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isTextual()) {
            String s = n.asText().trim();
            if ("true".equalsIgnoreCase(s)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(s)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static String jsonToString(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isNumber() || n.isBoolean()) {
            return n.asText();
        }
        return null;
    }
}
