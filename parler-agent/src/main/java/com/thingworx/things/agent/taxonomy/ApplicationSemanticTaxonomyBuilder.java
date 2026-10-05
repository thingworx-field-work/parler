package com.thingworx.things.agent.taxonomy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;
import com.thingworx.things.agent.configrepo.RepositoryTextLoads;
import com.thingworx.things.agent.skillregistry.FileRepositoryRepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositoryReader;

/**
 * Loads semantic taxonomy from the agent configuration repository during prompt-context refresh.
 *
 * <p>Supports:</p>
 * <ul>
 * <li><b>v2</b> — {@code identity-types.json} root object with {@code version: 2} and {@code entities[]} (see
 * {@link IdentityTypesJsonParser}). When that object is <b>valid</b>, a non-empty {@code asset-types.json} triggers
 * {@code TAXONOMY_LEGACY_FILE_PRESENT} (presence-only; not parsed as taxonomy). When v2 identity is <b>invalid</b> but
 * {@code asset-types.json} is a valid non-empty v3 map, the map loads like the v3 asset-types-only path.</li>
 * <li><b>v3</b> — {@code identity-types.json} may be a root JSON array of identity rules and/or
 * {@code asset-types.json} may be a v3 object map. The two files load <b>independently</b>: identity rules can load
 * without asset-type definitions (and vice versa); missing companion files produce scoped warnings, not a global
 * {@code TAXONOMY_UNAVAILABLE} for the other side.</li>
 * </ul>
 */
public final class ApplicationSemanticTaxonomyBuilder {

    private ApplicationSemanticTaxonomyBuilder() {}

    public static ApplicationSemanticTaxonomySnapshot build(AgentThing agent, Logger log) {
        Instant now = Instant.now();
        String repoName = agent.getConfigurationRepositoryThingName();
        if (repoName == null || repoName.isBlank()) {
            return ApplicationSemanticTaxonomySnapshot.notConfigured(now, List.of());
        }
        var fr = FileRepositoryThingResolver.resolve(repoName.trim(), log, agent.getName());
        if (fr.isEmpty()) {
            List<TaxonomyDiagnostic> dx = List.of(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR,
                    "TAXONOMY_UNAVAILABLE", "Configuration repository Thing is not available: " + repoName.trim()));
            return ApplicationSemanticTaxonomySnapshot.unavailable(now, dx, "");
        }
        RepositoryReader reader = FileRepositoryRepositoryReader.forConfiguration(fr.get());
        return buildFromRepositoryReader(reader, log, agent.getName(), now);
    }

    /**
     * Same taxonomy load as {@link #build(AgentThing, Logger)} but with an explicit reader (unit tests).
     */
    public static ApplicationSemanticTaxonomySnapshot buildFromRepositoryReader(RepositoryReader reader, Logger log,
            String agentName, Instant now) {
        RepositoryTextLoads.Result idTr =
                RepositoryTextLoads.loadText(reader, ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        RepositoryTextLoads.Result assetTypesTr =
                RepositoryTextLoads.loadText(reader, ConfigurationRepositoryPaths.ASSET_TYPES_JSON);

        boolean assetTypesPresent =
                assetTypesTr.kind() == RepositoryTextLoads.Kind.CONTENT && assetTypesTr.text() != null
                        && !assetTypesTr.text().isBlank();

        boolean identityPresent =
                idTr.kind() == RepositoryTextLoads.Kind.CONTENT && idTr.text() != null && !idTr.text().isBlank();

        List<TaxonomyDiagnostic> notConfiguredExtras = new ArrayList<>();
        if (idTr.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
            log.error("[{}] identity-types.json read failed: {}", agentName, idTr.errorMessage());
            List<TaxonomyDiagnostic> dx = List.of(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR,
                    "TAXONOMY_UNAVAILABLE", idTr.errorMessage() != null ? idTr.errorMessage() : "read failed"));
            return ApplicationSemanticTaxonomySnapshot.unavailable(now, dx,
                    ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        }

        if (identityPresent) {
            String identityText = idTr.text();
            boolean v3Identity = identityText.stripLeading().startsWith("[");
            if (v3Identity) {
                return buildV3WithIdentityArray(log, agentName, now, identityText, assetTypesTr);
            }

            List<TaxonomyDiagnostic> extra = new ArrayList<>();
            if (assetTypesPresent) {
                extra.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_LEGACY_FILE_PRESENT",
                        "identity-types.json is authoritative; asset-types.json is present but ignored. "
                                + "Remove asset-types.json from the configuration repository when ready."));
            }
            IdentityTypesJsonParser.ParseOutcome parsed =
                    IdentityTypesJsonParser.parse(identityText, log, agentName);
            if (!parsed.configValid()) {
                List<TaxonomyDiagnostic> idFail = new ArrayList<>(parsed.diagnostics());
                if (idFail.stream().noneMatch(d -> "TAXONOMY_CONFIG_INVALID".equals(d.code())
                        && d.severity() == TaxonomyDiagnostic.Severity.ERROR)) {
                    idFail.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                            "identity-types.json is invalid"));
                }
                Optional<ApplicationSemanticTaxonomySnapshot> assetOnly = tryLoadedAssetTypesOnlySnapshot(
                        assetTypesTr, log, agentName, now, downgradeErrorsToWarnings(idFail));
                if (assetOnly.isPresent()) {
                    return assetOnly.get();
                }
                // Invalid v2 identity: companion asset file is a candidate v3 map, not legacy-ignored; do not attach
                // TAXONOMY_LEGACY_FILE_PRESENT here — appendAssetSideFailureDiagnosticsForUnavailable carries asset parse
                // failures when present.
                appendAssetSideFailureDiagnosticsForUnavailable(idFail, assetTypesTr, log, agentName);
                return ApplicationSemanticTaxonomySnapshot.unavailable(now, idFail,
                        ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
            }
            List<TaxonomyDiagnostic> dx = new ArrayList<>(parsed.diagnostics());
            dx.addAll(extra);
            return ApplicationSemanticTaxonomySnapshot.loaded(parsed.entries(), List.of(), dx, false, now,
                    ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        }

        // No identity content: allow v3 asset-types-only load (parse any non-blank asset file; malformed → unavailable)
        if (assetTypesPresent) {
            String assetText = assetTypesTr.text();
            TaxonomyV3JsonParsers.AssetTypesOutcome assets =
                    TaxonomyV3JsonParsers.parseAssetTypesObject(assetText, log, agentName, true);
            if (assets.valid() && !assets.entries().isEmpty()) {
                List<TaxonomyDiagnostic> dx = new ArrayList<>(notConfiguredExtras);
                dx.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_IDENTITY_RULES_ABSENT",
                        "identity-types.json is missing or empty; resolve_thing is unavailable until v3 identity rules are configured."));
                dx.addAll(assets.diagnostics());
                return ApplicationSemanticTaxonomySnapshot.loaded(assets.entries(), List.of(), dx, false, now,
                        ConfigurationRepositoryPaths.ASSET_TYPES_JSON);
            }
            List<TaxonomyDiagnostic> dx = new ArrayList<>(notConfiguredExtras);
            dx.addAll(assets.diagnostics());
            if (dx.isEmpty()) {
                dx.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR, "TAXONOMY_CONFIG_INVALID",
                        "asset-types.json could not be loaded as a non-empty v3 object map."));
            }
            return ApplicationSemanticTaxonomySnapshot.unavailable(now, dx,
                    ConfigurationRepositoryPaths.ASSET_TYPES_JSON);
        }

        return ApplicationSemanticTaxonomySnapshot.notConfigured(now, notConfiguredExtras);
    }

    private static ApplicationSemanticTaxonomySnapshot buildV3WithIdentityArray(Logger log,
            String agentName, Instant now, String identityText, RepositoryTextLoads.Result assetTypesTr) {
        TaxonomyV3JsonParsers.IdentityRulesOutcome idRules =
                TaxonomyV3JsonParsers.parseIdentityRulesArray(identityText, log, agentName);
        if (!idRules.valid()) {
            Optional<ApplicationSemanticTaxonomySnapshot> assetOnly = tryLoadedAssetTypesOnlySnapshot(
                    assetTypesTr, log, agentName, now, downgradeErrorsToWarnings(idRules.diagnostics()));
            if (assetOnly.isPresent()) {
                return assetOnly.get();
            }
            List<TaxonomyDiagnostic> failDx = new ArrayList<>(idRules.diagnostics());
            appendAssetSideFailureDiagnosticsForUnavailable(failDx, assetTypesTr, log, agentName);
            return ApplicationSemanticTaxonomySnapshot.unavailable(now, failDx,
                    ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        }
        List<TaxonomyDiagnostic> dx = new ArrayList<>(idRules.diagnostics());
        List<AssetTypeEntry> assetEntries = List.of();

        if (assetTypesTr.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
            dx.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_ASSET_TYPES_READ_ERROR",
                    "asset-types.json read failed: "
                            + (assetTypesTr.errorMessage() != null ? assetTypesTr.errorMessage() : "unknown")));
            return ApplicationSemanticTaxonomySnapshot.loaded(assetEntries, idRules.rules(), dx, false, now,
                    ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        }

        boolean assetContent =
                assetTypesTr.kind() == RepositoryTextLoads.Kind.CONTENT && assetTypesTr.text() != null
                        && !assetTypesTr.text().isBlank();
        if (!assetContent) {
            dx.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_ASSET_TYPES_ABSENT",
                    "asset-types.json is missing or empty; list_asset_types and resolve_asset_type are unavailable."));
            return ApplicationSemanticTaxonomySnapshot.loaded(assetEntries, idRules.rules(), dx, false, now,
                    ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        }

        TaxonomyV3JsonParsers.AssetTypesOutcome assets =
                TaxonomyV3JsonParsers.parseAssetTypesObject(assetTypesTr.text(), log, agentName, false);
        dx.addAll(downgradeErrorsToWarnings(assets.diagnostics()));
        if (assets.valid()) {
            assetEntries = assets.entries();
        } else {
            dx.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_ASSET_TYPES_PARSE_FAILED",
                    "asset-types.json could not be parsed as a v3 object map; continuing with identity rules only."));
        }
        return ApplicationSemanticTaxonomySnapshot.loaded(assetEntries, idRules.rules(), dx, false, now,
                ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
    }

    private static List<TaxonomyDiagnostic> downgradeErrorsToWarnings(List<TaxonomyDiagnostic> in) {
        List<TaxonomyDiagnostic> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        for (TaxonomyDiagnostic d : in) {
            if (d.severity() == TaxonomyDiagnostic.Severity.ERROR) {
                out.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, d.code(), d.message()));
            } else {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * When {@code identity-types.json} is invalid or v2-invalid but {@code asset-types.json} parses to a non-empty v3
     * map, commit an asset-types-only loaded snapshot with {@code prefixDiagnostics} (identity-side failures, typically
     * downgraded to warnings) first.
     */
    private static Optional<ApplicationSemanticTaxonomySnapshot> tryLoadedAssetTypesOnlySnapshot(
            RepositoryTextLoads.Result assetTypesTr, Logger log, String agentName, Instant now,
            List<TaxonomyDiagnostic> prefixDiagnostics) {
        if (assetTypesTr.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
            return Optional.empty();
        }
        if (assetTypesTr.kind() != RepositoryTextLoads.Kind.CONTENT || assetTypesTr.text() == null
                || assetTypesTr.text().isBlank()) {
            return Optional.empty();
        }
        TaxonomyV3JsonParsers.AssetTypesOutcome assets =
                TaxonomyV3JsonParsers.parseAssetTypesObject(assetTypesTr.text(), log, agentName, true);
        if (!assets.valid() || assets.entries().isEmpty()) {
            return Optional.empty();
        }
        List<TaxonomyDiagnostic> dx = new ArrayList<>(prefixDiagnostics);
        dx.add(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_IDENTITY_RULES_ABSENT",
                "identity-types.json is missing or invalid; resolve_thing is unavailable until valid v3 identity array rules are configured."));
        dx.addAll(assets.diagnostics());
        return Optional.of(ApplicationSemanticTaxonomySnapshot.loaded(assets.entries(), List.of(), dx, false, now,
                ConfigurationRepositoryPaths.ASSET_TYPES_JSON));
    }

    private static void appendAssetSideFailureDiagnosticsForUnavailable(List<TaxonomyDiagnostic> target,
            RepositoryTextLoads.Result assetTypesTr, Logger log, String agentName) {
        if (assetTypesTr.kind() != RepositoryTextLoads.Kind.CONTENT || assetTypesTr.text() == null
                || assetTypesTr.text().isBlank()) {
            return;
        }
        TaxonomyV3JsonParsers.AssetTypesOutcome assets =
                TaxonomyV3JsonParsers.parseAssetTypesObject(assetTypesTr.text(), log, agentName, true);
        target.addAll(assets.diagnostics());
    }
}
