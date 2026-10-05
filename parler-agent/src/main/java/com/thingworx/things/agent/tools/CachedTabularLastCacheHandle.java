package com.thingworx.things.agent.tools;

/**
 * P2 TOKEN literal for last-qualifying tabular {@code cacheId}. Mutation authority is
 * {@link TabularCacheHandleMirror}; this class retains the sentinel string and {@link #isToken}
 * predicate only (U2 BP9 / BP1).
 *
 * @see docs/agent/p2_last_tabular_cache.md
 */
public final class CachedTabularLastCacheHandle {

    /**
     * Literal JSON string for {@code cacheId} meaning "resolve from last qualifying tabular tool in this conversation".
     */
    public static final String TOKEN = "__PARLER_LAST_QUALIFYING_TABULAR_CACHE__";

    private CachedTabularLastCacheHandle() {}

    /** @return true if {@code cacheId} is non-null and equals {@link #TOKEN} after trim */
    public static boolean isToken(String cacheId) {
        return cacheId != null && TOKEN.equals(cacheId.trim());
    }
}
