package com.thingworx.things.agent;

import org.json.JSONObject;

/**
 * AlwaysOn Parler wire {@code type: "table"} — pure {@link org.json} for offline JUnit (same pattern as
 * {@link ParlerTabularToolSuccessWire}). {@link ParlerReceiveMessageSupport#wireTable} delegates here so tests
 * need not load ThingWorx {@code LogUtilities} static init.
 *
 * <p>Payload shape: {@code CONTRACTS/TABLE_CONTRACT.md}.</p>
 */
public final class ParlerTableWire {

    private ParlerTableWire() {}

    /** Builds the UTF-8 JSON string for {@link ParlerReceiveMessageSupport#send}. */
    public static String toWireJson(String requestId, String conversationId, JSONObject table) {
        JSONObject o = new JSONObject();
        o.put("request_id", requestId != null ? requestId : "");
        o.put("conversation_id", conversationId != null ? conversationId : "");
        o.put("type", "table");
        o.put("table", table != null ? table : new JSONObject());
        return o.toString();
    }
}
