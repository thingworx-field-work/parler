package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Universal InfoTable → {@code parler.infotable.matrix.v1} encoder for LLM replay compaction
 * ({@code docs/agent/llm-token-budget.md} Phase 2). Fail-soft: never throws to callers; logs
 * {@code LLM_COMPACT_MALFORMED} and returns the original body when encoding is unsafe or not worthwhile.
 */
public final class InfoTableMatrixCodec {

    public static final String FORMAT_MATRIX_V1 = "parler.infotable.matrix.v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> ELIGIBLE_RESULT_KINDS = Set.of(
            "INFOTABLE",
            "INFOTABLE_LARGE",
            "ENTITY_QUERY_INLINE",
            "ENTITY_QUERY_LARGE",
            "ENTITY_LIST_INLINE",
            "ENTITY_LIST_LARGE",
            "ENTITY_TAXONOMY_QUERY_INLINE",
            "ENTITY_TAXONOMY_QUERY_LARGE",
            "CACHED_TABULATE_INLINE",
            "CACHED_TABULATE_LARGE",
            "CACHED_FILTER_ROWS_INLINE",
            "CACHED_FILTER_ROWS_LARGE",
            "CACHED_FILTER_SORT_TOPN_INLINE",
            "CACHED_FILTER_SORT_TOPN_LARGE",
            "CACHED_GROUP_METRIC_INLINE",
            "CACHED_GROUP_METRIC_LARGE",
            "NUMERIC_HISTORY_INLINE",
            "NUMERIC_HISTORY_AGGREGATES",
            "VALUE_STREAM_HISTORY_INLINE");

    private InfoTableMatrixCodec() {}

    /**
     * @return encoded JSON when matrix form is applied; otherwise {@code originalBody} unchanged
     */
    public static String encodeIfEligible(String originalBody, Logger log) {
        if (originalBody == null || originalBody.isEmpty()) {
            return originalBody;
        }
        String trimmed = originalBody.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return originalBody;
        }
        try {
            JsonNode root = MAPPER.readTree(originalBody);
            if (!root.isObject()) {
                return originalBody;
            }
            ObjectNode obj = (ObjectNode) root;
            if (isSealedOrFormatted(obj)) {
                return originalBody;
            }
            if (!"success".equalsIgnoreCase(text(obj, "status"))) {
                return originalBody;
            }
            if (!isEligibleResultKind(obj)) {
                return originalBody;
            }
            JsonNode cols = obj.get("columns");
            if (cols == null || !cols.isArray() || cols.isEmpty()) {
                malformed(log, "missing_or_empty_columns");
                return originalBody;
            }
            String rowsKey = resolvePrimaryTabularRowsKey(obj);
            if (rowsKey == null) {
                malformed(log, "no_rows_array");
                return originalBody;
            }
            JsonNode rows = obj.get(rowsKey);
            if (rows.isEmpty()) {
                return originalBody;
            }
            if (rows.get(0).isArray()) {
                return originalBody;
            }
            if (hasProtectedColumn(cols, rows)) {
                return originalBody;
            }
            List<ColumnDef> colDefs = parseColumns(cols);
            if (colDefs.isEmpty()) {
                malformed(log, "no_column_names");
                return originalBody;
            }
            Set<String> constantCols = detectConstantColumns(colDefs, rows);
            ObjectNode out = shallowCopyWithoutRows(obj, rowsKey);
            ArrayNode matrixRows = MAPPER.createArrayNode();
            for (JsonNode row : rows) {
                if (!row.isObject()) {
                    malformed(log, "row_not_object");
                    return originalBody;
                }
                ArrayNode one = MAPPER.createArrayNode();
                for (ColumnDef d : colDefs) {
                    if (constantCols.contains(d.name)) {
                        continue;
                    }
                    JsonNode cell = row.get(d.name);
                    one.add(cell == null || cell.isNull() ? MAPPER.nullNode() : cell);
                }
                matrixRows.add(one);
            }
            ArrayNode outCols = MAPPER.createArrayNode();
            for (ColumnDef d : colDefs) {
                if (constantCols.contains(d.name)) {
                    continue;
                }
                ObjectNode c = MAPPER.createObjectNode();
                c.put("name", d.name);
                c.put("baseType", d.baseType != null ? d.baseType : "STRING");
                outCols.add(c);
            }
            out.set("columns", outCols);
            out.set(rowsKey, matrixRows);
            if (!constantCols.isEmpty()) {
                ObjectNode constObj = MAPPER.createObjectNode();
                for (String name : constantCols) {
                    JsonNode v = firstRowValue(rows, name);
                    constObj.set(name, v == null ? MAPPER.nullNode() : v);
                }
                out.set("constants", constObj);
            }
            out.put("$format", FORMAT_MATRIX_V1);
            String encoded = MAPPER.writeValueAsString(out);
            int rawLen = originalBody.length();
            int compactLen = encoded.length();
            if (!passesSavingsThreshold(rawLen, compactLen)) {
                return originalBody;
            }
            return encoded;
        } catch (Exception e) {
            if (log != null) {
                log.warn("LLM_COMPACT_MALFORMED reason=encode_exception msg={}", e.getMessage());
            }
            return originalBody;
        }
    }

    private static boolean passesSavingsThreshold(int rawLen, int compactLen) {
        if (rawLen <= 0) {
            return false;
        }
        return compactLen + 80 <= rawLen && compactLen <= rawLen * 0.95;
    }

    private static void malformed(Logger log, String reason) {
        if (log != null) {
            log.warn("LLM_COMPACT_MALFORMED resultKind=tabular reason={}", reason);
        }
    }

    private static boolean isSealedOrFormatted(ObjectNode obj) {
        if (!obj.has("$format")) {
            return false;
        }
        String f = obj.get("$format").asText("");
        return f.startsWith("parler.infotable.matrix")
                || f.startsWith("parler.cohort.")
                || f.startsWith("parler.infotable.summary")
                || f.startsWith("parler.evidence.stub");
    }

    private static boolean isEligibleResultKind(ObjectNode obj) {
        if (!obj.has("resultKind") || obj.get("resultKind").isNull()) {
            return resolvePrimaryTabularRowsKey(obj) != null;
        }
        String rk = obj.get("resultKind").asText("");
        return ELIGIBLE_RESULT_KINDS.contains(rk);
    }

    /**
     * Tabular tool envelopes use {@code rows}/{@code sampleRows}; taxonomy uses
     * {@code rootEntityList}/{@code sampleRootEntityList}. Prefer the first present array key in that order.
     * Package-private for {@link LlmToolResultTierBPromoter} (same package) so Tier B summaries use the same
     * row-array resolution as matrix encoding.
     */
    /**
     * Whether {@code body} is the sealed matrix this codec emits — the single source of truth for that question.
     *
     * <p>Consumers that need to recognize a matrix (for example the checkpoint evidence manifest) MUST call this
     * rather than re-deriving the shape. A second interpretation drifts: it is easy to miss that the encoder writes
     * the matrix back under <em>whichever</em> of the four row keys it resolved, or that an all-constant column set
     * legitimately produces {@code columns:[]} with empty cell arrays and the values under {@code constants}.
     *
     * <p>The sealed shape, exactly as {@link #encodeIfEligible} produces it:
     *
     * <ul>
     *   <li>{@code $format} is {@value #FORMAT_MATRIX_V1};</li>
     *   <li>exactly one row key is present — {@code shallowCopyWithoutRows} drops all four and only the resolved
     *       one is written back, so a second collection means the body was not sealed by this codec;</li>
     *   <li>that row array is non-empty: an empty source row array returns the original body unsealed;</li>
     *   <li>{@code columns} is an array of named columns, possibly <em>empty</em> when every column was constant;</li>
     *   <li>every row is a cell array whose width equals the column count, since one cell is emitted per
     *       non-constant column.</li>
     * </ul>
     *
     * <p>This says nothing about whether the body is acceptable <em>evidence</em> — success status, PASSWORD
     * columns, and row bounds are the caller's policy, not the encoder's shape.
     */
    public static boolean isSealedMatrixV1(JsonNode body) {
        if (body == null || !body.isObject()) {
            return false;
        }
        JsonNode format = body.get("$format");
        if (format == null || !format.isTextual() || !FORMAT_MATRIX_V1.equals(format.asText())) {
            return false;
        }
        // Admission: the encoder refuses a present resultKind outside ELIGIBLE_RESULT_KINDS, so a body carrying an
        // ineligible one could not have been sealed here however well-formed the rest of it looks.
        JsonNode resultKind = body.get("resultKind");
        if (resultKind != null && !resultKind.isNull() && !ELIGIBLE_RESULT_KINDS.contains(resultKind.asText(""))) {
            return false;
        }
        JsonNode columns = body.get("columns");
        if (columns == null || !columns.isArray()) {
            return false;
        }
        // The output loop writes both name and a textual baseType for every surviving column — defaulting to STRING
        // rather than omitting it. A column missing its type is producer-impossible, and it would also slip past a
        // consumer's PASSWORD gate.
        for (JsonNode c : columns) {
            if (!c.isObject() || !isNonBlankText(c.get("name")) || !isNonBlankText(c.get("baseType"))) {
                return false;
            }
        }
        // Empty columns is legitimate only as the all-constant representation, which by construction moved at least
        // one source column into a non-empty constants object.
        if (columns.isEmpty()) {
            JsonNode constants = body.get("constants");
            if (constants == null || !constants.isObject() || constants.isEmpty()) {
                return false;
            }
        }
        // Exactly one row key may be *present*. shallowCopyWithoutRows strips all four before the resolved one is
        // written back, so a second key — even explicitly null — means this body was not sealed here.
        JsonNode rows = null;
        for (String key : TABULAR_ROWS_KEYS) {
            if (!body.has(key)) {
                continue;
            }
            JsonNode n = body.get(key);
            if (rows != null || n == null || !n.isArray()) {
                return false;
            }
            rows = n;
        }
        // An empty source row array returns the original body unsealed, so a zero-row matrix is unproducible.
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        for (JsonNode r : rows) {
            if (!r.isArray() || r.size() != columns.size()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNonBlankText(JsonNode n) {
        return n != null && n.isTextual() && !n.asText().isBlank();
    }

    /** The row collection returned by {@link #isSealedMatrixV1}-accepted bodies, or {@code null}. */
    public static JsonNode sealedMatrixRows(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        for (String key : TABULAR_ROWS_KEYS) {
            JsonNode n = body.get(key);
            if (n != null && n.isArray()) {
                return n;
            }
        }
        return null;
    }


    /** The tabular row keys this codec understands, in resolution order. */
    static final String[] TABULAR_ROWS_KEYS = { "rows", "sampleRows", "rootEntityList", "sampleRootEntityList" };

    static String resolvePrimaryTabularRowsKey(ObjectNode obj) {
        for (String key : TABULAR_ROWS_KEYS) {
            if (obj.has(key) && obj.get(key).isArray()) {
                return key;
            }
        }
        return null;
    }

    private static boolean hasProtectedColumn(JsonNode cols, JsonNode rows) {
        for (JsonNode c : cols) {
            if (!c.isObject()) {
                continue;
            }
            String bt = text((ObjectNode) c, "baseType");
            if (bt != null && "PASSWORD".equalsIgnoreCase(bt)) {
                return true;
            }
            String name = text((ObjectNode) c, "name");
            if (name == null) {
                continue;
            }
            for (JsonNode row : rows) {
                if (!row.isObject()) {
                    continue;
                }
                JsonNode cell = row.get(name);
                if (cell != null && cell.isTextual() && "***".equals(cell.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<ColumnDef> parseColumns(JsonNode cols) {
        List<ColumnDef> out = new ArrayList<>();
        for (JsonNode c : cols) {
            if (!c.isObject()) {
                continue;
            }
            ObjectNode o = (ObjectNode) c;
            String name = text(o, "name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            String bt = text(o, "baseType");
            out.add(new ColumnDef(name, bt));
        }
        return out;
    }

    private static Set<String> detectConstantColumns(List<ColumnDef> colDefs, JsonNode rows) {
        Set<String> constants = new HashSet<>();
        for (ColumnDef d : colDefs) {
            if (isConstantColumn(d.name, rows)) {
                constants.add(d.name);
            }
        }
        return constants;
    }

    private static boolean isConstantColumn(String name, JsonNode rows) {
        JsonNode first = null;
        boolean seen = false;
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                return false;
            }
            JsonNode v = row.get(name);
            if (!seen) {
                first = v;
                seen = true;
            } else if (!jsonEquals(first, v)) {
                return false;
            }
        }
        return seen;
    }

    private static JsonNode firstRowValue(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            if (row.isObject()) {
                return row.get(name);
            }
        }
        return null;
    }

    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || a.isNull()) {
            return b == null || b.isNull();
        }
        if (b == null || b.isNull()) {
            return false;
        }
        return a.equals(b);
    }

    private static boolean isTabularRowsKey(String field) {
        for (String key : TABULAR_ROWS_KEYS) {
            if (key.equals(field)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode shallowCopyWithoutRows(ObjectNode src, String rowsKey) {
        ObjectNode out = MAPPER.createObjectNode();
        Iterator<String> it = src.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (isTabularRowsKey(f)) {
                continue;
            }
            out.set(f, src.get(f));
        }
        return out;
    }

    private static String text(ObjectNode o, String field) {
        JsonNode n = o.get(field);
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

    private static final class ColumnDef {
        final String name;
        final String baseType;

        ColumnDef(String name, String baseType) {
            this.name = name;
            this.baseType = baseType;
        }
    }
}
