package com.thingworx.things.agent.tools;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.ParlerProtectionAudit;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Built-in {@code analyze_entity_set} — deterministic set algebra over two conversation-cached entity/list tables.
 * Supports {@code difference}, {@code intersection}, {@code union}, and {@code symmetric_difference}. See {@code docs/agent/entity-set-analysis.md}.
 */
public final class AnalyzeEntitySetExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(AnalyzeEntitySetExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** INLINE vs LARGE split — aligned with {@link InvokeServiceExecutor#LARGE_TABLE_ROW_THRESHOLD}. */
    static final int INLINE_ROW_THRESHOLD = InvokeServiceExecutor.largeTableRowThreshold();

    private static final Set<String> ALLOWED_OPERAND_KEYS = keys("cacheId", "keyColumn", "label");
    /** Root keys aligned with {@link AnalyzeEntitySetToolSchema#parametersSchema()} — reject any other top-level key. */
    private static final Set<String> ALLOWED_ROOT_KEYS = keys("operation", "left", "right", "projectColumns", "maxItems", "offset");

    /** For drift tests — must match {@link AnalyzeEntitySetToolSchema#parametersSchema()} property names. */
    static Set<String> rootKeysForDriftTest() {
        return Collections.unmodifiableSet(new HashSet<>(ALLOWED_ROOT_KEYS));
    }

    private AnalyzeEntitySetExecutor() {}

    private static Set<String> keys(String... ks) {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, ks);
        return s;
    }

    /**
     * @return error detail for {@link #errorJson}, or {@code null} if only documented root keys are present
     */
    private static String validateOnlyAllowedRootKeys(JsonNode root) {
        java.util.Iterator<String> fn = root.fieldNames();
        while (fn.hasNext()) {
            String k = fn.next();
            if (!ALLOWED_ROOT_KEYS.contains(k)) {
                return "Unexpected root key \"" + k + "\". Allowed keys are: operation, left, right, projectColumns, maxItems, offset.";
            }
        }
        return null;
    }

    public static String execute(ToolCall call) {
        try {
            return doExecute(call);
        } catch (ArtifactCacheException e) {
            if (e.code() == ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE) {
                throw e;
            }
            return errorJson(e.code().name(), e.getMessage());
        } catch (IllegalArgumentException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("maxItems must") || m.contains("offset must")) {
                return errorJson("LIMIT_OUT_OF_RANGE", m);
            }
            LOG.warn("analyze_entity_set: {}", m);
            return errorJson("INVALID_PARAMETERS", m);
        } catch (Exception e) {
            LOG.warn("analyze_entity_set failed: {}", e.getMessage(), e);
            return errorJson("ENTITY_SET_ERROR", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private static String doExecute(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null || call.getArguments().isBlank() ? "{}" : call.getArguments());
        if (!root.isObject()) {
            return errorJson("INVALID_PARAMETERS", "Tool arguments must be a JSON object.");
        }
        String rootKeyErr = validateOnlyAllowedRootKeys(root);
        if (rootKeyErr != null) {
            return errorJson("INVALID_PARAMETERS", rootKeyErr);
        }
        String op = text(root, "operation");
        if (op == null || op.isEmpty()) {
            return errorJson("INVALID_PARAMETERS", "operation is required.");
        }
        op = op.trim().toLowerCase(Locale.ROOT);
        // B18: acceptance set is AnalyzeEntitySetToolSchema.OPERATIONS (schema enum ↔ executor).
        if (!AnalyzeEntitySetToolSchema.isSupportedOperation(op)) {
            return errorJson("UNSUPPORTED_OPERATION",
                    "Unsupported operation \"" + op + "\". Supported: "
                            + String.join(", ", AnalyzeEntitySetToolSchema.OPERATIONS) + ".");
        }
        boolean mergeOp = "union".equals(op) || "symmetric_difference".equals(op);
        JsonNode leftN = root.get("left");
        JsonNode rightN = root.get("right");
        if (leftN == null || !leftN.isObject()) {
            return errorJson("INVALID_PARAMETERS", "left must be an object with cacheId.");
        }
        if (rightN == null || !rightN.isObject()) {
            return errorJson("INVALID_PARAMETERS", "right must be an object with cacheId.");
        }
        Operand leftOp = Operand.parse(leftN, "left");
        Operand rightOp = Operand.parse(rightN, "right");

        InfoTable leftTable = InvokeServiceExecutor.lookupCachedInfotable(leftOp.cacheId);
        if (leftTable == null) {
            return errorJson("CACHE_MISS", "No cached result for left.cacheId in the current conversation (or expired).");
        }
        InfoTable rightTable = InvokeServiceExecutor.lookupCachedInfotable(rightOp.cacheId);
        if (rightTable == null) {
            return errorJson("CACHE_MISS", "No cached result for right.cacheId in the current conversation (or expired).");
        }
        String sizeErr = checkOperandSize(leftTable, "left");
        if (sizeErr != null) {
            return errorJson("TABLE_TOO_LARGE_FOR_TRANSFORM", sizeErr);
        }
        sizeErr = checkOperandSize(rightTable, "right");
        if (sizeErr != null) {
            return errorJson("TABLE_TOO_LARGE_FOR_TRANSFORM", sizeErr);
        }

        DataShapeDefinition leftShape = shapeOf(leftTable);
        DataShapeDefinition rightShape = shapeOf(rightTable);
        if (!columnExists(leftShape, leftOp.keyColumn)) {
            return errorJson("INVALID_PARAMETERS", "left keyColumn \"" + leftOp.keyColumn + "\" is not a column on the left cached table.");
        }
        if (!columnExists(rightShape, rightOp.keyColumn)) {
            return errorJson("INVALID_PARAMETERS", "right keyColumn \"" + rightOp.keyColumn + "\" is not a column on the right cached table.");
        }
        Violation pv = passwordViolation(leftShape, leftOp.keyColumn, "left.keyColumn");
        if (pv != null) {
            return protectedBlocked(pv);
        }
        pv = passwordViolation(rightShape, rightOp.keyColumn, "right.keyColumn");
        if (pv != null) {
            return protectedBlocked(pv);
        }
        BaseTypes leftKeyBt = columnBaseType(leftTable, leftOp.keyColumn);
        BaseTypes rightKeyBt = columnBaseType(rightTable, rightOp.keyColumn);
        if (!isSupportedEntitySetKeyBaseType(leftKeyBt)) {
            return errorJson("INVALID_PARAMETERS", "left keyColumn \"" + leftOp.keyColumn
                    + "\" must be STRING, THINGNAME, GUID, INTEGER, LONG, NUMBER, BOOLEAN, or DATETIME for entity-set keys.");
        }
        if (!isSupportedEntitySetKeyBaseType(rightKeyBt)) {
            return errorJson("INVALID_PARAMETERS", "right keyColumn \"" + rightOp.keyColumn
                    + "\" must be STRING, THINGNAME, GUID, INTEGER, LONG, NUMBER, BOOLEAN, or DATETIME for entity-set keys.");
        }

        List<String> projectColumns;
        if (mergeOp) {
            try {
                projectColumns = resolveProjectColumnsForMerge(root, leftTable, leftShape, rightTable, rightShape, leftOp.keyColumn, rightOp.keyColumn);
            } catch (IllegalArgumentException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                return errorJson("INVALID_PARAMETERS", m);
            }
            for (String pc : projectColumns) {
                if (!columnExists(leftShape, pc) && !columnExists(rightShape, pc)) {
                    return errorJson("INVALID_PARAMETERS", "projectColumns references unknown column \"" + pc + "\" (not on left or right cached table).");
                }
                if (columnExists(leftShape, pc)) {
                    pv = passwordViolation(leftShape, pc, "projectColumns");
                    if (pv != null) {
                        return protectedBlocked(pv);
                    }
                }
                if (columnExists(rightShape, pc)) {
                    pv = passwordViolation(rightShape, pc, "projectColumns");
                    if (pv != null) {
                        return protectedBlocked(pv);
                    }
                }
            }
        } else {
            projectColumns = resolveProjectColumns(root, leftTable, leftShape);
            for (String pc : projectColumns) {
                if (!columnExists(leftShape, pc)) {
                    return errorJson("INVALID_PARAMETERS", "projectColumns references unknown left column \"" + pc + "\".");
                }
                pv = passwordViolation(leftShape, pc, "projectColumns");
                if (pv != null) {
                    return protectedBlocked(pv);
                }
            }
        }

        int[] paging = CachedTabularTabulatePaging.parseMaxItemsAndOffset(root, CachedTabularTabulatePaging.SORT_TOPN_DEFAULT_LIMIT);

        DifferenceBuild diff;
        if ("difference".equals(op)) {
            diff = buildDifference(leftTable, rightTable, leftOp, rightOp, projectColumns, leftKeyBt, rightKeyBt);
        } else if ("intersection".equals(op)) {
            diff = buildIntersection(leftTable, rightTable, leftOp, rightOp, projectColumns, leftKeyBt, rightKeyBt);
        } else if ("union".equals(op)) {
            diff = buildUnion(leftTable, rightTable, leftOp, rightOp, projectColumns, leftKeyBt, rightKeyBt);
        } else {
            diff = buildSymmetricDifference(leftTable, rightTable, leftOp, rightOp, projectColumns, leftKeyBt, rightKeyBt);
        }
        if (diff.errorCode != null) {
            return errorJson(diff.errorCode, diff.errorMessage);
        }

        InfoTable out = diff.outTable;
        com.thingworx.things.agent.source.SourceDescriptor leftDesc =
                InvokeServiceExecutor.lookupSourceDescriptor(leftOp.cacheId);
        com.thingworx.things.agent.source.SourceDescriptor derived =
                com.thingworx.things.agent.source.SourceDescriptorSupport.forDerivedStore(
                        leftDesc, leftOp.cacheId, out, "analyze_entity_set");
        if (rightOp != null && rightOp.cacheId != null && !rightOp.cacheId.isBlank()) {
            derived = com.thingworx.things.agent.source.SourceDescriptorSupport.appendParent(
                    derived, rightOp.cacheId);
        }
        String newCacheId = InvokeServiceExecutor.storeInfotableInConversationCache(out, derived);
        TabularCacheHandleMirror.recordQualifyingCacheId(newCacheId);

        return formatSuccess(op, leftOp, rightOp, leftTable, rightTable, diff, newCacheId, projectColumns, paging[0], paging[1]);
    }

    private static String checkOperandSize(InfoTable t, String side) {
        int rows = t.getRowCount();
        if (rows > CachedTabularToolsExecutor.MAX_ROWS_FOR_TABULAR_TRANSFORM) {
            return side + " cached table exceeds max rows for transform (" + CachedTabularToolsExecutor.MAX_ROWS_FOR_TABULAR_TRANSFORM + ").";
        }
        return null;
    }

    private static List<String> resolveProjectColumns(JsonNode root, InfoTable left, DataShapeDefinition leftShape) {
        if (root.has("projectColumns")) {
            JsonNode arr = root.get("projectColumns");
            if (arr == null || !arr.isArray() || arr.size() == 0) {
                throw new IllegalArgumentException("When projectColumns is present it must be a non-empty JSON array of column names.");
            }
            List<String> out = new ArrayList<>();
            for (JsonNode el : arr) {
                if (el == null || !el.isTextual()) {
                    throw new IllegalArgumentException("projectColumns must be an array of strings.");
                }
                String c = el.asText().trim();
                if (c.isEmpty()) {
                    throw new IllegalArgumentException("projectColumns contains an empty string.");
                }
                out.add(c);
            }
            return out;
        }
        return defaultScalarColumns(left, leftShape);
    }

    private static List<String> resolveProjectColumnsForMerge(JsonNode root, InfoTable left, DataShapeDefinition leftShape,
            InfoTable right, DataShapeDefinition rightShape, String leftKeyCol, String rightKeyCol) {
        if (root.has("projectColumns")) {
            JsonNode arr = root.get("projectColumns");
            if (arr == null || !arr.isArray() || arr.size() == 0) {
                throw new IllegalArgumentException("When projectColumns is present it must be a non-empty JSON array of column names.");
            }
            List<String> out = new ArrayList<>();
            for (JsonNode el : arr) {
                if (el == null || !el.isTextual()) {
                    throw new IllegalArgumentException("projectColumns must be an array of strings.");
                }
                String c = el.asText().trim();
                if (c.isEmpty()) {
                    throw new IllegalArgumentException("projectColumns contains an empty string.");
                }
                out.add(c);
            }
            return out;
        }
        return defaultMergeProjectColumns(left, leftShape, right, rightShape, leftKeyCol, rightKeyCol);
    }

    /**
     * Default projection for union / symmetric_difference: scalar column names present on both operands (left declaration order),
     * with {@code leftKeyCol} listed first when it appears in the intersection.
     */
    private static List<String> defaultMergeProjectColumns(InfoTable left, DataShapeDefinition leftShape, InfoTable right,
            DataShapeDefinition rightShape, String leftKeyCol, String rightKeyCol) {
        Set<String> rightScalars = new LinkedHashSet<>(defaultScalarColumns(right, rightShape));
        List<String> out = new ArrayList<>();
        for (String c : defaultScalarColumns(left, leftShape)) {
            if (rightScalars.contains(c)) {
                out.add(c);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(
                    "No overlapping scalar columns between operands for default projection; supply explicit projectColumns.");
        }
        if (out.contains(leftKeyCol)) {
            out.remove(leftKeyCol);
            out.add(0, leftKeyCol);
        } else if (!leftKeyCol.equals(rightKeyCol) && out.contains(rightKeyCol)) {
            out.remove(rightKeyCol);
            out.add(0, rightKeyCol);
        }
        return out;
    }

    /** Default projection: declared-order scalar columns on the left operand (excludes PASSWORD and non-projectable types). */
    private static List<String> defaultScalarColumns(InfoTable left, DataShapeDefinition ds) {
        List<String> names = columnNames(left);
        List<String> out = new ArrayList<>();
        for (String n : names) {
            BaseTypes bt = columnBaseType(left, n);
            if (bt == null || bt == BaseTypes.PASSWORD) {
                continue;
            }
            if (!isProjectableScalar(bt)) {
                continue;
            }
            out.add(n);
        }
        return out;
    }

    private static boolean isProjectableScalar(BaseTypes bt) {
        if (bt == null) {
            return false;
        }
        switch (bt) {
            case STRING:
            case NUMBER:
            case INTEGER:
            case LONG:
            case BOOLEAN:
            case DATETIME:
            case THINGNAME:
            case GUID:
                return true;
            default:
                return false;
        }
    }

    private static final class Operand {
        final String cacheId;
        final String keyColumn;
        final String label;

        Operand(String cacheId, String keyColumn, String label) {
            this.cacheId = cacheId;
            this.keyColumn = keyColumn;
            this.label = label;
        }

        static Operand parse(JsonNode obj, String sideLabel) {
            java.util.Iterator<String> fn = obj.fieldNames();
            while (fn.hasNext()) {
                String k = fn.next();
                if (!ALLOWED_OPERAND_KEYS.contains(k)) {
                    throw new IllegalArgumentException(sideLabel + " must contain only cacheId, optional keyColumn, and optional label (found unexpected key \"" + k + "\").");
                }
            }
            String cid = text(obj, "cacheId");
            if (cid == null || cid.isEmpty()) {
                throw new IllegalArgumentException(sideLabel + ".cacheId is required.");
            }
            if (CachedTabularLastCacheHandle.isToken(cid)) {
                throw new IllegalArgumentException(sideLabel + ".cacheId must be an explicit cache id from a prior tool; "
                        + "the last-tabular sentinel is not accepted for analyze_entity_set operands.");
            }
            String keyCol = text(obj, "keyColumn");
            if (keyCol == null || keyCol.isEmpty()) {
                keyCol = "name";
            }
            String label = text(obj, "label");
            return new Operand(cid.trim(), keyCol.trim(), label == null ? "" : label.trim());
        }
    }

    private static final class DifferenceBuild {
        InfoTable outTable;
        int leftRowCount;
        int rightRowCount;
        int leftUniqueKeys;
        int rightUniqueKeys;
        int leftDupRows;
        int rightDupRows;
        String errorCode;
        String errorMessage;
    }

    private static boolean isSupportedEntitySetKeyBaseType(BaseTypes bt) {
        if (bt == null) {
            return false;
        }
        switch (bt) {
            case STRING:
            case THINGNAME:
            case GUID:
            case INTEGER:
            case LONG:
            case NUMBER:
            case BOOLEAN:
            case DATETIME:
                return true;
            default:
                return false;
        }
    }

    private static DifferenceBuild buildDifference(InfoTable left, InfoTable right, Operand lo, Operand ro, List<String> projectColumns,
            BaseTypes leftKeyBt, BaseTypes rightKeyBt) throws Exception {
        DifferenceBuild b = new DifferenceBuild();
        b.leftRowCount = left.getRowCount();
        b.rightRowCount = right.getRowCount();

        Map<String, Integer> firstLeftIndex = new LinkedHashMap<>();
        Map<String, List<ValueCollection>> leftRowsByKey = new LinkedHashMap<>();
        for (int i = 0; i < left.getRowCount(); i++) {
            ValueCollection row = left.getRow(i);
            KeyParse kp = keyFromRow(row, lo.keyColumn, leftKeyBt);
            if (kp.error != null) {
                b.errorCode = "ENTITY_SET_KEY_UNUSABLE";
                b.errorMessage = "left row " + i + ": " + kp.error;
                return b;
            }
            String k = kp.key;
            firstLeftIndex.putIfAbsent(k, i);
            leftRowsByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(row);
        }
        b.leftUniqueKeys = firstLeftIndex.size();
        b.leftDupRows = left.getRowCount() - b.leftUniqueKeys;

        Map<String, List<ValueCollection>> rightRowsByKey = new LinkedHashMap<>();
        for (int i = 0; i < right.getRowCount(); i++) {
            ValueCollection row = right.getRow(i);
            KeyParse kp = keyFromRow(row, ro.keyColumn, rightKeyBt);
            if (kp.error != null) {
                b.errorCode = "ENTITY_SET_KEY_UNUSABLE";
                b.errorMessage = "right row " + i + ": " + kp.error;
                return b;
            }
            String k = kp.key;
            rightRowsByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(row);
        }
        b.rightUniqueKeys = rightRowsByKey.size();
        b.rightDupRows = right.getRowCount() - b.rightUniqueKeys;

        for (Map.Entry<String, List<ValueCollection>> e : leftRowsByKey.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            ValueCollection first = e.getValue().get(0);
            for (int j = 1; j < e.getValue().size(); j++) {
                if (!rowsAgreeOnProjectedColumns(first, e.getValue().get(j), projectColumns)) {
                    b.errorCode = "ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS";
                    b.errorMessage = "Duplicate key \"" + e.getKey() + "\" on the left operand has conflicting values in projected columns.";
                    return b;
                }
            }
        }

        DataShapeDefinition outShape = buildOutputShape(left, projectColumns);
        InfoTable out = new InfoTable(outShape);
        List<String> diffKeysOrdered = new ArrayList<>();
        for (String k : firstLeftIndex.keySet()) {
            if (!rightRowsByKey.containsKey(k)) {
                diffKeysOrdered.add(k);
            }
        }
        for (String k : diffKeysOrdered) {
            int idx = firstLeftIndex.get(k);
            ValueCollection srcRow = left.getRow(idx);
            ValueCollection dst = new ValueCollection();
            for (String col : projectColumns) {
                Object val = srcRow.getValue(col);
                if (val instanceof IPrimitiveType) {
                    dst.put(col, (IPrimitiveType) val);
                } else if (val == null) {
                    // omit null cell — downstream rowToObject treats missing as null
                } else if (val instanceof String || val instanceof Number || val instanceof Boolean) {
                    // Defer to ValueCollection coercion — wrap as StringPrimitive for STRING-ish cells when needed
                    if (val instanceof String) {
                        dst.put(col, new StringPrimitive((String) val));
                    } else if (val instanceof Number) {
                        dst.put(col, new NumberPrimitive(((Number) val).doubleValue()));
                    } else {
                        dst.put(col, new BooleanPrimitive((Boolean) val));
                    }
                } else {
                    // Projectable scalar columns should be primitive-backed; fail closed if not.
                    b.errorCode = "ENTITY_SET_ERROR";
                    b.errorMessage = "Non-primitive cell in projected column \"" + col + "\".";
                    return b;
                }
            }
            out.addRow(dst);
        }
        b.outTable = out;
        return b;
    }

    private static DifferenceBuild buildIntersection(InfoTable left, InfoTable right, Operand lo, Operand ro, List<String> projectColumns,
            BaseTypes leftKeyBt, BaseTypes rightKeyBt) throws Exception {
        DifferenceBuild b = new DifferenceBuild();
        b.leftRowCount = left.getRowCount();
        b.rightRowCount = right.getRowCount();

        Map<String, Integer> firstLeftIndex = new LinkedHashMap<>();
        Map<String, List<ValueCollection>> leftRowsByKey = new LinkedHashMap<>();
        for (int i = 0; i < left.getRowCount(); i++) {
            ValueCollection row = left.getRow(i);
            KeyParse kp = keyFromRow(row, lo.keyColumn, leftKeyBt);
            if (kp.error != null) {
                b.errorCode = "ENTITY_SET_KEY_UNUSABLE";
                b.errorMessage = "left row " + i + ": " + kp.error;
                return b;
            }
            String k = kp.key;
            firstLeftIndex.putIfAbsent(k, i);
            leftRowsByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(row);
        }
        b.leftUniqueKeys = firstLeftIndex.size();
        b.leftDupRows = left.getRowCount() - b.leftUniqueKeys;

        Map<String, List<ValueCollection>> rightRowsByKey = new LinkedHashMap<>();
        for (int i = 0; i < right.getRowCount(); i++) {
            ValueCollection row = right.getRow(i);
            KeyParse kp = keyFromRow(row, ro.keyColumn, rightKeyBt);
            if (kp.error != null) {
                b.errorCode = "ENTITY_SET_KEY_UNUSABLE";
                b.errorMessage = "right row " + i + ": " + kp.error;
                return b;
            }
            String k = kp.key;
            rightRowsByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(row);
        }
        b.rightUniqueKeys = rightRowsByKey.size();
        b.rightDupRows = right.getRowCount() - b.rightUniqueKeys;

        for (Map.Entry<String, List<ValueCollection>> e : leftRowsByKey.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            ValueCollection first = e.getValue().get(0);
            for (int j = 1; j < e.getValue().size(); j++) {
                if (!rowsAgreeOnProjectedColumns(first, e.getValue().get(j), projectColumns)) {
                    b.errorCode = "ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS";
                    b.errorMessage = "Duplicate key \"" + e.getKey() + "\" on the left operand has conflicting values in projected columns.";
                    return b;
                }
            }
        }

        DataShapeDefinition outShape = buildOutputShape(left, projectColumns);
        InfoTable out = new InfoTable(outShape);
        List<String> interKeysOrdered = new ArrayList<>();
        for (String k : firstLeftIndex.keySet()) {
            if (rightRowsByKey.containsKey(k)) {
                interKeysOrdered.add(k);
            }
        }
        for (String k : interKeysOrdered) {
            int idx = firstLeftIndex.get(k);
            ValueCollection srcRow = left.getRow(idx);
            ValueCollection dst = new ValueCollection();
            for (String col : projectColumns) {
                Object val = srcRow.getValue(col);
                if (val instanceof IPrimitiveType) {
                    dst.put(col, (IPrimitiveType) val);
                } else if (val == null) {
                    // omit null cell
                } else if (val instanceof String || val instanceof Number || val instanceof Boolean) {
                    if (val instanceof String) {
                        dst.put(col, new StringPrimitive((String) val));
                    } else if (val instanceof Number) {
                        dst.put(col, new NumberPrimitive(((Number) val).doubleValue()));
                    } else {
                        dst.put(col, new BooleanPrimitive((Boolean) val));
                    }
                } else {
                    b.errorCode = "ENTITY_SET_ERROR";
                    b.errorMessage = "Non-primitive cell in projected column \"" + col + "\".";
                    return b;
                }
            }
            out.addRow(dst);
        }
        b.outTable = out;
        return b;
    }

    /**
     * Total order for canonical entity-set keys in {@code union} / {@code symmetric_difference}.
     * Lexicographic on the canonical key string so {@code compare(a,b) == 0} iff {@code a.equals(b)} (required by
     * {@link TreeSet}) and distinct STRING spellings such as {@code "1"} vs {@code "01"} stay distinct. Numeric
     * equality of logical keys is handled earlier via {@link #formatEntitySetKeyForComparison} when indexing rows.
     */
    private static int compareCanonicalEntityKeys(String a, String b) {
        return a.compareTo(b);
    }

    private static List<String> columnsExistingOnShape(DataShapeDefinition shape, List<String> projectColumns) {
        List<String> out = new ArrayList<>();
        for (String c : projectColumns) {
            if (columnExists(shape, c)) {
                out.add(c);
            }
        }
        return out;
    }

    private static DataShapeDefinition buildMergeOutputShape(InfoTable left, InfoTable right, List<String> projectColumns) {
        DataShapeDefinition out = new DataShapeDefinition();
        int ord = 0;
        for (String col : projectColumns) {
            BaseTypes bt = null;
            if (columnExists(shapeOf(left), col)) {
                bt = columnBaseType(left, col);
            }
            if (bt == null && columnExists(shapeOf(right), col)) {
                bt = columnBaseType(right, col);
            }
            FieldDefinition fd = new FieldDefinition();
            fd.setName(col);
            fd.setBaseType(bt != null ? bt : BaseTypes.STRING);
            fd.setOrdinal(ord++);
            out.addFieldDefinition(fd);
        }
        return out;
    }

    private static boolean appendProjectedPrimitive(DifferenceBuild b, ValueCollection dst, String col, Object val) {
        if (val instanceof IPrimitiveType) {
            dst.put(col, (IPrimitiveType) val);
        } else if (val == null) {
            return true;
        } else if (val instanceof String || val instanceof Number || val instanceof Boolean) {
            if (val instanceof String) {
                dst.put(col, new StringPrimitive((String) val));
            } else if (val instanceof Number) {
                dst.put(col, new NumberPrimitive(((Number) val).doubleValue()));
            } else {
                dst.put(col, new BooleanPrimitive((Boolean) val));
            }
        } else {
            b.errorCode = "ENTITY_SET_ERROR";
            b.errorMessage = "Non-primitive cell in projected column \"" + col + "\".";
            return false;
        }
        return true;
    }

    /**
     * When a key appears in both operands, projected columns present on both shapes must agree where both sides have a value,
     * or else one side may be null (filled from the other).
     */
    private static String conflictingOverlapColumn(ValueCollection leftRow, ValueCollection rightRow, List<String> colsOnBoth) {
        for (String c : colsOnBoth) {
            Object a = leftRow.getValue(c);
            Object b = rightRow.getValue(c);
            String na = normalizeCell(a);
            String nb = normalizeCell(b);
            if (na.equals(nb)) {
                continue;
            }
            if ("\0null".equals(na) || "\0null".equals(nb)) {
                continue;
            }
            return c;
        }
        return null;
    }

    private static DifferenceBuild buildUnion(InfoTable left, InfoTable right, Operand lo, Operand ro, List<String> projectColumns,
            BaseTypes leftKeyBt, BaseTypes rightKeyBt) throws Exception {
        DifferenceBuild b = new DifferenceBuild();
        b.leftRowCount = left.getRowCount();
        b.rightRowCount = right.getRowCount();

        Map<String, Integer> firstLeftIndex = new LinkedHashMap<>();
        Map<String, List<ValueCollection>> leftRowsByKey = new LinkedHashMap<>();
        for (int i = 0; i < left.getRowCount(); i++) {
            ValueCollection row = left.getRow(i);
            KeyParse kp = keyFromRow(row, lo.keyColumn, leftKeyBt);
            if (kp.error != null) {
                b.errorCode = "ENTITY_SET_KEY_UNUSABLE";
                b.errorMessage = "left row " + i + ": " + kp.error;
                return b;
            }
            String k = kp.key;
            firstLeftIndex.putIfAbsent(k, i);
            leftRowsByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(row);
        }
        b.leftUniqueKeys = firstLeftIndex.size();
        b.leftDupRows = left.getRowCount() - b.leftUniqueKeys;

        Map<String, Integer> firstRightIndex = new LinkedHashMap<>();
        Map<String, List<ValueCollection>> rightRowsByKey = new LinkedHashMap<>();
        for (int i = 0; i < right.getRowCount(); i++) {
            ValueCollection row = right.getRow(i);
            KeyParse kp = keyFromRow(row, ro.keyColumn, rightKeyBt);
            if (kp.error != null) {
                b.errorCode = "ENTITY_SET_KEY_UNUSABLE";
                b.errorMessage = "right row " + i + ": " + kp.error;
                return b;
            }
            String k = kp.key;
            firstRightIndex.putIfAbsent(k, i);
            rightRowsByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(row);
        }
        b.rightUniqueKeys = rightRowsByKey.size();
        b.rightDupRows = right.getRowCount() - b.rightUniqueKeys;

        DataShapeDefinition leftShape = shapeOf(left);
        DataShapeDefinition rightShape = shapeOf(right);
        List<String> colsLeft = columnsExistingOnShape(leftShape, projectColumns);
        for (Map.Entry<String, List<ValueCollection>> e : leftRowsByKey.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            ValueCollection first = e.getValue().get(0);
            for (int j = 1; j < e.getValue().size(); j++) {
                if (!rowsAgreeOnProjectedColumns(first, e.getValue().get(j), colsLeft)) {
                    b.errorCode = "ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS";
                    b.errorMessage = "Duplicate key \"" + e.getKey() + "\" on the left operand has conflicting values in projected columns.";
                    return b;
                }
            }
        }
        List<String> colsRight = columnsExistingOnShape(rightShape, projectColumns);
        for (Map.Entry<String, List<ValueCollection>> e : rightRowsByKey.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            ValueCollection first = e.getValue().get(0);
            for (int j = 1; j < e.getValue().size(); j++) {
                if (!rowsAgreeOnProjectedColumns(first, e.getValue().get(j), colsRight)) {
                    b.errorCode = "ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS";
                    b.errorMessage = "Duplicate key \"" + e.getKey() + "\" on the right operand has conflicting values in projected columns.";
                    return b;
                }
            }
        }

        List<String> colsBoth = new ArrayList<>();
        for (String c : projectColumns) {
            if (columnExists(leftShape, c) && columnExists(rightShape, c)) {
                colsBoth.add(c);
            }
        }

        TreeSet<String> allKeys = new TreeSet<>(AnalyzeEntitySetExecutor::compareCanonicalEntityKeys);
        allKeys.addAll(leftRowsByKey.keySet());
        allKeys.addAll(rightRowsByKey.keySet());

        DataShapeDefinition outShape = buildMergeOutputShape(left, right, projectColumns);
        InfoTable out = new InfoTable(outShape);
        for (String k : allKeys) {
            boolean inL = leftRowsByKey.containsKey(k);
            boolean inR = rightRowsByKey.containsKey(k);
            ValueCollection leftRow = inL ? left.getRow(firstLeftIndex.get(k)) : null;
            ValueCollection rightRow = inR ? right.getRow(firstRightIndex.get(k)) : null;
            if (inL && inR) {
                String conflict = conflictingOverlapColumn(leftRow, rightRow, colsBoth);
                if (conflict != null) {
                    b.errorCode = "ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS";
                    b.errorMessage = "Key \"" + k + "\" appears in both operands with different values for column \"" + conflict + "\".";
                    return b;
                }
            }
            ValueCollection dst = new ValueCollection();
            for (String col : projectColumns) {
                boolean onL = columnExists(leftShape, col);
                boolean onR = columnExists(rightShape, col);
                Object chosen = null;
                if (inL && onL) {
                    chosen = leftRow.getValue(col);
                }
                if ((chosen == null || normalizeCell(chosen).equals("\0null")) && inR && onR) {
                    chosen = rightRow.getValue(col);
                }
                if (chosen == null || normalizeCell(chosen).equals("\0null")) {
                    continue;
                }
                if (!appendProjectedPrimitive(b, dst, col, chosen)) {
                    return b;
                }
            }
            out.addRow(dst);
        }
        b.outTable = out;
        return b;
    }

    private static DifferenceBuild buildSymmetricDifference(InfoTable left, InfoTable right, Operand lo, Operand ro,
            List<String> projectColumns, BaseTypes leftKeyBt, BaseTypes rightKeyBt) throws Exception {
        DifferenceBuild b = new DifferenceBuild();
        b.leftRowCount = left.getRowCount();
        b.rightRowCount = right.getRowCount();

        Map<String, Integer> firstLeftIndex = new LinkedHashMap<>();
        Map<String, List<ValueCollection>> leftRowsByKey = new LinkedHashMap<>();
        for (int i = 0; i < left.getRowCount(); i++) {
            ValueCollection row = left.getRow(i);
            KeyParse kp = keyFromRow(row, lo.keyColumn, leftKeyBt);
            if (kp.error != null) {
                b.errorCode = "ENTITY_SET_KEY_UNUSABLE";
                b.errorMessage = "left row " + i + ": " + kp.error;
                return b;
            }
            String k = kp.key;
            firstLeftIndex.putIfAbsent(k, i);
            leftRowsByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(row);
        }
        b.leftUniqueKeys = firstLeftIndex.size();
        b.leftDupRows = left.getRowCount() - b.leftUniqueKeys;

        Map<String, Integer> firstRightIndex = new LinkedHashMap<>();
        Map<String, List<ValueCollection>> rightRowsByKey = new LinkedHashMap<>();
        for (int i = 0; i < right.getRowCount(); i++) {
            ValueCollection row = right.getRow(i);
            KeyParse kp = keyFromRow(row, ro.keyColumn, rightKeyBt);
            if (kp.error != null) {
                b.errorCode = "ENTITY_SET_KEY_UNUSABLE";
                b.errorMessage = "right row " + i + ": " + kp.error;
                return b;
            }
            String k = kp.key;
            firstRightIndex.putIfAbsent(k, i);
            rightRowsByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(row);
        }
        b.rightUniqueKeys = rightRowsByKey.size();
        b.rightDupRows = right.getRowCount() - b.rightUniqueKeys;

        DataShapeDefinition leftShape = shapeOf(left);
        DataShapeDefinition rightShape = shapeOf(right);
        List<String> colsLeft = columnsExistingOnShape(leftShape, projectColumns);
        for (Map.Entry<String, List<ValueCollection>> e : leftRowsByKey.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            ValueCollection first = e.getValue().get(0);
            for (int j = 1; j < e.getValue().size(); j++) {
                if (!rowsAgreeOnProjectedColumns(first, e.getValue().get(j), colsLeft)) {
                    b.errorCode = "ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS";
                    b.errorMessage = "Duplicate key \"" + e.getKey() + "\" on the left operand has conflicting values in projected columns.";
                    return b;
                }
            }
        }
        List<String> colsRight = columnsExistingOnShape(rightShape, projectColumns);
        for (Map.Entry<String, List<ValueCollection>> e : rightRowsByKey.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            ValueCollection first = e.getValue().get(0);
            for (int j = 1; j < e.getValue().size(); j++) {
                if (!rowsAgreeOnProjectedColumns(first, e.getValue().get(j), colsRight)) {
                    b.errorCode = "ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS";
                    b.errorMessage = "Duplicate key \"" + e.getKey() + "\" on the right operand has conflicting values in projected columns.";
                    return b;
                }
            }
        }

        List<String> colsBoth = new ArrayList<>();
        for (String c : projectColumns) {
            if (columnExists(leftShape, c) && columnExists(rightShape, c)) {
                colsBoth.add(c);
            }
        }

        TreeSet<String> symKeys = new TreeSet<>(AnalyzeEntitySetExecutor::compareCanonicalEntityKeys);
        for (String k : leftRowsByKey.keySet()) {
            if (!rightRowsByKey.containsKey(k)) {
                symKeys.add(k);
            }
        }
        for (String k : rightRowsByKey.keySet()) {
            if (!leftRowsByKey.containsKey(k)) {
                symKeys.add(k);
            }
        }

        DataShapeDefinition outShape = buildMergeOutputShape(left, right, projectColumns);
        InfoTable out = new InfoTable(outShape);
        for (String k : symKeys) {
            boolean inL = leftRowsByKey.containsKey(k);
            boolean inR = rightRowsByKey.containsKey(k);
            ValueCollection leftRow = inL ? left.getRow(firstLeftIndex.get(k)) : null;
            ValueCollection rightRow = inR ? right.getRow(firstRightIndex.get(k)) : null;
            ValueCollection dst = new ValueCollection();
            for (String col : projectColumns) {
                boolean onL = columnExists(leftShape, col);
                boolean onR = columnExists(rightShape, col);
                Object chosen = null;
                if (inL && onL) {
                    chosen = leftRow.getValue(col);
                } else if (inR && onR) {
                    chosen = rightRow.getValue(col);
                }
                if (chosen == null || normalizeCell(chosen).equals("\0null")) {
                    continue;
                }
                if (!appendProjectedPrimitive(b, dst, col, chosen)) {
                    return b;
                }
            }
            out.addRow(dst);
        }
        b.outTable = out;
        return b;
    }

    private static DataShapeDefinition buildOutputShape(InfoTable left, List<String> projectColumns) {
        DataShapeDefinition out = new DataShapeDefinition();
        int ord = 0;
        for (String col : projectColumns) {
            BaseTypes bt = columnBaseType(left, col);
            FieldDefinition fd = new FieldDefinition();
            fd.setName(col);
            fd.setBaseType(bt != null ? bt : BaseTypes.STRING);
            fd.setOrdinal(ord++);
            out.addFieldDefinition(fd);
        }
        return out;
    }

    private static boolean rowsAgreeOnProjectedColumns(ValueCollection a, ValueCollection b, List<String> cols) {
        for (String c : cols) {
            if (!Objects.equals(normalizeCell(a.getValue(c)), normalizeCell(b.getValue(c)))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeCell(Object v) {
        if (v == null) {
            return "\0null";
        }
        if (v instanceof IPrimitiveType) {
            try {
                Object inner = ((IPrimitiveType) v).getValue();
                return String.valueOf(inner);
            } catch (Exception e) {
                return v.toString();
            }
        }
        if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return String.valueOf(v);
    }

    private static final class KeyParse {
        final String key;
        final String error;

        KeyParse(String key, String error) {
            this.key = key;
            this.error = error;
        }
    }

    private static KeyParse keyFromRow(ValueCollection row, String col, BaseTypes declaredBaseType) {
        if (row == null) {
            return new KeyParse(null, "row is null");
        }
        Object v = row.getValue(col);
        if (v == null) {
            return new KeyParse(null, "missing key column \"" + col + "\"");
        }
        Object inner;
        if (v instanceof IPrimitiveType) {
            try {
                inner = ((IPrimitiveType) v).getValue();
            } catch (Exception e) {
                return new KeyParse(null, "key column \"" + col + "\" could not be read");
            }
        } else if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            inner = v;
        } else {
            return new KeyParse(null, "key column \"" + col + "\" is not a scalar primitive");
        }
        if (inner == null) {
            return new KeyParse(null, "key value is null");
        }
        String key = formatEntitySetKeyForComparison(inner, declaredBaseType);
        if (key == null) {
            return new KeyParse(null, "key value could not be normalized for the column base type");
        }
        if (key.isEmpty()) {
            return new KeyParse(null, "key value is blank");
        }
        return new KeyParse(key, null);
    }

    /**
     * Canonical key string so INTEGER {@code 1}, LONG {@code 1}, and NUMBER {@code 1.0} compare equal across operands.
     */
    static String formatEntitySetKeyForComparison(Object inner, BaseTypes declaredBaseType) {
        if (inner == null || declaredBaseType == null) {
            return null;
        }
        switch (declaredBaseType) {
            case INTEGER:
            case LONG:
            case NUMBER:
                if (!(inner instanceof Number)) {
                    return null;
                }
                return new BigDecimal(inner.toString()).stripTrailingZeros().toPlainString();
            case BOOLEAN:
                if (inner instanceof Boolean) {
                    return ((Boolean) inner).booleanValue() ? "true" : "false";
                }
                return null;
            case STRING:
            case THINGNAME:
            case GUID:
            case DATETIME:
                return String.valueOf(inner).trim();
            default:
                return null;
        }
    }

    private static String formatSuccess(String operation, Operand leftOp, Operand rightOp, InfoTable leftTable, InfoTable rightTable,
            DifferenceBuild diff, String newCacheId, List<String> projectColumns, int maxItems, int offset) throws Exception {
        int totalRows = diff.outTable.getRowCount();
        int matchedKeys = totalRows;

        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "success");
        root.put("operation", operation);
        root.put("cacheId", newCacheId);
        root.set("left", operandSummary(leftOp, leftTable, diff.leftUniqueKeys, diff.leftDupRows));
        root.set("right", operandSummary(rightOp, rightTable, diff.rightUniqueKeys, diff.rightDupRows));
        root.put("matchedKeys", matchedKeys);
        root.put("totalRows", totalRows);
        root.put("inputsFullyScanned", true);

        ArrayNode colMeta = MAPPER.createArrayNode();
        for (String c : projectColumns) {
            ObjectNode cn = MAPPER.createObjectNode();
            cn.put("name", c);
            cn.put("baseType", ParlerInfotableJsonUtil.wireBaseTypeForColumn(diff.outTable, c));
            colMeta.add(cn);
        }
        root.set("columns", colMeta);

        String resultKind;
        List<String> cols = projectColumns;
        if (totalRows == 0) {
            resultKind = "ENTITY_SET_EMPTY";
            root.put("resultKind", resultKind);
            root.putArray("rows");
            root.put("answerSetComplete", true);
            root.put("sampleOnly", false);
            root.put("rowsOmitted", false);
            root.put("returnedRows", 0);
            String emptyHint;
            if ("intersection".equals(operation)) {
                emptyHint = "Empty intersection; use tabulate_cached_result on the returned cacheId when the overlap is non-empty, or adjust operands.";
            } else if ("union".equals(operation)) {
                emptyHint = "Empty union (both operands had no rows); use tabulate_cached_result on other caches if needed.";
            } else if ("symmetric_difference".equals(operation)) {
                emptyHint = "Empty symmetric difference (the two sets are identical on keys); adjust operands or use difference/intersection.";
            } else {
                emptyHint = "Empty difference; use tabulate_cached_result or build_chart_from_tabular_result on other caches if needed.";
            }
            root.put("hint", emptyHint);
            LOG.info("analyze_entity_set ok resultKind={} totalRows=0 cacheId={}", resultKind, newCacheId);
            return MAPPER.writeValueAsString(root);
        }

        int threshold = INLINE_ROW_THRESHOLD;
        if (totalRows <= threshold) {
            resultKind = "ENTITY_SET_INLINE";
            root.put("resultKind", resultKind);
            int start = Math.min(offset, totalRows);
            int end = Math.min(start + maxItems, totalRows);
            ArrayNode rows = MAPPER.createArrayNode();
            for (int i = start; i < end; i++) {
                rows.add(CachedTabularToolsExecutor.rowToObject(diff.outTable, i, cols));
            }
            root.set("rows", rows);
            int returned = end - start;
            root.put("returnedRows", returned);
            root.put("sampleOnly", returned < totalRows);
            root.put("rowsOmitted", returned < totalRows);
            boolean inlineComplete = returned == totalRows && start == 0;
            root.put("answerSetComplete", inlineComplete);
            root.put("hint", "Use tabulate_cached_result on cacheId for group_metric / charts; fetch_cached_result pages this table.");
        } else {
            resultKind = "ENTITY_SET_LARGE";
            root.put("resultKind", resultKind);
            int start = Math.min(offset, totalRows);
            int cap = Math.min(maxItems, threshold);
            int end = Math.min(start + cap, totalRows);
            ArrayNode sample = MAPPER.createArrayNode();
            for (int i = start; i < end; i++) {
                sample.add(CachedTabularToolsExecutor.rowToObject(diff.outTable, i, cols));
            }
            root.set("sampleRows", sample);
            int returned = end - start;
            root.put("returnedRows", returned);
            root.put("sampleOnly", true);
            root.put("rowsOmitted", true);
            root.put("answerSetComplete", false);
            root.put("hint", InvokeServiceExecutor.FETCH_CACHED_LARGE_TABLE_HINT);
        }
        LOG.info("analyze_entity_set ok resultKind={} totalRows={} cacheId={}", resultKind, totalRows, newCacheId);
        return MAPPER.writeValueAsString(root);
    }

    private static ObjectNode operandSummary(Operand op, InfoTable table, int uniqueKeys, int dupRows) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("cacheId", op.cacheId);
        o.put("keyColumn", op.keyColumn);
        if (op.label != null && !op.label.isEmpty()) {
            o.put("label", op.label);
        }
        o.put("rowCount", table.getRowCount());
        o.put("uniqueKeys", uniqueKeys);
        o.put("duplicateKeyRows", dupRows);
        return o;
    }

    private static DataShapeDefinition shapeOf(InfoTable src) {
        try {
            return src != null ? src.getDataShape() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean columnExists(DataShapeDefinition ds, String col) {
        if (ds == null || col == null) {
            return false;
        }
        try {
            if (ds.getFields() != null) {
                for (FieldDefinition f : ds.getFields().values()) {
                    if (col.equals(f.getName())) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
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
        }
        return names;
    }

    private static BaseTypes columnBaseType(InfoTable it, String col) {
        try {
            DataShapeDefinition ds = it.getDataShape();
            if (ds == null || ds.getFields() == null) {
                return null;
            }
            for (FieldDefinition fd : ds.getFields().values()) {
                if (col.equals(fd.getName())) {
                    return fd.getBaseType();
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static final class Violation {
        final String field;
        final String column;

        Violation(String field, String column) {
            this.field = field;
            this.column = column;
        }
    }

    private static Violation passwordViolation(DataShapeDefinition ds, String col, String field) {
        if (ParlerInfotableJsonUtil.isPasswordColumn(ds, col)) {
            return new Violation(field, col);
        }
        return null;
    }

    private static String protectedBlocked(Violation v) {
        ParlerProtectionAudit.blocked(ProtectedValuePolicy.CODE_TABULAR_PROTECTED_COLUMN, "analyze_entity_set",
                "field=" + v.field + " column=" + v.column);
        return errorJson(ProtectedValuePolicy.CODE_TABULAR_PROTECTED_COLUMN,
                "Cannot use PASSWORD column \"" + v.column + "\" for " + v.field + ".");
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        String s = n.asText();
        return s == null ? null : s.trim();
    }

    private static String errorJson(String code, String message) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message == null ? "" : message);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + code + "\",\"message\":\"\"}";
        }
    }
}
