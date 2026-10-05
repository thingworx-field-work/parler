package com.thingworx.things.agent.analysis.stats;

/**
 * Explicit finite/missing/non-finite counts for U5 numeric series (DIK-1). No imputation.
 */
public final class FiniteMissingAccounting {

    private final long considered;
    private final long finite;
    private final long missing;
    private final long nonFinite;

    public FiniteMissingAccounting(long considered, long finite, long missing, long nonFinite) {
        if (considered < 0L || finite < 0L || missing < 0L || nonFinite < 0L) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
        if (finite + missing + nonFinite != considered) {
            throw new IllegalArgumentException("finite+missing+nonFinite must equal considered");
        }
        this.considered = considered;
        this.finite = finite;
        this.missing = missing;
        this.nonFinite = nonFinite;
    }

    public static FiniteMissingAccounting ofValues(double[] valuesIncludingNaN) {
        if (valuesIncludingNaN == null) {
            return new FiniteMissingAccounting(0L, 0L, 0L, 0L);
        }
        long finite = 0L;
        long missing = 0L;
        long nonFinite = 0L;
        for (double v : valuesIncludingNaN) {
            if (Double.isNaN(v)) {
                missing++;
            } else if (!Double.isFinite(v)) {
                nonFinite++;
            } else {
                finite++;
            }
        }
        return new FiniteMissingAccounting(valuesIncludingNaN.length, finite, missing, nonFinite);
    }

    public long considered() {
        return considered;
    }

    public long finite() {
        return finite;
    }

    public long missing() {
        return missing;
    }

    public long nonFinite() {
        return nonFinite;
    }
}
