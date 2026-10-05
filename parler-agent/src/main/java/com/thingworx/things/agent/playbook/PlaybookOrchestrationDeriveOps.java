package com.thingworx.things.agent.playbook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Service-orchestration derive ops for topic {@code playbook-34-35} Slice A.
 */
final class PlaybookOrchestrationDeriveOps {

    private PlaybookOrchestrationDeriveOps() {}

    static boolean supports(String op) {
        return "normalize_resolved_things".equals(op) || "extract_from_tool_output".equals(op)
                || "build_nested_object".equals(op) || "json_stringify".equals(op)
                || "resolve_time_window_for_playbook".equals(op) || "empty_rows_if_skipped".equals(op)
                || "merge_row_sets".equals(op);
    }

    static JSONObject execute(String op, JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if ("normalize_resolved_things".equals(op)) {
            return normalizeResolvedThings(args, ctx);
        }
        if ("extract_from_tool_output".equals(op)) {
            return extractFromToolOutput(args, ctx);
        }
        if ("build_nested_object".equals(op)) {
            return PlaybookNestedObjectBuilder.buildNestedObject(args, ctx);
        }
        if ("json_stringify".equals(op)) {
            return PlaybookNestedObjectBuilder.jsonStringify(args, ctx);
        }
        if ("resolve_time_window_for_playbook".equals(op)) {
            return PlaybookTimeWindowDeriveOps.resolveTimeWindowForPlaybook(args, ctx);
        }
        if ("empty_rows_if_skipped".equals(op)) {
            return PlaybookOptionalBranchDeriveOps.emptyRowsIfSkipped(args, ctx);
        }
        if ("merge_row_sets".equals(op)) {
            return PlaybookOptionalBranchDeriveOps.mergeRowSets(args, ctx);
        }
        throw new PlaybookRunException("unsupported orchestration derive op: " + op);
    }

    private static JSONObject normalizeResolvedThings(JSONObject args, PlaybookRunContext ctx)
            throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("normalize_resolved_things: missing args");
        }
        String fanOutId = args.optString("fanOutNodeId", "").trim();
        if (fanOutId.isEmpty()) {
            throw new PlaybookRunException("normalize_resolved_things: missing fanOutNodeId");
        }
        String inputField = args.optString("inputField", "input").trim();
        if (inputField.isEmpty()) {
            inputField = "input";
        }
        String onUnresolved = args.optString("onUnresolved", "gap").trim();
        if (!"gap".equals(onUnresolved) && !"clarify".equals(onUnresolved)) {
            throw new PlaybookRunException("normalize_resolved_things: onUnresolved must be gap or clarify");
        }
        int minResolvedRows = intArg(args, "minResolvedRows", 0);
        int maxRows = requiredPositiveInt(args, "maxRows", "normalize_resolved_things");
        if (maxRows < minResolvedRows) {
            throw new PlaybookRunException(
                    "normalize_resolved_things: maxRows must be >= minResolvedRows", "GENERIC_INPUT_INVALID");
        }

        JSONObject fanOut = ctx.nodeOutput(fanOutId);
        if (fanOut == null) {
            throw new PlaybookRunException("normalize_resolved_things: missing output for " + fanOutId);
        }
        JSONArray children = fanOut.optJSONArray("children");
        if (children == null) {
            throw new PlaybookRunException("normalize_resolved_things: fan_out has no children array",
                    "GENERIC_INPUT_INVALID");
        }

        JSONArray rows = new JSONArray();
        JSONArray gaps = new JSONArray();
        int resolvedCount = 0;
        int totalCount = children.length();

        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            if (child == null) {
                gaps.put(gapJson("", "child_not_object"));
                continue;
            }
            JSONObject item = child.optJSONObject("item");
            String input = PlaybookResolveThingEnvelope.inputFromItem(item, inputField);
            if (!"ok".equals(child.optString("status", ""))) {
                gaps.put(gapJson(input, "child_failed"));
                continue;
            }
            PlaybookResolveThingEnvelope.Classification c = PlaybookResolveThingEnvelope.classify(
                    child.optJSONObject("toolOutput"), input, inputField);
            if (c.kind == PlaybookResolveThingEnvelope.Kind.ROW) {
                rows.put(c.row);
                resolvedCount++;
            } else if (c.kind == PlaybookResolveThingEnvelope.Kind.CLARIFY) {
                if ("clarify".equals(onUnresolved)) {
                    return needsClarification(c.clarifyMessage, c.candidates);
                }
                gaps.put(c.gap != null ? c.gap : gapJson(input, "clarify"));
            } else {
                if ("clarify".equals(onUnresolved)) {
                    JSONArray candidates = c.gap != null ? c.gap.optJSONArray("candidates") : null;
                    String gapCode = c.gap != null ? c.gap.optString("code", "").trim() : "";
                    if (gapCode.isEmpty() && c.gap != null) {
                        gapCode = c.gap.optString("reason", "").trim();
                    }
                    String msg = "Could not resolve " + inputLabel(input);
                    if (!gapCode.isEmpty()) {
                        msg += " (" + gapCode + ")";
                    }
                    msg += ".";
                    return needsClarification(msg, candidates);
                }
                gaps.put(c.gap);
            }
        }

        if (resolvedCount < minResolvedRows) {
            return needsClarification(
                    "Resolved " + resolvedCount + " item(s) but at least " + minResolvedRows + " required.", null);
        }

        PlaybookGenericRowArrays.Truncation t = PlaybookGenericRowArrays.truncateIfNeeded(rows, maxRows,
                "normalize_resolved_things");
        for (int i = 0; i < gaps.length(); i++) {
            t.gaps.put(gaps.get(i));
        }

        JSONObject output = new JSONObject();
        output.put("rows", t.rows);
        output.put("gaps", t.gaps);
        output.put("totalCount", totalCount);
        output.put("returned", t.returned);

        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", output);
        PlaybookNodeEvidence.attach(out, PlaybookNodeEvidence.singleLine(
                "normalize_resolved_things: resolved=" + resolvedCount + "; gaps=" + gaps.length() + "; returned="
                        + t.returned));
        return out;
    }

    private static JSONObject extractFromToolOutput(JSONObject args, PlaybookRunContext ctx)
            throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("extract_from_tool_output: missing args");
        }
        String sourceNodeId = args.optString("sourceNodeId", "").trim();
        if (sourceNodeId.isEmpty()) {
            throw new PlaybookRunException("extract_from_tool_output: missing sourceNodeId");
        }
        String mode = args.optString("mode", "").trim();
        if (!"single".equals(mode) && !"fan_out_children".equals(mode)) {
            throw new PlaybookRunException("extract_from_tool_output: mode must be single or fan_out_children");
        }
        String arrayPath = args.optString("arrayPath", "").trim();
        if (!PlaybookOrchestrationPath.isValidArrayPath(arrayPath)) {
            throw new PlaybookRunException("extract_from_tool_output: invalid arrayPath", "ORCHESTRATION_PATH_INVALID");
        }
        JSONObject where = args.optJSONObject("where");
        if (where != null && where.length() > 0) {
            PlaybookRowPredicate.requireValidShape(where);
        }
        JSONArray fields = args.optJSONArray("fields");
        if (fields == null || fields.length() == 0) {
            throw new PlaybookRunException("extract_from_tool_output: fields must be a non-empty array");
        }
        boolean fanOutChildren = "fan_out_children".equals(mode);
        validateFieldSpecs(fields, fanOutChildren);
        String onUnresolved = args.optString("onUnresolved", "gap").trim();
        if (!"gap".equals(onUnresolved) && !"clarify".equals(onUnresolved)) {
            throw new PlaybookRunException("extract_from_tool_output: onUnresolved must be gap or clarify");
        }
        int maxRows = requiredPositiveInt(args, "maxRows", "extract_from_tool_output");

        JSONArray outRows = new JSONArray();
        JSONArray gaps = new JSONArray();

        JSONObject nodeOut = ctx.nodeOutput(sourceNodeId);
        if (nodeOut == null) {
            throw new PlaybookRunException("extract_from_tool_output: missing output for " + sourceNodeId);
        }

        if ("single".equals(mode)) {
            JSONObject toolOut = nodeOut.optJSONObject("toolOutput");
            if (toolOut == null) {
                if ("clarify".equals(onUnresolved)) {
                    return needsClarification("No toolOutput on source node " + sourceNodeId + ".", null);
                }
                gaps.put(extractGap(null, "missing_tool_output", arrayPath, null, sourceNodeId));
            } else {
                extractFromToolOutputRoot(toolOut, null, arrayPath, where, fields, ctx, outRows, gaps, onUnresolved,
                        sourceNodeId);
            }
        } else {
            JSONArray children = nodeOut.optJSONArray("children");
            if (children == null) {
                throw new PlaybookRunException("extract_from_tool_output: fan_out has no children array",
                        "GENERIC_INPUT_INVALID");
            }
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                if (child == null || !"ok".equals(child.optString("status", ""))) {
                    gaps.put(extractGap(child, "child_failed", arrayPath, i, sourceNodeId));
                    continue;
                }
                JSONObject toolOut = child.optJSONObject("toolOutput");
                if (toolOut == null) {
                    gaps.put(extractGap(child, "missing_tool_output", arrayPath, i, sourceNodeId));
                    continue;
                }
                extractFromToolOutputRoot(toolOut, child, arrayPath, where, fields, ctx, outRows, gaps, onUnresolved,
                        sourceNodeId);
            }
        }

        if ("clarify".equals(onUnresolved) && gaps.length() > 0) {
            return needsClarification("Could not extract all required rows from tool output.", null);
        }

        int totalCount = outRows.length();

        PlaybookGenericRowArrays.Truncation t = PlaybookGenericRowArrays.truncateIfNeeded(outRows, maxRows,
                "extract_from_tool_output");
        for (int i = 0; i < gaps.length(); i++) {
            t.gaps.put(gaps.get(i));
        }

        JSONObject output = new JSONObject();
        output.put("rows", t.rows);
        output.put("gaps", t.gaps);
        output.put("totalCount", totalCount);
        output.put("returned", t.returned);

        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", output);
        PlaybookNodeEvidence.attach(out, PlaybookNodeEvidence.singleLine(
                "extract_from_tool_output: mode=" + mode + "; extracted=" + totalCount + "; returned=" + t.returned));
        return out;
    }

    private static void extractFromToolOutputRoot(
            JSONObject toolOut,
            JSONObject parentEntry,
            String arrayPath,
            JSONObject where,
            JSONArray fields,
            PlaybookRunContext ctx,
            JSONArray outRows,
            JSONArray gaps,
            String onUnresolved,
            String sourceNodeId) throws PlaybookRunException {
        int rowsBefore = outRows.length();
        Object atPath;
        try {
            atPath = PlaybookOrchestrationPath.navigateFromRoot(toolOut, arrayPath);
        } catch (PlaybookRunException e) {
            gaps.put(extractGap(parentEntry, "path_error", arrayPath, null, sourceNodeId));
            return;
        }
        JSONArray array;
        try {
            array = PlaybookOrchestrationPath.requireArray(atPath, "extract_from_tool_output");
        } catch (PlaybookRunException e) {
            gaps.put(extractGap(parentEntry, "not_array", arrayPath, null, sourceNodeId));
            return;
        }
        if (array.length() == 0) {
            gaps.put(extractGap(parentEntry, "empty_array", arrayPath, null, sourceNodeId));
            return;
        }
        boolean hasWhere = where != null && where.length() > 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject el = array.optJSONObject(i);
            if (el == null) {
                gaps.put(extractGap(parentEntry, "non_object_element", arrayPath, i, sourceNodeId));
                continue;
            }
            if (hasWhere && !PlaybookRowPredicate.evaluateWithoutShapeCheck(where, el, ctx)) {
                continue;
            }
            try {
                JSONObject row = projectFields(el, parentEntry, fields);
                if (row.length() > 0) {
                    outRows.put(row);
                }
            } catch (PlaybookRunException e) {
                if ("clarify".equals(onUnresolved)) {
                    throw e;
                }
                gaps.put(extractGap(parentEntry, "projection_error", arrayPath, i, sourceNodeId));
            }
        }
        int rowsAdded = outRows.length() - rowsBefore;
        if (rowsAdded == 0) {
            gaps.put(extractGap(parentEntry, hasWhere ? "no_where_match" : "no_projected_rows", arrayPath, null,
                    sourceNodeId));
        }
    }

    private static JSONObject projectFields(JSONObject arrayElement, JSONObject parentEntry, JSONArray fields)
            throws PlaybookRunException {
        JSONObject row = new JSONObject();
        for (int i = 0; i < fields.length(); i++) {
            JSONObject spec = fields.optJSONObject(i);
            if (spec == null) {
                continue;
            }
            String from = spec.optString("from", "").trim();
            String as = spec.optString("as", "").trim();
            if (from.isEmpty() || as.isEmpty()) {
                throw new PlaybookRunException("extract_from_tool_output: field spec requires from and as",
                        "GENERIC_INPUT_INVALID");
            }
            Object raw = PlaybookOrchestrationPath.navigateFieldFrom(arrayElement, parentEntry, from);
            Object coerced = coerceFieldValue(raw, spec.optString("type", "").trim());
            if (coerced != null && coerced != JSONObject.NULL) {
                row.put(as, coerced);
            }
        }
        return row;
    }

    private static Object coerceFieldValue(Object raw, String type) throws PlaybookRunException {
        if (raw == null || raw == JSONObject.NULL) {
            return JSONObject.NULL;
        }
        if ("number".equals(type)) {
            if (raw instanceof Number) {
                return raw;
            }
            try {
                return Double.parseDouble(String.valueOf(raw));
            } catch (NumberFormatException e) {
                throw new PlaybookRunException("extract_from_tool_output: expected number, got " + raw,
                        "GENERIC_INPUT_INVALID");
            }
        }
        return raw;
    }

    private static void validateFieldSpecs(JSONArray fields, boolean fanOutChildren) throws PlaybookRunException {
        java.util.Set<String> seenAs = new java.util.LinkedHashSet<>();
        for (int i = 0; i < fields.length(); i++) {
            JSONObject spec = fields.optJSONObject(i);
            if (spec == null) {
                throw new PlaybookRunException("extract_from_tool_output: fields[" + i + "] must be an object",
                        "GENERIC_INPUT_INVALID");
            }
            String from = spec.optString("from", "").trim();
            if (!PlaybookOrchestrationPath.isValidFieldFrom(from, fanOutChildren)) {
                throw new PlaybookRunException("extract_from_tool_output: invalid fields[" + i + "].from",
                        "ORCHESTRATION_PATH_INVALID");
            }
            String as = spec.optString("as", "").trim();
            if (as.isEmpty() || !as.matches("[A-Za-z][A-Za-z0-9_-]*")
                    || PlaybookGenericOpsConstants.isReservedOutputField(as)) {
                throw new PlaybookRunException("extract_from_tool_output: invalid fields[" + i + "].as",
                        "GENERIC_INPUT_INVALID");
            }
            if (!seenAs.add(as)) {
                throw new PlaybookRunException("extract_from_tool_output: duplicate fields[" + i + "].as \"" + as + "\"",
                        "GENERIC_INPUT_INVALID");
            }
        }
    }

    private static JSONObject extractGap(
            JSONObject parentEntry,
            String code,
            String arrayPath,
            Integer childIndex,
            String sourceNodeId) {
        JSONObject g = PlaybookGapObjects.structured(code, gapMessage(code, parentEntry, arrayPath, childIndex, sourceNodeId));
        g.put("arrayPath", arrayPath);
        g.put("sourceNodeId", sourceNodeId);
        if (childIndex != null) {
            g.put("childIndex", childIndex);
        }
        JSONObject item = parentEntry != null ? parentEntry.optJSONObject("item") : null;
        g.put("input", PlaybookResolveThingEnvelope.inputFromItem(item, "input"));
        return g;
    }

    private static JSONObject gapJson(String input, String code) {
        return PlaybookGapObjects.withContext(code, gapMessage(code, null, "", null, ""),
                new JSONObject().put("input", input != null ? input : ""));
    }

    private static String gapMessage(String code, JSONObject parentEntry, String arrayPath, Integer childIndex,
            String sourceNodeId) {
        if ("child_not_object".equals(code)) {
            return "Fan-out child is not an object";
        }
        if ("child_failed".equals(code)) {
            return "Fan-out child failed";
        }
        if ("clarify".equals(code)) {
            return "Unresolved fan-out child";
        }
        if ("missing_tool_output".equals(code)) {
            return "Missing tool output during extraction";
        }
        if ("path_error".equals(code)) {
            return "Path error at " + arrayPath;
        }
        if ("not_array".equals(code)) {
            return "Expected array at " + arrayPath;
        }
        if ("empty_array".equals(code)) {
            return "Empty array at " + arrayPath;
        }
        if ("non_object_element".equals(code)) {
            return "Non-object element in " + arrayPath;
        }
        if ("projection_error".equals(code)) {
            return "Projection error in " + arrayPath;
        }
        if ("no_where_match".equals(code)) {
            return "No rows matched filter at " + arrayPath;
        }
        if ("no_projected_rows".equals(code)) {
            return "No projected rows at " + arrayPath;
        }
        return code.replace('_', ' ');
    }

    private static String inputLabel(String input) {
        return input != null && !input.isBlank() ? "\"" + input + "\"" : "item";
    }

    private static int requiredPositiveInt(JSONObject args, String key, String op) throws PlaybookRunException {
        if (!args.has(key)) {
            throw new PlaybookRunException(op + ": missing " + key);
        }
        Object v = args.opt(key);
        if (!(v instanceof Number)) {
            throw new PlaybookRunException(op + ": " + key + " must be a number");
        }
        double d = ((Number) v).doubleValue();
        if (d < 1 || d != Math.rint(d)) {
            throw new PlaybookRunException(op + ": " + key + " must be an integer >= 1");
        }
        return (int) d;
    }

    private static int intArg(JSONObject args, String key, int defaultValue) throws PlaybookRunException {
        if (!args.has(key)) {
            return defaultValue;
        }
        Object v = args.opt(key);
        if (!(v instanceof Number)) {
            throw new PlaybookRunException("invalid " + key);
        }
        double d = ((Number) v).doubleValue();
        if (d < 0 || d != Math.rint(d)) {
            throw new PlaybookRunException(key + " must be a non-negative integer");
        }
        return (int) d;
    }

    private static JSONObject needsClarification(String message, JSONArray candidates) {
        JSONObject out = new JSONObject();
        out.put("status", "needs_clarification");
        out.put("message", message);
        if (candidates != null && candidates.length() > 0) {
            out.put("candidates", candidates);
        }
        return out;
    }
}
