package com.thingworx.things.agent.playbook;

import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.tools.InfotableJsonCodec;
import com.thingworx.types.InfoTable;

/**
 * Resolves V1 reference forms: literals, {@code $input}, {@code $var}, {@code $ref}, {@code $item}.
 * <p>
 * A continuing {@code $ref} through {@code toolOutput.result} parses a JSON-string result before resolving later
 * segments. Direct result references and non-{@code $ref} paths remain opaque.
 * <p>
 * {@code $table} and {@code $infotable} are allowed only inside {@code tool_call.args} per
 * {@link #resolvePlaybookToolCallArgs}. Other call sites use {@link #resolve}, which rejects those
 * forms with {@code TABLE_REF_NOT_ALLOWED_HERE} / {@code INFOTABLE_REF_NOT_ALLOWED_HERE}.
 */
public final class PlaybookExpressionResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlaybookExpressionResolver() {}

    public static Object resolve(Object value, PlaybookRunContext ctx) throws PlaybookRunException {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            if (o.has("$table")) {
                throw new PlaybookRunException(
                        "$table is only resolvable as the sole value of a top-level tool_call.args parameter",
                        "TABLE_REF_NOT_ALLOWED_HERE");
            }
            if (o.has("$infotable")) {
                throw new PlaybookRunException(
                        "$infotable is only resolvable inside tool_call.args parameter bindings",
                        "INFOTABLE_REF_NOT_ALLOWED_HERE");
            }
            if (o.has("$input")) {
                return navigate(ctx.params(), o.getString("$input"));
            }
            if (o.has("$var")) {
                return PlaybookRunContextVars.get(ctx.vars(), o.getString("$var"));
            }
            if (o.has("$ref")) {
                return navigateRef(ctx, o.getString("$ref"));
            }
            if (o.has("$item")) {
                return navigate(ctx.currentItem(), o.getString("$item"));
            }
            JSONObject out = new JSONObject();
            for (String key : o.keySet()) {
                out.put(key, resolve(o.get(key), ctx));
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                out.put(resolve(arr.get(i), ctx));
            }
            return out;
        }
        return value;
    }

    public static JSONObject resolveArgs(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            return new JSONObject();
        }
        JSONObject out = new JSONObject();
        for (String key : args.keySet()) {
            Object v = resolve(args.get(key), ctx);
            if (v != null) {
                out.put(key, v);
            }
        }
        return out;
    }

    /**
     * Resolves {@code tool_call.args}. Each parameter may bind a raw {@link InfoTable} via {@code $table} or build one
     * via top-level {@code $infotable} on repository extended tools only (v1).
     */
    public static PlaybookResolvedToolArgs resolvePlaybookToolCallArgs(JSONObject args, String toolName,
            PlaybookRunContext ctx) throws PlaybookRunException {
        if (args == null) {
            return PlaybookResolvedToolArgs.empty();
        }
        JSONObject json = new JSONObject();
        Map<String, InfoTable> tables = new LinkedHashMap<>();
        for (String key : args.keySet()) {
            Object raw = args.get(key);
            if ("parameters".equals(key) && jsonContainsDollarInfotable(raw)) {
                throw new PlaybookRunException(
                        "$infotable under args.parameters is deferred in v1; use a repository extended tool",
                        "INFOTABLE_REF_NOT_ALLOWED_HERE");
            }
            if (raw instanceof JSONObject) {
                JSONObject ro = (JSONObject) raw;
                if (ro.has("$table")) {
                    if (ro.length() != 1) {
                        throw new PlaybookRunException("$table object must contain only the $table key",
                                "TABLE_REF_INVALID_FORMAT");
                    }
                    tables.put(key, resolveTableRef(ctx, ro.optString("$table", "")));
                    continue;
                }
                if (ro.has("$infotable")) {
                    tables.put(key, resolveInfotableBinding(ro.optJSONObject("$infotable"), ctx, key));
                    continue;
                }
            }
            if (jsonContainsDollarTable(raw)) {
                throw new PlaybookRunException(
                        "$table is only allowed as the sole value of a top-level tool_call.args parameter (key " + key
                                + ")",
                        "TABLE_REF_NOT_ALLOWED_HERE");
            }
            if (jsonContainsDollarInfotable(raw)) {
                throw new PlaybookRunException(
                        "$infotable is only allowed as the sole value of a top-level tool_call.args parameter (key "
                                + key + ")",
                        "INFOTABLE_REF_NOT_ALLOWED_HERE");
            }
            Object v = resolve(raw, ctx);
            if (v != null) {
                json.put(key, v);
            }
        }
        return new PlaybookResolvedToolArgs(json, tables);
    }

    static InfoTable resolveInfotableBinding(JSONObject spec, PlaybookRunContext ctx, String paramLabel)
            throws PlaybookRunException {
        if (spec == null || spec.length() == 0) {
            throw new PlaybookRunException("$infotable binding requires rows and dataShapeName (parameter " + paramLabel
                    + ")", "INFOTABLE_REF_INVALID_FORMAT");
        }
        Object rowsSpec = spec.opt("rows");
        if (!(rowsSpec instanceof JSONObject)) {
            throw new PlaybookRunException("$infotable.rows must be a $ref object, not an inline array (parameter "
                    + paramLabel + ")", "INFOTABLE_REF_INVALID_FORMAT");
        }
        JSONObject rowsRef = (JSONObject) rowsSpec;
        if (rowsRef.length() != 1 || !rowsRef.has("$ref")) {
            throw new PlaybookRunException(
                    "$infotable.rows must be exactly { \"$ref\": \"<nodeId>.<path>\" } (parameter " + paramLabel + ")",
                    "INFOTABLE_REF_INVALID_FORMAT");
        }
        String ref = rowsRef.optString("$ref", "").trim();
        if (ref.isEmpty()) {
            throw new PlaybookRunException("$infotable.rows $ref must be non-blank (parameter " + paramLabel + ")",
                    "INFOTABLE_REF_INVALID_FORMAT");
        }
        Object resolvedRows;
        try {
            resolvedRows = navigateRef(ctx, ref);
        } catch (PlaybookRunException e) {
            throw new PlaybookRunException(
                    "$infotable.rows $ref could not be resolved: " + ref + " (parameter " + paramLabel + ")",
                    "INFOTABLE_REF_NOT_FOUND");
        }
        if (!(resolvedRows instanceof JSONArray)) {
            throw new PlaybookRunException(
                    "$infotable.rows $ref must resolve to a JSON array (parameter " + paramLabel + ")",
                    "INFOTABLE_REF_NOT_FOUND");
        }
        JSONArray rows = (JSONArray) resolvedRows;
        boolean allowEmpty = spec.optBoolean("allowEmpty", false);
        if (rows.length() == 0 && !allowEmpty) {
            throw new PlaybookRunException(
                    "$infotable binding produced zero rows (parameter " + paramLabel
                            + "); set allowEmpty:true to permit",
                    "INFOTABLE_EMPTY");
        }
        String dataShapeName = spec.optString("dataShapeName", "").trim();
        if (dataShapeName.isEmpty()) {
            throw new PlaybookRunException("$infotable.dataShapeName is required (parameter " + paramLabel + ")",
                    "INFOTABLE_REF_INVALID_FORMAT");
        }
        FieldDefinition fd = FieldDefinition.createInfoTableFieldDefinition(paramLabel, "", dataShapeName);
        DataShapeDefinition shape = InfotableJsonCodec.resolveDataShapeForParameter(fd);
        if (shape == null) {
            throw new PlaybookRunException(
                    "$infotable could not resolve DataShape \"" + dataShapeName + "\" (parameter " + paramLabel + ")",
                    "INFOTABLE_BUILD_FAILED");
        }
        try {
            JsonNode node = MAPPER.readTree(rows.toString());
            return InfotableJsonCodec.jsonToInfoTable(node, shape, paramLabel);
        } catch (IllegalArgumentException e) {
            throw new PlaybookRunException(
                    "$infotable row validation failed for parameter " + paramLabel + ": " + e.getMessage(),
                    "INFOTABLE_BUILD_FAILED");
        } catch (Exception e) {
            throw new PlaybookRunException(
                    "$infotable build failed for parameter " + paramLabel + ": " + e.getMessage(),
                    "INFOTABLE_BUILD_FAILED");
        }
    }

    private static boolean jsonContainsDollarTable(Object value) {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            if (o.has("$table")) {
                return true;
            }
            for (String k : o.keySet()) {
                if (jsonContainsDollarTable(o.get(k))) {
                    return true;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) {
                if (jsonContainsDollarTable(a.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean jsonContainsDollarInfotable(Object value) {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            if (o.has("$infotable")) {
                return true;
            }
            for (String k : o.keySet()) {
                if (jsonContainsDollarInfotable(o.get(k))) {
                    return true;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) {
                if (jsonContainsDollarInfotable(a.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static InfoTable resolveTableRef(PlaybookRunContext ctx, String ref) throws PlaybookRunException {
        if (ref == null || ref.isBlank()) {
            throw new PlaybookRunException("empty $table reference", "TABLE_REF_INVALID_FORMAT");
        }
        if (!ref.endsWith(".result")) {
            throw new PlaybookRunException("invalid $table ref (v1 requires <nodeId>.result): " + ref,
                    "TABLE_REF_INVALID_FORMAT");
        }
        InfoTable t = ctx.rawTable(ref);
        if (t == null) {
            throw new PlaybookRunException("no raw table retained for ref: " + ref, "TABLE_REF_NOT_FOUND");
        }
        return t;
    }

    private static Object navigateRef(PlaybookRunContext ctx, String ref) throws PlaybookRunException {
        int dot = ref.indexOf('.');
        if (dot <= 0) {
            throw new PlaybookRunException("invalid $ref: " + ref);
        }
        String nodeId = ref.substring(0, dot);
        JSONObject nodeOut = ctx.nodeOutput(nodeId);
        if (nodeOut == null) {
            if (ctx.isSkipped(nodeId)) {
                return null;
            }
            throw new PlaybookRunException("missing node output for ref: " + ref);
        }
        return navigate(nodeOut, ref.substring(dot + 1), true);
    }

    private static Object navigate(Object root, String path) throws PlaybookRunException {
        return navigate(root, path, false);
    }

    @SuppressWarnings("unchecked")
    private static Object navigate(Object root, String path, boolean normalizeToolOutputResult)
            throws PlaybookRunException {
        if (path == null || path.isEmpty()) {
            return root;
        }
        String[] parts = path.split("\\.");
        Object cur = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (cur == null) {
                return null;
            }
            if (cur instanceof JSONObject) {
                JSONObject o = (JSONObject) cur;
                cur = o.has(part) ? o.get(part) : null;
            } else if (cur instanceof java.util.Map) {
                cur = ((java.util.Map<?, ?>) cur).get(part);
            } else {
                throw new PlaybookRunException("cannot navigate path segment " + part + " on " + cur.getClass().getName());
            }
            if (normalizeToolOutputResult && "result".equals(part) && i > 0
                    && "toolOutput".equals(parts[i - 1]) && i < parts.length - 1) {
                cur = PlaybookEvidencePathSupport.normalizeJsonResultValue(cur);
            }
        }
        return cur;
    }
}
