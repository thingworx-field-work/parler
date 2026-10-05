package com.thingworx.things.agent.transform.time;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.transform.time.RollingOperator.WindowKind;

/**
 * CF-52 {@code rolling_stats_v1}: one statistic over record-anchored rolling windows. Windows hold only
 * records that were read and are recomputed from their members, so nothing is extrapolated and no sliding
 * update drifts. Design: {@code docs/agent/nearterm/computing-enhancement.md} CE-2.
 */
public final class RollingStats {

    public static final String METHOD_ID = "rolling_stats_v1";

    /** Bound that keeps sums and sums of squared deviations finite for up to 100,000 members. */
    public static final double MAX_MAGNITUDE = 1e150;

    /** Sum of window member counts, records with missing values included. Provisional first value. */
    public static final long MAX_WINDOW_WORK = 5_000_000L;

    public static final int MAX_PARTITIONS = 50;

    public static final String VALUE_MAGNITUDE_UNSUPPORTED = "VALUE_MAGNITUDE_UNSUPPORTED";
    public static final String NUMERIC_RESULT_UNSUPPORTED = "NUMERIC_RESULT_UNSUPPORTED";
    public static final String WINDOW_WORK_TOO_LARGE = "WINDOW_WORK_TOO_LARGE";
    public static final String TOO_MANY_PARTITIONS = "TOO_MANY_PARTITIONS";
    public static final String STATISTIC_INVALID = "STATISTIC_INVALID";

    private static final int ABORT_CHECK_MASK = 0x3FF;

    public enum Statistic {
        MEAN(false, 1),
        SUM(false, 1),
        MIN(false, 1),
        MAX(false, 1),
        STDDEV(false, 2),
        COUNT_VALUES(true, 0),
        COUNT_RECORDS(true, 0);

        private final boolean gatesOnRecords;
        private final int minimumSample;

        Statistic(boolean gatesOnRecords, int minimumSample) {
            this.gatesOnRecords = gatesOnRecords;
            this.minimumSample = minimumSample;
        }

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Statistic fromWire(String raw) {
            for (Statistic s : values()) {
                if (s.wireName().equals(raw == null ? null : raw.trim().toLowerCase(Locale.ROOT))) {
                    return s;
                }
            }
            throw new MeasurementException(STATISTIC_INVALID, "statistic must be one of mean, sum, min, max, "
                    + "stddev, count_values, count_records; got " + raw);
        }
    }

    public enum ValueStatus {
        OK,
        BELOW_MIN_SUPPORT,
        BELOW_MIN_SAMPLE
    }

    public static final class Config {
        private final Statistic statistic;
        private final WindowKind kind;
        private final int observationWindow;
        private final Duration durationWindow;
        private final int minSupport;

        public Config(Statistic statistic, WindowKind kind, int observationWindow, Duration durationWindow,
                int minSupport) {
            this.statistic = Objects.requireNonNull(statistic, "statistic");
            this.kind = Objects.requireNonNull(kind, "kind");
            if (minSupport < 1 || (kind == WindowKind.OBSERVATION_COUNT && observationWindow < 1)
                    || (kind == WindowKind.ELAPSED_DURATION
                            && (durationWindow == null || durationWindow.isZero() || durationWindow.isNegative()))) {
                throw new IllegalArgumentException("WINDOW_INVALID");
            }
            this.observationWindow = observationWindow;
            this.durationWindow = durationWindow;
            this.minSupport = minSupport;
        }

        public Statistic statistic() {
            return statistic;
        }

        public WindowKind kind() {
            return kind;
        }

        public int observationWindow() {
            return observationWindow;
        }

        public Duration durationWindow() {
            return durationWindow;
        }

        public int minSupport() {
            return minSupport;
        }
    }

    /** One record of a series; {@code value} is null when the row carried no usable number. */
    public static final class Record {
        private final String entity;
        private final Instant instant;
        private final long sourceOrdinal;
        private final Double value;

        public Record(String entity, Instant instant, long sourceOrdinal, Double value) {
            this.entity = entity == null ? "" : entity;
            this.instant = Objects.requireNonNull(instant, "instant");
            this.sourceOrdinal = sourceOrdinal;
            this.value = value;
        }
    }

    public static final class Row {
        private final String entity;
        private final Instant timestamp;
        private final Instant windowStart;
        private final Instant windowEnd;
        private final Double value;
        private final ValueStatus valueStatus;
        private final int support;
        private final int records;
        private final boolean warmedUp;

        Row(String entity, Instant timestamp, Instant windowStart, Instant windowEnd, Double value,
                ValueStatus valueStatus, int support, int records, boolean warmedUp) {
            this.entity = entity;
            this.timestamp = timestamp;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.value = value;
            this.valueStatus = valueStatus;
            this.support = support;
            this.records = records;
            this.warmedUp = warmedUp;
        }

        public String entity() {
            return entity;
        }

        public Instant timestamp() {
            return timestamp;
        }

        /** Instant of the earliest member record, not the nominal window edge. */
        public Instant windowStart() {
            return windowStart;
        }

        public Instant windowEnd() {
            return windowEnd;
        }

        public Double value() {
            return value;
        }

        public ValueStatus valueStatus() {
            return valueStatus;
        }

        /** Valid values among the members; the same meaning for every statistic. */
        public int support() {
            return support;
        }

        public int records() {
            return records;
        }

        /**
         * Warm-up marker only: the window frame was available. It does not establish that the interior was
         * observed or that the window is covered.
         */
        public boolean warmedUp() {
            return warmedUp;
        }
    }

    public static final class Result {
        private final List<Row> rows;
        private final Map<ValueStatus, Integer> statusCounts;
        private final int partitions;
        private final int notWarmedUp;
        private final long windowWork;

        Result(List<Row> rows, Map<ValueStatus, Integer> statusCounts, int partitions, int notWarmedUp,
                long windowWork) {
            this.rows = List.copyOf(rows);
            this.statusCounts = statusCounts;
            this.partitions = partitions;
            this.notWarmedUp = notWarmedUp;
            this.windowWork = windowWork;
        }

        public List<Row> rows() {
            return rows;
        }

        public int count(ValueStatus status) {
            return statusCounts.getOrDefault(status, 0);
        }

        public int partitions() {
            return partitions;
        }

        public int notWarmedUp() {
            return notWarmedUp;
        }

        public long windowWork() {
            return windowWork;
        }
    }

    private RollingStats() {}

    public static Result compute(List<Record> records, Config config) {
        return compute(records, config, () -> { });
    }

    public static Result compute(List<Record> records, Config config, Runnable abortCheck) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(abortCheck, "abortCheck");
        Map<String, List<Record>> byEntity = new LinkedHashMap<>();
        int seen = 0;
        for (Record r : records == null ? List.<Record>of() : records) {
            if ((seen++ & ABORT_CHECK_MASK) == 0) {
                abortCheck.run();
            }
            if (r.value != null && !(Math.abs(r.value) <= MAX_MAGNITUDE)) {
                throw new MeasurementException(VALUE_MAGNITUDE_UNSUPPORTED,
                        "values must be finite with an absolute value of at most 1e150");
            }
            byEntity.computeIfAbsent(r.entity, k -> new ArrayList<>()).add(r);
            if (byEntity.size() > MAX_PARTITIONS) {
                throw new MeasurementException(TOO_MANY_PARTITIONS,
                        "more than " + MAX_PARTITIONS + " distinct entityColumn values");
            }
        }
        List<List<Record>> partitions = new ArrayList<>();
        for (List<Record> p : byEntity.values()) {
            List<Record> ordered = new ArrayList<>(p);
            ordered.sort(Comparator.comparing((Record r) -> r.instant).thenComparingLong(r -> r.sourceOrdinal));
            partitions.add(ordered);
        }

        // Exact membership count before any aggregation: a sparse elapsed input is judged on the work it
        // really needs, never on a pessimistic estimate.
        long work = 0L;
        List<int[]> firstMembers = new ArrayList<>();
        for (List<Record> p : partitions) {
            abortCheck.run();
            int[] first = firstMemberIndexes(p, config);
            firstMembers.add(first);
            for (int i = 0; i < first.length; i++) {
                work += i - first[i] + 1;
            }
            if (work > MAX_WINDOW_WORK) {
                throw new MeasurementException(WINDOW_WORK_TOO_LARGE, "the windows hold more than "
                        + MAX_WINDOW_WORK + " member records in total; use a smaller window or a shorter span");
            }
        }

        List<Row> rows = new ArrayList<>();
        Map<ValueStatus, Integer> statusCounts = new EnumMap<>(ValueStatus.class);
        int notWarmedUp = 0;
        for (int pi = 0; pi < partitions.size(); pi++) {
            List<Record> p = partitions.get(pi);
            int[] first = firstMembers.get(pi);
            Instant firstInstant = p.get(0).instant;
            for (int i = 0; i < p.size(); i++) {
                if ((i & ABORT_CHECK_MASK) == 0) {
                    abortCheck.run();
                }
                Row row = row(p, first[i], i, firstInstant, config);
                rows.add(row);
                statusCounts.merge(row.valueStatus, 1, Integer::sum);
                if (!row.warmedUp) {
                    notWarmedUp++;
                }
            }
        }
        return new Result(rows, statusCounts, partitions.size(), notWarmedUp, work);
    }

    /**
     * Index of the earliest member of the window anchored at each record. Members are the records at or
     * before the anchor in stable (time, source ordinal) order; an elapsed window keeps those not earlier
     * than {@code t - D}, both ends inclusive, decided as {@link RollingOperator} decides it: by the elapsed
     * time between the two records.
     */
    private static int[] firstMemberIndexes(List<Record> ordered, Config config) {
        int[] first = new int[ordered.size()];
        int lo = 0;
        for (int i = 0; i < ordered.size(); i++) {
            if (config.kind == WindowKind.OBSERVATION_COUNT) {
                lo = Math.max(0, i - config.observationWindow + 1);
            } else {
                // Elapsed time between two real instants, never "anchor minus D": an admitted duration
                // can put that nominal edge outside the Instant range although every record is in range.
                Instant anchor = ordered.get(i).instant;
                while (Duration.between(ordered.get(lo).instant, anchor).compareTo(config.durationWindow) > 0) {
                    lo++;
                }
            }
            first[i] = lo;
        }
        return first;
    }

    private static Row row(List<Record> p, int from, int anchor, Instant firstInstant, Config config) {
        int records = anchor - from + 1;
        double[] valid = new double[records];
        int support = 0;
        for (int i = from; i <= anchor; i++) {
            if (p.get(i).value != null) {
                valid[support++] = p.get(i).value;
            }
        }
        Record at = p.get(anchor);
        boolean warmedUp = config.kind == WindowKind.OBSERVATION_COUNT
                ? records == config.observationWindow
                : Duration.between(firstInstant, at.instant).compareTo(config.durationWindow) >= 0;

        Statistic st = config.statistic;
        ValueStatus status = ValueStatus.OK;
        Double value = null;
        if ((st.gatesOnRecords ? records : support) < config.minSupport) {
            status = ValueStatus.BELOW_MIN_SUPPORT;
        } else if (support < st.minimumSample) {
            status = ValueStatus.BELOW_MIN_SAMPLE;
        } else {
            value = statistic(st, valid, support, records);
        }
        return new Row(at.entity, at.instant, p.get(from).instant, at.instant, value, status, support, records,
                warmedUp);
    }

    static double statistic(Statistic st, double[] valid, int n, int records) {
        double result;
        switch (st) {
            case COUNT_RECORDS:
                return records;
            case COUNT_VALUES:
                return n;
            case SUM:
                result = compensatedSum(valid, n);
                break;
            case MIN:
                result = min(valid, n);
                break;
            case MAX:
                result = max(valid, n);
                break;
            case MEAN:
                result = mean(valid, n);
                break;
            case STDDEV:
                result = stddev(valid, n);
                break;
            default:
                throw new IllegalStateException("unknown statistic");
        }
        if (!Double.isFinite(result)) {
            throw new MeasurementException(NUMERIC_RESULT_UNSUPPORTED,
                    "a " + st.wireName() + " result is not a finite number");
        }
        return result;
    }

    /** A constant window reports its value, so rounding in sum / n cannot move the mean off it. */
    private static double mean(double[] v, int n) {
        double lo = min(v, n);
        return lo == max(v, n) ? lo : compensatedSum(v, n) / n;
    }

    /**
     * Sample standard deviation, centred and scaled. The rounded mean is only a centre c, never treated as the
     * exact mean: with d = v − c the variance is {@code (Σd² − (Σd)²/n) / (n − 1)} for any c, and Σd restores
     * what rounding c took away. Without it a small spread on a large offset (1e16 and 1e16 + 2) is reported
     * 41% too high. The deviations are exact or accurate to one ulp because c lies among the values. They are
     * divided by their largest magnitude before squaring and the scale is multiplied back after the square
     * root: squaring first, or going through the variance, would underflow small deviations to zero.
     */
    private static double stddev(double[] v, int n) {
        if (min(v, n) == max(v, n)) {
            return 0d;
        }
        double centre = compensatedSum(v, n) / n;
        double scale = 0d;
        for (int i = 0; i < n; i++) {
            scale = Math.max(scale, Math.abs(v[i] - centre));
        }
        double[] ratios = new double[n];
        double[] squares = new double[n];
        for (int i = 0; i < n; i++) {
            ratios[i] = (v[i] - centre) / scale;
            squares[i] = ratios[i] * ratios[i];
        }
        double sumOfRatios = compensatedSum(ratios, n);
        double centred = compensatedSum(squares, n) - sumOfRatios * sumOfRatios / n;
        double result = scale * Math.sqrt(Math.max(centred, 0d) / (n - 1));
        if (result == 0d) {
            // The members differ, so a zero spread is an underflow past representability, not a finding.
            throw new MeasurementException(NUMERIC_RESULT_UNSUPPORTED,
                    "the standard deviation of a non-constant window underflowed to zero");
        }
        return result;
    }

    /** Neumaier compensated summation. */
    private static double compensatedSum(double[] v, int n) {
        double sum = 0d;
        double compensation = 0d;
        for (int i = 0; i < n; i++) {
            double t = sum + v[i];
            compensation += Math.abs(sum) >= Math.abs(v[i]) ? (sum - t) + v[i] : (v[i] - t) + sum;
            sum = t;
        }
        return sum + compensation;
    }

    private static double min(double[] v, int n) {
        double m = v[0];
        for (int i = 1; i < n; i++) {
            m = Math.min(m, v[i]);
        }
        return m;
    }

    private static double max(double[] v, int n) {
        double m = v[0];
        for (int i = 1; i < n; i++) {
            m = Math.max(m, v[i]);
        }
        return m;
    }
}
