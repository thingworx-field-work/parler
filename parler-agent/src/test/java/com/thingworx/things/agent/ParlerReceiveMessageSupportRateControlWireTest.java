package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ratecontrol.LlmRateLimitAdmissionReason;

/** Wire JSON for {@link ParlerReceiveMessageSupport#wireRateControlStatus} (no ThingWorx static logging). */
class ParlerReceiveMessageSupportRateControlWireTest {

    @Test
    void wireRateControlStatus_waiting_includesReasonAndDurations() {
        String frame = ParlerReceiveMessageSupport.wireRateControlStatus(
                "rid-1", "cid-1", true, LlmRateLimitAdmissionReason.tokens_per_minute, 20500L, 20500L);
        JSONObject o = new JSONObject(frame);
        assertEquals("rate_control.status", o.getString("type"));
        assertEquals("waiting", o.getString("status"));
        assertEquals("rid-1", o.getString("request_id"));
        assertEquals("cid-1", o.getString("conversation_id"));
        assertEquals("tokens_per_minute", o.getString("reason"));
        assertEquals(20500L, o.getLong("wait_ms"));
        assertEquals(20500L, o.getLong("retry_after_ms"));
    }

    @Test
    void wireRateControlStatus_resumed_omitsOptionalFields() {
        String frame = ParlerReceiveMessageSupport.wireRateControlStatus("rid-1", "cid-1", false, null, 0L, 0L);
        JSONObject o = new JSONObject(frame);
        assertEquals("resumed", o.getString("status"));
        assertFalse(o.has("reason"));
        assertFalse(o.has("wait_ms"));
        assertFalse(o.has("retry_after_ms"));
        assertTrue(o.has("request_id"));
        assertTrue(o.has("conversation_id"));
    }
}
