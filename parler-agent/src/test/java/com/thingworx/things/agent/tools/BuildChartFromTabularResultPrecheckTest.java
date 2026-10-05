package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** cache_id shape before phase-only CHART_FALLBACK (offline-safe). */
class BuildChartFromTabularResultPrecheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void distribution_cacheSourceMissingCacheId_returnsMissingCacheId() throws Exception {
        String json = "{\"source\":\"cache_id\",\"intent\":\"distribution\",\"xColumn\":\"x\"}";
        String out = BuildChartFromTabularResultPrecheck.intentModeCacheShapeAndPhaseFallbackOrNull(
                MAPPER.readTree(json), "distribution");
        JSONObject o = new JSONObject(out);
        assertEquals("error", o.getString("status"));
        assertEquals("MISSING_CACHE_ID", o.getString("code"));
    }

    @Test
    void distribution_lastInvoke_isNotPrecheckedAway_sinceC2b1() throws Exception {
        String json = "{\"source\":\"last_invoke\",\"intent\":\"distribution\"}";
        assertNull(BuildChartFromTabularResultPrecheck.intentModeCacheShapeAndPhaseFallbackOrNull(
                MAPPER.readTree(json), "distribution"));
    }

    @Test
    void timeTrend_returnsNull() throws Exception {
        String json = "{\"source\":\"last_invoke\",\"intent\":\"time_trend\",\"xColumn\":\"t\"}";
        assertNull(BuildChartFromTabularResultPrecheck.intentModeCacheShapeAndPhaseFallbackOrNull(
                MAPPER.readTree(json), "time_trend"));
    }
}
