package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Generic {@code collect_gaps} derive op (see {@code docs/agent/playbook-generic-ops-foundation.md} section 8.10).
 * Package-private; invoked from {@link PlaybookGenericDeriveOps}.
 * <p>
 * Structured gaps use a canonical key order for {@link #structuredDedupeKey} only; emitted
 * {@link JSONObject} payloads follow {@code org.json} map iteration rules (not guaranteed alphabetical).
 */
final class PlaybookGenericCollectGaps {

    private static final Set<String> STRUCT_KEYS = Set.of("code", "detail", "message");
    private static final String[] STRUCT_FIELD_ORDER = {"code", "detail", "message"};

    private PlaybookGenericCollectGaps() {}

    static JSONObject execute(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("collect_gaps: missing args");
        }
        JSONArray refs = args.optJSONArray("refs");
        if (refs == null || refs.length() == 0) {
            throw new PlaybookRunException("collect_gaps: refs must be a non-empty array");
        }
        if (!args.has("maxItems")) {
            throw new PlaybookRunException("collect_gaps: maxItems is required");
        }
        Object miObj = args.opt("maxItems");
        if (!(miObj instanceof Number)) {
            throw new PlaybookRunException("collect_gaps: maxItems must be a number");
        }
        double mid = ((Number) miObj).doubleValue();
        if (mid < 1 || mid != Math.rint(mid) || mid > PlaybookGenericOpsConstants.MAX_COLLECT_GAPS_ITEMS) {
            throw new PlaybookRunException(
                    "collect_gaps: maxItems must be an integer from 1 to "
                            + PlaybookGenericOpsConstants.MAX_COLLECT_GAPS_ITEMS);
        }
        int maxItems = ((Number) miObj).intValue();

        List<Object> merged = new ArrayList<>();
        LinkedHashSet<String> dedupeKeys = new LinkedHashSet<>();

        for (int ri = 0; ri < refs.length(); ri++) {
            Object refEl = refs.get(ri);
            if (!(refEl instanceof String)) {
                throw new PlaybookRunException("collect_gaps: refs[" + ri + "] must be a JSON string",
                        "GENERIC_INPUT_INVALID");
            }
            String refPath = ((String) refEl).trim();
            if (refPath.isEmpty()) {
                throw new PlaybookRunException("collect_gaps: refs[" + ri + "] must be non-blank",
                        "GENERIC_INPUT_INVALID");
            }
            Object resolved =
                    PlaybookExpressionResolver.resolve(new JSONObject().put("$ref", refPath), ctx);
            if (!(resolved instanceof JSONArray)) {
                throw new PlaybookRunException(
                        "collect_gaps: refs[" + ri + "] must resolve to a JSON array, got "
                                + (resolved == null ? "null" : resolved.getClass().getSimpleName()),
                        "GENERIC_INPUT_INVALID");
            }
            JSONArray arr = (JSONArray) resolved;
            if (arr.length() > PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS) {
                throw new PlaybookRunException(
                        "collect_gaps: refs[" + ri + "] array exceeds cap "
                                + PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS,
                        "GENERIC_INPUT_TOO_LARGE");
            }
            for (int ii = 0; ii < arr.length(); ii++) {
                Object item = arr.get(ii);
                GapEntry ge = normalizeEntry(item, ri, ii);
                if (dedupeKeys.add(ge.dedupeKey)) {
                    merged.add(ge.payload);
                }
            }
        }

        int logical = merged.size();
        int take = Math.min(logical, maxItems);
        JSONArray outGaps = new JSONArray();
        for (int i = 0; i < take; i++) {
            outGaps.put(merged.get(i));
        }

        int returned = outGaps.length();
        JSONObject output = new JSONObject().put("gaps", outGaps).put("totalCount", logical).put("returned", returned);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        String ev = "collect_gaps: collected " + take + " merged gap entr" + (take == 1 ? "y" : "ies");
        if (logical > take) {
            ev += " (truncated from " + logical + ")";
        }
        ev += ".";
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev));
        return result;
    }

    private static final class GapEntry {
        final String dedupeKey;
        final Object payload;

        GapEntry(String dedupeKey, Object payload) {
            this.dedupeKey = dedupeKey;
            this.payload = payload;
        }
    }

    private static GapEntry normalizeEntry(Object item, int refIndex, int itemIndex) throws PlaybookRunException {
        if (item == null || item == JSONObject.NULL) {
            throw new PlaybookRunException(
                    "collect_gaps: null entry at refs[" + refIndex + "][" + itemIndex + "]", "GENERIC_INPUT_INVALID");
        }
        if (item instanceof String) {
            String s = ((String) item).trim();
            if (s.isEmpty()) {
                throw new PlaybookRunException(
                        "collect_gaps: blank string gap at refs[" + refIndex + "][" + itemIndex + "]",
                        "GENERIC_INPUT_INVALID");
            }
            if (s.length() > PlaybookGenericOpsConstants.MAX_GAP_TEXT_CHARS) {
                throw new PlaybookRunException(
                        "collect_gaps: string gap exceeds " + PlaybookGenericOpsConstants.MAX_GAP_TEXT_CHARS
                                + " chars at refs[" + refIndex + "][" + itemIndex + "]",
                        "GENERIC_INPUT_INVALID");
            }
            return new GapEntry("s:" + s, s);
        }
        if (item instanceof JSONObject) {
            JSONObject canonical = canonicalStructuredGap((JSONObject) item, refIndex, itemIndex);
            return new GapEntry("o:" + structuredDedupeKey(canonical), canonical);
        }
        throw new PlaybookRunException(
                "collect_gaps: entry must be string or flat object at refs[" + refIndex + "][" + itemIndex + "]",
                "GENERIC_INPUT_INVALID");
    }

    private static JSONObject canonicalStructuredGap(JSONObject in, int refIndex, int itemIndex)
            throws PlaybookRunException {
        if (in.length() == 0) {
            throw new PlaybookRunException(
                    "collect_gaps: structured gap must not be empty at refs[" + refIndex + "][" + itemIndex + "]",
                    "GENERIC_INPUT_INVALID");
        }
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (String k : in.keySet()) {
            if (!STRUCT_KEYS.contains(k)) {
                throw new PlaybookRunException(
                        "collect_gaps: illegal key \"" + k + "\" at refs[" + refIndex + "][" + itemIndex + "]",
                        "GENERIC_INPUT_INVALID");
            }
            Object v = in.get(k);
            if (v == null || v == JSONObject.NULL) {
                throw new PlaybookRunException(
                        "collect_gaps: null value for \"" + k + "\" at refs[" + refIndex + "][" + itemIndex + "]",
                        "GENERIC_INPUT_INVALID");
            }
            if (v instanceof JSONArray || v instanceof JSONObject) {
                throw new PlaybookRunException(
                        "collect_gaps: nested value for \"" + k + "\" at refs[" + refIndex + "][" + itemIndex + "]",
                        "GENERIC_INPUT_INVALID");
            }
            if (v instanceof String) {
                String sv = ((String) v).trim();
                if (sv.length() > PlaybookGenericOpsConstants.MAX_GAP_TEXT_CHARS) {
                    throw new PlaybookRunException(
                            "collect_gaps: string field \"" + k + "\" exceeds "
                                    + PlaybookGenericOpsConstants.MAX_GAP_TEXT_CHARS + " chars",
                            "GENERIC_INPUT_INVALID");
                }
                sorted.put(k, sv);
            } else if (v instanceof Number) {
                sorted.put(k, v);
            } else {
                throw new PlaybookRunException(
                        "collect_gaps: unsupported value type for \"" + k + "\" at refs[" + refIndex + "]["
                                + itemIndex + "]",
                        "GENERIC_INPUT_INVALID");
            }
        }
        JSONObject out = new JSONObject();
        for (String k : STRUCT_FIELD_ORDER) {
            if (sorted.containsKey(k)) {
                out.put(k, sorted.get(k));
            }
        }
        return out;
    }

    private static String structuredDedupeKey(JSONObject canonical) {
        StringBuilder sb = new StringBuilder();
        for (String k : STRUCT_FIELD_ORDER) {
            if (!canonical.has(k)) {
                continue;
            }
            sb.append(k).append('\u0001');
            Object v = canonical.get(k);
            if (v instanceof Number) {
                sb.append("n:").append(((Number) v).doubleValue());
            } else {
                sb.append("t:").append(String.valueOf(v));
            }
            sb.append('\u0002');
        }
        return sb.toString();
    }
}
