package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.cache.ArtifactCacheTurnAdmission;

class ParlerArtifactCacheTerminalWireTest {

    @Test
    void cacheAdmissionErrorUsesExistingTerminalErrorFrameAndSameIds() {
        JSONObject frame = new JSONObject(ParlerReceiveMessageSupport.wireError(
                "request-13", "conversation-13", ArtifactCacheTurnAdmission.NOT_CONFIGURED_MESSAGE,
                ArtifactCacheTurnAdmission.NOT_CONFIGURED_CODE));

        assertEquals("error", frame.getString("type"));
        assertEquals("request-13", frame.getString("request_id"));
        assertEquals("conversation-13", frame.getString("conversation_id"));
        assertEquals("ARTIFACT_CACHE_NOT_CONFIGURED", frame.getString("code"));
        assertEquals(ArtifactCacheTurnAdmission.NOT_CONFIGURED_MESSAGE, frame.getString("message"));
        assertFalse(frame.has("assistant_message_id"));
        assertFalse(frame.has("llm_usage"));
    }
}
