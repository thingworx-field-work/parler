package com.thingworx.things.agent.transform.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.transform.time.CounterDelta.Arithmetic;
import com.thingworx.things.agent.transform.time.CounterDelta.Classification;
import com.thingworx.things.agent.transform.time.CounterDelta.CounterDeltaException;
import com.thingworx.things.agent.transform.time.CounterDelta.Reading;
import com.thingworx.things.agent.transform.time.CounterDelta.Result;
import com.thingworx.things.agent.transform.time.CounterDelta.Rules;

/** CF-05 kernel: reference cases from {@code computing-enhancement/counter-delta-reference.json} plus domain rules. */
class CounterDeltaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-03-02T06:00:00Z");
    private static final double TWO_53 = 9_007_199_254_740_992d;

    @TestFactory
    Stream<DynamicTest> referenceCases() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/computing-enhancement/counter-delta-reference.json")) {
            root = MAPPER.readTree(in);
        }
        assertEquals(CounterDelta.METHOD_ID, root.path("method").asText());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> {
                List<Reading> readings = new ArrayList<>();
                long ordinal = 0L;
                for (JsonNode r : c.path("readings")) {
                    readings.add(new Reading("", T0.plusSeconds(r.get(0).asLong()), ordinal++, r.get(1).asDouble()));
                }
                Result out = CounterDelta.compute(readings, rules(c.path("rules")));
                JsonNode expected = c.path("expected");
                assertEquals(expected.size(), out.segments().size(), "segment count");
                for (int i = 0; i < expected.size(); i++) {
                    CounterDelta.Segment s = out.segments().get(i);
                    assertEquals(expected.get(i).get(0).asText(), s.classification().name(), "segment " + i);
                    if (expected.get(i).get(1).isNull()) {
                        assertNull(s.delta(), "segment " + i + " must carry no increment");
                    } else {
                        assertEquals(expected.get(i).get(1).asDouble(), s.delta(), 0d, "segment " + i);
                    }
                    assertEquals(s.classification().lowerBound(), s.lowerBound());
                }
                assertEquals(c.path("knownDelta").asDouble(), out.knownDelta(), 0d, "knownDelta");
                assertEquals(c.path("lowerBoundDelta").asDouble(), out.lowerBoundDelta(), 0d, "lowerBoundDelta");
            }));
        }
        assertTrue(tests.size() >= 20);
        return tests.stream();
    }

    @Test
    void conflictBarrier_neverPublishesTheReconnectedFifty() {
        Result out = CounterDelta.compute(List.of(
                reading(0, 100), reading(30, 200), reading(30, 10), reading(60, 150)), new Rules(null, null, null, 0d));
        assertEquals(1, out.conflictInstants());
        assertEquals(2, out.count(Classification.BROKEN_BY_CONFLICT));
        assertNull(out.segments().get(0).endReading(), "a conflict instant has no usable reading");
        assertNull(out.segments().get(1).startReading());
        assertEquals(0, out.segmentsWithDelta());
    }

    @Test
    void readingsAtOrAbove2Pow53_orNegative_failTheWholeRequest() {
        // 2^53+1 is cached as exactly 2^53: a "greater than" gate would accept it and report 1 for a true 2.
        assertEquals(TWO_53, (double) 9_007_199_254_740_993L, 0d);
        for (double bad : new double[] {TWO_53, TWO_53 * 2, -1d}) {
            CounterDeltaException e = assertThrows(CounterDeltaException.class,
                    () -> CounterDelta.compute(List.of(reading(0, 1), reading(10, bad)), Rules.none()));
            assertEquals(CounterDelta.DOMAIN_UNSUPPORTED, e.code());
        }
    }

    @Test
    void largeIntegersBelow2Pow53_subtractExactly() {
        Result out = CounterDelta.compute(List.of(
                reading(0, 9_007_199_254_740_000d), reading(10, 9_007_199_254_740_991d)), Rules.none());
        assertEquals(Arithmetic.EXACT_INTEGER, out.arithmetic());
        assertEquals(991d, out.knownDelta(), 0d);
    }

    @Test
    void fractionalReadingsOrRules_useFloat64() {
        assertEquals(Arithmetic.FLOAT64,
                CounterDelta.compute(List.of(reading(0, 10.5), reading(10, 12.25)), Rules.none()).arithmetic());
        assertEquals(Arithmetic.FLOAT64, CounterDelta.compute(List.of(reading(0, 10), reading(10, 12)),
                new Rules(null, null, null, 0.5)).arithmetic());
    }

    @Test
    void exactRunningTotalReaching2Pow53_isRejected() {
        double step = 4_503_599_627_370_496d; // 2^52: two NORMAL segments of this size sum to 2^53
        List<Reading> readings = List.of(reading(0, 0), reading(10, step), reading(20, 0), reading(30, step));
        CounterDeltaException e = assertThrows(CounterDeltaException.class,
                () -> CounterDelta.compute(readings, new Rules(null, null, null, 0d)));
        assertEquals(CounterDelta.DOMAIN_UNSUPPORTED, e.code());
    }

    @Test
    void ruleValidation() {
        assertRule(() -> new Rules(100d, null, null, null)); // modulus without a rate
        assertRule(() -> new Rules(100d, 1d, null, 0d)); // modulus with a baseline
        assertRule(() -> new Rules(0d, 1d, null, null));
        assertRule(() -> new Rules(null, -1d, null, null));
        assertRule(() -> new Rules(null, Double.NaN, null, null));
        assertRule(() -> new Rules(null, null, 0L, null));
        assertRule(() -> new Rules(null, null, null, -1d));
        assertRule(() -> new Rules(TWO_53 * 2, 1d, null, null));
    }

    @Test
    void readingsThatContradictTheDeclaredRule_failTheWholeRequest() {
        CounterDeltaException atModulus = assertThrows(CounterDeltaException.class, () -> CounterDelta.compute(
                List.of(reading(0, 10), reading(10, 100)), new Rules(100d, 2d, null, null)));
        assertEquals(CounterDelta.RULE_CONTRADICTED, atModulus.code());
        CounterDeltaException belowBaseline = assertThrows(CounterDeltaException.class, () -> CounterDelta.compute(
                List.of(reading(0, 10), reading(10, 4)), new Rules(null, null, null, 5d)));
        assertEquals(CounterDelta.RULE_CONTRADICTED, belowBaseline.code());
    }

    @Test
    void partitionsAreIndependent_andCapped() {
        Result out = CounterDelta.compute(List.of(
                new Reading("A", T0, 0, 100), new Reading("B", T0, 1, 5),
                new Reading("A", T0.plusSeconds(60), 2, 130), new Reading("B", T0.plusSeconds(60), 3, 9)),
                Rules.none());
        assertEquals(2, out.partitions());
        assertEquals(34d, out.knownDelta(), 0d);
        assertNull(out.firstReading(), "no table-wide first/last time for several entities");
        List<Reading> many = new ArrayList<>();
        for (int i = 0; i <= CounterDelta.MAX_PARTITIONS; i++) {
            many.add(new Reading("E" + i, T0, i, 1));
        }
        assertEquals(CounterDelta.TOO_MANY_PARTITIONS,
                assertThrows(CounterDeltaException.class, () -> CounterDelta.compute(many, Rules.none())).code());
    }

    @Test
    void noReadings_yieldNoSegments() {
        Result out = CounterDelta.compute(List.of(), Rules.none());
        assertTrue(out.segments().isEmpty());
        assertEquals(0, out.partitions());
    }

    private static Rules rules(JsonNode r) {
        return new Rules(
                r.has("counterModulus") ? r.get("counterModulus").asDouble() : null,
                r.has("maxRatePerSecond") ? r.get("maxRatePerSecond").asDouble() : null,
                r.has("maxGapSeconds") ? r.get("maxGapSeconds").asLong() : null,
                r.has("resetBaseline") ? r.get("resetBaseline").asDouble() : null);
    }

    private static void assertRule(org.junit.jupiter.api.function.Executable e) {
        assertEquals(CounterDelta.RULE_INVALID, assertThrows(CounterDeltaException.class, e).code());
    }

    private static Reading reading(long seconds, double value) {
        return new Reading("", T0.plusSeconds(seconds), seconds, value);
    }
}
