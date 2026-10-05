package com.thingworx.things.agent.analysis.stats;

import java.util.Arrays;
import java.util.Objects;

/**
 * Spearman average ranks for ties (DIK-1). Ties receive the mean of their ordinal ranks
 * (1-based). Sort is by value ascending, then original index ascending for determinism.
 */
public final class AverageRanks {

    private AverageRanks() {}

    public static double[] ofFinite(double[] finiteValues) {
        Objects.requireNonNull(finiteValues, "finiteValues");
        int n = finiteValues.length;
        double[] ranks = new double[n];
        if (n == 0) {
            return ranks;
        }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(finiteValues[i])) {
                throw new IllegalArgumentException("finiteValues must be finite");
            }
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> {
            int c = Double.compare(finiteValues[a], finiteValues[b]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && finiteValues[idx[j + 1]] == finiteValues[idx[i]]) {
                j++;
            }
            // 1-based ranks from i+1 through j+1 inclusive; average for ties.
            double avg = ((i + 1) + (j + 1)) / 2.0;
            for (int k = i; k <= j; k++) {
                ranks[idx[k]] = avg;
            }
            i = j + 1;
        }
        return ranks;
    }
}
