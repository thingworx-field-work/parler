package com.thingworx.things.agent.analysis.stats;

import java.util.Objects;

/**
 * Stable two-pass Pearson correlation and sample covariance (divide by {@code n-1}) over paired
 * finite values (DIK-1). Zero variance on either side yields {@link #insufficient()}.
 */
public final class StablePearson {

    private final long n;
    private final double covariance;
    private final double pearson;
    private final boolean insufficient;

    private StablePearson(long n, double covariance, double pearson, boolean insufficient) {
        this.n = n;
        this.covariance = covariance;
        this.pearson = pearson;
        this.insufficient = insufficient;
    }

    public static StablePearson ofPairedFinite(double[] x, double[] y) {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        if (x.length != y.length) {
            throw new IllegalArgumentException("x and y must have equal length");
        }
        long n = x.length;
        if (n < 2L) {
            return new StablePearson(n, Double.NaN, Double.NaN, true);
        }
        for (int i = 0; i < x.length; i++) {
            if (!Double.isFinite(x[i]) || !Double.isFinite(y[i])) {
                throw new IllegalArgumentException("paired values must be finite");
            }
        }
        StableMeanVariance mx = StableMeanVariance.ofFinite(x);
        StableMeanVariance my = StableMeanVariance.ofFinite(y);
        if (!mx.hasVariance() || !my.hasVariance() || mx.sampleStddev() == 0.0 || my.sampleStddev() == 0.0) {
            return new StablePearson(n, Double.NaN, Double.NaN, true);
        }
        double c = 0.0;
        for (int i = 0; i < x.length; i++) {
            c += (x[i] - mx.mean()) * (y[i] - my.mean());
        }
        double cov = c / (n - 1L);
        double r = cov / (mx.sampleStddev() * my.sampleStddev());
        return new StablePearson(n, cov, r, false);
    }

    public long n() {
        return n;
    }

    public double covariance() {
        return covariance;
    }

    public double pearson() {
        return pearson;
    }

    public boolean insufficient() {
        return insufficient;
    }
}
