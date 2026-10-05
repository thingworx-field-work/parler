package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ChartTabularIntentPhaseBoundaryTest {

    @Test
    void distribution_isNoLongerAnEarlyFallback_sinceC2b1() {
        assertNull(ChartTabularIntentPhaseBoundary.tryEarlyJsonFallback("distribution"));
    }

    @Test
    void statusTimeline_returnsStructuredFallback() {
        String s = ChartTabularIntentPhaseBoundary.tryEarlyJsonFallback("status_timeline");
        JSONObject o = new JSONObject(s);
        assertEquals("STATUS_TIMELINE_NOT_SUPPORTED_IN_PHASE_1", o.getString("fallbackReason"));
    }

    @Test
    void timeTrend_notHandledHere() {
        assertNull(ChartTabularIntentPhaseBoundary.tryEarlyJsonFallback("time_trend"));
    }
}
