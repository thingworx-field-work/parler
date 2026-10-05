package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.HistoryOverlayChartBuilder;
import com.thingworx.things.agent.HistorySeriesComposerSupport;

/**
 * M4 S4+cache: no live cache publish on post-fetch chart validation failure.
 */
class M4S4SeriesCachesOrderingTest {

    M4S4SeriesCachesOrderingTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        HistorySeriesComposerSupport.STORE_ATTEMPTS_FOR_TESTS.set(0);
    }

    @Test
    void invalidXAxisModeAfterFetch_publishesNoCacheAndNoSeriesCaches() throws Exception {
        AgentToolContext.setConversationId("s4-order-fail");
        HistorySeriesComposerSupport.STORE_ATTEMPTS_FOR_TESTS.set(0);

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

        String json;
        try {
            json = BuildHistoryOverlayChartExecutor.finishAfterFetchedSeries(
                    withHistory, "not_a_valid_x_axis_mode", "line", null, null, null);
        } catch (HistoryOverlayChartBuilder.BuildException e) {
            json = errorJson(e.code, e.getMessage());
        }

        JSONObject o = new JSONObject(json);
        assertEquals("error", o.getString("status"));
        assertEquals("HISTORY_OVERLAY_INVALID_X_AXIS_MODE", o.getString("code"));
        assertFalse(o.has("seriesCaches"), "fault path must not emit seriesCaches");
        assertEquals(0, HistorySeriesComposerSupport.STORE_ATTEMPTS_FOR_TESTS.get(),
                "fault path must not attempt live series cache store");
    }

    @Test
    void successfulFinishAfterFetch_emitsSeriesCaches() throws Exception {
        AgentToolContext.setConversationId("s4-order-ok");
        HistorySeriesComposerSupport.STORE_ATTEMPTS_FOR_TESTS.set(0);

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

        String json = BuildHistoryOverlayChartExecutor.finishAfterFetchedSeries(
                withHistory, "absolute_time", "line", null, null, null);
        JSONObject o = new JSONObject(json);
        assertEquals("success", o.getString("status"));
        assertEquals("CHART_EMITTED", o.getString("code"));
        assertTrue(o.has("seriesCaches"));
        assertEquals(2, o.getJSONArray("seriesCaches").length());
        assertEquals(2, HistorySeriesComposerSupport.STORE_ATTEMPTS_FOR_TESTS.get());

        String cacheId = o.getJSONArray("seriesCaches").getJSONObject(0).getString("cacheId");
        assertEquals(2, InvokeServiceExecutor.lookupCachedInfotable(cacheId).getRowCount().intValue());

        // CM-1: the five existing fields stay; columns, roles and the resolved request window are added.
        JSONObject entry = o.getJSONArray("seriesCaches").getJSONObject(0);
        assertEquals("A", entry.getString("label"));
        assertEquals("Thing.A", entry.getString("thingName"));
        assertEquals("currentDraw", entry.getString("propertyName"));
        assertEquals(2, entry.getInt("totalRows"));
        assertEquals(2, entry.getJSONArray("columns").length());
        assertEquals("timestamp", entry.getJSONArray("columns").getJSONObject(0).getString("name"));
        assertEquals("STRING", entry.getJSONArray("columns").getJSONObject(0).getString("baseType"));
        assertEquals("value", entry.getJSONArray("columns").getJSONObject(1).getString("name"));
        assertEquals("NUMBER", entry.getJSONArray("columns").getJSONObject(1).getString("baseType"));
        assertEquals("timestamp", entry.getString("timeColumn"));
        assertEquals("value", entry.getString("valueColumn"));
        assertEquals("2026-01-01T00:00:00Z", entry.getString("windowStart"));
        assertEquals("2026-01-01T01:00:00Z", entry.getString("windowEnd"));
        assertEquals("UTC", entry.getString("resolvedTimeZone"));
        assertFalse(entry.has("completeness"), "request window is not coverage; completeness stays unpublished");

        // CM-4: each series' runtime descriptor carries its own subject identity from ResolvedSeriesInput.
        for (int i = 0; i < 2; i++) {
            JSONObject e = o.getJSONArray("seriesCaches").getJSONObject(i);
            com.thingworx.things.agent.source.SourceDescriptor d =
                    com.thingworx.things.agent.cache.TabularArtifactHub.lookupDescriptor(e.getString("cacheId"));
            assertEquals(e.getString("thingName"), d.subjectThingName());
            assertEquals("currentDraw", d.subjectPropertyName());
        }
    }

    @Test
    void successfulFinishAfterFetch_omitsBlankTimeZone() throws Exception {
        AgentToolContext.setConversationId("s4-order-ok-tz");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T01:00:00Z");
        List<HistorySeriesComposerSupport.HistoryPoint> pts = List.of(
                new HistorySeriesComposerSupport.HistoryPoint(t0, 1.0),
                new HistorySeriesComposerSupport.HistoryPoint(t1, 2.0));
        List<HistoryOverlayChartBuilder.ResolvedSeriesInput> withHistory = List.of(
                new HistoryOverlayChartBuilder.ResolvedSeriesInput("A", "Thing.A", "currentDraw", t0, t1, "", pts),
                new HistoryOverlayChartBuilder.ResolvedSeriesInput("B", "Thing.B", "currentDraw", t0, t1, null, pts));
        JSONObject o = new JSONObject(BuildHistoryOverlayChartExecutor.finishAfterFetchedSeries(
                withHistory, "absolute_time", "line", null, null, null));
        JSONObject entry = o.getJSONArray("seriesCaches").getJSONObject(0);
        assertFalse(entry.has("resolvedTimeZone"));
        assertTrue(entry.has("windowStart"));
    }

    private static String errorJson(String code, String message) {
        JSONObject o = new JSONObject();
        o.put("status", "error");
        o.put("code", code);
        o.put("message", message != null ? message : "");
        return o.toString();
    }
}
