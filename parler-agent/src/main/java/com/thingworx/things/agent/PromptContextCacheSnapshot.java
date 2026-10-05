package com.thingworx.things.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.configrepo.ExternalSystemPromptSelection;
import com.thingworx.things.agent.semantics.SemanticProfileSnapshot;
import com.thingworx.things.agent.skillregistry.SkillRegistrySnapshot;
import com.thingworx.things.agent.taxonomy.ApplicationSemanticTaxonomySnapshot;

/**
 * Immutable in-memory snapshot for stable prompt assembly and resolver-side taxonomy / GenericThing metadata.
 */
public final class PromptContextCacheSnapshot {

    /** Optional: configuration repository scan outcome (null when {@code configurationRepository} is empty). */
    public static final class ConfigurationRepositoryState {

        private final ExtendedToolRegistrySnapshot extendedTools;
        private final boolean repositoryUnavailable;
        private final boolean invokeServicePolicyMissing;
        private final boolean invokeServicePolicyInvalid;
        private final int invokeServicePolicyRuleCount;
        /** Lowercase lifecycle key for {@code /taxonomies/type-taxonomy.md} (file-only; not the combined system block). */
        private final String typeTaxonomyMarkdownStatus;
        private final int typeTaxonomyMarkdownCharCount;

        public ConfigurationRepositoryState(
                ExtendedToolRegistrySnapshot extendedTools,
                boolean repositoryUnavailable,
                boolean invokeServicePolicyMissing,
                boolean invokeServicePolicyInvalid,
                int invokeServicePolicyRuleCount,
                String typeTaxonomyMarkdownStatus,
                int typeTaxonomyMarkdownCharCount) {
            this.extendedTools = extendedTools != null ? extendedTools : ExtendedToolRegistrySnapshot.missing();
            this.repositoryUnavailable = repositoryUnavailable;
            this.invokeServicePolicyMissing = invokeServicePolicyMissing;
            this.invokeServicePolicyInvalid = invokeServicePolicyInvalid;
            this.invokeServicePolicyRuleCount = invokeServicePolicyRuleCount;
            this.typeTaxonomyMarkdownStatus =
                    typeTaxonomyMarkdownStatus != null ? typeTaxonomyMarkdownStatus.trim().toLowerCase(Locale.ROOT)
                            : "unknown";
            this.typeTaxonomyMarkdownCharCount = Math.max(0, typeTaxonomyMarkdownCharCount);
        }

        public ExtendedToolRegistrySnapshot getExtendedTools() {
            return extendedTools;
        }

        public boolean isRepositoryUnavailable() {
            return repositoryUnavailable;
        }

        public boolean isInvokeServicePolicyMissing() {
            return invokeServicePolicyMissing;
        }

        public boolean isInvokeServicePolicyInvalid() {
            return invokeServicePolicyInvalid;
        }

        public int getInvokeServicePolicyRuleCount() {
            return invokeServicePolicyRuleCount;
        }

        public String getTypeTaxonomyMarkdownStatus() {
            return typeTaxonomyMarkdownStatus;
        }

        public int getTypeTaxonomyMarkdownCharCount() {
            return typeTaxonomyMarkdownCharCount;
        }

        public String formatDiagnosticsMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("## Configuration repository diagnostics\n\n");
            if (repositoryUnavailable) {
                sb.append("- repository: **unavailable** (Thing missing or not a FileRepository)\n");
            } else {
                sb.append("- repository: ok\n");
            }
            ExtendedToolRegistrySnapshot ext = extendedTools;
            if (ext.isFileInvalid()) {
                sb.append("- extended tools: **invalid** `extended_tools.json` — no extended tools registered\n");
            } else if (ext.isFileMissing()) {
                sb.append("- extended tools: file missing — none registered\n");
            } else {
                sb.append("- extended tools: ").append(ext.allByName().size()).append(" registered\n");
            }
            if (invokeServicePolicyMissing) {
                sb.append("- invoke_service policy: missing — all invoke_service calls require HITL\n");
            } else if (invokeServicePolicyInvalid) {
                sb.append("- invoke_service policy: **invalid** — all invoke_service calls require HITL\n");
            } else {
                sb.append("- invoke_service policy: loaded, ").append(invokeServicePolicyRuleCount).append(" rules\n");
            }
            switch (typeTaxonomyMarkdownStatus) {
                case "oversized":
                    sb.append("- type taxonomy markdown: **oversized** (> 32 KB) — ignored\n");
                    break;
                case "read_error":
                    sb.append("- type taxonomy markdown: **read error** — ignored\n");
                    break;
                case "unavailable":
                    sb.append("- type taxonomy markdown: **unavailable** (repository Thing not usable)\n");
                    break;
                case "missing":
                    sb.append("- type taxonomy markdown: file missing\n");
                    break;
                case "empty":
                    sb.append("- type taxonomy markdown: file empty\n");
                    break;
                case "loaded":
                    sb.append("- type taxonomy markdown: loaded (").append(typeTaxonomyMarkdownCharCount).append(" chars)\n");
                    break;
                default:
                    break;
            }
            return sb.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ConfigurationRepositoryState)) {
                return false;
            }
            ConfigurationRepositoryState that = (ConfigurationRepositoryState) o;
            return repositoryUnavailable == that.repositoryUnavailable
                    && invokeServicePolicyMissing == that.invokeServicePolicyMissing
                    && invokeServicePolicyInvalid == that.invokeServicePolicyInvalid
                    && invokeServicePolicyRuleCount == that.invokeServicePolicyRuleCount
                    && typeTaxonomyMarkdownCharCount == that.typeTaxonomyMarkdownCharCount
                    && Objects.equals(typeTaxonomyMarkdownStatus, that.typeTaxonomyMarkdownStatus)
                    && Objects.equals(extendedTools, that.extendedTools);
        }

        @Override
        public int hashCode() {
            return Objects.hash(extendedTools, repositoryUnavailable, invokeServicePolicyMissing,
                    invokeServicePolicyInvalid, invokeServicePolicyRuleCount, typeTaxonomyMarkdownStatus,
                    typeTaxonomyMarkdownCharCount);
        }
    }

    /**
     * Raw-byte fingerprint for one configuration-repository path at the time of the last successful prompt-context
     * refresh (see {@code docs/agent/collection-tool.md}).
     */
    public static final class RepositoryFileLoadIdentity {

        private final String path;
        private final String status;
        private final long byteSize;
        private final String loadedSha256;
        private final Instant loadedAtUtc;
        private final String loadedPath;

        public RepositoryFileLoadIdentity(String path, String status, long byteSize, String loadedSha256,
                Instant loadedAtUtc, String loadedPath) {
            this.path = path != null ? path : "";
            this.status = status != null ? status : "read_error";
            this.byteSize = Math.max(0, byteSize);
            this.loadedSha256 = loadedSha256;
            this.loadedAtUtc = loadedAtUtc != null ? loadedAtUtc : Instant.EPOCH;
            this.loadedPath = loadedPath != null ? loadedPath : "";
        }

        public String path() {
            return path;
        }

        public String status() {
            return status;
        }

        public long byteSize() {
            return byteSize;
        }

        public String loadedSha256() {
            return loadedSha256;
        }

        public Instant loadedAtUtc() {
            return loadedAtUtc;
        }

        public String loadedPath() {
            return loadedPath;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RepositoryFileLoadIdentity)) {
                return false;
            }
            RepositoryFileLoadIdentity that = (RepositoryFileLoadIdentity) o;
            return byteSize == that.byteSize && Objects.equals(path, that.path) && Objects.equals(status, that.status)
                    && Objects.equals(loadedSha256, that.loadedSha256)
                    && Objects.equals(loadedAtUtc, that.loadedAtUtc) && Objects.equals(loadedPath, that.loadedPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, status, byteSize, loadedSha256, loadedAtUtc, loadedPath);
        }
    }

    private final String taxonomySystemBlock;
    private final List<TaxonomyRow> taxonomyRows;
    private final List<String> genericThingTemplateNames;
    private final String genericThingTemplateNamesBlock;
    private final String alertPromptBlock;
    private final SkillRegistrySnapshot skillRegistry;
    private final ConfigurationRepositoryState configurationRepositoryState;
    private final ApplicationSemanticTaxonomySnapshot applicationSemanticTaxonomy;
    private final SemanticProfileSnapshot semanticProfile;
    private final Instant loadedAtUtc;
    private final List<RepositoryFileLoadIdentity> repositoryFileLoads;
    private final ExternalSystemPromptSelection externalSystemPrompt;

    public PromptContextCacheSnapshot(String taxonomySystemBlock, List<TaxonomyRow> taxonomyRows,
            List<String> genericThingTemplateNames, String genericThingTemplateNamesBlock,
            String alertPromptBlock, Instant loadedAtUtc) {
        this(taxonomySystemBlock, taxonomyRows, genericThingTemplateNames, genericThingTemplateNamesBlock, alertPromptBlock,
                null, null, null, loadedAtUtc, List.of(), null);
    }

    public PromptContextCacheSnapshot(String taxonomySystemBlock, List<TaxonomyRow> taxonomyRows,
            List<String> genericThingTemplateNames, String genericThingTemplateNamesBlock,
            String alertPromptBlock, SkillRegistrySnapshot skillRegistry, Instant loadedAtUtc) {
        this(taxonomySystemBlock, taxonomyRows, genericThingTemplateNames, genericThingTemplateNamesBlock, alertPromptBlock,
                skillRegistry, null, null, loadedAtUtc, List.of(), null);
    }

    public PromptContextCacheSnapshot(String taxonomySystemBlock, List<TaxonomyRow> taxonomyRows,
            List<String> genericThingTemplateNames, String genericThingTemplateNamesBlock,
            String alertPromptBlock, SkillRegistrySnapshot skillRegistry,
            ConfigurationRepositoryState configurationRepositoryState, Instant loadedAtUtc) {
        this(taxonomySystemBlock, taxonomyRows, genericThingTemplateNames, genericThingTemplateNamesBlock, alertPromptBlock,
                skillRegistry, configurationRepositoryState, null, loadedAtUtc, List.of(), null);
    }

    public PromptContextCacheSnapshot(String taxonomySystemBlock, List<TaxonomyRow> taxonomyRows,
            List<String> genericThingTemplateNames, String genericThingTemplateNamesBlock,
            String alertPromptBlock, SkillRegistrySnapshot skillRegistry,
            ConfigurationRepositoryState configurationRepositoryState,
            ApplicationSemanticTaxonomySnapshot applicationSemanticTaxonomy, Instant loadedAtUtc) {
        this(taxonomySystemBlock, taxonomyRows, genericThingTemplateNames, genericThingTemplateNamesBlock, alertPromptBlock,
                skillRegistry, configurationRepositoryState, applicationSemanticTaxonomy, loadedAtUtc, List.of(), null);
    }

    public PromptContextCacheSnapshot(String taxonomySystemBlock, List<TaxonomyRow> taxonomyRows,
            List<String> genericThingTemplateNames, String genericThingTemplateNamesBlock,
            String alertPromptBlock, SkillRegistrySnapshot skillRegistry,
            ConfigurationRepositoryState configurationRepositoryState,
            ApplicationSemanticTaxonomySnapshot applicationSemanticTaxonomy, Instant loadedAtUtc,
            List<RepositoryFileLoadIdentity> repositoryFileLoads) {
        this(taxonomySystemBlock, taxonomyRows, genericThingTemplateNames, genericThingTemplateNamesBlock,
                alertPromptBlock, skillRegistry, configurationRepositoryState, applicationSemanticTaxonomy, loadedAtUtc,
                repositoryFileLoads, null);
    }

    public PromptContextCacheSnapshot(String taxonomySystemBlock, List<TaxonomyRow> taxonomyRows,
            List<String> genericThingTemplateNames, String genericThingTemplateNamesBlock,
            String alertPromptBlock, SkillRegistrySnapshot skillRegistry,
            ConfigurationRepositoryState configurationRepositoryState,
            ApplicationSemanticTaxonomySnapshot applicationSemanticTaxonomy, Instant loadedAtUtc,
            List<RepositoryFileLoadIdentity> repositoryFileLoads, SemanticProfileSnapshot semanticProfile) {
        this(taxonomySystemBlock, taxonomyRows, genericThingTemplateNames, genericThingTemplateNamesBlock,
                alertPromptBlock, skillRegistry, configurationRepositoryState, applicationSemanticTaxonomy, loadedAtUtc,
                repositoryFileLoads, semanticProfile, null);
    }

    public PromptContextCacheSnapshot(String taxonomySystemBlock, List<TaxonomyRow> taxonomyRows,
            List<String> genericThingTemplateNames, String genericThingTemplateNamesBlock,
            String alertPromptBlock, SkillRegistrySnapshot skillRegistry,
            ConfigurationRepositoryState configurationRepositoryState,
            ApplicationSemanticTaxonomySnapshot applicationSemanticTaxonomy, Instant loadedAtUtc,
            List<RepositoryFileLoadIdentity> repositoryFileLoads, SemanticProfileSnapshot semanticProfile,
            ExternalSystemPromptSelection externalSystemPrompt) {
        this.taxonomySystemBlock = taxonomySystemBlock != null ? taxonomySystemBlock : "";
        this.taxonomyRows = taxonomyRows != null ? Collections.unmodifiableList(taxonomyRows) : Collections.emptyList();
        this.genericThingTemplateNames =
                genericThingTemplateNames != null ? Collections.unmodifiableList(genericThingTemplateNames)
                        : Collections.emptyList();
        this.genericThingTemplateNamesBlock =
                genericThingTemplateNamesBlock != null ? genericThingTemplateNamesBlock : "";
        this.alertPromptBlock = alertPromptBlock != null ? alertPromptBlock : "";
        Instant at = loadedAtUtc != null ? loadedAtUtc : Instant.now();
        this.skillRegistry = skillRegistry != null ? skillRegistry : SkillRegistrySnapshot.empty(at);
        this.configurationRepositoryState = configurationRepositoryState;
        this.applicationSemanticTaxonomy = applicationSemanticTaxonomy;
        this.semanticProfile = semanticProfile;
        this.loadedAtUtc = at;
        this.repositoryFileLoads =
                repositoryFileLoads != null && !repositoryFileLoads.isEmpty()
                        ? Collections.unmodifiableList(new java.util.ArrayList<>(repositoryFileLoads))
                        : List.of();
        this.externalSystemPrompt = externalSystemPrompt != null
                ? externalSystemPrompt
                : ExternalSystemPromptSelection.defaultSelection();
    }

    public PromptContextCacheSnapshot withApplicationSemanticTaxonomy(
            ApplicationSemanticTaxonomySnapshot applicationSemanticTaxonomy) {
        return new PromptContextCacheSnapshot(taxonomySystemBlock, taxonomyRows, genericThingTemplateNames,
                genericThingTemplateNamesBlock, alertPromptBlock, skillRegistry, configurationRepositoryState,
                applicationSemanticTaxonomy, loadedAtUtc, repositoryFileLoads, semanticProfile, externalSystemPrompt);
    }

    public PromptContextCacheSnapshot withSemanticProfile(SemanticProfileSnapshot semanticProfile) {
        return new PromptContextCacheSnapshot(taxonomySystemBlock, taxonomyRows, genericThingTemplateNames,
                genericThingTemplateNamesBlock, alertPromptBlock, skillRegistry, configurationRepositoryState,
                applicationSemanticTaxonomy, loadedAtUtc, repositoryFileLoads, semanticProfile, externalSystemPrompt);
    }

    public PromptContextCacheSnapshot withExternalSystemPrompt(ExternalSystemPromptSelection externalSystemPrompt) {
        return new PromptContextCacheSnapshot(taxonomySystemBlock, taxonomyRows, genericThingTemplateNames,
                genericThingTemplateNamesBlock, alertPromptBlock, skillRegistry, configurationRepositoryState,
                applicationSemanticTaxonomy, loadedAtUtc, repositoryFileLoads, semanticProfile, externalSystemPrompt);
    }

    public String getTaxonomySystemBlock() {
        return taxonomySystemBlock;
    }

    public List<TaxonomyRow> getTaxonomyRows() {
        return taxonomyRows;
    }

    public List<String> getGenericThingTemplateNames() {
        return genericThingTemplateNames;
    }

    public String getGenericThingTemplateNamesBlock() {
        return genericThingTemplateNamesBlock;
    }

    public String getAlertPromptBlock() {
        return alertPromptBlock;
    }

    public Instant getLoadedAtUtc() {
        return loadedAtUtc;
    }

    /** Unified skill metadata + refresh diagnostics (see {@code docs/agent/skill-management.md}). */
    public SkillRegistrySnapshot getSkillRegistry() {
        return skillRegistry;
    }

    /** @return null when no {@code configurationRepository} is configured */
    public ConfigurationRepositoryState getConfigurationRepositoryState() {
        return configurationRepositoryState;
    }

    /** Extended tools from the last prompt-context refresh; empty registry when not configured. */
    public ExtendedToolRegistrySnapshot getExtendedToolRegistry() {
        return configurationRepositoryState != null ? configurationRepositoryState.getExtendedTools()
                : ExtendedToolRegistrySnapshot.missing();
    }

    /** Repository-backed semantic taxonomy cache (v2 object or v3 array identity + asset-types map); may be {@code null} before first refresh. */
    public ApplicationSemanticTaxonomySnapshot getApplicationSemanticTaxonomy() {
        return applicationSemanticTaxonomy;
    }

    /**
     * Repository-backed application semantic profile ({@code /semantics/semantic-profile.json}); may be
     * {@code null} before first refresh.
     */
    public SemanticProfileSnapshot getSemanticProfile() {
        return semanticProfile;
    }

    /**
     * Per-path raw-byte fingerprints from the last successful prompt-context refresh; empty when not captured (e.g.
     * repository unavailable).
     */
    public List<RepositoryFileLoadIdentity> getRepositoryFileLoads() {
        return repositoryFileLoads;
    }

    public ExternalSystemPromptSelection getExternalSystemPrompt() {
        return externalSystemPrompt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PromptContextCacheSnapshot)) {
            return false;
        }
        PromptContextCacheSnapshot that = (PromptContextCacheSnapshot) o;
        return Objects.equals(taxonomySystemBlock, that.taxonomySystemBlock)
                && Objects.equals(taxonomyRows, that.taxonomyRows)
                && Objects.equals(genericThingTemplateNames, that.genericThingTemplateNames)
                && Objects.equals(genericThingTemplateNamesBlock, that.genericThingTemplateNamesBlock)
                && Objects.equals(alertPromptBlock, that.alertPromptBlock)
                && Objects.equals(skillRegistry, that.skillRegistry)
                && Objects.equals(configurationRepositoryState, that.configurationRepositoryState)
                && Objects.equals(applicationSemanticTaxonomy, that.applicationSemanticTaxonomy)
                && Objects.equals(semanticProfile, that.semanticProfile)
                && Objects.equals(loadedAtUtc, that.loadedAtUtc)
                && Objects.equals(repositoryFileLoads, that.repositoryFileLoads)
                && Objects.equals(externalSystemPrompt, that.externalSystemPrompt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taxonomySystemBlock, taxonomyRows, genericThingTemplateNames,
                genericThingTemplateNamesBlock, alertPromptBlock, skillRegistry, configurationRepositoryState,
                applicationSemanticTaxonomy, semanticProfile, loadedAtUtc, repositoryFileLoads, externalSystemPrompt);
    }
}
