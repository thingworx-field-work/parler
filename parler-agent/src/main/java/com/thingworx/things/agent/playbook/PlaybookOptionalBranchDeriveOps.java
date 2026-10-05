package com.thingworx.things.agent.playbook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Optional-branch derive helpers (topic {@code playbook-34-35} Slice D).
 */
final class PlaybookOptionalBranchDeriveOps {

    private PlaybookOptionalBranchDeriveOps() {}

    static JSONObject emptyRowsIfSkipped(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("empty_rows_if_skipped: missing args");
        }
        String sourceNodeId = args.optString("sourceNodeId", "").trim();
        if (sourceNodeId.isEmpty()) {
            throw new PlaybookRunException("empty_rows_if_skipped: sourceNodeId is required");
        }
        String label = args.optString("label", sourceNodeId).trim();
        if (ctx.isSkipped(sourceNodeId)) {
            JSONObject output = new JSONObject()
                    .put("rows", new JSONArray())
                    .put("skipped", true)
                    .put("branch", sourceNodeId)
                    .put("label", label);
            JSONObject out = new JSONObject().put("status", "ok").put("output", output);
            PlaybookNodeEvidence.attachLinesOnly(out, PlaybookNodeEvidence.singleLine(
                    "empty_rows_if_skipped: branch \"" + sourceNodeId + "\" skipped; emitted 0 rows"));
            return out;
        }
        JSONObject nodeOut = ctx.nodeOutput(sourceNodeId);
        if (nodeOut == null) {
            throw new PlaybookRunException("empty_rows_if_skipped: missing output for " + sourceNodeId);
        }
        JSONObject outputObj = nodeOut.optJSONObject("output");
        JSONArray rows = outputObj != null ? outputObj.optJSONArray("rows") : null;
        if (rows == null) {
            Object resolved = PlaybookExpressionResolver.resolve(
                    new JSONObject().put("$ref", sourceNodeId + ".output.rows"), ctx);
            rows = PlaybookGenericRowArrays.coerceResolvedRowContainerToArray(resolved, "empty_rows_if_skipped");
        }
        JSONObject output = new JSONObject()
                .put("rows", rows)
                .put("skipped", false)
                .put("branch", sourceNodeId)
                .put("label", label);
        JSONObject out = new JSONObject().put("status", "ok").put("output", output);
        PlaybookNodeEvidence.attachLinesOnly(out, PlaybookNodeEvidence.singleLine(
                "empty_rows_if_skipped: branch \"" + sourceNodeId + "\" applied; rows=" + rows.length()));
        return out;
    }

    static JSONObject mergeRowSets(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("merge_row_sets: missing args");
        }
        JSONArray sources = args.optJSONArray("sources");
        if (sources == null || sources.length() == 0) {
            throw new PlaybookRunException("merge_row_sets: sources must be a non-empty array");
        }
        if (!args.has("maxSources")) {
            throw new PlaybookRunException("merge_row_sets: maxSources is required");
        }
        if (!args.has("maxRows")) {
            throw new PlaybookRunException("merge_row_sets: maxRows is required");
        }
        int maxSources = readPositiveIntCap(args, "maxSources", PlaybookGenericOpsConstants.MAX_MERGE_ROW_SETS_SOURCES);
        int maxRows = readPositiveIntCap(args, "maxRows", PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS);
        if (sources.length() > maxSources) {
            throw new PlaybookRunException(
                    "merge_row_sets: sources length " + sources.length() + " exceeds maxSources " + maxSources);
        }

        JSONArray sourceCounts = new JSONArray();
        JSONArray gaps = new JSONArray();
        JSONArray merged = new JSONArray();
        int totalCount = 0;

        for (int i = 0; i < sources.length(); i++) {
            JSONObject srcSpec = sources.optJSONObject(i);
            if (srcSpec == null || srcSpec.length() != 1 || !srcSpec.has("$ref")) {
                throw new PlaybookRunException(
                        "merge_row_sets: sources[" + i + "] must be exactly { \"$ref\": \"<nodeId>.<path>\" }");
            }
            Object resolved = PlaybookExpressionResolver.resolve(srcSpec, ctx);
            JSONArray rows = PlaybookGenericRowArrays.coerceResolvedRowContainerToArray(resolved, "merge_row_sets");
            if (rows.length() > PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS) {
                throw new PlaybookRunException(
                        "merge_row_sets: sources[" + i + "] exceeds cap "
                                + PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS,
                        "GENERIC_INPUT_TOO_LARGE");
            }
            PlaybookGenericRowArrays.requireEachSlotIsObject(rows, "merge_row_sets");
            int count = rows.length();
            sourceCounts.put(count);
            totalCount += count;
            if (count == 0) {
                gaps.put("merge_row_sets: source[" + i + "] empty (0 rows)");
            }
            for (int r = 0; r < rows.length(); r++) {
                merged.put(rows.getJSONObject(r));
            }
        }

        PlaybookGenericRowArrays.Truncation trunc =
                PlaybookGenericRowArrays.truncateIfNeeded(merged, maxRows, "merge_row_sets");
        for (int g = 0; g < trunc.gaps.length(); g++) {
            gaps.put(trunc.gaps.get(g));
        }

        JSONObject output = new JSONObject()
                .put("rows", trunc.rows)
                .put("sourceCounts", sourceCounts)
                .put("totalCount", totalCount)
                .put("returned", trunc.returned)
                .put("gaps", gaps);
        JSONObject out = new JSONObject().put("status", "ok").put("output", output);
        String ev = "merge_row_sets: merged " + sources.length() + " source(s); logical " + totalCount + " row(s); emitted "
                + trunc.returned + " row(s)";
        if (totalCount > trunc.returned) {
            ev += " (truncated to cap " + maxRows + ")";
        }
        PlaybookNodeEvidence.attachLinesOnly(out, PlaybookNodeEvidence.singleLine(ev));
        return out;
    }

    private static int readPositiveIntCap(JSONObject args, String key, int cap) throws PlaybookRunException {
        Object o = args.opt(key);
        if (!(o instanceof Number)) {
            throw new PlaybookRunException("merge_row_sets: " + key + " must be a number");
        }
        double d = ((Number) o).doubleValue();
        if (d < 1 || d != Math.rint(d) || d > cap) {
            throw new PlaybookRunException(
                    "merge_row_sets: " + key + " must be an integer from 1 to " + cap);
        }
        return (int) d;
    }
}
