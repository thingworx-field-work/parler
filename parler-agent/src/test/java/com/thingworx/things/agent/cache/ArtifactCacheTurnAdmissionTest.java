package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArtifactCacheTurnAdmissionTest {

    @Test
    void readinessStatusesMapToOneSharedPublicPolicy() {
        ArtifactCacheTurnAdmission.Decision ready = ArtifactCacheTurnAdmission.fromReadiness(
                ArtifactCacheCore.Readiness.ready("ArtifactRepository"));
        ArtifactCacheTurnAdmission.Decision blank = ArtifactCacheTurnAdmission.fromReadiness(
                ArtifactCacheCore.Readiness.notConfigured());
        ArtifactCacheTurnAdmission.Decision unavailable = ArtifactCacheTurnAdmission.fromReadiness(
                ArtifactCacheCore.Readiness.repositoryUnavailable("BrokenRepository"));

        assertTrue(ready.isReady());
        assertEquals("ArtifactRepository", ready.repositoryThingName());
        assertNull(ready.errorCode());

        assertFalse(blank.isReady());
        assertEquals("ARTIFACT_CACHE_NOT_CONFIGURED", blank.errorCode());
        assertTrue(blank.message().contains("AgentSettings.artifactCacheFileRepository"));
        assertEquals("[ARTIFACT_CACHE_NOT_CONFIGURED] " + blank.message(), blank.serviceExceptionMessage());

        assertFalse(unavailable.isReady());
        assertEquals("ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE", unavailable.errorCode());
        assertEquals("BrokenRepository", unavailable.repositoryThingName());
        assertTrue(unavailable.message().contains("AgentSettings.artifactCacheFileRepository"));
    }

    @Test
    void publicMessagesAreFixedAndContainNoBackendDetail() {
        String secret = "physical/path/namespace/credential/payload";
        ArtifactCacheTurnAdmission.Decision unavailable = ArtifactCacheTurnAdmission.fromReadiness(
                ArtifactCacheCore.Readiness.repositoryUnavailable("ArtifactRepository"));

        assertFalse(unavailable.message().contains(secret));
        assertFalse(unavailable.serviceExceptionMessage().contains("ArtifactRepository"));
    }
}
