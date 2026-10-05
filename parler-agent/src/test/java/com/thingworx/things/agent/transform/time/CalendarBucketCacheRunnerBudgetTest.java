package com.thingworx.things.agent.transform.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.ArtifactCacheTestFixtures;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.transform.time.CalendarBucketLabeler.Granularity;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;

/** CF-03 operation-wide resources: one deadline through publication, and the row limit at its boundary. */
class CalendarBucketCacheRunnerBudgetTest {

    private static final Instant T0 = Instant.parse("2026-03-02T06:00:00Z");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final long BUDGET_NANOS = BudgetVector.defaultsForTabular().maxWallTimeMillis() * 1_000_000L;

    CalendarBucketCacheRunnerBudgetTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("ce4-calendar-bucket-budget");
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void deadlineAtEveryGate_isATypedFailure() throws Exception {
        String cid = TabularArtifactHub.store(table(3_000));
        AtomicLong dryReads = new AtomicLong();
        CalendarBucketCacheRunner.run(cid, "ts", BERLIN, Granularity.HOUR, () -> {
            dryReads.incrementAndGet();
            return 0L;
        });
        assertTrue(dryReads.get() > 5, "clock reads=" + dryReads.get());
        // Every clock read but the last is a gate; the last one only measures a finished operation.
        for (long jumpAt = 2; jumpAt < dryReads.get(); jumpAt++) {
            long at = jumpAt;
            AtomicLong reads = new AtomicLong();
            MeasurementException e = assertThrows(MeasurementException.class,
                    () -> CalendarBucketCacheRunner.run(cid, "ts", BERLIN, Granularity.HOUR,
                            () -> reads.incrementAndGet() >= at ? BUDGET_NANOS + 1 : 0L),
                    "jump at clock read " + at);
            assertEquals(OperationGuard.TIME_BUDGET_EXCEEDED, e.code(), "jump at clock read " + at);
        }
    }

    @Test
    void deadlineDuringOutputCreation_neverMakesTheArtifactVisible() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = TabularArtifactHub.store(table(10));
        AtomicLong clock = new AtomicLong();
        cache.onNextCreates(() -> clock.set(BUDGET_NANOS + 1));
        MeasurementException e = assertThrows(MeasurementException.class,
                () -> CalendarBucketCacheRunner.run(cid, "ts", BERLIN, Granularity.DAY, clock::get));
        assertEquals(OperationGuard.TIME_BUDGET_EXCEEDED, e.code());
        assertEquals(0, cache.publicationsSinceArmed(), "the staged table was aborted, not published");
    }

    @Test
    void interruptDuringOutputCreation_neverMakesTheArtifactVisible() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = TabularArtifactHub.store(table(10));
        cache.onNextCreates(() -> Thread.currentThread().interrupt());
        try {
            MeasurementException e = assertThrows(MeasurementException.class,
                    () -> CalendarBucketCacheRunner.run(cid, "ts", BERLIN, Granularity.DAY));
            assertEquals(OperationGuard.CANCELLED, e.code());
            assertTrue(Thread.currentThread().isInterrupted(), "the flag is left for the caller");
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, cache.publicationsSinceArmed());
    }

    /** Representative scale through the real cache path, at the row limit and one past it. */
    @Test
    void rowLimit_isInclusiveAtTheCap_andRefusesOneMore() throws Exception {
        int cap = CalendarBucketCacheRunner.MAX_ROWS;
        String capped = TabularArtifactHub.store(table(cap));
        long start = System.nanoTime();
        MeasurementRunResult run = CalendarBucketCacheRunner.run(capped, "ts", BERLIN, Granularity.HOUR);
        long millis = (System.nanoTime() - start) / 1_000_000L;
        assertNotNull(run.findingCacheId());
        assertEquals(Integer.toString(cap), run.envelope().metrics().get("assignedRows"));
        System.out.println("CalendarBucket scale: " + cap + " rows, hour buckets, distinctBuckets="
                + run.envelope().metrics().get("distinctBuckets") + ", wallMillis=" + millis);
        assertTrue(millis < BudgetVector.defaultsForTabular().maxWallTimeMillis());

        String over = TabularArtifactHub.store(table(cap + 1));
        MeasurementException e = assertThrows(MeasurementException.class,
                () -> CalendarBucketCacheRunner.run(over, "ts", BERLIN, Granularity.DAY));
        assertEquals(CalendarBucketCacheRunner.INPUT_TOO_LARGE, e.code());
    }

    private static InfoTable table(int n) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("ts", BaseTypes.DATETIME));
        shape.addFieldDefinition(field("v", BaseTypes.NUMBER));
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < n; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("ts", new DatetimePrimitive(new DateTime(T0.plusSeconds(i * 60L).toEpochMilli())));
            vc.put("v", new NumberPrimitive((double) (i % 97)));
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
