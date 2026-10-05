package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Generic {@code join_by_key} derive op (see {@code docs/agent/playbook-generic-ops-foundation.md} section 8.8).
 * Package-private; invoked from {@link PlaybookGenericDeriveOps}.
 */
final class PlaybookGenericJoinByKey {

    private PlaybookGenericJoinByKey() {}

    static JSONObject execute(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("join_by_key: missing args");
        }
        JSONArray left = PlaybookGenericRowArrays.loadResolvedSide(args, ctx, "left", "join_by_key");
        JSONArray right = PlaybookGenericRowArrays.loadResolvedSide(args, ctx, "right", "join_by_key");
        int nLeft = left.length();
        int nRight = right.length();

        String leftKey = PlaybookGenericJsonSchemaStrings.requireTrimmed(args, "leftKey", "join_by_key: args");
        if (leftKey.isEmpty()) {
            throw new PlaybookRunException("join_by_key: leftKey must be non-blank");
        }
        if (!PlaybookGenericPathGrammar.isValidDottedPath(leftKey)) {
            throw new PlaybookRunException("join_by_key: leftKey invalid dotted path");
        }
        String rightKey = PlaybookGenericJsonSchemaStrings.requireTrimmed(args, "rightKey", "join_by_key: args");
        if (rightKey.isEmpty()) {
            throw new PlaybookRunException("join_by_key: rightKey must be non-blank");
        }
        if (!PlaybookGenericPathGrammar.isValidDottedPath(rightKey)) {
            throw new PlaybookRunException("join_by_key: rightKey invalid dotted path");
        }

        String joinTypeRaw = PlaybookGenericJsonSchemaStrings.requireTrimmed(args, "joinType", "join_by_key: args");
        if (joinTypeRaw.isEmpty()) {
            throw new PlaybookRunException("join_by_key: joinType must be non-blank");
        }
        String joinType = joinTypeRaw.toLowerCase(Locale.ROOT);
        if (!("inner".equals(joinType) || "left".equals(joinType))) {
            throw new PlaybookRunException("join_by_key: joinType must be inner or left");
        }

        String rightPrefix = PlaybookGenericJsonSchemaStrings.optionalTrimmed(args, "rightPrefix", "join_by_key: args");

        if (!args.has("maxRows")) {
            throw new PlaybookRunException("join_by_key: maxRows is required");
        }
        Object mrObj = args.opt("maxRows");
        if (!(mrObj instanceof Number)) {
            throw new PlaybookRunException("join_by_key: maxRows must be a number");
        }
        double mrd = ((Number) mrObj).doubleValue();
        if (mrd < 1 || mrd != Math.rint(mrd) || mrd > PlaybookGenericOpsConstants.MAX_GENERIC_JOIN_OUTPUT_ROWS) {
            throw new PlaybookRunException(
                    "join_by_key: maxRows must be an integer from 1 to "
                            + PlaybookGenericOpsConstants.MAX_GENERIC_JOIN_OUTPUT_ROWS);
        }
        int maxRows = ((Number) mrObj).intValue();

        Map<String, List<Integer>> rightIndex = buildRightIndex(right, rightKey);

        int logical = countLogical(left, rightIndex, leftKey, joinType);
        int unmatchedLeft = 0;
        int innerLeftNoRightMatch = 0;
        for (int li = 0; li < nLeft; li++) {
            Object lv = PlaybookJsonRowPath.getAtPath(left.getJSONObject(li), leftKey);
            List<Integer> matches = matchesForLeftKey(lv, rightIndex);
            if (matches.isEmpty() && "left".equals(joinType)) {
                unmatchedLeft++;
            }
            if (matches.isEmpty() && "inner".equals(joinType) && !isNullJoinKey(lv)) {
                innerLeftNoRightMatch++;
            }
        }

        JSONArray built = new JSONArray();
        outer:
        for (int li = 0; li < nLeft; li++) {
            JSONObject lrow = left.getJSONObject(li);
            Object lv = PlaybookJsonRowPath.getAtPath(lrow, leftKey);
            List<Integer> matches = matchesForLeftKey(lv, rightIndex);
            if (matches.isEmpty()) {
                if ("left".equals(joinType)) {
                    if (built.length() >= maxRows) {
                        break outer;
                    }
                    built.put(mergeRows(lrow, null, rightPrefix));
                }
            } else {
                for (int ri : matches) {
                    if (built.length() >= maxRows) {
                        break outer;
                    }
                    built.put(mergeRows(lrow, right.getJSONObject(ri), rightPrefix));
                }
            }
        }

        PlaybookGenericRowArrays.Truncation t =
                PlaybookGenericRowArrays.truncateIfNeededWithLogicalCount(built, logical, maxRows, "join_by_key");
        JSONArray gaps = new JSONArray();
        for (int gi = 0; gi < t.gaps.length(); gi++) {
            gaps.put(t.gaps.getString(gi));
        }

        JSONObject output =
                new JSONObject().put("rows", t.rows).put("totalCount", t.logicalCount).put("returned", t.returned).put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        String ev = "join_by_key: left " + nLeft + " row(s); right " + nRight + " row(s); logical " + logical
                + " joined row(s); emitted " + t.returned + " row(s)";
        if ("left".equals(joinType) && unmatchedLeft > 0) {
            ev += "; " + unmatchedLeft + " left row(s) had no right match";
        }
        if ("inner".equals(joinType) && innerLeftNoRightMatch > 0) {
            ev += "; " + innerLeftNoRightMatch + " inner left row(s) had no right match";
        }
        if (t.logicalCount > t.returned) {
            ev += "; output truncated to maxRows " + maxRows;
        }
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev + "."));
        return result;
    }

    private static Map<String, List<Integer>> buildRightIndex(JSONArray right, String rightKey)
            throws PlaybookRunException {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (int ri = 0; ri < right.length(); ri++) {
            JSONObject rrow = right.getJSONObject(ri);
            Object rv = PlaybookJsonRowPath.getAtPath(rrow, rightKey);
            if (isNullJoinKey(rv)) {
                continue;
            }
            String sk = stableKey(rv);
            out.computeIfAbsent(sk, k -> new ArrayList<>()).add(ri);
        }
        return out;
    }

    /** SQL-style: null / JSON-null join keys do not match anything (including each other). */
    private static boolean isNullJoinKey(Object v) {
        return v == null || v == JSONObject.NULL;
    }

    private static List<Integer> matchesForLeftKey(Object lv, Map<String, List<Integer>> rightIndex) {
        if (isNullJoinKey(lv)) {
            return List.of();
        }
        return rightIndex.getOrDefault(stableKey(lv), List.of());
    }

    private static int countLogical(JSONArray left, Map<String, List<Integer>> rightIndex, String leftKey,
            String joinType) throws PlaybookRunException {
        int logical = 0;
        for (int li = 0; li < left.length(); li++) {
            Object lv = PlaybookJsonRowPath.getAtPath(left.getJSONObject(li), leftKey);
            List<Integer> matches = matchesForLeftKey(lv, rightIndex);
            if (matches.isEmpty()) {
                if ("left".equals(joinType)) {
                    logical++;
                }
            } else {
                logical += matches.size();
            }
        }
        return logical;
    }

    /** Same encoding as {@code PlaybookGenericGroupBy} composite parts for non-null join key values only. */
    private static String stableKey(Object v) {
        if (v instanceof Boolean) {
            return "\u0000b" + v;
        }
        if (v instanceof Number) {
            return "\u0000d" + Double.toString(((Number) v).doubleValue());
        }
        return "\u0000s" + String.valueOf(v);
    }

    private static JSONObject mergeRows(JSONObject leftRow, JSONObject rightRow, String rightPrefix)
            throws PlaybookRunException {
        JSONObject out = new JSONObject();
        for (String k : leftRow.keySet()) {
            out.put(k, leftRow.get(k));
        }
        if (rightRow == null) {
            return out;
        }
        for (String rk : rightRow.keySet()) {
            String nk = rightPrefix + rk;
            if (out.has(nk)) {
                throw new PlaybookRunException("join_by_key: merged row key collision on \"" + nk + "\"",
                        "GENERIC_INPUT_INVALID");
            }
            out.put(nk, rightRow.get(rk));
        }
        return out;
    }
}
