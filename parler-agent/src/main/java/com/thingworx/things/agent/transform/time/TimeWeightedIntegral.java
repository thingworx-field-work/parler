package com.thingworx.things.agent.transform.time;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.analysis.HalfOpenWindow;

/**
 * CF-01 {@code time_weighted_v1}: time-weighted integral and mean of one numeric series inside one window.
 * Every millisecond of the window is OBSERVED (a real reading on both sides within the gap), HELD (a
 * last-value estimate with no reading proving it) or UNKNOWN; the three never mix in one sum. Areas are
 * accumulated in value × milliseconds as exact rationals and rounded to binary64 once, so neither a clipped
 * end value nor the display unit can lose a representable integral or move the mean. It never
 * extrapolates past the observed boundary of a limited source and never calls a partly covered integral a
 * window total. Design: {@code docs/agent/nearterm/computing-enhancement.md} CE-3.
 */
public final class TimeWeightedIntegral {

    public static final String METHOD_ID = "time_weighted_v1";

    /** Same bound as {@code rolling_stats}: products and sums of admitted values cannot overflow. */
    public static final double MAX_MAGNITUDE = RollingStats.MAX_MAGNITUDE;

    /**
     * Precision of the one division behind each published number. Areas themselves are exact rationals, so
     * this bounds the error of the final quotient only, never of a term that later cancels.
     */
    private static final MathContext DIVISION = new MathContext(50);

    /** The abort check runs once per 1,024 readings. */
    private static final int ABORT_CHECK_MASK = 0x3FF;

    public static final String INTEGRATION_METHOD_INVALID = "INTEGRATION_METHOD_INVALID";
    public static final String TIME_UNIT_INVALID = "TIME_UNIT_INVALID";
    public static final String MAX_GAP_INVALID = "MAX_GAP_INVALID";
    public static final String VALUE_MAGNITUDE_UNSUPPORTED = RollingStats.VALUE_MAGNITUDE_UNSUPPORTED;
    public static final String NUMERIC_RESULT_UNSUPPORTED = RollingStats.NUMERIC_RESULT_UNSUPPORTED;

    public static final long MAX_GAP_SECONDS = 9_007_199_254_740_992L;

    public enum Method {
        STEP_HOLD("step_hold"),
        TRAPEZOID("trapezoid");

        private final String wireName;

        Method(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Method fromWire(String raw) {
            for (Method m : values()) {
                if (m.wireName.equals(raw)) {
                    return m;
                }
            }
            throw new MeasurementException(INTEGRATION_METHOD_INVALID,
                    "integrationMethod must be step_hold or trapezoid, got " + raw);
        }
    }

    /** Time factor of the integral: value × seconds, minutes or hours. */
    public enum Unit {
        SECONDS("seconds", 1_000L),
        MINUTES("minutes", 60_000L),
        HOURS("hours", 3_600_000L);

        private final String wireName;
        private final long millis;

        Unit(String wireName, long millis) {
            this.wireName = wireName;
            this.millis = millis;
        }

        public String wireName() {
            return wireName;
        }

        double of(long durationMillis) {
            return (double) durationMillis / millis;
        }

        public static Unit fromWire(String raw) {
            for (Unit u : values()) {
                if (u.wireName.equals(raw)) {
                    return u;
                }
            }
            throw new MeasurementException(TIME_UNIT_INVALID,
                    "timeUnit must be seconds, minutes or hours, got " + raw);
        }
    }

    public enum Coverage {
        OBSERVED,
        HELD,
        UNKNOWN
    }

    public static final class Config {
        private final Method method;
        private final long maxGapSeconds;
        private final Unit unit;

        public Config(Method method, long maxGapSeconds, Unit unit) {
            this.method = Objects.requireNonNull(method, "method");
            this.unit = Objects.requireNonNull(unit, "unit");
            if (maxGapSeconds <= 0L || maxGapSeconds > MAX_GAP_SECONDS) {
                throw new MeasurementException(MAX_GAP_INVALID,
                        "maxGapSeconds must be a positive whole number of seconds, at most 2^53");
            }
            this.maxGapSeconds = maxGapSeconds;
        }

        public Method method() {
            return method;
        }

        public long maxGapSeconds() {
            return maxGapSeconds;
        }

        public Unit unit() {
            return unit;
        }
    }

    public static final class Reading {
        final Instant instant;
        final long sourceOrdinal;
        final double value;

        public Reading(Instant instant, long sourceOrdinal, double value) {
            this.instant = Objects.requireNonNull(instant, "instant");
            this.sourceOrdinal = sourceOrdinal;
            this.value = value;
        }
    }

    /** One stretch of the window, clipped to it. Values, duration in unit and integral are null on UNKNOWN. */
    public static final class Segment {
        private final long startMillis;
        private final long endMillis;
        private final Double startValue;
        private final Double endValue;
        private final Double supportedDuration;
        private final Double integral;
        private final Coverage coverage;

        Segment(long startMillis, long endMillis, Double startValue, Double endValue, Double supportedDuration,
                Double integral, Coverage coverage) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.startValue = startValue;
            this.endValue = endValue;
            this.supportedDuration = supportedDuration;
            this.integral = integral;
            this.coverage = coverage;
        }

        public Instant start() {
            return Instant.ofEpochMilli(startMillis);
        }

        public Instant end() {
            return Instant.ofEpochMilli(endMillis);
        }

        public long millis() {
            return endMillis - startMillis;
        }

        public Double startValue() {
            return startValue;
        }

        public Double endValue() {
            return endValue;
        }

        public Double supportedDuration() {
            return supportedDuration;
        }

        public Double integral() {
            return integral;
        }

        public Coverage coverage() {
            return coverage;
        }
    }

    public static final class Result {
        private final List<Segment> segments = new ArrayList<>();
        /** Areas in value × milliseconds, unit-independent and unrounded until the very end. */
        private Area observedArea = Area.ZERO;
        private Area estimatedArea = Area.ZERO;
        private double observedIntegral;
        private double estimatedIntegral;
        private double integral;
        private long observedMillis;
        private long estimatedMillis;
        private long unknownMillis;
        private long windowMillis;
        private long cancelledTailHoldMillis;
        private long duplicateRowsCollapsed;
        private long conflictInstants;
        private int readingsInWindow;
        private boolean evidence;
        private Double mean;

        public List<Segment> segments() {
            return segments;
        }

        public double observedIntegral() {
            return observedIntegral;
        }

        public double estimatedIntegral() {
            return estimatedIntegral;
        }

        public double integral() {
            return integral;
        }

        /** Integral over covered time; null when no time is covered, never 0. */
        public Double timeWeightedMean() {
            return mean;
        }

        public long observedMillis() {
            return observedMillis;
        }

        public long estimatedMillis() {
            return estimatedMillis;
        }

        public long unknownMillis() {
            return unknownMillis;
        }

        public long coveredMillis() {
            return observedMillis + estimatedMillis;
        }

        public long windowMillis() {
            return windowMillis;
        }

        /** Tail hold that a limited source forbade; it is part of {@link #unknownMillis()}. */
        public long cancelledTailHoldMillis() {
            return cancelledTailHoldMillis;
        }

        public long duplicateRowsCollapsed() {
            return duplicateRowsCollapsed;
        }

        public long conflictInstants() {
            return conflictInstants;
        }

        /** Distinct reading instants inside the window, conflicting ones included. */
        public int readingsInWindow() {
            return readingsInWindow;
        }

        /** True when the window holds a reading instant or either side has a non-conflicting anchor. */
        public boolean hasEvidence() {
            return evidence;
        }

        public int unknownSegments() {
            int n = 0;
            for (Segment s : segments) {
                if (s.coverage == Coverage.UNKNOWN) {
                    n++;
                }
            }
            return n;
        }

        /** Whether the result contains an estimate is a matter of held time, not of the held integral. */
        public boolean containsEstimate() {
            return estimatedMillis > 0L;
        }
    }

    /** One reading instant: a value, or a conflict that is a continuity barrier. */
    private static final class Node {
        final long millis;
        final double value;
        boolean conflict;

        Node(long millis, double value) {
            this.millis = millis;
            this.value = value;
        }
    }

    private TimeWeightedIntegral() {}

    /**
     * @param readings readings with a usable value, in any order; of those outside the window only the
     *        nearest instant on each side is used, as an anchor
     * @param tailHoldAllowed false when the source reached a read limit or is partial: the hold after the
     *        last reading would cross the observed boundary and becomes UNKNOWN
     */
    public static Result compute(List<Reading> readings, HalfOpenWindow window, Config config,
            boolean tailHoldAllowed, Runnable abortCheck) {
        Objects.requireNonNull(readings, "readings");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(config, "config");
        MeasurementSeriesReader.requireSupportedInstant(window.startInclusive());
        MeasurementSeriesReader.requireSupportedInstant(window.endExclusive());
        long windowStart = window.startInclusive().toEpochMilli();
        long windowEnd = window.endExclusive().toEpochMilli();

        Result result = new Result();
        result.windowMillis = windowEnd - windowStart;
        List<Node> nodes = relevantNodes(readings, windowStart, windowEnd, result, abortCheck);

        // maxGapSeconds <= 2^53 and |epoch millis| < 2.6e14, so node time plus the gap stays inside a long.
        long gapMillis = config.maxGapSeconds * 1_000L;
        if (nodes.isEmpty()) {
            unknown(result, windowStart, windowEnd);
        } else if (nodes.get(0).millis > windowStart) {
            unknown(result, windowStart, Math.min(nodes.get(0).millis, windowEnd));
        }
        for (int i = 0; i < nodes.size(); i++) {
            if ((i & ABORT_CHECK_MASK) == 0) {
                abortCheck.run();
            }
            Node a = nodes.get(i);
            if (a.millis >= windowEnd) {
                break;
            }
            if (i + 1 < nodes.size()) {
                between(result, a, nodes.get(i + 1), windowStart, windowEnd, gapMillis, config);
            } else {
                tail(result, a, windowStart, windowEnd, gapMillis, config, tailHoldAllowed);
            }
        }
        // The unit only scales what is displayed. The mean comes from the unrounded area and the covered
        // milliseconds, so it is the same in every unit, also when the displayed integral rounds to 0.
        Area totalArea = result.observedArea.plus(result.estimatedArea);
        result.observedIntegral = inUnit(result.observedArea, config.unit);
        result.estimatedIntegral = inUnit(result.estimatedArea, config.unit);
        result.integral = inUnit(totalArea, config.unit);
        if (result.coveredMillis() > 0L) {
            result.mean = totalArea.dividedBy(result.coveredMillis());
        }
        if (!Double.isFinite(result.observedIntegral()) || !Double.isFinite(result.estimatedIntegral())
                || !Double.isFinite(result.integral()) || (result.mean != null && !Double.isFinite(result.mean))) {
            throw new MeasurementException(NUMERIC_RESULT_UNSUPPORTED,
                    "the integral or its time-weighted mean is not a finite number");
        }
        return result;
    }

    /**
     * Sort, collapse identical duplicates, mark conflicting instants, and keep the in-window instants plus
     * the nearest instant on each side. A conflicting anchor stays: it is a barrier and must not be skipped
     * in favour of a farther reading.
     */
    private static List<Node> relevantNodes(List<Reading> readings, long windowStart, long windowEnd,
            Result result, Runnable abortCheck) {
        List<Reading> sorted = new ArrayList<>(readings);
        sorted.sort(Comparator.<Reading, Instant>comparing(r -> r.instant).thenComparingLong(r -> r.sourceOrdinal));
        List<Node> all = new ArrayList<>();
        Node current = null;
        long currentRows = 0L;
        for (int i = 0; i < sorted.size(); i++) {
            if ((i & ABORT_CHECK_MASK) == 0) {
                abortCheck.run();
            }
            Reading r = sorted.get(i);
            MeasurementSeriesReader.requireSupportedInstant(r.instant);
            if (!(Math.abs(r.value) <= MAX_MAGNITUDE)) {
                throw new MeasurementException(VALUE_MAGNITUDE_UNSUPPORTED,
                        "values must be finite with an absolute value of at most 1e150");
            }
            long millis = r.instant.toEpochMilli();
            if (current == null || current.millis != millis) {
                current = new Node(millis, r.value);
                currentRows = 1L;
                all.add(current);
            } else {
                currentRows++;
                if (r.value != current.value) {
                    current.conflict = true;
                }
            }
        }
        int firstInside = 0;
        while (firstInside < all.size() && all.get(firstInside).millis < windowStart) {
            firstInside++;
        }
        int firstAfter = firstInside;
        while (firstAfter < all.size() && all.get(firstAfter).millis < windowEnd) {
            firstAfter++;
        }
        int from = Math.max(0, firstInside - 1);
        int to = Math.min(all.size(), firstAfter + 1);
        List<Node> relevant = new ArrayList<>(all.subList(from, to));
        result.readingsInWindow = firstAfter - firstInside;
        result.evidence = result.readingsInWindow > 0;
        for (Node n : relevant) {
            if (n.conflict) {
                result.conflictInstants++;
            } else if (n.millis < windowStart || n.millis >= windowEnd) {
                result.evidence = true;
            }
        }
        result.duplicateRowsCollapsed = duplicatesOf(sorted, relevant);
        return relevant;
    }

    /** Rows beyond the first at a non-conflicting relevant instant. */
    private static long duplicatesOf(List<Reading> sorted, List<Node> relevant) {
        long duplicates = 0L;
        int at = 0;
        for (Node n : relevant) {
            while (at < sorted.size() && sorted.get(at).instant.toEpochMilli() < n.millis) {
                at++;
            }
            long rows = 0L;
            while (at < sorted.size() && sorted.get(at).instant.toEpochMilli() == n.millis) {
                rows++;
                at++;
            }
            if (!n.conflict) {
                duplicates += rows - 1L;
            }
        }
        return duplicates;
    }

    private static void between(Result result, Node a, Node b, long windowStart, long windowEnd, long gapMillis,
            Config config) {
        long from = Math.max(a.millis, windowStart);
        long to = Math.min(b.millis, windowEnd);
        if (a.conflict || b.conflict) {
            unknown(result, from, to);
            return;
        }
        long elapsed = b.millis - a.millis;
        if (config.method == Method.TRAPEZOID) {
            if (elapsed > gapMillis) {
                unknown(result, from, to);
                return;
            }
            // Both ends are real readings: the value at a clipped window edge is interpolated, not extrapolated.
            // The interpolated end values are display cells only. The area is formed from the two readings and
            // the time offsets, because an end value can round to 0 while the area is representable.
            double startValue = from == a.millis ? a.value : interpolate(a, b, from);
            double endValue = to == b.millis ? b.value : interpolate(a, b, to);
            covered(result, from, to, startValue, endValue, trapezoidArea(a, b, from, to), Coverage.OBSERVED,
                    config.unit);
            return;
        }
        if (elapsed <= gapMillis) {
            covered(result, from, to, a.value, a.value, heldArea(a, from, to), Coverage.OBSERVED, config.unit);
            return;
        }
        // The validity of a value starts at its own instant and does not restart at the window edge.
        long holdEnd = Math.min(a.millis + gapMillis, to);
        if (holdEnd > from) {
            covered(result, from, holdEnd, a.value, a.value, heldArea(a, from, holdEnd), Coverage.HELD,
                    config.unit);
            unknown(result, holdEnd, to);
        } else {
            unknown(result, from, to);
        }
    }

    /**
     * Linear interpolation between two real readings. The offset is multiplied in before the division: a
     * per-millisecond slope can underflow to 0 although the interpolated value is representable.
     */
    private static double interpolate(Node a, Node b, long atMillis) {
        return a.value + (b.value - a.value) * (double) (atMillis - a.millis) / (double) (b.millis - a.millis);
    }

    private static void tail(Result result, Node last, long windowStart, long windowEnd, long gapMillis,
            Config config, boolean tailHoldAllowed) {
        long from = Math.max(last.millis, windowStart);
        if (config.method == Method.TRAPEZOID || last.conflict) {
            unknown(result, from, windowEnd);
            return;
        }
        long holdEnd = Math.min(last.millis + gapMillis, windowEnd);
        if (holdEnd <= from) {
            unknown(result, from, windowEnd);
        } else if (!tailHoldAllowed) {
            result.cancelledTailHoldMillis = holdEnd - from;
            unknown(result, from, windowEnd);
        } else {
            covered(result, from, holdEnd, last.value, last.value, heldArea(last, from, holdEnd), Coverage.HELD,
                    config.unit);
            unknown(result, holdEnd, windowEnd);
        }
    }

    /**
     * Area under the line through two readings between {@code from} and {@code to}:
     * {@code [va × (2E − p − q) + vb × (p + q)] × (to − from) ÷ 2E} with E the elapsed time between the readings
     * and p, q the offsets of the two edges from the left reading. An unclipped segment needs no division at
     * all, {@code (va + vb) × E ÷ 2}; a clipped one keeps E as its denominator.
     */
    private static Area trapezoidArea(Node a, Node b, long from, long to) {
        long elapsed = b.millis - a.millis;
        if (from == a.millis && to == b.millis) {
            return Area.ofDoubled(new BigDecimal(a.value).add(new BigDecimal(b.value))
                    .multiply(BigDecimal.valueOf(elapsed)), 1L);
        }
        long offsets = (from - a.millis) + (to - a.millis);
        BigDecimal weighted = new BigDecimal(a.value).multiply(BigDecimal.valueOf(2L * elapsed - offsets))
                .add(new BigDecimal(b.value).multiply(BigDecimal.valueOf(offsets)));
        return Area.ofDoubled(weighted.multiply(BigDecimal.valueOf(to - from)), elapsed);
    }

    private static Area heldArea(Node held, long from, long to) {
        return Area.ofDoubled(new BigDecimal(held.value).multiply(BigDecimal.valueOf(2L * (to - from))), 1L);
    }

    /** The one place where an area is rounded to binary64, in the requested unit. */
    private static double inUnit(Area area, Unit unit) {
        return area.dividedBy(unit.millis);
    }

    /**
     * An area in value × milliseconds as an exact rational, stored doubled so that halving is part of the final
     * division. Sums are exact: a small term is never rounded away next to large terms that later cancel. Only
     * segments clipped by a window edge carry a denominator other than 1, so it stays a product of at most a
     * few elapsed times.
     */
    private static final class Area {
        static final Area ZERO = new Area(BigDecimal.ZERO, BigDecimal.ONE);

        private final BigDecimal doubledNumerator;
        private final BigDecimal denominator;

        private Area(BigDecimal doubledNumerator, BigDecimal denominator) {
            this.doubledNumerator = doubledNumerator;
            this.denominator = denominator;
        }

        static Area ofDoubled(BigDecimal doubledNumerator, long denominator) {
            return new Area(doubledNumerator, BigDecimal.valueOf(denominator));
        }

        Area plus(Area other) {
            if (denominator.compareTo(other.denominator) == 0) {
                return new Area(doubledNumerator.add(other.doubledNumerator), denominator);
            }
            return new Area(doubledNumerator.multiply(other.denominator)
                    .add(other.doubledNumerator.multiply(denominator)), denominator.multiply(other.denominator));
        }

        /** The area divided by {@code divisor}, rounded to binary64 in this single step. */
        double dividedBy(long divisor) {
            return doubledNumerator.divide(denominator.multiply(BigDecimal.valueOf(2L * divisor)), DIVISION)
                    .doubleValue();
        }
    }

    private static void covered(Result result, long from, long to, double startValue, double endValue,
            Area area, Coverage coverage, Unit unit) {
        if (to <= from) {
            return;
        }
        if (coverage == Coverage.OBSERVED) {
            result.observedArea = result.observedArea.plus(area);
            result.observedMillis += to - from;
        } else {
            result.estimatedArea = result.estimatedArea.plus(area);
            result.estimatedMillis += to - from;
        }
        result.segments.add(new Segment(from, to, startValue, endValue, unit.of(to - from), inUnit(area, unit),
                coverage));
    }

    /** Adjacent unknown stretches are one segment. */
    private static void unknown(Result result, long from, long to) {
        if (to <= from) {
            return;
        }
        result.unknownMillis += to - from;
        int lastIdx = result.segments.size() - 1;
        if (lastIdx >= 0) {
            Segment last = result.segments.get(lastIdx);
            if (last.coverage == Coverage.UNKNOWN && last.endMillis == from) {
                result.segments.set(lastIdx,
                        new Segment(last.startMillis, to, null, null, null, null, Coverage.UNKNOWN));
                return;
            }
        }
        result.segments.add(new Segment(from, to, null, null, null, null, Coverage.UNKNOWN));
    }
}
