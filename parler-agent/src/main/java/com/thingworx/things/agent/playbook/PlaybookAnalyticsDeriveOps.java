package com.thingworx.things.agent.playbook;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * P2 analytics derive ops for topic {@code playbook-34-35} Slice E (§6.9–§6.10).
 */
final class PlaybookAnalyticsDeriveOps {

    private PlaybookAnalyticsDeriveOps() {}

    static JSONObject addComputedFields(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("add_computed_fields: missing args");
        }
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "add_computed_fields");
        JSONArray fields = args.optJSONArray("fields");
        if (fields == null || fields.length() == 0) {
            throw new PlaybookRunException("add_computed_fields: fields must be a non-empty array");
        }
        String onNull = normalizeOnNull(args.optString("onNull", "null"));
        int maxRows = intCapArg(args, "maxRows", PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS,
                "add_computed_fields");

        JSONArray outRows = new JSONArray();
        JSONArray gaps = new JSONArray();
        int skippedRows = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject inRow = rows.getJSONObject(i);
            JSONObject outRow = shallowCopyRow(inRow);
            boolean skipRow = false;
            for (int j = 0; j < fields.length(); j++) {
                JSONObject spec = fields.optJSONObject(j);
                if (spec == null) {
                    throw new PlaybookRunException("add_computed_fields: fields[" + j + "] must be an object");
                }
                String as = spec.optString("as", "").trim();
                if (as.isEmpty() || PlaybookGenericOpsConstants.isReservedOutputField(as)) {
                    throw new PlaybookRunException("add_computed_fields: fields[" + j + "].as invalid");
                }
                JSONObject expr = spec.optJSONObject("expr");
                if (expr == null || expr.length() == 0) {
                    throw new PlaybookRunException("add_computed_fields: fields[" + j + "].expr required");
                }
                ComputedValue cv = evalExpr(expr, inRow, as);
                if (cv.ok) {
                    outRow.put(as, cv.value);
                } else if ("skip".equals(onNull)) {
                    skipRow = true;
                    break;
                } else if ("gap".equals(onNull)) {
                    gaps.put("add_computed_fields: " + cv.gapNote + " (row " + i + ", field " + as + ")");
                    outRow.put(as, JSONObject.NULL);
                } else {
                    outRow.put(as, JSONObject.NULL);
                }
            }
            if (skipRow) {
                skippedRows++;
                continue;
            }
            outRows.put(outRow);
        }

        PlaybookGenericRowArrays.Truncation t = PlaybookGenericRowArrays.truncateIfNeeded(outRows, maxRows,
                "add_computed_fields");
        for (int i = 0; i < gaps.length(); i++) {
            t.gaps.put(gaps.get(i));
        }
        JSONObject output = new JSONObject()
                .put("rows", t.rows)
                .put("totalCount", rows.length())
                .put("returned", t.returned)
                .put("gaps", t.gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        String ev = "add_computed_fields: input " + rows.length() + " row(s); emitted " + t.returned + " row(s); "
                + fields.length() + " field(s)";
        if (skippedRows > 0) {
            ev += "; skipped " + skippedRows + " row(s) (onNull=skip)";
        }
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev + "."));
        return result;
    }

    static JSONObject collectValues(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("collect_values: missing args");
        }
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "collect_values");
        String field = args.optString("field", "").trim();
        if (field.isEmpty() || !PlaybookGenericPathGrammar.isValidDottedPath(field)) {
            throw new PlaybookRunException("collect_values: field must be a non-blank dotted path");
        }
        int maxValues = intCapArg(args, "maxValues", PlaybookGenericOpsConstants.MAX_COLLECT_VALUES, "collect_values");

        Set<String> seen = new LinkedHashSet<>();
        JSONArray values = new JSONArray();
        JSONArray gaps = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            Object raw = PlaybookJsonRowPath.getAtPath(row, field);
            if (raw == null || raw == JSONObject.NULL) {
                continue;
            }
            String key = String.valueOf(raw);
            if (seen.add(key)) {
                if (values.length() >= maxValues) {
                    gaps.put("collect_values: truncated unique values at cap " + maxValues);
                    break;
                }
                values.put(raw instanceof Number ? raw : key);
            }
        }
        JSONObject output = new JSONObject()
                .put("values", values)
                .put("totalCount", values.length())
                .put("returned", values.length())
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(
                "collect_values: scanned " + rows.length() + " row(s); unique values=" + values.length() + "."));
        return result;
    }

    static JSONObject joinValues(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("join_values: missing args");
        }
        Object valuesObj = PlaybookExpressionResolver.resolve(args.opt("values"), ctx);
        JSONArray values = toArray(valuesObj, "join_values");
        String delimiter = args.optString("delimiter", ",");
        int maxLength = intCapArg(args, "maxLength", PlaybookGenericOpsConstants.MAX_JOIN_VALUE_CHARS, "join_values");

        StringBuilder sb = new StringBuilder();
        JSONArray gaps = new JSONArray();
        boolean truncated = false;
        for (int i = 0; i < values.length(); i++) {
            if (values.isNull(i)) {
                continue;
            }
            String part = String.valueOf(values.get(i));
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                if (sb.length() + delimiter.length() > maxLength) {
                    truncated = true;
                    gaps.put("join_values: truncated at maxLength " + maxLength);
                    break;
                }
                sb.append(delimiter);
            }
            if (sb.length() + part.length() > maxLength) {
                int room = maxLength - sb.length();
                if (room > 0) {
                    sb.append(part, 0, room);
                }
                truncated = true;
                gaps.put("join_values: truncated at maxLength " + maxLength);
                break;
            }
            sb.append(part);
        }
        JSONObject output = new JSONObject()
                .put("value", sb.toString())
                .put("truncated", truncated)
                .put("partCount", values.length())
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(
                "join_values: joined " + values.length() + " value(s); length=" + sb.length()
                        + (truncated ? " (truncated)" : "") + "."));
        return result;
    }

    private static final class ComputedValue {
        final boolean ok;
        final Object value;
        final String gapNote;

        ComputedValue(boolean ok, Object value, String gapNote) {
            this.ok = ok;
            this.value = value;
            this.gapNote = gapNote;
        }
    }

    private static ComputedValue evalExpr(JSONObject expr, JSONObject row, String as) throws PlaybookRunException {
        String op = expr.optString("op", "").trim().toLowerCase(Locale.ROOT);
        if ("datetime_diff_minutes".equals(op)) {
            String leftField = expr.optString("left", "").trim();
            String rightField = expr.optString("right", "").trim();
            if (leftField.isEmpty() || rightField.isEmpty()) {
                throw new PlaybookRunException("add_computed_fields: datetime_diff_minutes requires left and right");
            }
            Instant left = parseInstant(PlaybookJsonRowPath.getAtPath(row, leftField));
            Instant right = parseInstant(PlaybookJsonRowPath.getAtPath(row, rightField));
            if (left == null || right == null) {
                return new ComputedValue(false, null, "null datetime operand for " + as);
            }
            long minutes = Duration.between(right, left).toMinutes();
            return new ComputedValue(true, minutes, null);
        }
        if ("add".equals(op) || "sub".equals(op) || "mul".equals(op) || "div".equals(op)) {
            String leftField = expr.optString("left", "").trim();
            String rightField = expr.optString("right", "").trim();
            if (leftField.isEmpty() || rightField.isEmpty()) {
                throw new PlaybookRunException("add_computed_fields: " + op + " requires left and right");
            }
            Double left = toNumber(PlaybookJsonRowPath.getAtPath(row, leftField));
            Double right = toNumber(PlaybookJsonRowPath.getAtPath(row, rightField));
            if (left == null || right == null) {
                return new ComputedValue(false, null, "null numeric operand for " + as);
            }
            double result;
            switch (op) {
                case "add":
                    result = left + right;
                    break;
                case "sub":
                    result = left - right;
                    break;
                case "mul":
                    result = left * right;
                    break;
                default:
                    if (right == 0.0) {
                        return new ComputedValue(false, null, "division by zero for " + as);
                    }
                    result = left / right;
                    break;
            }
            return new ComputedValue(true, result, null);
        }
        throw new PlaybookRunException("add_computed_fields: unsupported expr op \"" + op + "\"");
    }

    private static Instant parseInstant(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return null;
        }
        if (raw instanceof Number) {
            return Instant.ofEpochMilli(((Number) raw).longValue());
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double toNumber(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JSONArray toArray(Object valuesObj, String op) throws PlaybookRunException {
        if (valuesObj instanceof JSONArray) {
            return (JSONArray) valuesObj;
        }
        if (valuesObj instanceof JSONObject) {
            JSONObject o = (JSONObject) valuesObj;
            if (o.has("values")) {
                Object inner = o.get("values");
                if (inner instanceof JSONArray) {
                    return (JSONArray) inner;
                }
            }
        }
        if (valuesObj == null || valuesObj == JSONObject.NULL) {
            return new JSONArray();
        }
        throw new PlaybookRunException(op + ": values must resolve to a JSON array");
    }

    private static String normalizeOnNull(String raw) throws PlaybookRunException {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("null".equals(s) || "skip".equals(s) || "gap".equals(s)) {
            return s;
        }
        throw new PlaybookRunException("add_computed_fields: onNull must be null, skip, or gap");
    }

    private static int intCapArg(JSONObject args, String key, int cap, String op) throws PlaybookRunException {
        if (!args.has(key)) {
            return cap;
        }
        Object v = args.opt(key);
        if (!(v instanceof Number)) {
            throw new PlaybookRunException(op + ": " + key + " must be a number");
        }
        double d = ((Number) v).doubleValue();
        if (d < 1 || d != Math.rint(d) || d > cap) {
            throw new PlaybookRunException(op + ": " + key + " must be an integer between 1 and " + cap);
        }
        return (int) d;
    }

    private static JSONObject shallowCopyRow(JSONObject inRow) {
        JSONObject o = new JSONObject();
        for (String key : inRow.keySet()) {
            o.put(key, inRow.get(key));
        }
        return o;
    }
}
