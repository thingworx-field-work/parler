package com.thingworx.things.agent.cache;

/**
 * Minimum internal ArtifactCache contract (design §A3). No path-returning, startup loader, or
 * file-delete methods.
 */
public interface ArtifactCache {

    ArtifactWriter create(ArtifactCreateRequest request, ArtifactAccessContext context,
            ArtifactIoLimits limits) throws ArtifactCacheException;

    ArtifactRef publish(ArtifactWriter writer, ArtifactAccessContext context) throws ArtifactCacheException;

    ArtifactReader open(ArtifactRef ref, ArtifactAccessContext context, ArtifactIoLimits limits)
            throws ArtifactCacheException;

    void invalidate(ArtifactRef ref, ArtifactAccessContext context);

    void invalidateScope(ArtifactAccessContext context);
}
