package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * CF-05: a MEASUREMENT may report SUCCESS under unproven completeness only while it discloses the
 * observed-span scope. Any other warning is not a substitute, and no other operation class gains this.
 */
class MeasurementEnvelopeValidationTest {

    private static final String SCOPE = AnalysisEnvelopeValidator.SCOPE_OBSERVED_SPAN + ": observed span only.";

    @Test
    void measurementClassIsSeparateFromU5AndU6() {
        assertTrue(AnalysisOperation.COUNTER_DELTA.isMeasurement());
        assertEquals(AnalysisOperationClass.MEASUREMENT, AnalysisOperation.COUNTER_DELTA.operationClass());
        assertFalse(AnalysisOperation.COUNTER_DELTA.isU5());
        assertFalse(AnalysisOperation.COUNTER_DELTA.isU6());
        assertFalse(AnalysisOperation.RATE_OF_CHANGE.isMeasurement());
        assertEquals("counter_delta", AnalysisOperation.COUNTER_DELTA.wireName());
    }

    @Test
    void successUnderUnknownOrPartial_needsTheScopeDisclosure() {
        for (CompletenessStatus c : new CompletenessStatus[] {CompletenessStatus.UNKNOWN, CompletenessStatus.PARTIAL}) {
            assertEquals(EvidenceStatus.SUCCESS, measurement(c).addWarning(SCOPE).build().status());
            assertEquals(EvidenceStatus.SUCCESS,
                    measurement(c).addWarning("2 segment(s) carry no increment.").addWarning(SCOPE).build().status());
            assertRejected(measurement(c));
            assertRejected(measurement(c).addWarning("2 segment(s) carry no increment."));
            assertRejected(measurement(c).addWarning("mentions " + AnalysisEnvelopeValidator.SCOPE_OBSERVED_SPAN
                    + " but does not lead with it"));
        }
    }

    @Test
    void completeMeasurement_needsNoDisclosure_andStillNeedsSupport() {
        assertEquals(EvidenceStatus.SUCCESS, measurement(CompletenessStatus.COMPLETE).build().status());
        assertRejected(measurement(CompletenessStatus.UNKNOWN).addWarning(SCOPE).n(0));
    }

    @Test
    void otherOperationClassesKeepRequiringCompleteForSuccess() {
        assertRejected(AnalysisEnvelopeBuilder.create().status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.RATE_OF_CHANGE).completeness(CompletenessStatus.UNKNOWN).n(3)
                .addWarning(SCOPE));
    }

    private static AnalysisEnvelopeBuilder measurement(CompletenessStatus completeness) {
        return AnalysisEnvelopeBuilder.create().status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.COUNTER_DELTA).completeness(completeness).n(3);
    }

    private static void assertRejected(AnalysisEnvelopeBuilder builder) {
        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
