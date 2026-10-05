package com.thingworx.things.agent.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ErrorRecoveryMapperTest {

    @Test
    void cacheMiss_liveProven_emitsNotFoundAndReexecute() {
        TypedToolError t = ErrorRecoveryMapper.map("CACHE_MISS", "gone", true);
        assertEquals("CACHE_MISS", t.code());
        assertEquals(ErrorCategory.LIFECYCLE, t.category());
        assertEquals(ErrorRecoveryMapper.REASON_NOT_FOUND, t.reason());
        assertTrue(t.retryable());
        assertEquals(ErrorRecoveryMapper.BUDGET_KEY_SOURCE_QUERY, t.retryBudgetKey());
        assertEquals(1, t.recoveryActions().size());
        assertEquals(RecoveryActionType.REEXECUTE_SOURCE, t.recoveryActions().get(0).type());
        assertFalse(t.evidenceStillUsable());
    }

    @Test
    void cacheMiss_unproven_omitsReason() {
        TypedToolError t = ErrorRecoveryMapper.map("CACHE_MISS", "gone", false);
        assertNull(t.reason());
        assertEquals(RecoveryActionType.REEXECUTE_SOURCE, t.recoveryActions().get(0).type());
    }

    @Test
    void unknownCode_mapsToInternalCategoryPreservingCode() {
        TypedToolError t = ErrorRecoveryMapper.map("WEIRD_NEW_CODE", "x");
        assertEquals("WEIRD_NEW_CODE", t.code());
        assertEquals(ErrorCategory.INTERNAL, t.category());
        assertTrue(t.recoveryActions().isEmpty());
        assertFalse(t.retryable());
    }

    @Test
    void invalidParameters_argumentCategory() {
        TypedToolError t = ErrorRecoveryMapper.map("INVALID_PARAMETERS", "bad");
        assertEquals("INVALID_PARAMETERS", t.code());
        assertEquals(ErrorCategory.ARGUMENT, t.category());
    }
}
