package com.thingworx.things.agent.recovery;

import com.thingworx.things.agent.cache.ArtifactCacheIds;
import com.thingworx.things.agent.cache.TabularArtifactHub;

/**
 * Proves whether a collapsed cache lookup miss may carry EG1 {@code reason=NOT_FOUND}.
 *
 * <p>{@code NOT_FOUND} requires a well-formed public UUID cacheId that was a prior live handle
 * for the <em>current principal's</em> artifact namespace (descriptor registered under that
 * namespace) while the artifact open misses. Malformed, legacy/non-UUID, foreign (including
 * cross-principal same-conversation), never-seen, and pre-restart/disposed handles stay unproven
 * (outer {@code CACHE_MISS} only, no reason).
 */
public final class CacheMissClassifier {

    private CacheMissClassifier() {}

    /**
     * @param cacheId public {@code cacheId} that failed lookup (may be null/blank/malformed)
     * @return {@code true} only when live current-principal absence is proven
     */
    public static boolean isLiveNotFoundProven(String cacheId) {
        if (!ArtifactCacheIds.isWellFormedPublicCacheId(cacheId)) {
            return false;
        }
        return TabularArtifactHub.descriptorOwnedByCurrentPrincipal(cacheId.trim());
    }
}
