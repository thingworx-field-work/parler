package com.thingworx.things.agent.analysis.stats;

import java.util.Objects;

/**
 * Simple OLS {@code y = intercept + slope * x} with residual summary (DIK-1/DIK-4). Uses two-pass
 * centered sums; insufficient when {@code n < 2} or x variance is zero.
 *
 * <p><b>MSE convention (DIK-4 pin):</b> {@code mse = sse / (n - 2)} for {@code n > 2} (residual
 * degrees of freedom for a 2-parameter fit). For {@code n == 2}, {@code mse} is {@link Double#NaN}.
 * Fit gates MUST key off {@link #rSquared()} (or an explicit residual-df statistic), not a
 * mismatched {@code sse/n} figure.
 */
public final class SimpleOls {

    private final long n;
    private final double slope;
    private final double intercept;
    private final double rSquared;
    private final double sse;
    private final double mse;
    private final double residualMean;
    private final double residualStddev;
    private final double residualMin;
    private final double residualMax;
    private final boolean insufficient;

    private SimpleOls(long n, double slope, double intercept, double rSquared, double sse, double mse,
            double residualMean, double residualStddev, double residualMin, double residualMax,
            boolean insufficient) {
        this.n = n;
        this.slope = slope;
        this.intercept = intercept;
        this.rSquared = rSquared;
        this.sse = sse;
        this.mse = mse;
        this.residualMean = residualMean;
        this.residualStddev = residualStddev;
        this.residualMin = residualMin;
        this.residualMax = residualMax;
        this.insufficient = insufficient;
    }

    public static SimpleOls fit(double[] x, double[] y) {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        if (x.length != y.length) {
            throw new IllegalArgumentException("x and y must have equal length");
        }
        long n = x.length;
        if (n < 2L) {
            return insufficient(n);
        }
        for (int i = 0; i < x.length; i++) {
            if (!Double.isFinite(x[i]) || !Double.isFinite(y[i])) {
                throw new IllegalArgumentException("values must be finite");
            }
        }
        StableMeanVariance mx = StableMeanVariance.ofFinite(x);
        StableMeanVariance my = StableMeanVariance.ofFinite(y);
        if (!mx.hasVariance() || mx.sampleVariance() == 0.0) {
            return insufficient(n);
        }
        double sxx = 0.0;
        double sxy = 0.0;
        for (int i = 0; i < x.length; i++) {
            double dx = x[i] - mx.mean();
            sxx += dx * dx;
            sxy += dx * (y[i] - my.mean());
        }
        double slope = sxy / sxx;
        double intercept = my.mean() - slope * mx.mean();
        double sse = 0.0;
        double residualMin = Double.POSITIVE_INFINITY;
        double residualMax = Double.NEGATIVE_INFINITY;
        double residualSum = 0.0;
        double[] residuals = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double fitted = intercept + slope * x[i];
            double r = y[i] - fitted;
            residuals[i] = r;
            sse += r * r;
            residualSum += r;
            residualMin = Math.min(residualMin, r);
            residualMax = Math.max(residualMax, r);
        }
        double sst = 0.0;
        for (int i = 0; i < y.length; i++) {
            double d = y[i] - my.mean();
            sst += d * d;
        }
        // Constant-y perfect fit (sse=0,sst=0) is R²=1 so flat trends remain quantification SUCCESS
        // and threshold_crossing can judge FLAT rather than insufficient_fit.
        double rSquared = sst == 0.0 ? (sse == 0.0 ? 1.0 : Double.NaN) : 1.0 - (sse / sst);
        double mse = n > 2L ? sse / (n - 2L) : Double.NaN;
        StableMeanVariance residualStats = StableMeanVariance.ofFinite(residuals);
        return new SimpleOls(n, slope, intercept, rSquared, sse, mse, residualSum / n,
                residualStats.hasVariance() ? residualStats.sampleStddev() : 0.0, residualMin, residualMax, false);
    }

    private static SimpleOls insufficient(long n) {
        return new SimpleOls(n, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, true);
    }

    public long n() {
        return n;
    }

    public double slope() {
        return slope;
    }

    public double intercept() {
        return intercept;
    }

    public double rSquared() {
        return rSquared;
    }

    public double sse() {
        return sse;
    }

    public double mse() {
        return mse;
    }

    public double residualMean() {
        return residualMean;
    }

    public double residualStddev() {
        return residualStddev;
    }

    public double residualMin() {
        return residualMin;
    }

    public double residualMax() {
        return residualMax;
    }

    public boolean insufficient() {
        return insufficient;
    }
}
