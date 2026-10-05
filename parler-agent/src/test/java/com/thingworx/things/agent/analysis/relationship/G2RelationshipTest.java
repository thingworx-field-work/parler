package com.thingworx.things.agent.analysis.relationship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.stats.NumericObservation;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.evidence.EvidenceStatus;

class G2RelationshipTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    void exactAlignment_matchesOnTimestamp() {
        NumericSeries left = series(new double[] {1, 2, 3}, 0);
        NumericSeries right = series(new double[] {10, 20, 30}, 0);
        AlignmentResult a = SeriesAligner.exact(left, right);
        assertEquals(3, a.alignedPairs());
        assertEquals(0L, a.maxAbsDeltaMillis());
        assertEquals(SeriesAligner.MODE_EXACT, a.mode());
    }

    @Test
    void nearestAlignment_oneToOne_prefersSmallestDelta() {
        // left at t0,t1,t2; right offset by +200ms on middle point only within 1s tol
        List<NumericObservation> left = List.of(
                obs(0, 0, 1.0),
                obs(1, 1000, 2.0),
                obs(2, 2000, 3.0));
        List<NumericObservation> right = List.of(
                obs(0, 0, 10.0),
                obs(1, 1200, 20.0),
                obs(2, 2000, 30.0));
        AlignmentResult a = SeriesAligner.nearest(new NumericSeries(left, null, true),
                new NumericSeries(right, null, true), Duration.ofMillis(500));
        assertEquals(3, a.alignedPairs());
        assertEquals(200L, a.maxAbsDeltaMillis());
        assertEquals(1L, a.pairs().get(1).left().sourceOrdinal());
        assertEquals(1L, a.pairs().get(1).right().sourceOrdinal());
    }

    @Test
    void pearson_perfectCorrelation_isSuccess() {
        NumericSeries left = series(new double[] {1, 2, 3, 4}, 0);
        NumericSeries right = series(new double[] {2, 4, 6, 8}, 0);
        G2RelationshipResult r = AssociationEvidence.pearson(SeriesAligner.exact(left, right), "A", "B");
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertEquals("association", r.outcomeCode());
        assertEquals(1.0, Double.parseDouble(r.metrics().get("pearsonR")), 1e-12);
        assertFalse(RelationshipWording.isCausalProse(r.metrics().get("summary")));
    }

    @Test
    void pearson_nearZeroAssociation_isStillSuccess() {
        // Weak association still quantification SUCCESS (not NO_FINDING).
        NumericSeries left = series(new double[] {1, 2, 3, 4, 5, 6, 7, 8}, 0);
        NumericSeries right = series(new double[] {0.1, -0.2, 0.15, -0.05, 0.2, -0.1, 0.05, -0.15}, 0);
        G2RelationshipResult r = AssociationEvidence.pearson(SeriesAligner.exact(left, right), null, null);
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertEquals("association", r.outcomeCode());
        assertTrue(Math.abs(Double.parseDouble(r.metrics().get("pearsonR"))) < 0.5);
    }

    @Test
    void pearson_constantSeries_insufficientVariance() {
        NumericSeries left = series(new double[] {1, 1, 1, 1}, 0);
        NumericSeries right = series(new double[] {2, 3, 4, 5}, 0);
        G2RelationshipResult r = AssociationEvidence.pearson(SeriesAligner.exact(left, right), null, null);
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, r.status());
        assertEquals("insufficient_variance", r.outcomeCode());
    }

    @Test
    void spearman_tiedRanks() {
        NumericSeries left = series(new double[] {10, 20, 20, 40}, 0);
        NumericSeries right = series(new double[] {1, 2, 3, 4}, 0);
        G2RelationshipResult r = AssociationEvidence.spearman(SeriesAligner.exact(left, right), null, null);
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertTrue(r.metrics().containsKey("spearmanRho"));
    }

    @Test
    void ols_knownLine_andResidualSummary() {
        NumericSeries x = series(new double[] {0, 1, 2, 3}, 0);
        NumericSeries y = series(new double[] {1, 3, 5, 7}, 0);
        G2RelationshipResult r = AssociationEvidence.olsPair(SeriesAligner.exact(x, y), "s", "degC");
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertEquals(2.0, Double.parseDouble(r.metrics().get("slope")), 1e-12);
        assertEquals(1.0, Double.parseDouble(r.metrics().get("intercept")), 1e-12);
        assertEquals("degC / s", r.metrics().get("slopeUnit"));
    }

    @Test
    void pairwiseDeletion_countsDroppedNonFinite() {
        List<NumericObservation> left = List.of(
                obs(0, 0, 1.0),
                obs(1, 1000, Double.NaN),
                obs(2, 2000, 3.0));
        List<NumericObservation> right = List.of(
                obs(0, 0, 10.0),
                obs(1, 1000, 20.0),
                obs(2, 2000, 30.0));
        AlignmentResult a = SeriesAligner.exact(new NumericSeries(left, null, true),
                new NumericSeries(right, null, true));
        PairwiseFinitePairs p = PairwiseFinitePairs.from(a);
        assertEquals(3L, p.considered());
        assertEquals(2L, p.usedCount());
        assertEquals(1L, p.dropped());
    }

    @Test
    void lagScan_selectsShiftedAssociation_withCaveatAndPerLagFindings() {
        // y matches x at lag=+2; leading noise keeps lag=+1 from also being perfect.
        double[] x = {1, 2, 3, 4, 5, 6, 7, 8};
        double[] y = {-50, 40, 1, 2, 3, 4, 5, 6};
        AlignmentResult a = SeriesAligner.exact(series(x, 0), series(y, 0));
        G2RelationshipResult r = LagScan.evaluate(a, 3);
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertEquals(2, Integer.parseInt(r.metrics().get("bestLag")));
        assertEquals(LagScan.LAG_SELECTION_CAVEAT, r.metrics().get("lagSelectionCaveat"));
        assertEquals(7, r.findings().size()); // lags -3..+3
        assertTrue(r.findings().stream().anyMatch(f -> f.segmentOrPair().startsWith("lag=2;")));
        assertFalse(RelationshipWording.isCausalProse(r.metrics().get("summary")));
    }

    @Test
    void lagScan_tieBreak_prefersSmallerAbsLag() {
        // Perfect correlation at lag 0; other lags weaker or equal support
        double[] v = {1, 2, 3, 4, 5, 6};
        G2RelationshipResult r = LagScan.evaluate(SeriesAligner.exact(series(v, 0), series(v, 0)), 2);
        assertEquals(0, Integer.parseInt(r.metrics().get("bestLag")));
    }

    @Test
    void g2Result_forbidsNoFinding() {
        assertThrows(IllegalArgumentException.class, () -> G2RelationshipResult.builder()
                .status(EvidenceStatus.NO_FINDING)
                .outcomeCode("x")
                .methodId("pearson")
                .supportN(4)
                .build());
    }

    @Test
    void lowSupport_insufficient() {
        NumericSeries left = series(new double[] {1}, 0);
        NumericSeries right = series(new double[] {2}, 0);
        G2RelationshipResult r = AssociationEvidence.pearson(SeriesAligner.exact(left, right), null, null);
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, r.status());
        assertEquals("insufficient_support", r.outcomeCode());
    }

    private static NumericSeries series(double[] values, long startOrdinal) {
        List<NumericObservation> obs = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            obs.add(obs(startOrdinal + i, i * 1000L, values[i]));
        }
        return new NumericSeries(obs, null, true);
    }

    private static NumericObservation obs(long ordinal, long offsetMillis, double value) {
        return new NumericObservation(T0.plusMillis(offsetMillis), ordinal, value);
    }
}
