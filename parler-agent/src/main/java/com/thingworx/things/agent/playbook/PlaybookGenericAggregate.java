package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Generic {@code aggregate} derive op (see {@code docs/agent/playbook-generic-ops-foundation.md} section 8.6).
 * Package-private; invoked from {@link PlaybookGenericDeriveOps}.
 */
final class PlaybookGenericAggregate {

    private PlaybookGenericAggregate() {}

    static JSONObject execute(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("aggregate: missing args");
        }
        JSONArray rows = PlaybookGenericRowArrays.loadResolvedRowArgs(args, ctx, "aggregate");
        int inputRowSlots = rows.length();
        JSONArray measures = PlaybookGenericMeasures.requireNonEmptyMeasuresArray(args, "aggregate");
        PlaybookGenericMeasures.validateRuntimeMeasures(measures, null, "aggregate");
        List<Integer> all = new ArrayList<>(inputRowSlots);
        for (int i = 0; i < inputRowSlots; i++) {
            all.add(i);
        }
        JSONArray gaps = new JSONArray();
        if (inputRowSlots == 0) {
            gaps.put("aggregate: empty input row set");
        }
        JSONObject output = new JSONObject();
        boolean anyNumericGap = false;
        for (int m = 0; m < measures.length(); m++) {
            JSONObject spec = measures.optJSONObject(m);
            if (spec == null) {
                throw new PlaybookRunException("aggregate: measures[" + m + "] must be an object");
            }
            String[] nfs = PlaybookGenericMeasures.readMeasureFieldStrings(spec, m, "aggregate");
            String name = nfs[0];
            String op = nfs[1];
            String field = nfs[2];
            PlaybookGenericMeasures.Result mr = PlaybookGenericMeasures.eval(op, field, all, rows);
            if (mr.gapNote != null) {
                anyNumericGap = true;
            }
            output.put(name, mr.value);
        }
        if (anyNumericGap) {
            gaps.put("aggregate: one or more numeric measures undefined (no numeric samples)");
        }
        output.put("gaps", gaps).put("totalCount", inputRowSlots).put("returned", inputRowSlots);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        String ev = "aggregate: input " + inputRowSlots + " row slot(s); " + measures.length() + " measure(s).";
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(ev));
        return result;
    }
}
