package com.thingworx.things.agent.tools;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.data.filters.FilterFactory;
import com.thingworx.things.agent.tools.predicate.ParlerQueryFilterParser;

/**
 * Offline admission for {@code query_entities} QUERY JSON before {@link QueryJsonPrimitiveMapper} builds
 * {@link com.thingworx.types.primitives.JSONPrimitive}. See {@code docs/agent/model-tool-admission-guardrails.md}.
 */
public final class QueryEntitiesQueryAdmission {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * ThingWorx-native leaf {@code type} values allowed on the {@code query_entities} platform dispatch path.
     * Case-insensitive match; original JSON is passed through unchanged. Excludes Parler in-memory-only leaves
     * ({@code CONTAINS}, {@code STARTSWITH}, {@code ISEMPTY}, …) per design review.
     */
    private static final Set<String> PLATFORM_LEAF_TYPES_UC = new HashSet<>();

    static {
        PLATFORM_LEAF_TYPES_UC.add(FilterFactory.FILTER_IN.toUpperCase(Locale.ROOT));
        PLATFORM_LEAF_TYPES_UC.add(FilterFactory.FILTER_NOT_IN.toUpperCase(Locale.ROOT));
        PLATFORM_LEAF_TYPES_UC.add(FilterFactory.FILTER_BETWEEN.toUpperCase(Locale.ROOT));
        PLATFORM_LEAF_TYPES_UC.add(FilterFactory.FILTER_NOTBETWEEN.toUpperCase(Locale.ROOT));
        PLATFORM_LEAF_TYPES_UC.add(FilterFactory.FILTER_LIKE.toUpperCase(Locale.ROOT));
        PLATFORM_LEAF_TYPES_UC.add(FilterFactory.FILTER_NOT_LIKE.toUpperCase(Locale.ROOT));
        PLATFORM_LEAF_TYPES_UC.add(FilterFactory.FILTER_NEAR.toUpperCase(Locale.ROOT));
        PLATFORM_LEAF_TYPES_UC.add(FilterFactory.FILTER_NOTNEAR.toUpperCase(Locale.ROOT));
        PLATFORM_LEAF_TYPES_UC.add(FilterFactory.FILTER_TAGGEDWITH.toUpperCase(Locale.ROOT));
        PLATFORM_LEAF_TYPES_UC.add(FilterFactory.FILTER_NOT_TAGGEDWITH.toUpperCase(Locale.ROOT));
        // MATCHES / NOTMATCHES omitted in v1 (align with cached ParlerQueryFilterParser — regex surface); add with
        // expression key + admission when a platform dispatch path is confirmed.
        for (String s : new String[] {"EQ", "NE", "LT", "LE", "GT", "GE", "MISSINGVALUE", "NOTMISSINGVALUE"}) {
            PLATFORM_LEAF_TYPES_UC.add(s);
        }
    }

    private static final Set<String> PARLER_ONLY_SUBSTRING_LEAVES_UC = new HashSet<>();

    static {
        for (String s : new String[] {"CONTAINS", "NOTCONTAINS", "STARTSWITH", "NOTSTARTSWITH", "ENDSWITH", "NOTENDSWITH",
                "ISEMPTY", "NOTEMPTY"}) {
            PARLER_ONLY_SUBSTRING_LEAVES_UC.add(s);
        }
    }

    private QueryEntitiesQueryAdmission() {}

    /**
     * @return {@code null} if admission passes; otherwise UTF-8 JSON tool error string with {@code status},
     *         {@code code}, {@code message}, optional {@code path}, optional {@code recoveryHint}.
     */
    public static String validateOrErrorJson(JsonNode queryNode) {
        if (queryNode == null || queryNode.isNull()) {
            return null;
        }
        final JsonNode queryNorm;
        try {
            JSONObject jo = QueryJsonPrimitiveMapper.parseQueryObject("query", queryNode, MAPPER);
            QueryJsonPrimitiveMapper.validateQueryObject(jo, "query");
            // Re-root predicate admission on the parsed QUERY envelope so textual `query` JSON matches object-shaped
            // tool JSON (QueryJsonPrimitiveMapper textual-object recovery).
            queryNorm = MAPPER.readTree(jo.toString());
        } catch (IllegalArgumentException e) {
            return errorJson("INVALID_PREDICATE", e.getMessage(), "query", null);
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return errorJson("INVALID_PREDICATE", "query could not be normalized for admission: " + m, "query", null);
        }
        if (!queryNorm.has("filters") || queryNorm.get("filters").isNull()) {
            return null;
        }
        JsonNode filters = queryNorm.get("filters");
        if (!filters.isObject()) {
            return errorJson("INVALID_PREDICATE", "query.filters must be a JSON object (leaf or composite filter).",
                    "query.filters", null);
        }
        int leaves = ParlerQueryFilterParser.countLeaves(filters);
        if (leaves > ParlerQueryFilterParser.MAX_LEAVES) {
            return errorJson("TOO_MANY_PREDICATES",
                    "Predicate exceeds maximum of " + ParlerQueryFilterParser.MAX_LEAVES + " leaf conditions.",
                    "query.filters", null);
        }
        int depth = ParlerQueryFilterParser.maxCompositeDepth(filters);
        if (depth > ParlerQueryFilterParser.MAX_COMPOSITE_DEPTH) {
            return errorJson("TOO_MANY_PREDICATES",
                    "Predicate boolean depth exceeds maximum of " + ParlerQueryFilterParser.MAX_COMPOSITE_DEPTH + ".",
                    "query.filters", null);
        }
        return validateFilterNode(filters, "query.filters");
    }

    private static String validateFilterNode(JsonNode n, String path) {
        if (n == null || !n.isObject()) {
            return errorJson("INVALID_PREDICATE", "Filter node must be a JSON object.", path, null);
        }
        JsonNode typeN = n.get("type");
        if (typeN == null || typeN.isNull() || !typeN.isTextual() || typeN.asText().trim().isEmpty()) {
            return errorJson("INVALID_PREDICATE", "Each filter object requires a non-empty textual type.", path + ".type",
                    null);
        }
        String type = typeN.asText().trim();
        String typeUpper = type.toUpperCase(Locale.ROOT);
        if (typeUpper.equalsIgnoreCase(FilterFactory.FILTER_COMPOSITE_AND)
                || typeUpper.equalsIgnoreCase(FilterFactory.FILTER_COMPOSITE_OR)) {
            JsonNode arr = n.get(CommonPropertyNames.PROP_FILTERS);
            if (arr == null || !arr.isArray()) {
                return errorJson("INVALID_PREDICATE", typeUpper + " requires a filters array.", path + ".filters", null);
            }
            if (arr.size() < 1) {
                return errorJson("INVALID_PREDICATE", typeUpper + " requires a non-empty filters array.", path + ".filters",
                        null);
            }
            for (int i = 0; i < arr.size(); i++) {
                String cp = path + ".filters[" + i + "]";
                String err = validateFilterNode(arr.get(i), cp);
                if (err != null) {
                    return err;
                }
            }
            return null;
        }
        if (typeUpper.equalsIgnoreCase(FilterFactory.FILTER_COMPOSITE_NOT)) {
            JsonNode arr = n.get(CommonPropertyNames.PROP_FILTERS);
            if (arr == null || !arr.isArray()) {
                return errorJson("INVALID_PREDICATE", "NOT requires a filters array.", path + ".filters", null);
            }
            if (arr.size() != 1) {
                return errorJson("INVALID_PREDICATE", "NOT requires filters array of length 1.", path + ".filters", null);
            }
            return errorJson(
                    "UNSUPPORTED_PREDICATE_FOR_QUERY_ENTITIES",
                    "query_entities.query does not support composite NOT on this path. Build both cached entity lists "
                            + "and call analyze_entity_set with operation=\"difference\".",
                    path,
                    recoveryHintDifference());
        }
        if (PARLER_ONLY_SUBSTRING_LEAVES_UC.contains(typeUpper)) {
            return errorJson("UNSUPPORTED_OPERATOR",
                    "Filter type '" + type.trim()
                            + "' is not supported for query_entities (Parler in-memory-only predicate). "
                            + "Use ThingWorx-native types (EQ, IN, LIKE, …) or cached tabular tools.",
                    path + ".type", null);
        }
        if (!PLATFORM_LEAF_TYPES_UC.contains(typeUpper)) {
            return errorJson("UNSUPPORTED_OPERATOR",
                    "Filter type '" + type.trim() + "' is not supported for query_entities platform dispatch.",
                    path + ".type", null);
        }
        JsonNode fieldNode = n.get(CommonPropertyNames.PROP_FIELDNAME);
        if (fieldNode == null || !fieldNode.isTextual() || fieldNode.asText().trim().isEmpty()) {
            return errorJson("INVALID_PREDICATE", "Leaf filter requires non-empty textual fieldName.",
                    path + ".fieldName", null);
        }
        String leafErr = validateLeafValueKeys(type, n, path);
        if (leafErr != null) {
            return leafErr;
        }
        return validateLeafUnknownKeys(n, path);
    }

    private static String validateLeafValueKeys(String typeRaw, JsonNode n, String path) {
        if (typeRaw.equalsIgnoreCase(FilterFactory.FILTER_IN) || typeRaw.equalsIgnoreCase(FilterFactory.FILTER_NOT_IN)) {
            JsonNode values = n.get(CommonPropertyNames.PROP_VALUES);
            if (values == null || !values.isArray() || values.size() < 1) {
                return errorJson("INVALID_PREDICATE", typeRaw.toUpperCase(Locale.ROOT) + " requires non-empty values array.",
                        path + ".values", null);
            }
        } else if (typeRaw.equalsIgnoreCase(FilterFactory.FILTER_BETWEEN)
                || typeRaw.equalsIgnoreCase(FilterFactory.FILTER_NOTBETWEEN)) {
            if (!n.has(CommonPropertyNames.PROP_FROM) || n.get(CommonPropertyNames.PROP_FROM).isNull()
                    || !n.has(CommonPropertyNames.PROP_TO) || n.get(CommonPropertyNames.PROP_TO).isNull()) {
                return errorJson("INVALID_PREDICATE", typeRaw.toUpperCase(Locale.ROOT) + " requires from and to.", path,
                        null);
            }
        } else if (typeRaw.equalsIgnoreCase(FilterFactory.FILTER_NEAR)
                || typeRaw.equalsIgnoreCase(FilterFactory.FILTER_NOTNEAR)) {
            if (!n.has(CommonPropertyNames.PROP_LOCATION) || n.get(CommonPropertyNames.PROP_LOCATION).isNull()) {
                return errorJson("INVALID_PREDICATE", "NEAR requires location.", path + ".location", null);
            }
            if (!n.has(CommonPropertyNames.PROP_DISTANCE) || n.get(CommonPropertyNames.PROP_DISTANCE).isNull()) {
                return errorJson("INVALID_PREDICATE", "NEAR requires distance.", path + ".distance", null);
            }
        } else if (typeRaw.equalsIgnoreCase(FilterFactory.FILTER_TAGGEDWITH)
                || typeRaw.equalsIgnoreCase(FilterFactory.FILTER_NOT_TAGGEDWITH)) {
            JsonNode tags = n.get(CommonPropertyNames.PROP_TAGS);
            if (tags == null || tags.isNull() || (tags.isArray() && tags.size() < 1)) {
                return errorJson("INVALID_PREDICATE", typeRaw.toUpperCase(Locale.ROOT) + " requires non-empty tags.",
                        path + ".tags", null);
            }
        } else if (requiresScalarOrLikeValue(typeRaw)) {
            if (!n.has(CommonPropertyNames.PROP_VALUE) || n.get(CommonPropertyNames.PROP_VALUE).isNull()) {
                return errorJson("INVALID_PREDICATE", typeRaw.toUpperCase(Locale.ROOT) + " requires value.",
                        path + ".value", null);
            }
            JsonNode v = n.get(CommonPropertyNames.PROP_VALUE);
            if (typeRaw.equalsIgnoreCase(FilterFactory.FILTER_LIKE)
                    || typeRaw.equalsIgnoreCase(FilterFactory.FILTER_NOT_LIKE)) {
                if (v.isTextual() && v.asText().length() > ParlerQueryFilterParser.MAX_LIKE_PATTERN_LENGTH) {
                    return errorJson("INVALID_PREDICATE",
                            "like pattern exceeds max length " + ParlerQueryFilterParser.MAX_LIKE_PATTERN_LENGTH + ".",
                            path + ".value", null);
                }
            }
        }
        return null;
    }

    /** Scalar comparisons and LIKE/NOTLIKE require a non-null {@code value} (MISSINGVALUE types do not). */
    private static boolean requiresScalarOrLikeValue(String typeRaw) {
        return typeRaw.equalsIgnoreCase("EQ")
                || typeRaw.equalsIgnoreCase("NE")
                || typeRaw.equalsIgnoreCase("LT")
                || typeRaw.equalsIgnoreCase("LE")
                || typeRaw.equalsIgnoreCase("GT")
                || typeRaw.equalsIgnoreCase("GE")
                || typeRaw.equalsIgnoreCase(FilterFactory.FILTER_LIKE)
                || typeRaw.equalsIgnoreCase(FilterFactory.FILTER_NOT_LIKE);
    }

    private static String validateLeafUnknownKeys(JsonNode n, String path) {
        Iterator<String> fn = n.fieldNames();
        while (fn.hasNext()) {
            String k = fn.next();
            if (!ParlerQueryFilterParser.isAllowedLeafKeyForPlatformQuery(k)) {
                return errorJson("INVALID_PREDICATE", "Unknown or disallowed key on leaf filter: " + k, path + "." + k,
                        null);
            }
        }
        return null;
    }

    private static ObjectNode recoveryHintDifference() {
        ObjectNode h = MAPPER.createObjectNode();
        h.put("tool", "analyze_entity_set");
        h.put("operation", "difference");
        return h;
    }

    private static String errorJson(String code, String message, String path, ObjectNode recoveryHint) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message == null ? "" : message);
            if (path != null) {
                o.put("path", path);
            }
            if (recoveryHint != null) {
                o.set("recoveryHint", recoveryHint);
            }
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + code + "\"}";
        }
    }
}
