package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerChartWireSupportTest {

    private static String minimalHistoryJson() {
        return "{\"status\":\"success\",\"thingName\":\"T\",\"propertyName\":\"P\",\"points\":["
                + "{\"timestamp\":\"2026-01-01T00:00:00Z\",\"value\":1.0},"
                + "{\"timestamp\":\"2026-01-01T00:01:00Z\",\"value\":2.0}]}";
    }

    @Test
    void livePath_assignsChartId() {
        Optional<JSONObject> live = ParlerChartWireSupport.chartBlockFromNumericHistoryToolResult(minimalHistoryJson());
        assertTrue(live.isPresent());
        assertTrue(live.get().has("chartId"));
    }

    @Test
    void replayPath_omitsChartId() {
        Optional<JSONObject> replay =
                ParlerChartWireSupport.chartBlockFromNumericHistoryToolResult(minimalHistoryJson(), false);
        assertTrue(replay.isPresent());
        assertFalse(replay.get().has("chartId"));
    }

    @Test
    void chart_emitted_with_chart_block_replay_keeps_persisted_chart_id() {
        String body = "{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"chartBlock\":{"
                + "\"kind\":\"pie\",\"chartId\":\"c-live\",\"title\":\"T\","
                + "\"series\":[{\"name\":\"s\",\"x\":[\"A\"],\"y\":[1]}]}}";
        Optional<JSONObject> replay = ParlerChartWireSupport.chartBlockFromChartEmittedToolResult(body, false);
        assertTrue(replay.isPresent());
        assertEquals("c-live", replay.get().optString("chartId"),
                "chart-group members resolve their chart by this id on history replay");
        assertEquals("pie", replay.get().optString("kind"));
        String noId = body.replace("\"chartId\":\"c-live\",", "");
        assertFalse(ParlerChartWireSupport.chartBlockFromChartEmittedToolResult(noId, false).get().has("chartId"),
                "history export never invents an id");
    }

    @Test
    void chart_emitted_without_chart_block_empty() {
        assertFalse(ParlerChartWireSupport.chartBlockFromChartEmittedToolResult(
                "{\"status\":\"success\",\"code\":\"CHART_EMITTED\"}", false).isPresent());
    }
}
