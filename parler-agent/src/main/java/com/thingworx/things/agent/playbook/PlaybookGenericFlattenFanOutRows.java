package com.thingworx.things.agent.playbook;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Concatenates {@code toolOutput.rows} from each successful child of a {@code fan_out} whose inner node is a
 * {@code tool_call}. Optional {@code injectFromItem} copies fields from the fan-out {@code item} into each emitted
 * row (same {@code from} / {@code as} shape as {@code project} field specs; {@code from} uses
 * {@link PlaybookJsonRowPath} on the item object). Bounded by {@link PlaybookGenericOpsConstants#MAX_GENERIC_INPUT_ROWS}
 * for the materialized {@code output.rows} array; {@code output.totalCount} counts all valid row objects that would
 * merge before the cap (§6.2).
 *
 * <p>See {@code docs/agent/playbook-generic-ops-foundation.md} §6.2 / §11 (reference path).
 */
final class PlaybookGenericFlattenFanOutRows {

    private static final Pattern OUTPUT_FIELD_AS = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    private PlaybookGenericFlattenFanOutRows() {}

    static JSONObject execute(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("flatten_fan_out_rows: missing args");
        }
        String fanOutId = args.optString("fanOutNodeId", "").trim();
        if (fanOutId.isEmpty()) {
            throw new PlaybookRunException("flatten_fan_out_rows: fanOutNodeId is required");
        }
        JSONArray injectSpecs = args.optJSONArray("injectFromItem");
        if (injectSpecs != null && injectSpecs.length() > 0) {
            validateRuntimeInjectFromItem(injectSpecs);
        }
        JSONObject fanOut = ctx.nodeOutput(fanOutId);
        if (fanOut == null) {
            throw new PlaybookRunException("flatten_fan_out_rows: missing output for node " + fanOutId);
        }
        JSONArray children = fanOut.optJSONArray("children");
        if (children == null) {
            throw new PlaybookRunException(
                    "flatten_fan_out_rows: node " + fanOutId + " has no children array", "GENERIC_INPUT_INVALID");
        }
        int cap = PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS;
        JSONArray outRows = new JSONArray();
        JSONArray gaps = new JSONArray();
        int logicalValid = 0;
        boolean truncated = false;
        for (int i = 0; i < children.length(); i++) {
            JSONObject ch = children.optJSONObject(i);
            if (ch == null) {
                gaps.put("flatten_fan_out_rows: child[" + i + "] is not an object");
                continue;
            }
            if (!"ok".equals(ch.optString("status", ""))) {
                gaps.put("flatten_fan_out_rows: child[" + i + "] status=" + ch.optString("status", ""));
                continue;
            }
            JSONObject item = ch.optJSONObject("item");
            JSONObject toolOut = ch.optJSONObject("toolOutput");
            JSONArray rows = toolOut != null ? toolOut.optJSONArray("rows") : null;
            if (rows == null || rows.length() == 0) {
                continue;
            }
            for (int r = 0; r < rows.length(); r++) {
                JSONObject row = rows.optJSONObject(r);
                if (row == null) {
                    gaps.put("flatten_fan_out_rows: child[" + i + "].rows[" + r + "] is not an object");
                    continue;
                }
                logicalValid++;
                if (outRows.length() < cap) {
                    JSONObject copy = shallowCopyRow(row);
                    applyInjectFromItem(copy, item, injectSpecs);
                    outRows.put(copy);
                } else {
                    truncated = true;
                }
            }
        }
        if (truncated) {
            gaps.put("flatten_fan_out_rows: truncated merged rows to cap " + cap);
        }
        int returned = outRows.length();
        JSONObject output = new JSONObject()
                .put("rows", outRows)
                .put("totalCount", logicalValid)
                .put("returned", returned)
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        String ev = "flatten_fan_out_rows: fanOut=" + fanOutId + "; logical " + logicalValid + " row(s); emitted "
                + returned + " row(s)";
        if (logicalValid > returned) {
            ev += " (truncated to cap " + cap + ")";
        }
        ev += " from " + children.length() + " child slot(s).";
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev));
        return result;
    }

    private static void validateRuntimeInjectFromItem(JSONArray injectSpecs) throws PlaybookRunException {
        Set<String> seenAs = new HashSet<>();
        for (int i = 0; i < injectSpecs.length(); i++) {
            JSONObject spec = injectSpecs.optJSONObject(i);
            if (spec == null) {
                throw new PlaybookRunException("flatten_fan_out_rows: injectFromItem[" + i + "] must be an object",
                        "GENERIC_INPUT_INVALID");
            }
            String from = spec.optString("from", "").trim();
            String as = spec.optString("as", "").trim();
            if (from.isEmpty() || as.isEmpty()) {
                throw new PlaybookRunException(
                        "flatten_fan_out_rows: injectFromItem[" + i + "] requires non-blank from and as",
                        "GENERIC_INPUT_INVALID");
            }
            if (!PlaybookGenericPathGrammar.isValidDottedPath(from)) {
                throw new PlaybookRunException(
                        "flatten_fan_out_rows: injectFromItem[" + i + "] from invalid path: " + from,
                        "GENERIC_INPUT_INVALID");
            }
            if (!OUTPUT_FIELD_AS.matcher(as).matches()) {
                throw new PlaybookRunException(
                        "flatten_fan_out_rows: injectFromItem[" + i + "] as invalid identifier: " + as,
                        "GENERIC_INPUT_INVALID");
            }
            if (PlaybookGenericOpsConstants.isReservedOutputField(as)) {
                throw new PlaybookRunException(
                        "flatten_fan_out_rows: injectFromItem[" + i + "] as is reserved: " + as,
                        "GENERIC_INPUT_INVALID");
            }
            if (!seenAs.add(as)) {
                throw new PlaybookRunException(
                        "flatten_fan_out_rows: injectFromItem duplicate as \"" + as + "\"", "GENERIC_INPUT_INVALID");
            }
        }
    }

    private static void applyInjectFromItem(JSONObject copy, JSONObject item, JSONArray injectSpecs)
            throws PlaybookRunException {
        if (injectSpecs == null || injectSpecs.length() == 0 || item == null) {
            return;
        }
        for (int s = 0; s < injectSpecs.length(); s++) {
            JSONObject spec = injectSpecs.optJSONObject(s);
            if (spec == null) {
                continue;
            }
            String from = spec.optString("from", "").trim();
            String as = spec.optString("as", "").trim();
            if (from.isEmpty() || as.isEmpty() || copy.has(as)) {
                continue;
            }
            Object v = PlaybookJsonRowPath.getAtPath(item, from);
            if (v != null && v != JSONObject.NULL) {
                copy.put(as, v);
            }
        }
    }

    private static JSONObject shallowCopyRow(JSONObject row) {
        JSONObject o = new JSONObject();
        for (String k : row.keySet()) {
            o.put(k, row.get(k));
        }
        return o;
    }
}
