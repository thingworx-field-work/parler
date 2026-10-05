package com.thingworx.things.agent.analysis.stats;

import java.util.Objects;

/**
 * Stable two-pass mean / sample variance / stddev over finite values (DIK-1). Sample variance uses
 * {@code n-1} when {@code n >= 2}.
 */
public final class StableMeanVariance {

    private final long n;
    private final double mean;
    private final double sampleVariance;
    private final double sampleStddev;

    private StableMeanVariance(long n, double mean, double sampleVariance) {
        this.n = n;
        this.mean = mean;
        this.sampleVariance = sampleVariance;
        this.sampleStddev = sampleVariance > 0.0 ? Math.sqrt(sampleVariance) : 0.0;
    }

    public static StableMeanVariance ofFinite(double[] finiteValues) {
        Objects.requireNonNull(finiteValues, "finiteValues");
        long n = finiteValues.length;
        if (n == 0L) {
            return new StableMeanVariance(0L, Double.NaN, Double.NaN);
        }
        double sum = 0.0;
        for (double v : finiteValues) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("finiteValues must contain only finite numbers");
            }
            sum += v;
        }
        double mean = sum / n;
        if (n == 1L) {
            return new StableMeanVariance(1L, mean, Double.NaN);
        }
        double sse = 0.0;
        for (double v : finiteValues) {
            double d = v - mean;
            sse += d * d;
        }
        return new StableMeanVariance(n, mean, sse / (n - 1L));
    }

    public long n() {
        return n;
    }

    public double mean() {
        return mean;
    }

    public double sampleVariance() {
        return sampleVariance;
    }

    public double sampleStddev() {
        return sampleStddev;
    }

    public boolean hasVariance() {
        return n >= 2L && Double.isFinite(sampleVariance);
    }
}
