package com.thingworx.things.agent.taxonomy;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds ThingWorx QUERY JSON for v3 synthetic {@link AssetTypeEntry} rows ({@code v3zip:*}) so
 * {@code QueryImplementingThings*} applies identity predicates server-side (bug 006).
 */
public final class TaxonomyIdentityV3QitQuery {

    private static final ObjectMapper MAPPER = TaxonomyResolverJson.mapper();

    private TaxonomyIdentityV3QitQuery() {}

    /**
     * Root query object: {@code { "filters": { "type": "OR", "filters": [ ... ] } }} for supported
     * zippered rules only ({@code equals}, {@code suffix}). Returns {@code null} when the entry is
     * not v3 synthetic, has no pairs, or contains unsupported match modes (unsupported modes are
     * rejected at {@code identity-types.json} parse time; this builder defends in depth).
     */
    public static JsonNode buildRootFiltersOr(AssetTypeEntry type, String trimmedText) {
        if (type == null || trimmedText == null) {
            return null;
        }
        String ek = type.entityKey();
        if (ek == null || !ek.startsWith("v3zip:")) {
            return null;
        }
        List<String> props = type.identityProperties();
        List<String> rules = type.matchRules();
        int n = Math.min(props.size(), rules.size());
        if (n == 0) {
            return null;
        }
        ArrayNode leaves = MAPPER.createArrayNode();
        for (int i = 0; i < n; i++) {
            String prop = props.get(i);
            String rule = rules.get(i);
            if (prop == null || prop.isBlank()) {
                return null;
            }
            String r = rule != null ? rule.trim() : "";
            JsonNode leaf = leafFilter(prop.trim(), r, trimmedText);
            if (leaf == null) {
                return null;
            }
            leaves.add(leaf);
        }
        ObjectNode filters = MAPPER.createObjectNode();
        filters.put("type", "OR");
        filters.set("filters", leaves);
        ObjectNode root = MAPPER.createObjectNode();
        root.set("filters", filters);
        return root;
    }

    /**
     * Escapes {@code \}, {@code *}, {@code %}, and {@code ?} in the user suffix fragment so it is
     * treated literally in ThingWorx {@code LIKE} SQL-pattern mode, then the resolver prepends a
     * single leading {@code *} multi-character wildcard for suffix match (see {@code docs/agent/taxonomy.md} §4.1.1).
     */
    static String escapeTwSqlLikeSuffixUserPortion(String trimmedText) {
        if (trimmedText == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trimmedText.length(); i++) {
            char c = trimmedText.charAt(i);
            if (c == '\\' || c == '*' || c == '%' || c == '?') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** {@code null} when {@code rule} is not {@code equals} or {@code suffix}. */
    static JsonNode leafFilter(String fieldName, String rule, String trimmedText) {
        ObjectNode leaf = MAPPER.createObjectNode();
        leaf.put("fieldName", fieldName);
        leaf.put("isCaseSensitive", false);
        switch (rule) {
        case "equals":
            leaf.put("type", "EQ");
            leaf.put("value", trimmedText);
            return leaf;
        case "suffix":
            leaf.put("type", "LIKE");
            leaf.put("value", "*" + escapeTwSqlLikeSuffixUserPortion(trimmedText));
            return leaf;
        default:
            return null;
        }
    }
}
