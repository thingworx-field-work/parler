package com.thingworx.things.agent.analysis.outlier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.G1DetectionResult;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.evidence.EvidenceStatus;

class G1OutlierDetectorTest {

    @Test
    void robustZ_detectsInjectedOutlier() {
        // Nine inliers around 10, one spike at 100.
        double[] v = {10, 10.1, 9.9, 10.2, 9.8, 10.05, 9.95, 10.15, 9.85, 100.0};
        G1DetectionResult r = RobustZDetector.detect(NumericSeries.fromFiniteValues(v, "u"),
                RobustZDetector.DEFAULT_THRESHOLD, ZeroDispersionPolicy.INSUFFICIENT);
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertEquals("outlier", r.outcomeCode());
        assertEquals(1, r.findings().size());
        assertEquals(9L, r.findings().get(0).sourceOrdinal());
    }

    @Test
    void robustZ_noFindingOnCleanSeries() {
        double[] v = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        G1DetectionResult r = RobustZDetector.detect(NumericSeries.fromFiniteValues(v, null),
                3.5, ZeroDispersionPolicy.INSUFFICIENT);
        assertEquals(EvidenceStatus.NO_FINDING, r.status());
        assertEquals("no_outlier", r.outcomeCode());
        assertTrue(r.supportN() > 0);
    }

    @Test
    void robustZ_constantSeries_insufficient() {
        double[] v = {5, 5, 5, 5, 5};
        G1DetectionResult r = RobustZDetector.detect(NumericSeries.fromFiniteValues(v, null),
                3.5, ZeroDispersionPolicy.INSUFFICIENT);
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, r.status());
        assertEquals("ZERO_DISPERSION", r.outcomeCode());
    }

    @Test
    void robustZ_shortSeries_insufficient() {
        G1DetectionResult r = RobustZDetector.detect(NumericSeries.fromFiniteValues(new double[] {1, 2}, null),
                3.5, ZeroDispersionPolicy.INSUFFICIENT);
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, r.status());
        assertEquals("insufficient_support", r.outcomeCode());
    }

    @Test
    void iqr_detectsFenceBreach() {
        double[] v = {1, 2, 3, 4, 5, 6, 7, 8, 9, 50};
        G1DetectionResult r = IqrDetector.detect(NumericSeries.fromFiniteValues(v, null),
                IqrDetector.DEFAULT_K, ZeroDispersionPolicy.INSUFFICIENT);
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertTrue(r.findings().stream().anyMatch(f -> f.sourceOrdinal() == 9L));
        assertEquals("iqr", r.findings().get(0).segmentOrPair());
    }

    @Test
    void iqr_and_robustZ_findingsSeparatelyLabeled() {
        double[] v = {10, 10.1, 9.9, 10.2, 9.8, 10.05, 9.95, 10.15, 9.85, 100.0};
        G1DetectionResult z = RobustZDetector.detect(NumericSeries.fromFiniteValues(v, null), 3.5,
                ZeroDispersionPolicy.INSUFFICIENT);
        G1DetectionResult i = IqrDetector.detect(NumericSeries.fromFiniteValues(v, null), 1.5,
                ZeroDispersionPolicy.INSUFFICIENT);
        assertEquals("robust_z", z.findings().get(0).segmentOrPair());
        assertEquals("iqr", i.findings().get(0).segmentOrPair());
    }
}
