package com.thingworx.things.agent.analysis.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StableStatsTest {

    @Test
    void meanVariance_twoPassDeterministic() {
        double[] values = {1.0, 2.0, 3.0, 4.0};
        StableMeanVariance a = StableMeanVariance.ofFinite(values);
        StableMeanVariance b = StableMeanVariance.ofFinite(values.clone());
        assertEquals(a.mean(), b.mean(), 0.0);
        assertEquals(a.sampleVariance(), b.sampleVariance(), 0.0);
        assertEquals(2.5, a.mean(), 1e-12);
        assertEquals(sampleVar(values), a.sampleVariance(), 1e-12);
    }

    @Test
    void pearson_perfectAndZeroVariance() {
        double[] x = {1.0, 2.0, 3.0, 4.0};
        double[] y = {2.0, 4.0, 6.0, 8.0};
        StablePearson p = StablePearson.ofPairedFinite(x, y);
        assertFalse(p.insufficient());
        assertEquals(1.0, p.pearson(), 1e-12);

        assertTrue(StablePearson.ofPairedFinite(
                new double[] {1.0, 1.0, 1.0}, new double[] {1.0, 2.0, 3.0}).insufficient());
    }

    @Test
    void ols_knownLine() {
        double[] x = {0.0, 1.0, 2.0, 3.0};
        double[] y = {1.0, 3.0, 5.0, 7.0}; // y = 1 + 2x
        SimpleOls fit = SimpleOls.fit(x, y);
        assertFalse(fit.insufficient());
        assertEquals(2.0, fit.slope(), 1e-12);
        assertEquals(1.0, fit.intercept(), 1e-12);
        assertEquals(1.0, fit.rSquared(), 1e-12);
    }

    @Test
    void averageRanks_tiesUseMean() {
        double[] values = {10.0, 20.0, 20.0, 40.0};
        double[] ranks = AverageRanks.ofFinite(values);
        assertEquals(1.0, ranks[0], 0.0);
        assertEquals(2.5, ranks[1], 0.0);
        assertEquals(2.5, ranks[2], 0.0);
        assertEquals(4.0, ranks[3], 0.0);
    }

    @Test
    void finiteMissingAccounting() {
        NumericSeries series = new NumericSeries(java.util.List.of(
                new NumericObservation(null, 0, 1.0),
                new NumericObservation(null, 1, null),
                new NumericObservation(null, 2, Double.POSITIVE_INFINITY),
                new NumericObservation(null, 3, 2.0)), "degC", true);
        assertEquals(4L, series.accounting().considered());
        assertEquals(2L, series.accounting().finite());
        assertEquals(1L, series.accounting().missing());
        assertEquals(1L, series.accounting().nonFinite());
        assertEquals("degC", series.valueUnit());
    }

    private static double sampleVar(double[] v) {
        double mean = 0.0;
        for (double x : v) {
            mean += x;
        }
        mean /= v.length;
        double sse = 0.0;
        for (double x : v) {
            double d = x - mean;
            sse += d * d;
        }
        return sse / (v.length - 1);
    }
}
