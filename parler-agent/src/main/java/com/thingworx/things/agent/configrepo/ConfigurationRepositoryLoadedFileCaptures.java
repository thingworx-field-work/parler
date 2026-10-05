package com.thingworx.things.agent.configrepo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.playbook.PlaybookCatalogEntry;
import com.thingworx.things.agent.playbook.PlaybookRegistrySnapshot;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.agent.skillregistry.SkillRegistryDescriptor;
import com.thingworx.things.agent.skillregistry.SkillRegistrySnapshot;
import com.thingworx.things.agent.skillregistry.SkillSourceKind;

/**
 * Captures per-path raw-byte fingerprints during prompt-context refresh for later {@code GetAgentRuntimeSnapshot}
 * comparison (see {@code docs/agent/collection-tool.md}).
 */
public final class ConfigurationRepositoryLoadedFileCaptures {

    private ConfigurationRepositoryLoadedFileCaptures() {}

    public static List<PromptContextCacheSnapshot.RepositoryFileLoadIdentity> capture(
            RepositoryReader reader,
            SkillRegistrySnapshot skills,
            PlaybookRegistrySnapshot playbooks,
            Instant snapshotInstant,
            Logger log) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.add(ConfigurationRepositoryPaths.EXTENDED_TOOLS);
        paths.add(ConfigurationRepositoryPaths.INVOKE_SERVICE_POLICY);
        paths.add(ConfigurationRepositoryPaths.TAXONOMY_TYPE_MARKDOWN);
        paths.add(ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        paths.add(ConfigurationRepositoryPaths.ASSET_TYPES_JSON);
        paths.add(ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON);
        if (skills != null) {
            for (SkillRegistryDescriptor d : skills.descriptorsByShortId().values()) {
                if (d.sourceKind() == SkillSourceKind.REPOSITORY) {
                    String p = d.repositorySkillPath();
                    if (p != null && !p.isBlank()) {
                        paths.add(p.startsWith("/") ? p : "/" + p);
                    }
                }
            }
        }
        if (playbooks != null && playbooks.isLoaded()) {
            for (PlaybookCatalogEntry e : playbooks.catalogById().values()) {
                String p = e.playbookPath();
                if (p != null && !p.isBlank()) {
                    paths.add(p.startsWith("/") ? p : "/" + p);
                }
            }
        }
        List<PromptContextCacheSnapshot.RepositoryFileLoadIdentity> out = new ArrayList<>();
        for (String path : paths) {
            try {
                RepositoryFileFingerprint.Result fp = RepositoryFileFingerprint.capture(reader, path, snapshotInstant);
                out.add(toIdentity(fp));
            } catch (Exception e) {
                if (log != null) {
                    log.warn("[ConfigurationRepositoryLoadedFileCaptures] path {}: {}", path, e.getMessage());
                }
                out.add(new PromptContextCacheSnapshot.RepositoryFileLoadIdentity(path, "read_error", 0, null,
                        snapshotInstant, path));
            }
        }
        return List.copyOf(out);
    }

    private static PromptContextCacheSnapshot.RepositoryFileLoadIdentity toIdentity(RepositoryFileFingerprint.Result fp) {
        return new PromptContextCacheSnapshot.RepositoryFileLoadIdentity(fp.path(), fp.statusWireLower(), fp.byteSize(),
                fp.sha256Hex(), fp.capturedAtUtc(), fp.path());
    }

    /** Union of tracked paths from a snapshot's load identities plus standard config paths. */
    public static Set<String> unionPaths(List<PromptContextCacheSnapshot.RepositoryFileLoadIdentity> loaded,
            SkillRegistrySnapshot skills,
            PlaybookRegistrySnapshot playbooks) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.add(ConfigurationRepositoryPaths.EXTENDED_TOOLS);
        paths.add(ConfigurationRepositoryPaths.INVOKE_SERVICE_POLICY);
        paths.add(ConfigurationRepositoryPaths.TAXONOMY_TYPE_MARKDOWN);
        paths.add(ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        paths.add(ConfigurationRepositoryPaths.ASSET_TYPES_JSON);
        paths.add(ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON);
        if (loaded != null) {
            for (PromptContextCacheSnapshot.RepositoryFileLoadIdentity id : loaded) {
                if (id.path() != null && !id.path().isBlank()) {
                    paths.add(id.path());
                }
            }
        }
        if (skills != null) {
            for (SkillRegistryDescriptor d : skills.descriptorsByShortId().values()) {
                if (d.sourceKind() == SkillSourceKind.REPOSITORY) {
                    String p = d.repositorySkillPath();
                    if (p != null && !p.isBlank()) {
                        paths.add(p.startsWith("/") ? p : "/" + p);
                    }
                }
            }
        }
        if (playbooks != null && playbooks.isLoaded()) {
            for (PlaybookCatalogEntry e : playbooks.catalogById().values()) {
                String p = e.playbookPath();
                if (p != null && !p.isBlank()) {
                    paths.add(p.startsWith("/") ? p : "/" + p);
                }
            }
        }
        return paths;
    }
}
