package com.thingworx.things.agent.fleet;

import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.analysis.stats.LinearPercentile;

/**
 * Cohort distribution summary over comparable members (fleet-rca §7.2). Quartiles/median use the
 * U5 {@code h=(n-1)p} convention via {@link LinearPercentile}.
 */
public final class FleetDistributionStats {

    private final int comparableN;
    private final double median;
    private final double q1;
    private final double q3;
    private final double mad;
    private final boolean zeroDispersion;

    private FleetDistributionStats(
            int comparableN, double median, double q1, double q3, double mad, boolean zeroDispersion) {
        this.comparableN = comparableN;
        this.median = median;
        this.q1 = q1;
        this.q3 = q3;
        this.mad = mad;
        this.zeroDispersion = zeroDispersion;
    }

    public static FleetDistributionStats of(List<ComparableMemberMetric> cohort) {
        Objects.requireNonNull(cohort, "cohort");
        if (cohort.isEmpty()) {
            throw new IllegalArgumentException("cohort must be non-empty");
        }
        double[] values = new double[cohort.size()];
        for (int i = 0; i < cohort.size(); i++) {
            values[i] = cohort.get(i).metricValue();
        }
        double median = LinearPercentile.of(values, 0.5);
        double q1 = LinearPercentile.of(values, 0.25);
        double q3 = LinearPercentile.of(values, 0.75);
        double mad = CompetitionRank.mad(cohort);
        return new FleetDistributionStats(cohort.size(), median, q1, q3, mad, mad == 0.0);
    }

    public int comparableN() {
        return comparableN;
    }

    public double median() {
        return median;
    }

    public double q1() {
        return q1;
    }

    public double q3() {
        return q3;
    }

    public double mad() {
        return mad;
    }

    /** True when MAD==0 — robust-z is undefined for every member. */
    public boolean zeroDispersion() {
        return zeroDispersion;
    }
}
