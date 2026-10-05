package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.HistorySeriesComposerSupport;
import com.thingworx.types.InfoTable;

/**
 * M4 S4+cache: overlay source series store helper publishes fetchable {@code cacheId}s.
 */
class M4S4SeriesCachesStoreTest {

    M4S4SeriesCachesStoreTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void storeNumericHistoryPoints_publishesLookupableCacheId() throws Exception {
        AgentToolContext.setConversationId("s4-series-caches");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T00:01:00Z");
        List<HistorySeriesComposerSupport.HistoryPoint> points = List.of(
                new HistorySeriesComposerSupport.HistoryPoint(t0, 1.5),
                new HistorySeriesComposerSupport.HistoryPoint(t1, 2.5));

        StoredSeriesCache stored = HistorySeriesComposerSupport.storeNumericHistoryPointsInConversationCache(points);
        assertNotNull(stored);
        String cacheId = stored.cacheId();
        assertFalseBlank(cacheId);
        // CM-0/CM-1: the DTO reflects the written table and the roles stamped on the same descriptor.
        assertEquals("timestamp", stored.timeColumn());
        assertEquals("value", stored.valueColumn());
        try (com.thingworx.things.agent.cache.TypedTabularStream stream =
                com.thingworx.things.agent.cache.TypedTabularStream.open(cacheId, null, 0)) {
            assertEquals(stream.schema().size(), stored.columns().size());
            for (int i = 0; i < stream.schema().size(); i++) {
                assertEquals(stream.schema().get(i).name(), stored.columns().get(i).name());
                assertEquals(stream.schema().get(i).baseType(), stored.columns().get(i).baseType());
            }
            assertEquals("timestamp", stream.sourceDescriptor().timeColumn());
            assertEquals("value", stream.sourceDescriptor().valueColumn());
        }

        InfoTable got = InvokeServiceExecutor.lookupCachedInfotable(cacheId);
        assertNotNull(got);
        assertEquals(2, got.getRowCount().intValue());
        assertTrue(got.getDataShape().getFields().containsKey("timestamp"));
        assertTrue(got.getDataShape().getFields().containsKey("value"));
    }

    @Test
    void storeNumericHistoryPoints_emptyReturnsNull() {
        AgentToolContext.setConversationId("s4-series-caches-empty");
        assertNull(HistorySeriesComposerSupport.storeNumericHistoryPointsInConversationCache(List.of()));
        assertNull(HistorySeriesComposerSupport.storeNumericHistoryPointsInConversationCache(null));
    }

    @Test
    void storeWithRoles_omitsInvalidRolesButStillStores() throws Exception {
        AgentToolContext.setConversationId("s4-series-caches-invalid-role");
        InfoTable table = NumericHistoryCacheWriter.newTable();
        NumericHistoryCacheWriter.addRow(table, "2026-01-01T00:00:00Z", 1.0);
        StoredSeriesCache stored = NumericHistoryCacheWriter.storeWithRoles(table, "any.producer",
                "timestamp", "not-a-column", null);
        assertNotNull(stored);
        assertNull(stored.timeColumn());
        assertNull(stored.valueColumn());
        assertNotNull(InvokeServiceExecutor.lookupCachedInfotable(stored.cacheId()));
        assertNull(com.thingworx.things.agent.cache.TabularArtifactHub.lookupDescriptor(stored.cacheId()).valueColumn());
    }


    @Test
    void storeWithIdentity_stampsSubject_pointsOnlyOverloadDeclaresNone() throws Exception {
        AgentToolContext.setConversationId("s4-series-caches-identity");
        List<HistorySeriesComposerSupport.HistoryPoint> points = List.of(
                new HistorySeriesComposerSupport.HistoryPoint(Instant.parse("2026-01-01T00:00:00Z"), 1.5));
        StoredSeriesCache withId = HistorySeriesComposerSupport.storeNumericHistoryPointsInConversationCache(
                points, "SE.CellFab.Model.Workunit.ORD-Contacting-01", "contactForce");
        com.thingworx.things.agent.source.SourceDescriptor d =
                com.thingworx.things.agent.cache.TabularArtifactHub.lookupDescriptor(withId.cacheId());
        assertEquals("SE.CellFab.Model.Workunit.ORD-Contacting-01", d.subjectThingName());
        assertEquals("contactForce", d.subjectPropertyName());
        assertEquals("value", d.valueColumn(), "roles and identity ride the same write");

        StoredSeriesCache noId = HistorySeriesComposerSupport.storeNumericHistoryPointsInConversationCache(points);
        assertNull(com.thingworx.things.agent.cache.TabularArtifactHub.lookupDescriptor(noId.cacheId()).subjectThingName());

        StoredSeriesCache half = HistorySeriesComposerSupport.storeNumericHistoryPointsInConversationCache(
                points, "SE.Thing.X", "");
        assertNotNull(half, "an incomplete identity never fails the store");
        assertNull(com.thingworx.things.agent.cache.TabularArtifactHub.lookupDescriptor(half.cacheId()).subjectThingName());
    }

    private static void assertFalseBlank(String s) {
        assertNotNull(s);
        assertTrue(!s.isBlank(), "cacheId must be non-blank");
    }
}
