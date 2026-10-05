package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Wire JSON shape for terminal {@code done} frames (no ThingWorx static logging). */
class ParlerReceiveMessageSupportWireDoneTest {

    @Test
    void wireDone_fourArg_emitsLlmUsageWhenSanitized() {
        String raw = "{\"promptTokens\":5,\"completionTokens\":2,\"inputTokens\":5,\"outputTokens\":2}";
        String frame = ParlerReceiveMessageSupport.wireDone("rid-1", "cid-1", "am-1", raw);
        JSONObject o = new JSONObject(frame);
        assertTrue(o.has("llm_usage"));
        assertTrue(o.getJSONObject("llm_usage").getInt("promptTokens") == 5);
        assertTrue(frame.contains("\"type\":\"done\""));
    }
}
