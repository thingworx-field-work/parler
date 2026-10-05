package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * DIK-0 freeze: U5 operation families, detection/quantification split, and closed
 * threshold_crossing outcome → status map.
 */
class Dik0VocabularyLockTest {

    @Test
    void u5OperationsLocked() {
        Set<AnalysisOperation> u5 = EnumSet.allOf(AnalysisOperation.class).stream()
                .filter(AnalysisOperation::isU5)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AnalysisOperation.class)));
        assertEquals(EnumSet.of(
                AnalysisOperation.OUTLIER,
                AnalysisOperation.CHANGE_POINT,
                AnalysisOperation.SPC,
                AnalysisOperation.THRESHOLD_CROSSING,
                AnalysisOperation.RELATIONSHIP,
                AnalysisOperation.TREND), u5);
    }

    @Test
    void detectionVsQuantificationSplit() {
        assertTrue(AnalysisOperation.OUTLIER.isDetection());
        assertTrue(AnalysisOperation.CHANGE_POINT.isDetection());
        assertTrue(AnalysisOperation.SPC.isDetection());
        assertTrue(AnalysisOperation.THRESHOLD_CROSSING.isDetection());
        assertTrue(AnalysisOperation.RELATIONSHIP.isQuantification());
        assertTrue(AnalysisOperation.TREND.isQuantification());
        assertFalse(AnalysisOperation.RELATIONSHIP.isDetection());
        assertFalse(AnalysisOperation.RESAMPLE.isU5());
    }

    @Test
    void thresholdCrossingOutcomeStatusClosedMap() {
        assertEquals(EvidenceStatus.SUCCESS, ThresholdCrossingOutcome.CROSSING_WITHIN_HORIZON.status());
        assertEquals(EvidenceStatus.SUCCESS, ThresholdCrossingOutcome.ALREADY_CROSSED.status());
        assertEquals(EvidenceStatus.NO_FINDING, ThresholdCrossingOutcome.OUTSIDE_HORIZON.status());
        assertEquals(EvidenceStatus.NO_FINDING, ThresholdCrossingOutcome.FLAT.status());
        assertEquals(EvidenceStatus.NO_FINDING, ThresholdCrossingOutcome.WRONG_DIRECTION.status());
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, ThresholdCrossingOutcome.INSUFFICIENT_SUPPORT.status());
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, ThresholdCrossingOutcome.INSUFFICIENT_FIT.status());
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, ThresholdCrossingOutcome.QUALITY_BLOCKED.status());
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, ThresholdCrossingOutcome.APPLICABILITY_FAILED.status());
        assertEquals(9, ThresholdCrossingOutcome.values().length);
    }

    @Test
    void capabilityCatalogCoversPlannedMethods() {
        assertTrue(U5MethodCapabilityCatalog.size() >= 15);
        assertTrue(U5MethodCapabilityCatalog.find("robust_z").isPresent());
        assertTrue(U5MethodCapabilityCatalog.find("theil_sen").isPresent());
        assertTrue(U5MethodCapabilityCatalog.find("threshold_crossing").isPresent());
        assertTrue(U5MethodCapabilityCatalog.find("spc_run_r1").orElseThrow().enabledByDefault());
        assertFalse(U5MethodCapabilityCatalog.find("spc_run_r2").orElseThrow().enabledByDefault());
    }
}
