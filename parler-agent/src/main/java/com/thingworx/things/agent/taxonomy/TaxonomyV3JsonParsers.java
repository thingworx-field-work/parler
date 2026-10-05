package com.thingworx.things.agent.taxonomy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.tools.ModelKeyResolutionNormalize;

/**
 * Parses v3 {@code /taxonomies/asset-types.json} object map and {@code identity-types.json} array rules.
 */
public final class TaxonomyV3JsonParsers {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaxonomyV3JsonParsers() {}

    public static final class AssetTypesOutcome {
        private final boolean valid;
        private final List<AssetTypeEntry> entries;
        private final List<TaxonomyDiagnostic> diagnostics;

        AssetTypesOutcome(boolean valid, List<AssetTypeEntry> entries, List<TaxonomyDiagnostic> diagnostics) {
            this.valid = valid;
            this.entries = entries != null ? List.copyOf(entries) : List.of();
            this.diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        }

        public boolean valid() {
            return valid;
        }

        public List<AssetTypeEntry> entries() {
            return entries;
        }

        public List<TaxonomyDiagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static AssetTypesOutcome parseAssetTypesObject(String jsonText, Logger log, String agentName) {
        return parseAssetTypesObject(jsonText, log, agentName, true);
    }

    /**
     * When {@code requireNonEmptyEntries} is {@code false}, missing/blank JSON or an empty object map yields an
     * empty entry list with warnings only (v3 identity array may load without asset-type definitions).
     */
    public static AssetTypesOutcome parseAssetTypesObject(String jsonText, Logger log, String agentName,
            boolean requireNonEmptyEntries) {
        List<TaxonomyDiagnostic> diagnostics = new ArrayList<>();
        if (jsonText == null || jsonText.isBlank()) {
            if (requireNonEmptyEntries) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                        "asset-types.json is missing or empty for v3 taxonomy"));
                return new AssetTypesOutcome(false, List.of(), diagnostics);
            }
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_ASSET_TYPES_ABSENT",
                    "asset-types.json is missing or empty; list_asset_types and resolve_asset_type are unavailable."));
            return new AssetTypesOutcome(true, List.of(), diagnostics);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(jsonText);
        } catch (Exception e) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                    "Invalid asset-types.json JSON: " + e.getMessage()));
            return new AssetTypesOutcome(false, List.of(), diagnostics);
        }
        if (!root.isObject()) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                    "asset-types.json root must be a JSON object for v3"));
            return new AssetTypesOutcome(false, List.of(), diagnostics);
        }
        List<AssetTypeEntry> entries = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String canonicalLabel = e.getKey();
            JsonNode node = e.getValue();
            if (node == null || !node.isObject()) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_ROW_INVALID",
                        "asset-types entry for \"" + canonicalLabel + "\" must be an object"));
                return new AssetTypesOutcome(false, List.of(), diagnostics);
            }
            String entityType = text(node, "entityType");
            String entityName = text(node, "entityName");
            if (!"ThingTemplate".equals(entityType) && !"ThingShape".equals(entityType)) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_ROW_INVALID",
                        "asset type \"" + canonicalLabel + "\" entityType must be ThingTemplate or ThingShape"));
                return new AssetTypesOutcome(false, List.of(), diagnostics);
            }
            if (entityName.isEmpty()) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_ROW_INVALID",
                        "asset type \"" + canonicalLabel + "\" missing entityName"));
                return new AssetTypesOutcome(false, List.of(), diagnostics);
            }
            String representation = "ThingTemplate".equals(entityType) ? "template_as_type" : "shape_as_type";
            List<String> aliases = parseStringArray(node.get("aliases"));
            List<String> critical = parseStringArray(node.get("criticalProperties"));
            String normKey = ModelKeyResolutionNormalize.normalizePhase0(canonicalLabel);
            if (normKey.isEmpty()) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_ROW_INVALID",
                        "asset type key invalid after normalization: " + canonicalLabel));
                return new AssetTypesOutcome(false, List.of(), diagnostics);
            }
            AssetTypeEntry row = new AssetTypeEntry(canonicalLabel, List.of(), representation, canonicalLabel, aliases,
                    entityType, entityName, List.of(), List.of(), critical, null);
            entries.add(row);
        }
        if (entries.isEmpty()) {
            if (requireNonEmptyEntries) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                        "asset-types.json contains no asset type entries"));
                return new AssetTypesOutcome(false, List.of(), diagnostics);
            }
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_ASSET_TYPES_EMPTY",
                    "asset-types.json contains no asset type entries; list_asset_types and resolve_asset_type have nothing to list."));
            return new AssetTypesOutcome(true, List.of(), diagnostics);
        }
        return new AssetTypesOutcome(true, entries, diagnostics);
    }

    public static final class IdentityRulesOutcome {
        private final boolean valid;
        private final List<ThingIdentityRuleV3> rules;
        private final List<TaxonomyDiagnostic> diagnostics;

        IdentityRulesOutcome(boolean valid, List<ThingIdentityRuleV3> rules, List<TaxonomyDiagnostic> diagnostics) {
            this.valid = valid;
            this.rules = rules != null ? List.copyOf(rules) : List.of();
            this.diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        }

        public boolean valid() {
            return valid;
        }

        public List<ThingIdentityRuleV3> rules() {
            return rules;
        }

        public List<TaxonomyDiagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static IdentityRulesOutcome parseIdentityRulesArray(String jsonText, Logger log, String agentName) {
        List<TaxonomyDiagnostic> diagnostics = new ArrayList<>();
        if (jsonText == null || jsonText.isBlank()) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                    "identity-types.json is missing or empty"));
            return new IdentityRulesOutcome(false, List.of(), diagnostics);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(jsonText);
        } catch (Exception e) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                    "Invalid identity-types.json JSON: " + e.getMessage()));
            return new IdentityRulesOutcome(false, List.of(), diagnostics);
        }
        if (!root.isArray()) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                    "v3 identity-types.json root must be a JSON array"));
            return new IdentityRulesOutcome(false, List.of(), diagnostics);
        }
        List<ThingIdentityRuleV3> rules = new ArrayList<>();
        for (JsonNode ruleNode : root) {
            if (ruleNode == null || !ruleNode.isObject()) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_ROW_INVALID",
                        "Each identity rule must be an object"));
                return new IdentityRulesOutcome(false, List.of(), diagnostics);
            }
            String baseTemplate = text(ruleNode, "baseThingTemplate");
            if (baseTemplate.isEmpty()) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_ROW_INVALID",
                        "identity rule missing baseThingTemplate"));
                return new IdentityRulesOutcome(false, List.of(), diagnostics);
            }
            JsonNode idProps = ruleNode.get("identityProperties");
            if (idProps == null || !idProps.isArray() || idProps.size() == 0) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_ROW_INVALID",
                        "identity rule missing identityProperties array"));
                return new IdentityRulesOutcome(false, List.of(), diagnostics);
            }
            List<ThingIdentityPropertyMatchV3> props = new ArrayList<>();
            for (JsonNode p : idProps) {
                if (p.isTextual()) {
                    props.add(new ThingIdentityPropertyMatchV3(p.asText(), "equals"));
                } else if (p.isObject()) {
                    String n = text(p, "name");
                    String m = text(p, "match");
                    if (n.isEmpty()) {
                        diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_ROW_INVALID",
                                "identityProperties entry missing name"));
                        return new IdentityRulesOutcome(false, List.of(), diagnostics);
                    }
                    if (m.isEmpty()) {
                        m = "equals";
                    }
                    props.add(new ThingIdentityPropertyMatchV3(n, m));
                } else {
                    diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_ROW_INVALID",
                            "identityProperties entry must be string or object"));
                    return new IdentityRulesOutcome(false, List.of(), diagnostics);
                }
            }
            for (ThingIdentityPropertyMatchV3 pm : props) {
                if (!isSupportedV3IdentityMatch(pm.match())) {
                    diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_ROW_INVALID",
                            "identityProperties entry for \"" + pm.name() + "\" has unsupported match \""
                                    + pm.match() + "\" (v3 allows only equals, suffix)"));
                    return new IdentityRulesOutcome(false, List.of(), diagnostics);
                }
            }
            List<String> critical = parseStringArray(ruleNode.get("criticalProperties"));
            rules.add(new ThingIdentityRuleV3(baseTemplate, props, critical));
        }
        if (rules.isEmpty()) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                    "identity-types.json array is empty"));
            return new IdentityRulesOutcome(false, List.of(), diagnostics);
        }
        return new IdentityRulesOutcome(true, rules, diagnostics);
    }

    private static boolean isSupportedV3IdentityMatch(String match) {
        return "equals".equals(match) || "suffix".equals(match);
    }

    private static String text(JsonNode o, String field) {
        if (o == null) {
            return "";
        }
        JsonNode n = o.get(field);
        return n != null && n.isTextual() ? n.asText("").trim() : "";
    }

    private static List<String> parseStringArray(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.size() == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n != null && n.isTextual()) {
                String s = n.asText("").trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return List.copyOf(out);
    }
}
