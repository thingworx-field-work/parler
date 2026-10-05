package com.thingworx.things.agent.cache;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Public {@code cacheId} ↔ {@link ArtifactRef} bridge (U2 BP1). Live LARGE handles are the
 * lowercase UUID text of {@link ArtifactRef#artifactId()}; no path/principal/kind encoding.
 *
 * <p>Historical compact-fetch restore may still carry non-UUID {@code cacheId} strings until M4.
 * Those map to a deterministic internal UUID so restore/lookup can use ArtifactCache without a
 * second Infotable map. New stores always mint well-formed public UUID ids.
 */
public final class ArtifactCacheIds {

    private ArtifactCacheIds() {}

    public static String toPublicCacheId(ArtifactRef ref) {
        if (ref == null) {
            return null;
        }
        return ref.artifactId();
    }

    /**
     * @return well-formed ref, or {@code null} when {@code cacheId} is null/blank/malformed
     *         (uniform miss path — do not distinguish guessed vs absent here)
     */
    public static ArtifactRef tryParsePublicCacheId(String cacheId) {
        if (cacheId == null) {
            return null;
        }
        String t = cacheId.trim();
        if (!ArtifactRef.isWellFormedId(t)) {
            return null;
        }
        return ArtifactRef.ofValidated(t);
    }

    public static boolean isWellFormedPublicCacheId(String cacheId) {
        return cacheId != null && ArtifactRef.isWellFormedId(cacheId.trim());
    }

    /**
     * Resolve store/lookup artifact id for a public {@code cacheId}. Well-formed UUIDs pass through;
     * historical non-UUID ids (compact rehydrate bridge) map to a conversation-scoped deterministic UUID.
     */
    public static ArtifactRef resolveStoreLookupRef(String conversationId, String cacheId) {
        if (cacheId == null) {
            return null;
        }
        String t = cacheId.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (ArtifactRef.isWellFormedId(t)) {
            return ArtifactRef.ofValidated(t);
        }
        String conv = conversationId == null ? "" : conversationId.trim();
        String synthetic = UUID.nameUUIDFromBytes(
                ("parler.u2.legacy-cache:" + conv + ":" + t).getBytes(StandardCharsets.UTF_8))
                .toString()
                .toLowerCase();
        return ArtifactRef.ofValidated(synthetic);
    }
}
