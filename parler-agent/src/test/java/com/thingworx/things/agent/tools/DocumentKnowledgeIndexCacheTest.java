package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.thingworx.common.RESTAPIConstants.StatusCode;
import com.thingworx.common.exceptions.InvalidRequestException;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.InfoTable;

/**
 * TTL reuse with rebuild reads made as the current user: a hit makes no repository call, and a rebuild the
 * repository refuses returns nothing and leaves the shared entry untouched.
 */
class DocumentKnowledgeIndexCacheTest {

    private static final String AGENT = "CacheTestAgent";
    private static final DocumentKnowledgeSettings SETTINGS = new DocumentKnowledgeSettings(
            "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);

    @AfterEach
    void tearDown() {
        DocumentKnowledgeIndexCache.clearForTests();
    }

    @Test
    void rebuild_failure_serves_stale_index_when_previous_exists() throws Exception {
        DocumentKnowledgeIndexCache.installExpiredForTests(SETTINGS, AGENT, fixtureIndex());
        DocumentKnowledgeIndexCache.rebuildHookForTests = (s, agent, log, now) -> null;

        DocumentKnowledgeIndexCache.LoadResult result = load();
        assertNotNull(result.index());
        assertTrue(result.staleRebuildFailed());
        assertTrue(result.index().searchedChunks() >= 40);
    }

    @Test
    void rebuild_failure_without_previous_returns_empty() {
        DocumentKnowledgeIndexCache.rebuildHookForTests = (s, agent, log, now) -> null;

        DocumentKnowledgeIndexCache.LoadResult result = load();
        assertNull(result.index());
        assertFalse(result.staleRebuildFailed());
    }

    @Test
    void fresh_hit_makes_no_repository_call() throws Exception {
        DocumentKnowledgeIndex cached = DocumentKnowledgeIndexCache.loadForTests(SETTINGS, AGENT,
                DocumentKnowledgeIndexTest.fernwickFixtureReader());
        DocumentKnowledgeIndexCache.readerForTests = s -> {
            throw new AssertionError("a fresh hit must not read the repository");
        };

        assertSame(cached, load().index());
    }

    @Test
    void refused_rebuild_returns_nothing_and_leaves_the_shared_entry_untouched() throws Exception {
        DocumentKnowledgeIndexCache.installExpiredForTests(SETTINGS, AGENT, fixtureIndex());
        DocumentKnowledgeIndexCache.readerForTests = s -> refusingReader();

        DocumentKnowledgeIndexCache.LoadResult refused = load();
        assertNull(refused.index());
        assertFalse(refused.staleRebuildFailed());

        DocumentKnowledgeIndexCache.readerForTests = null;
        DocumentKnowledgeIndexCache.rebuildHookForTests = (s, agent, log, now) -> null;
        DocumentKnowledgeIndexCache.LoadResult later = load();
        assertNotNull(later.index());
        assertTrue(later.staleRebuildFailed());
    }

    @Test
    void refused_file_read_propagates_out_of_the_index_load() {
        RepositoryReader refusingLoads = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) throws Exception {
                return DocumentKnowledgeIndexTest.fernwickFixtureReader().getFileListing(path, nameMask);
            }

            @Override
            public String loadText(String path) throws Exception {
                throw new InvalidRequestException("Not authorized for ServiceInvoke on LoadText",
                        StatusCode.STATUS_FORBIDDEN);
            }
        };
        DocumentKnowledgeIndexCache.readerForTests = s -> refusingLoads;

        assertNull(load().index());
    }

    private static DocumentKnowledgeIndexCache.LoadResult load() {
        return DocumentKnowledgeIndexCache.getOrLoad(SETTINGS, AGENT, NOPLogger.NOP_LOGGER);
    }

    private static DocumentKnowledgeIndex fixtureIndex() throws Exception {
        return DocumentKnowledgeIndex.load(DocumentKnowledgeIndexTest.fernwickFixtureReader(), SETTINGS, Instant.now());
    }

    private static RepositoryReader refusingReader() {
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) throws Exception {
                throw new InvalidRequestException("Not authorized for ServiceInvoke on BrowseDirectory",
                        StatusCode.STATUS_UNAUTHORIZED);
            }

            @Override
            public String loadText(String path) throws Exception {
                throw new AssertionError("no file read after a refused listing");
            }
        };
    }
}
