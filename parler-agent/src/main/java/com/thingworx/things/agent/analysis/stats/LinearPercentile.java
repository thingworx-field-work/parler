package com.thingworx.things.agent.analysis.stats;

import java.util.Arrays;
import java.util.Objects;

/**
 * Shared {@code h=(n-1)p} linear-interpolation percentile (type-7). Frozen for U5 median/MAD/IQR
 * and aligned with {@code CachedTabularGroupMetricExecutor} summaries (D3).
 */
public final class LinearPercentile {

    private LinearPercentile() {}

    /**
     * @param values finite sample (copied and sorted); must be non-empty
     * @param p percentile in {@code [0,1]}
     */
    public static double of(double[] values, double p) {
        Objects.requireNonNull(values, "values");
        if (values.length == 0) {
            throw new IllegalArgumentException("values must be non-empty");
        }
        if (Double.isNaN(p) || p < 0.0 || p > 1.0) {
            throw new IllegalArgumentException("p must be in [0,1]");
        }
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        return ofSorted(sorted, p);
    }

    /**
     * Linear interpolation on a sorted array; {@code p} in {@code [0,1]}.
     * Same convention as historical {@code CachedTabularGroupMetricExecutor.percentileLinearSorted}.
     */
    public static double ofSorted(double[] sorted, double p) {
        Objects.requireNonNull(sorted, "sorted");
        if (sorted.length == 0) {
            throw new IllegalArgumentException("sorted must be non-empty");
        }
        if (Double.isNaN(p) || p < 0.0 || p > 1.0) {
            throw new IllegalArgumentException("p must be in [0,1]");
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = (sorted.length - 1) * p;
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        lo = Math.max(0, Math.min(sorted.length - 1, lo));
        hi = Math.max(0, Math.min(sorted.length - 1, hi));
        if (lo == hi) {
            return sorted[lo];
        }
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
    }
}
