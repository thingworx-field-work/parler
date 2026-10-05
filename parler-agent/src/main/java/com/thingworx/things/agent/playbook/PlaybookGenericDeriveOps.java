package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Priority 1 generic {@code derive} operations (see {@code docs/agent/playbook-generic-ops-foundation.md}).
 * Dispatched from {@link PlaybookDeriveOps#execute} before V1a/V1b-specific ops.
 */
public final class PlaybookGenericDeriveOps {

    private static final Set<String> GENERIC_OPS = Set.of("project", "filter", "sort", "top_n", "pick_one", "group_by",
            "aggregate", "join_by_key", "build_targets", "collect_gaps", "flatten_fan_out_rows",
            "normalize_resolved_things", "extract_from_tool_output", "build_nested_object", "json_stringify",
            "resolve_time_window_for_playbook", "empty_rows_if_skipped", "merge_row_sets", "add_computed_fields", "collect_values",
            "join_values", "normalize_text", "match_candidates", "dedupe", "limit_rows", "format_evidence_lines");

    private PlaybookGenericDeriveOps() {}

    public static boolean supports(String op) {
        return op != null && GENERIC_OPS.contains(op);
    }

    public static JSONObject execute(String op, JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (PlaybookOrchestrationDeriveOps.supports(op)) {
            return PlaybookOrchestrationDeriveOps.execute(op, args, ctx);
        }
        if ("project".equals(op)) {
            return project(args, ctx);
        }
        if ("filter".equals(op)) {
            return filter(args, ctx);
        }
        if ("sort".equals(op)) {
            return sort(args, ctx);
        }
        if ("top_n".equals(op)) {
            return topN(args, ctx);
        }
        if ("pick_one".equals(op)) {
            return pickOne(args, ctx);
        }
        if ("group_by".equals(op)) {
            return PlaybookGenericGroupBy.execute(args, ctx);
        }
        if ("aggregate".equals(op)) {
            return PlaybookGenericAggregate.execute(args, ctx);
        }
        if ("join_by_key".equals(op)) {
            return PlaybookGenericJoinByKey.execute(args, ctx);
        }
        if ("build_targets".equals(op)) {
            return PlaybookGenericBuildTargets.execute(args, ctx);
        }
        if ("collect_gaps".equals(op)) {
            return PlaybookGenericCollectGaps.execute(args, ctx);
        }
        if ("flatten_fan_out_rows".equals(op)) {
            return PlaybookGenericFlattenFanOutRows.execute(args, ctx);
        }
        if ("add_computed_fields".equals(op)) {
            return PlaybookAnalyticsDeriveOps.addComputedFields(args, ctx);
        }
        if ("collect_values".equals(op)) {
            return PlaybookAnalyticsDeriveOps.collectValues(args, ctx);
        }
        if ("join_values".equals(op)) {
            return PlaybookAnalyticsDeriveOps.joinValues(args, ctx);
        }
        if (PlaybookAuthoringDeriveOps.supports(op)) {
            return PlaybookAuthoringDeriveOps.execute(op, args, ctx);
        }
        throw new PlaybookRunException("unsupported generic derive op: " + op);
    }

    private static JSONObject project(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("project: missing args");
        }
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "project");
        int inputRowSlots = rows.length();
        JSONArray fields = args.optJSONArray("fields");
        if (fields == null || fields.length() == 0) {
            throw new PlaybookRunException("project: fields must be a non-empty array");
        }
        int fieldSpecCount = countNonTrivialFieldSpecs(fields);
        boolean dropNullOnlyRows = args.optBoolean("dropNullOnlyRows", false);
        JSONArray outRows = new JSONArray();
        int skippedDropNullOnly = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject inRow = rows.getJSONObject(i);
            JSONObject outRow = new JSONObject();
            boolean rowHasNonNull = false;
            for (int j = 0; j < fields.length(); j++) {
                JSONObject spec = fields.optJSONObject(j);
                if (spec == null) {
                    continue;
                }
                String from = spec.optString("from", "").trim();
                String as = spec.optString("as", "").trim();
                if (from.isEmpty() || as.isEmpty()) {
                    continue;
                }
                Object v = PlaybookJsonRowPath.getAtPath(inRow, from);
                if (spec.has("default") && (v == null || v == JSONObject.NULL)) {
                    v = spec.get("default");
                }
                if (v == null || v == JSONObject.NULL) {
                    outRow.put(as, JSONObject.NULL);
                } else {
                    outRow.put(as, v);
                    rowHasNonNull = true;
                }
            }
            if (dropNullOnlyRows && !rowHasNonNull) {
                skippedDropNullOnly++;
                continue;
            }
            outRows.put(outRow);
        }
        int produced = outRows.length();
        JSONArray gaps = new JSONArray();
        JSONObject output = new JSONObject()
                .put("rows", outRows)
                .put("totalCount", produced)
                .put("returned", produced)
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        String ev = "project: input " + inputRowSlots + " row slot(s); emitted " + produced + " row(s) to "
                + fieldSpecCount + " field spec(s)";
        if (skippedDropNullOnly > 0) {
            ev += "; " + skippedDropNullOnly + " row slot(s) skipped (dropNullOnlyRows, all-null after defaults)";
        }
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev + "."));
        return result;
    }

    private static JSONObject filter(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("filter: missing args");
        }
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "filter");
        int inputRowSlots = rows.length();
        JSONObject where = args.optJSONObject("where");
        if (where == null || where.length() == 0) {
            throw new PlaybookRunException("filter: where must be a non-empty predicate object");
        }
        PlaybookRowPredicate.requireValidShape(where);
        JSONArray outRows = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject inRow = rows.getJSONObject(i);
            if (PlaybookRowPredicate.evaluateWithoutShapeCheck(where, inRow, ctx)) {
                outRows.put(shallowCopyRow(inRow));
            }
        }
        int kept = outRows.length();
        int dropped = inputRowSlots - kept;
        JSONArray gaps = new JSONArray();
        JSONObject output = new JSONObject()
                .put("rows", outRows)
                .put("totalCount", kept)
                .put("returned", kept)
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        String ev = "filter: input " + inputRowSlots + " row slot(s); kept " + kept + " row(s); dropped " + dropped
                + " row(s).";
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev));
        return result;
    }

    private static JSONObject sort(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("sort: missing args");
        }
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "sort");
        int inputRowSlots = rows.length();
        JSONArray orderBy = args.optJSONArray("orderBy");
        if (orderBy == null || orderBy.length() == 0) {
            throw new PlaybookRunException("sort: orderBy must be a non-empty array");
        }
        List<OrderKey> keys = parseOrderBy(orderBy, "sort");
        List<JSONObject> rowList = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            rowList.add(rows.getJSONObject(i));
        }
        Comparator<JSONObject> cmp = (a, b) -> compareRowsByOrderBy(a, b, keys);
        rowList.sort(cmp);
        JSONArray outRows = new JSONArray();
        for (JSONObject r : rowList) {
            outRows.put(shallowCopyRow(r));
        }
        int produced = outRows.length();
        JSONArray gaps = new JSONArray();
        JSONObject output = new JSONObject()
                .put("rows", outRows)
                .put("totalCount", produced)
                .put("returned", produced)
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        String ev = "sort: input " + inputRowSlots + " row slot(s); emitted " + produced + " row(s) by "
                + keys.size() + " order key(s).";
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev));
        return result;
    }

    private static JSONObject topN(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("top_n: missing args");
        }
        if (!args.has("n")) {
            throw new PlaybookRunException("top_n: n is required");
        }
        Object nRaw = args.opt("n");
        if (!(nRaw instanceof Number)) {
            throw new PlaybookRunException("top_n: n must be a number");
        }
        double nd = ((Number) nRaw).doubleValue();
        if (nd < 0 || nd != Math.rint(nd) || nd > Integer.MAX_VALUE) {
            throw new PlaybookRunException("top_n: n must be a non-negative integer");
        }
        int n = ((Number) nRaw).intValue();
        if (n > PlaybookGenericOpsConstants.MAX_GENERIC_TOP_N) {
            throw new PlaybookRunException(
                    "top_n: n exceeds cap " + PlaybookGenericOpsConstants.MAX_GENERIC_TOP_N,
                    "GENERIC_INPUT_INVALID");
        }
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "top_n");
        int inputRowSlots = rows.length();
        JSONArray orderBy = args.optJSONArray("orderBy");
        List<JSONObject> rowList = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            rowList.add(rows.getJSONObject(i));
        }
        StringBuilder orderEv = new StringBuilder();
        if (orderBy != null && orderBy.length() > 0) {
            List<OrderKey> keys = parseOrderBy(orderBy, "top_n");
            rowList.sort((a, b) -> compareRowsByOrderBy(a, b, keys));
            if (!keys.isEmpty()) {
                OrderKey k0 = keys.get(0);
                orderEv.append(" by ").append(k0.field).append(" ").append(k0.ascending ? "asc" : "desc");
            }
        } else if (orderBy != null && orderBy.length() == 0) {
            throw new PlaybookRunException("top_n: orderBy must be omitted or a non-empty array");
        }
        int take = Math.min(n, rowList.size());
        JSONArray gaps = new JSONArray();
        if (n > rowList.size()) {
            gaps.put("top_n returned fewer rows than requested");
        }
        JSONArray outRows = new JSONArray();
        for (int i = 0; i < take; i++) {
            outRows.put(shallowCopyRow(rowList.get(i)));
        }
        int produced = outRows.length();
        JSONObject output = new JSONObject()
                .put("rows", outRows)
                .put("totalCount", produced)
                .put("returned", produced)
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        String ev = "top_n: selected " + produced + " of " + inputRowSlots + " row(s)" + orderEv + ".";
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev));
        return result;
    }

    private static JSONObject pickOne(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("pick_one: missing args");
        }
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "pick_one");
        int inputRowSlots = rows.length();
        JSONObject where = args.optJSONObject("where");
        if (where == null || where.length() == 0) {
            throw new PlaybookRunException("pick_one: where must be a non-empty predicate object");
        }
        PlaybookRowPredicate.requireValidShape(where);
        String onZero = normalizePickMode(args.optString("onZero", "needs_clarification"), "onZero");
        String onMultiple = normalizePickMode(args.optString("onMultiple", "needs_clarification"), "onMultiple");
        String label = args.optString("label", "row").trim();
        if (label.isEmpty()) {
            label = "row";
        }
        List<JSONObject> matches = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject inRow = rows.getJSONObject(i);
            if (PlaybookRowPredicate.evaluateWithoutShapeCheck(where, inRow, ctx)) {
                matches.add(inRow);
            }
        }
        int m = matches.size();
        if (m == 1) {
            JSONObject row = shallowCopyRow(matches.get(0));
            JSONArray gaps = new JSONArray();
            JSONObject output = new JSONObject()
                    .put("row", row)
                    .put("totalCount", 1)
                    .put("returned", 1)
                    .put("gaps", gaps);
            JSONObject result = new JSONObject().put("status", "ok").put("output", output);
            String ev = "pick_one: selected 1 of " + inputRowSlots + " row slot(s) (label " + label + ").";
            PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev));
            return result;
        }
        if (m == 0) {
            return pickOneZero(inputRowSlots, label, onZero);
        }
        return pickOneMultiple(inputRowSlots, label, onMultiple, matches);
    }

    private static JSONObject pickOneZero(int inputRowSlots, String label, String onZero) throws PlaybookRunException {
        switch (onZero) {
            case "needs_clarification":
                return pickOneNeedsClarificationWithOutput(
                        "No " + label + " matched the predicate (0 of " + inputRowSlots + " rows).",
                        "pick_one: 0 matches; needs_clarification (" + label + ").");
            case "gap":
                JSONArray gapsG = new JSONArray();
                gapsG.put("pick_one: zero matches for " + label);
                JSONObject outG = new JSONObject()
                        .put("row", JSONObject.NULL)
                        .put("totalCount", 0)
                        .put("returned", 0)
                        .put("gaps", gapsG);
                JSONObject resG = new JSONObject().put("status", "ok").put("output", outG);
                PlaybookNodeEvidence.attachLinesOnly(
                        resG, PlaybookNodeEvidence.singleLine("pick_one: 0 matches; mode gap (" + label + ")."));
                return resG;
            case "empty":
                JSONObject outE = new JSONObject()
                        .put("row", JSONObject.NULL)
                        .put("totalCount", 0)
                        .put("returned", 0)
                        .put("gaps", new JSONArray());
                JSONObject resE = new JSONObject().put("status", "ok").put("output", outE);
                PlaybookNodeEvidence.attachLinesOnly(
                        resE, PlaybookNodeEvidence.singleLine("pick_one: 0 matches; mode empty (" + label + ")."));
                return resE;
            default:
                throw new PlaybookRunException("pick_one: invalid onZero mode");
        }
    }

    private static JSONObject pickOneMultiple(
            int inputRowSlots, String label, String onMultiple, List<JSONObject> matches)
            throws PlaybookRunException {
        switch (onMultiple) {
            case "needs_clarification":
                return pickOneNeedsClarificationWithOutput(
                        "Multiple " + label + " rows matched (" + matches.size() + " of " + inputRowSlots
                                + "). Please narrow the predicate.",
                        "pick_one: " + matches.size() + " matches; needs_clarification (" + label + ").");
            case "gap":
                JSONArray gapsG = new JSONArray();
                gapsG.put("pick_one: multiple matches for " + label);
                JSONObject outG = new JSONObject()
                        .put("row", JSONObject.NULL)
                        .put("totalCount", 0)
                        .put("returned", 0)
                        .put("gaps", gapsG);
                JSONObject resG = new JSONObject().put("status", "ok").put("output", outG);
                PlaybookNodeEvidence.attachLinesOnly(
                        resG,
                        PlaybookNodeEvidence.singleLine(
                                "pick_one: " + matches.size() + " matches; mode gap (" + label + ")."));
                return resG;
            case "first":
                JSONObject row = shallowCopyRow(matches.get(0));
                JSONArray gaps = new JSONArray();
                JSONObject output = new JSONObject()
                        .put("row", row)
                        .put("totalCount", 1)
                        .put("returned", 1)
                        .put("gaps", gaps);
                JSONObject result = new JSONObject().put("status", "ok").put("output", output);
                PlaybookNodeEvidence.attachLinesOnly(
                        result,
                        PlaybookNodeEvidence.singleLine(
                                "pick_one: selected first of " + matches.size() + " matches (" + label + ")."));
                return result;
            default:
                throw new PlaybookRunException("pick_one: invalid onMultiple mode");
        }
    }

    /**
     * Section 6.1 generic envelope: {@code needs_clarification} still includes {@code output} with
     * {@code row} JSON-null and zero counts (same shell as {@code gap}/{@code empty}).
     */
    private static JSONObject pickOneNeedsClarificationWithOutput(String message, String evidenceLine) {
        JSONObject output = new JSONObject()
                .put("row", JSONObject.NULL)
                .put("totalCount", 0)
                .put("returned", 0)
                .put("gaps", new JSONArray());
        JSONObject res = new JSONObject()
                .put("status", "needs_clarification")
                .put("message", message)
                .put("output", output);
        PlaybookNodeEvidence.attachLinesOnly(res, PlaybookNodeEvidence.singleLine(evidenceLine));
        return res;
    }

    private static String normalizePickMode(String raw, String fieldName) throws PlaybookRunException {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("onZero".equals(fieldName)) {
            if ("needs_clarification".equals(s) || "gap".equals(s) || "empty".equals(s)) {
                return s;
            }
        } else if ("onMultiple".equals(fieldName)) {
            if ("needs_clarification".equals(s) || "gap".equals(s) || "first".equals(s)) {
                return s;
            }
        }
        throw new PlaybookRunException("pick_one: invalid " + fieldName + " value");
    }

    private static final class OrderKey {
        final String field;
        final boolean ascending;

        OrderKey(String field, boolean ascending) {
            this.field = field;
            this.ascending = ascending;
        }
    }

    private static List<OrderKey> parseOrderBy(JSONArray orderBy, String opLabel) throws PlaybookRunException {
        List<OrderKey> keys = new ArrayList<>(orderBy.length());
        for (int i = 0; i < orderBy.length(); i++) {
            JSONObject spec = orderBy.optJSONObject(i);
            if (spec == null) {
                throw new PlaybookRunException(opLabel + ": orderBy[" + i + "] must be an object");
            }
            String field = spec.optString("field", "").trim();
            if (field.isEmpty()) {
                throw new PlaybookRunException(opLabel + ": orderBy[" + i + "] requires non-blank field");
            }
            if (!PlaybookGenericPathGrammar.isValidDottedPath(field)) {
                throw new PlaybookRunException(opLabel + ": orderBy[" + i + "] invalid dotted field path");
            }
            String dir = spec.optString("direction", "asc").trim().toLowerCase(Locale.ROOT);
            boolean asc;
            if ("asc".equals(dir)) {
                asc = true;
            } else if ("desc".equals(dir)) {
                asc = false;
            } else {
                throw new PlaybookRunException(opLabel + ": orderBy[" + i + "] direction must be asc or desc");
            }
            keys.add(new OrderKey(field, asc));
        }
        return keys;
    }

    private static int compareRowsByOrderBy(JSONObject a, JSONObject b, List<OrderKey> keys) {
        try {
            for (OrderKey k : keys) {
                Object va = PlaybookJsonRowPath.getAtPath(a, k.field);
                Object vb = PlaybookJsonRowPath.getAtPath(b, k.field);
                int c = PlaybookGenericRowOrdering.compareFieldValues(va, vb, k.ascending);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        } catch (PlaybookRunException e) {
            throw new IllegalStateException("unexpected row path failure during sort", e);
        }
    }

    private static JSONObject shallowCopyRow(JSONObject inRow) {
        JSONObject o = new JSONObject();
        for (String key : inRow.keySet()) {
            o.put(key, inRow.get(key));
        }
        return o;
    }

    private static int countNonTrivialFieldSpecs(JSONArray fields) {
        int n = 0;
        for (int j = 0; j < fields.length(); j++) {
            JSONObject spec = fields.optJSONObject(j);
            if (spec == null) {
                continue;
            }
            String from = spec.optString("from", "").trim();
            String as = spec.optString("as", "").trim();
            if (!from.isEmpty() && !as.isEmpty()) {
                n++;
            }
        }
        return n > 0 ? n : fields.length();
    }
}
