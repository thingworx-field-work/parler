package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Slice E authoring helpers ({@code docs/agent/playbook-customer-readiness.md} §9).
 */
final class PlaybookAuthoringDeriveOps {

    private static final Set<String> OPS = Set.of(
            "normalize_text", "match_candidates", "dedupe", "limit_rows", "format_evidence_lines");

    private static final Pattern TEMPLATE_FIELD = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");

    private PlaybookAuthoringDeriveOps() {}

    static boolean supports(String op) {
        return op != null && OPS.contains(op);
    }

    static JSONObject execute(String op, JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if ("normalize_text".equals(op)) {
            return normalizeText(args, ctx);
        }
        if ("match_candidates".equals(op)) {
            return matchCandidates(args, ctx);
        }
        if ("dedupe".equals(op)) {
            return dedupe(args, ctx);
        }
        if ("limit_rows".equals(op)) {
            return limitRows(args, ctx);
        }
        if ("format_evidence_lines".equals(op)) {
            return formatEvidenceLines(args, ctx);
        }
        throw new PlaybookRunException("unsupported authoring derive op: " + op);
    }

    static String normalizeTextValue(String text, String mode) {
        String s = text != null ? text : "";
        String m = mode != null ? mode.trim().toLowerCase(Locale.ROOT) : "identifier";
        if ("trim".equals(m)) {
            return s.trim();
        }
        if ("lower".equals(m)) {
            return s.toLowerCase(Locale.ROOT);
        }
        if ("collapse_ws".equals(m)) {
            return collapseWhitespace(s.trim());
        }
        if ("identifier".equals(m)) {
            return collapseWhitespace(s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " "));
        }
        return collapseWhitespace(s.trim().toLowerCase(Locale.ROOT));
    }

    private static JSONObject normalizeText(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("normalize_text: missing args");
        }
        Object raw = PlaybookExpressionResolver.resolve(args.opt("text"), ctx);
        String original = raw == null || raw == JSONObject.NULL ? "" : String.valueOf(raw);
        String mode = args.optString("mode", "identifier").trim();
        String normalized = normalizeTextValue(original, mode);
        JSONObject output = new JSONObject().put("text", normalized).put("original", original).put("mode", mode);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        PlaybookNodeEvidence.attachLinesOnly(result,
                PlaybookNodeEvidence.singleLine("normalize_text: mode=" + mode + "; len=" + normalized.length() + "."));
        return result;
    }

    private static JSONObject matchCandidates(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("match_candidates: missing args");
        }
        Object needleObj = PlaybookExpressionResolver.resolve(args.opt("needle"), ctx);
        String needle = needleObj == null || needleObj == JSONObject.NULL ? "" : String.valueOf(needleObj);
        boolean normalize = args.optBoolean("normalize", true);
        String compareNeedle = normalize ? normalizeTextValue(needle, "identifier") : needle.trim();
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "match_candidates");
        String field = args.optString("candidateField", "name").trim();
        if (field.isEmpty()) {
            field = "name";
        }
        if (!PlaybookGenericPathGrammar.isSingleSegmentField(field)) {
            throw new PlaybookRunException("match_candidates: candidateField must be a single segment");
        }
        String onZero = normalizePickMode(args.optString("onZero", "needs_clarification"), "onZero");
        String onMultiple = normalizePickMode(args.optString("onMultiple", "needs_clarification"), "onMultiple");
        String label = args.optString("label", "candidate").trim();
        if (label.isEmpty()) {
            label = "candidate";
        }
        List<JSONObject> matches = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            Object cv = row.opt(field);
            String candidate = cv == null || cv == JSONObject.NULL ? "" : String.valueOf(cv);
            String compare = normalize ? normalizeTextValue(candidate, "identifier") : candidate.trim();
            if (!compareNeedle.isEmpty() && compareNeedle.equals(compare)) {
                matches.add(row);
            }
        }
        int m = matches.size();
        if (m == 1) {
            JSONObject row = shallowCopyRow(matches.get(0));
            JSONObject output = new JSONObject()
                    .put("row", row)
                    .put("matchCount", 1)
                    .put("totalCount", rows.length())
                    .put("gaps", new JSONArray());
            JSONObject result = new JSONObject().put("status", "ok").put("output", output);
            PlaybookNodeEvidence.attachLinesOnly(result,
                    PlaybookNodeEvidence.singleLine("match_candidates: matched 1 " + label + "."));
            return result;
        }
        if (m == 0) {
            return pickOutcome(onZero, "match_candidates: no " + label + " matched.",
                    "NO_MATCH", outputWithGaps(rows.length(), "No candidate matched needle."), label);
        }
        if (m > 1) {
            if ("first".equals(onMultiple)) {
                JSONObject row = shallowCopyRow(matches.get(0));
                JSONArray gaps = new JSONArray().put(PlaybookGapObjects.structured("AMBIGUOUS_MATCH",
                        "Multiple candidates matched; took first of " + m + "."));
                JSONObject output = new JSONObject()
                        .put("row", row)
                        .put("matchCount", m)
                        .put("totalCount", rows.length())
                        .put("gaps", gaps);
                JSONObject result = new JSONObject().put("status", "ok").put("output", output);
                PlaybookNodeEvidence.attachLinesOnly(result,
                        PlaybookNodeEvidence.singleLine("match_candidates: ambiguous (" + m + "); took first."));
                return result;
            }
            JSONArray candidates = new JSONArray();
            int cap = Math.min(m, 8);
            for (int i = 0; i < cap; i++) {
                candidates.put(shallowCopyRow(matches.get(i)));
            }
            return pickOutcome(onMultiple, "match_candidates: " + m + " " + label + " candidates matched.",
                    "AMBIGUOUS_MATCH",
                    new JSONObject().put("matchCount", m).put("totalCount", rows.length())
                            .put("candidates", candidates)
                            .put("gaps", new JSONArray().put(PlaybookGapObjects.structured("AMBIGUOUS_MATCH",
                                    m + " candidates matched."))),
                    label);
        }
        throw new PlaybookRunException("match_candidates: unreachable");
    }

    private static JSONObject dedupe(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("dedupe: missing args");
        }
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "dedupe");
        JSONArray keys = args.optJSONArray("keys");
        if (keys == null || keys.length() == 0) {
            throw new PlaybookRunException("dedupe: keys must be a non-empty array");
        }
        List<String> keyFields = new ArrayList<>();
        for (int i = 0; i < keys.length(); i++) {
            String k = keys.optString(i, "").trim();
            if (k.isEmpty()) {
                throw new PlaybookRunException("dedupe: keys[" + i + "] must be non-blank");
            }
            keyFields.add(k);
        }
        JSONArray outRows = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        int removed = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String sig = compositeKey(row, keyFields);
            if (seen.add(sig)) {
                outRows.put(shallowCopyRow(row));
            } else {
                removed++;
            }
        }
        JSONArray gaps = new JSONArray();
        if (removed > 0) {
            gaps.put("dedupe removed " + removed + " duplicate row(s) by " + keyFields);
        }
        int produced = outRows.length();
        JSONObject output = new JSONObject()
                .put("rows", outRows)
                .put("totalCount", produced)
                .put("returned", produced)
                .put("logicalCount", rows.length())
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        PlaybookNodeEvidence.attachLinesOnly(result,
                PlaybookNodeEvidence.singleLine("dedupe: " + rows.length() + " -> " + produced + " row(s)."));
        return result;
    }

    private static JSONObject limitRows(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("limit_rows: missing args");
        }
        if (!args.has("maxRows")) {
            throw new PlaybookRunException("limit_rows: maxRows is required");
        }
        Object mr = args.opt("maxRows");
        if (!(mr instanceof Number)) {
            throw new PlaybookRunException("limit_rows: maxRows must be a number");
        }
        double mrd = ((Number) mr).doubleValue();
        if (mrd < 0 || mrd != Math.rint(mrd) || mrd > PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS) {
            throw new PlaybookRunException("limit_rows: maxRows must be an integer from 0 to "
                    + PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS);
        }
        int maxRows = ((Number) mr).intValue();
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "limit_rows");
        int logical = rows.length();
        int take = Math.min(maxRows, logical);
        JSONArray outRows = new JSONArray();
        for (int i = 0; i < take; i++) {
            outRows.put(shallowCopyRow(rows.getJSONObject(i)));
        }
        JSONArray gaps = new JSONArray();
        if (logical > take) {
            gaps.put("limit_rows truncated from " + logical + " to " + take + " row(s)");
        }
        JSONObject output = new JSONObject()
                .put("rows", outRows)
                .put("totalCount", logical)
                .put("returned", take)
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        PlaybookNodeEvidence.attachLinesOnly(result,
                PlaybookNodeEvidence.singleLine("limit_rows: returned " + take + " of " + logical + " row(s)."));
        return result;
    }

    private static JSONObject formatEvidenceLines(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("format_evidence_lines: missing args");
        }
        String template = args.optString("template", "").trim();
        if (template.isEmpty()) {
            throw new PlaybookRunException("format_evidence_lines: template is required");
        }
        int maxLines = args.optInt("maxLines", PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS);
        if (maxLines < 1 || maxLines > PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS) {
            throw new PlaybookRunException("format_evidence_lines: maxLines must be from 1 to "
                    + PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS);
        }
        JSONArray lines = new JSONArray();
        int logical = 0;
        if (args.has("rows")) {
            JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "format_evidence_lines");
            logical = rows.length();
            int take = Math.min(maxLines, logical);
            for (int i = 0; i < take; i++) {
                lines.put(applyTemplate(template, rows.getJSONObject(i)));
            }
        } else if (args.has("object")) {
            Object obj = PlaybookExpressionResolver.resolve(args.opt("object"), ctx);
            if (obj instanceof JSONObject) {
                logical = 1;
                lines.put(applyTemplate(template, (JSONObject) obj));
            } else {
                throw new PlaybookRunException("format_evidence_lines: object must resolve to a JSON object");
            }
        } else {
            throw new PlaybookRunException("format_evidence_lines: rows or object is required");
        }
        JSONArray gaps = new JSONArray();
        if (logical > lines.length()) {
            gaps.put("format_evidence_lines truncated to " + lines.length() + " line(s)");
        }
        JSONObject output = new JSONObject()
                .put("lines", lines)
                .put("totalCount", logical)
                .put("returned", lines.length())
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        PlaybookNodeEvidence.attach(result, lines);
        return result;
    }

    private static String applyTemplate(String template, JSONObject row) throws PlaybookRunException {
        Matcher m = TEMPLATE_FIELD.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String field = m.group(1);
            Object v = PlaybookJsonRowPath.getAtPath(row, field);
            String rep = v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String compositeKey(JSONObject row, List<String> keys) throws PlaybookRunException {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (!PlaybookGenericPathGrammar.isSingleSegmentField(k)) {
                throw new PlaybookRunException("dedupe: keys must be single-segment fields");
            }
            Object v = row.opt(k);
            sb.append(k).append('\u0001');
            sb.append(v == null || v == JSONObject.NULL ? "" : String.valueOf(v));
            sb.append('\u0002');
        }
        return sb.toString();
    }

    private static JSONObject outputWithGaps(int totalCount, String gapNote) {
        return new JSONObject()
                .put("matchCount", 0)
                .put("totalCount", totalCount)
                .put("gaps", new JSONArray().put(PlaybookGapObjects.structured("NO_MATCH", gapNote)));
    }

    private static JSONObject pickOutcome(String mode, String message, String gapCode, JSONObject output, String label)
            throws PlaybookRunException {
        if ("needs_clarification".equals(mode)) {
            JSONObject result = new JSONObject();
            result.put("status", "needs_clarification");
            result.put("message", message);
            result.put("output", output);
            return result;
        }
        if ("gap".equals(mode)) {
            JSONArray gaps = output.optJSONArray("gaps");
            if (gaps == null || gaps.length() == 0) {
                gaps = new JSONArray().put(PlaybookGapObjects.structured(gapCode, message));
                output.put("gaps", gaps);
            }
            JSONObject result = new JSONObject().put("status", "ok").put("output", output);
            PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(message));
            return result;
        }
        if ("empty".equals(mode)) {
            JSONObject result = new JSONObject().put("status", "ok").put("output", output.put("row", JSONObject.NULL));
            PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(message));
            return result;
        }
        throw new PlaybookRunException("match_candidates: invalid mode for " + label);
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
        throw new PlaybookRunException("match_candidates: invalid " + fieldName + " value");
    }

    private static String collapseWhitespace(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ");
    }

    private static JSONObject shallowCopyRow(JSONObject row) {
        return new JSONObject(row.toString());
    }
}
