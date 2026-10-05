package com.thingworx.things.agent.cache;

/**
 * Current-JVM presence probe for a public {@code cacheId}, for checkpoint evidence liveness
 * ({@code docs/core/advanced-compact.md} §5 invariant 11, §9.2 step 4).
 *
 * <p><b>Logical, not physical.</b> §3.6 defines a handle's usability as current-JVM index presence <em>plus</em>
 * principal/scope, TTL, invalidation, and completeness — "physical file retention != logical cache liveness". So
 * this asks for all of those, and for nothing else: it does not scan the repository, resurrect an index from
 * persisted files, open a reader, or decode a payload. A liveness answer must cost nothing and must not make a
 * stale handle usable by looking for it.
 *
 * <p>Never throws. An unconfigured repository, a cache that is not file-backed, or any fault answers {@code false},
 * which degrades the ref to {@code historical-recompute} — the safe direction.
 */
public final class ArtifactCacheLiveness {

    private ArtifactCacheLiveness() {}

    /**
     * @return {@code true} only when {@code cacheId} is indexed, unexpired, and complete under the access
     *         namespace of {@code conversationId}; a restarted or cleared index, an expired or incomplete record,
     *         a different conversation, and a different caller principal all answer {@code false}
     */
    public static boolean isIndexedForConversation(String conversationId, String cacheId) {
        if (cacheId == null || cacheId.isBlank()) {
            return false;
        }
        try {
            ArtifactRef ref = ArtifactCacheIds.resolveStoreLookupRef(conversationId, cacheId);
            if (ref == null) {
                return false;
            }
            ArtifactCache cache = TabularArtifactHub.resolveCache();
            if (!(cache instanceof FileArtifactCache)) {
                return false;
            }
            return ((FileArtifactCache) cache).isLogicallyLive(
                    TabularArtifactHub.accessContext(conversationId), ref);
        } catch (Exception e) {
            return false;
        }
    }
}
