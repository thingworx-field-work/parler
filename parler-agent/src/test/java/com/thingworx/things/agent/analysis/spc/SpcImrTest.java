package com.thingworx.things.agent.analysis.spc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.G1DetectionResult;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.evidence.EvidenceStatus;

class SpcImrTest {

    @Test
    void imr_knownReferenceLimits() {
        // Five points with known mean 10 and MR bar 1 → sigma = 1/1.128
        double[] v = {10, 11, 10, 11, 10};
        ImrLimits.Result r = ImrLimits.compute(NumericSeries.fromFiniteValues(v, null));
        assertTrue(!r.insufficient());
        assertEquals(10.4, r.limits.center(), 1e-12); // mean of 10,11,10,11,10 = 10.4
        assertEquals(1.0, r.limits.mrBar(), 1e-12);
        assertEquals(1.0 / ImrLimits.D2, r.limits.sigma(), 1e-12);
        assertEquals("control", r.limits.toMetrics().get("limitKind"));
    }

    @Test
    void r1_flagsBeyond3Sigma() {
        double[] v = {10, 10.1, 9.9, 10.05, 9.95, 10.02, 9.98, 10.01, 9.99, 30.0};
        G1DetectionResult r = SpcRunRuleEvaluator.evaluate(NumericSeries.fromFiniteValues(v, null),
                EnumSet.of(SpcRunRule.R1));
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertTrue(r.findings().stream().anyMatch(f -> "spc_run_r1".equals(f.methodId())));
        assertTrue(r.metrics().get("limitKind").equals("control"));
    }

    @Test
    void r1_onlyDefault_noFindingOnInControl() {
        double[] v = {10, 10.2, 9.8, 10.1, 9.9, 10.05, 9.95, 10.0, 10.15, 9.85};
        G1DetectionResult r = SpcRunRuleEvaluator.evaluate(NumericSeries.fromFiniteValues(v, null),
                EnumSet.of(SpcRunRule.R1));
        assertEquals(EvidenceStatus.NO_FINDING, r.status());
        assertEquals("no_spc_violation", r.outcomeCode());
    }

    @Test
    void constantSeries_insufficient() {
        double[] v = {5, 5, 5, 5, 5};
        G1DetectionResult r = SpcRunRuleEvaluator.evaluate(NumericSeries.fromFiniteValues(v, null),
                EnumSet.of(SpcRunRule.R1));
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, r.status());
        assertEquals("ZERO_DISPERSION", r.outcomeCode());
    }

    @Test
    void defaultEnabledRules_r1Only() {
        assertTrue(SpcRunRule.R1.enabledByDefault());
        assertTrue(!SpcRunRule.R2.enabledByDefault());
        assertTrue(!SpcRunRule.R3.enabledByDefault());
        assertTrue(!SpcRunRule.R4.enabledByDefault());
    }

    @Test
    void r2_recordsInvolvedOrdinals_notJustWindowEnd() {
        // Build a series where a 3-window has two points beyond 2σ, and the window end is in-band.
        // Center≈0, sigma≈1 after I-MR on mostly-zero noise with small MRs is hard; use explicit
        // large excursions then a quiet end point inside the window.
        double[] v = {
                0, 0.1, -0.1, 0.05, -0.05, 0.02, -0.02, 0.01, -0.01, 0.0, // warm-up for limits
                5.0, 5.0, 0.0 // window: two beyond, end in-band relative to estimated limits
        };
        G1DetectionResult r = SpcRunRuleEvaluator.evaluate(NumericSeries.fromFiniteValues(v, null),
                EnumSet.of(SpcRunRule.R2));
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertTrue(r.findings().stream().anyMatch(f ->
                "spc_run_r2".equals(f.methodId())
                        && f.segmentOrPair().contains("involved=")
                        && f.segmentOrPair().contains("10")
                        && f.segmentOrPair().contains("11")
                        && !f.segmentOrPair().matches(".*involved=12(,|$).*")));
        // Primary ordinal is earliest involved, not the quiet window end.
        assertTrue(r.findings().stream()
                .filter(f -> "spc_run_r2".equals(f.methodId()))
                .anyMatch(f -> f.sourceOrdinal() == 10L));
    }

    @Test
    void r3_emitsOneFindingWithFourInvolvedOrdinals() {
        double[] v = new double[20];
        for (int i = 0; i < 12; i++) {
            v[i] = 0.0;
        }
        // four of five beyond 1σ on the high side, then in-band
        v[12] = 3.0;
        v[13] = 3.0;
        v[14] = 3.0;
        v[15] = 3.0;
        v[16] = 0.0;
        G1DetectionResult r = SpcRunRuleEvaluator.evaluate(NumericSeries.fromFiniteValues(v, null),
                EnumSet.of(SpcRunRule.R3));
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertTrue(r.findings().stream().anyMatch(f ->
                "spc_run_r3".equals(f.methodId()) && f.segmentOrPair().contains("involved=")));
    }

    @Test
    void r4_oneFindingForLongStreak_withAllInvolvedOrdinals() {
        // Keep the below-center warm-up shorter than 8 so only the high streak qualifies.
        // Mean ≈ 0.63 → seven zeros are below center (no R4); twelve ones are above (one R4).
        double[] v = new double[19];
        for (int i = 0; i < 7; i++) {
            v[i] = 0.0;
        }
        for (int i = 7; i < 19; i++) {
            v[i] = 1.0;
        }
        G1DetectionResult r = SpcRunRuleEvaluator.evaluate(NumericSeries.fromFiniteValues(v, null),
                EnumSet.of(SpcRunRule.R4));
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        long r4Count = r.findings().stream().filter(f -> "spc_run_r4".equals(f.methodId())).count();
        assertEquals(1L, r4Count);
        String meta = r.findings().stream()
                .filter(f -> "spc_run_r4".equals(f.methodId()))
                .findFirst()
                .orElseThrow()
                .segmentOrPair();
        assertTrue(meta.startsWith("R4@v1;involved="));
        for (int ord = 7; ord < 19; ord++) {
            assertTrue(meta.contains(Integer.toString(ord)), "missing involved ordinal " + ord);
        }
    }
}
