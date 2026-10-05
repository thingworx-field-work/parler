package com.thingworx.things.agent.transform.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.ArtifactCacheTestFixtures;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.NumericHistoryCacheWriter;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral.Config;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral.Method;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral.Unit;
import com.thingworx.types.InfoTable;

/** CF-01 operation-wide resources: one deadline through publication, and the reading limit at its boundary. */
class TimeWeightedCacheRunnerBudgetTest {

    private static final Instant T0 = Instant.parse("2026-03-02T06:00:00Z");
    private static final HalfOpenWindow WINDOW = HalfOpenWindow.of(T0, T0.plusSeconds(400_000));
    private static final Config STEP = new Config(Method.STEP_HOLD, 30, Unit.HOURS);
    private static final long BUDGET_NANOS = BudgetVector.defaultsForTabular().maxWallTimeMillis() * 1_000_000L;
    private static final String TS = NumericHistoryCacheWriter.TIME_COLUMN;
    private static final String V = NumericHistoryCacheWriter.VALUE_COLUMN;

    TimeWeightedCacheRunnerBudgetTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("ce3-time-weighted-budget");
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void deadlineDuringReadingOrComputing_isATypedFailure() throws Exception {
        String cid = history(2_000, 0);
        AtomicLong dryReads = new AtomicLong();
        TimeWeightedCacheRunner.run(cid, TS, V, WINDOW, STEP, () -> {
            dryReads.incrementAndGet();
            return 0L;
        });
        assertTrue(dryReads.get() > 8, "clock reads=" + dryReads.get());
        // Every clock read but the last is a gate (reading, computing, around publication); the last one only
        // measures the elapsed time of a finished operation.
        for (long jumpAt = 2; jumpAt < dryReads.get(); jumpAt++) {
            long at = jumpAt;
            AtomicLong reads = new AtomicLong();
            MeasurementException e = assertThrows(MeasurementException.class, () -> TimeWeightedCacheRunner.run(
                    cid, TS, V, WINDOW, STEP, () -> reads.incrementAndGet() >= at ? BUDGET_NANOS + 1 : 0L),
                    "jump at clock read " + at);
            assertEquals(OperationGuard.TIME_BUDGET_EXCEEDED, e.code(), "jump at clock read " + at);
        }
    }

    @Test
    void deadlineDuringOutputCreation_neverMakesTheArtifactVisible() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = history(10, 0);
        AtomicLong clock = new AtomicLong();
        cache.onNextCreates(() -> clock.set(BUDGET_NANOS + 1));
        MeasurementException e = assertThrows(MeasurementException.class,
                () -> TimeWeightedCacheRunner.run(cid, TS, V, WINDOW, STEP, clock::get));
        assertEquals(OperationGuard.TIME_BUDGET_EXCEEDED, e.code());
        assertEquals(0, cache.publicationsSinceArmed(), "the staged table was aborted, not published");
    }

    @Test
    void interruptDuringOutputCreation_neverMakesTheArtifactVisible() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = history(10, 0);
        cache.onNextCreates(() -> Thread.currentThread().interrupt());
        try {
            MeasurementException e = assertThrows(MeasurementException.class,
                    () -> TimeWeightedCacheRunner.run(cid, TS, V, WINDOW, STEP));
            assertEquals(OperationGuard.CANCELLED, e.code());
            assertTrue(Thread.currentThread().isInterrupted(), "the flag is left for the caller");
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, cache.publicationsSinceArmed());
    }

    /**
     * Representative scale through the real cache path. The limit counts readings inside the window; the two
     * anchors ride on top of it, so 100,000 in-window readings plus an anchor on each side are admitted.
     */
    @Test
    void readingLimit_isInclusiveAtTheCap_anchorsExcluded_andRefusesOneMore() throws Exception {
        int cap = MeasurementSeriesReader.MAX_OBSERVATIONS;
        HalfOpenWindow exact = HalfOpenWindow.of(T0, T0.plusSeconds(cap));
        String capped = history(cap + 2, -1);
        long start = System.nanoTime();
        MeasurementRunResult run = TimeWeightedCacheRunner.run(capped, TS, V, exact,
                new Config(Method.TRAPEZOID, 30, Unit.HOURS));
        long millis = (System.nanoTime() - start) / 1_000_000L;
        assertNotNull(run.findingCacheId());
        assertEquals(Integer.toString(cap), run.envelope().metrics().get("readings"));
        assertEquals(Integer.toString(cap), run.envelope().metrics().get("observedSeconds"),
                "both anchors are real readings, so the whole window is observed");
        assertEquals("0", run.envelope().metrics().get("unknownSeconds"));
        System.out.println("TimeWeighted scale: " + (cap + 2) + " rows, " + cap + " in-window readings, trapezoid, "
                + "segments=" + run.envelope().evidence().n() + ", wallMillis=" + millis);
        assertTrue(millis < BudgetVector.defaultsForTabular().maxWallTimeMillis());

        HalfOpenWindow oneMore = HalfOpenWindow.of(T0, T0.plusSeconds(cap + 1L));
        MeasurementException e = assertThrows(MeasurementException.class,
                () -> TimeWeightedCacheRunner.run(capped, TS, V, oneMore, STEP));
        assertEquals(MeasurementSeriesReader.INPUT_TOO_LARGE, e.code());
    }

    /** A numeric property-history cache, one reading per second starting {@code firstSecond} after T0. */
    private static String history(int n, int firstSecond) throws Exception {
        InfoTable table = NumericHistoryCacheWriter.newTable();
        for (int i = 0; i < n; i++) {
            NumericHistoryCacheWriter.addRow(table, T0.plusSeconds(firstSecond + i).toString(), (double) (i % 97));
        }
        return NumericHistoryCacheWriter.store(table, "query_numeric_property_history", "Pump1", "power", null)
                .cacheId();
    }
}
