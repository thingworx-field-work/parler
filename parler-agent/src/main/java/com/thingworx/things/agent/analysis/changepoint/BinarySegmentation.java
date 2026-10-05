package com.thingworx.things.agent.analysis.changepoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import com.thingworx.things.agent.analysis.AnalysisOutcomeCodes;
import com.thingworx.things.agent.analysis.FindingRow;
import com.thingworx.things.agent.analysis.G1DetectionResult;
import com.thingworx.things.agent.analysis.stats.NumericObservation;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Bounded binary segmentation for mean-shift change-point candidates (§7.2 / §8.1).
 * Split scoring is O(1) per candidate via prefix Σx / Σx² (overall O(k·n) with candidate cap k).
 */
public final class BinarySegmentation {

    public static final String METHOD_ID = "binary_segmentation";
    public static final int DEFAULT_MAX_CANDIDATES = 3;
    public static final int DEFAULT_MIN_SEGMENT_SUPPORT = 3;
    public static final double DEFAULT_MIN_ABS_MEAN_SHIFT = 0.0;
    public static final double DEFAULT_MIN_RELATIVE_SSE_REDUCTION = 0.05;

    private BinarySegmentation() {}

    public static G1DetectionResult detect(NumericSeries series, Config config) {
        return detect(series, config, null);
    }

    /**
     * @param rangeQueryCounter optional probe incremented once per O(1) prefix-range stats query
     *        (tests assert O(k·n) query counts; must not be used for product logic)
     */
    public static G1DetectionResult detect(NumericSeries series, Config config,
            AtomicLong rangeQueryCounter) {
        Objects.requireNonNull(series, "series");
        Config cfg = config == null ? Config.defaults() : config.validate();
        List<NumericObservation> finite = new ArrayList<>();
        for (NumericObservation o : series.observations()) {
            if (o.isFinite()) {
                finite.add(o);
            }
        }
        long n = finite.size();
        if (n < (long) cfg.minSegmentSupport * 2L) {
            return result(EvidenceStatus.INSUFFICIENT_EVIDENCE, "insufficient_support", n, List.of(),
                    metrics(cfg, n, 0));
        }
        double[] values = new double[(int) n];
        for (int i = 0; i < finite.size(); i++) {
            values[i] = finite.get(i).value();
        }
        PrefixSums prefix = PrefixSums.of(values);
        List<Split> splits = new ArrayList<>();
        segment(prefix, 0, values.length, cfg, splits, rangeQueryCounter);
        splits.sort(Comparator.comparingInt(s -> s.index));
        if (splits.size() > cfg.maxCandidates) {
            splits = new ArrayList<>(splits.subList(0, cfg.maxCandidates));
        }
        List<FindingRow> findings = new ArrayList<>();
        for (Split s : splits) {
            NumericObservation at = finite.get(s.index);
            findings.add(FindingRow.builder()
                    .sourceOrdinal(at.sourceOrdinal())
                    .timestamp(at.instant())
                    .score(s.sseReduction)
                    .effect(s.meanRight - s.meanLeft)
                    .methodId(METHOD_ID)
                    .outcomeCode("change_point")
                    .segmentOrPair("split@" + s.index
                            + ";nL=" + s.nLeft + ";nR=" + s.nRight
                            + ";meanL=" + s.meanLeft + ";meanR=" + s.meanRight
                            + ";rel=" + s.relativeReduction)
                    .build());
        }
        if (findings.isEmpty()) {
            return result(EvidenceStatus.NO_FINDING, "no_change_point", n, List.of(),
                    metrics(cfg, n, 0));
        }
        return result(EvidenceStatus.SUCCESS, "change_point", n, findings, metrics(cfg, n, findings.size()));
    }

    private static void segment(PrefixSums prefix, int lo, int hi, Config cfg, List<Split> out,
            AtomicLong rangeQueryCounter) {
        int len = hi - lo;
        if (out.size() >= cfg.maxCandidates || len < cfg.minSegmentSupport * 2) {
            return;
        }
        SegmentStats parent = prefix.stats(lo, hi, rangeQueryCounter);
        Split best = null;
        for (int split = lo + cfg.minSegmentSupport; split <= hi - cfg.minSegmentSupport; split++) {
            SegmentStats left = prefix.stats(lo, split, rangeQueryCounter);
            SegmentStats right = prefix.stats(split, hi, rangeQueryCounter);
            double reduction = parent.sse - left.sse - right.sse;
            if (reduction <= 0.0) {
                continue;
            }
            double absShift = Math.abs(right.mean - left.mean);
            double rel = parent.sse == 0.0 ? 0.0 : reduction / parent.sse;
            if (absShift < cfg.minAbsMeanShift || rel < cfg.minRelativeSseReduction) {
                continue;
            }
            Split candidate = new Split(split, left.n, right.n, left.mean, right.mean, reduction, rel);
            if (best == null
                    || candidate.sseReduction > best.sseReduction
                    || (candidate.sseReduction == best.sseReduction && candidate.index < best.index)) {
                best = candidate;
            }
        }
        if (best == null) {
            return;
        }
        out.add(best);
        if (out.size() >= cfg.maxCandidates) {
            return;
        }
        segment(prefix, lo, best.index, cfg, out, rangeQueryCounter);
        if (out.size() >= cfg.maxCandidates) {
            return;
        }
        segment(prefix, best.index, hi, cfg, out, rangeQueryCounter);
    }

    private static G1DetectionResult result(EvidenceStatus status, String outcome, long n,
            List<FindingRow> findings, Map<String, String> metrics) {
        return G1DetectionResult.builder()
                .status(status)
                .outcomeCode(outcome)
                .methodId(METHOD_ID)
                .supportN(n)
                .findings(findings)
                .metrics(withOutcome(metrics, outcome))
                .build();
    }

    private static Map<String, String> metrics(Config cfg, long n, int candidates) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("supportN", Long.toString(n));
        m.put("maxCandidates", Integer.toString(cfg.maxCandidates));
        m.put("minSegmentSupport", Integer.toString(cfg.minSegmentSupport));
        m.put("minAbsMeanShift", Double.toString(cfg.minAbsMeanShift));
        m.put("minRelativeSseReduction", Double.toString(cfg.minRelativeSseReduction));
        m.put("candidateCount", Integer.toString(candidates));
        return m;
    }

    private static Map<String, String> withOutcome(Map<String, String> metrics, String outcome) {
        Map<String, String> m = new LinkedHashMap<>(metrics);
        m.put(AnalysisOutcomeCodes.METRIC_KEY, outcome);
        return m;
    }

    public static final class Config {
        public final int maxCandidates;
        public final int minSegmentSupport;
        public final double minAbsMeanShift;
        public final double minRelativeSseReduction;

        public Config(int maxCandidates, int minSegmentSupport, double minAbsMeanShift,
                double minRelativeSseReduction) {
            this.maxCandidates = maxCandidates;
            this.minSegmentSupport = minSegmentSupport;
            this.minAbsMeanShift = minAbsMeanShift;
            this.minRelativeSseReduction = minRelativeSseReduction;
        }

        public static Config defaults() {
            return new Config(DEFAULT_MAX_CANDIDATES, DEFAULT_MIN_SEGMENT_SUPPORT,
                    DEFAULT_MIN_ABS_MEAN_SHIFT, DEFAULT_MIN_RELATIVE_SSE_REDUCTION);
        }

        Config validate() {
            if (maxCandidates < 1) {
                throw new IllegalArgumentException("maxCandidates must be >= 1");
            }
            if (minSegmentSupport < 2) {
                throw new IllegalArgumentException("minSegmentSupport must be >= 2");
            }
            if (!(minAbsMeanShift >= 0.0) || !Double.isFinite(minAbsMeanShift)) {
                throw new IllegalArgumentException("minAbsMeanShift must be finite and >= 0");
            }
            if (!(minRelativeSseReduction >= 0.0) || !Double.isFinite(minRelativeSseReduction)) {
                throw new IllegalArgumentException("minRelativeSseReduction must be finite and >= 0");
            }
            return this;
        }
    }

    /** Prefix Σx / Σx² for O(1) segment SSE = Σx² − (Σx)²/n. */
    static final class PrefixSums {
        private final double[] sum; // sum[i] = Σ values[0..i)
        private final double[] sumSq;

        private PrefixSums(double[] sum, double[] sumSq) {
            this.sum = sum;
            this.sumSq = sumSq;
        }

        static PrefixSums of(double[] values) {
            double[] sum = new double[values.length + 1];
            double[] sumSq = new double[values.length + 1];
            for (int i = 0; i < values.length; i++) {
                sum[i + 1] = sum[i] + values[i];
                sumSq[i + 1] = sumSq[i] + values[i] * values[i];
            }
            return new PrefixSums(sum, sumSq);
        }

        SegmentStats stats(int lo, int hi, AtomicLong rangeQueryCounter) {
            if (rangeQueryCounter != null) {
                rangeQueryCounter.incrementAndGet();
            }
            int n = hi - lo;
            double s = sum[hi] - sum[lo];
            double sq = sumSq[hi] - sumSq[lo];
            double mean = s / n;
            double sse = sq - (s * s) / n;
            if (sse < 0.0 && sse > -1e-12) {
                sse = 0.0;
            }
            return new SegmentStats(n, mean, sse);
        }
    }

    private static final class SegmentStats {
        final int n;
        final double mean;
        final double sse;

        SegmentStats(int n, double mean, double sse) {
            this.n = n;
            this.mean = mean;
            this.sse = sse;
        }
    }

    private static final class Split {
        final int index;
        final int nLeft;
        final int nRight;
        final double meanLeft;
        final double meanRight;
        final double sseReduction;
        final double relativeReduction;

        Split(int index, int nLeft, int nRight, double meanLeft, double meanRight, double sseReduction,
                double relativeReduction) {
            this.index = index;
            this.nLeft = nLeft;
            this.nRight = nRight;
            this.meanLeft = meanLeft;
            this.meanRight = meanRight;
            this.sseReduction = sseReduction;
            this.relativeReduction = relativeReduction;
        }
    }
}
