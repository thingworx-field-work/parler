package com.thingworx.things.agent.transform.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.transform.time.RollingOperator.WindowKind;
import com.thingworx.things.agent.transform.time.RollingStats.Config;
import com.thingworx.things.agent.transform.time.RollingStats.Record;
import com.thingworx.things.agent.transform.time.RollingStats.Result;
import com.thingworx.things.agent.transform.time.RollingStats.Statistic;

/** CF-52 kernel: reference cases from {@code computing-enhancement/rolling-stats-reference.json} plus limits. */
class RollingStatsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-03-02T06:00:00Z");

    @TestFactory
    Stream<DynamicTest> referenceCases() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/computing-enhancement/rolling-stats-reference.json")) {
            root = MAPPER.readTree(in);
        }
        assertEquals(RollingStats.METHOD_ID, root.path("method").asText());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> {
                List<Record> records = new ArrayList<>();
                long ordinal = 0L;
                for (JsonNode r : c.path("records")) {
                    records.add(new Record("", T0.plusSeconds(r.get(0).asLong()), ordinal++,
                            r.get(1).isNull() ? null : r.get(1).asDouble()));
                }
                Config config = new Config(Statistic.fromWire(c.path("statistic").asText()),
                        WindowKind.valueOf(c.path("kind").asText()), c.path("observationWindow").asInt(5),
                        Duration.ofSeconds(c.path("durationWindowSeconds").asLong(3600)),
                        c.path("minSupport").asInt(1));
                Result out = RollingStats.compute(records, config);
                JsonNode expected = c.path("expected");
                assertEquals(expected.size(), out.rows().size(), "row count");
                double tolerance = c.path("relativeTolerance").asDouble(0d);
                for (int i = 0; i < expected.size(); i++) {
                    JsonNode e = expected.get(i);
                    RollingStats.Row row = out.rows().get(i);
                    if (e.get(0).isNull()) {
                        assertNull(row.value(), "row " + i + " must carry no value");
                    } else {
                        double want = e.get(0).asDouble();
                        assertEquals(want, row.value(), Math.abs(want) * tolerance, "row " + i);
                    }
                    assertEquals(e.get(1).asText(), row.valueStatus().name(), "row " + i + " status");
                    assertEquals(e.get(2).asInt(), row.support(), "row " + i + " support");
                    assertEquals(e.get(3).asInt(), row.records(), "row " + i + " records");
                    assertEquals(e.get(4).asBoolean(), row.warmedUp(), "row " + i + " warmedUp");
                }
            }));
        }
        assertTrue(tests.size() >= 25);
        return tests.stream();
    }

    /** The stable-prefix tie rule is the existing rolling mean's membership, not a new one. */
    @Test
    void elapsedMembership_agreesWithTheExistingRollingOperator() {
        long[][] points = {{0, 1}, {60, 3}, {60, 5}, {90, 7}, {200, 11}};
        List<TimePoint> forOperator = new ArrayList<>();
        List<Record> forStats = new ArrayList<>();
        for (int i = 0; i < points.length; i++) {
            forOperator.add(new TimePoint(T0.plusSeconds(points[i][0]), i, (double) points[i][1]));
            forStats.add(new Record("", T0.plusSeconds(points[i][0]), i, (double) points[i][1]));
        }
        List<BucketedValue> legacy = RollingOperator.rollingMean(
                forOperator, WindowKind.ELAPSED_DURATION, 0, Duration.ofSeconds(60), 1);
        Result stats = RollingStats.compute(forStats,
                new Config(Statistic.MEAN, WindowKind.ELAPSED_DURATION, 5, Duration.ofSeconds(60), 1));
        for (int i = 0; i < points.length; i++) {
            assertEquals(legacy.get(i).value(), stats.rows().get(i).value(), 1e-12, "row " + i);
            assertEquals(legacy.get(i).support(), stats.rows().get(i).support(), "row " + i);
        }
    }

    @Test
    void oneMillisecondBeforeTheEdge_isNotAMember() {
        Result out = RollingStats.compute(List.of(
                new Record("", T0.minusMillis(1), 0, 1d), new Record("", T0.plusSeconds(60), 1, 3d)),
                new Config(Statistic.COUNT_RECORDS, WindowKind.ELAPSED_DURATION, 5, Duration.ofSeconds(60), 1));
        assertEquals(1d, out.rows().get(1).value(), 0d);
    }

    /** An admitted duration whose nominal left edge is not a representable Instant: no overflow. */
    @Test
    void hugeElapsedDuration_holdsEveryEarlierRecord_andNeverWarmsUp() {
        List<Record> records = List.of(record(0, 1d), record(60, 3d));
        Result viaRollingStats = RollingStats.compute(records, new Config(Statistic.MEAN,
                WindowKind.ELAPSED_DURATION, 5, Duration.ofSeconds(Long.MAX_VALUE), 1));
        assertEquals(1d, viaRollingStats.rows().get(0).value(), 0d);
        assertEquals(2d, viaRollingStats.rows().get(1).value(), 0d);
        assertEquals(2, viaRollingStats.rows().get(1).records());
        assertEquals(3L, viaRollingStats.windowWork());
        assertEquals(2, viaRollingStats.notWarmedUp(), "the frame reaches far before the first record");
        // Parity with the existing rolling mean on the same input.
        List<BucketedValue> legacy = RollingOperator.rollingMean(
                List.of(new TimePoint(T0, 0, 1d), new TimePoint(T0.plusSeconds(60), 1, 3d)),
                WindowKind.ELAPSED_DURATION, 0, Duration.ofSeconds(Long.MAX_VALUE), 1);
        assertEquals(legacy.get(1).value(), viaRollingStats.rows().get(1).value(), 0d);
    }

    @Test
    void smallestSubnormalSpread_isPositive_andTheZeroBackstopRefuses() {
        Config stddev = new Config(Statistic.STDDEV, WindowKind.OBSERVATION_COUNT, 5, null, 2);
        Result positive = RollingStats.compute(List.of(record(0, 5e-324), record(1, 0d)), stddev);
        assertTrue(positive.rows().get(1).value() > 0d);
        // Five members: the scaled result rounds to zero although the members differ.
        MeasurementException e = assertThrows(MeasurementException.class, () -> RollingStats.compute(List.of(
                record(0, 5e-324), record(1, 0d), record(2, 0d), record(3, 0d), record(4, 0d)), stddev));
        assertEquals(RollingStats.NUMERIC_RESULT_UNSUPPORTED, e.code());
    }

    @Test
    void valuesBeyondTheMagnitudeBound_failTheWholeRequest() {
        Config sum = new Config(Statistic.SUM, WindowKind.OBSERVATION_COUNT, 2, null, 1);
        assertEquals(2e150, RollingStats.compute(List.of(record(0, 1e150), record(1, 1e150)), sum)
                .rows().get(1).value(), 0d);
        for (double bad : new double[] {1.0000001e150, -2e200}) {
            MeasurementException e = assertThrows(MeasurementException.class,
                    () -> RollingStats.compute(List.of(record(0, 1d), record(1, bad)), sum));
            assertEquals(RollingStats.VALUE_MAGNITUDE_UNSUPPORTED, e.code());
        }
    }

    @Test
    void windowWork_isCountedExactly_includingRecordsWithoutAValue() {
        // 100,000 records with an observation window of 50: work is 50*51/2 + (100000-50)*50 = 4,998,775.
        int n = 100_000;
        List<Record> records = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            records.add(new Record("", T0.plusSeconds(i), i, i % 2 == 0 ? null : (double) i));
        }
        Result inside = RollingStats.compute(records,
                new Config(Statistic.COUNT_RECORDS, WindowKind.OBSERVATION_COUNT, 50, null, 1));
        assertEquals(4_998_775L, inside.windowWork());
        assertTrue(inside.windowWork() <= RollingStats.MAX_WINDOW_WORK);
        MeasurementException e = assertThrows(MeasurementException.class, () -> RollingStats.compute(records,
                new Config(Statistic.COUNT_RECORDS, WindowKind.OBSERVATION_COUNT, 51, null, 1)));
        assertEquals(RollingStats.WINDOW_WORK_TOO_LARGE, e.code());

        // A huge nominal elapsed window over sparse records needs little real work and is not refused.
        List<Record> sparse = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            sparse.add(new Record("", T0.plusSeconds(i * 86_400L * 400), i, 1d));
        }
        Result sparseOut = RollingStats.compute(sparse,
                new Config(Statistic.SUM, WindowKind.ELAPSED_DURATION, 5, Duration.ofDays(365), 1));
        assertEquals(1_000L, sparseOut.windowWork());
    }

    @Test
    void partitionsRollIndependently_andAreCapped() {
        Result out = RollingStats.compute(List.of(
                new Record("A", T0, 0, 1d), new Record("B", T0, 1, 10d),
                new Record("A", T0.plusSeconds(60), 2, 3d), new Record("B", T0.plusSeconds(60), 3, 30d)),
                new Config(Statistic.SUM, WindowKind.OBSERVATION_COUNT, 2, null, 1));
        assertEquals(2, out.partitions());
        assertEquals(List.of(1d, 4d, 10d, 40d), List.of(out.rows().get(0).value(), out.rows().get(1).value(),
                out.rows().get(2).value(), out.rows().get(3).value()));
        List<Record> many = new ArrayList<>();
        for (int i = 0; i <= RollingStats.MAX_PARTITIONS; i++) {
            many.add(new Record("E" + i, T0, i, 1d));
        }
        assertEquals(RollingStats.TOO_MANY_PARTITIONS, assertThrows(MeasurementException.class,
                () -> RollingStats.compute(many, new Config(Statistic.SUM, WindowKind.OBSERVATION_COUNT, 2, null, 1)))
                .code());
    }

    @Test
    void invalidStatisticAndWindows_failFast() {
        assertEquals(RollingStats.STATISTIC_INVALID,
                assertThrows(MeasurementException.class, () -> Statistic.fromWire("median")).code());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Statistic.SUM, WindowKind.OBSERVATION_COUNT, 0, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Statistic.SUM, WindowKind.ELAPSED_DURATION, 5, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Statistic.SUM, WindowKind.OBSERVATION_COUNT, 5, null, 0));
    }

    @Test
    void kernelStopsWhenTheAbortCheckThrows() {
        List<Record> records = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            records.add(record(i, (double) i));
        }
        AtomicLong checks = new AtomicLong();
        assertThrows(MeasurementException.class, () -> RollingStats.compute(records,
                new Config(Statistic.SUM, WindowKind.OBSERVATION_COUNT, 5, null, 1), () -> {
                    if (checks.incrementAndGet() > 3) {
                        throw new MeasurementException(OperationGuard.CANCELLED, "stop");
                    }
                }));
        assertTrue(checks.get() <= 5);
    }

    @Test
    void noRecords_yieldNoRows() {
        assertTrue(RollingStats.compute(List.of(),
                new Config(Statistic.SUM, WindowKind.OBSERVATION_COUNT, 5, null, 1)).rows().isEmpty());
    }

    /**
     * The rounded mean is only a centre. A small spread on a large offset must give the same standard deviation
     * as the same spread at zero; for two values the reference is their difference over sqrt(2).
     */
    @Test
    void stddev_isUnchangedByTranslation_andExactForAdjacentValues() {
        Config stddev = new Config(Statistic.STDDEV, WindowKind.OBSERVATION_COUNT, 2, null, 1);
        double[][] pairs = {{0d, 2d}, {1e16, 1e16 + 2d}, {-1e16, -1e16 + 2d}, {1e9, Math.nextUp(1e9)},
                {1d, Math.nextUp(1d)}, {-1e150, Math.nextUp(-1e150)}, {1e-300, Math.nextUp(1e-300)},
                {4.5e15, 4.5e15 + 1d}, {1e16 + 2d, 1e16}};
        for (double[] pair : pairs) {
            Result out = RollingStats.compute(List.of(record(0, pair[0]), record(1, pair[1])), stddev);
            double want = new BigDecimal(pair[1]).subtract(new BigDecimal(pair[0])).abs().doubleValue() / Math.sqrt(2d);
            assertEquals(want, out.rows().get(1).value(), want * 1e-15, pair[0] + ", " + pair[1]);
        }
    }

    /** Independent exact reference: windows of mixed size shifted by large positive and negative offsets. */
    @Test
    void stddev_matchesAnExactReference_underLargeOffsets() {
        Random random = new Random(20260921L);
        for (int round = 0; round < 300; round++) {
            int n = 2 + random.nextInt(9);
            double offset = (random.nextBoolean() ? 1d : -1d) * Math.pow(10, random.nextInt(17));
            double spread = Math.pow(10, random.nextInt(8) - 3);
            List<Record> records = new ArrayList<>();
            List<BigDecimal> exact = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double v = offset + Math.rint(random.nextDouble() * 1_000d) * spread;
                records.add(record(i, v));
                exact.add(new BigDecimal(v));
            }
            Result out = RollingStats.compute(records,
                    new Config(Statistic.STDDEV, WindowKind.OBSERVATION_COUNT, n, null, 1));
            BigDecimal sum = exact.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal mean = sum.divide(BigDecimal.valueOf(n), new MathContext(80));
            BigDecimal squares = exact.stream().map(x -> x.subtract(mean).pow(2)).reduce(BigDecimal.ZERO,
                    BigDecimal::add);
            double want = Math.sqrt(squares.divide(BigDecimal.valueOf(n - 1L), new MathContext(80)).doubleValue());
            RollingStats.Row last = out.rows().get(n - 1);
            if (want == 0d) {
                assertEquals(0d, last.value(), 0d, "round " + round);
            } else {
                assertEquals(want, last.value(), want * 1e-9, "round " + round + " offset " + offset);
            }
        }
    }

    private static Record record(long seconds, Double value) {
        return new Record("", T0.plusSeconds(seconds), seconds, value);
    }
}
