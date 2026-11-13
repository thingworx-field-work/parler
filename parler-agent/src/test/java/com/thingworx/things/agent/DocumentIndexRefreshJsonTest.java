package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.tools.DemoPageFixtures;
import com.thingworx.things.agent.tools.DocumentKnowledgeIndex;
import com.thingworx.things.agent.tools.DocumentKnowledgeIndexCache;
import com.thingworx.things.agent.tools.DocumentKnowledgeSettings;

/** P40 (topic round 6, R27): the service result and the {@code documentKnowledge} snapshot block. */
class DocumentIndexRefreshJsonTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final DocumentKnowledgeSettings SETTINGS = DemoPageFixtures.settings(6_000);

    @AfterEach
    void tearDown() {
        DocumentKnowledgeIndexCache.clearForTests();
    }

    @Test
    void the_service_result_and_the_snapshot_block() throws Exception {
        JsonNode none = M.readTree(DocumentIndexRefreshJson.snapshotBlock(SETTINGS, DemoPageFixtures.AGENT).toString());
        assertTrue(none.path("index").isNull(), "no index cached and none loaded by the read");
        assertEquals(300, none.path("indexTtlSeconds").asInt());
        assertEquals(6_000, none.path("chunkMaxChars").asInt());
        assertTrue(DocumentKnowledgeIndexCache.peek(SETTINGS, DemoPageFixtures.AGENT).isEmpty());

        DocumentKnowledgeIndex idx = DocumentKnowledgeIndexCache.loadForTests(SETTINGS, DemoPageFixtures.AGENT,
                new DemoPageFixtures());
        JsonNode loaded = M.readTree(DocumentIndexRefreshJson.snapshotBlock(SETTINGS, DemoPageFixtures.AGENT).toString());
        assertEquals(idx.loadedAt().toString(), loaded.path("index").path("loadedAt").asText());
        assertEquals(idx.expiresAt().toString(), loaded.path("index").path("expiresAt").asText());

        JsonNode removed = M.readTree(DocumentIndexRefreshJson.refreshResult(
                DocumentKnowledgeIndexCache.invalidate(SETTINGS, DemoPageFixtures.AGENT), 300));
        assertTrue(removed.path("invalidated").asBoolean());
        assertEquals(idx.loadedAt().toString(), removed.path("evictedIndexLoadedAt").asText());
        assertEquals(300, removed.path("ttlSeconds").asInt());

        JsonNode again = M.readTree(DocumentIndexRefreshJson.refreshResult(
                DocumentKnowledgeIndexCache.invalidate(SETTINGS, DemoPageFixtures.AGENT), 300));
        assertFalse(again.path("invalidated").asBoolean());
        assertTrue(again.path("evictedIndexLoadedAt").isNull());
    }
}
