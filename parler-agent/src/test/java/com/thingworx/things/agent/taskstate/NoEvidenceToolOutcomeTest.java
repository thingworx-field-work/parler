package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NoEvidenceToolOutcomeTest {

    @Test
    void null_and_error_envelope_fail() {
        assertFalse(NoEvidenceToolOutcome.isSatisfiedForNoEvidencePath(null));
        assertTrue(NoEvidenceToolOutcome.isExplicitJsonErrorEnvelope("{\"status\":\"error\",\"message\":\"x\"}"));
        assertFalse(NoEvidenceToolOutcome.isSatisfiedForNoEvidencePath("{\"status\":\"error\",\"message\":\"x\"}"));
    }

    @Test
    void empty_string_succeeds() {
        assertFalse(NoEvidenceToolOutcome.isExplicitJsonErrorEnvelope(""));
        assertTrue(NoEvidenceToolOutcome.isSatisfiedForNoEvidencePath(""));
    }

    @Test
    void plain_text_succeeds() {
        assertTrue(NoEvidenceToolOutcome.isSatisfiedForNoEvidencePath("skill body markdown"));
    }

    @Test
    void status_success_json_succeeds() {
        assertTrue(NoEvidenceToolOutcome.isSatisfiedForNoEvidencePath("{\"status\":\"success\"}"));
    }

    @Test
    void non_error_json_object_succeeds() {
        assertTrue(NoEvidenceToolOutcome.isSatisfiedForNoEvidencePath("{\"rows\":[]}"));
    }
}
