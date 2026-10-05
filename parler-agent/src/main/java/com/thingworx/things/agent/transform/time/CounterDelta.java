package com.thingworx.things.agent.transform.time;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CF-05 {@code counter_delta_v1}: increments of a cumulative counter between real readings. It never
 * extrapolates past a reading, never turns an unexplained negative jump into a number, and keeps exact
 * segments apart from lower bounds. Design: {@code docs/agent/nearterm/computing-enhancement.md} CE-1.
 */
public final class CounterDelta {

    public static final String METHOD_ID = "counter_delta_v1";

    /** Readings and rule values must stay below 2^53, where every integer is an exact double. */
    public static final double EXACT_INTEGER_LIMIT = 9_007_199_254_740_992d;

    public static final int MAX_PARTITIONS = 50;

    /** The abort check runs once per 1,024 readings or segments. */
    private static final int ABORT_CHECK_MASK = 0x3FF;

    public static final String RULE_INVALID = "COUNTER_RULE_INVALID";
    public static final String RULE_CONTRADICTED = "COUNTER_RULE_CONTRADICTED";
    public static final String DOMAIN_UNSUPPORTED = "COUNTER_DOMAIN_UNSUPPORTED";
    public static final String TOO_MANY_PARTITIONS = "TOO_MANY_PARTITIONS";

    public enum Classification {
        NORMAL(true, false),
        ROLLOVER(true, false),
        RESET(true, true),
        POSSIBLE_HIDDEN_RESET(true, true),
        IMPLAUSIBLE_INCREASE(false, false),
        UNCERTAIN_NEGATIVE_JUMP(false, false),
        WRAP_UNIDENTIFIABLE(false, false),
        GAP_EXCEEDED(false, false),
        BROKEN_BY_CONFLICT(false, false),
        SINGLE_READING(false, false);

        private final boolean carriesDelta;
        private final boolean lowerBound;

        Classification(boolean carriesDelta, boolean lowerBound) {
            this.carriesDelta = carriesDelta;
            this.lowerBound = lowerBound;
        }

        public boolean carriesDelta() {
            return carriesDelta;
        }

        public boolean lowerBound() {
            return lowerBound;
        }
    }

    public enum Arithmetic {
        EXACT_INTEGER,
        FLOAT64;

        public String wireName() {
            return name().toLowerCase();
        }
    }

    /** Request failure with a stable reason code. */
    public static final class CounterDeltaException extends MeasurementException {
        private static final long serialVersionUID = 1L;

        public CounterDeltaException(String code, String message) {
            super(code, message);
        }
    }

    /** Caller-supplied assumptions about the counter. Absent values are never inferred. */
    public static final class Rules {
        private final Double counterModulus;
        private final Double maxRatePerSecond;
        private final Long maxGapSeconds;
        private final Double resetBaseline;

        public Rules(Double counterModulus, Double maxRatePerSecond, Long maxGapSeconds, Double resetBaseline) {
            requireRange("counterModulus", counterModulus, false);
            requireRange("maxRatePerSecond", maxRatePerSecond, false);
            requireRange("resetBaseline", resetBaseline, true);
            if (maxGapSeconds != null && (maxGapSeconds <= 0L || maxGapSeconds > (long) EXACT_INTEGER_LIMIT)) {
                throw new CounterDeltaException(RULE_INVALID,
                        "maxGapSeconds must be a positive whole number of seconds, at most 2^53");
            }
            if (counterModulus != null && maxRatePerSecond == null) {
                throw new CounterDeltaException(RULE_INVALID,
                        "counterModulus requires maxRatePerSecond: without a rate bound the number of wraps "
                                + "between two readings cannot be decided");
            }
            if (counterModulus != null && resetBaseline != null) {
                throw new CounterDeltaException(RULE_INVALID,
                        "counterModulus and resetBaseline cannot be combined");
            }
            this.counterModulus = counterModulus;
            this.maxRatePerSecond = maxRatePerSecond;
            this.maxGapSeconds = maxGapSeconds;
            this.resetBaseline = resetBaseline;
        }

        public static Rules none() {
            return new Rules(null, null, null, null);
        }

        private static void requireRange(String name, Double v, boolean zeroAllowed) {
            if (v == null) {
                return;
            }
            boolean inRange = Double.isFinite(v) && v <= EXACT_INTEGER_LIMIT && (zeroAllowed ? v >= 0d : v > 0d);
            if (!inRange) {
                throw new CounterDeltaException(RULE_INVALID, name + " must be finite, "
                        + (zeroAllowed ? "non-negative" : "positive") + " and at most 2^53");
            }
        }

        public Double counterModulus() {
            return counterModulus;
        }

        public Double maxRatePerSecond() {
            return maxRatePerSecond;
        }

        public Long maxGapSeconds() {
            return maxGapSeconds;
        }

        public Double resetBaseline() {
            return resetBaseline;
        }
    }

    /** One usable observation: valid time, finite value, inside the analysis window. */
    public static final class Reading {
        private final String entity;
        private final Instant instant;
        private final long sourceOrdinal;
        private final double value;

        public Reading(String entity, Instant instant, long sourceOrdinal, double value) {
            this.entity = entity == null ? "" : entity;
            this.instant = Objects.requireNonNull(instant, "instant");
            this.sourceOrdinal = sourceOrdinal;
            this.value = value;
        }
    }

    public static final class Segment {
        private final String entity;
        private final Instant start;
        private final Instant end;
        private final Double startReading;
        private final Double endReading;
        private final Double delta;
        private final Classification classification;
        private final double elapsedSeconds;

        Segment(String entity, Instant start, Instant end, Double startReading, Double endReading, Double delta,
                Classification classification, double elapsedSeconds) {
            this.entity = entity;
            this.start = start;
            this.end = end;
            this.startReading = startReading;
            this.endReading = endReading;
            this.delta = delta;
            this.classification = classification;
            this.elapsedSeconds = elapsedSeconds;
        }

        public String entity() {
            return entity;
        }

        public Instant start() {
            return start;
        }

        public Instant end() {
            return end;
        }

        public Double startReading() {
            return startReading;
        }

        public Double endReading() {
            return endReading;
        }

        /** Null whenever the classification carries no increment. */
        public Double delta() {
            return delta;
        }

        public Classification classification() {
            return classification;
        }

        public boolean lowerBound() {
            return classification.lowerBound();
        }

        public double elapsedSeconds() {
            return elapsedSeconds;
        }
    }

    public static final class Result {
        private final List<Segment> segments;
        private final Map<Classification, Integer> counts;
        private final double knownDelta;
        private final double lowerBoundDelta;
        private final Arithmetic arithmetic;
        private final int partitions;
        private final long duplicateRowsCollapsed;
        private final long conflictInstants;
        private final Instant firstReading;
        private final Instant lastReading;

        Result(List<Segment> segments, Map<Classification, Integer> counts, double knownDelta,
                double lowerBoundDelta, Arithmetic arithmetic, int partitions, long duplicateRowsCollapsed,
                long conflictInstants, Instant firstReading, Instant lastReading) {
            this.segments = List.copyOf(segments);
            this.counts = counts;
            this.knownDelta = knownDelta;
            this.lowerBoundDelta = lowerBoundDelta;
            this.arithmetic = arithmetic;
            this.partitions = partitions;
            this.duplicateRowsCollapsed = duplicateRowsCollapsed;
            this.conflictInstants = conflictInstants;
            this.firstReading = firstReading;
            this.lastReading = lastReading;
        }

        public List<Segment> segments() {
            return segments;
        }

        public int count(Classification c) {
            return counts.getOrDefault(c, 0);
        }

        /** Sum of exact segments only ({@code NORMAL}, {@code ROLLOVER}). */
        public double knownDelta() {
            return knownDelta;
        }

        /** Sum of lower-bound segments only; never added to {@link #knownDelta()}. */
        public double lowerBoundDelta() {
            return lowerBoundDelta;
        }

        public Arithmetic arithmetic() {
            return arithmetic;
        }

        public int partitions() {
            return partitions;
        }

        public long duplicateRowsCollapsed() {
            return duplicateRowsCollapsed;
        }

        public long conflictInstants() {
            return conflictInstants;
        }

        public int segmentsWithDelta() {
            int n = 0;
            for (Classification c : Classification.values()) {
                if (c.carriesDelta()) {
                    n += count(c);
                }
            }
            return n;
        }

        public int lowerBoundSegments() {
            return count(Classification.RESET) + count(Classification.POSSIBLE_HIDDEN_RESET);
        }

        public int segmentsWithoutDelta() {
            return segments.size() - segmentsWithDelta();
        }

        /** First and last observation of the only partition; null when there are several or none. */
        public Instant firstReading() {
            return firstReading;
        }

        public Instant lastReading() {
            return lastReading;
        }
    }

    private CounterDelta() {}

    public static Result compute(List<Reading> readings, Rules rules) {
        return compute(readings, rules, () -> { });
    }

    /**
     * @param abortCheck called between bounded units of work; it throws to stop the operation. The kernel
     *        holds no clock or thread policy of its own.
     */
    public static Result compute(List<Reading> readings, Rules rules, Runnable abortCheck) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(abortCheck, "abortCheck");
        List<Reading> all = readings == null ? List.of() : readings;
        boolean integerValued = isIntegerValued(rules.counterModulus()) && isIntegerValued(rules.resetBaseline());
        Map<String, List<Reading>> byEntity = new LinkedHashMap<>();
        int seen = 0;
        for (Reading r : all) {
            if ((seen++ & ABORT_CHECK_MASK) == 0) {
                abortCheck.run();
            }
            requireInDomain(r.value, rules);
            integerValued &= Math.rint(r.value) == r.value;
            byEntity.computeIfAbsent(r.entity, k -> new ArrayList<>()).add(r);
            if (byEntity.size() > MAX_PARTITIONS) {
                throw new CounterDeltaException(TOO_MANY_PARTITIONS,
                        "more than " + MAX_PARTITIONS + " distinct entityColumn values");
            }
        }
        Arithmetic arithmetic = integerValued ? Arithmetic.EXACT_INTEGER : Arithmetic.FLOAT64;

        List<Segment> segments = new ArrayList<>();
        Map<Classification, Integer> counts = new EnumMap<>(Classification.class);
        Totals totals = new Totals(arithmetic);
        long duplicates = 0L;
        long conflicts = 0L;
        Instant first = null;
        Instant last = null;
        for (Map.Entry<String, List<Reading>> partition : byEntity.entrySet()) {
            abortCheck.run();
            List<Moment> moments = moments(partition.getValue());
            for (Moment m : moments) {
                duplicates += m.duplicates;
                conflicts += m.conflict ? 1 : 0;
            }
            if (byEntity.size() == 1) {
                first = moments.get(0).instant;
                last = moments.get(moments.size() - 1).instant;
            }
            if (moments.size() == 1) {
                Moment only = moments.get(0);
                if (!only.conflict) {
                    add(segments, counts, totals, new Segment(partition.getKey(), only.instant, only.instant,
                            only.value, only.value, null, Classification.SINGLE_READING, 0d));
                }
                continue;
            }
            for (int i = 1; i < moments.size(); i++) {
                if ((i & ABORT_CHECK_MASK) == 0) {
                    abortCheck.run();
                }
                add(segments, counts, totals, classify(partition.getKey(), moments.get(i - 1), moments.get(i), rules));
            }
        }
        return new Result(segments, counts, totals.known, totals.lowerBound, arithmetic, byEntity.size(),
                duplicates, conflicts, first, last);
    }

    private static Segment classify(String entity, Moment prev, Moment cur, Rules rules) {
        Duration between = Duration.between(prev.instant, cur.instant);
        // Not toNanos(): that overflows a long beyond about 292 years.
        double elapsed = between.getSeconds() + between.getNano() / 1_000_000_000d;
        Double a = prev.conflict ? null : prev.value;
        Double b = cur.conflict ? null : cur.value;
        if (prev.conflict || cur.conflict) {
            return new Segment(entity, prev.instant, cur.instant, a, b, null, Classification.BROKEN_BY_CONFLICT,
                    elapsed);
        }
        Classification c;
        Double delta = null;
        double d = b - a;
        Double rate = rules.maxRatePerSecond();
        double bound = rate == null ? Double.POSITIVE_INFINITY : rate * elapsed;
        if (rules.maxGapSeconds() != null && elapsed > rules.maxGapSeconds()) {
            c = Classification.GAP_EXCEEDED;
        } else if (rules.counterModulus() != null) {
            double modulus = rules.counterModulus();
            if (bound >= modulus) {
                c = Classification.WRAP_UNIDENTIFIABLE;
            } else {
                double candidate = d >= 0d ? d : (modulus - a) + b;
                if (candidate <= bound) {
                    c = d >= 0d ? Classification.NORMAL : Classification.ROLLOVER;
                    delta = candidate;
                } else {
                    c = d >= 0d ? Classification.IMPLAUSIBLE_INCREASE : Classification.UNCERTAIN_NEGATIVE_JUMP;
                }
            }
        } else if (d < 0d) {
            double candidate = rules.resetBaseline() == null ? Double.NaN : b - rules.resetBaseline();
            if (rules.resetBaseline() != null && candidate <= bound) {
                c = Classification.RESET;
                delta = candidate;
            } else {
                c = Classification.UNCERTAIN_NEGATIVE_JUMP;
            }
        } else if (d > bound) {
            // Ruling out a hidden reset does not make the observed increase possible: rate first.
            c = Classification.IMPLAUSIBLE_INCREASE;
        } else if (rules.resetBaseline() != null && rate != null && b - rules.resetBaseline() <= bound) {
            c = Classification.POSSIBLE_HIDDEN_RESET;
            delta = d;
        } else {
            c = Classification.NORMAL;
            delta = d;
        }
        return new Segment(entity, prev.instant, cur.instant, a, b, delta, c, elapsed);
    }

    private static void add(List<Segment> segments, Map<Classification, Integer> counts, Totals totals, Segment s) {
        segments.add(s);
        counts.merge(s.classification, 1, Integer::sum);
        if (s.delta != null) {
            totals.add(s.delta, s.lowerBound());
        }
    }

    private static void requireInDomain(double v, Rules rules) {
        if (!(v >= 0d && v < EXACT_INTEGER_LIMIT)) {
            throw new CounterDeltaException(DOMAIN_UNSUPPORTED,
                    "counter readings must satisfy 0 <= reading < 2^53; the cache stores doubles, so a reading "
                            + "at or above 2^53 may already be rounded");
        }
        if (rules.counterModulus() != null && v >= rules.counterModulus()) {
            throw new CounterDeltaException(RULE_CONTRADICTED, "a reading is at or above counterModulus");
        }
        if (rules.resetBaseline() != null && v < rules.resetBaseline()) {
            throw new CounterDeltaException(RULE_CONTRADICTED, "a reading is below resetBaseline");
        }
    }

    private static boolean isIntegerValued(Double v) {
        return v == null || Math.rint(v) == v;
    }

    /** Readings of one partition grouped by instant, in (time, source ordinal) order. */
    private static List<Moment> moments(List<Reading> readings) {
        List<Reading> ordered = new ArrayList<>(readings);
        ordered.sort(Comparator.comparing((Reading r) -> r.instant).thenComparingLong(r -> r.sourceOrdinal));
        List<Moment> out = new ArrayList<>();
        for (Reading r : ordered) {
            Moment tail = out.isEmpty() ? null : out.get(out.size() - 1);
            if (tail != null && tail.instant.equals(r.instant)) {
                if (tail.value == r.value) {
                    tail.duplicates++;
                } else {
                    tail.conflict = true;
                }
            } else {
                out.add(new Moment(r.instant, r.value));
            }
        }
        return out;
    }

    private static final class Moment {
        final Instant instant;
        final double value;
        long duplicates;
        boolean conflict;

        Moment(Instant instant, double value) {
            this.instant = instant;
            this.value = value;
        }
    }

    private static final class Totals {
        final Arithmetic arithmetic;
        double known;
        double lowerBound;

        Totals(Arithmetic arithmetic) {
            this.arithmetic = arithmetic;
        }

        void add(double delta, boolean isLowerBound) {
            double next = (isLowerBound ? lowerBound : known) + delta;
            if (arithmetic == Arithmetic.EXACT_INTEGER && next >= EXACT_INTEGER_LIMIT) {
                throw new CounterDeltaException(DOMAIN_UNSUPPORTED,
                        "a running total reached 2^53 and can no longer be exact");
            }
            if (isLowerBound) {
                lowerBound = next;
            } else {
                known = next;
            }
        }
    }
}
