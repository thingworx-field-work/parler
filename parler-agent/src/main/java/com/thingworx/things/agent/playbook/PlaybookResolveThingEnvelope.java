package com.thingworx.things.agent.playbook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Classifies a {@code resolve_thing} {@code toolOutput} envelope for plural normalization.
 * See {@code docs/agent/playbook-34-35.md} §6.1.
 */
final class PlaybookResolveThingEnvelope {

    enum Kind {
        ROW,
        GAP,
        CLARIFY
    }

    static final class Classification {
        final Kind kind;
        final JSONObject row;
        final JSONObject gap;
        final String clarifyMessage;
        final JSONArray candidates;

        private Classification(Kind kind, JSONObject row, JSONObject gap, String clarifyMessage, JSONArray candidates) {
            this.kind = kind;
            this.row = row;
            this.gap = gap;
            this.clarifyMessage = clarifyMessage;
            this.candidates = candidates;
        }

        static Classification row(JSONObject row) {
            return new Classification(Kind.ROW, row, null, null, null);
        }

        static Classification gap(JSONObject gap) {
            return new Classification(Kind.GAP, null, gap, null, null);
        }

        static Classification clarify(String message, JSONArray candidates) {
            return new Classification(Kind.CLARIFY, null, null, message, candidates);
        }
    }

    private PlaybookResolveThingEnvelope() {}

    static String inputFromItem(JSONObject item, String inputField) {
        if (item == null) {
            return "";
        }
        String primary = inputField != null && !inputField.isBlank() ? inputField : "input";
        String v = item.optString(primary, "").trim();
        if (v.isEmpty() && !"region".equals(primary)) {
            v = item.optString("region", "").trim();
        }
        return v;
    }

    static Classification classify(JSONObject toolOut, String input, String inputField) {
        if (toolOut == null) {
            return Classification.gap(gapEntry(input, inputField, "missing_tool_output", null));
        }
        String status = toolOut.optString("status", "").trim();
        if ("error".equalsIgnoreCase(status)) {
            return classifyError(toolOut, input, inputField);
        }
        if (!"success".equalsIgnoreCase(status)) {
            return Classification.clarify("Unexpected resolve_thing status \"" + status + "\" for " + label(input)
                    + ".", null);
        }
        String resultKind = toolOut.optString("resultKind", "").trim();
        if ("THING_RESOLVED_LARGE".equals(resultKind) || "TAXONOMY_ASSET_IDENTIFIER_LARGE".equals(resultKind)) {
            JSONArray sample = toolOut.optJSONArray("sampleMatches");
            return Classification.gap(gapEntry(input, inputField, "large", candidateNamesOnly(sample)));
        }
        JSONArray matches = toolOut.optJSONArray("matches");
        if (matches == null || matches.length() == 0) {
            return Classification.gap(gapEntry(input, inputField, "not_found", null));
        }
        if (matches.length() > 1) {
            return Classification.gap(gapEntry(input, inputField, "ambiguous",
                    candidateNamesOnly(matches)));
        }
        JSONObject m0 = matches.optJSONObject(0);
        if (m0 == null) {
            return Classification.gap(gapEntry(input, inputField, "invalid_match", null));
        }
        String name = m0.optString("name", "").trim();
        if (name.isEmpty()) {
            return Classification.gap(gapEntry(input, inputField, "missing_name", null));
        }
        JSONObject row = new JSONObject();
        row.put("input", input);
        row.put("name", name);
        String display = m0.optString("displayName", "").trim();
        row.put("displayName", display.isEmpty() ? input : display);
        row.put("resultKind", "thing");
        return Classification.row(row);
    }

    private static Classification classifyError(JSONObject toolOut, String input, String inputField) {
        String code = toolOut.optString("code", "").trim();
        JSONArray candidates = null;
        if ("IDENTITY_AMBIGUOUS".equals(code) || "ASSET_IDENTIFIER_AMBIGUOUS".equals(code)) {
            candidates = candidateNamesOnly(toolOut.optJSONArray("candidates"));
            return Classification.gap(gapEntry(input, inputField, "ambiguous", candidates));
        }
        if ("IDENTITY_NOT_FOUND".equals(code) || "ASSET_IDENTIFIER_NOT_FOUND".equals(code)) {
            return Classification.gap(gapEntry(input, inputField, "not_found", null));
        }
        if ("TAXONOMY_UNAVAILABLE".equals(code) || "ASSET_TYPES_NOT_CONFIGURED".equals(code)
                || "ASSET_TYPE_NOT_FOUND".equals(code)) {
            return Classification.gap(gapEntry(input, inputField, code.toLowerCase(), null));
        }
        return Classification.gap(gapEntry(input, inputField, code.isEmpty() ? "error" : code, null));
    }

    private static JSONObject gapEntry(String input, String inputField, String code, JSONArray candidates) {
        JSONObject g = PlaybookGapObjects.structured(code, gapMessage(code, input));
        g.put("input", input);
        if (candidates != null && candidates.length() > 0) {
            g.put("candidates", candidates);
        }
        return g;
    }

    private static String gapMessage(String code, String input) {
        String label = label(input);
        if ("ambiguous".equals(code)) {
            return "Multiple matches for " + label;
        }
        if ("not_found".equals(code)) {
            return "No match for " + label;
        }
        if ("large".equals(code)) {
            return "Too many matches for " + label;
        }
        if ("missing_tool_output".equals(code)) {
            return "Missing resolve_thing output for " + label;
        }
        if ("invalid_match".equals(code)) {
            return "Invalid match row for " + label;
        }
        if ("missing_name".equals(code)) {
            return "Resolved match missing name for " + label;
        }
        return code.isEmpty() ? "resolve_thing gap" : code.replace('_', ' ');
    }

    private static String label(String input) {
        return input != null && !input.isBlank() ? "\"" + input + "\"" : "identifier";
    }

    private static JSONArray candidateNamesOnly(JSONArray in) {
        JSONArray out = new JSONArray();
        if (in == null) {
            return out;
        }
        for (int i = 0; i < in.length(); i++) {
            JSONObject o = in.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String n = o.optString("name", "").trim();
            if (!n.isEmpty()) {
                out.put(new JSONObject().put("name", n));
            }
        }
        return out;
    }
}
