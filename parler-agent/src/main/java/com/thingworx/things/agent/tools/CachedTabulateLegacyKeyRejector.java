package com.thingworx.things.agent.tools;

import java.util.Iterator;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.things.agent.tools.predicate.PredicateErrorMessages;

/**
 * Strict clean break for {@code tabulate_cached_result} — rejects legacy filter/sort/envelope keys per
 * {@code docs/agent/query-spec.md} §7.1 (layer 2, after PASSWORD sweep).
 */
public final class CachedTabulateLegacyKeyRejector {

    private static final String[] ROOT_LEGACY = {"where", "sort", "limit", "sortBy", "direction"};

    private CachedTabulateLegacyKeyRejector() {}

    public static void validate(String mode, JsonNode root) throws CachedTabularDecisionToolException {
        if (root == null || !root.isObject()) {
            return;
        }
        for (String k : ROOT_LEGACY) {
            if (root.has(k)) {
                throw rejectForRootKey(k);
            }
        }
        JsonNode measures = root.get("measures");
        if (measures != null && measures.isArray()) {
            for (JsonNode m : measures) {
                if (m != null && m.isObject() && m.has("where")) {
                    throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                            PredicateErrorMessages.rejectedLegacyPredicateShape());
                }
            }
        }
        walkFilterLikeObjectsForLegacyShape(root.get("filters"));
        walkFilterLikeObjectsForLegacyShape(root.get("having"));
        if (measures != null && measures.isArray()) {
            for (JsonNode m : measures) {
                if (m != null && m.isObject()) {
                    walkFilterLikeObjectsForLegacyShape(m.get("filters"));
                }
            }
        }
        validateSortsArray(root.get("sorts"));
    }

    private static CachedTabularDecisionToolException rejectForRootKey(String key) {
        if ("where".equals(key)) {
            return new CachedTabularDecisionToolException("INVALID_PREDICATE",
                    PredicateErrorMessages.rejectedLegacyPredicateShape());
        }
        if ("sort".equals(key) || "sortBy".equals(key) || "direction".equals(key) || "limit".equals(key)) {
            return new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                    PredicateErrorMessages.rejectedLegacySortShape());
        }
        return new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                PredicateErrorMessages.rejectedLegacyEnvelope("Rejected legacy envelope key '" + key + "'."));
    }

    private static void validateSortsArray(JsonNode sorts) throws CachedTabularDecisionToolException {
        if (sorts == null || sorts.isNull()) {
            return;
        }
        if (!sorts.isArray()) {
            return;
        }
        for (JsonNode el : sorts) {
            if (el == null || !el.isObject()) {
                continue;
            }
            if (el.has("column") || el.has("direction")) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        PredicateErrorMessages.rejectedLegacySortShape());
            }
        }
    }

    /**
     * Rejects legacy predicate shapes anywhere we accept a TWX filter object (§7.1 + §8.2).
     */
    private static void walkFilterLikeObjectsForLegacyShape(JsonNode n) throws CachedTabularDecisionToolException {
        if (n == null || n.isNull()) {
            return;
        }
        if (!n.isObject()) {
            return;
        }
        Iterator<String> it = n.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            String kl = k.toLowerCase(Locale.ROOT);
            if ("op".equals(kl) || "column".equals(kl) || "all".equals(kl) || "any".equals(kl) || "predicates".equals(kl)
                    || "op_name".equals(kl) || "operator".equals(kl) || "field".equals(kl) || "term".equals(kl)
                    || "compare".equals(kl) || "predicate".equals(kl) || "min".equals(kl) || "max".equals(kl)
                    || "low".equals(kl) || "high".equals(kl)) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                        PredicateErrorMessages.rejectedLegacyPredicateShape());
            }
            // §7.1 — reject legacy/wrong-casing column key `fieldname` etc.; canonical is exactly `fieldName`.
            if (!CommonPropertyNames.PROP_FIELDNAME.equals(k)
                    && CommonPropertyNames.PROP_FIELDNAME.equalsIgnoreCase(k)) {
                throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                        PredicateErrorMessages.rejectedLegacyPredicateShape());
            }
            if ("not".equals(kl)) {
                JsonNode type = n.get("type");
                if (type == null || !type.isTextual()
                        || !"NOT".equalsIgnoreCase(type.asText().trim())) {
                    throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                            PredicateErrorMessages.rejectedLegacyPredicateShape());
                }
            }
        }
        JsonNode type = n.get("type");
        if (type != null && type.isTextual()) {
            String t = type.asText().trim().toUpperCase(Locale.ROOT);
            if ("BETWEEN".equals(t) || "NOTBETWEEN".equals(t)) {
                if (n.has("min") || n.has("max")) {
                    throw new CachedTabularDecisionToolException("INVALID_PREDICATE",
                            PredicateErrorMessages.rejectedLegacyPredicateShape());
                }
            }
        }
        JsonNode filters = n.get("filters");
        if (filters != null && filters.isArray()) {
            for (JsonNode c : filters) {
                walkFilterLikeObjectsForLegacyShape(c);
            }
        }
    }
}
