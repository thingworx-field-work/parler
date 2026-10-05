package com.thingworx.things.agent.transform.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
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
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral.Config;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral.Coverage;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral.Method;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral.Reading;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral.Result;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral.Segment;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral.Unit;

/** CF-01 kernel: reference cases from {@code computing-enhancement/time-weighted-reference.json} plus limits. */
class TimeWeightedIntegralTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-03-02T06:00:00Z");
    private static final double RELATIVE_TOLERANCE = 1e-12;

    @TestFactory
    Stream<DynamicTest> referenceCases() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/computing-enhancement/time-weighted-reference.json")) {
            root = MAPPER.readTree(in);
        }
        assertEquals(TimeWeightedIntegral.METHOD_ID, root.path("method").asText());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> check(c)));
        }
        assertTrue(tests.size() >= 30);
        return tests.stream();
    }

    private static void check(JsonNode c) {
        List<Reading> readings = new ArrayList<>();
        long ordinal = 0L;
        for (JsonNode r : c.path("readings")) {
            readings.add(new Reading(T0.plusMillis(r.get(0).asLong()), ordinal++, r.get(1).asDouble()));
        }
        HalfOpenWindow window = HalfOpenWindow.of(T0.plusMillis(c.path("window").get(0).asLong()),
                T0.plusMillis(c.path("window").get(1).asLong()));
        Config config = new Config(Method.fromWire(c.path("method").asText()), c.path("maxGapSeconds").asLong(),
                Unit.fromWire(c.path("timeUnit").asText()));
        Result out = TimeWeightedIntegral.compute(readings, window, config, !c.path("limited").asBoolean(false),
                () -> { });

        if (c.has("segments")) {
            JsonNode expected = c.path("segments");
            assertEquals(expected.size(), out.segments().size(), "segment count");
            for (int i = 0; i < expected.size(); i++) {
                JsonNode e = expected.get(i);
                Segment s = out.segments().get(i);
                assertEquals(T0.plusMillis(e.get(0).asLong()), s.start(), "segment " + i + " start");
                assertEquals(T0.plusMillis(e.get(1).asLong()), s.end(), "segment " + i + " end");
                assertEquals(e.get(2).asText(), s.coverage().name(), "segment " + i + " coverage");
                if (e.get(3).isNull()) {
                    assertNull(s.integral(), "segment " + i + " carries no integral");
                    assertNull(s.supportedDuration(), "segment " + i + " carries no supported duration");
                    assertNull(s.startValue());
                } else {
                    assertClose(e.get(3).asDouble(), s.integral(), "segment " + i + " integral");
                    assertClose(config.unit().of(s.millis()), s.supportedDuration(), "segment " + i + " duration");
                }
            }
        } else {
            assertEquals(c.path("segmentCount").asInt(), out.segments().size(), "segment count");
        }
        assertClose(c.path("observedIntegral").asDouble(), out.observedIntegral(), "observedIntegral");
        assertClose(c.path("estimatedIntegral").asDouble(), out.estimatedIntegral(), "estimatedIntegral");
        assertClose(c.path("observedIntegral").asDouble() + c.path("estimatedIntegral").asDouble(), out.integral(),
                "integral");
        if (c.path("mean").isNull()) {
            assertNull(out.timeWeightedMean(), "no covered time: the mean is absent, not 0");
            assertEquals(0L, out.coveredMillis());
        } else {
            assertNotNull(out.timeWeightedMean());
            assertClose(c.path("mean").asDouble(), out.timeWeightedMean(), "mean");
        }
        assertEquals(out.windowMillis(), out.observedMillis() + out.estimatedMillis() + out.unknownMillis(),
                "the three durations add up to the window, to the millisecond");
        long fromSegments = 0L;
        for (Segment s : out.segments()) {
            fromSegments += s.millis();
        }
        assertEquals(out.windowMillis(), fromSegments, "segments tile the window");
        assertEquals(out.estimatedMillis() > 0L, out.containsEstimate());
        if (c.has("observedMs")) {
            assertEquals(c.path("observedMs").asLong(), out.observedMillis());
        }
        if (c.has("estimatedMs")) {
            assertEquals(c.path("estimatedMs").asLong(), out.estimatedMillis());
        }
        if (c.has("unknownMs")) {
            assertEquals(c.path("unknownMs").asLong(), out.unknownMillis());
        }
        if (c.has("cancelledMs")) {
            assertEquals(c.path("cancelledMs").asLong(), out.cancelledTailHoldMillis());
        }
        if (c.has("evidence")) {
            assertEquals(c.path("evidence").asBoolean(), out.hasEvidence());
        }
        if (c.has("readingsInWindow")) {
            assertEquals(c.path("readingsInWindow").asInt(), out.readingsInWindow());
        }
        assertEquals(c.path("duplicates").asLong(0L), out.duplicateRowsCollapsed());
        assertEquals(c.path("conflicts").asLong(0L), out.conflictInstants());
    }

    /** Acceptance 9: the integral scales by 60 and 3,600 with the unit; the mean does not move. */
    @Test
    void timeUnit_scalesTheIntegral_notTheMean() {
        List<Reading> readings = List.of(reading(0, 1.5), reading(1_800_000, 4.25), reading(3_600_000, 2));
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plusSeconds(3_600));
        Result s = TimeWeightedIntegral.compute(readings, window, new Config(Method.TRAPEZOID, 3_600, Unit.SECONDS),
                true, () -> { });
        Result m = TimeWeightedIntegral.compute(readings, window, new Config(Method.TRAPEZOID, 3_600, Unit.MINUTES),
                true, () -> { });
        Result h = TimeWeightedIntegral.compute(readings, window, new Config(Method.TRAPEZOID, 3_600, Unit.HOURS),
                true, () -> { });
        assertClose(s.integral(), m.integral() * 60d, "minutes");
        assertClose(s.integral(), h.integral() * 3_600d, "hours");
        assertClose(s.timeWeightedMean(), m.timeWeightedMean(), "mean in minutes");
        assertClose(s.timeWeightedMean(), h.timeWeightedMean(), "mean in hours");
        assertClose(3d, h.integral(), "(1.5+4.25)/2*0.5 + (4.25+2)/2*0.5");
    }

    /** A window edge between two readings is published as that edge, to the millisecond. */
    @Test
    void windowEdgesBetweenReadings_areTheSegmentEndpoints() {
        HalfOpenWindow window = HalfOpenWindow.of(T0.plusMillis(1_234), T0.plusMillis(8_765));
        Result out = TimeWeightedIntegral.compute(List.of(reading(0, 0), reading(10_000, 10)), window,
                new Config(Method.TRAPEZOID, 10, Unit.SECONDS), true, () -> { });
        assertEquals(1, out.segments().size());
        assertEquals(window.startInclusive(), out.segments().get(0).start());
        assertEquals(window.endExclusive(), out.segments().get(0).end());
        assertClose(1.234, out.segments().get(0).startValue(), "interpolated at windowStart");
        assertClose(8.765, out.segments().get(0).endValue(), "interpolated at windowEnd");
        assertEquals(Coverage.OBSERVED, out.segments().get(0).coverage());
    }

    @Test
    void unsupportedInputs_refuseTheWholeRequest() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plusSeconds(10));
        Config config = new Config(Method.TRAPEZOID, 10, Unit.SECONDS);
        assertCode(TimeWeightedIntegral.VALUE_MAGNITUDE_UNSUPPORTED, () -> TimeWeightedIntegral.compute(
                List.of(reading(0, 1e151)), window, config, true, () -> { }));
        assertCode(TimeWeightedIntegral.VALUE_MAGNITUDE_UNSUPPORTED, () -> TimeWeightedIntegral.compute(
                List.of(reading(0, Double.NaN)), window, config, true, () -> { }));
        assertCode(MeasurementSeriesReader.TIMESTAMP_UNSUPPORTED, () -> TimeWeightedIntegral.compute(
                List.of(new Reading(T0.plusNanos(500), 0L, 1d)), window, config, true, () -> { }));
        assertCode(MeasurementSeriesReader.TIMESTAMP_UNSUPPORTED, () -> TimeWeightedIntegral.compute(
                List.of(), HalfOpenWindow.of(T0.plusNanos(1), T0.plusSeconds(10)), config, true, () -> { }));
        assertCode(MeasurementSeriesReader.TIMESTAMP_UNSUPPORTED, () -> TimeWeightedIntegral.compute(
                List.of(), HalfOpenWindow.of(T0, Instant.parse("+10000-01-01T00:00:00Z")), config, true, () -> { }));
        // The largest admitted magnitude over the longest admitted gap still gives finite numbers.
        Result big = TimeWeightedIntegral.compute(List.of(reading(0, 1e150), reading(10_000, -1e150)), window,
                config, true, () -> { });
        assertTrue(Double.isFinite(big.integral()));
    }

    /**
     * A representable integral must not be lost in an intermediate: a per-millisecond slope, a halved endpoint
     * sum or a duration formed first can each underflow to 0. References are exact rational arithmetic on the
     * binary64 inputs.
     */
    @Test
    void tinyValues_keepTheirRepresentableIntegral_clippedAndUnclipped() {
        double tiny = 1e-320;
        Result clipped = TimeWeightedIntegral.compute(List.of(reading(0, 0), reading(10_000, tiny)),
                HalfOpenWindow.of(T0.plusSeconds(2), T0.plusSeconds(8)),
                new Config(Method.TRAPEZOID, 10, Unit.SECONDS), true, () -> { });
        double clippedReference = new BigDecimal(tiny).multiply(BigDecimal.valueOf(3)).doubleValue();
        assertTrue(clipped.integral() > 0d, "integral=" + clipped.integral());
        assertEquals(clippedReference, clipped.integral(), 4 * Double.MIN_VALUE);
        assertEquals(new BigDecimal(tiny).divide(BigDecimal.valueOf(2)).doubleValue(), clipped.timeWeightedMean(),
                2 * Double.MIN_VALUE);

        HalfOpenWindow thousand = HalfOpenWindow.of(T0, T0.plusSeconds(1_000));
        Result unclipped = TimeWeightedIntegral.compute(
                List.of(reading(0, 0), reading(1_000_000, Double.MIN_VALUE)), thousand,
                new Config(Method.TRAPEZOID, 1_000, Unit.SECONDS), true, () -> { });
        assertEquals(500 * Double.MIN_VALUE, unclipped.integral(), 0d);

        Result held = TimeWeightedIntegral.compute(List.of(reading(0, Double.MIN_VALUE)), thousand,
                new Config(Method.STEP_HOLD, 1_000, Unit.SECONDS), true, () -> { });
        assertEquals(1_000 * Double.MIN_VALUE, held.estimatedIntegral(), 0d);
        Result observedStep = TimeWeightedIntegral.compute(
                List.of(reading(0, Double.MIN_VALUE), reading(1_000_000, 0)), thousand,
                new Config(Method.STEP_HOLD, 1_000, Unit.SECONDS), true, () -> { });
        assertEquals(1_000 * Double.MIN_VALUE, observedStep.observedIntegral(), 0d);
    }

    /**
     * A clipped end value may round to 0 while the clipped area is representable: the area is formed from the
     * two readings and the offsets, never from the rounded end cells.
     */
    @Test
    void clippedEndValuesThatRoundToZero_stillGiveTheirArea() {
        List<Reading> ramp = List.of(reading(0, 0), reading(1_000_000, Double.MIN_VALUE));
        Config config = new Config(Method.TRAPEZOID, 1_000, Unit.SECONDS);
        Result head = TimeWeightedIntegral.compute(ramp, HalfOpenWindow.of(T0, T0.plusSeconds(400)), config, true,
                () -> { });
        assertEquals(0d, head.segments().get(0).endValue(), 0d, "the displayed end value does round to 0");
        assertEquals(80 * Double.MIN_VALUE, head.integral(), 0d, "0.4 * MIN / 2 * 400 s");
        assertEquals(80 * Double.MIN_VALUE, head.segments().get(0).integral(), 0d);

        Result anchorsOnly = TimeWeightedIntegral.compute(ramp,
                HalfOpenWindow.of(T0.plusSeconds(100), T0.plusSeconds(400)), config, true, () -> { });
        assertEquals(0, anchorsOnly.readingsInWindow());
        assertEquals(75 * Double.MIN_VALUE, anchorsOnly.integral(), 0d, "(0.1 + 0.4) * MIN / 2 * 300 s");

        // Descending and negative ramps, clipped at the other edge.
        Result tail = TimeWeightedIntegral.compute(
                List.of(reading(0, -Double.MIN_VALUE), reading(1_000_000, 0)),
                HalfOpenWindow.of(T0.plusSeconds(600), T0.plusSeconds(1_000)), config, true, () -> { });
        assertEquals(-80 * Double.MIN_VALUE, tail.integral(), 0d);
    }

    /**
     * The unit scales the displayed integral only. The mean is formed before that rounding, so it is the same
     * in every unit, also when the integral in a coarse unit is a coarse subnormal or rounds to 0.
     */
    @Test
    void mean_isTheSameInEveryUnit_evenWhenTheIntegralUnderflows() {
        HalfOpenWindow second = HalfOpenWindow.of(T0, T0.plusSeconds(1));
        for (Method method : Method.values()) {
            for (double value : new double[] {1e-320, Double.MIN_VALUE, -3 * Double.MIN_VALUE, 1e-300, 7.25}) {
                for (Unit unit : Unit.values()) {
                    Result out = TimeWeightedIntegral.compute(List.of(reading(0, value), reading(1_000, value)),
                            second, new Config(method, 1, unit), true, () -> { });
                    assertEquals(value, out.timeWeightedMean(), 0d, method + " " + unit + " " + value);
                    double integral = new BigDecimal(value).multiply(BigDecimal.valueOf(1_000))
                            .divide(BigDecimal.valueOf(unit == Unit.SECONDS ? 1_000L
                                    : unit == Unit.MINUTES ? 60_000L : 3_600_000L), new MathContext(60))
                            .doubleValue();
                    assertEquals(integral, out.integral(), 0d, method + " " + unit + " " + value);
                }
            }
        }
        Result underflow = TimeWeightedIntegral.compute(
                List.of(reading(0, Double.MIN_VALUE), reading(1_000, Double.MIN_VALUE)), second,
                new Config(Method.STEP_HOLD, 1, Unit.HOURS), true, () -> { });
        assertEquals(0d, underflow.integral(), 0d, "MIN / 3600 is below half of MIN: 0 is the rounded integral");
        assertEquals(Double.MIN_VALUE, underflow.timeWeightedMean(), 0d);

        // A varying series: the mean of a ramp clipped inside one interval, identical in the three units.
        List<Reading> ramp = List.of(reading(0, 0), reading(1_000_000, 1e-320));
        Double first = null;
        for (Unit unit : Unit.values()) {
            Result out = TimeWeightedIntegral.compute(ramp,
                    HalfOpenWindow.of(T0.plusSeconds(100), T0.plusSeconds(400)),
                    new Config(Method.TRAPEZOID, 1_000, unit), true, () -> { });
            double want = new BigDecimal(1e-320).multiply(new BigDecimal("0.25")).doubleValue();
            assertEquals(want, out.timeWeightedMean(), 0d, unit.wireName());
            first = first == null ? out.timeWeightedMean() : first;
            assertEquals(first, out.timeWeightedMean(), 0d);
        }
    }

    /**
     * Large segments that cancel must leave their exact residual: areas are exact rationals, so no term is
     * rounded before the sum. (A, 1, −A) integrates to 1 with mean 0.5 for every A.
     */
    @Test
    void cancellingSegments_keepTheirExactResidual() {
        for (double a : new double[] {1e16, 1e60, 1e150, -1e150}) {
            List<Reading> readings = List.of(reading(0, a), reading(1_000, 1), reading(2_000, -a));
            for (Unit unit : Unit.values()) {
                Result out = TimeWeightedIntegral.compute(readings, HalfOpenWindow.of(T0, T0.plusSeconds(2)),
                        new Config(Method.TRAPEZOID, 1, unit), true, () -> { });
                double perUnit = unit == Unit.SECONDS ? 1d : unit == Unit.MINUTES ? 60d : 3_600d;
                assertClose(1d / perUnit, out.integral(), a + " " + unit);
                assertEquals(0.5, out.timeWeightedMean(), 0d, a + " " + unit);
            }
            // Clipped at both edges with unequal elapsed times: exact reference (1 + 1/3·... ) from rationals.
            List<Reading> uneven = List.of(reading(-700, a), reading(300, 1), reading(1_000, -a), reading(1_900, 2));
            Result clipped = TimeWeightedIntegral.compute(uneven,
                    HalfOpenWindow.of(T0, T0.plusMillis(1_500)), new Config(Method.TRAPEZOID, 1, Unit.SECONDS), true,
                    () -> { });
            BigDecimal reference = exactTrapezoid(uneven, 0, 1_500).divide(BigDecimal.valueOf(1_000),
                    new MathContext(60));
            assertClose(reference.doubleValue(), clipped.integral(), "clipped " + a);
            assertClose(reference.doubleValue() / 1.5, clipped.timeWeightedMean(), "clipped mean " + a);
        }
        // Step holds that cancel: +A for one second, −A for one second, then 3 for one second.
        Result step = TimeWeightedIntegral.compute(
                List.of(reading(0, 1e150), reading(1_000, -1e150), reading(2_000, 3), reading(3_000, 3)),
                HalfOpenWindow.of(T0, T0.plusSeconds(3)), new Config(Method.STEP_HOLD, 1, Unit.SECONDS), true,
                () -> { });
        assertEquals(3d, step.integral(), 0d);
        assertEquals(1d, step.timeWeightedMean(), 0d);
    }

    /** Exact area in value × milliseconds of the piecewise-linear series inside [from, to), as a rational. */
    private static BigDecimal exactTrapezoid(List<Reading> readings, long from, long to) {
        BigDecimal total = BigDecimal.ZERO;
        MathContext wide = new MathContext(400);
        for (int i = 0; i + 1 < readings.size(); i++) {
            long a = readings.get(i).instant.toEpochMilli() - T0.toEpochMilli();
            long b = readings.get(i + 1).instant.toEpochMilli() - T0.toEpochMilli();
            long lo = Math.max(a, from);
            long hi = Math.min(b, to);
            if (hi <= lo) {
                continue;
            }
            BigDecimal va = new BigDecimal(readings.get(i).value);
            BigDecimal vb = new BigDecimal(readings.get(i + 1).value);
            BigDecimal weighted = va.multiply(BigDecimal.valueOf(2 * (b - a) - (lo - a) - (hi - a)))
                    .add(vb.multiply(BigDecimal.valueOf((lo - a) + (hi - a))));
            total = total.add(weighted.multiply(BigDecimal.valueOf(hi - lo))
                    .divide(BigDecimal.valueOf(2 * (b - a)), wide));
        }
        return total;
    }

    /** The same ordering keeps the top of the admitted domain finite over the longest admitted window. */
    @Test
    void largestValuesOverTheLongestWindow_stayFiniteAndExact() {
        Instant start = Instant.parse("0001-01-01T00:00:00Z");
        Instant end = Instant.parse("9999-12-31T23:59:59.999Z");
        List<Reading> readings = List.of(new Reading(start, 0L, 1e150), new Reading(end, 1L, 1e150));
        for (Unit unit : Unit.values()) {
            Result out = TimeWeightedIntegral.compute(readings, HalfOpenWindow.of(start, end),
                    new Config(Method.TRAPEZOID, TimeWeightedIntegral.MAX_GAP_SECONDS, unit), true, () -> { });
            BigDecimal reference = new BigDecimal(1e150)
                    .multiply(BigDecimal.valueOf(end.toEpochMilli() - start.toEpochMilli()))
                    .divide(BigDecimal.valueOf(unit == Unit.SECONDS ? 1_000L : unit == Unit.MINUTES ? 60_000L
                            : 3_600_000L), MathContext.DECIMAL128);
            assertClose(reference.doubleValue(), out.integral(), unit.wireName());
            assertClose(1e150, out.timeWeightedMean(), unit.wireName() + " mean");
        }
    }

    /** Independent reference over mixed magnitudes, both methods, clipped at both window edges. */
    @Test
    void randomSeries_matchAnExactRationalReference() {
        Random random = new Random(20260920L);
        for (int round = 0; round < 200; round++) {
            int n = 2 + random.nextInt(12);
            List<Reading> readings = new ArrayList<>();
            long t = -5_000L;
            double scale = Math.pow(10, random.nextInt(470) - 320);
            for (int i = 0; i < n; i++) {
                t += 1 + random.nextInt(9_000);
                readings.add(reading(t, (random.nextDouble() - 0.5) * scale));
            }
            long windowStart = random.nextInt(3_000);
            long windowEnd = windowStart + 1 + random.nextInt((int) Math.max(1, t - windowStart));
            HalfOpenWindow window = HalfOpenWindow.of(T0.plusMillis(windowStart), T0.plusMillis(windowEnd));
            for (Method method : Method.values()) {
                Result out = TimeWeightedIntegral.compute(readings, window, new Config(method, 100, Unit.MINUTES),
                        true, () -> { });
                BigDecimal reference = BigDecimal.ZERO;
                BigDecimal magnitude = BigDecimal.ZERO;
                // Gap of 100 s exceeds every interval here, so every stretch between two readings is OBSERVED.
                for (int i = 0; i + 1 < n; i++) {
                    long a = readings.get(i).instant.toEpochMilli() - T0.toEpochMilli();
                    long b = readings.get(i + 1).instant.toEpochMilli() - T0.toEpochMilli();
                    long from = Math.max(a, windowStart);
                    long to = Math.min(b, windowEnd);
                    if (to <= from) {
                        continue;
                    }
                    BigDecimal va = new BigDecimal(readings.get(i).value);
                    BigDecimal vb = new BigDecimal(readings.get(i + 1).value);
                    BigDecimal twiceMeanTimesSpan = method == Method.STEP_HOLD
                            ? va.multiply(BigDecimal.valueOf(2L * (b - a)))
                            : va.multiply(BigDecimal.valueOf(2L * (b - a) - (from - a) - (to - a)))
                                    .add(vb.multiply(BigDecimal.valueOf((from - a) + (to - a))));
                    BigDecimal term = twiceMeanTimesSpan.multiply(BigDecimal.valueOf(to - from))
                            .divide(BigDecimal.valueOf(2L * (b - a) * 60_000L), new MathContext(60));
                    reference = reference.add(term);
                    magnitude = magnitude.add(term.abs());
                }
                double want = reference.doubleValue();
                assertEquals(want, out.observedIntegral(), Math.max(magnitude.doubleValue() * 1e-12, 64 * Double.MIN_VALUE),
                        "round " + round + " " + method);
            }
        }
    }

    @Test
    void configValues_areValidated() {
        assertCode(TimeWeightedIntegral.MAX_GAP_INVALID, () -> new Config(Method.STEP_HOLD, 0, Unit.SECONDS));
        assertCode(TimeWeightedIntegral.MAX_GAP_INVALID,
                () -> new Config(Method.STEP_HOLD, TimeWeightedIntegral.MAX_GAP_SECONDS + 1, Unit.SECONDS));
        assertCode(TimeWeightedIntegral.INTEGRATION_METHOD_INVALID, () -> Method.fromWire("linear"));
        assertCode(TimeWeightedIntegral.INTEGRATION_METHOD_INVALID, () -> Method.fromWire(null));
        assertCode(TimeWeightedIntegral.TIME_UNIT_INVALID, () -> Unit.fromWire("days"));
        // The largest gap does not overflow the hold arithmetic.
        Result out = TimeWeightedIntegral.compute(List.of(reading(0, 2)),
                HalfOpenWindow.of(T0, T0.plusSeconds(10)),
                new Config(Method.STEP_HOLD, TimeWeightedIntegral.MAX_GAP_SECONDS, Unit.SECONDS), true, () -> { });
        assertEquals(10_000L, out.estimatedMillis());
    }

    @Test
    void abortCheck_runsDuringTheComputation() {
        List<Reading> readings = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            readings.add(reading(i * 1_000L, i % 7));
        }
        AtomicLong checks = new AtomicLong();
        TimeWeightedIntegral.compute(readings, HalfOpenWindow.of(T0, T0.plusSeconds(5_000)),
                new Config(Method.TRAPEZOID, 10, Unit.SECONDS), true, checks::incrementAndGet);
        assertTrue(checks.get() >= 8, "checks=" + checks.get());
        assertThrows(IllegalStateException.class, () -> TimeWeightedIntegral.compute(readings,
                HalfOpenWindow.of(T0, T0.plusSeconds(5_000)), new Config(Method.TRAPEZOID, 10, Unit.SECONDS), true,
                () -> {
                    throw new IllegalStateException("stop");
                }));
    }

    private static Reading reading(long millisFromStart, double value) {
        return new Reading(T0.plusMillis(millisFromStart), millisFromStart, value);
    }

    private static void assertClose(double expected, double actual, String what) {
        assertEquals(expected, actual, Math.abs(expected) * RELATIVE_TOLERANCE, what);
    }

    private static void assertCode(String code, Runnable r) {
        MeasurementException e = assertThrows(MeasurementException.class, r::run);
        assertEquals(code, e.code());
    }
}
