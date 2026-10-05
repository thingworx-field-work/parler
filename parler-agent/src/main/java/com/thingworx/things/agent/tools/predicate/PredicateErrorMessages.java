package com.thingworx.things.agent.tools.predicate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.types.constants.CommonPropertyNames;

/**
 * Stable user-facing messages for query-spec §8.3 (cached tabular predicate / sort shape errors).
 */
public final class PredicateErrorMessages {

    /** Substring tests and INVALID_PREDICATE recovery copy. */
    public static final String UNCONDITIONAL_MEASURE_OMIT_FILTERS_RECOVERY =
            "If this was meant to be an unconditional measure, omit the filters field entirely; do not send "
                    + "{\"type\":\"TRUE\"} or similar placeholder predicates.";

    private PredicateErrorMessages() {}

    public static String rejectedLegacyPredicateShape() {
        return "Filter uses rejected legacy shape '{op, column, value}'. Canonical: '{type, fieldName, value}'. "
                + "See docs/agent/query-spec.md §3.1.";
    }

    public static String rejectedLegacySortShape() {
        return "Sort uses rejected legacy keys ('sortBy' / 'direction' / sort element 'column' / 'direction'). "
                + "Canonical: 'sorts': [{'fieldName': '<col>', 'isAscending': true | false}]. See docs/agent/query-spec.md §4.";
    }

    public static String rejectedLegacyEnvelope(String detail) {
        return detail + " See docs/agent/query-spec.md §7.1.";
    }

    public static String unknownFilterType(String got) {
        return "Unknown filter type '" + got + "'. Common types: EQ, NE, LT, LE, GT, GE, IN, NOTIN, BETWEEN, LIKE, "
                + "MISSINGVALUE. Full list: query-spec.md §3.3.";
    }

    public static String matchesNotSupported() {
        return "Filter type 'MATCHES' is not supported in cached predicate (regex DoS risk). "
                + "Use LIKE with * / ? wildcards, or CONTAINS / STARTSWITH / ENDSWITH.";
    }

    public static String unknownLeafKeys(String missingKey, JsonNode leaf) {
        List<String> got = new ArrayList<>();
        Iterator<String> it = leaf.fieldNames();
        while (it.hasNext()) {
            got.add(it.next());
        }
        Collections.sort(got);
        String base = "Leaf filter requires key '" + missingKey + "' (got keys: " + got + "). Recognized leaf keys: type, "
                + "fieldName, value, values, from, to, isCaseSensitive, location, distance, units, tags. "
                + "See docs/agent/query-spec.md §3.";
        if (CommonPropertyNames.PROP_FIELDNAME.equals(missingKey) && isStandaloneTrueTypePlaceholder(leaf)) {
            return base + " " + UNCONDITIONAL_MEASURE_OMIT_FILTERS_RECOVERY;
        }
        return base;
    }

    /**
     * Detects {@code {"type":"TRUE"}} / {@code {"type":true}} — not accepted as a filter; used only for error hints.
     */
    static boolean isStandaloneTrueTypePlaceholder(JsonNode leaf) {
        if (leaf == null || !leaf.isObject()) {
            return false;
        }
        if (leaf.size() != 1 || !leaf.has("type")) {
            return false;
        }
        JsonNode t = leaf.get("type");
        if (t == null || t.isNull()) {
            return false;
        }
        if (t.isBoolean()) {
            return t.booleanValue();
        }
        if (t.isTextual()) {
            return "TRUE".equalsIgnoreCase(t.asText().trim());
        }
        return false;
    }

    public static String unknownLeafKeyNotAllowed(String keyName, JsonNode leaf) {
        TreeSet<String> got = new TreeSet<>();
        leaf.fieldNames().forEachRemaining(got::add);
        return "Leaf filter has unknown key '" + keyName + "' (got keys: " + got + "). Recognized leaf keys: type, "
                + "fieldName, value, values, from, to, isCaseSensitive, location, distance, units, tags. "
                + "See docs/agent/query-spec.md §3.";
    }

    public static String sortEntryRequiresFieldName(JsonNode el) {
        TreeSet<String> got = new TreeSet<>();
        if (el != null && el.isObject()) {
            el.fieldNames().forEachRemaining(got::add);
        }
        return "Sort entry requires 'fieldName' (got keys: " + got + "). Recognized sort keys: fieldName, isAscending, "
                + "isCaseSensitive. See docs/agent/query-spec.md §4.";
    }

    public static String nearRequiresLocationColumn(String col, String actualBaseType) {
        return "Filter type 'NEAR' requires a LOCATION column (got base type '" + actualBaseType + "' for column '"
                + col + "'). See query-spec.md §3.7.";
    }

    public static String taggedRequiresTagsColumn(String col, String actualBaseType) {
        return "Filter type 'TAGGED' requires a TAGS column (got base type '" + actualBaseType + "' for column '"
                + col + "'). See query-spec.md §3.7.";
    }

    public static String isEmptyRequiresStringFamily(String col, String actualBaseType) {
        return "Filter type 'ISEMPTY' requires a string-family column (STRING, TEXT, GUID, *NAME, HYPERLINK, "
                + "IMAGELINK, HTML, or XML — see query-spec.md §3.9); got base type '" + actualBaseType + "' for column '"
                + col + "'.";
    }

    /** Normalizes TWX / JSON unit strings for error messages. */
    public static String normalizeUnitsToken(String units) {
        if (units == null) {
            return "";
        }
        return units.trim().toLowerCase(Locale.ROOT);
    }
}
