package com.thingworx.things.agent.cache;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;

import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.repository.FileRepositoryThing;

/**
 * One {@link FileArtifactCache} per distinct configured FileRepository Thing name for the JVM.
 * Package-private — callers use {@link ArtifactCacheCore}.
 */
final class FileArtifactCacheRegistry {

    private static final ConcurrentHashMap<String, FileArtifactCache> BY_REPO = new ConcurrentHashMap<>();

    private FileArtifactCacheRegistry() {}

    static FileArtifactCache getOrCreate(String repositoryThingName, ArtifactPayloadStore store) {
        String key = Objects.requireNonNull(repositoryThingName, "repositoryThingName").trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("repositoryThingName must be non-blank");
        }
        Objects.requireNonNull(store, "store");
        return BY_REPO.computeIfAbsent(key, name -> new FileArtifactCache(name, store));
    }

    /**
     * Resolve {@code artifactCacheFileRepository} to a shared cache instance. Empty/blank or
     * missing/non-FileRepository Thing → empty (caller fails closed via {@link ArtifactCacheCore}).
     */
    static Optional<FileArtifactCache> resolveConfigured(String configuredRepoName, Logger log,
            String agentThingName) {
        if (configuredRepoName == null || configuredRepoName.isBlank()) {
            return Optional.empty();
        }
        Optional<FileRepositoryThing> fr =
                FileRepositoryThingResolver.resolve(configuredRepoName.trim(), log, agentThingName);
        if (fr.isEmpty()) {
            return Optional.empty();
        }
        String name = configuredRepoName.trim();
        return Optional.of(getOrCreate(name, new FileRepositoryArtifactPayloadStore(fr.get())));
    }

    /** Test seam: clear JVM registry (simulates restart-empty index; does not delete payloads). */
    static void clearForTests() {
        BY_REPO.clear();
    }

    /** Shut down every registered cache and drop registry entries (JVM metadata only). */
    static void shutdownAll() {
        for (FileArtifactCache cache : BY_REPO.values()) {
            cache.shutdown();
        }
        BY_REPO.clear();
    }

    static FileArtifactCache getOrCreate(String repositoryThingName,
            Function<String, ArtifactPayloadStore> storeFactory) {
        String key = Objects.requireNonNull(repositoryThingName, "repositoryThingName").trim();
        return BY_REPO.computeIfAbsent(key, name -> new FileArtifactCache(name, storeFactory.apply(name)));
    }
}
