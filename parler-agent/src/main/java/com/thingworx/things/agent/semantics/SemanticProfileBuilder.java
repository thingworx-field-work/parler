package com.thingworx.things.agent.semantics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;
import com.thingworx.things.agent.configrepo.RepositoryTextLoads;
import com.thingworx.things.agent.skillregistry.FileRepositoryRepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositoryReader;

/**
 * Bounded parse/validate for {@code /semantics/semantic-profile.json} (SP1–SP4, SP7 structural).
 * Live Property/Service existence and authorization checks are invocation-time (M2).
 */
public final class SemanticProfileBuilder {

    public static final String SCHEMA_V1 = SemanticProfileSnapshot.SCHEMA_V1;
    public static final int MAX_ASSET_TYPES = 64;
    public static final int MAX_ROLES_PER_ASSET_TYPE = 128;
    public static final int MAX_ALIASES_PER_ROLE = 8;
    public static final int MAX_UTF8_BYTES = 256 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ROOT_FIELDS =
            Set.of("schema", "profileId", "version", "assetTypes");
    private static final Set<String> ROLE_FIELDS =
            Set.of("aliases", "binding", "unit", "dimension", "grain", "expectedCadence");
    private static final Set<String> PROPERTY_BINDING_FIELDS = Set.of("kind", "propertyName");
    private static final Set<String> SERVICE_BINDING_FIELDS =
            Set.of("kind", "thingName", "serviceName", "resultField");

    private SemanticProfileBuilder() {}

    public static final class ParseOutcome {
        private final boolean valid;
        private final SemanticProfileSnapshot snapshot;
        private final List<SemanticProfileDiagnostic> diagnostics;

        ParseOutcome(boolean valid, SemanticProfileSnapshot snapshot, List<SemanticProfileDiagnostic> diagnostics) {
            this.valid = valid;
            this.snapshot = snapshot;
            this.diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        }

        public boolean valid() {
            return valid;
        }

        public SemanticProfileSnapshot snapshot() {
            return snapshot;
        }

        public List<SemanticProfileDiagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static SemanticProfileSnapshot build(AgentThing agent, Logger log) {
        return build(agent, log, Instant.now(), null);
    }

    public static SemanticProfileSnapshot build(AgentThing agent, Logger log, Instant now,
            Set<String> knownAssetTypeKeys) {
        Instant at = now != null ? now : Instant.now();
        String repoName = agent.getConfigurationRepositoryThingName();
        if (repoName == null || repoName.isBlank()) {
            return SemanticProfileSnapshot.notConfigured(at);
        }
        var fr = FileRepositoryThingResolver.resolve(repoName.trim(), log, agent.getName());
        if (fr.isEmpty()) {
            return SemanticProfileSnapshot.unavailable(at,
                    List.of(error("SEMANTIC_PROFILE_UNAVAILABLE",
                            "Configuration repository Thing is not available: " + repoName.trim())));
        }
        RepositoryReader reader = FileRepositoryRepositoryReader.forConfiguration(fr.get());
        return buildFromRepositoryReader(reader, log, agent.getName(), at, knownAssetTypeKeys);
    }

    public static SemanticProfileSnapshot buildFromRepositoryReader(RepositoryReader reader, Logger log,
            String agentName, Instant now) {
        return buildFromRepositoryReader(reader, log, agentName, now, null);
    }

    /**
     * @param knownAssetTypeKeys when non-null/non-empty, every profile asset-type key must be present
     *        (taxonomy reference check). When null/empty, taxonomy reference validation is skipped
     *        (unit tests / structural-only loads).
     */
    public static SemanticProfileSnapshot buildFromRepositoryReader(RepositoryReader reader, Logger log,
            String agentName, Instant now, Set<String> knownAssetTypeKeys) {
        Instant at = now != null ? now : Instant.now();
        RepositoryTextLoads.Result tr =
                RepositoryTextLoads.loadText(reader, ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON);
        if (tr.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
            if (log != null) {
                log.error("[{}] semantic-profile.json read failed: {}", agentName, tr.errorMessage());
            }
            return SemanticProfileSnapshot.unavailable(at,
                    List.of(error("SEMANTIC_PROFILE_UNAVAILABLE",
                            tr.errorMessage() != null ? tr.errorMessage() : "read failed")));
        }
        if (tr.kind() != RepositoryTextLoads.Kind.CONTENT || tr.text() == null || tr.text().isBlank()) {
            return SemanticProfileSnapshot.notConfigured(at);
        }
        ParseOutcome parsed = parse(tr.text(), at, knownAssetTypeKeys);
        if (!parsed.valid()) {
            return SemanticProfileSnapshot.unavailable(at, parsed.diagnostics());
        }
        return parsed.snapshot();
    }

    public static ParseOutcome parse(String jsonText) {
        return parse(jsonText, Instant.now(), null);
    }

    public static ParseOutcome parse(String jsonText, Instant now, Set<String> knownAssetTypeKeys) {
        Instant at = now != null ? now : Instant.now();
        List<SemanticProfileDiagnostic> dx = new ArrayList<>();
        if (jsonText == null || jsonText.isBlank()) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", "semantic-profile.json is missing or empty"));
            return new ParseOutcome(false, SemanticProfileSnapshot.unavailable(at, dx), dx);
        }
        byte[] utf8 = jsonText.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_UTF8_BYTES) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID",
                    "semantic-profile.json exceeds max UTF-8 bytes (" + MAX_UTF8_BYTES + ")"));
            return new ParseOutcome(false, SemanticProfileSnapshot.unavailable(at, dx), dx);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(jsonText);
        } catch (Exception e) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", "Invalid JSON: " + e.getMessage()));
            return new ParseOutcome(false, SemanticProfileSnapshot.unavailable(at, dx), dx);
        }
        if (!root.isObject()) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", "root must be a JSON object"));
            return new ParseOutcome(false, SemanticProfileSnapshot.unavailable(at, dx), dx);
        }
        rejectUnknownFields(root, ROOT_FIELDS, "root", dx);
        if (hasError(dx)) {
            return new ParseOutcome(false, SemanticProfileSnapshot.unavailable(at, dx), dx);
        }

        String schema = text(root, "schema");
        if (!SCHEMA_V1.equals(schema)) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID",
                    "schema must be \"" + SCHEMA_V1 + "\""));
        }
        String profileId = text(root, "profileId");
        if (profileId == null || profileId.isBlank()) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", "profileId is required"));
        }
        String version = text(root, "version");
        if (version == null || version.isBlank()) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", "version is required"));
        }
        JsonNode assetTypes = root.get("assetTypes");
        if (assetTypes == null || !assetTypes.isObject()) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", "assetTypes must be a JSON object"));
            return new ParseOutcome(false, SemanticProfileSnapshot.unavailable(at, dx), dx);
        }
        if (assetTypes.size() > MAX_ASSET_TYPES) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID",
                    "assetTypes exceeds cap (" + MAX_ASSET_TYPES + ")"));
        }

        LinkedHashMap<String, List<SemanticPropertyRole>> rolesByAssetType = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> assetIt = assetTypes.fields();
        while (assetIt.hasNext()) {
            Map.Entry<String, JsonNode> assetEntry = assetIt.next();
            String assetTypeKey = assetEntry.getKey();
            if (assetTypeKey == null || assetTypeKey.isBlank()) {
                dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", "assetType key must be non-blank"));
                continue;
            }
            if (knownAssetTypeKeys != null && !knownAssetTypeKeys.isEmpty()
                    && !knownAssetTypeKeys.contains(assetTypeKey)) {
                dx.add(error("SEMANTIC_PROFILE_TAXONOMY_REF",
                        "assetTypeKey \"" + assetTypeKey + "\" is not present in taxonomy snapshot"));
            }
            JsonNode assetNode = assetEntry.getValue();
            if (assetNode == null || !assetNode.isObject()) {
                dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID",
                        "assetTypes." + assetTypeKey + " must be an object"));
                continue;
            }
            rejectUnknownFields(assetNode, Set.of("propertyRoles"), "assetTypes." + assetTypeKey, dx);
            JsonNode propertyRoles = assetNode.get("propertyRoles");
            if (propertyRoles == null || !propertyRoles.isObject()) {
                dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID",
                        "assetTypes." + assetTypeKey + ".propertyRoles must be an object"));
                continue;
            }
            if (propertyRoles.size() > MAX_ROLES_PER_ASSET_TYPE) {
                dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", "assetTypes." + assetTypeKey
                        + ".propertyRoles exceeds cap (" + MAX_ROLES_PER_ASSET_TYPE + ")"));
            }
            List<SemanticPropertyRole> roles =
                    parseRoles(assetTypeKey, propertyRoles, dx);
            if (!roles.isEmpty()) {
                rolesByAssetType.put(assetTypeKey, roles);
            }
        }

        if (hasError(dx)) {
            return new ParseOutcome(false, SemanticProfileSnapshot.unavailable(at, dx), dx);
        }

        String digest = sha256Hex(utf8);
        SemanticProfileSnapshot snap =
                SemanticProfileSnapshot.loaded(profileId.trim(), version.trim(), digest, rolesByAssetType, dx, at);
        return new ParseOutcome(true, snap, dx);
    }

    private static List<SemanticPropertyRole> parseRoles(String assetTypeKey, JsonNode propertyRoles,
            List<SemanticProfileDiagnostic> dx) {
        List<SemanticPropertyRole> roles = new ArrayList<>();
        LinkedHashSet<String> roleIds = new LinkedHashSet<>();
        LinkedHashMap<String, String> aliasOwner = new LinkedHashMap<>();

        Iterator<Map.Entry<String, JsonNode>> roleIt = propertyRoles.fields();
        while (roleIt.hasNext()) {
            Map.Entry<String, JsonNode> roleEntry = roleIt.next();
            String roleId = roleEntry.getKey();
            String path = "assetTypes." + assetTypeKey + ".propertyRoles." + roleId;
            if (roleId == null || roleId.isBlank()) {
                dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", path + ": roleId must be non-blank"));
                continue;
            }
            if (!roleIds.add(roleId)) {
                dx.add(error("SEMANTIC_PROFILE_AMBIGUOUS", path + ": duplicate roleId"));
                continue;
            }
            JsonNode roleNode = roleEntry.getValue();
            if (roleNode == null || !roleNode.isObject()) {
                dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", path + " must be an object"));
                continue;
            }
            rejectUnknownFields(roleNode, ROLE_FIELDS, path, dx);

            List<String> aliases = parseAliases(roleNode.get("aliases"), path, dx);
            for (String alias : aliases) {
                String prior = aliasOwner.put(alias, roleId);
                if (prior != null && !prior.equals(roleId)) {
                    dx.add(error("SEMANTIC_PROFILE_AMBIGUOUS",
                            path + ": alias \"" + alias + "\" already owned by roleId=" + prior));
                }
                if (alias.equals(roleId)) {
                    dx.add(error("SEMANTIC_PROFILE_AMBIGUOUS",
                            path + ": alias must not equal its own roleId"));
                }
                if (roleIds.contains(alias) && !alias.equals(roleId)) {
                    dx.add(error("SEMANTIC_PROFILE_AMBIGUOUS",
                            path + ": alias \"" + alias + "\" collides with another roleId"));
                }
            }
            // Also detect roleId colliding with previously registered alias
            if (aliasOwner.containsKey(roleId) && !roleId.equals(aliasOwner.get(roleId))) {
                dx.add(error("SEMANTIC_PROFILE_AMBIGUOUS",
                        path + ": roleId collides with alias of roleId=" + aliasOwner.get(roleId)));
            }

            int errorsBefore = dx.size();
            SemanticRoleBinding binding = parseBinding(roleNode.get("binding"), path, dx);
            String unit = text(roleNode, "unit");
            String dimension = text(roleNode, "dimension");
            String grain = text(roleNode, "grain");
            String cadence = text(roleNode, "expectedCadence");
            boolean roleOk = binding != null;

            if (unit == null || unit.isBlank()) {
                dx.add(error("SEMANTIC_PROFILE_UNIT", path + ": unit is required"));
                roleOk = false;
            } else if (!SemanticProfileVocabulary.isUnit(unit)) {
                dx.add(error("SEMANTIC_PROFILE_UNIT", path + ": unknown unit token \"" + unit + "\""));
                roleOk = false;
            }
            if (dimension == null || dimension.isBlank()) {
                dx.add(error("SEMANTIC_PROFILE_UNIT", path + ": dimension is required"));
                roleOk = false;
            } else if (!SemanticProfileVocabulary.isDimension(dimension)) {
                dx.add(error("SEMANTIC_PROFILE_UNIT", path + ": unknown dimension \"" + dimension + "\""));
                roleOk = false;
            }
            if (roleOk && unit != null && dimension != null) {
                String expectedDim = SemanticProfileVocabulary.dimensionForUnit(unit);
                if (!dimension.equals(expectedDim)) {
                    dx.add(error("SEMANTIC_PROFILE_UNIT", path + ": unit \"" + unit + "\" belongs to dimension \""
                            + expectedDim + "\", not \"" + dimension + "\""));
                    roleOk = false;
                }
            }
            if (grain == null || grain.isBlank()) {
                dx.add(error("SEMANTIC_PROFILE_UNIT", path + ": grain is required"));
                roleOk = false;
            } else if (!SemanticProfileVocabulary.isGrain(grain)) {
                dx.add(error("SEMANTIC_PROFILE_UNIT", path + ": unknown grain \"" + grain + "\""));
                roleOk = false;
            }
            String cadenceErr = SemanticProfileVocabulary.validateCadence(cadence);
            if (cadenceErr != null) {
                dx.add(error("SEMANTIC_PROFILE_UNIT", path + ": " + cadenceErr));
                roleOk = false;
            }
            if (dx.size() > errorsBefore) {
                roleOk = false;
            }
            if (!roleOk) {
                continue;
            }
            String normalizedCadence = cadence == null || cadence.isBlank() ? null : cadence.trim();
            roles.add(new SemanticPropertyRole(roleId, aliases, binding, unit, dimension, grain, normalizedCadence));
        }
        return roles;
    }

    private static List<String> parseAliases(JsonNode aliasesNode, String path, List<SemanticProfileDiagnostic> dx) {
        if (aliasesNode == null || aliasesNode.isNull()) {
            return List.of();
        }
        if (!aliasesNode.isArray()) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", path + ".aliases must be an array"));
            return List.of();
        }
        if (aliasesNode.size() > MAX_ALIASES_PER_ROLE) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID",
                    path + ".aliases exceeds cap (" + MAX_ALIASES_PER_ROLE + ")"));
        }
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (JsonNode n : aliasesNode) {
            if (n == null || !n.isTextual()) {
                dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", path + ".aliases entries must be strings"));
                continue;
            }
            String a = n.asText().trim();
            if (a.isEmpty()) {
                dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", path + ".aliases entries must be non-blank"));
                continue;
            }
            if (!aliases.add(a)) {
                dx.add(error("SEMANTIC_PROFILE_AMBIGUOUS", path + ": duplicate alias \"" + a + "\""));
            }
        }
        return List.copyOf(aliases);
    }

    private static SemanticRoleBinding parseBinding(JsonNode binding, String path, List<SemanticProfileDiagnostic> dx) {
        if (binding == null || !binding.isObject()) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID", path + ".binding must be an object"));
            return null;
        }
        String kindRaw = text(binding, "kind");
        SemanticBindingKind kind = SemanticBindingKind.parse(kindRaw);
        if (kind == null) {
            dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID",
                    path + ".binding.kind must be PROPERTY or SERVICE"));
            return null;
        }
        if (kind == SemanticBindingKind.PROPERTY) {
            rejectUnknownFields(binding, PROPERTY_BINDING_FIELDS, path + ".binding", dx);
            String propertyName = text(binding, "propertyName");
            if (propertyName == null || propertyName.isBlank()) {
                dx.add(error("SEMANTIC_PROFILE_TARGET", path + ".binding.propertyName is required"));
                return null;
            }
            return SemanticRoleBinding.property(propertyName.trim());
        }
        rejectUnknownFields(binding, SERVICE_BINDING_FIELDS, path + ".binding", dx);
        String thingName = text(binding, "thingName");
        String serviceName = text(binding, "serviceName");
        String resultField = text(binding, "resultField");
        boolean ok = true;
        if (thingName == null || thingName.isBlank()) {
            dx.add(error("SEMANTIC_PROFILE_TARGET", path + ".binding.thingName is required"));
            ok = false;
        }
        if (serviceName == null || serviceName.isBlank()) {
            dx.add(error("SEMANTIC_PROFILE_TARGET", path + ".binding.serviceName is required"));
            ok = false;
        }
        if (resultField == null || resultField.isBlank()) {
            dx.add(error("SEMANTIC_PROFILE_TARGET", path + ".binding.resultField is required"));
            ok = false;
        }
        if (!ok) {
            return null;
        }
        return SemanticRoleBinding.service(thingName.trim(), serviceName.trim(), resultField.trim());
    }

    private static void rejectUnknownFields(JsonNode obj, Set<String> allowed, String path,
            List<SemanticProfileDiagnostic> dx) {
        Iterator<String> names = obj.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                dx.add(error("SEMANTIC_PROFILE_CONFIG_INVALID",
                        path + ": unknown field \"" + name + "\""));
            }
        }
    }

    private static String text(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            return null;
        }
        return n.asText();
    }

    private static boolean hasError(List<SemanticProfileDiagnostic> dx) {
        for (SemanticProfileDiagnostic d : dx) {
            if (d.severity() == SemanticProfileDiagnostic.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    private static SemanticProfileDiagnostic error(String code, String message) {
        return new SemanticProfileDiagnostic(SemanticProfileDiagnostic.Severity.ERROR, code, message);
    }

    static String sha256Hex(byte[] utf8) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(utf8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
