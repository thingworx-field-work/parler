package com.thingworx.things.agent.analysis.trend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.ThresholdCrossingOutcome;
import com.thingworx.things.agent.analysis.stats.NumericObservation;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.analysis.stats.SimpleOls;
import com.thingworx.things.agent.evidence.EvidenceStatus;

class G4TrendTest {

    private static final Instant T0 = Instant.parse("2024-06-01T00:00:00Z");

    @Test
    void olsTrend_knownSlope_andMseUsesNMinus2() {
        // y = 10 + 2*(t seconds); points every 1s: t=0..3 → y=10,12,14,16
        NumericSeries series = series(new double[] {10, 12, 14, 16}, 1000);
        G4TrendResult r = OlsTrend.fit(series, "degC");
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertEquals(2.0, r.slope(), 1e-9);
        assertEquals(10.0, r.intercept(), 1e-9);
        assertEquals("degC / s", r.metrics().get("slopeUnit"));
        assertEquals(OlsTrend.MSE_DENOMINATOR, r.metrics().get("mseDenominator"));
        // Perfect fit → sse=0 → mse=0 under n-2
        assertEquals(0.0, Double.parseDouble(r.metrics().get("mse")), 0.0);

        SimpleOls ols = SimpleOls.fit(new double[] {0, 1, 2, 3}, new double[] {10, 12, 14, 16});
        assertEquals(0.0, ols.mse(), 0.0);
        // n=4 → denominator 2; if it were sse/n this would still be 0 — use noisy fit below
    }

    @Test
    void simpleOls_mseDenominatorIsNMinus2_notN() {
        double[] x = {0, 1, 2, 3};
        double[] y = {1.0, 2.1, 2.9, 4.2}; // near y=1+x
        SimpleOls fit = SimpleOls.fit(x, y);
        assertFalse(fit.insufficient());
        assertEquals(fit.sse() / (x.length - 2), fit.mse(), 1e-12);
        assertTrue(Math.abs(fit.mse() - fit.sse() / x.length) > 1e-12);
    }

    @Test
    void olsTrend_flatSlope_isStillSuccess() {
        NumericSeries series = series(new double[] {5, 5, 5, 5, 5}, 1000);
        G4TrendResult r = OlsTrend.fit(series, null);
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertEquals(0.0, r.slope(), 1e-12);
    }

    @Test
    void theilSen_recoversLinearSlope() {
        NumericSeries series = series(new double[] {0, 1, 2, 3, 4}, 1000);
        G4TrendResult r = TheilSenTrend.fit(series, "u");
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertEquals(1.0, r.slope(), 1e-9);
    }

    @Test
    void crossing_withinHorizon() {
        // Rising 1 unit/s from 0; threshold 5; after 4s last=4; need 1s more; horizon 10s
        NumericSeries series = series(new double[] {0, 1, 2, 3, 4}, 1000);
        G4CrossingResult r = ThresholdCrossingEvaluator.evaluate(series,
                ThresholdCrossingEvaluator.Config.defaults()
                        .withThreshold(5.0)
                        .withHorizon(Duration.ofSeconds(10))
                        .withValueUnit("u"));
        assertEquals(ThresholdCrossingOutcome.CROSSING_WITHIN_HORIZON, r.outcome());
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertNotNull(r.estimatedCrossingAt());
        assertEquals(1, r.findings().size());
    }

    @Test
    void crossing_outsideHorizon() {
        NumericSeries series = series(new double[] {0, 1, 2, 3, 4}, 1000);
        G4CrossingResult r = ThresholdCrossingEvaluator.evaluate(series,
                ThresholdCrossingEvaluator.Config.defaults()
                        .withThreshold(100.0)
                        .withHorizon(Duration.ofSeconds(2)));
        assertEquals(ThresholdCrossingOutcome.OUTSIDE_HORIZON, r.outcome());
        assertEquals(EvidenceStatus.NO_FINDING, r.status());
        assertTrue(r.findings().isEmpty());
    }

    @Test
    void crossing_alreadyCrossed() {
        NumericSeries series = series(new double[] {0, 2, 4, 6, 8}, 1000);
        G4CrossingResult r = ThresholdCrossingEvaluator.evaluate(series,
                ThresholdCrossingEvaluator.Config.defaults().withThreshold(5.0));
        assertEquals(ThresholdCrossingOutcome.ALREADY_CROSSED, r.outcome());
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertNull(r.estimatedCrossingAt());
    }

    @Test
    void crossing_wrongDirection() {
        NumericSeries series = series(new double[] {10, 9, 8, 7, 6}, 1000);
        G4CrossingResult r = ThresholdCrossingEvaluator.evaluate(series,
                ThresholdCrossingEvaluator.Config.defaults().withThreshold(20.0));
        assertEquals(ThresholdCrossingOutcome.WRONG_DIRECTION, r.outcome());
        assertEquals(EvidenceStatus.NO_FINDING, r.status());
    }

    @Test
    void crossing_flat() {
        NumericSeries series = series(new double[] {3, 3, 3, 3, 3}, 1000);
        G4CrossingResult r = ThresholdCrossingEvaluator.evaluate(series,
                ThresholdCrossingEvaluator.Config.defaults().withThreshold(10.0));
        assertEquals(ThresholdCrossingOutcome.FLAT, r.outcome());
        assertEquals(EvidenceStatus.NO_FINDING, r.status());
    }

    @Test
    void crossing_insufficientSupport() {
        NumericSeries series = series(new double[] {1, 2}, 1000);
        G4CrossingResult r = ThresholdCrossingEvaluator.evaluate(series,
                ThresholdCrossingEvaluator.Config.defaults().withThreshold(10.0));
        assertEquals(ThresholdCrossingOutcome.INSUFFICIENT_SUPPORT, r.outcome());
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, r.status());
    }

    @Test
    void crossing_insufficientFit_rSquaredGate() {
        // Noisy series: low R² vs minRSquared=0.99
        NumericSeries series = series(new double[] {1, 5, 2, 8, 3}, 1000);
        G4CrossingResult r = ThresholdCrossingEvaluator.evaluate(series,
                ThresholdCrossingEvaluator.Config.defaults()
                        .withThreshold(100.0)
                        .withMinRSquared(0.99));
        assertEquals(ThresholdCrossingOutcome.INSUFFICIENT_FIT, r.outcome());
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, r.status());
        assertEquals("rSquared", r.metrics().get("fitGateStatistic"));
    }

    @Test
    void crossing_qualityBlocked_precedesAlreadyCrossed() {
        NumericSeries series = series(new double[] {0, 2, 4, 6, 8}, 1000);
        G4CrossingResult r = ThresholdCrossingEvaluator.evaluate(series,
                ThresholdCrossingEvaluator.Config.defaults()
                        .withThreshold(5.0)
                        .withQualityBlocked(true));
        assertEquals(ThresholdCrossingOutcome.QUALITY_BLOCKED, r.outcome());
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, r.status());
    }

    @Test
    void crossing_applicabilityFailed() {
        NumericSeries series = series(new double[] {0, 1, 2, 3, 4}, 1000);
        G4CrossingResult r = ThresholdCrossingEvaluator.evaluate(series,
                ThresholdCrossingEvaluator.Config.defaults()
                        .withThreshold(5.0)
                        .withApplicabilityFailed(true));
        assertEquals(ThresholdCrossingOutcome.APPLICABILITY_FAILED, r.outcome());
    }

    @Test
    void trendResult_forbidsNoFinding() {
        assertThrows(IllegalArgumentException.class, () -> G4TrendResult.builder()
                .status(EvidenceStatus.NO_FINDING)
                .outcomeCode("x")
                .methodId("ols_trend")
                .supportN(4)
                .slope(1.0)
                .intercept(0.0)
                .build());
    }

    @Test
    void crossing_alreadyCrossed_inWindowNotJustEndpoints() {
        // Peaks above threshold mid-window then returns below — still already_crossed.
        NumericSeries series = series(new double[] {0, 10, 0}, 1000);
        G4CrossingResult r = ThresholdCrossingEvaluator.evaluate(series,
                ThresholdCrossingEvaluator.Config.defaults().withThreshold(5.0));
        assertEquals(ThresholdCrossingOutcome.ALREADY_CROSSED, r.outcome());
        assertEquals(EvidenceStatus.SUCCESS, r.status());
    }

    @Test
    void projection_preservesSubMillisecondSeparation() {
        List<NumericObservation> obs = List.of(
                new NumericObservation(T0, 0, 1.0),
                new NumericObservation(T0.plusNanos(500_000), 1, 2.0)); // 500 µs
        TrendSeriesProjection proj = TrendSeriesProjection.from(new NumericSeries(obs, null, true));
        assertEquals(2, proj.usedCount());
        assertTrue(proj.elapsedSeconds()[1] > 0.0);
        assertEquals(0.0005, proj.elapsedSeconds()[1], 1e-12);
        Instant back = proj.instantAtElapsedSeconds(proj.elapsedSeconds()[1]);
        assertEquals(obs.get(1).instant(), back);
        G4TrendResult ols = OlsTrend.fit(new NumericSeries(obs, null, true), null);
        assertEquals(EvidenceStatus.SUCCESS, ols.status());
        G4TrendResult ts = TheilSenTrend.fit(new NumericSeries(obs, null, true), null);
        assertEquals(EvidenceStatus.SUCCESS, ts.status());
    }

    @Test
    void crossing_zeroVariance_isInsufficientFit_notSupport() {
        // Identical timestamps → zero x variance after projection.
        List<NumericObservation> obs = List.of(
                new NumericObservation(T0, 0, 1.0),
                new NumericObservation(T0, 1, 2.0),
                new NumericObservation(T0, 2, 3.0),
                new NumericObservation(T0, 3, 4.0));
        G4CrossingResult r = ThresholdCrossingEvaluator.evaluate(new NumericSeries(obs, null, true),
                ThresholdCrossingEvaluator.Config.defaults().withThreshold(10.0));
        assertEquals(ThresholdCrossingOutcome.INSUFFICIENT_FIT, r.outcome());
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, r.status());
    }

    @Test
    void theilSen_budgetExceeded_isError() {
        // Catalog maxEligiblePoints for theil_sen is 2000.
        double[] values = new double[2001];
        for (int i = 0; i < values.length; i++) {
            values[i] = i;
        }
        G4TrendResult trend = TheilSenTrend.fit(series(values, 1000), null);
        assertEquals(EvidenceStatus.ERROR, trend.status());
        assertEquals("budget_exceeded", trend.outcomeCode());

        G4CrossingResult crossing = ThresholdCrossingEvaluator.evaluate(series(values, 1000),
                ThresholdCrossingEvaluator.Config.defaults()
                        .withThreshold(10_000.0)
                        .withTheilSen(true));
        assertNull(crossing.outcome());
        assertEquals(EvidenceStatus.ERROR, crossing.status());
        assertEquals("budget_exceeded", crossing.outcomeCode());
        assertEquals("support_only", crossing.metrics().get("fitGateStatistic"));
    }

    private static NumericSeries series(double[] values, long stepMillis) {
        List<NumericObservation> obs = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            obs.add(new NumericObservation(T0.plusMillis(i * stepMillis), i, values[i]));
        }
        return new NumericSeries(obs, null, true);
    }
}
