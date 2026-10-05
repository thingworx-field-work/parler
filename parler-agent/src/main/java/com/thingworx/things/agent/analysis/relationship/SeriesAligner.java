package com.thingworx.things.agent.analysis.relationship;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.thingworx.things.agent.analysis.stats.LinearPercentile;
import com.thingworx.things.agent.analysis.stats.NumericObservation;
import com.thingworx.things.agent.analysis.stats.NumericSeries;

/**
 * Exact canonical-timestamp and nearest-within-tolerance one-to-one alignment (§7.4 / D5).
 * No interpolation. Observations without an Instant are counted as considered but never matched.
 */
public final class SeriesAligner {

    public static final String MODE_EXACT = "exact";
    public static final String MODE_NEAREST = "nearest_within_tolerance";
    /**
     * Max transient within-tolerance candidate pairs before nearest matching (DIK-5).
     * Prevents unbounded O(|l|·|r|) heap on wide tolerances.
     */
    public static final long MAX_NEAREST_CANDIDATE_PAIRS = 2_000_000L;

    private SeriesAligner() {}

    public static AlignmentResult exact(NumericSeries left, NumericSeries right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        List<NumericObservation> l = timestamped(left);
        List<NumericObservation> r = timestamped(right);
        List<AlignedPair> pairs = new ArrayList<>();
        Set<Long> usedRight = new HashSet<>();
        // Deterministic left order: timestamp, then sourceOrdinal.
        List<NumericObservation> leftSorted = new ArrayList<>(l);
        leftSorted.sort(BY_TS_ORD);
        List<NumericObservation> rightSorted = new ArrayList<>(r);
        rightSorted.sort(BY_TS_ORD);
        int ri = 0;
        for (NumericObservation lo : leftSorted) {
            while (ri < rightSorted.size()
                    && rightSorted.get(ri).instant().isBefore(lo.instant())) {
                ri++;
            }
            int j = ri;
            NumericObservation best = null;
            while (j < rightSorted.size() && rightSorted.get(j).instant().equals(lo.instant())) {
                NumericObservation cand = rightSorted.get(j);
                if (!usedRight.contains(cand.sourceOrdinal())) {
                    if (best == null || cand.sourceOrdinal() < best.sourceOrdinal()) {
                        best = cand;
                    }
                }
                j++;
            }
            if (best != null) {
                usedRight.add(best.sourceOrdinal());
                pairs.add(new AlignedPair(lo, best, 0L));
            }
        }
        return finish(MODE_EXACT, left, right, l.size(), r.size(), pairs);
    }

    /**
     * One-to-one nearest matching within {@code tolerance}. Candidate order: smallest abs delta,
     * then earlier pair timestamp (min of the two), then left ordinal, then right ordinal.
     */
    public static AlignmentResult nearest(NumericSeries left, NumericSeries right, Duration tolerance) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(tolerance, "tolerance");
        if (tolerance.isNegative() || tolerance.isZero()) {
            throw new IllegalArgumentException("tolerance must be positive");
        }
        long tolMillis = tolerance.toMillis();
        List<NumericObservation> l = timestamped(left);
        List<NumericObservation> r = timestamped(right);
        long candidateEstimate = (long) l.size() * (long) r.size();
        if (candidateEstimate > MAX_NEAREST_CANDIDATE_PAIRS) {
            throw new IllegalStateException(
                    com.thingworx.things.agent.analysis.U5MethodRegistry.BUDGET_EXCEEDED_REASON
                            + ": nearest_align_candidates");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (NumericObservation lo : l) {
            for (NumericObservation ro : r) {
                long delta = Math.abs(Duration.between(lo.instant(), ro.instant()).toMillis());
                if (delta <= tolMillis) {
                    Instant earlier = lo.instant().isBefore(ro.instant()) ? lo.instant() : ro.instant();
                    candidates.add(new Candidate(lo, ro, delta, earlier));
                }
            }
        }
        candidates.sort(Comparator
                .comparingLong((Candidate c) -> c.absDelta)
                .thenComparing(c -> c.earlier)
                .thenComparingLong(c -> c.left.sourceOrdinal())
                .thenComparingLong(c -> c.right.sourceOrdinal()));
        Set<Long> usedLeft = new HashSet<>();
        Set<Long> usedRight = new HashSet<>();
        List<AlignedPair> pairs = new ArrayList<>();
        for (Candidate c : candidates) {
            if (usedLeft.contains(c.left.sourceOrdinal()) || usedRight.contains(c.right.sourceOrdinal())) {
                continue;
            }
            usedLeft.add(c.left.sourceOrdinal());
            usedRight.add(c.right.sourceOrdinal());
            long signed = Duration.between(c.left.instant(), c.right.instant()).toMillis();
            pairs.add(new AlignedPair(c.left, c.right, signed));
        }
        // Stable report order: left timestamp/ordinal.
        pairs.sort(Comparator
                .comparing((AlignedPair p) -> p.left().instant())
                .thenComparingLong(p -> p.left().sourceOrdinal()));
        return finish(MODE_NEAREST, left, right, l.size(), r.size(), pairs);
    }

    private static AlignmentResult finish(String mode, NumericSeries left, NumericSeries right,
            int leftTs, int rightTs, List<AlignedPair> pairs) {
        long leftConsidered = left.observations().size();
        long rightConsidered = right.observations().size();
        long leftUnmatched = leftTs - pairs.size();
        long rightUnmatched = rightTs - pairs.size();
        long maxAbs = 0L;
        double[] absDeltas = new double[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            long abs = Math.abs(pairs.get(i).deltaMillis());
            maxAbs = Math.max(maxAbs, abs);
            absDeltas[i] = abs;
        }
        long median = pairs.isEmpty() ? 0L : Math.round(LinearPercentile.of(absDeltas, 0.5));
        return new AlignmentResult(mode, pairs, leftConsidered, rightConsidered, leftUnmatched, rightUnmatched,
                maxAbs, median);
    }

    private static List<NumericObservation> timestamped(NumericSeries series) {
        List<NumericObservation> out = new ArrayList<>();
        for (NumericObservation o : series.observations()) {
            if (o.instant() != null) {
                out.add(o);
            }
        }
        return out;
    }

    private static final Comparator<NumericObservation> BY_TS_ORD = Comparator
            .comparing(NumericObservation::instant)
            .thenComparingLong(NumericObservation::sourceOrdinal);

    private static final class Candidate {
        final NumericObservation left;
        final NumericObservation right;
        final long absDelta;
        final Instant earlier;

        Candidate(NumericObservation left, NumericObservation right, long absDelta, Instant earlier) {
            this.left = left;
            this.right = right;
            this.absDelta = absDelta;
            this.earlier = earlier;
        }
    }
}
