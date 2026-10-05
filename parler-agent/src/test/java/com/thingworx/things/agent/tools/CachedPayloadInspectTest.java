package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.cache.JsonArtifactHub;
import com.thingworx.things.agent.cache.LargeJsonCaps;
import com.thingworx.things.agent.cache.TabularArtifactHub;

class CachedPayloadInspectTest {

    CachedPayloadInspectTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void inspectReturnsBoundedStructureHints() throws Exception {
        AgentToolContext.setConversationId("inspect-1");
        String cacheId = JsonArtifactHub.store("{\"a\":1,\"b\":[{\"x\":\"y\"}]}");
        JsonNode root = MAPPER.readTree(CachedPayloadInspect.execute(cacheId, "inspect", null));
        assertEquals("success", root.path("status").asText());
        assertEquals("LARGE_JSON_HINTS", root.path("resultKind").asText());
        assertTrue(root.path("parseableJson").asBoolean());
        assertEquals("object", root.path("structure").path("type").asText());
        assertTrue(root.path("structure").path("fields").has("a"));
    }

    @Test
    void extractInlineWhenUnderCap() throws Exception {
        AgentToolContext.setConversationId("inspect-2");
        String cacheId = JsonArtifactHub.store("{\"items\":[{\"id\":7},{\"id\":8}]}");
        JsonNode root = MAPPER.readTree(CachedPayloadInspect.execute(cacheId, "extract", "items[1].id"));
        assertEquals("success", root.path("status").asText());
        assertEquals("JSON_EXTRACT", root.path("resultKind").asText());
        assertEquals(8, root.path("value").asInt());
    }

    @Test
    void extractOverCapReturnsHandleNotTruncatedBody() throws Exception {
        AgentToolContext.setConversationId("inspect-3");
        int over = LargeJsonCaps.INVOKE_RESULT_CLASSIFY_CHAR_CAP + 10;
        String big = "\"v\":\"" + "x".repeat(over) + "\"";
        String payload = "{" + big + "}";
        String cacheId = JsonArtifactHub.store(payload);
        JsonNode root = MAPPER.readTree(CachedPayloadInspect.execute(cacheId, "extract", "v"));
        assertEquals("success", root.path("status").asText());
        assertEquals("LARGE_JSON_CACHED", root.path("resultKind").asText());
        assertFalse(root.path("completeInline").asBoolean());
        assertFalse(root.path("truncated").asBoolean());
        assertTrue(root.path("extractCacheId").asText().length() > 0);
        String extracted = JsonArtifactHub.lookup(root.path("extractCacheId").asText());
        assertTrue(extracted != null && extracted.length() > LargeJsonCaps.INVOKE_RESULT_CLASSIFY_CHAR_CAP);
    }

    @Test
    void jsonRoundTripViaHub() throws Exception {
        AgentToolContext.setConversationId("json-hub-1");
        String cacheId = JsonArtifactHub.store("{\"ok\":true}");
        assertEquals("{\"ok\":true}", JsonArtifactHub.lookup(cacheId));
    }
}
