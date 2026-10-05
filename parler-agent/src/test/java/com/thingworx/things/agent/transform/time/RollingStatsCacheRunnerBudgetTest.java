package com.thingworx.things.agent.transform.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
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
import com.thingworx.things.agent.transform.time.RollingOperator.WindowKind;
import com.thingworx.things.agent.transform.time.RollingStats.Config;
import com.thingworx.things.agent.transform.time.RollingStats.Statistic;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;

/** CF-52 operation-wide resources: one deadline through publication, and the limits at their boundary. */
class RollingStatsCacheRunnerBudgetTest {

    private static final Instant T0 = Instant.parse("2026-03-02T06:00:00Z");
    private static final HalfOpenWindow WINDOW = HalfOpenWindow.of(T0, T0.plusSeconds(400_000));
    private static final Config SUM_OF_5 = new Config(Statistic.SUM, WindowKind.OBSERVATION_COUNT, 5, null, 1);
    private static final long BUDGET_NANOS = BudgetVector.defaultsForTabular().maxWallTimeMillis() * 1_000_000L;

    RollingStatsCacheRunnerBudgetTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("ce2-rolling-stats-budget");
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void deadlineDuringReadingOrComputing_isATypedFailure() throws Exception {
        String cid = TabularArtifactHub.store(table(2_000));
        for (int jumpAt : new int[] {3, 8}) {
            AtomicLong reads = new AtomicLong();
            MeasurementException e = assertThrows(MeasurementException.class, () -> RollingStatsCacheRunner.run(
                    cid, "ts", "v", null, WINDOW, SUM_OF_5,
                    () -> reads.incrementAndGet() >= jumpAt ? BUDGET_NANOS + 1 : 0L));
            assertEquals(OperationGuard.TIME_BUDGET_EXCEEDED, e.code(), "jump at clock read " + jumpAt);
        }
    }

    @Test
    void deadlineDuringOutputCreation_neverMakesTheArtifactVisible() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = TabularArtifactHub.store(table(10));
        AtomicLong clock = new AtomicLong();
        cache.onNextCreates(() -> clock.set(BUDGET_NANOS + 1));
        MeasurementException e = assertThrows(MeasurementException.class,
                () -> RollingStatsCacheRunner.run(cid, "ts", "v", null, WINDOW, SUM_OF_5, clock::get));
        assertEquals(OperationGuard.TIME_BUDGET_EXCEEDED, e.code());
        assertEquals(0, cache.publicationsSinceArmed(), "the staged table was aborted, not published");
    }

    /** Representative scale through the real cache path, at the reading limit and one past it. */
    @Test
    void readingLimit_isInclusiveAtTheCap_andRefusesOneMore() throws Exception {
        HalfOpenWindow wide = HalfOpenWindow.of(T0, T0.plusSeconds(MeasurementSeriesReader.MAX_OBSERVATIONS + 10L));
        String capped = TabularArtifactHub.store(table(MeasurementSeriesReader.MAX_OBSERVATIONS));
        long start = System.nanoTime();
        MeasurementRunResult run = RollingStatsCacheRunner.run(capped, "ts", "v", null, wide,
                new Config(Statistic.STDDEV, WindowKind.OBSERVATION_COUNT, 50, null, 2));
        long millis = (System.nanoTime() - start) / 1_000_000L;
        assertNotNull(run.findingCacheId());
        assertEquals("4998775", run.envelope().metrics().get("windowWork"));
        assertEquals(MeasurementSeriesReader.MAX_OBSERVATIONS - 1, run.envelope().evidence().n());
        System.out.println("RollingStats scale: 100000 records, window 50, stddev, windowWork=4998775, wallMillis="
                + millis);
        assertTrue(millis < BudgetVector.defaultsForTabular().maxWallTimeMillis());

        String over = TabularArtifactHub.store(table(MeasurementSeriesReader.MAX_OBSERVATIONS + 1));
        MeasurementException e = assertThrows(MeasurementException.class,
                () -> RollingStatsCacheRunner.run(over, "ts", "v", null, wide, SUM_OF_5));
        assertEquals(MeasurementSeriesReader.INPUT_TOO_LARGE, e.code());
    }

    private static InfoTable table(int n) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("ts", BaseTypes.DATETIME));
        shape.addFieldDefinition(field("v", BaseTypes.NUMBER));
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < n; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("ts", new DatetimePrimitive(new DateTime(T0.plusSeconds(i).toEpochMilli())));
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
