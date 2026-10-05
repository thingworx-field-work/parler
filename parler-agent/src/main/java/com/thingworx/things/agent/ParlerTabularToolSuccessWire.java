package com.thingworx.things.agent;

import org.json.JSONObject;

/**
 * Further Insight optional Parler wire {@code tabular.tool_success} — compact tool-success projection only
 * (no LARGE row duplication). Pure {@link org.json} — safe for offline JUnit without ThingWorx static init.
 *
 * Emitted via {@link ParlerReceiveMessageSupport#send}.
 */
public final class ParlerTabularToolSuccessWire {

    private ParlerTabularToolSuccessWire() {}

    /**
     * When {@code toolSuccessRoot} carries Further Insight {@code insightEnvelope}, returns a small wire payload
     * ({@code insightEnvelope} + optional {@code resultKind} / {@code sourceCacheId}).
     *
     * @return {@code null} when there is no {@code insightEnvelope} object
     */
    public static JSONObject compactPayload(JSONObject toolSuccessRoot) {
        if (toolSuccessRoot == null) {
            return null;
        }
        JSONObject env = toolSuccessRoot.optJSONObject("insightEnvelope");
        if (env == null) {
            return null;
        }
        JSONObject out = new JSONObject();
        out.put("insightEnvelope", env);
        if (toolSuccessRoot.has("resultKind")) {
            Object rk = toolSuccessRoot.opt("resultKind");
            if (rk != null) {
                out.put("resultKind", rk);
            }
        }
        if (toolSuccessRoot.has("sourceCacheId")) {
            Object sc = toolSuccessRoot.opt("sourceCacheId");
            if (sc != null) {
                out.put("sourceCacheId", sc);
            }
        }
        return out;
    }

    /** Builds the UTF-8 JSON string for {@link ParlerReceiveMessageSupport#send}. */
    public static String toWireJson(String requestId, String conversationId, JSONObject payload) {
        JSONObject o = new JSONObject();
        o.put("request_id", requestId != null ? requestId : "");
        o.put("conversation_id", conversationId != null ? conversationId : "");
        o.put("type", "tabular.tool_success");
        o.put("payload", payload != null ? payload : new JSONObject());
        return o.toString();
    }
}
