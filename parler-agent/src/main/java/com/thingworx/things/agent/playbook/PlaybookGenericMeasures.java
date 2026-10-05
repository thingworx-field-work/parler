package com.thingworx.things.agent.playbook;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shared measure ops for {@code group_by} and {@code aggregate} (design section 8.5–8.6).
 * Package-private.
 */
final class PlaybookGenericMeasures {

    static final Set<String> OPS = Set.of("count", "count_present", "sum", "min", "max", "mean");

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    private PlaybookGenericMeasures() {}

    /** {@code measures} must be present, a {@link JSONArray}, and non-empty. */
    static JSONArray requireNonEmptyMeasuresArray(JSONObject args, String opLabel) throws PlaybookRunException {
        if (!args.has("measures")) {
            throw new PlaybookRunException(opLabel + ": measures is required");
        }
        JSONArray arr = args.optJSONArray("measures");
        if (arr == null) {
            throw new PlaybookRunException(opLabel + ": measures must be a JSON array", "GENERIC_INPUT_INVALID");
        }
        if (arr.length() == 0) {
            throw new PlaybookRunException(opLabel + ": measures must be a non-empty array");
        }
        return arr;
    }

    /** When {@code args} has {@code fieldName} but it is not a {@link JSONArray}, fail closed. */
    static JSONArray requireMeasuresArray(JSONObject args, String fieldName, String opLabel)
            throws PlaybookRunException {
        if (!args.has(fieldName)) {
            return new JSONArray();
        }
        JSONArray arr = args.optJSONArray(fieldName);
        if (arr == null) {
            throw new PlaybookRunException(opLabel + ": " + fieldName + " must be a JSON array when present",
                    "GENERIC_INPUT_INVALID");
        }
        return arr;
    }

    /**
     * Reads {@code name}, {@code op}, {@code field} from a measure spec with strict JSON string typing (no
     * {@code optString} coercion of booleans/numbers).
     */
    static String[] readMeasureFieldStrings(JSONObject spec, int m, String opLabel) throws PlaybookRunException {
        String ctx = opLabel + ": measures[" + m + "]";
        String name = PlaybookGenericJsonSchemaStrings.requireTrimmed(spec, "name", ctx);
        if (name.isEmpty()) {
            throw new PlaybookRunException(ctx + " requires name");
        }
        String op = PlaybookGenericJsonSchemaStrings.optionalTrimmed(spec, "op", ctx).toLowerCase(Locale.ROOT);
        String field = PlaybookGenericJsonSchemaStrings.optionalTrimmed(spec, "field", ctx);
        return new String[] {name, op, field};
    }

    static void validateMeasureSpecs(JSONArray measures, Set<String> keyPaths, String opLabel, String nodeId,
            PlaybookValidationCollector col) {
        Set<String> keySet = keyPaths != null ? keyPaths : Set.of();
        Set<String> seenNames = new HashSet<>();
        for (int m = 0; m < measures.length(); m++) {
            JSONObject spec = measures.optJSONObject(m);
            if (spec == null) {
                col.node(nodeId, "",opLabel + " derive measures[" + m + "] must be an object: " + nodeId);
                continue;
            }
            if (!spec.has("name")) {
                col.node(nodeId, "",opLabel + " derive measures[" + m + "] requires name: " + nodeId);
                continue;
            }
            Object nameObj = spec.get("name");
            if (!(nameObj instanceof String)) {
                col.node(nodeId, "",opLabel + " derive measures[" + m + "] name must be a JSON string: " + nodeId);
                continue;
            }
            String name = ((String) nameObj).trim();
            if (name.isEmpty()) {
                col.node(nodeId, "",opLabel + " derive measures[" + m + "] requires name: " + nodeId);
                continue;
            }
            String op = "";
            if (spec.has("op") && !spec.isNull("op")) {
                Object opObj = spec.get("op");
                if (!(opObj instanceof String)) {
                    col.node(nodeId, "",opLabel + " derive measures[" + m + "] op must be a JSON string: " + nodeId);
                    continue;
                }
                op = ((String) opObj).trim().toLowerCase(Locale.ROOT);
            }
            if (!NAME.matcher(name).matches()) {
                col.node(nodeId, "",opLabel + " derive measures[" + m + "] invalid measure name: " + nodeId);
            }
            if (PlaybookGenericOpsConstants.isReservedOutputField(name)) {
                col.node(nodeId, "",opLabel + " derive measures[" + m + "] name is reserved: " + name + " (" + nodeId + ")");
            }
            if (!keySet.isEmpty() && keySet.contains(name)) {
                col.node(nodeId, "",opLabel + " derive measure name collides with key: " + name + " (" + nodeId + ")");
            }
            if (!seenNames.add(name)) {
                col.node(nodeId, "",opLabel + " derive duplicate measure name \"" + name + "\": " + nodeId);
            }
            if (!OPS.contains(op)) {
                col.node(nodeId, "",opLabel + " derive measures[" + m + "] unsupported op: " + nodeId);
            }
            String field = "";
            if (spec.has("field") && !spec.isNull("field")) {
                Object fieldObj = spec.get("field");
                if (!(fieldObj instanceof String)) {
                    col.node(nodeId, "",opLabel + " derive measures[" + m + "] field must be a JSON string: " + nodeId);
                    continue;
                }
                field = ((String) fieldObj).trim();
            }
            if ("count".equals(op)) {
                if (!field.isEmpty()) {
                    col.node(nodeId, "",opLabel + " derive count measure must not set field (" + nodeId + ")");
                }
            } else {
                if (field.isEmpty()) {
                    col.node(nodeId, "",opLabel + " derive measures[" + m + "] requires field for op " + op + ": " + nodeId);
                } else if (!PlaybookGenericPathGrammar.isValidDottedPath(field)) {
                    col.node(nodeId, "",opLabel + " derive measures[" + m + "] invalid measure field: " + nodeId);
                }
            }
        }
    }

    static void validateRuntimeMeasures(JSONArray measures, Set<String> keyPaths, String opLabel)
            throws PlaybookRunException {
        Set<String> keySet = keyPaths != null ? keyPaths : Set.of();
        Set<String> seenNames = new HashSet<>();
        for (int m = 0; m < measures.length(); m++) {
            JSONObject spec = measures.optJSONObject(m);
            if (spec == null) {
                throw new PlaybookRunException(opLabel + ": measures[" + m + "] must be an object");
            }
            String[] nfs = readMeasureFieldStrings(spec, m, opLabel);
            String name = nfs[0];
            String op = nfs[1];
            if (!NAME.matcher(name).matches()) {
                throw new PlaybookRunException(opLabel + ": measures[" + m + "] invalid measure name");
            }
            if (!seenNames.add(name)) {
                throw new PlaybookRunException(opLabel + ": duplicate measure name: " + name);
            }
            if (PlaybookGenericOpsConstants.isReservedOutputField(name)) {
                throw new PlaybookRunException(opLabel + ": measures[" + m + "] name is reserved: " + name);
            }
            if (!keySet.isEmpty() && keySet.contains(name)) {
                throw new PlaybookRunException(opLabel + ": measure name collides with key: " + name);
            }
            if (!OPS.contains(op)) {
                throw new PlaybookRunException(opLabel + ": unsupported measure op: " + op);
            }
            String field = nfs[2];
            if ("count".equals(op)) {
                if (!field.isEmpty()) {
                    throw new PlaybookRunException(opLabel + ": count measure must not set field");
                }
            } else {
                if (field.isEmpty()) {
                    throw new PlaybookRunException(opLabel + ": measures[" + m + "] requires field for op " + op);
                }
                if (!PlaybookGenericPathGrammar.isValidDottedPath(field)) {
                    throw new PlaybookRunException(opLabel + ": measures[" + m + "] invalid measure field path");
                }
            }
        }
    }

    static Result eval(String op, String field, List<Integer> indices, JSONArray rows) throws PlaybookRunException {
        switch (op) {
            case "count":
                return new Result(indices.size(), null);
            case "count_present": {
                int c = 0;
                for (int ix : indices) {
                    Object v = PlaybookJsonRowPath.getAtPath(rows.getJSONObject(ix), field);
                    if (!PlaybookPredicateValueOps.isEmpty(v)) {
                        c++;
                    }
                }
                return new Result(c, null);
            }
            case "sum": {
                double sum = 0;
                int n = 0;
                for (int ix : indices) {
                    Object v = PlaybookJsonRowPath.getAtPath(rows.getJSONObject(ix), field);
                    if (v instanceof Number) {
                        sum += ((Number) v).doubleValue();
                        n++;
                    }
                }
                if (n == 0) {
                    return new Result(JSONObject.NULL, "no numeric values");
                }
                return new Result(sum, null);
            }
            case "min":
            case "max": {
                Double best = null;
                for (int ix : indices) {
                    Object v = PlaybookJsonRowPath.getAtPath(rows.getJSONObject(ix), field);
                    if (v instanceof Number) {
                        double d = ((Number) v).doubleValue();
                        if (best == null) {
                            best = d;
                        } else if ("min".equals(op)) {
                            best = Math.min(best, d);
                        } else {
                            best = Math.max(best, d);
                        }
                    }
                }
                if (best == null) {
                    return new Result(JSONObject.NULL, "no numeric values");
                }
                return new Result(best, null);
            }
            case "mean": {
                double sum = 0;
                int n = 0;
                for (int ix : indices) {
                    Object v = PlaybookJsonRowPath.getAtPath(rows.getJSONObject(ix), field);
                    if (v instanceof Number) {
                        sum += ((Number) v).doubleValue();
                        n++;
                    }
                }
                if (n == 0) {
                    return new Result(JSONObject.NULL, "mean undefined");
                }
                return new Result(sum / n, null);
            }
            default:
                throw new PlaybookRunException("unsupported measure op: " + op);
        }
    }

    static final class Result {
        final Object value;
        final String gapNote;

        Result(Object value, String gapNote) {
            this.value = value;
            this.gapNote = gapNote;
        }
    }
}
