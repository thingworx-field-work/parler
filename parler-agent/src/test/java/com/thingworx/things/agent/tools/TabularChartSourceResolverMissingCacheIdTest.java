package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class TabularChartSourceResolverMissingCacheIdTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void cacheSourceWithoutCacheId_returnsErrorJson() throws Exception {
        String json = "{\"source\":\"cache_id\",\"intent\":\"distribution\",\"xColumn\":\"a\"}";
        String err = TabularChartSourceResolver.missingCacheIdForCacheSourceOrNull(MAPPER.readTree(json));
        assertTrue(err.contains("MISSING_CACHE_ID"));
        assertTrue(err.contains("cacheId is required"));
    }

    @Test
    void cacheSourceWithCacheId_returnsNull() throws Exception {
        String json = "{\"source\":\"cache_id\",\"cacheId\":\"cid-1\",\"intent\":\"distribution\",\"xColumn\":\"a\"}";
        assertNull(TabularChartSourceResolver.missingCacheIdForCacheSourceOrNull(MAPPER.readTree(json)));
    }

    @Test
    void lastInvoke_returnsNullWithoutInspectingCacheId() throws Exception {
        String json = "{\"source\":\"last_invoke\",\"intent\":\"distribution\",\"xColumn\":\"a\"}";
        assertNull(TabularChartSourceResolver.missingCacheIdForCacheSourceOrNull(MAPPER.readTree(json)));
    }
}
