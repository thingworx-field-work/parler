package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.fasterxml.jackson.databind.JsonNode;

/** P40 (topic round 6, R27): lazy invalidation of one index key, guarded against an in-flight rebuild. */
class DocumentIndexRefreshTest {

    private static final DocumentKnowledgeSettings SETTINGS = DemoPageFixtures.settings(6_000);

    @AfterEach
    void tearDown() {
        DocumentKnowledgeIndexCache.clearForTests();
        AgentToolContext.clear();
    }

    private static DocumentKnowledgeIndexCache.LoadResult load(String agent) {
        return DocumentKnowledgeIndexCache.getOrLoad(SETTINGS, agent, NOPLogger.NOP_LOGGER);
    }

    @Test
    void invalidate_reports_the_evicted_index_and_the_next_search_finds_a_changed_row() throws Exception {
        DemoPageFixtures reader = new DemoPageFixtures();
        DocumentKnowledgeIndexCache.readerForTests = s -> reader;
        DocumentKnowledgeIndex first = load(DemoPageFixtures.AGENT).index();
        assertNotNull(first);

        reader.changedPage0001 = "Changed demo row, published after the index was loaded.";
        String stale = DocumentKnowledgeRuntime.executeGetDocumentChunk(
                DemoPageFixtures.getChunk("g-1", "page-0001"), SETTINGS, DemoPageFixtures.AGENT);
        assertFalse(stale.contains("Changed demo row"), "the cached index still serves the old row");

        DocumentKnowledgeIndexCache.Invalidation inv =
                DocumentKnowledgeIndexCache.invalidate(SETTINGS, DemoPageFixtures.AGENT);
        assertTrue(inv.invalidated());
        assertEquals(first.loadedAt(), inv.evictedIndexLoadedAt());
        assertTrue(DocumentKnowledgeIndexCache.peek(SETTINGS, DemoPageFixtures.AGENT).isEmpty(), "index: null");

        String fresh = DocumentKnowledgeRuntime.executeGetDocumentChunk(
                DemoPageFixtures.getChunk("g-2", "page-0001"), SETTINGS, DemoPageFixtures.AGENT);
        assertTrue(fresh.contains("Changed demo row"), fresh);
    }

    @Test
    void with_no_entry_nothing_is_invalidated() {
        DocumentKnowledgeIndexCache.Invalidation inv =
                DocumentKnowledgeIndexCache.invalidate(SETTINGS, DemoPageFixtures.AGENT);
        assertFalse(inv.invalidated());
        assertNull(inv.evictedIndexLoadedAt());
    }

    @Test
    void a_second_key_stays_cached() {
        DocumentKnowledgeIndexCache.readerForTests = s -> new DemoPageFixtures();
        load(DemoPageFixtures.AGENT);
        load("OtherDemoAgent");
        DocumentKnowledgeIndexCache.invalidate(SETTINGS, DemoPageFixtures.AGENT);
        assertTrue(DocumentKnowledgeIndexCache.peek(SETTINGS, DemoPageFixtures.AGENT).isEmpty());
        assertTrue(DocumentKnowledgeIndexCache.peek(SETTINGS, "OtherDemoAgent").isPresent());
    }

    @Test
    void a_rebuild_that_began_before_the_invalidation_does_not_repopulate_the_key() throws Exception {
        DocumentKnowledgeIndex built = DocumentKnowledgeIndexCache.loadForTests(SETTINGS, "builder",
                new DemoPageFixtures());
        DocumentKnowledgeIndexCache.clearForTests();
        DocumentKnowledgeIndexCache.rebuildHookForTests = (s, agent, log, now) -> {
            DocumentKnowledgeIndexCache.invalidate(SETTINGS, DemoPageFixtures.AGENT);
            return built;
        };
        DocumentKnowledgeIndexCache.LoadResult r = load(DemoPageFixtures.AGENT);
        assertSame(built, r.index(), "the rebuilt index serves this one call");
        assertTrue(DocumentKnowledgeIndexCache.peek(SETTINGS, DemoPageFixtures.AGENT).isEmpty(), "not stored");
    }

    @Test
    void an_invalidation_between_the_generation_compare_and_the_publish_leaves_the_key_empty() throws Exception {
        DocumentKnowledgeIndex built = DocumentKnowledgeIndexCache.loadForTests(SETTINGS, "builder",
                new DemoPageFixtures());
        String key = SETTINGS.cacheKey(DemoPageFixtures.AGENT);
        long captured = DocumentKnowledgeIndexCache.generation(key);
        DocumentKnowledgeIndexCache.invalidate(key);
        assertFalse(DocumentKnowledgeIndexCache.publishIfCurrent(key, captured, built));
        assertTrue(DocumentKnowledgeIndexCache.peek(SETTINGS, DemoPageFixtures.AGENT).isEmpty());
        assertTrue(DocumentKnowledgeIndexCache.publishIfCurrent(key, DocumentKnowledgeIndexCache.generation(key),
                built), "a current generation publishes");
    }

    @Test
    void a_failed_rebuild_after_invalidation_fails_as_a_first_load() throws Exception {
        DocumentKnowledgeIndexCache.loadForTests(SETTINGS, DemoPageFixtures.AGENT, new DemoPageFixtures());
        DocumentKnowledgeIndexCache.invalidate(SETTINGS, DemoPageFixtures.AGENT);
        DocumentKnowledgeIndexCache.rebuildHookForTests = (s, agent, log, now) -> {
            throw new IllegalStateException("synthetic rebuild failure");
        };
        DocumentKnowledgeIndexCache.LoadResult r = load(DemoPageFixtures.AGENT);
        assertNull(r.index(), "no stale index to serve");
        assertFalse(r.staleRebuildFailed());
    }

    @Test
    void an_invalidation_during_a_failing_rebuild_does_not_serve_the_stale_index() throws Exception {
        DocumentKnowledgeIndex loaded = DocumentKnowledgeIndexCache.loadForTests(SETTINGS, DemoPageFixtures.AGENT,
                new DemoPageFixtures());
        DocumentKnowledgeIndexCache.installExpiredForTests(SETTINGS, DemoPageFixtures.AGENT, loaded);
        DocumentKnowledgeIndexCache.rebuildHookForTests = (s, agent, log, now) -> {
            DocumentKnowledgeIndexCache.invalidate(SETTINGS, DemoPageFixtures.AGENT);
            throw new IllegalStateException("synthetic rebuild failure");
        };
        assertNull(load(DemoPageFixtures.AGENT).index());
    }

    @Test
    void peek_never_triggers_a_load() {
        AtomicInteger rebuilds = new AtomicInteger();
        DocumentKnowledgeIndexCache.rebuildHookForTests = (s, agent, log, now) -> {
            rebuilds.incrementAndGet();
            return null;
        };
        assertTrue(DocumentKnowledgeIndexCache.peek(SETTINGS, DemoPageFixtures.AGENT).isEmpty());
        assertEquals(0, rebuilds.get());
    }
}
