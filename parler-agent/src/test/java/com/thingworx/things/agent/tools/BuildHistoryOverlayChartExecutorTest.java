package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.ToolCall;

class BuildHistoryOverlayChartExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void tooFewSeries() throws Exception {
        String json = BuildHistoryOverlayChartExecutor.execute(new ToolCall("h1",
                "build_history_overlay_chart",
                "{\"propertyName\":\"currentDraw\",\"series\":[{\"label\":\"A\",\"thingName\":\"T1\"}]}"));
        JSONObject o = new JSONObject(json);
        assertEquals("error", o.getString("status"));
        assertEquals("HISTORY_OVERLAY_TOO_FEW_SERIES", o.getString("code"));
        assertNoRetiredChartToolCode(o);
    }

    @Test
    void missingPropertyName() throws Exception {
        String json = BuildHistoryOverlayChartExecutor.execute(new ToolCall("h2",
                "build_history_overlay_chart",
                "{\"series\":[{\"label\":\"A\",\"thingName\":\"T1\"},{\"label\":\"B\",\"thingName\":\"T2\"}]}"));
        JSONObject o = new JSONObject(json);
        assertEquals("error", o.getString("status"));
        assertEquals("HISTORY_OVERLAY_MISSING_PROPERTY", o.getString("code"));
        assertNoRetiredChartToolCode(o);
    }

    @Test
    void invalidAnchorTimeUsesDistinctAnchorCode_s4() throws Exception {
        String json = BuildHistoryOverlayChartExecutor.execute(new ToolCall("h3",
                "build_history_overlay_chart",
                "{\"propertyName\":\"currentDraw\",\"anchorTime\":\"not-an-instant\","
                        + "\"series\":[{\"label\":\"A\",\"thingName\":\"T1\",\"startTime\":\"2026-01-01T00:00:00Z\","
                        + "\"endTime\":\"2026-01-01T01:00:00Z\"},{\"label\":\"B\",\"thingName\":\"T2\","
                        + "\"startTime\":\"2026-01-01T00:00:00Z\",\"endTime\":\"2026-01-01T01:00:00Z\"}]}"));
        JSONObject o = new JSONObject(json);
        assertEquals("error", o.getString("status"));
        assertEquals(HistoryOverlayToolErrors.INVALID_ANCHOR_TIME, o.getString("code"));
        assertNoRetiredChartToolCode(o);
    }

    @Test
    void nonObjectSeriesEntryUsesDistinctSeriesCode_s4() throws Exception {
        String json = BuildHistoryOverlayChartExecutor.execute(new ToolCall("h3b",
                "build_history_overlay_chart",
                "{\"propertyName\":\"currentDraw\",\"series\":[\"not-an-object\","
                        + "{\"label\":\"B\",\"thingName\":\"T2\",\"startTime\":\"2026-01-01T00:00:00Z\","
                        + "\"endTime\":\"2026-01-01T01:00:00Z\"}]}"));
        JSONObject o = new JSONObject(json);
        assertEquals("error", o.getString("status"));
        assertEquals(HistoryOverlayToolErrors.INVALID_SERIES_ENTRY, o.getString("code"));
        assertNoRetiredChartToolCode(o);
    }

    @Test
    void blankSeriesLabelUsesDistinctLabelCode_s4() throws Exception {
        String json = BuildHistoryOverlayChartExecutor.execute(new ToolCall("h3c",
                "build_history_overlay_chart",
                "{\"propertyName\":\"currentDraw\",\"series\":[{\"label\":\"  \",\"thingName\":\"T1\","
                        + "\"startTime\":\"2026-01-01T00:00:00Z\",\"endTime\":\"2026-01-01T01:00:00Z\"},"
                        + "{\"label\":\"B\",\"thingName\":\"T2\",\"startTime\":\"2026-01-01T00:00:00Z\","
                        + "\"endTime\":\"2026-01-01T01:00:00Z\"}]}"));
        JSONObject o = new JSONObject(json);
        assertEquals("error", o.getString("status"));
        assertEquals(HistoryOverlayToolErrors.MISSING_SERIES_LABEL, o.getString("code"));
        assertNoRetiredChartToolCode(o);
    }

    @Test
    void anchorOffsetWithExplicitBoundsMapsToOverlayWindowCode() throws Exception {
        ObjectNode series = MAPPER.createObjectNode();
        series.put("label", "A");
        series.put("thingName", "T1");
        series.put("startTime", "2026-01-01T00:00:00Z");
        series.put("endTime", "2026-01-01T01:00:00Z");
        series.put("anchorOffset", "1d");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("propertyName", "currentDraw");
        root.putArray("series").add(series).addObject()
                .put("label", "B")
                .put("thingName", "T2")
                .put("startTime", "2026-01-01T00:00:00Z")
                .put("endTime", "2026-01-01T01:00:00Z");

        PeriodOverPeriodPeriodResolver.Outcome resolverOutcome =
                PeriodOverPeriodPeriodResolver.resolve(series, Instant.parse("2026-06-30T12:00:00Z"));
        assertTrue(resolverOutcome.isError());
        assertTrue(HistoryOverlayToolErrors.isRetiredChartToolCode(resolverOutcome.errorCode));

        String json = HistoryOverlayToolErrors.windowResolutionError(resolverOutcome);
        JSONObject o = new JSONObject(json);
        assertEquals("HISTORY_OVERLAY_INVALID_TIME_WINDOW", o.getString("code"));
        assertEquals("anchorOffset", o.getString("rejectedParameter"));
        assertNoRetiredChartToolCode(o);
    }

    @Test
    void missingWindowFieldsMapToOverlayWindowCode() throws Exception {
        ObjectNode series = MAPPER.createObjectNode();
        series.put("label", "A");
        series.put("thingName", "T1");
        PeriodOverPeriodPeriodResolver.Outcome resolverOutcome =
                PeriodOverPeriodPeriodResolver.resolve(series, Instant.now());
        assertTrue(resolverOutcome.isError());

        String json = HistoryOverlayToolErrors.windowResolutionError(resolverOutcome);
        JSONObject o = new JSONObject(json);
        assertEquals("HISTORY_OVERLAY_INVALID_TIME_WINDOW", o.getString("code"));
        assertNoRetiredChartToolCode(o);
    }

    private static void assertNoRetiredChartToolCode(JSONObject o) {
        String code = o.optString("code", "");
        assertFalse(HistoryOverlayToolErrors.isRetiredChartToolCode(code),
                "retired chart tool code leaked: " + code);
        assertFalse(code.startsWith("POP_"), "POP code leaked: " + code);
    }
}
