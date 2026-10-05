package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Generic {@code build_targets} derive op (see {@code docs/agent/playbook-generic-ops-foundation.md} sections 6.4–6.5,
 * 8.7). Package-private; invoked from {@link PlaybookGenericDeriveOps}. Source nesting order is
 * ascending lexicographic order of {@code sources} keys (not {@link JSONObject} iteration order).
 */
final class PlaybookGenericBuildTargets {

    private PlaybookGenericBuildTargets() {}

    static JSONObject execute(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("build_targets: missing args");
        }
        JSONObject sourcesObj = args.optJSONObject("sources");
        if (sourcesObj == null || sourcesObj.length() == 0) {
            throw new PlaybookRunException("build_targets: sources must be a non-empty object");
        }
        JSONObject template = args.optJSONObject("template");
        if (template == null || template.length() == 0) {
            throw new PlaybookRunException("build_targets: template must be a non-empty object");
        }
        if (!args.has("maxTargets")) {
            throw new PlaybookRunException("build_targets: maxTargets is required");
        }
        Object mtObj = args.opt("maxTargets");
        if (!(mtObj instanceof Number)) {
            throw new PlaybookRunException("build_targets: maxTargets must be a number");
        }
        double mtd = ((Number) mtObj).doubleValue();
        if (mtd < 1 || mtd != Math.rint(mtd) || mtd > PlaybookGenericOpsConstants.MAX_GENERIC_TARGETS) {
            throw new PlaybookRunException(
                    "build_targets: maxTargets must be an integer from 1 to "
                            + PlaybookGenericOpsConstants.MAX_GENERIC_TARGETS);
        }
        int maxTargets = ((Number) mtObj).intValue();

        Map<String, JSONArray> sources = new HashMap<>();
        for (String name : sourcesObj.keySet()) {
            Object resolved = PlaybookExpressionResolver.resolve(sourcesObj.get(name), ctx);
            JSONArray rows = PlaybookGenericRowArrays.coerceResolvedRowContainerToArray(resolved, "build_targets");
            if (rows.length() > PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS) {
                throw new PlaybookRunException(
                        "build_targets: source \"" + name + "\" exceeds cap "
                                + PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS,
                        "GENERIC_INPUT_TOO_LARGE");
            }
            PlaybookGenericRowArrays.requireEachSlotIsObject(rows, "build_targets", "sources." + name);
            sources.put(name, rows);
        }
        /*
         * org.json.JSONObject key order is not insertion-ordered (HashMap-backed); §8.7 requires a
         * deterministic nesting order. Use ascending lexicographic order of source names (natural String order).
         */
        List<String> sourceOrder = new ArrayList<>(sources.keySet());
        Collections.sort(sourceOrder);

        boolean anySourceEmpty = false;
        for (String n : sourceOrder) {
            if (sources.get(n).length() == 0) {
                anySourceEmpty = true;
                break;
            }
        }

        long logicalLong = 1L;
        for (String n : sourceOrder) {
            int len = sources.get(n).length();
            if (len == 0) {
                logicalLong = 0L;
                break;
            }
            if (logicalLong > Long.MAX_VALUE / len) {
                logicalLong = Long.MAX_VALUE;
                break;
            }
            logicalLong *= len;
        }

        JSONArray extraGaps = new JSONArray();
        int logicalForTruncation;
        if (logicalLong > Integer.MAX_VALUE) {
            logicalForTruncation = Integer.MAX_VALUE;
            extraGaps.put("build_targets: logical cartesian product overflowed int range (totalCount clamped)");
        } else {
            logicalForTruncation = (int) logicalLong;
        }

        if (anySourceEmpty && logicalLong == 0L) {
            extraGaps.put("build_targets: at least one source had zero rows (no targets produced)");
        }

        JSONArray built = new JSONArray();
        int[] missingPathSlots = new int[1];
        cartesian(template, sourceOrder, sources, 0, new HashMap<>(), ctx, maxTargets, built, missingPathSlots);

        PlaybookGenericRowArrays.Truncation t =
                PlaybookGenericRowArrays.truncateIfNeededWithLogicalCount(built, logicalForTruncation, maxTargets,
                        "build_targets");
        JSONArray gaps = new JSONArray();
        for (int gi = 0; gi < t.gaps.length(); gi++) {
            gaps.put(t.gaps.getString(gi));
        }
        for (int gi = 0; gi < extraGaps.length(); gi++) {
            gaps.put(extraGaps.getString(gi));
        }
        if (missingPathSlots[0] > 0) {
            gaps.put(missingPathGapMessage(missingPathSlots[0]));
        }

        JSONObject output = new JSONObject()
                .put("targets", t.rows)
                .put("totalCount", t.logicalCount)
                .put("returned", t.returned)
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);

        StringBuilder ev = new StringBuilder("build_targets: built ")
                .append(t.returned)
                .append(" target(s); logical ")
                .append(t.logicalCount);
        if (t.logicalCount > t.returned) {
            ev.append("; truncated to maxTargets ").append(maxTargets);
        }
        ev.append("; sources (lex order ");
        for (int i = 0; i < sourceOrder.size(); i++) {
            if (i > 0) {
                ev.append(" x ");
            }
            String sn = sourceOrder.get(i);
            ev.append(sources.get(sn).length()).append(" ").append(sn);
        }
        ev.append(").");
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev.toString()));
        return result;
    }

    private static void cartesian(
            JSONObject template,
            List<String> sourceOrder,
            Map<String, JSONArray> sources,
            int depth,
            Map<String, JSONObject> combo,
            PlaybookRunContext ctx,
            int maxTargets,
            JSONArray built,
            int[] missingPathSlots)
            throws PlaybookRunException {
        if (built.length() >= maxTargets) {
            return;
        }
        if (depth == sourceOrder.size()) {
            built.put(materializeTemplate(template, combo, ctx, missingPathSlots));
            return;
        }
        String srcName = sourceOrder.get(depth);
        JSONArray rows = sources.get(srcName);
        for (int i = 0; i < rows.length(); i++) {
            if (built.length() >= maxTargets) {
                return;
            }
            combo.put(srcName, rows.getJSONObject(i));
            cartesian(template, sourceOrder, sources, depth + 1, combo, ctx, maxTargets, built, missingPathSlots);
        }
    }

    private static JSONObject materializeTemplate(JSONObject template, Map<String, JSONObject> combo,
            PlaybookRunContext ctx, int[] missingPathSlots) throws PlaybookRunException {
        JSONObject out = new JSONObject();
        for (String key : template.keySet()) {
            out.put(key, materializeValue(template.get(key), combo, ctx, missingPathSlots));
        }
        return out;
    }

    private static Object materializeValue(Object raw, Map<String, JSONObject> combo, PlaybookRunContext ctx,
            int[] missingPathSlots)
            throws PlaybookRunException {
        if (raw == null || raw == JSONObject.NULL) {
            return JSONObject.NULL;
        }
        if (raw instanceof String || raw instanceof Number || raw instanceof Boolean) {
            return raw;
        }
        if (raw instanceof JSONArray) {
            throw new PlaybookRunException("build_targets: template arrays are not supported in v1");
        }
        if (!(raw instanceof JSONObject)) {
            throw new PlaybookRunException(
                    "build_targets: unsupported template value type: " + raw.getClass().getSimpleName());
        }
        JSONObject o = (JSONObject) raw;
        if (o.length() == 1 && o.has("$path")) {
            Object p = o.opt("$path");
            if (!(p instanceof String)) {
                throw new PlaybookRunException("build_targets: $path must be a JSON string");
            }
            return resolvePath(((String) p).trim(), combo, missingPathSlots);
        }
        if (o.length() == 1) {
            String onlyKey = o.keys().next();
            if (!onlyKey.isEmpty() && onlyKey.charAt(0) == '$') {
                if (!("$input".equals(onlyKey) || "$var".equals(onlyKey) || "$ref".equals(onlyKey)
                        || "$item".equals(onlyKey))) {
                    throw new PlaybookRunException("build_targets: unsupported template binding key " + onlyKey);
                }
                return PlaybookExpressionResolver.resolve(o, ctx);
            }
        }
        for (String k : o.keySet()) {
            if (!k.isEmpty() && k.charAt(0) == '$') {
                throw new PlaybookRunException(
                        "build_targets: template object must not mix $-bindings with sibling keys under the same object");
            }
        }
        JSONObject nested = new JSONObject();
        for (String k : o.keySet()) {
            nested.put(k, materializeValue(o.get(k), combo, ctx, missingPathSlots));
        }
        return nested;
    }

    private static Object resolvePath(String spec, Map<String, JSONObject> combo, int[] missingPathSlots)
            throws PlaybookRunException {
        if (spec == null || spec.isEmpty()) {
            throw new PlaybookRunException("build_targets: $path must be non-blank");
        }
        int dot = spec.indexOf('.');
        if (dot <= 0) {
            throw new PlaybookRunException("build_targets: $path must be sourceName.dottedFieldPath");
        }
        String src = spec.substring(0, dot).trim();
        String path = spec.substring(dot + 1).trim();
        if (src.isEmpty() || path.isEmpty() || !PlaybookGenericPathGrammar.isValidDottedPath(path)) {
            throw new PlaybookRunException("build_targets: invalid $path");
        }
        JSONObject row = combo.get(src);
        if (row == null) {
            throw new PlaybookRunException("build_targets: $path references unknown source \"" + src + "\"");
        }
        Object v = PlaybookJsonRowPath.getAtPath(row, path);
        if (v == null) {
            missingPathSlots[0]++;
            return JSONObject.NULL;
        }
        return v;
    }

    private static String missingPathGapMessage(int count) {
        String core = "build_targets: " + count + " template field(s) had no value from $path (serialized as null)";
        int max = PlaybookGenericOpsConstants.MAX_GAP_TEXT_CHARS;
        return core.length() <= max ? core : core.substring(0, max);
    }
}
