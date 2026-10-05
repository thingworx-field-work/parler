package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

class CompletenessPropagationTest {

    @Test
    void mergeParents_neverPromotes() {
        assertEquals(CompletenessStatus.UNKNOWN,
                CompletenessPropagation.mergeParents(
                        CompletenessStatus.COMPLETE, CompletenessStatus.UNKNOWN));
        assertEquals(CompletenessStatus.PARTIAL,
                CompletenessPropagation.mergeParents(
                        CompletenessStatus.COMPLETE, CompletenessStatus.PARTIAL));
        assertEquals(CompletenessStatus.COMPLETE,
                CompletenessPropagation.mergeParents(
                        CompletenessStatus.COMPLETE, CompletenessStatus.COMPLETE));
    }

    @Test
    void gapBearingCompleteParent_remainsCompleteWhenFullyScanned() {
        // Observation gaps are quality findings; completeness stays COMPLETE for the declared
        // window when parent is COMPLETE and inputs were fully scanned (B6 gap-bearing lock).
        CompletenessStatus result = CompletenessPropagation.forTransform(
                CompletenessStatus.COMPLETE, true, true);
        assertEquals(CompletenessStatus.COMPLETE, result);
        assertTrue(CompletenessPropagation.isMonotone(CompletenessStatus.COMPLETE, result));
    }

    @Test
    void partialParent_cannotBecomeComplete() {
        CompletenessStatus result = CompletenessPropagation.forTransform(
                CompletenessStatus.PARTIAL, true, true);
        assertEquals(CompletenessStatus.PARTIAL, result);
        assertFalse(CompletenessPropagation.isMonotone(
                CompletenessStatus.PARTIAL, CompletenessStatus.COMPLETE));
    }

    @Test
    void incompleteScan_forcesUnknownOrWorse() {
        assertEquals(CompletenessStatus.UNKNOWN,
                CompletenessPropagation.forTransform(CompletenessStatus.COMPLETE, false, true));
        assertEquals(CompletenessStatus.UNKNOWN,
                CompletenessPropagation.forTransform(CompletenessStatus.COMPLETE, true, false));
    }
}
