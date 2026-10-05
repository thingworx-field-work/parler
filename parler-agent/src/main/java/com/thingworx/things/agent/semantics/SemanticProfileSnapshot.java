package com.thingworx.things.agent.semantics;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;

/**
 * Immutable application semantic-profile snapshot (SP2). Invalid refresh retains a prior valid
 * snapshot via {@link #staleFromPrior}.
 */
public final class SemanticProfileSnapshot {

    public static final String SCHEMA_V1 = "parler-semantic-profile-v1";

    private final boolean loaded;
    private final boolean stale;
    private final String snapshotStatus;
    private final String profileId;
    private final String version;
    private final String digest;
    private final Map<String, List<SemanticPropertyRole>> rolesByAssetType;
    private final List<SemanticProfileDiagnostic> diagnostics;
    private final Instant lastSuccessfulRefresh;
    private final Instant lastAttemptedRefresh;
    private final String effectiveSourcePath;

    private SemanticProfileSnapshot(boolean loaded, boolean stale, String snapshotStatus, String profileId,
            String version, String digest, Map<String, List<SemanticPropertyRole>> rolesByAssetType,
            List<SemanticProfileDiagnostic> diagnostics, Instant lastSuccessfulRefresh, Instant lastAttemptedRefresh,
            String effectiveSourcePath) {
        this.loaded = loaded;
        this.stale = stale;
        this.snapshotStatus = snapshotStatus != null ? snapshotStatus : "unavailable";
        this.profileId = profileId != null ? profileId : "";
        this.version = version != null ? version : "";
        this.digest = digest != null ? digest : "";
        if (rolesByAssetType == null || rolesByAssetType.isEmpty()) {
            this.rolesByAssetType = Map.of();
        } else {
            LinkedHashMap<String, List<SemanticPropertyRole>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<SemanticPropertyRole>> e : rolesByAssetType.entrySet()) {
                copy.put(e.getKey(), List.copyOf(e.getValue()));
            }
            this.rolesByAssetType = Collections.unmodifiableMap(copy);
        }
        this.diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        this.lastSuccessfulRefresh = lastSuccessfulRefresh;
        this.lastAttemptedRefresh = lastAttemptedRefresh;
        this.effectiveSourcePath = effectiveSourcePath != null ? effectiveSourcePath : "";
    }

    public static SemanticProfileSnapshot notConfigured(Instant attemptedAt) {
        Instant at = attemptedAt != null ? attemptedAt : Instant.now();
        return new SemanticProfileSnapshot(false, false, "not_configured", "", "", "", Map.of(), List.of(), null, at,
                "");
    }

    public static SemanticProfileSnapshot unavailable(Instant attemptedAt, List<SemanticProfileDiagnostic> diagnostics) {
        Instant at = attemptedAt != null ? attemptedAt : Instant.now();
        return new SemanticProfileSnapshot(false, false, "unavailable", "", "", "", Map.of(), diagnostics, null, at,
                ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON);
    }

    public static SemanticProfileSnapshot loaded(String profileId, String version, String digest,
            Map<String, List<SemanticPropertyRole>> rolesByAssetType, List<SemanticProfileDiagnostic> diagnostics,
            Instant refreshedAt) {
        Instant at = refreshedAt != null ? refreshedAt : Instant.now();
        boolean empty = rolesByAssetType == null || rolesByAssetType.isEmpty();
        return new SemanticProfileSnapshot(true, false, empty ? "empty" : "loaded", profileId, version, digest,
                rolesByAssetType, diagnostics, at, at, ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON);
    }

    public static SemanticProfileSnapshot staleFromPrior(SemanticProfileSnapshot prior, Instant attemptedAt,
            List<SemanticProfileDiagnostic> currentFailureDiagnostics) {
        if (prior == null || !prior.loaded) {
            return unavailable(attemptedAt, currentFailureDiagnostics);
        }
        Instant at = attemptedAt != null ? attemptedAt : Instant.now();
        return new SemanticProfileSnapshot(true, true, "stale", prior.profileId, prior.version, prior.digest,
                prior.rolesByAssetType, merge(currentFailureDiagnostics, prior.diagnostics),
                prior.lastSuccessfulRefresh, at, prior.effectiveSourcePath);
    }

    private static List<SemanticProfileDiagnostic> merge(List<SemanticProfileDiagnostic> current,
            List<SemanticProfileDiagnostic> prior) {
        if ((current == null || current.isEmpty()) && (prior == null || prior.isEmpty())) {
            return List.of();
        }
        java.util.ArrayList<SemanticProfileDiagnostic> merged = new java.util.ArrayList<>();
        if (current != null) {
            merged.addAll(current);
        }
        if (prior != null) {
            merged.addAll(prior);
        }
        return List.copyOf(merged);
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

    public String profileId() {
        return profileId;
    }

    public String version() {
        return version;
    }

    public String digest() {
        return digest;
    }

    public Map<String, List<SemanticPropertyRole>> rolesByAssetType() {
        return rolesByAssetType;
    }

    public List<SemanticPropertyRole> rolesForAssetType(String assetTypeKey) {
        if (assetTypeKey == null) {
            return List.of();
        }
        List<SemanticPropertyRole> roles = rolesByAssetType.get(assetTypeKey);
        return roles != null ? roles : List.of();
    }

    public List<SemanticProfileDiagnostic> diagnostics() {
        return diagnostics;
    }

    public Instant lastSuccessfulRefresh() {
        return lastSuccessfulRefresh;
    }

    public Instant lastAttemptedRefresh() {
        return lastAttemptedRefresh;
    }

    public String effectiveSourcePath() {
        return effectiveSourcePath;
    }

    public int assetTypeCount() {
        return rolesByAssetType.size();
    }

    public int roleCount() {
        int n = 0;
        for (List<SemanticPropertyRole> roles : rolesByAssetType.values()) {
            n += roles.size();
        }
        return n;
    }

    public String formatDiagnosticsMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Application semantic profile diagnostics\n\n");
        sb.append("- loaded: **").append(loaded).append("**\n");
        sb.append("- status: **").append(snapshotStatus).append("**\n");
        sb.append("- profileId: `").append(profileId.isEmpty() ? "(n/a)" : profileId).append("`\n");
        sb.append("- version: `").append(version.isEmpty() ? "(n/a)" : version).append("`\n");
        sb.append("- digest: `").append(digest.isEmpty() ? "(n/a)" : digest).append("`\n");
        sb.append("- assetTypeCount: ").append(assetTypeCount()).append("\n");
        sb.append("- roleCount: ").append(roleCount()).append("\n");
        sb.append("- stale: **").append(stale).append("**\n");
        sb.append("- sourcePath: `").append(effectiveSourcePath.isEmpty() ? "(n/a)" : effectiveSourcePath)
                .append("`\n");
        sb.append("- diagnostics: ").append(diagnostics.size()).append("\n");
        for (SemanticProfileDiagnostic d : diagnostics) {
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
        if (!(o instanceof SemanticProfileSnapshot)) {
            return false;
        }
        SemanticProfileSnapshot that = (SemanticProfileSnapshot) o;
        return loaded == that.loaded && stale == that.stale && Objects.equals(digest, that.digest)
                && Objects.equals(profileId, that.profileId) && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(loaded, stale, profileId, version, digest);
    }
}
