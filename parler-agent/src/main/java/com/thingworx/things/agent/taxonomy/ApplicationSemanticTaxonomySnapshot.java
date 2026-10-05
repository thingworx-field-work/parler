package com.thingworx.things.agent.taxonomy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;

/**
 * Immutable semantic taxonomy loaded from {@code /taxonomies/identity-types.json} (v2 object or v3 array) plus
 * optional v3 {@code /taxonomies/asset-types.json}. Stored on {@link com.thingworx.things.agent.PromptContextCacheSnapshot}.
 */
public final class ApplicationSemanticTaxonomySnapshot {

    private final boolean loaded;
    private final boolean stale;
    private final String snapshotStatus;
    private final List<AssetTypeEntry> assetTypes;
    /** v3 unique-Thing rules; empty for v2-only snapshots. */
    private final List<ThingIdentityRuleV3> thingIdentityRules;
    private final List<TaxonomyDiagnostic> diagnostics;
    private final Instant lastSuccessfulRefresh;
    private final Instant lastAttemptedRefresh;
    /** Fast path: only keys that appear on exactly one flattened row. */
    private final Map<String, AssetTypeEntry> byUniqueAssetTypeKey;
    private final String effectiveSourcePath;

    private ApplicationSemanticTaxonomySnapshot(boolean loaded, boolean stale, String snapshotStatus,
            List<AssetTypeEntry> assetTypes, List<ThingIdentityRuleV3> thingIdentityRules,
            List<TaxonomyDiagnostic> diagnostics, Instant lastSuccessfulRefresh,
            Instant lastAttemptedRefresh, String effectiveSourcePath) {
        this.loaded = loaded;
        this.stale = stale;
        this.snapshotStatus = snapshotStatus != null ? snapshotStatus : "unavailable";
        this.assetTypes = assetTypes != null ? List.copyOf(assetTypes) : List.of();
        this.thingIdentityRules = thingIdentityRules != null ? List.copyOf(thingIdentityRules) : List.of();
        this.diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        this.lastSuccessfulRefresh = lastSuccessfulRefresh;
        this.lastAttemptedRefresh = lastAttemptedRefresh;
        this.effectiveSourcePath = effectiveSourcePath != null ? effectiveSourcePath : "";
        Map<String, Long> keyCounts =
                this.assetTypes.stream().collect(Collectors.groupingBy(AssetTypeEntry::key, Collectors.counting()));
        LinkedHashMap<String, AssetTypeEntry> map = new LinkedHashMap<>();
        for (AssetTypeEntry e : this.assetTypes) {
            if (keyCounts.getOrDefault(e.key(), 0L) == 1L) {
                map.put(e.key(), e);
            }
        }
        this.byUniqueAssetTypeKey = Collections.unmodifiableMap(map);
    }

    public static ApplicationSemanticTaxonomySnapshot notConfigured(Instant attemptedAt,
            List<TaxonomyDiagnostic> diagnostics) {
        Instant at = attemptedAt != null ? attemptedAt : Instant.now();
        return new ApplicationSemanticTaxonomySnapshot(false, false, "not_configured", List.of(), List.of(),
                diagnostics != null ? diagnostics : List.of(), null, at, "");
    }

    public static ApplicationSemanticTaxonomySnapshot unavailable(Instant attemptedAt,
            List<TaxonomyDiagnostic> diagnostics) {
        return unavailable(attemptedAt, diagnostics, "");
    }

    /**
     * @param attemptedSourcePath repository path that was read when entering {@code unavailable} (e.g.
     *        {@code /taxonomies/identity-types.json} after a parse failure); may be empty
     */
    public static ApplicationSemanticTaxonomySnapshot unavailable(Instant attemptedAt,
            List<TaxonomyDiagnostic> diagnostics, String attemptedSourcePath) {
        Instant at = attemptedAt != null ? attemptedAt : Instant.now();
        String p = attemptedSourcePath != null ? attemptedSourcePath : "";
        return new ApplicationSemanticTaxonomySnapshot(false, false, "unavailable", List.of(), List.of(),
                diagnostics != null ? diagnostics : List.of(), null, at, p);
    }

    public static ApplicationSemanticTaxonomySnapshot loaded(List<AssetTypeEntry> assetTypes,
            List<TaxonomyDiagnostic> diagnostics, boolean stale, Instant refreshedAt, String effectiveSourcePath) {
        return loaded(assetTypes, List.of(), diagnostics, stale, refreshedAt, effectiveSourcePath);
    }

    public static ApplicationSemanticTaxonomySnapshot loaded(List<AssetTypeEntry> assetTypes,
            List<ThingIdentityRuleV3> thingIdentityRules, List<TaxonomyDiagnostic> diagnostics, boolean stale,
            Instant refreshedAt, String effectiveSourcePath) {
        Instant at = refreshedAt != null ? refreshedAt : Instant.now();
        boolean hasAssets = assetTypes != null && !assetTypes.isEmpty();
        boolean hasIdentity = thingIdentityRules != null && !thingIdentityRules.isEmpty();
        String status = (hasAssets || hasIdentity) ? "loaded" : "empty";
        return new ApplicationSemanticTaxonomySnapshot(true, stale, status, assetTypes, thingIdentityRules, diagnostics,
                at, at, effectiveSourcePath);
    }

    /** Serve prior cache after a failed refresh (§8.1). */
    public static ApplicationSemanticTaxonomySnapshot staleFromPrior(ApplicationSemanticTaxonomySnapshot prior,
            Instant attemptedAt, List<TaxonomyDiagnostic> currentFailureDiagnostics) {
        if (prior == null || !prior.loaded) {
            return unavailable(attemptedAt, currentFailureDiagnostics);
        }
        Instant at = attemptedAt != null ? attemptedAt : Instant.now();
        List<TaxonomyDiagnostic> merged = mergeDiagnostics(currentFailureDiagnostics, prior.diagnostics);
        return new ApplicationSemanticTaxonomySnapshot(true, true, "stale", prior.assetTypes, prior.thingIdentityRules,
                merged, prior.lastSuccessfulRefresh, at, prior.effectiveSourcePath);
    }

    private static List<TaxonomyDiagnostic> mergeDiagnostics(List<TaxonomyDiagnostic> current,
            List<TaxonomyDiagnostic> prior) {
        List<TaxonomyDiagnostic> merged = new ArrayList<>();
        if (current != null) {
            merged.addAll(current);
        }
        if (prior != null) {
            merged.addAll(prior);
        }
        return merged;
    }

    /** Repository path for the loaded structured taxonomy file (empty when not loaded). */
    public String effectiveSourcePath() {
        return effectiveSourcePath;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isStale() {
        return stale;
    }

    public String snapshotStatus() {
        return snapshotStatus;
    }

    public boolean isResolverAvailable() {
        return loaded;
    }

    public List<AssetTypeEntry> assetTypes() {
        return assetTypes;
    }

    /** v3 identity rules from {@code identity-types.json} array; empty when v2-only. */
    public List<ThingIdentityRuleV3> thingIdentityRules() {
        return thingIdentityRules;
    }

    public int assetTypeCount() {
        return assetTypes.size();
    }

    public List<TaxonomyDiagnostic> diagnostics() {
        return diagnostics;
    }

    public Instant lastSuccessfulRefresh() {
        return lastSuccessfulRefresh;
    }

    public Instant lastAttemptedRefresh() {
        return lastAttemptedRefresh;
    }

    public List<AssetTypeEntry> entriesWithAssetTypeKey(String assetTypeKey) {
        if (assetTypeKey == null) {
            return List.of();
        }
        return assetTypes.stream().filter(e -> assetTypeKey.equals(e.key())).collect(Collectors.toUnmodifiableList());
    }

    /**
     * Resolves a type row for v2-style identifier flows (internal). When multiple rows share the same
     * {@code types[].key} across entities, {@code entityKeyOpt} must match {@link AssetTypeEntry#entityKey()}.
     */
    public Optional<AssetTypeEntry> resolveIdentifierEntry(String assetTypeKey, String entityKeyOpt) {
        List<AssetTypeEntry> m = entriesWithAssetTypeKey(assetTypeKey);
        if (m.isEmpty()) {
            return Optional.empty();
        }
        if (m.size() == 1) {
            return Optional.of(m.get(0));
        }
        if (entityKeyOpt != null && !entityKeyOpt.isBlank()) {
            return m.stream().filter(e -> entityKeyOpt.equals(e.entityKey())).findFirst();
        }
        return Optional.empty();
    }

    /** When {@code assetTypeKey} is globally unique in this snapshot, returns that row. */
    public Optional<AssetTypeEntry> findByExactKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byUniqueAssetTypeKey.get(key));
    }

    public String formatDiagnosticsMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Application semantic taxonomy diagnostics\n\n");
        sb.append("- loaded: **").append(loaded).append("**\n");
        sb.append("- status: **").append(snapshotStatus).append("**\n");
        sb.append("- assetTypeCount: ").append(assetTypeCount()).append("\n");
        sb.append("- stale: **").append(stale).append("**\n");
        sb.append("- sourcePath: `").append(effectiveSourcePath.isEmpty() ? "(n/a)" : effectiveSourcePath)
                .append("`\n");
        sb.append("- diagnostics: ").append(diagnostics.size()).append("\n");
        for (TaxonomyDiagnostic d : diagnostics) {
            sb.append("  - [").append(d.severity().name().toLowerCase()).append("] ")
                    .append(d.code()).append(": ").append(d.message()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ApplicationSemanticTaxonomySnapshot)) {
            return false;
        }
        ApplicationSemanticTaxonomySnapshot that = (ApplicationSemanticTaxonomySnapshot) o;
        return loaded == that.loaded && stale == that.stale && Objects.equals(snapshotStatus, that.snapshotStatus)
                && Objects.equals(assetTypes, that.assetTypes)
                && Objects.equals(thingIdentityRules, that.thingIdentityRules)
                && Objects.equals(diagnostics, that.diagnostics)
                && Objects.equals(lastSuccessfulRefresh, that.lastSuccessfulRefresh)
                && Objects.equals(lastAttemptedRefresh, that.lastAttemptedRefresh)
                && Objects.equals(effectiveSourcePath, that.effectiveSourcePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(loaded, stale, snapshotStatus, assetTypes, thingIdentityRules, diagnostics,
                lastSuccessfulRefresh, lastAttemptedRefresh, effectiveSourcePath);
    }
}
