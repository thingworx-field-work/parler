package com.thingworx.things.agent.playbook;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shared helpers for generic row ops: strict row-slot validation and output-row truncation
 * (see {@code docs/agent/playbook-generic-ops-foundation.md} sections 6.2–6.4, common rules §6.1).
 * <p>
 * Non-expanding ops ({@code project}, {@code filter}, {@code sort}, {@code top_n}, {@code pick_one},
 * {@code group_by}, {@code aggregate}, …) have output row count ≤ input; with
 * {@link PlaybookGenericOpsConstants#MAX_GENERIC_INPUT_ROWS} == {@link PlaybookGenericOpsConstants#MAX_GENERIC_OUTPUT_ROWS},
 * they do not reach output truncation at runtime. Expanding ops ({@code join_by_key}, cartesian
 * {@code build_targets}, …) MUST surface truncation via {@link #truncateIfNeeded} or
 * {@link #truncateIfNeededWithLogicalCount} so {@code totalCount} / {@code returned} / {@code gaps} match section 6.2.
 */
public final class PlaybookGenericRowArrays {

    private PlaybookGenericRowArrays() {}

    /**
     * Resolves container to a {@link JSONArray} of row objects: arrays pass through, a lone
     * {@link JSONObject} becomes a one-element array, {@link List} is wrapped. Other types fail closed.
     */
    public static JSONArray coerceResolvedRowContainerToArray(Object o, String opLabel) throws PlaybookRunException {
        if (o == null || o == JSONObject.NULL) {
            throw new PlaybookRunException(opLabel + ": resolved input is null", "GENERIC_INPUT_INVALID");
        }
        if (o instanceof JSONArray) {
            return (JSONArray) o;
        }
        if (o instanceof JSONObject) {
            return new JSONArray().put(o);
        }
        if (o instanceof List) {
            return new JSONArray((List<?>) o);
        }
        throw new PlaybookRunException(
                opLabel + ": input must be a JSON array or object, got " + o.getClass().getSimpleName(),
                "GENERIC_INPUT_INVALID");
    }

    /**
     * Resolve {@code args.opt(fieldKey)} (e.g. {@code "left"} / {@code "right"} for {@code join_by_key}), coerce,
     * enforce {@link PlaybookGenericOpsConstants#MAX_GENERIC_INPUT_ROWS}, and require object slots.
     */
    public static JSONArray loadResolvedSide(JSONObject args, PlaybookRunContext ctx, String fieldKey, String opLabel)
            throws PlaybookRunException {
        return loadResolvedNamedRows(args, ctx, fieldKey, opLabel);
    }

    /**
     * Resolve {@code args}{@code .opt("rows")}, coerce, enforce {@link PlaybookGenericOpsConstants#MAX_GENERIC_INPUT_ROWS},
     * and require object slots — shared entry for generic row ops ({@code project}, {@code filter}, …).
     */
    public static JSONArray loadResolvedRowArgs(JSONObject args, PlaybookRunContext ctx, String opLabel)
            throws PlaybookRunException {
        return loadResolvedNamedRows(args, ctx, "rows", opLabel);
    }

    private static JSONArray loadResolvedNamedRows(JSONObject args, PlaybookRunContext ctx, String fieldKey, String opLabel)
            throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException(opLabel + ": missing args", "GENERIC_INPUT_INVALID");
        }
        Object rowsObj = PlaybookExpressionResolver.resolve(args.opt(fieldKey), ctx);
        JSONArray rows = coerceResolvedRowContainerToArray(rowsObj, opLabel);
        if (rows.length() > PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS) {
            throw new PlaybookRunException(
                    opLabel + ": input " + fieldKey + " exceeds cap " + PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS,
                    "GENERIC_INPUT_TOO_LARGE");
        }
        requireEachSlotIsObject(rows, opLabel, fieldKey);
        return rows;
    }

    /**
     * Each array slot MUST be a {@link JSONObject}. Used by row-iterating derive ops so scalar /
     * null elements are not silently skipped.
     */
    public static void requireEachSlotIsObject(JSONArray rows, String opLabel) throws PlaybookRunException {
        requireEachSlotIsObject(rows, opLabel, "rows");
    }

    /**
     * @param rowRole field name for error messages ({@code rows}, {@code left}, {@code right}, …).
     */
    public static void requireEachSlotIsObject(JSONArray rows, String opLabel, String rowRole)
            throws PlaybookRunException {
        if (rows == null) {
            throw new PlaybookRunException(opLabel + ": " + rowRole + " array is null", "GENERIC_INPUT_INVALID");
        }
        for (int i = 0; i < rows.length(); i++) {
            if (rows.optJSONObject(i) == null) {
                throw new PlaybookRunException(
                        opLabel + ": " + rowRole + "[" + i + "] must be a JSON object", "GENERIC_INPUT_INVALID");
            }
        }
    }

    /**
     * If {@code rows.length()} exceeds {@code maxRows}, copy the first {@code maxRows} objects into a new
     * array and append one deterministic gap string. Otherwise returns the original array reference.
     */
    public static Truncation truncateIfNeeded(JSONArray rows, int maxRows, String gapPrefix) {
        int logical = rows.length();
        if (logical <= maxRows) {
            return new Truncation(rows, logical, logical, new JSONArray());
        }
        JSONArray truncated = new JSONArray();
        for (int i = 0; i < maxRows; i++) {
            truncated.put(rows.getJSONObject(i));
        }
        JSONArray gaps = new JSONArray();
        gaps.put(gapPrefix + ": truncated output rows from " + logical + " to " + maxRows);
        return new Truncation(truncated, logical, maxRows, gaps);
    }

    /**
     * When the logical output row count is known separately from the materialized prefix (expanding joins), set
     * {@link Truncation#logicalCount} to {@code logicalCount} and {@link Truncation#returned} to
     * {@code materializedRows.length()} (must be {@code min(logicalCount, maxRows)} when {@code logicalCount > maxRows}).
     */
    public static Truncation truncateIfNeededWithLogicalCount(
            JSONArray materializedRows, int logicalCount, int maxRows, String gapPrefix) {
        if (logicalCount <= maxRows) {
            if (materializedRows.length() != logicalCount) {
                throw new IllegalArgumentException(
                        "materialized row count " + materializedRows.length() + " must equal logicalCount " + logicalCount);
            }
            return new Truncation(materializedRows, logicalCount, materializedRows.length(), new JSONArray());
        }
        if (materializedRows.length() != maxRows) {
            throw new IllegalArgumentException(
                    "when truncated, materialized row count must equal maxRows (" + maxRows + "), got "
                            + materializedRows.length());
        }
        JSONArray gaps = new JSONArray();
        gaps.put(gapPrefix + ": truncated output rows from " + logicalCount + " to " + maxRows);
        return new Truncation(materializedRows, logicalCount, maxRows, gaps);
    }

    /** Result of {@link #truncateIfNeeded}. */
    public static final class Truncation {
        public final JSONArray rows;
        /** Logical row count before truncation. */
        public final int logicalCount;
        /** Serialized row length after truncation. */
        public final int returned;
        public final JSONArray gaps;

        Truncation(JSONArray rows, int logicalCount, int returned, JSONArray gaps) {
            this.rows = rows;
            this.logicalCount = logicalCount;
            this.returned = returned;
            this.gaps = gaps;
        }
    }
}
