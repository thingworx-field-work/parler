package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerTabularToolSuccessWireTest {

    @Test
    void compactPayload_keepsEnvelopeAndMetadata() {
        JSONObject env = new JSONObject();
        env.put("schemaVersion", "1");
        env.put("rowEstimate", 42);
        JSONObject root = new JSONObject();
        root.put("insightEnvelope", env);
        root.put("resultKind", "CACHED_TABULATE_INLINE");
        root.put("sourceCacheId", "cache-abc");
        JSONObject c = ParlerTabularToolSuccessWire.compactPayload(root);
        assertNotNull(c);
        assertEquals("1", c.getJSONObject("insightEnvelope").getString("schemaVersion"));
        assertEquals(42, c.getJSONObject("insightEnvelope").getInt("rowEstimate"));
        assertEquals("CACHED_TABULATE_INLINE", c.getString("resultKind"));
        assertEquals("cache-abc", c.getString("sourceCacheId"));
    }

    @Test
    void compactPayload_nullWithoutInsightEnvelope() {
        JSONObject root = new JSONObject();
        root.put("resultKind", "OTHER");
        assertNull(ParlerTabularToolSuccessWire.compactPayload(root));
    }

    @Test
    void toWireJson_includesTypeAndPayload() {
        JSONObject p = new JSONObject();
        p.put("insightEnvelope", new JSONObject().put("schemaVersion", "1"));
        String wire = ParlerTabularToolSuccessWire.toWireJson("rid", "cid", p);
        JSONObject o = new JSONObject(wire);
        assertEquals("tabular.tool_success", o.getString("type"));
        assertEquals("rid", o.getString("request_id"));
        assertEquals("cid", o.getString("conversation_id"));
        assertTrue(o.getJSONObject("payload").has("insightEnvelope"));
    }
}
