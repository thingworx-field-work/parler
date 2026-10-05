package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ArtifactCacheCoreReadinessTest {

    @Test
    void missingAndBlankConfigurationDoNotResolveARepository() {
        AtomicInteger resolverCalls = new AtomicInteger();

        for (String configured : new String[] {null, "", "   \t "}) {
            ArtifactCacheCore.Readiness readiness = ArtifactCacheCore.readinessConfigured(
                    configured, ignored -> {
                        resolverCalls.incrementAndGet();
                        return true;
                    });

            assertEquals(ArtifactCacheCore.Readiness.Status.NOT_CONFIGURED, readiness.status());
            assertFalse(readiness.isReady());
            assertNull(readiness.repositoryThingName());
        }

        assertEquals(0, resolverCalls.get());
    }

    @Test
    void unavailableRepositoryRetainsOnlyTheNormalizedConfiguredName() {
        AtomicReference<String> resolvedName = new AtomicReference<>();

        ArtifactCacheCore.Readiness readiness = ArtifactCacheCore.readinessConfigured(
                "  MissingArtifactRepository  ", name -> {
                    resolvedName.set(name);
                    return false;
                });

        assertEquals("MissingArtifactRepository", resolvedName.get());
        assertEquals(ArtifactCacheCore.Readiness.Status.REPOSITORY_UNAVAILABLE, readiness.status());
        assertEquals("MissingArtifactRepository", readiness.repositoryThingName());
        assertFalse(readiness.isReady());
    }

    @Test
    void resolverFailureIsUnavailableWithoutLeakingItsMessage() {
        ArtifactCacheCore.Readiness readiness = ArtifactCacheCore.readinessConfigured(
                "ArtifactRepository", name -> {
                    throw new IllegalStateException("physical/path/credential detail");
                });

        assertEquals(ArtifactCacheCore.Readiness.Status.REPOSITORY_UNAVAILABLE, readiness.status());
        assertEquals("ArtifactRepository", readiness.repositoryThingName());
    }

    @Test
    void validRepositoryIsReadyWithoutConstructingACache() {
        AtomicInteger resolverCalls = new AtomicInteger();

        ArtifactCacheCore.Readiness readiness = ArtifactCacheCore.readinessConfigured(
                " ArtifactRepository ", name -> {
                    resolverCalls.incrementAndGet();
                    return "ArtifactRepository".equals(name);
                });

        assertEquals(1, resolverCalls.get());
        assertEquals(ArtifactCacheCore.Readiness.Status.READY, readiness.status());
        assertEquals("ArtifactRepository", readiness.repositoryThingName());
        assertTrue(readiness.isReady());
    }

    @Test
    void correctedConfigurationIsReevaluatedAndFailureIsNotCached() {
        AtomicReference<String> availableName = new AtomicReference<>();
        AtomicInteger resolverCalls = new AtomicInteger();

        ArtifactCacheCore.Readiness first = ArtifactCacheCore.readinessConfigured(
                "BrokenRepository", name -> {
                    resolverCalls.incrementAndGet();
                    return name.equals(availableName.get());
                });
        availableName.set("FixedRepository");
        ArtifactCacheCore.Readiness restarted = ArtifactCacheCore.readinessConfigured(
                "FixedRepository", name -> {
                    resolverCalls.incrementAndGet();
                    return name.equals(availableName.get());
                });

        assertEquals(ArtifactCacheCore.Readiness.Status.REPOSITORY_UNAVAILABLE, first.status());
        assertEquals(ArtifactCacheCore.Readiness.Status.READY, restarted.status());
        assertEquals("FixedRepository", restarted.repositoryThingName());
        assertEquals(2, resolverCalls.get());
    }
}
