package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.HistoryOverlayChartBuilder;
import com.thingworx.things.agent.HistorySeriesComposerSupport;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * M4 overlay miss-alignment proof: overlay {@code seriesCaches[].cacheId}
 * hits/misses through the ordinary table path — no chart {@code resultKind} rewrite.
 */
class M4OverlaySeriesCachesMissAlignmentTest {

    M4OverlaySeriesCachesMissAlignmentTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        HistorySeriesComposerSupport.STORE_ATTEMPTS_FOR_TESTS.set(0);
    }

    @Test
    void overlayPublishedCacheId_hitsFetchAndTabulateWithOrdinaryPackaging() throws Exception {
        AgentToolContext.setConversationId("m4-overlay-miss-hit");
        String cacheId = publishOverlaySeriesCacheId("m4-overlay-miss-hit");

        AgentToolContext.setParlerStreamIds("req-hit", null);
        FetchCachedReplayGuard.beginTurn(FetchCachedReplayGuard.turnKey("m4-overlay-miss-hit", "req-hit"));
        JSONObject fetch = new JSONObject(InvokeServiceExecutor.executeFetchCachedResult(
                new ToolCall("f1", "fetch_cached_result",
                        "{\"cacheId\":\"" + cacheId + "\",\"offset\":0,\"limit\":10}")));
        assertEquals("success", fetch.getString("status"));
        assertEquals(cacheId, fetch.getString("cacheId"));
        assertEquals(2, fetch.getInt("returnedRows"));
        assertFalse(fetch.has("resultKind"), "fetch_cached_result must not invent resultKind");
        assertFalse(fetch.has("code") && "HISTORY_OVERLAY_NO_DATA".equals(fetch.optString("code")),
                "overlay-specific codes must not appear on fetch");

        JSONObject tabulate = new JSONObject(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result",
                        "{\"cacheId\":\"" + cacheId + "\",\"mode\":\"filter_rows\","
                                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"value\",\"value\":0},"
                                + "\"maxItems\":10,\"offset\":0}")));
        assertEquals("success", tabulate.getString("status"));
        String rk = tabulate.getString("resultKind");
        assertTrue(rk.startsWith("CACHED_"), "tabulate must use ordinary cached packaging, got " + rk);
        assertTrue(rk.contains("INLINE") || rk.contains("EMPTY") || rk.contains("LARGE")
                        || rk.contains("FILTER"),
                "expected EMPTY/INLINE/LARGE (or filter) packaging, got " + rk);
        assertFalse(rk.startsWith("HISTORY_OVERLAY_"), "must not invent overlay chart resultKind");
    }

    @Test
    void overlayPublishedCacheId_uniformMissAfterScopeInvalidate() throws Exception {
        AgentToolContext.setConversationId("m4-overlay-miss-evict");
        String cacheId = publishOverlaySeriesCacheId("m4-overlay-miss-evict");
        assertNotNull(InvokeServiceExecutor.lookupCachedInfotable(cacheId));

        TabularArtifactHub.invalidateCurrentScope();

        JSONObject fetchMiss = new JSONObject(InvokeServiceExecutor.executeFetchCachedResult(
                new ToolCall("f2", "fetch_cached_result",
                        "{\"cacheId\":\"" + cacheId + "\",\"offset\":0,\"limit\":10}")));
        assertEquals("error", fetchMiss.getString("status"));
        assertEquals("CACHE_MISS", fetchMiss.getString("code"));

        JSONObject tabMiss = new JSONObject(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t2", "tabulate_cached_result",
                        "{\"cacheId\":\"" + cacheId + "\",\"mode\":\"filter_count\","
                                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"value\",\"value\":0}}")));
        assertEquals("error", tabMiss.getString("status"));
        assertEquals("CACHE_MISS", tabMiss.getString("code"));
    }

    @Test
    void overlayPath_malformedAndForeignIds_uniformCacheMiss() throws Exception {
        AgentToolContext.setConversationId("m4-overlay-miss-guess");
        // Ensure conversation scope exists; no overlay publish required for guessed ids.
        publishOverlaySeriesCacheId("m4-overlay-miss-guess");

        JSONObject malformed = new JSONObject(InvokeServiceExecutor.executeFetchCachedResult(
                new ToolCall("f3", "fetch_cached_result",
                        "{\"cacheId\":\"not-a-uuid\",\"offset\":0,\"limit\":10}")));
        assertEquals("error", malformed.getString("status"));
        assertEquals("CACHE_MISS", malformed.getString("code"));

        String foreign = UUID.randomUUID().toString();
        JSONObject foreignMiss = new JSONObject(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t3", "tabulate_cached_result",
                        "{\"cacheId\":\"" + foreign + "\",\"mode\":\"filter_count\","
                                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"value\",\"value\":0}}")));
        assertEquals("error", foreignMiss.getString("status"));
        assertEquals("CACHE_MISS", foreignMiss.getString("code"));
    }

    @Test
    void overlayChartSuccess_keepsChartEmittedWithoutResultKindPackaging() throws Exception {
        AgentToolContext.setConversationId("m4-overlay-miss-shape");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T01:00:00Z");
        List<HistorySeriesComposerSupport.HistoryPoint> pts = List.of(
                new HistorySeriesComposerSupport.HistoryPoint(t0, 1.0),
                new HistorySeriesComposerSupport.HistoryPoint(t1, 2.0));
        List<HistoryOverlayChartBuilder.ResolvedSeriesInput> withHistory = List.of(
                new HistoryOverlayChartBuilder.ResolvedSeriesInput(
                        "A", "Thing.A", "currentDraw", t0, t1, "UTC", pts),
                new HistoryOverlayChartBuilder.ResolvedSeriesInput(
                        "B", "Thing.B", "currentDraw", t0, t1, "UTC", pts));

        JSONObject chart = new JSONObject(BuildHistoryOverlayChartExecutor.finishAfterFetchedSeries(
                withHistory, "absolute_time", "line", null, null, null));
        assertEquals("success", chart.getString("status"));
        assertEquals("CHART_EMITTED", chart.getString("code"));
        assertFalse(chart.has("resultKind"),
                "chart tool must not invent EMPTY/INLINE/LARGE resultKind packaging");
        assertTrue(chart.has("seriesCaches"));
    }

    /** Publish one overlay series cache via the post-fetch chart success path; return first cacheId. */
    private static String publishOverlaySeriesCacheId(String conversationId) throws Exception {
        AgentToolContext.setConversationId(conversationId);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T01:00:00Z");
        List<HistorySeriesComposerSupport.HistoryPoint> pts = List.of(
                new HistorySeriesComposerSupport.HistoryPoint(t0, 1.0),
                new HistorySeriesComposerSupport.HistoryPoint(t1, 2.0));
        List<HistoryOverlayChartBuilder.ResolvedSeriesInput> withHistory = List.of(
                new HistoryOverlayChartBuilder.ResolvedSeriesInput(
                        "A", "Thing.A", "currentDraw", t0, t1, "UTC", pts),
                new HistoryOverlayChartBuilder.ResolvedSeriesInput(
                        "B", "Thing.B", "currentDraw", t0, t1, "UTC", pts));
        JSONObject chart = new JSONObject(BuildHistoryOverlayChartExecutor.finishAfterFetchedSeries(
                withHistory, "absolute_time", "line", null, null, null));
        assertEquals("CHART_EMITTED", chart.getString("code"));
        String cacheId = chart.getJSONArray("seriesCaches").getJSONObject(0).getString("cacheId");
        assertNotNull(cacheId);
        assertFalse(cacheId.isBlank());
        return cacheId;
    }
}
