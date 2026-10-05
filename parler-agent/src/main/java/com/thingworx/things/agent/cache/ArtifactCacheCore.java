package com.thingworx.things.agent.cache;

import java.util.Objects;
import java.util.function.Predicate;

import org.slf4j.Logger;

import com.thingworx.things.agent.AgentBaseThing;
import com.thingworx.things.agent.FileRepositoryThingResolver;

/**
 * Narrow Core-owned accessor for the configured artifact cache. Blank, missing, or
 * non-FileRepository configuration fails closed with {@link ArtifactCacheFaultCode#REPOSITORY_UNAVAILABLE}.
 */
public final class ArtifactCacheCore {

    private ArtifactCacheCore() {}

    /** Side-effect-free production readiness classification for the configured cache repository. */
    public static final class Readiness {

        public enum Status {
            READY,
            NOT_CONFIGURED,
            REPOSITORY_UNAVAILABLE
        }

        private final Status status;
        private final String repositoryThingName;

        private Readiness(Status status, String repositoryThingName) {
            this.status = Objects.requireNonNull(status, "status");
            this.repositoryThingName = repositoryThingName;
        }

        public Status status() {
            return status;
        }

        /** Normalized configured Thing name; null only when no non-blank name was supplied. */
        public String repositoryThingName() {
            return repositoryThingName;
        }

        public boolean isReady() {
            return status == Status.READY;
        }

        static Readiness ready(String repositoryThingName) {
            return new Readiness(Status.READY, repositoryThingName);
        }

        static Readiness notConfigured() {
            return new Readiness(Status.NOT_CONFIGURED, null);
        }

        static Readiness repositoryUnavailable(String repositoryThingName) {
            return new Readiness(Status.REPOSITORY_UNAVAILABLE, repositoryThingName);
        }
    }

    /**
     * Resolve only the configured Thing name/type needed for turn admission. This method does not
     * create an {@link ArtifactCache}, open or write a repository file, or cache a failed result.
     */
    public static Readiness readiness(AgentBaseThing agent, Logger log) {
        if (agent == null) {
            return Readiness.repositoryUnavailable(null);
        }
        return readinessConfigured(agent.getArtifactCacheFileRepositoryName(),
                FileRepositoryThingResolver::resolvesQuietly);
    }

    /** Package-private resolver seam for focused readiness tests without a ThingWorx boot. */
    static Readiness readinessConfigured(String configuredRepoName, Predicate<String> repositoryResolver) {
        Objects.requireNonNull(repositoryResolver, "repositoryResolver");
        String normalized = configuredRepoName == null ? "" : configuredRepoName.trim();
        if (normalized.isEmpty()) {
            return Readiness.notConfigured();
        }
        try {
            return repositoryResolver.test(normalized)
                    ? Readiness.ready(normalized)
                    : Readiness.repositoryUnavailable(normalized);
        } catch (RuntimeException unresolved) {
            return Readiness.repositoryUnavailable(normalized);
        }
    }

    public static ArtifactCache requireCache(AgentBaseThing agent, Logger log) throws ArtifactCacheException {
        if (agent == null) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                    "Artifact cache requires an AgentThing");
        }
        return requireConfigured(agent.getArtifactCacheFileRepositoryName(), log, agent.getName());
    }

    /** Package-private production-shaped fault path (also used by focused Core fixtures). */
    static ArtifactCache requireConfigured(String configuredRepoName, Logger log, String agentThingName)
            throws ArtifactCacheException {
        return FileArtifactCacheRegistry.resolveConfigured(configuredRepoName, log, agentThingName)
                .map(c -> (ArtifactCache) c)
                .orElseThrow(() -> new ArtifactCacheException(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                        "artifactCacheFileRepository is empty, missing, or not a FileRepository Thing"));
    }
}
