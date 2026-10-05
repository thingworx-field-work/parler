package com.thingworx.things.agent.tools.predicate;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.data.filters.AndFilterCollection;
import com.thingworx.types.data.filters.FilterFactory;
import com.thingworx.types.data.filters.IFilter;
import com.thingworx.types.data.filters.OrFilterCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.things.agent.tools.CachedTabularDecisionPredicate;
import com.thingworx.things.agent.tools.CachedTabularDecisionToolException;
import com.thingworx.things.agent.tools.ParlerInfotableJsonUtil;

/**
 * Parses ThingWorx-aligned filter JSON for cached tabular tools into {@link IFilter} instances
 * (see {@code docs/agent/query-spec.md}).
 */
public final class ParlerQueryFilterParser {

    /** Spec §8.1 layer 6 — composite leaf count cap (whole tree). */
    public static final int MAX_LEAVES = 32;
    /** Spec §3.2 — composite nesting cap. */
    public static final int MAX_COMPOSITE_DEPTH = 4;
    /** Same cap as legacy {@link com.thingworx.things.agent.tools.CachedTabularDecisionPredicate} LIKE guard. */
    public static final int MAX_LIKE_PATTERN_LENGTH = 256;

    private static final Set<String> ALLOWED_LEAF_KEYS = new HashSet<>();

    static {
        ALLOWED_LEAF_KEYS.add("type");
        ALLOWED_LEAF_KEYS.add(CommonPropertyNames.PROP_FIELDNAME);
        ALLOWED_LEAF_KEYS.add(CommonPropertyNames.PROP_VALUE);
        ALLOWED_LEAF_KEYS.add(CommonPropertyNames.PROP_VALUES);
        ALLOWED_LEAF_KEYS.add(CommonPropertyNames.PROP_FROM);
        ALLOWED_LEAF_KEYS.add(CommonPropertyNames.PROP_TO);
        ALLOWED_LEAF_KEYS.add(CommonPropertyNames.PROP_ISCASESENSITIVE);
        ALLOWED_LEAF_KEYS.add(CommonPropertyNames.PROP_LOCATION);
        ALLOWED_LEAF_KEYS.add(CommonPropertyNames.PROP_DISTANCE);
        ALLOWED_LEAF_KEYS.add(CommonPropertyNames.PROP_UNITS);
        ALLOWED_LEAF_KEYS.add(CommonPropertyNames.PROP_TAGS);
    }

    private ParlerQueryFilterParser() {}

    /**
     * Whether {@code key} is permitted on a ThingWorx platform leaf filter object (same allowlist as cached tabular
     * {@link #ALLOWED_LEAF_KEYS}; used by {@link com.thingworx.things.agent.tools.QueryEntitiesQueryAdmission}).
     */
    public static boolean isAllowedLeafKeyForPlatformQuery(String key) {
        return key != null && ALLOWED_LEAF_KEYS.contains(key);
    }

    public static IFilter parse(JsonNode filterRoot, DataShapeDefinition shape) throws CachedTabularDecisionToolException {
        if (filterRoot == null || filterRoot.isNull()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "filters must be a JSON object.");
        }
        if (!filterRoot.isObject()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "filters must be a JSON object.");
        }
        int leaves = countLeaves(filterRoot);
        if (leaves > MAX_LEAVES) {
            throw new CachedTabularDecisionToolException("TOO_MANY_PREDICATES",
                    "Predicate exceeds maximum of " + MAX_LEAVES + " leaf conditions.");
        }
        int depth = maxCompositeDepth(filterRoot);
        if (depth > MAX_COMPOSITE_DEPTH) {
            throw new CachedTabularDecisionToolException("TOO_MANY_PREDICATES",
                    "Predicate boolean depth exceeds maximum of " + MAX_COMPOSITE_DEPTH + ".");
        }
        try {
            return parseInternal(filterRoot, shape);
        } catch (CachedTabularDecisionToolException e) {
            throw e;
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", m);
        }
    }

    private static IFilter parseInternal(JsonNode n, DataShapeDefinition shape) throws Exception {
        JsonNode typeN = n.get("type");
        if (typeN == null || !typeN.isTextual() || typeN.asText().trim().isEmpty()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                    PredicateErrorMessages.unknownLeafKeys("type", n));
        }
        String type = typeN.asText().trim().toUpperCase(Locale.ROOT);
        if (FilterFactory.FILTER_COMPOSITE_AND.equals(type) || FilterFactory.FILTER_COMPOSITE_OR.equals(type)) {
            JsonNode arr = n.get(CommonPropertyNames.PROP_FILTERS);
            if (arr == null || !arr.isArray() || arr.size() < 1) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                        type + " requires a non-empty filters array.");
            }
            if (FilterFactory.FILTER_COMPOSITE_AND.equals(type)) {
                AndFilterCollection out = new AndFilterCollection();
                for (JsonNode c : arr) {
                    out.addFilter(parseInternal(c, shape));
                }
                return out;
            }
            OrFilterCollection out = new OrFilterCollection();
            for (JsonNode c : arr) {
                out.addFilter(parseInternal(c, shape));
            }
            return out;
        }
        if (FilterFactory.FILTER_COMPOSITE_NOT.equals(type)) {
            JsonNode arr = n.get(CommonPropertyNames.PROP_FILTERS);
            if (arr == null || !arr.isArray() || arr.size() != 1) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                        "NOT requires filters array of length 1.");
            }
            return new NotFilter(parseInternal(arr.get(0), shape));
        }
        return parseLeaf(n, shape, type);
    }

    private static IFilter parseLeaf(JsonNode n, DataShapeDefinition shape, String type) throws Exception {
        Iterator<String> fn = n.fieldNames();
        while (fn.hasNext()) {
            String k = fn.next();
            if (!ALLOWED_LEAF_KEYS.contains(k)) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                        PredicateErrorMessages.unknownLeafKeyNotAllowed(k, n));
            }
        }
        JsonNode fieldNode = n.get(CommonPropertyNames.PROP_FIELDNAME);
        if (fieldNode == null || !fieldNode.isTextual() || fieldNode.asText().trim().isEmpty()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                    PredicateErrorMessages.unknownLeafKeys(CommonPropertyNames.PROP_FIELDNAME, n));
        }
        String fieldName = fieldNode.asText().trim();
        if (ParlerInfotableJsonUtil.isPasswordColumn(shape, fieldName)) {
            throw new CachedTabularDecisionToolException("PROTECTED_TABULAR_COLUMN_BLOCKED",
                    "Cannot use PASSWORD column \"" + fieldName + "\" in filters.");
        }
        BaseTypes colBt = CachedTabularDecisionPredicate.columnBaseType(shape, null, fieldName);
        if (colBt == null) {
            throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + fieldName);
        }
        if (FilterFactory.FILTER_MATCHES.equals(type) || FilterFactory.FILTER_NOT_MATCHES.equals(type)) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", PredicateErrorMessages.matchesNotSupported());
        }
        switch (type) {
            case "CONTAINS":
            case "NOTCONTAINS":
                return buildSubstring(fieldName, ParlerLiteralSubstringFilter.Mode.CONTAINS, !"NOTCONTAINS".equals(type), n, colBt);
            case "STARTSWITH":
            case "NOTSTARTSWITH":
                return buildSubstring(fieldName, ParlerLiteralSubstringFilter.Mode.STARTS_WITH, !"NOTSTARTSWITH".equals(type), n, colBt);
            case "ENDSWITH":
            case "NOTENDSWITH":
                return buildSubstring(fieldName, ParlerLiteralSubstringFilter.Mode.ENDS_WITH, !"NOTENDSWITH".equals(type), n, colBt);
            case "ISEMPTY":
            case "NOTEMPTY":
                if (!CachedTabularDecisionPredicate.isStringFamilyForIsempty(colBt)) {
                    throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                            PredicateErrorMessages.isEmptyRequiresStringFamily(fieldName, baseTypeLabel(colBt)));
                }
                return new ParlerEmptyStringCellFilter(fieldName, "ISEMPTY".equals(type));
            case FilterFactory.FILTER_NEAR:
            case FilterFactory.FILTER_NOTNEAR:
                if (colBt != BaseTypes.LOCATION) {
                    throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                            PredicateErrorMessages.nearRequiresLocationColumn(fieldName, baseTypeLabel(colBt)));
                }
                return FilterFactory.createFilter(shape, fieldName, type, normalizeNearJson(n));
            case FilterFactory.FILTER_TAGGEDWITH:
            case FilterFactory.FILTER_NOT_TAGGEDWITH:
                if (colBt != BaseTypes.TAGS) {
                    throw new CachedTabularDecisionToolException("TYPE_MISMATCH",
                            PredicateErrorMessages.taggedRequiresTagsColumn(fieldName, baseTypeLabel(colBt)));
                }
                return FilterFactory.createFilter(shape, fieldName, type, new JSONObject(n.toString()));
            case FilterFactory.FILTER_LIKE:
            case FilterFactory.FILTER_NOT_LIKE: {
                JsonNode val = n.get(CommonPropertyNames.PROP_VALUE);
                if (val != null && val.isTextual() && val.asText().length() > MAX_LIKE_PATTERN_LENGTH) {
                    throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                            "like pattern exceeds max length " + MAX_LIKE_PATTERN_LENGTH + ".");
                }
                break;
            }
            case FilterFactory.FILTER_BETWEEN:
            case FilterFactory.FILTER_NOTBETWEEN:
                if (!n.has(CommonPropertyNames.PROP_FROM) || n.get(CommonPropertyNames.PROP_FROM).isNull()
                        || !n.has(CommonPropertyNames.PROP_TO) || n.get(CommonPropertyNames.PROP_TO).isNull()) {
                    throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                            type + " requires from and to.");
                }
                break;
            case FilterFactory.FILTER_IN:
            case FilterFactory.FILTER_NOT_IN: {
                JsonNode values = n.get(CommonPropertyNames.PROP_VALUES);
                if (values == null || !values.isArray() || values.size() < 1) {
                    throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "IN/NOTIN requires non-empty values array.");
                }
                break;
            }
            default:
        }
        JSONObject jo = new JSONObject(n.toString());
        IFilter f = FilterFactory.createFilter(shape, fieldName, type, jo);
        if (f == null) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", PredicateErrorMessages.unknownFilterType(type));
        }
        return f;
    }

    private static IFilter buildSubstring(String fieldName, ParlerLiteralSubstringFilter.Mode mode, boolean inclusive,
            JsonNode n, BaseTypes colBt) throws CachedTabularDecisionToolException {
        if (!CachedTabularDecisionPredicate.isPlainOrRichStringColumnForSubstringFilters(colBt)) {
            throw new CachedTabularDecisionToolException("UNSUPPORTED_OPERATOR",
                    "Substring filter is not supported for column type " + baseTypeLabel(colBt) + ".");
        }
        JsonNode val = n.get(CommonPropertyNames.PROP_VALUE);
        if (val == null || val.isNull()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "Substring filter requires value.");
        }
        String needle = val.isTextual() ? val.asText() : val.asText();
        boolean caseSens = n.path(CommonPropertyNames.PROP_ISCASESENSITIVE).asBoolean(false);
        return new ParlerLiteralSubstringFilter(fieldName, mode, needle, caseSens, inclusive);
    }

    private static JSONObject normalizeNearJson(JsonNode n) throws CachedTabularDecisionToolException {
        JSONObject jo = new JSONObject(n.toString());
        if (!jo.has(CommonPropertyNames.PROP_LOCATION)) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "NEAR requires location.");
        }
        if (!jo.has(CommonPropertyNames.PROP_DISTANCE)) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "NEAR requires distance.");
        }
        String rawUnits = jo.optString(CommonPropertyNames.PROP_UNITS, "M");
        jo.put(CommonPropertyNames.PROP_UNITS, normalizeNearUnitsOrThrow(rawUnits));
        return jo;
    }

    /**
     * @param units raw units string from JSON (may be empty — TWX defaults vary; spec requires explicit normalization set)
     */
    public static String normalizeNearUnitsOrThrow(String units) throws CachedTabularDecisionToolException {
        if (units == null) {
            units = "";
        }
        String t = units.trim();
        if (t.isEmpty()) {
            return "M";
        }
        String u = t.toLowerCase(Locale.ROOT);
        switch (u) {
            case "m":
            case "mile":
            case "miles":
                return "M";
            case "k":
            case "km":
            case "kilometer":
            case "kilometers":
            case "kilometre":
            case "kilometres":
                return "K";
            case "n":
            case "nm":
            case "nautical_mile":
            case "nautical_miles":
            case "nauticalmile":
            case "nauticalmiles":
                return "N";
            default:
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                        "Unknown NEAR units '" + units + "'. Accepted: M/mile(s), K/km/kilometer(s), N/nm/nautical_mile(s). "
                                + "See docs/agent/query-spec.md §3.8.");
        }
    }

    private static String baseTypeLabel(BaseTypes bt) {
        return bt == null ? "UNKNOWN" : bt.name();
    }

    public static int countLeaves(JsonNode n) {
        if (n == null || !n.isObject()) {
            return 0;
        }
        JsonNode typeN = n.get("type");
        if (typeN == null || !typeN.isTextual()) {
            return 1;
        }
        String type = typeN.asText().trim().toUpperCase(Locale.ROOT);
        if (FilterFactory.FILTER_COMPOSITE_AND.equals(type) || FilterFactory.FILTER_COMPOSITE_OR.equals(type)) {
            JsonNode arr = n.get(CommonPropertyNames.PROP_FILTERS);
            if (arr == null || !arr.isArray()) {
                return 1;
            }
            int s = 0;
            for (JsonNode c : arr) {
                s += countLeaves(c);
            }
            return s;
        }
        if (FilterFactory.FILTER_COMPOSITE_NOT.equals(type)) {
            JsonNode arr = n.get(CommonPropertyNames.PROP_FILTERS);
            if (arr == null || !arr.isArray() || arr.size() != 1) {
                return 1;
            }
            return countLeaves(arr.get(0));
        }
        return 1;
    }

    public static int maxCompositeDepth(JsonNode n) {
        if (n == null || !n.isObject()) {
            return 0;
        }
        JsonNode typeN = n.get("type");
        if (typeN == null || !typeN.isTextual()) {
            return 1;
        }
        String type = typeN.asText().trim().toUpperCase(Locale.ROOT);
        if (FilterFactory.FILTER_COMPOSITE_AND.equals(type) || FilterFactory.FILTER_COMPOSITE_OR.equals(type)) {
            JsonNode arr = n.get(CommonPropertyNames.PROP_FILTERS);
            if (arr == null || !arr.isArray()) {
                return 1;
            }
            int m = 1;
            for (JsonNode c : arr) {
                m = Math.max(m, 1 + maxCompositeDepth(c));
            }
            return m;
        }
        if (FilterFactory.FILTER_COMPOSITE_NOT.equals(type)) {
            JsonNode arr = n.get(CommonPropertyNames.PROP_FILTERS);
            if (arr == null || !arr.isArray() || arr.size() != 1) {
                return 1;
            }
            return 1 + maxCompositeDepth(arr.get(0));
        }
        return 1;
    }

    /**
     * Collects column names referenced for PASSWORD sweep (query-spec §8.2): canonical {@code fieldName} on TWX
     * leaves, plus legacy {@code column} / {@code field} / {@code fieldname} keys anywhere in the subtree so
     * {@link com.thingworx.things.agent.tools.TabularPasswordColumnGuard} runs before legacy-shape rejection.
     */
    public static void collectFieldNames(JsonNode n, Set<String> out) {
        walkFilterJsonForColumnReferences(n, out);
    }

    private static void walkFilterJsonForColumnReferences(JsonNode n, Set<String> out) {
        if (n == null || !n.isObject()) {
            return;
        }
        addColumnReferenceKeys(n, out);
        JsonNode typeN = n.get("type");
        String type = (typeN != null && typeN.isTextual()) ? typeN.asText().trim().toUpperCase(Locale.ROOT) : null;
        if (FilterFactory.FILTER_COMPOSITE_AND.equals(type) || FilterFactory.FILTER_COMPOSITE_OR.equals(type)) {
            descendFiltersChildren(n, out);
            return;
        }
        if (FilterFactory.FILTER_COMPOSITE_NOT.equals(type)) {
            JsonNode arr = n.get(CommonPropertyNames.PROP_FILTERS);
            if (arr != null && arr.isArray() && arr.size() == 1) {
                walkFilterJsonForColumnReferences(arr.get(0), out);
            }
            return;
        }
        if (n.has("all")) {
            JsonNode arr = n.get("all");
            if (arr != null && arr.isArray()) {
                for (JsonNode c : arr) {
                    walkFilterJsonForColumnReferences(c, out);
                }
            }
            return;
        }
        if (n.has("any")) {
            JsonNode arr = n.get("any");
            if (arr != null && arr.isArray()) {
                for (JsonNode c : arr) {
                    walkFilterJsonForColumnReferences(c, out);
                }
            }
            return;
        }
        if (n.has("not")) {
            walkFilterJsonForColumnReferences(n.get("not"), out);
        }
    }

    private static void descendFiltersChildren(JsonNode n, Set<String> out) {
        JsonNode arr = n.get(CommonPropertyNames.PROP_FILTERS);
        if (arr != null && arr.isArray()) {
            for (JsonNode c : arr) {
                walkFilterJsonForColumnReferences(c, out);
            }
        }
    }

    private static void addColumnReferenceKeys(JsonNode n, Set<String> out) {
        addTrimmedText(n, CommonPropertyNames.PROP_FIELDNAME, out);
        addTrimmedText(n, "column", out);
        addTrimmedText(n, "field", out);
        addTrimmedText(n, "fieldname", out);
    }

    private static void addTrimmedText(JsonNode n, String key, Set<String> out) {
        JsonNode v = n.get(key);
        if (v != null && v.isTextual()) {
            String s = v.asText().trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
    }
}
