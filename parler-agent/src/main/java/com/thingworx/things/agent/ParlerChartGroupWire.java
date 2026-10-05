package com.thingworx.things.agent;

import org.json.JSONObject;

/**
 * AlwaysOn Parler wire {@code type: "chart_group"} (chart-enhancement design §8.5, CHART_CONTRACT §2.6) — pure
 * {@code org.json} for offline-testable construction; the payload is a full {@code ChartGroupManifest}.
 */
public final class ParlerChartGroupWire {
    private ParlerChartGroupWire() {}

    public static String toWireJson(String requestId, String conversationId, JSONObject manifest) {
        JSONObject o = new JSONObject();
        o.put("type", "chart_group");
        if (conversationId != null && !conversationId.isEmpty()) {
            o.put("conversation_id", conversationId);
        }
        o.put("request_id", requestId != null ? requestId : "");
        o.put("group", manifest != null ? manifest : new JSONObject());
        return o.toString();
    }
}
