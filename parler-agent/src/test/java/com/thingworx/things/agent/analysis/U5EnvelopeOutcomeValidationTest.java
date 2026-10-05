package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

class U5EnvelopeOutcomeValidationTest {

    @Test
    void detectionNoFindingMayCarryPositiveSupport() {
        AnalysisEnvelope env = AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.NO_FINDING)
                .operation(AnalysisOperation.OUTLIER)
                .method(descriptor(AnalysisOperation.OUTLIER, "robust_z"))
                .completeness(CompletenessStatus.COMPLETE)
                .n(40)
                .metrics(Map.of(AnalysisOutcomeCodes.METRIC_KEY, "no_outlier"))
                .inputsFullyScanned(true)
                .build();
        assertTrue(AnalysisEnvelopeValidator.validate(env).isEmpty());
        assertEquals(40L, env.evidence().n());
    }

    @Test
    void detectionNoFindingRejectsZeroSupport() {
        assertThrows(IllegalArgumentException.class, () -> AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.NO_FINDING)
                .operation(AnalysisOperation.OUTLIER)
                .method(descriptor(AnalysisOperation.OUTLIER, "robust_z"))
                .completeness(CompletenessStatus.COMPLETE)
                .n(0)
                .metrics(Map.of(AnalysisOutcomeCodes.METRIC_KEY, "no_outlier"))
                .inputsFullyScanned(true)
                .build());
    }

    @Test
    void thresholdCrossingNoFindingRejectsZeroSupport() {
        assertThrows(IllegalArgumentException.class, () -> AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.NO_FINDING)
                .operation(AnalysisOperation.THRESHOLD_CROSSING)
                .method(descriptor(AnalysisOperation.THRESHOLD_CROSSING, "threshold_crossing"))
                .completeness(CompletenessStatus.COMPLETE)
                .n(0)
                .metrics(Map.of(AnalysisOutcomeCodes.METRIC_KEY, "flat"))
                .inputsFullyScanned(true)
                .build());
    }

    @Test
    void thresholdCrossingOutcomeMustMatchStatus() {
        assertThrows(IllegalArgumentException.class, () -> AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.THRESHOLD_CROSSING)
                .method(descriptor(AnalysisOperation.THRESHOLD_CROSSING, "threshold_crossing"))
                .completeness(CompletenessStatus.COMPLETE)
                .n(20)
                .metrics(Map.of(AnalysisOutcomeCodes.METRIC_KEY, "outside_horizon"))
                .build());
    }

    @Test
    void thresholdCrossingClosedMapAccepted() {
        AnalysisEnvelope env = AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.NO_FINDING)
                .operation(AnalysisOperation.THRESHOLD_CROSSING)
                .method(descriptor(AnalysisOperation.THRESHOLD_CROSSING, "threshold_crossing"))
                .completeness(CompletenessStatus.COMPLETE)
                .n(20)
                .metrics(Map.of(AnalysisOutcomeCodes.METRIC_KEY, "outside_horizon"))
                .inputsFullyScanned(true)
                .build();
        assertTrue(AnalysisEnvelopeValidator.validate(env).isEmpty());
    }

    @Test
    void alreadyCrossedIsSuccess() {
        AnalysisEnvelope env = AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.THRESHOLD_CROSSING)
                .method(descriptor(AnalysisOperation.THRESHOLD_CROSSING, "threshold_crossing"))
                .completeness(CompletenessStatus.COMPLETE)
                .n(20)
                .metrics(Map.of(AnalysisOutcomeCodes.METRIC_KEY, "already_crossed"))
                .inputsFullyScanned(true)
                .build();
        assertTrue(AnalysisEnvelopeValidator.validate(env).isEmpty());
    }

    @Test
    void quantificationRejectsNoFinding() {
        var errors = AnalysisEnvelopeValidator.validate(AnalysisEnvelope.builder()
                .status(EvidenceStatus.NO_FINDING)
                .operation(AnalysisOperation.RELATIONSHIP)
                .method(descriptor(AnalysisOperation.RELATIONSHIP, "pearson"))
                .evidence(com.thingworx.things.agent.evidence.EvidenceAssessment.builder()
                        .status(EvidenceStatus.NO_FINDING)
                        .completeness(CompletenessStatus.COMPLETE)
                        .n(0)
                        .build())
                .metrics(Map.of(AnalysisOutcomeCodes.METRIC_KEY, "zero_association"))
                .build());
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("must not use NO_FINDING")));
    }

    @Test
    void u5RequiresOutcomeCodeMetric() {
        assertThrows(IllegalArgumentException.class, () -> AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.TREND)
                .method(descriptor(AnalysisOperation.TREND, "ols_trend"))
                .completeness(CompletenessStatus.COMPLETE)
                .n(10)
                .build());
    }

    private static AnalysisMethodDescriptor descriptor(AnalysisOperation op, String id) {
        return AnalysisMethodDescriptor.builder().id(id).version("1").operation(op).build();
    }
}
