package com.thingworx.things.agent.transform.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.ArtifactCacheTestFixtures;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.transform.time.CounterDelta.CounterDeltaException;
import com.thingworx.things.agent.transform.time.CounterDelta.Reading;
import com.thingworx.things.agent.transform.time.CounterDelta.Rules;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;

/** CF-05 operation-wide resources: one deadline for read, compute and publish; limits at their boundary. */
class CounterDeltaCacheRunnerBudgetTest {

    private static final Instant T0 = Instant.parse("2026-03-02T06:00:00Z");
    private static final HalfOpenWindow WINDOW = HalfOpenWindow.of(T0, T0.plusSeconds(400_000));

    CounterDeltaCacheRunnerBudgetTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("ce1-counter-delta-budget");
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void deadlinePassedBeforePublication_publishesNothing() throws Exception {
        String cid = TabularArtifactHub.store(table(3));
        long budgetNanos = BudgetVector.defaultsForTabular().maxWallTimeMillis() * 1_000_000L;
        // The clock stays at 0 through reading and computing, then jumps past the budget: only the
        // pre-publication gates can stop the operation.
        AtomicLong calls = new AtomicLong();
        MeasurementException e = assertThrows(MeasurementException.class, () -> CounterDeltaCacheRunner.run(
                cid, "ts", "v", null, WINDOW, Rules.none(), () -> calls.incrementAndGet() > 6 ? budgetNanos + 1 : 0L));
        assertEquals(CounterDeltaCacheRunner.TIME_BUDGET_EXCEEDED, e.code());
    }

    /**
     * The deadline passes while the output artifact is being created, after the runner's own last check.
     * The store path must stop before the staged artifact becomes visible.
     */
    @Test
    void deadlinePassedDuringOutputCreation_neverMakesTheArtifactVisible() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = TabularArtifactHub.store(table(3));
        long budgetNanos = BudgetVector.defaultsForTabular().maxWallTimeMillis() * 1_000_000L;
        AtomicLong clock = new AtomicLong();
        cache.onNextCreates(() -> clock.set(budgetNanos + 1));
        MeasurementException e = assertThrows(MeasurementException.class, () -> CounterDeltaCacheRunner.run(
                cid, "ts", "v", null, WINDOW, Rules.none(), clock::get));
        assertEquals(CounterDeltaCacheRunner.TIME_BUDGET_EXCEEDED, e.code());
        assertEquals(0, cache.publicationsSinceArmed(), "the staged segment table was aborted, not published");
    }

    /** An interrupt that arrives during output creation is a cancellation, not a success. */
    @Test
    void interruptDuringOutputCreation_neverMakesTheArtifactVisible() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = TabularArtifactHub.store(table(3));
        cache.onNextCreates(() -> Thread.currentThread().interrupt());
        try {
            MeasurementException e = assertThrows(MeasurementException.class,
                    () -> CounterDeltaCacheRunner.run(cid, "ts", "v", null, WINDOW, Rules.none()));
            assertEquals(CounterDeltaCacheRunner.CANCELLED, e.code());
            assertTrue(Thread.currentThread().isInterrupted(), "the flag is left for the caller");
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, cache.publicationsSinceArmed());
    }

    /** The output writer gets the operation's remaining time, not a fresh full deadline. */
    @Test
    void outputWriter_isLimitedToTheRemainingOperationTime() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = TabularArtifactHub.store(table(3));
        long budgetMillis = BudgetVector.defaultsForTabular().maxWallTimeMillis();
        AtomicLong clock = new AtomicLong();
        cache.onNextCreates(() -> { });
        // Every clock read advances one second, so well over a second has been used before the writer opens.
        MeasurementRunResult run = CounterDeltaCacheRunner.run(cid, "ts", "v", null, WINDOW, Rules.none(),
                () -> clock.addAndGet(1_000_000_000L));
        assertNotNull(run.findingCacheId());
        assertEquals(1, cache.publicationsSinceArmed());
        assertTrue(run.envelope().budget().consumedWallTimeMillis() <= budgetMillis);
    }

    /** No segment table to publish: an over-budget run is still a typed failure, not a normal envelope. */
    @Test
    void overBudgetRunWithoutSegments_isATypedFailure() throws Exception {
        String cid = TabularArtifactHub.store(table(3));
        HalfOpenWindow empty = HalfOpenWindow.of(T0.minusSeconds(100), T0.minusSeconds(50));
        long budgetNanos = BudgetVector.defaultsForTabular().maxWallTimeMillis() * 1_000_000L;
        // Dry run: count the clock reads. The last read is the accounting, the one before it is the
        // completion check of the no-table branch.
        AtomicLong dryReads = new AtomicLong();
        MeasurementRunResult dry = CounterDeltaCacheRunner.run(cid, "ts", "v", null, empty, Rules.none(),
                () -> { dryReads.incrementAndGet(); return 0L; });
        assertEquals(null, dry.findingCacheId());
        long completionCheck = dryReads.get() - 1;
        AtomicLong reads = new AtomicLong();
        MeasurementException e = assertThrows(MeasurementException.class, () -> CounterDeltaCacheRunner.run(
                cid, "ts", "v", null, empty, Rules.none(),
                () -> reads.incrementAndGet() >= completionCheck ? budgetNanos + 1 : 0L));
        assertEquals(CounterDeltaCacheRunner.TIME_BUDGET_EXCEEDED, e.code());
    }

    @Test
    void accountingReportsTheWholeOperation() throws Exception {
        String cid = TabularArtifactHub.store(table(50));
        AtomicLong clock = new AtomicLong();
        MeasurementRunResult run = CounterDeltaCacheRunner.run(cid, "ts", "v", null, WINDOW, Rules.none(),
                () -> clock.addAndGet(1_000_000L));
        assertNotNull(run.findingCacheId());
        assertNotNull(run.envelope().budget());
        assertEquals(50L, run.envelope().budget().consumedRows());
        assertTrue(run.envelope().budget().consumedBytes() > 0L);
        assertTrue(run.envelope().budget().consumedWallTimeMillis() > 0L);
        assertEquals(BudgetVector.defaultsForTabular().maxWallTimeMillis(),
                run.envelope().budget().effective().maxWallTimeMillis());
    }

    @Test
    void kernelStopsWhenTheAbortCheckThrows() {
        List<Reading> readings = readings(5_000);
        AtomicLong checks = new AtomicLong();
        assertThrows(MeasurementException.class, () -> CounterDelta.compute(readings, Rules.none(), () -> {
            if (checks.incrementAndGet() > 3) {
                throw new CounterDeltaException(CounterDeltaCacheRunner.CANCELLED, "stop");
            }
        }));
        assertTrue(checks.get() <= 5, "stopped at the first failing check, not after finishing");
    }

    /** Representative scale at the admission limit: 100,000 readings compute, one more is refused. */
    @Test
    void readingLimit_isInclusiveAtTheCap_andRefusesOneMore() throws Exception {
        CounterDelta.Result atCap = CounterDelta.compute(readings(CounterDeltaCacheRunner.MAX_READINGS), Rules.none());
        assertEquals(CounterDeltaCacheRunner.MAX_READINGS - 1, atCap.segmentsWithDelta());
        assertEquals((double) (CounterDeltaCacheRunner.MAX_READINGS - 1), atCap.knownDelta(), 0d);

        HalfOpenWindow wide = HalfOpenWindow.of(T0, T0.plusSeconds(CounterDeltaCacheRunner.MAX_READINGS + 10L));
        String capped = TabularArtifactHub.store(table(CounterDeltaCacheRunner.MAX_READINGS));
        assertEquals(CounterDeltaCacheRunner.MAX_READINGS - 1,
                CounterDeltaCacheRunner.run(capped, "ts", "v", null, wide, Rules.none()).envelope().evidence().n());
        String over = TabularArtifactHub.store(table(CounterDeltaCacheRunner.MAX_READINGS + 1));
        MeasurementException e = assertThrows(MeasurementException.class,
                () -> CounterDeltaCacheRunner.run(over, "ts", "v", null, wide, Rules.none()));
        assertEquals(CounterDeltaCacheRunner.INPUT_TOO_LARGE, e.code());
    }

    private static List<Reading> readings(int n) {
        List<Reading> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Reading("", T0.plusSeconds(i), i, i));
        }
        return out;
    }

    private static InfoTable table(int n) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("ts", BaseTypes.DATETIME));
        shape.addFieldDefinition(field("v", BaseTypes.NUMBER));
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < n; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("ts", new DatetimePrimitive(new DateTime(T0.plusSeconds(i).toEpochMilli())));
            vc.put("v", new NumberPrimitive((double) i));
            table.addRow(vc);
        }
        return table;
    }

    private static FieldDefinition field(String name, BaseTypes type) {
        FieldDefinition f = new FieldDefinition();
        f.setName(name);
        f.setBaseType(type);
        return f;
    }
}
