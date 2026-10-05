package com.thingworx.things.agent.tools;

import org.json.JSONObject;

/**
 * Phase-1c intents that never emit a chart in this phase: return structured fallback before
 * column-mapping validation.
 */
public final class ChartTabularIntentPhaseBoundary {

    private ChartTabularIntentPhaseBoundary() {}

    /**
     * @return non-null JSON tool result body, or {@code null} when this helper does not apply
     */
    public static String tryEarlyJsonFallback(String intentNormalized) {
        if ("status_timeline".equals(intentNormalized)) {
            return phaseJson(intentNormalized, "STATUS_TIMELINE_NOT_SUPPORTED_IN_PHASE_1");
        }
        return null;
    }

    private static String phaseJson(String requestedIntent, String fallbackReason) {
        JSONObject o = new JSONObject();
        o.put("status", "success");
        o.put("code", "CHART_FALLBACK");
        o.put("requestedIntent", requestedIntent);
        o.put("fallback", true);
        o.put("fallbackReason", fallbackReason);
        return o.toString();
    }
}
