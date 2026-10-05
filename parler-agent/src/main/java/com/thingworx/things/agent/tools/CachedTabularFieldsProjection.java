package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;

/**
 * Optional root {@code fields} projection for {@code tabulate_cached_result} (query-spec §5.3–§5.4).
 */
public final class CachedTabularFieldsProjection {

    private static final Set<String> MODES_WITH_FIELDS = new HashSet<>();

    static {
        MODES_WITH_FIELDS.add("sort_topn");
        MODES_WITH_FIELDS.add("filter_rows");
        MODES_WITH_FIELDS.add("filter_sort_topn");
        MODES_WITH_FIELDS.add("group_metric");
    }

    private CachedTabularFieldsProjection() {}

    /** @throws CachedTabularDecisionToolException when {@code fields} is present but invalid for {@code mode}. */
    public static void assertModeAllowsFields(String mode, JsonNode root) throws CachedTabularDecisionToolException {
        if (root == null || !root.has("fields") || root.get("fields").isNull()) {
            return;
        }
        if (mode == null || !MODES_WITH_FIELDS.contains(mode)) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    "fields is not supported for mode " + mode + ".");
        }
    }

    /**
     * Parses {@code fields} when present; returns {@code null} to mean full column set.
     *
     * @param allowedColumns source or output column names (order used only for error stability)
     */
    public static List<String> parseFieldsOrNull(JsonNode fieldsNode, List<String> allowedColumns)
            throws CachedTabularDecisionToolException {
        if (fieldsNode == null || fieldsNode.isNull()) {
            return null;
        }
        if (!fieldsNode.isArray()) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "fields must be a JSON array of column name strings.");
        }
        if (fieldsNode.size() == 0) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "fields must not be an empty array.");
        }
        Set<String> allowed = new LinkedHashSet<>(allowedColumns);
        List<String> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fieldsNode.size(); i++) {
            JsonNode el = fieldsNode.get(i);
            if (el == null || !el.isTextual()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "fields[" + i + "] must be a non-empty string column name.");
            }
            String name = el.asText().trim();
            if (name.isEmpty()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "fields[" + i + "] must be a non-empty string column name.");
            }
            if (!seen.add(name)) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "fields contains duplicate column \"" + name + "\".");
            }
            if (!allowed.contains(name)) {
                throw new CachedTabularDecisionToolException("INVALID_COLUMN",
                        "Unknown column for fields projection: \"" + name + "\".");
            }
            order.add(name);
        }
        return order;
    }

    public static InfoTable project(InfoTable src, List<String> columnsInOrder) throws Exception {
        if (src == null || columnsInOrder == null || columnsInOrder.isEmpty()) {
            return src;
        }
        DataShapeDefinition inShape = src.getDataShape();
        DataShapeDefinition outShape = new DataShapeDefinition();
        int ord = 0;
        for (String col : columnsInOrder) {
            FieldDefinition srcFd = findField(inShape, col);
            if (srcFd == null) {
                throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + col);
            }
            FieldDefinition nf = new FieldDefinition();
            nf.setName(srcFd.getName());
            nf.setBaseType(srcFd.getBaseType());
            nf.setOrdinal(ord++);
            outShape.addFieldDefinition(nf);
        }
        InfoTable out = new InfoTable(outShape);
        int n = src.getRowCount();
        for (int i = 0; i < n; i++) {
            ValueCollection oldRow = src.getRow(i);
            ValueCollection newRow = new ValueCollection();
            for (String col : columnsInOrder) {
                newRow.put(col, oldRow.get(col));
            }
            out.addRow(newRow);
        }
        return out;
    }

    private static FieldDefinition findField(DataShapeDefinition ds, String name) {
        if (ds == null || ds.getFields() == null || name == null) {
            return null;
        }
        for (FieldDefinition f : ds.getFields().values()) {
            if (name.equals(f.getName())) {
                return f;
            }
        }
        return null;
    }

    /** Modes that may include root {@code fields} per query-spec §7.1. */
    public static boolean modeSupportsRootFields(String mode) {
        return mode != null && MODES_WITH_FIELDS.contains(mode.trim().toLowerCase(Locale.ROOT));
    }
}
