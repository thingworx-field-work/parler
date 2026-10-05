package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Generic {@code group_by} derive op (see {@code docs/agent/playbook-generic-ops-foundation.md} section 8.5).
 * Package-private; invoked from {@link PlaybookGenericDeriveOps}.
 */
final class PlaybookGenericGroupBy {

    private PlaybookGenericGroupBy() {}

    static JSONObject execute(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("group_by: missing args");
        }
        if (args.optBoolean("foldOverflowToOther", false)) {
            throw new PlaybookRunException("group_by: foldOverflowToOther is not supported in this version");
        }
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "group_by");
        int inputRowSlots = rows.length();
        JSONArray keys = args.optJSONArray("keys");
        if (keys == null || keys.length() == 0) {
            throw new PlaybookRunException("group_by: keys must be a non-empty array");
        }
        List<String> keyPaths = new ArrayList<>(keys.length());
        for (int i = 0; i < keys.length(); i++) {
            Object rawKey = keys.get(i);
            if (!(rawKey instanceof String)) {
                throw new PlaybookRunException("group_by: keys[" + i + "] must be a JSON string", "GENERIC_INPUT_INVALID");
            }
            String k = ((String) rawKey).trim();
            if (k.isEmpty()) {
                throw new PlaybookRunException("group_by: keys[" + i + "] must be a non-blank string");
            }
            if (!PlaybookGenericPathGrammar.isSingleSegmentField(k)) {
                throw new PlaybookRunException(
                        "group_by: keys[" + i + "] must be a single identifier segment (no dots): " + k);
            }
            if ("_other".equals(k)) {
                throw new PlaybookRunException("group_by: reserved key name _other is not allowed in keys");
            }
            keyPaths.add(k);
        }
        if (!args.has("maxGroups")) {
            throw new PlaybookRunException("group_by: maxGroups is required");
        }
        Object mgObj = args.opt("maxGroups");
        if (!(mgObj instanceof Number)) {
            throw new PlaybookRunException("group_by: maxGroups must be a number");
        }
        double mgd = ((Number) mgObj).doubleValue();
        if (mgd < 1 || mgd != Math.rint(mgd) || mgd > PlaybookGenericOpsConstants.MAX_GENERIC_GROUPS) {
            throw new PlaybookRunException(
                    "group_by: maxGroups must be an integer from 1 to " + PlaybookGenericOpsConstants.MAX_GENERIC_GROUPS);
        }
        int maxGroups = ((Number) mgObj).intValue();
        JSONArray measures = PlaybookGenericMeasures.requireMeasuresArray(args, "measures", "group_by");
        PlaybookGenericMeasures.validateRuntimeMeasures(measures, Set.copyOf(keyPaths), "group_by");

        LinkedHashMap<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int r = 0; r < rows.length(); r++) {
            JSONObject row = rows.getJSONObject(r);
            String ck = compositeKey(keyPaths, row);
            groups.computeIfAbsent(ck, k -> new ArrayList<>()).add(r);
        }
        int distinct = groups.size();
        JSONArray gaps = new JSONArray();
        List<String> orderedCompositeKeys;
        if (distinct > maxGroups) {
            List<String> sorted = new ArrayList<>(groups.keySet());
            Collections.sort(sorted);
            orderedCompositeKeys = sorted.subList(0, maxGroups);
            gaps.put("group_by: truncated distinct key groups from " + distinct + " to " + maxGroups);
        } else {
            orderedCompositeKeys = new ArrayList<>(groups.keySet());
        }

        JSONArray outRows = new JSONArray();
        boolean anyNumericGap = false;
        for (String ck : orderedCompositeKeys) {
            List<Integer> indices = groups.get(ck);
            JSONObject first = rows.getJSONObject(indices.get(0));
            JSONObject outRow = new JSONObject();
            for (String kp : keyPaths) {
                Object kv = PlaybookJsonRowPath.getAtPath(first, kp);
                if (kv == null || kv == JSONObject.NULL) {
                    outRow.put(kp, JSONObject.NULL);
                } else {
                    outRow.put(kp, kv);
                }
            }
            for (int m = 0; m < measures.length(); m++) {
                JSONObject spec = measures.optJSONObject(m);
                if (spec == null) {
                    throw new PlaybookRunException("group_by: measures[" + m + "] must be an object");
                }
                String[] nfs = PlaybookGenericMeasures.readMeasureFieldStrings(spec, m, "group_by");
                String name = nfs[0];
                String op = nfs[1];
                String field = nfs[2];
                PlaybookGenericMeasures.Result mr = PlaybookGenericMeasures.eval(op, field, indices, rows);
                if (mr.gapNote != null) {
                    anyNumericGap = true;
                }
                outRow.put(name, mr.value);
            }
            outRows.put(outRow);
        }
        if (anyNumericGap) {
            gaps.put("group_by: one or more aggregate values undefined (no numeric samples)");
        }
        int produced = outRows.length();
        JSONObject output = new JSONObject()
                .put("rows", outRows)
                .put("totalCount", distinct)
                .put("returned", produced)
                .put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        String ev = "group_by: input " + inputRowSlots + " row slot(s); " + distinct + " distinct key group(s); emitted "
                + produced + " row(s)";
        if (distinct > maxGroups) {
            ev += "; truncated to maxGroups " + maxGroups;
        }
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev + "."));
        return result;
    }

    private static String compositeKey(List<String> keyPaths, JSONObject row) throws PlaybookRunException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyPaths.size(); i++) {
            if (i > 0) {
                sb.append('\u001f');
            }
            Object v = PlaybookJsonRowPath.getAtPath(row, keyPaths.get(i));
            sb.append(encodeKeyPart(v));
        }
        return sb.toString();
    }

    private static String encodeKeyPart(Object v) {
        if (v == null || v == JSONObject.NULL) {
            return "\u0000n";
        }
        if (v instanceof Boolean) {
            return "\u0000b" + v;
        }
        if (v instanceof Number) {
            return "\u0000d" + Double.toString(((Number) v).doubleValue());
        }
        return "\u0000s" + String.valueOf(v);
    }
}
