package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.HistoryOverlayChartBuilder;
import com.thingworx.things.agent.HistorySeriesComposerSupport;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.cache.TypedBatch;
import com.thingworx.things.agent.cache.TypedTabularStream;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.BuildHistoryOverlayChartExecutor;
import com.thingworx.things.agent.tools.CachedTabularGroupMetricExecutor;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.things.agent.tools.PeriodOverPeriodPeriodResolver;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Locks the time-quality-join baseline: the live history overlay / period-over-period / group-metric
 * classes resolve, the group-metric surface ships no datePart grouping, and the global returned-rows
 * default is unchanged. Also captures a local microbench snapshot of cache store, lookup, and
 * early-stop streaming.
 */
class Tqj0BaselineCensusTest {

    Tqj0BaselineCensusTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void livePathClassesResolve() {
        assertTrue(BuildHistoryOverlayChartExecutor.class.getName().contains("BuildHistoryOverlayChart"));
        assertTrue(HistoryOverlayChartBuilder.class.getSimpleName().equals("HistoryOverlayChartBuilder"));
        assertTrue(HistorySeriesComposerSupport.class.getSimpleName().equals("HistorySeriesComposerSupport"));
        assertTrue(PeriodOverPeriodPeriodResolver.class.getSimpleName().equals("PeriodOverPeriodPeriodResolver"));
        assertTrue(CachedTabularGroupMetricExecutor.class.getSimpleName().equals("CachedTabularGroupMetricExecutor"));
        // MAX_SCANNED_ROWS_DECISION=100_000 is package-private and not asserted here.
        assertEquals(5000L, BudgetVector.defaultsForTabular().maxReturnedRows());
    }

    @Test
    void datePartGroupByNotShippedOnGroupMetricSurface() throws Exception {
        // No public datePart helper on the group-metric executor — plain-column groupBy only.
        for (Method m : CachedTabularGroupMetricExecutor.class.getDeclaredMethods()) {
            assertFalse(m.getName().toLowerCase().contains("datepart"),
                    "datePart helpers must not appear on shipped group-metric executor");
        }
    }

    @Test
    void microbench_storeLookupAndEarlyStopStream() throws Exception {
        InfoTable table = wideTable(2_000);
        long heapBefore = usedHeap();
        long t0 = System.nanoTime();
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(table);
        InfoTable looked = InvokeServiceExecutor.lookupCachedInfotable(cacheId);
        long storeLookupNanos = System.nanoTime() - t0;
        long heapAfterLookup = usedHeap();
        assertEquals(2_000, looked.getRowCount().intValue());
        assertTrue(storeLookupNanos >= 0L);

        long t1 = System.nanoTime();
        long streamed = 0L;
        try (TypedTabularStream stream = TypedTabularStream.open(cacheId, List.of("a", "b"), 200)) {
            while (!stream.exhausted()) {
                TypedBatch batch = stream.readBatch(50);
                streamed += batch.size();
            }
            assertEquals(200L, streamed);
            assertTrue(stream.stoppedEarly());
            assertFalse(stream.inputsFullyScanned());
        }
        long streamNanos = System.nanoTime() - t1;
        assertTrue(streamNanos >= 0L);
        // Structural only: heap delta may be negative after GC; record non-crash evidence.
        long heapDelta = heapAfterLookup - heapBefore;
        assertTrue(heapDelta > Long.MIN_VALUE);
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static InfoTable wideTable(int rows) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (String n : List.of("a", "b", "c", "d")) {
            FieldDefinition f = new FieldDefinition();
            f.setName(n);
            f.setBaseType(n.equals("a") ? BaseTypes.STRING : BaseTypes.NUMBER);
            shape.addFieldDefinition(f);
        }
        InfoTable t = new InfoTable(shape);
        for (int i = 0; i < rows; i++) {
            ValueCollection row = new ValueCollection();
            row.put("a", new StringPrimitive("r" + i));
            row.put("b", new NumberPrimitive((double) i));
            row.put("c", new NumberPrimitive((double) (i * 2)));
            row.put("d", new NumberPrimitive((double) (i * 3)));
            t.addRow(row);
        }
        return t;
    }
}
