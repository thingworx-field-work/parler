package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Wire JSON for gateway user stop + {@code hitl_resolution_source} (normative v1). */
class ParlerReceiveMessageSupportGatewayStopWireTest {

    @Test
    void wireSessionCancelled_includesTypeConversationAndRequest() {
        String json = ParlerReceiveMessageSupport.wireSessionCancelled("rid-1", "cid-1", "user_stop", "Turn stopped.");
        JSONObject o = new JSONObject(json);
        assertEquals("session.cancelled", o.getString("type"));
        assertEquals("rid-1", o.getString("request_id"));
        assertEquals("cid-1", o.getString("conversation_id"));
        assertEquals("user_stop", o.getString("reason"));
        assertEquals("Turn stopped.", o.getString("message"));
    }

    @Test
    void wireApprovalResolved_includesHitlResolutionSourceWhenSet() {
        String json = ParlerReceiveMessageSupport.wireApprovalResolved(
                "rid-1", "cid-1", "pid-1", "cancelled", false, null, null, "gateway_user_stop");
        JSONObject o = new JSONObject(json);
        assertEquals("approval.resolved", o.getString("type"));
        assertEquals("gateway_user_stop", o.getString("hitl_resolution_source"));
        assertTrue(!o.has("error"));
    }

    @Test
    void sendParkedGatewayUserStopLiveWirePair_nullRemote_isNoOp() {
        ParlerReceiveMessageSupport.sendParkedGatewayUserStopLiveWirePair(null, "rid-n", "cid-n", "pid-n", "r");
    }
}
