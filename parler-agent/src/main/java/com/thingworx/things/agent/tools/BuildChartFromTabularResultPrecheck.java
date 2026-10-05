package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Small intent-mode pre-checks for {@link BuildChartFromTabularResultExecutor} that must run before
 * heavy chart dependencies (keeps unit tests classpath-light).
 */
public final class BuildChartFromTabularResultPrecheck {

    private BuildChartFromTabularResultPrecheck() {}

    /**
     * After the intent token is known valid: enforce {@code cacheId} when {@code source} is {@code cache_id},
     * then apply phase-only {@code CHART_FALLBACK} for unsupported intents.
     *
     * @return tool-result JSON body to return immediately, or {@code null} to continue normal execution
     */
    public static String intentModeCacheShapeAndPhaseFallbackOrNull(JsonNode root, String normalizedKnownIntent) {
        String cacheShapeErr = TabularChartSourceResolver.missingCacheIdForCacheSourceOrNull(root);
        if (cacheShapeErr != null) {
            return cacheShapeErr;
        }
        return ChartTabularIntentPhaseBoundary.tryEarlyJsonFallback(normalizedKnownIntent);
    }
}
