package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.UnknownProjectedColumnException;
import com.thingworx.things.agent.source.ColumnRoles;

/**
 * CM-2 structured feedback for an {@code analyze_cached_result} column-projection miss. Adds, next to
 * the unchanged outer error keys: {@code rejectedParameter}, {@code parameterSource}, {@code side}
 * (relationship only), {@code cacheId}, a bounded {@code schema} and, only when provable,
 * {@code columnSuggestion}.
 *
 * <p>Suggestion order (each step is final): shared-parameter gate → unique case-insensitive match on
 * the full visible schema → validated writer-declared role. No inference from tool names, column names
 * or types. Uniqueness and role validation always use the full in-memory schema; the serialized
 * {@code schema} is only a bounded display.
 */
final class AnalyzeProjectionDiagnostics {

    /** First-value caps from the design (§5 CM-2 item 4); tunable at implementation review. */
    static final int MAX_SCHEMA_COLUMNS = 32;
    static final int MAX_SCHEMA_CHARS = 2_000;

    static final String PARAM_TIME = "timeColumn";
    static final String PARAM_VALUE = "valueColumn";
    static final String PARAM_RIGHT_VALUE = "rightValueColumn";
    static final String SOURCE_EXPLICIT = "explicit";
    static final String SOURCE_INHERITED_VALUE = "inherited:" + PARAM_VALUE;
    static final String SIDE_LEFT = "left";
    static final String SIDE_RIGHT = "right";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnalyzeProjectionDiagnostics() {}

    /** Carries one projection miss from {@code dispatch} to {@code execute}. */
    static final class ProjectionMiss extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient UnknownProjectedColumnException cause;
        final String cacheId;
        final String side;
        final boolean relationship;
        final String rejectedParameter;
        final String parameterSource;

        ProjectionMiss(UnknownProjectedColumnException cause, String cacheId, String side, boolean relationship,
                String rejectedParameter, String parameterSource) {
            super(cause.getMessage(), cause);
            this.cause = cause;
            this.cacheId = cacheId;
            this.side = side;
            this.relationship = relationship;
            this.rejectedParameter = rejectedParameter;
            this.parameterSource = parameterSource;
        }
    }

    /** Map the projection index of {@code [timeColumn, valueColumnForThisSide]} to the parameter name. */
    static String parameterFor(int projectionIndex, boolean rightSide) {
        if (projectionIndex == 0) {
            return PARAM_TIME;
        }
        return rightSide ? PARAM_RIGHT_VALUE : PARAM_VALUE;
    }

    /** Append the CM-2 fields to an error object that already carries status / reason / detail / mayPublish. */
    static void appendTo(ObjectNode err, ProjectionMiss miss) {
        List<TypedColumn> schema = miss.cause.visibleSchema();
        err.put("rejectedParameter", miss.rejectedParameter);
        err.put("parameterSource", miss.parameterSource);
        if (miss.relationship) {
            err.put("side", miss.side);
        }
        err.put("cacheId", miss.cacheId);

        String suggestion = suggest(miss, schema);
        BoundedSchema bounded = boundedSchema(schema, suggestion);
        err.set("schema", bounded.node);
        if (suggestion != null && bounded.suggestionShown) {
            ObjectNode cs = MAPPER.createObjectNode();
            cs.put(miss.rejectedParameter, suggestion);
            err.set("columnSuggestion", cs);
        }
    }

    /** The suggested real column name, or {@code null}. Uses the full schema only. */
    static String suggest(ProjectionMiss miss, List<TypedColumn> schema) {
        // 1. Shared-parameter gate: the left side already accepted this timeColumn.
        if (miss.relationship && SIDE_RIGHT.equals(miss.side) && PARAM_TIME.equals(miss.rejectedParameter)) {
            return null;
        }
        // 2. Unique case-insensitive match (a non-role column still wins: explicit selection).
        String rejected = miss.cause.columnName() == null ? "" : miss.cause.columnName().trim();
        String lower = rejected.toLowerCase(Locale.ROOT);
        String match = null;
        int matches = 0;
        for (TypedColumn c : schema) {
            if (c.name().toLowerCase(Locale.ROOT).equals(lower)) {
                matches++;
                match = c.name();
            }
        }
        if (matches == 1) {
            return match;
        }
        if (matches > 1) {
            return null; // case collision terminates; no role fallback
        }
        // 3. Validated writer-declared role on the failing side's own descriptor.
        List<String> names = new ArrayList<>(schema.size());
        for (TypedColumn c : schema) {
            names.add(c.name());
        }
        ColumnRoles roles = ColumnRoles.fromDescriptor(miss.cause.descriptor(), names);
        if (roles == null) {
            return null;
        }
        return PARAM_TIME.equals(miss.rejectedParameter) ? roles.timeColumn() : roles.valueColumn();
    }

    /** Result of {@link #boundedSchema}: the public node and whether the suggested column's definition is in it. */
    static final class BoundedSchema {
        final ObjectNode node;
        final boolean suggestionShown;

        BoundedSchema(ObjectNode node, boolean suggestionShown) {
            this.node = node;
            this.suggestionShown = suggestionShown;
        }
    }

    /**
     * Bounded display of the visible schema. The bound is measured on the <em>serialized</em> public
     * object ({@code columnCount}, {@code columns[]}, {@code truncated}, JSON escaping included): at
     * most {@link #MAX_SCHEMA_COLUMNS} columns and {@link #MAX_SCHEMA_CHARS} characters, source order,
     * names never clipped. A name the error egress would excerpt ({@link
     * ToolResultEgressGateway#hardTextExcerptChars()}) is unrepresentable and omitted. The suggested
     * column's definition is kept whenever it fits (other columns are dropped from the end first);
     * when even that does not fit, the suggestion is not shown. {@code truncated} is true whenever any
     * visible column is absent from the list.
     */
    static BoundedSchema boundedSchema(List<TypedColumn> schema, String suggestion) {
        int maxName = ToolResultEgressGateway.hardTextExcerptChars();
        List<TypedColumn> representable = new ArrayList<>();
        for (TypedColumn c : schema) {
            if (c.name().length() <= maxName) {
                representable.add(c);
            }
        }
        TypedColumn suggested = null;
        if (suggestion != null) {
            for (TypedColumn c : representable) {
                if (c.name().equals(suggestion)) {
                    suggested = c;
                    break;
                }
            }
        }

        // Candidate list: first MAX_SCHEMA_COLUMNS representable columns in source order; the
        // suggested column takes the last slot when it is not already among them.
        List<TypedColumn> chosen = new ArrayList<>();
        for (TypedColumn c : representable) {
            if (chosen.size() >= MAX_SCHEMA_COLUMNS) {
                break;
            }
            chosen.add(c);
        }
        if (suggested != null && !chosen.contains(suggested)) {
            if (chosen.size() >= MAX_SCHEMA_COLUMNS) {
                chosen.remove(chosen.size() - 1);
            }
            chosen.add(suggested);
        }

        // Trim from the end (never the suggested column) until the serialized object fits.
        int total = schema.size();
        while (true) {
            boolean truncated = chosen.size() < total;
            ObjectNode node = render(total, chosen, truncated);
            if (serializedLength(node) <= MAX_SCHEMA_CHARS) {
                return new BoundedSchema(node, suggested != null && chosen.contains(suggested));
            }
            int dropIdx = -1;
            for (int i = chosen.size() - 1; i >= 0; i--) {
                if (chosen.get(i) != suggested) {
                    dropIdx = i;
                    break;
                }
            }
            if (dropIdx < 0) {
                // Only the suggested column is left and it still does not fit: unrepresentable.
                chosen.clear();
                suggested = null;
            } else {
                chosen.remove(dropIdx);
            }
        }
    }

    private static ObjectNode render(int columnCount, List<TypedColumn> chosen, boolean truncated) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("columnCount", columnCount);
        ArrayNode cols = node.putArray("columns");
        for (TypedColumn c : chosen) {
            ObjectNode col = MAPPER.createObjectNode();
            col.put("name", c.name());
            col.put("baseType", c.baseType().name());
            cols.add(col);
        }
        node.put("truncated", truncated);
        return node;
    }

    /** Exact character count of the public JSON as the model will receive it. */
    static int serializedLength(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node).length();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}
