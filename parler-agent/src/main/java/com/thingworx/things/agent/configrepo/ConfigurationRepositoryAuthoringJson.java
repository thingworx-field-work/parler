package com.thingworx.things.agent.configrepo;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.json.JSONObject;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.playbook.PlaybookDocument;
import com.thingworx.things.agent.playbook.PlaybookDocumentValidation;
import com.thingworx.things.agent.playbook.PlaybookIds;
import com.thingworx.things.agent.playbook.PlaybookRegistryBuilder;
import com.thingworx.things.agent.playbook.PlaybookRegistryDiagnostic;
import com.thingworx.things.agent.playbook.PlaybookRegistrySnapshot;
import com.thingworx.things.agent.playbook.PlaybookToolDefinitionsMerge;
import com.thingworx.things.agent.playbook.PlaybookValidator;
import com.thingworx.things.agent.playbook.PlaybookValidationReport;
import com.thingworx.things.agent.playbook.PlaybookValidationReportBuilder;
import com.thingworx.things.agent.skillregistry.FileRepositoryRepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositorySkillScanner;
import com.thingworx.things.agent.skillregistry.SkillRegistryBuilder;
import com.thingworx.things.agent.skillregistry.SkillRegistrySnapshot;
import com.thingworx.things.agent.skillregistry.SkillSourceKind;
import com.thingworx.things.agent.configrepo.ParlerPackageVersion;
import com.thingworx.things.agent.semantics.SemanticProfileBuilder;
import com.thingworx.things.agent.semantics.SemanticProfileDiagnostic;
import com.thingworx.things.agent.semantics.SemanticProfileDiagnosticsJson;
import com.thingworx.things.agent.semantics.SemanticProfileSnapshot;
import com.thingworx.things.agent.taxonomy.ApplicationSemanticTaxonomyBuilder;
import com.thingworx.things.agent.taxonomy.ApplicationSemanticTaxonomySnapshot;
import com.thingworx.things.agent.taxonomy.AssetTypeEntry;
import com.thingworx.things.agent.taxonomy.IdentityTypesJsonParser;
import com.thingworx.things.agent.taxonomy.TaxonomyDiagnostic;
import com.thingworx.things.agent.taxonomy.TaxonomyResolverJson;
import com.thingworx.things.agent.taxonomy.TaxonomyV3JsonParsers;
import com.thingworx.things.repository.FileRepositoryThing;

/**
 * JSON helpers for {@code GetAgentRuntimeSnapshot} / {@code ValidateAgentConfigurationRepository} (authoring tools;
 * not LLM tools).
 */
public final class ConfigurationRepositoryAuthoringJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigurationRepositoryAuthoringJson() {}

    public static String validate(String repositoryNameOptional, AgentThing agent, Logger log) {
        try {
            return doValidate(repositoryNameOptional, agent, log);
        } catch (Exception e) {
            try {
                ObjectNode root = MAPPER.createObjectNode();
                root.putObject("repository").put("thingName", "").put("status", "error");
                putSummary(root, 1, 0, 0, 0, 0);
                ArrayNode items = root.putArray("items");
                items.add(item("error", "", "VALIDATION_EXCEPTION",
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return MAPPER.writeValueAsString(root);
            } catch (Exception e2) {
                return "{\"repository\":{\"status\":\"error\"},\"summary\":{\"errors\":1,\"warnings\":0,"
                        + "\"skillsRegistered\":0,\"extendedToolsRegistered\":0,\"invokeServicePolicyRules\":0},"
                        + "\"items\":[]}";
            }
        }
    }

    private static String doValidate(String repositoryNameOptional, AgentThing agent, Logger log) throws Exception {
        String wanted = repositoryNameOptional != null ? repositoryNameOptional.trim() : "";
        String repoName = wanted.isEmpty() ? agent.getConfigurationRepositoryThingName() : wanted;
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode repoObj = root.putObject("repository");
        ArrayNode items = root.putArray("items");
        int errors = 0;
        int warnings = 0;
        int skillsReg = 0;
        int extReg = 0;
        int polRules = 0;

        if (repoName == null || repoName.isBlank()) {
            repoObj.put("thingName", "");
            repoObj.put("status", "not_configured");
            items.add(item("warning", "", "NO_REPOSITORY", "configurationRepository is empty on AgentThing"));
            warnings++;
            putSummary(root, errors, warnings, skillsReg, extReg, polRules);
            return MAPPER.writeValueAsString(root);
        }
        repoObj.put("thingName", repoName);
        Optional<FileRepositoryThing> fr = FileRepositoryThingResolver.resolve(repoName.trim(), log, agent.getName());
        if (fr.isEmpty()) {
            repoObj.put("status", "unavailable");
            items.add(item("error", "", "REPOSITORY_UNAVAILABLE", "FileRepository Thing not found or wrong type"));
            errors++;
            putSummary(root, errors, warnings, skillsReg, extReg, polRules);
            return MAPPER.writeValueAsString(root);
        }
        repoObj.put("status", "ok");
        RepositoryReader reader = FileRepositoryRepositoryReader.forConfiguration(fr.get());
        Set<String> builtins = new HashSet<>(agent.builtinToolDefinitionNames());
        ExtendedToolRegistrySnapshot ext =
                ExtendedToolsManifest.load(reader, agent.getName(), agent, builtins, log);
        if (ext.isFileInvalid()) {
            items.add(item("error", "/tools/extended_tools.json", "EXTENDED_TOOLS_INVALID",
                    "Malformed or rejected manifest"));
            errors++;
        } else if (!ext.isFileMissing()) {
            extReg = ext.allByName().size();
        }
        List<ToolDefinition> playbookToolDefs = PlaybookToolDefinitionsMerge.merge(agent.toolRegistry(), ext);
        PlaybookRegistrySnapshot playbookSnap =
                PlaybookRegistryBuilder.buildFromReader(reader, agent.getName(), playbookToolDefs, ext, log);
        int[] playbookEw = {0, 0};
        appendPlaybookRegistryValidationItems(playbookSnap, items, playbookEw);
        errors += playbookEw[0];
        warnings += playbookEw[1];
        ArrayNode playbookValidations = root.putArray("playbookValidations");
        appendPlaybookDocumentValidationReports(reader, playbookToolDefs, ext, playbookValidations, log, agent);
        RepositoryTextLoads.Result polTr =
                RepositoryTextLoads.loadText(reader, ConfigurationRepositoryPaths.INVOKE_SERVICE_POLICY);
        if (polTr.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
            items.add(item("error", "/policies/invoke_service.json", "POLICY_READ_FAILED", polTr.errorMessage()));
            errors++;
        } else if (polTr.kind() == RepositoryTextLoads.Kind.CONTENT) {
            InvokeServiceAllowPolicy pol = InvokeServiceAllowPolicy.parseJsonOrInvalid(polTr.text(), log);
            if (pol.isInvalid()) {
                items.add(item("error", "/policies/invoke_service.json", "POLICY_INVALID",
                        "JSON failed structural validation"));
                errors++;
            } else {
                polRules = pol.ruleCount();
            }
        }
        TypeTaxonomyMarkdownLoader.TypeTaxonomyMarkdownOutcome tax =
                TypeTaxonomyMarkdownLoader.loadWithStatus(reader, log);
        if (tax.status() == TypeTaxonomyMarkdownLoader.Status.OVERSIZED) {
            items.add(item("warning", "/taxonomies/type-taxonomy.md", "TAXONOMY_OVERSIZED",
                    "Markdown exceeds " + TypeTaxonomyMarkdownLoader.MAX_CHARS + " bytes"));
            warnings++;
        } else if (tax.status() == TypeTaxonomyMarkdownLoader.Status.READ_ERROR) {
            items.add(item("error", "/taxonomies/type-taxonomy.md", "TAXONOMY_READ_FAILED",
                    "FileRepository read failed for type taxonomy markdown"));
            errors++;
        }
        SkillRegistrySnapshot skills =
                SkillRegistryBuilder.buildFromRepositoryReader(reader, repoName.trim(), agent.getName(),
                        playbookSnap.reservedSlashIds(), log);
        skillsReg = (int) skills.descriptorsByShortId().values().stream()
                .filter(d -> d.sourceKind() == SkillSourceKind.REPOSITORY).count();
        for (String d : skills.diagnostics()) {
            if (d != null && !d.isBlank()) {
                items.add(item("warning", "/skills", "SKILL_SCAN", d));
                warnings++;
            }
        }
        ObjectNode taxonomy = root.has("taxonomy") ? (ObjectNode) root.get("taxonomy") : root.putObject("taxonomy");
        int[] taxEw = {0, 0};
        appendStructuredTaxonomyValidation(reader, log, agent.getName(), taxonomy, items, taxEw);
        errors += taxEw[0];
        warnings += taxEw[1];
        ObjectNode semanticProfile = root.putObject("semanticProfile");
        int[] semEw = {0, 0};
        appendSemanticProfileValidation(reader, log, agent.getName(), semanticProfile, items, semEw);
        errors += semEw[0];
        warnings += semEw[1];
        putSummary(root, errors, warnings, skillsReg, extReg, polRules);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * Validates {@code /semantics/semantic-profile.json} with the same parser as runtime. Missing file is not an
     * error (semantic-dependent paths unavailable). Invalid content contributes itemized diagnostics.
     */
    static void appendSemanticProfileValidation(RepositoryReader reader, Logger log, String agentName,
            ObjectNode semanticProfile, ArrayNode items, int[] errorWarningDelta) throws Exception {
        semanticProfile.put("path", ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON);
        ApplicationSemanticTaxonomySnapshot tax =
                ApplicationSemanticTaxonomyBuilder.buildFromRepositoryReader(reader, log, agentName, java.time.Instant.now());
        Set<String> knownKeys = new HashSet<>();
        if (tax != null && tax.isLoaded()) {
            for (AssetTypeEntry e : tax.assetTypes()) {
                if (e != null && e.key() != null && !e.key().isBlank()) {
                    knownKeys.add(e.key());
                }
            }
        }
        SemanticProfileSnapshot snap = SemanticProfileBuilder.buildFromRepositoryReader(reader, log, agentName,
                java.time.Instant.now(), knownKeys.isEmpty() ? null : knownKeys);
        semanticProfile.put("status", snap.snapshotStatus());
        semanticProfile.put("loaded", snap.isLoaded());
        semanticProfile.put("stale", snap.isStale());
        semanticProfile.put("profileId", snap.profileId());
        semanticProfile.put("version", snap.version());
        semanticProfile.put("digest", snap.digest());
        semanticProfile.put("assetTypeCount", snap.assetTypeCount());
        semanticProfile.put("roleCount", snap.roleCount());
        semanticProfile.set("diagnostics", SemanticProfileDiagnosticsJson.diagnosticsArray(snap.diagnostics()));
        if ("not_configured".equals(snap.snapshotStatus())) {
            return;
        }
        for (SemanticProfileDiagnostic d : snap.diagnostics()) {
            String severity = d.severity() == SemanticProfileDiagnostic.Severity.ERROR ? "error" : "warning";
            items.add(item(severity, ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON, d.code(), d.message()));
            if (d.severity() == SemanticProfileDiagnostic.Severity.ERROR) {
                errorWarningDelta[0]++;
            } else {
                errorWarningDelta[1]++;
            }
        }
        if (!snap.isLoaded() && snap.diagnostics().isEmpty()) {
            items.add(item("error", ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON, "SEMANTIC_PROFILE_UNAVAILABLE",
                    "semantic-profile.json could not be loaded"));
            errorWarningDelta[0]++;
        }
    }

    /**
     * Structured taxonomy: v3 array identity and/or v3 {@code asset-types.json} object map load independently; v2
     * object {@code version: 2} — when that object is valid, optional legacy warning when non-empty
     * {@code asset-types.json} is present; when v2 identity is invalid, a present asset file is a candidate v3 map
     * (successful salvage, failed parse with asset diagnostics, or read error) — never {@code TAXONOMY_LEGACY_FILE_PRESENT}
     * on invalid-v2 paths.
     */
    static void appendStructuredTaxonomyValidation(RepositoryReader reader, Logger log, String agentName,
            ObjectNode taxonomy, ArrayNode items, int[] errorWarningDelta) throws Exception {
        ObjectNode identityJson = taxonomy.putObject("identityTypesJson");
        identityJson.put("path", ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        ObjectNode assetJson = taxonomy.putObject("assetTypesJson");
        assetJson.put("path", ConfigurationRepositoryPaths.ASSET_TYPES_JSON);
        RepositoryTextLoads.Result idTr =
                RepositoryTextLoads.loadText(reader, ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        RepositoryTextLoads.Result assetTr =
                RepositoryTextLoads.loadText(reader, ConfigurationRepositoryPaths.ASSET_TYPES_JSON);

        if (idTr.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
            identityJson.put("status", "read_error");
            identityJson.put("typeCount", 0);
            identityJson.set("diagnostics", MAPPER.createArrayNode());
            assetJson.put("status", "unknown");
            assetJson.put("typeCount", 0);
            assetJson.set("diagnostics", MAPPER.createArrayNode());
            items.add(item("error", ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON, "TAXONOMY_CONFIG_INVALID",
                    idTr.errorMessage()));
            errorWarningDelta[0]++;
            return;
        }
        if (idTr.kind() == RepositoryTextLoads.Kind.MISSING || idTr.kind() == RepositoryTextLoads.Kind.EMPTY) {
            identityJson.put("status", "missing");
            identityJson.put("typeCount", 0);
            identityJson.set("diagnostics", MAPPER.createArrayNode());
            populateAssetTypesJsonForValidation(assetTr, assetJson, log, agentName, items, errorWarningDelta, true);
            return;
        }
        String identityText = idTr.text();
        boolean v3Root = identityText != null && identityText.stripLeading().startsWith("[");

        if (v3Root) {
            validateV3IdentityWithOptionalAssets(identityText, assetTr, identityJson, assetJson, log, agentName,
                    items, errorWarningDelta);
            return;
        }

        IdentityTypesJsonParser.ParseOutcome idParsed =
                IdentityTypesJsonParser.parse(identityText, log, agentName, false);
        identityJson.set("diagnostics", TaxonomyResolverJson.diagnosticsArray(idParsed.diagnostics()));

        if (!idParsed.configValid()) {
            TaxonomyV3JsonParsers.AssetTypesOutcome salvageAssets = tryParseSalvageV3AssetMap(assetTr, log, agentName);
            if (salvageAssets != null) {
                writeAssetJsonFromOutcome(assetJson, salvageAssets);
                identityJson.put("status", "invalid");
                identityJson.put("typeCount", 0);
                emitIdentityParseOutcomeValidationItems(items, idParsed, true, errorWarningDelta);
                emitAssetTypesOutcomeValidationItems(items, salvageAssets, true, errorWarningDelta);
                return;
            }
            populateAssetJsonWhenInvalidV2IdentitySalvageFailed(assetTr, assetJson, log, agentName, items,
                    errorWarningDelta);
            identityJson.put("status", "invalid");
            identityJson.put("typeCount", 0);
            emitIdentityParseOutcomeValidationItems(items, idParsed, false, errorWarningDelta);
            return;
        }

        if (assetTr.kind() == RepositoryTextLoads.Kind.CONTENT && assetTr.text() != null
                && !assetTr.text().isBlank()) {
            items.add(item("warning", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, "TAXONOMY_LEGACY_FILE_PRESENT",
                    "asset-types.json is present alongside v2 identity-types.json; it is ignored — "
                            + "remove it after migrating to v3 array identity + asset-types map."));
            errorWarningDelta[1]++;
        }
        assetJson.put("status", "not_applicable_v2_identity");
        assetJson.put("typeCount", 0);
        assetJson.set("diagnostics", MAPPER.createArrayNode());
        identityJson.put("status", "loaded");
        identityJson.put("typeCount", idParsed.entries().size());
        emitIdentityParseOutcomeValidationItems(items, idParsed, false, errorWarningDelta);
    }

    private static TaxonomyV3JsonParsers.AssetTypesOutcome tryParseSalvageV3AssetMap(RepositoryTextLoads.Result assetTr,
            Logger log, String agentName) throws Exception {
        if (assetTr.kind() != RepositoryTextLoads.Kind.CONTENT || assetTr.text() == null
                || assetTr.text().isBlank()) {
            return null;
        }
        String at = assetTr.text();
        if (at.stripLeading().startsWith("[")) {
            return null;
        }
        TaxonomyV3JsonParsers.AssetTypesOutcome assets =
                TaxonomyV3JsonParsers.parseAssetTypesObject(at, log, agentName, true);
        if (!assets.valid() || assets.entries().isEmpty()) {
            return null;
        }
        return assets;
    }

    private static void writeAssetJsonFromOutcome(ObjectNode assetJson, TaxonomyV3JsonParsers.AssetTypesOutcome assets) {
        assetJson.set("diagnostics", TaxonomyResolverJson.diagnosticsArray(assets.diagnostics()));
        assetJson.put("typeCount", assets.entries().size());
        assetJson.put("status", "loaded");
    }

    /**
     * When v2-shaped identity is invalid and the v3 asset-map salvage did not yield a loaded map: surface asset-side
     * read/parse state (not {@code not_applicable_v2_identity} with empty diagnostics) and never emit
     * {@code TAXONOMY_LEGACY_FILE_PRESENT} — the asset file is a candidate v3 map, not legacy-ignored.
     */
    private static void populateAssetJsonWhenInvalidV2IdentitySalvageFailed(RepositoryTextLoads.Result assetTr,
            ObjectNode assetJson, Logger log, String agentName, ArrayNode items, int[] errorWarningDelta)
            throws Exception {
        if (assetTr.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
            assetJson.put("status", "read_error");
            assetJson.put("typeCount", 0);
            assetJson.set("diagnostics", MAPPER.createArrayNode());
            items.add(item("error", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, "TAXONOMY_CONFIG_INVALID",
                    assetTr.errorMessage() != null ? assetTr.errorMessage() : "read failed"));
            errorWarningDelta[0]++;
            return;
        }
        if (assetTr.kind() != RepositoryTextLoads.Kind.CONTENT || assetTr.text() == null
                || assetTr.text().isBlank()) {
            assetJson.put("status", "not_applicable_v2_identity");
            assetJson.put("typeCount", 0);
            assetJson.set("diagnostics", MAPPER.createArrayNode());
            return;
        }
        String at = assetTr.text();
        if (at.stripLeading().startsWith("[")) {
            assetJson.put("status", "invalid");
            assetJson.put("typeCount", 0);
            assetJson.set("diagnostics", MAPPER.createArrayNode());
            items.add(item("error", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, "TAXONOMY_CONFIG_INVALID",
                    "asset-types.json root must be a JSON object for v3"));
            errorWarningDelta[0]++;
            return;
        }
        TaxonomyV3JsonParsers.AssetTypesOutcome assets =
                TaxonomyV3JsonParsers.parseAssetTypesObject(at, log, agentName, true);
        assetJson.set("diagnostics", TaxonomyResolverJson.diagnosticsArray(assets.diagnostics()));
        assetJson.put("typeCount", assets.entries().size());
        assetJson.put("status", "invalid");
        emitAssetTypesOutcomeValidationItems(items, assets, false, errorWarningDelta);
    }

    private static void emitIdentityParseOutcomeValidationItems(ArrayNode items,
            IdentityTypesJsonParser.ParseOutcome idParsed, boolean downgradeErrorsToWarnings, int[] errorWarningDelta) {
        for (TaxonomyDiagnostic d : idParsed.diagnostics()) {
            if (d.severity() == TaxonomyDiagnostic.Severity.ERROR && downgradeErrorsToWarnings) {
                items.add(item("warning", ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[1]++;
            } else if (d.severity() == TaxonomyDiagnostic.Severity.ERROR) {
                items.add(item("error", ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[0]++;
            } else {
                items.add(item("warning", ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[1]++;
            }
        }
    }

    private static void emitAssetTypesOutcomeValidationItems(ArrayNode items,
            TaxonomyV3JsonParsers.AssetTypesOutcome assets, boolean downgradeErrorsToWarnings, int[] errorWarningDelta) {
        for (TaxonomyDiagnostic d : assets.diagnostics()) {
            if (d.severity() == TaxonomyDiagnostic.Severity.ERROR && downgradeErrorsToWarnings) {
                items.add(item("warning", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[1]++;
            } else if (d.severity() == TaxonomyDiagnostic.Severity.ERROR) {
                items.add(item("error", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[0]++;
            } else {
                items.add(item("warning", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[1]++;
            }
        }
    }

    private static void validateV3IdentityWithOptionalAssets(String identityText, RepositoryTextLoads.Result assetTr,
            ObjectNode identityJson, ObjectNode assetJson, Logger log, String agentName, ArrayNode items,
            int[] errorWarningDelta) throws Exception {
        TaxonomyV3JsonParsers.IdentityRulesOutcome idRules =
                TaxonomyV3JsonParsers.parseIdentityRulesArray(identityText, log, agentName);
        identityJson.put("typeCount", idRules.rules().size());
        identityJson.put("status", idRules.valid() ? "loaded" : "invalid");
        java.util.List<TaxonomyDiagnostic> idDx = new java.util.ArrayList<>(idRules.diagnostics());
        identityJson.set("diagnostics", TaxonomyResolverJson.diagnosticsArray(idDx));

        if (assetTr.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
            emitV3IdentityValidationItems(items, idRules, errorWarningDelta, false);
            assetJson.put("status", "read_error");
            assetJson.put("typeCount", 0);
            assetJson.set("diagnostics", MAPPER.createArrayNode());
            if (idRules.valid()) {
                items.add(item("warning", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, "TAXONOMY_ASSET_TYPES_READ_ERROR",
                        assetTr.errorMessage() != null ? assetTr.errorMessage() : "read failed"));
                errorWarningDelta[1]++;
            } else {
                items.add(item("error", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, "TAXONOMY_CONFIG_INVALID",
                        assetTr.errorMessage() != null ? assetTr.errorMessage() : "read failed"));
                errorWarningDelta[0]++;
            }
            return;
        }
        if (assetTr.kind() != RepositoryTextLoads.Kind.CONTENT || assetTr.text() == null
                || assetTr.text().isBlank()) {
            emitV3IdentityValidationItems(items, idRules, errorWarningDelta, false);
            assetJson.put("status", "missing");
            assetJson.put("typeCount", 0);
            assetJson.set("diagnostics", MAPPER.createArrayNode());
            if (idRules.valid()) {
                items.add(item("warning", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, "TAXONOMY_ASSET_TYPES_ABSENT",
                        "asset-types.json is missing or empty; list_asset_types / resolve_asset_type need this file."));
                errorWarningDelta[1]++;
            }
            return;
        }
        TaxonomyV3JsonParsers.AssetTypesOutcome assets =
                TaxonomyV3JsonParsers.parseAssetTypesObject(assetTr.text(), log, agentName, false);
        java.util.List<TaxonomyDiagnostic> adx = new java.util.ArrayList<>(assets.diagnostics());
        assetJson.set("diagnostics", TaxonomyResolverJson.diagnosticsArray(adx));
        assetJson.put("typeCount", assets.entries().size());
        assetJson.put("status", assets.valid() ? "loaded" : "invalid");
        boolean assetSideUsable = assets.valid() && !assets.entries().isEmpty();
        emitV3IdentityValidationItems(items, idRules, errorWarningDelta, !idRules.valid() && assetSideUsable);
        for (TaxonomyDiagnostic d : assets.diagnostics()) {
            if (d.severity() == TaxonomyDiagnostic.Severity.ERROR) {
                if (idRules.valid() || assetSideUsable) {
                    items.add(item("warning", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, d.code(), d.message()));
                    errorWarningDelta[1]++;
                } else {
                    items.add(item("error", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, d.code(), d.message()));
                    errorWarningDelta[0]++;
                }
            } else {
                items.add(item("warning", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[1]++;
            }
        }
    }

    private static void emitV3IdentityValidationItems(ArrayNode items, TaxonomyV3JsonParsers.IdentityRulesOutcome idRules,
            int[] errorWarningDelta, boolean downgradeIdentityErrorsToWarnings) {
        for (TaxonomyDiagnostic d : idRules.diagnostics()) {
            if (d.severity() == TaxonomyDiagnostic.Severity.ERROR && downgradeIdentityErrorsToWarnings) {
                items.add(item("warning", ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[1]++;
            } else if (d.severity() == TaxonomyDiagnostic.Severity.ERROR) {
                items.add(item("error", ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[0]++;
            } else {
                items.add(item("warning", ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[1]++;
            }
        }
    }

    private static void populateAssetTypesJsonForValidation(RepositoryTextLoads.Result assetTr, ObjectNode assetJson,
            Logger log, String agentName, ArrayNode items, int[] errorWarningDelta, boolean identityMissing)
            throws Exception {
        if (assetTr.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
            assetJson.put("status", "read_error");
            assetJson.put("typeCount", 0);
            assetJson.set("diagnostics", MAPPER.createArrayNode());
            items.add(item("error", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, "TAXONOMY_CONFIG_INVALID",
                    assetTr.errorMessage()));
            errorWarningDelta[0]++;
            return;
        }
        if (assetTr.kind() != RepositoryTextLoads.Kind.CONTENT || assetTr.text() == null
                || assetTr.text().isBlank()) {
            assetJson.put("status", "missing");
            assetJson.put("typeCount", 0);
            assetJson.set("diagnostics", MAPPER.createArrayNode());
            return;
        }
        String at = assetTr.text();
        if (at.stripLeading().startsWith("[")) {
            assetJson.put("status", "invalid");
            assetJson.put("typeCount", 0);
            assetJson.set("diagnostics", MAPPER.createArrayNode());
            items.add(item("error", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, "TAXONOMY_CONFIG_INVALID",
                    "asset-types.json root must be a JSON object for v3"));
            errorWarningDelta[0]++;
            return;
        }
        TaxonomyV3JsonParsers.AssetTypesOutcome assets =
                TaxonomyV3JsonParsers.parseAssetTypesObject(at, log, agentName, true);
        assetJson.set("diagnostics", TaxonomyResolverJson.diagnosticsArray(assets.diagnostics()));
        assetJson.put("typeCount", assets.entries().size());
        assetJson.put("status", assets.valid() && !assets.entries().isEmpty() ? "loaded" : "invalid");
        for (TaxonomyDiagnostic d : assets.diagnostics()) {
            if (d.severity() == TaxonomyDiagnostic.Severity.ERROR) {
                items.add(item("error", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[0]++;
            } else {
                items.add(item("warning", ConfigurationRepositoryPaths.ASSET_TYPES_JSON, d.code(), d.message()));
                errorWarningDelta[1]++;
            }
        }
        if (identityMissing && assets.valid() && !assets.entries().isEmpty()) {
            items.add(item("warning", ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON, "TAXONOMY_IDENTITY_RULES_ABSENT",
                    "identity-types.json is missing or empty; resolve_thing requires v3 identity array rules."));
            errorWarningDelta[1]++;
        }
    }

    /**
     * Maps {@link PlaybookRegistrySnapshot} diagnostics to validation {@code items[]} and error/warning counts.
     * Package-private for unit tests (mirrors {@code ValidateAgentConfigurationRepository} behavior).
     */
    static void appendPlaybookRegistryValidationItems(PlaybookRegistrySnapshot playbookSnap, ArrayNode items,
            int[] errorWarningDelta) {
        for (PlaybookRegistryDiagnostic d : playbookSnap.registryDiagnostics()) {
            if (d == null) {
                continue;
            }
            String line = d.snapshotLine();
            if (line == null || line.isBlank()) {
                continue;
            }
            String sev = d.severity() == PlaybookRegistryDiagnostic.Severity.ERROR ? "error" : "warning";
            items.add(item(sev, d.path(), d.code(), line));
            if (d.severity() == PlaybookRegistryDiagnostic.Severity.ERROR) {
                errorWarningDelta[0]++;
            } else {
                errorWarningDelta[1]++;
            }
        }
    }

    /**
     * Structured per-package validation reports for loaded and failed playbook packages (Slice B).
     */
    static void appendPlaybookDocumentValidationReports(RepositoryReader reader, List<ToolDefinition> playbookToolDefs,
            ExtendedToolRegistrySnapshot ext, ArrayNode playbookValidations, Logger log, AgentThing agent)
            throws Exception {
        if (reader == null) {
            return;
        }
        String agentVersion = ParlerPackageVersion.fromAnchorClass(AgentThing.class);
        com.thingworx.types.InfoTable listing = reader.getFileListing(PlaybookIds.PLAYBOOK_ROOT, "");
        List<String> dirNames = RepositorySkillScanner.extractTopLevelDirectoryNames(listing);
        java.util.Collections.sort(dirNames);
        for (String dirId : dirNames) {
            if (dirId == null || dirId.isEmpty() || dirId.charAt(0) == '.') {
                continue;
            }
            String path = PlaybookIds.playbookPathForId(dirId);
            RepositoryTextLoads.Result docRes = RepositoryTextLoads.loadText(reader, path);
            PlaybookValidationReport report;
            if (docRes.kind() != RepositoryTextLoads.Kind.CONTENT) {
                report = PlaybookValidationReport.packageUnreadable(dirId, path, docRes.kind().name(), agentVersion);
            } else {
                try {
                    JSONObject rootProbe = new JSONObject(docRes.text().trim());
                    PlaybookValidator.Result rootVal = PlaybookValidator.validatePlaybookJsonRoot(rootProbe);
                    if (!rootVal.valid()) {
                        report = PlaybookValidationReportBuilder.build(null, rootVal, dirId, agentVersion);
                    } else {
                        PlaybookDocument doc = PlaybookDocument.parse(docRes.text());
                        PlaybookValidator.Result docVal =
                                PlaybookValidator.validateDocument(doc, dirId, playbookToolDefs, ext);
                        report = PlaybookValidationReportBuilder.build(doc, docVal, dirId, agentVersion);
                    }
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    report = PlaybookValidationReport.invalidJson(msg, dirId, agentVersion);
                }
            }
            ObjectNode row = playbookValidations.addObject();
            row.put("packageDirId", dirId);
            row.put("path", path);
            row.set("report", report.toObjectNode());
        }
    }

    private static void putSummary(ObjectNode root, int errors, int warnings, int skillsReg, int extReg, int polRules) {
        ObjectNode s = root.putObject("summary");
        s.put("errors", errors);
        s.put("warnings", warnings);
        s.put("skillsRegistered", skillsReg);
        s.put("extendedToolsRegistered", extReg);
        s.put("invokeServicePolicyRules", polRules);
    }

    private static ObjectNode item(String severity, String path, String code, String message) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("severity", severity);
        n.put("path", path);
        n.put("code", code);
        n.put("message", message);
        return n;
    }
}
