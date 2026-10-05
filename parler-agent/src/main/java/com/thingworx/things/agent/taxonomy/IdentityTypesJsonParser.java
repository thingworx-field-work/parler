package com.thingworx.things.agent.taxonomy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.tools.ModelKeyResolutionNormalize;

/**
 * Parses {@code /taxonomies/identity-types.json} {@code version: 2} per {@code docs/agent/taxonomy.md} §5.1 / §6.2.1.
 * Flattens {@code entities[].types[]} into {@link AssetTypeEntry} rows (plus {@link AssetTypeEntry#entityKey()}).
 * {@code model_serial_template} and rows without {@code identity.properties}
 * are skipped with diagnostics. {@code defaultIdentifierProfiles} are accepted but not wired to tools in this slice.
 */
public final class IdentityTypesJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> RESERVED_NORMALIZED_ALIASES = Set.of("stream", "datatable", "valuestream");

    private IdentityTypesJsonParser() {}

    public static final class ParseOutcome {
        private final boolean configValid;
        private final List<AssetTypeEntry> entries;
        private final List<TaxonomyDiagnostic> diagnostics;

        ParseOutcome(boolean configValid, List<AssetTypeEntry> entries, List<TaxonomyDiagnostic> diagnostics) {
            this.configValid = configValid;
            this.entries = entries != null ? List.copyOf(entries) : List.of();
            this.diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        }

        public boolean configValid() {
            return configValid;
        }

        public List<AssetTypeEntry> entries() {
            return entries;
        }

        public List<TaxonomyDiagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static ParseOutcome parse(String jsonText, Logger log, String agentName) {
        return parse(jsonText, log, agentName, true);
    }

    public static ParseOutcome parse(String jsonText, Logger log, String agentName, boolean requireResolvedParents) {
        List<TaxonomyDiagnostic> diagnostics = new ArrayList<>();
        if (jsonText == null || jsonText.isBlank()) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                    "identity-types.json is missing or empty"));
            return new ParseOutcome(false, List.of(), diagnostics);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(jsonText);
        } catch (Exception e) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                    "Invalid JSON: " + e.getMessage()));
            return new ParseOutcome(false, List.of(), diagnostics);
        }
        if (!root.isObject()) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                    "Root must be a JSON object"));
            return new ParseOutcome(false, List.of(), diagnostics);
        }
        int version = root.path("version").asInt(-1);
        if (version != 2) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                    "identity-types.json must declare version 2, got: " + version));
            return new ParseOutcome(false, List.of(), diagnostics);
        }
        JsonNode entities = root.get("entities");
        if (entities == null || !entities.isArray()) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                    "entities must be an array"));
            return new ParseOutcome(false, List.of(), diagnostics);
        }
        warnUnknownTopLevel(root, diagnostics);
        warnDefaultProfiles(root, diagnostics);
        Set<String> normalizedEntityKeys = new HashSet<>();
        Map<String, List<String>> aliasOwners = new LinkedHashMap<>();
        List<AssetTypeEntry> entries = new ArrayList<>();
        for (JsonNode ent : entities) {
            parseEntity(ent, entries, normalizedEntityKeys, aliasOwners, diagnostics, log, agentName,
                    requireResolvedParents);
        }
        detectAliasCollisions(aliasOwners, diagnostics);
        return new ParseOutcome(true, entries, diagnostics);
    }

    private static void warnUnknownTopLevel(JsonNode root, List<TaxonomyDiagnostic> diagnostics) {
        var it = root.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (!"version".equals(f) && !"entities".equals(f)) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING,
                        "TAXONOMY_FUTURE_FIELD_IGNORED", "Unknown top-level field: " + f));
            }
        }
    }

    private static void warnDefaultProfiles(JsonNode root, List<TaxonomyDiagnostic> diagnostics) {
        JsonNode entities = root.get("entities");
        if (entities == null || !entities.isArray()) {
            return;
        }
        for (JsonNode ent : entities) {
            if (ent == null || !ent.isObject()) {
                continue;
            }
            JsonNode profiles = ent.get("defaultIdentifierProfiles");
            if (profiles != null && profiles.isArray() && profiles.size() > 0) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING,
                        "TAXONOMY_FUTURE_FIELD_IGNORED",
                        "defaultIdentifierProfiles is not consumed by built-in tools in this slice (entity "
                                + text(ent, "key") + ")"));
            }
        }
    }

    private static void parseEntity(JsonNode ent, List<AssetTypeEntry> entries, Set<String> normalizedEntityKeys,
            Map<String, List<String>> aliasOwners, List<TaxonomyDiagnostic> diagnostics, Logger log, String agentName,
            boolean requireResolvedParents) {
        if (ent == null || !ent.isObject()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Entity entry must be an object"));
            return;
        }
        String entityKey = text(ent, "key");
        if (entityKey.isEmpty()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Entity missing key"));
            return;
        }
        String normEntity = ModelKeyResolutionNormalize.normalizePhase0(entityKey);
        if (normEntity.isEmpty() || normalizedEntityKeys.contains(normEntity)) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Duplicate or invalid entity key: " + entityKey));
            return;
        }
        normalizedEntityKeys.add(normEntity);
        List<String> entityAliases = parseStringArray(ent.get("aliases"), true, diagnostics, "entity " + entityKey);
        registerAliasOwners(entityKey, entityKey, aliasOwners);
        for (String a : entityAliases) {
            registerAliasOwners(entityKey, a, aliasOwners);
            warnReservedAlias(a, entityKey, diagnostics);
        }
        JsonNode types = ent.get("types");
        if (types == null || !types.isArray()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Entity " + entityKey + " missing types array"));
            return;
        }
        List<String> entityAliasesFrozen = List.copyOf(entityAliases);
        Set<String> normalizedTypeKeysInEntity = new HashSet<>();
        for (JsonNode t : types) {
            parseTypeRow(entityKey, entityAliasesFrozen, t, entries, normalizedTypeKeysInEntity, aliasOwners,
                    diagnostics, log, agentName, requireResolvedParents);
        }
    }

    private static void parseTypeRow(String entityKey, List<String> entityAliases, JsonNode node,
            List<AssetTypeEntry> entries,
            Set<String> normalizedTypeKeysInEntity, Map<String, List<String>> aliasOwners,
            List<TaxonomyDiagnostic> diagnostics, Logger log, String agentName,
            boolean requireResolvedParents) {
        if (node == null || !node.isObject()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Type entry must be an object"));
            return;
        }
        warnFutureTypeFields(node, diagnostics);
        String key = text(node, "key");
        if (key.isEmpty()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Missing type key under entity " + entityKey));
            return;
        }
        String normKey = ModelKeyResolutionNormalize.normalizePhase0(key);
        if (normKey.isEmpty() || normalizedTypeKeysInEntity.contains(normKey)) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID",
                    "Duplicate or invalid type key \"" + key + "\" under entity " + entityKey));
            return;
        }
        normalizedTypeKeysInEntity.add(normKey);
        String representation = text(node, "representation");
        if (!"template_as_type".equals(representation) && !"shape_as_type".equals(representation)) {
            if ("model_serial_template".equals(representation)) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_FUTURE_FIELD_IGNORED",
                        "Skipping type \"" + key + "\" (model_serial_template not implemented in this slice)"));
            } else {
                diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Invalid representation for type " + key));
            }
            return;
        }
        JsonNode membership = node.get("membership");
        if (membership == null || !membership.isObject()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Missing membership for " + key));
            return;
        }
        String entityType = text(membership, "entityType");
        String entityName = text(membership, "entityName");
        if (!"ThingTemplate".equals(entityType) && !"ThingShape".equals(entityType)) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Invalid membership.entityType for " + key));
            return;
        }
        if (entityName.isEmpty()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Missing membership.entityName for " + key));
            return;
        }
        if ("template_as_type".equals(representation) && !"ThingTemplate".equals(entityType)) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "template_as_type requires membership.entityType "
                    + "ThingTemplate for type \"" + key + "\" (" + entityKey + "); got " + entityType));
            return;
        }
        if ("shape_as_type".equals(representation) && !"ThingShape".equals(entityType)) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "shape_as_type requires membership.entityType ThingShape "
                    + "for type \"" + key + "\" (" + entityKey + "); got " + entityType));
            return;
        }
        if (requireResolvedParents && !resolveParentExists(entityType, entityName)) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_ENTITY_UNRESOLVED",
                    "Parent " + entityType + " \"" + entityName + "\" for type \"" + key + "\" (" + entityKey + ")"));
            return;
        }
        JsonNode identity = node.get("identity");
        if (identity == null || !identity.isObject()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Missing identity for " + key));
            return;
        }
        if (identity.has("propertyGroups") && !identity.has("properties")) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_FUTURE_FIELD_IGNORED",
                    "Skipping type \"" + key + "\" (identity.propertyGroups without identity.properties not implemented)"));
            return;
        }
        List<String> props = stringArray(identity.get("properties"), true, diagnostics, key);
        if (props.isEmpty()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "identity.properties empty for " + key));
            return;
        }
        List<String> rules = stringArray(identity.get("matchRules"), false, diagnostics, key);
        if (rules.isEmpty() || !rules.stream().allMatch(IdentityTypesJsonParser::allowedRule)) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "identity.matchRules invalid for " + key));
            return;
        }
        List<String> aliases = parseStringArray(node.get("aliases"), true, diagnostics, key);
        registerAliasOwners(entityKey + "/" + key, key, aliasOwners);
        for (String a : aliases) {
            registerAliasOwners(entityKey + "/" + key, a, aliasOwners);
            warnReservedAlias(a, key, diagnostics);
        }
        List<String> critProps = parseCriticalProperties(node, diagnostics, key);
        QueryParentOutcome qpOut =
                parseQueryParent(node, representation, entityType, entityKey, key, diagnostics, requireResolvedParents);
        if (qpOut.skipRow) {
            return;
        }
        entries.add(new AssetTypeEntry(entityKey, entityAliases, representation, key, aliases, entityType, entityName,
                props, rules, critProps, qpOut.queryParent));
    }

    private static final class QueryParentOutcome {
        final TaxonomyQueryParent queryParent;
        final boolean skipRow;

        QueryParentOutcome(TaxonomyQueryParent queryParent, boolean skipRow) {
            this.queryParent = queryParent;
            this.skipRow = skipRow;
        }
    }

    private static QueryParentOutcome parseQueryParent(JsonNode node, String representation,
            String membershipEntityType, String entityKey, String typeKey, List<TaxonomyDiagnostic> diagnostics,
            boolean requireResolvedParents) {
        JsonNode qpNode = node.get("queryParent");
        if (qpNode == null || qpNode.isNull()) {
            return new QueryParentOutcome(null, false);
        }
        if (!"shape_as_type".equals(representation) || !"ThingShape".equals(membershipEntityType)) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_FUTURE_FIELD_IGNORED",
                    "queryParent is only used for shape_as_type rows with ThingShape membership; ignored on type \""
                            + typeKey + "\" (" + entityKey + ")"));
            return new QueryParentOutcome(null, false);
        }
        if (!qpNode.isObject()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "queryParent must be an object for " + typeKey));
            return new QueryParentOutcome(null, true);
        }
        String qpType = text(qpNode, "entityType");
        String qpName = text(qpNode, "entityName");
        if (!"ThingTemplate".equals(qpType)) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID",
                    "queryParent.entityType must be ThingTemplate for " + typeKey));
            return new QueryParentOutcome(null, true);
        }
        if (qpName.isEmpty()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "queryParent.entityName missing for " + typeKey));
            return new QueryParentOutcome(null, true);
        }
        if (requireResolvedParents && !resolveParentExists(qpType, qpName)) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_ENTITY_UNRESOLVED",
                    "queryParent ThingTemplate \"" + qpName + "\" for type \"" + typeKey + "\" (" + entityKey + ")"));
            return new QueryParentOutcome(null, true);
        }
        String role = text(qpNode, "role");
        warnUnknownQueryParentFields(qpNode, diagnostics);
        return new QueryParentOutcome(new TaxonomyQueryParent(qpType, qpName, role), false);
    }

    private static void warnUnknownQueryParentFields(JsonNode qpNode, List<TaxonomyDiagnostic> diagnostics) {
        var it = qpNode.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (!Set.of("entityType", "entityName", "role").contains(f)) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING,
                        "TAXONOMY_FUTURE_FIELD_IGNORED", "Unknown queryParent field: " + f));
            }
        }
    }

    private static void warnFutureTypeFields(JsonNode node, List<TaxonomyDiagnostic> diagnostics) {
        var it = node.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (!Set.of("key", "aliases", "representation", "membership", "queryParent", "identity", "criticalProperties",
                    "models").contains(f)) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING,
                        "TAXONOMY_FUTURE_FIELD_IGNORED", "Ignored field \"" + f + "\" on identity type"));
            }
        }
    }

    private static boolean resolveParentExists(String entityType, String entityName) {
        RelationshipTypes.ThingworxRelationshipTypes rel = "ThingTemplate".equals(entityType)
                ? RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate
                : RelationshipTypes.ThingworxRelationshipTypes.ThingShape;
        return PlatformAccess.findProgrammatic(entityName, rel) != null;
    }

    private static List<String> parseStringArray(JsonNode arr, boolean dropEmpty, List<TaxonomyDiagnostic> diagnostics,
            String ctx) {
        if (arr == null || arr.isNull()) {
            return List.of();
        }
        if (!arr.isArray()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Expected array for " + ctx));
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (!n.isTextual()) {
                continue;
            }
            String s = n.asText().trim();
            if (s.isEmpty()) {
                if (dropEmpty) {
                    diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Dropped empty string in " + ctx));
                }
                continue;
            }
            out.add(s);
        }
        return out;
    }

    private static List<String> parseCriticalProperties(JsonNode node, List<TaxonomyDiagnostic> diagnostics, String key) {
        JsonNode arr = node.get("criticalProperties");
        if (arr == null || arr.isNull()) {
            return List.of();
        }
        if (!arr.isArray()) {
            diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "criticalProperties must be an array for " + key));
            return List.of();
        }
        return stringArray(arr, true, diagnostics, key);
    }

    private static List<String> stringArray(JsonNode arr, boolean dropEmpty, List<TaxonomyDiagnostic> diagnostics,
            String key) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (!n.isTextual()) {
                continue;
            }
            String s = n.asText().trim();
            if (s.isEmpty()) {
                if (dropEmpty) {
                    diagnostics.add(rowDiag("TAXONOMY_ROW_INVALID", "Dropped empty property string on " + key));
                }
                continue;
            }
            out.add(s);
        }
        return out;
    }

    private static void registerAliasOwners(String entityKey, String phrase, Map<String, List<String>> aliasOwners) {
        String norm = ModelKeyResolutionNormalize.normalizePhase0(phrase);
        if (norm.isEmpty()) {
            return;
        }
        aliasOwners.computeIfAbsent(norm, k -> new ArrayList<>()).add(entityKey);
    }

    private static void detectAliasCollisions(Map<String, List<String>> aliasOwners,
            List<TaxonomyDiagnostic> diagnostics) {
        for (Map.Entry<String, List<String>> e : aliasOwners.entrySet()) {
            LinkedHashSet<String> owners = new LinkedHashSet<>(e.getValue());
            if (owners.size() > 1) {
                diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_ALIAS_COLLISION",
                        "Phrase \"" + e.getKey() + "\" matches entity keys: " + String.join(", ", owners)));
            }
        }
    }

    private static void warnReservedAlias(String a, String owner, List<TaxonomyDiagnostic> diagnostics) {
        String na = ModelKeyResolutionNormalize.normalizePhase0(a);
        if (RESERVED_NORMALIZED_ALIASES.contains(na)) {
            diagnostics.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_RESERVED_ALIAS",
                    "Alias \"" + a + "\" on " + owner + " uses reserved platform term"));
        }
    }

    private static boolean allowedRule(String r) {
        return "exact".equals(r) || "normalized".equals(r);
    }

    private static TaxonomyDiagnostic rowDiag(String code, String message) {
        return new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, code, message);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return "";
        }
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText().trim() : "";
    }
}
