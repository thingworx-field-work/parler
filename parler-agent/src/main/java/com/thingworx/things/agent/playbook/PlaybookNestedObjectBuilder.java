package com.thingworx.things.agent.playbook;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * {@code build_nested_object} and {@code json_stringify} derive ops (topic {@code playbook-34-35} Slice C).
 */
final class PlaybookNestedObjectBuilder {

    private PlaybookNestedObjectBuilder() {}

    static JSONObject buildNestedObject(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("build_nested_object: missing args");
        }
        JSONObject sourcesObj = args.optJSONObject("sources");
        if (sourcesObj == null || sourcesObj.length() == 0) {
            throw new PlaybookRunException("build_nested_object: sources must be a non-empty object");
        }
        JSONObject template = args.optJSONObject("template");
        if (template == null || template.length() == 0) {
            throw new PlaybookRunException("build_nested_object: template must be a non-empty object");
        }
        int maxParents = requiredPositiveInt(args, "maxParents", "build_nested_object");
        int maxChildren = requiredPositiveInt(args, "maxChildren", "build_nested_object");
        if (maxParents > PlaybookGenericOpsConstants.MAX_NESTED_PARENTS) {
            throw new PlaybookRunException("build_nested_object: maxParents exceeds cap "
                    + PlaybookGenericOpsConstants.MAX_NESTED_PARENTS);
        }
        if (maxChildren > PlaybookGenericOpsConstants.MAX_NESTED_CHILDREN) {
            throw new PlaybookRunException("build_nested_object: maxChildren exceeds cap "
                    + PlaybookGenericOpsConstants.MAX_NESTED_CHILDREN);
        }
        boolean omitNull = args.optBoolean("omitNull", false);
        int minParents = args.has("minParents") ? intArg(args, "minParents", 0) : 0;

        Map<String, JSONArray> sources = new HashMap<>();
        for (String name : sourcesObj.keySet()) {
            Object resolved = PlaybookExpressionResolver.resolve(sourcesObj.get(name), ctx);
            JSONArray rows = PlaybookGenericRowArrays.coerceResolvedRowContainerToArray(resolved, "build_nested_object");
            if (rows.length() > PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS) {
                throw new PlaybookRunException("build_nested_object: source \"" + name + "\" exceeds cap "
                        + PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS, "GENERIC_INPUT_TOO_LARGE");
            }
            PlaybookGenericRowArrays.requireEachSlotIsObject(rows, "build_nested_object", "sources." + name);
            sources.put(name, rows);
        }

        BuildState state = new BuildState(sources, ctx, maxParents, maxChildren, omitNull);
        JSONObject object = materializeObject(template, null, state);
        if (minParents >= 1 && state.mapParentCount < minParents) {
            return new JSONObject()
                    .put("status", "needs_clarification")
                    .put("message", "build_nested_object: at least " + minParents
                            + " parent row(s) required but $map produced " + state.mapParentCount);
        }
        JSONObject output = new JSONObject().put("object", object);
        if (state.gaps.length() > 0) {
            output.put("gaps", state.gaps);
        }
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(
                "build_nested_object: built payload with " + state.mapParentCount + " mapped parent(s); $src rows "
                        + state.childrenCopied));
        return result;
    }

    static JSONObject jsonStringify(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("json_stringify: missing args");
        }
        if (!args.has("maxBytes")) {
            throw new PlaybookRunException("json_stringify: maxBytes is required");
        }
        int maxBytes = requiredPositiveInt(args, "maxBytes", "json_stringify");
        if (maxBytes > PlaybookGenericOpsConstants.MAX_JSON_STRINGIFY_BYTES) {
            throw new PlaybookRunException("json_stringify: maxBytes exceeds cap "
                    + PlaybookGenericOpsConstants.MAX_JSON_STRINGIFY_BYTES);
        }
        Object value = PlaybookExpressionResolver.resolve(args.opt("value"), ctx);
        if (value == null || value == JSONObject.NULL) {
            throw new PlaybookRunException("json_stringify: value resolved to null");
        }
        String json = jsonValueToString(value);
        int byteCount = json.getBytes(StandardCharsets.UTF_8).length;
        if (byteCount > maxBytes) {
            throw new PlaybookRunException(
                    "json_stringify: serialized length " + byteCount + " exceeds maxBytes " + maxBytes,
                    "JSON_STRINGIFY_TOO_LARGE");
        }
        JSONObject output = new JSONObject().put("value", json).put("byteCount", byteCount);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        String keysHint = value instanceof JSONObject ? String.join(",", ((JSONObject) value).keySet()) : "(array)";
        PlaybookNodeEvidence.attachLinesOnly(result, PlaybookNodeEvidence.singleLine(
                "json_stringify: " + byteCount + " bytes; top-level keys " + keysHint));
        return result;
    }

    private static JSONObject materializeObject(JSONObject template, JSONObject parentRow, BuildState state)
            throws PlaybookRunException {
        JSONObject out = new JSONObject();
        for (String key : template.keySet()) {
            Object raw = template.get(key);
            Object mat = materializeValue(raw, parentRow, state, false);
            if (omitSkip(state, mat)) {
                continue;
            }
            out.put(key, mat);
        }
        return out;
    }

    private static Object materializeValue(Object raw, JSONObject parentRow, BuildState state, boolean inEach)
            throws PlaybookRunException {
        if (raw == null || raw == JSONObject.NULL) {
            return JSONObject.NULL;
        }
        if (raw instanceof String || raw instanceof Number || raw instanceof Boolean) {
            return raw;
        }
        if (raw instanceof JSONArray) {
            throw new PlaybookRunException("build_nested_object: template arrays require $map");
        }
        if (!(raw instanceof JSONObject)) {
            throw new PlaybookRunException(
                    "build_nested_object: unsupported template value type: " + raw.getClass().getSimpleName());
        }
        JSONObject o = (JSONObject) raw;
        if (o.length() == 1 && o.has("$map")) {
            if (inEach) {
                throw new PlaybookRunException("build_nested_object: nested $map inside $map.each is not supported in v1");
            }
            return buildMappedArray(o.optJSONObject("$map"), state);
        }
        if (o.length() == 1 && o.has("$literal")) {
            return o.get("$literal");
        }
        if (o.length() == 1 && o.has("$nodeRef")) {
            return resolveNodeRef(o.optString("$nodeRef", ""), state.ctx);
        }
        if (inEach && o.length() == 1 && o.has("$path")) {
            return resolveEachPath(o.optString("$path", ""), parentRow);
        }
        if (inEach && o.length() == 1 && o.has("$src")) {
            return attachSrcRows(o.optString("$src", ""), state);
        }
        if (o.length() == 1) {
            String onlyKey = o.keys().next();
            if (!onlyKey.isEmpty() && onlyKey.charAt(0) == '$') {
                throw new PlaybookRunException("build_nested_object: unsupported template binding " + onlyKey);
            }
        }
        for (String k : o.keySet()) {
            if (!k.isEmpty() && k.charAt(0) == '$') {
                throw new PlaybookRunException(
                        "build_nested_object: template object must not mix $-bindings with sibling keys");
            }
        }
        JSONObject nested = new JSONObject();
        for (String k : o.keySet()) {
            Object v = materializeValue(o.get(k), parentRow, state, inEach);
            if (!omitSkip(state, v)) {
                nested.put(k, v);
            }
        }
        return nested;
    }

    private static JSONArray buildMappedArray(JSONObject mapSpec, BuildState state) throws PlaybookRunException {
        if (mapSpec == null) {
            throw new PlaybookRunException("build_nested_object: $map must be an object");
        }
        String over = mapSpec.optString("over", "").trim();
        if (over.isEmpty()) {
            throw new PlaybookRunException("build_nested_object: $map.over is required");
        }
        JSONObject each = mapSpec.optJSONObject("each");
        if (each == null || each.length() == 0) {
            throw new PlaybookRunException("build_nested_object: $map.each is required");
        }
        JSONArray rows = state.sources.get(over);
        if (rows == null) {
            throw new PlaybookRunException("build_nested_object: $map.over references unknown source \"" + over + "\"");
        }
        JSONArray out = new JSONArray();
        int limit = Math.min(rows.length(), state.maxParents);
        if (rows.length() > state.maxParents) {
            state.gaps.put("build_nested_object: $map over \"" + over + "\" truncated from " + rows.length() + " to "
                    + state.maxParents + " parents");
        }
        for (int i = 0; i < limit; i++) {
            JSONObject parentRow = rows.getJSONObject(i);
            JSONObject item = materializeEachObject(each, parentRow, state);
            out.put(item);
            state.mapParentCount++;
        }
        return out;
    }

    private static JSONObject materializeEachObject(JSONObject eachTemplate, JSONObject parentRow, BuildState state)
            throws PlaybookRunException {
        JSONObject out = new JSONObject();
        for (String key : eachTemplate.keySet()) {
            Object mat = materializeValue(eachTemplate.get(key), parentRow, state, true);
            if (omitSkip(state, mat)) {
                continue;
            }
            out.put(key, mat);
        }
        return out;
    }

    private static Object resolveEachPath(String field, JSONObject parentRow) throws PlaybookRunException {
        if (field == null || field.isEmpty() || field.indexOf('.') >= 0) {
            throw new PlaybookRunException("build_nested_object: $path in $map.each must be a single field name");
        }
        if (parentRow == null || !parentRow.has(field)) {
            return JSONObject.NULL;
        }
        return parentRow.get(field);
    }

    private static JSONArray attachSrcRows(String srcName, BuildState state) throws PlaybookRunException {
        if (srcName == null || srcName.isEmpty()) {
            throw new PlaybookRunException("build_nested_object: $src must be non-blank");
        }
        JSONArray rows = state.sources.get(srcName);
        if (rows == null) {
            throw new PlaybookRunException("build_nested_object: $src references unknown source \"" + srcName + "\"");
        }
        int remaining = state.maxChildren - state.childrenCopied;
        if (remaining <= 0) {
            state.gaps.put("build_nested_object: maxChildren reached; omitted $src \"" + srcName + "\" rows");
            return new JSONArray();
        }
        JSONArray copy = new JSONArray();
        int take = Math.min(rows.length(), remaining);
        if (rows.length() > take) {
            state.gaps.put("build_nested_object: $src \"" + srcName + "\" truncated from " + rows.length() + " to "
                    + take + " rows (maxChildren)");
        }
        for (int i = 0; i < take; i++) {
            copy.put(rows.getJSONObject(i));
        }
        state.childrenCopied += take;
        return copy;
    }

    private static Object resolveNodeRef(String ref, PlaybookRunContext ctx) throws PlaybookRunException {
        if (ref == null || ref.isBlank()) {
            throw new PlaybookRunException("build_nested_object: $nodeRef must be non-blank");
        }
        Object v = PlaybookExpressionResolver.resolve(new JSONObject().put("$ref", ref.trim()), ctx);
        return v == null ? JSONObject.NULL : v;
    }

    private static boolean omitSkip(BuildState state, Object mat) {
        return state.omitNull && (mat == null || mat == JSONObject.NULL);
    }

    private static String jsonValueToString(Object value) throws PlaybookRunException {
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return value.toString();
        }
        throw new PlaybookRunException("json_stringify: value must be a JSON object or array");
    }

    private static int requiredPositiveInt(JSONObject args, String key, String op) throws PlaybookRunException {
        if (!args.has(key)) {
            throw new PlaybookRunException(op + ": missing " + key);
        }
        Object o = args.opt(key);
        if (!(o instanceof Number)) {
            throw new PlaybookRunException(op + ": " + key + " must be a number");
        }
        double d = ((Number) o).doubleValue();
        if (d < 1 || d != Math.rint(d)) {
            throw new PlaybookRunException(op + ": " + key + " must be an integer >= 1");
        }
        return (int) d;
    }

    private static int intArg(JSONObject args, String key, int defaultValue) throws PlaybookRunException {
        if (!args.has(key)) {
            return defaultValue;
        }
        Object o = args.opt(key);
        if (!(o instanceof Number)) {
            throw new PlaybookRunException("build_nested_object: " + key + " must be a number");
        }
        double d = ((Number) o).doubleValue();
        if (d < 0 || d != Math.rint(d)) {
            throw new PlaybookRunException("build_nested_object: " + key + " must be a non-negative integer");
        }
        return (int) d;
    }

    private static final class BuildState {
        final Map<String, JSONArray> sources;
        final PlaybookRunContext ctx;
        final int maxParents;
        final int maxChildren;
        final boolean omitNull;
        final JSONArray gaps = new JSONArray();
        int mapParentCount;
        int childrenCopied;

        BuildState(Map<String, JSONArray> sources, PlaybookRunContext ctx, int maxParents, int maxChildren,
                boolean omitNull) {
            this.sources = sources;
            this.ctx = ctx;
            this.maxParents = maxParents;
            this.maxChildren = maxChildren;
            this.omitNull = omitNull;
        }
    }
}
