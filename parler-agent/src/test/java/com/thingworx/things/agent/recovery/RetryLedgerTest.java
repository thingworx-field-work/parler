package com.thingworx.things.agent.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RetryLedgerTest {

    @AfterEach
    void clear() {
        RetryLedger.clearAllForTests();
    }

    @Test
    void tryConsume_onceThenExhausted() {
        String inv = "inv-1";
        String key = ErrorRecoveryMapper.BUDGET_KEY_SOURCE_QUERY;
        assertTrue(RetryLedger.tryConsume(inv, key));
        assertEquals(0, RetryLedger.remaining(inv, key));
        assertFalse(RetryLedger.tryConsume(inv, key));
        assertEquals(0, RetryLedger.remaining(inv, key));
    }

    @Test
    void separateInvocationIds_independentBudgets() {
        String key = ErrorRecoveryMapper.BUDGET_KEY_SOURCE_QUERY;
        assertTrue(RetryLedger.tryConsume("a", key));
        assertTrue(RetryLedger.tryConsume("b", key));
        assertFalse(RetryLedger.tryConsume("a", key));
        assertFalse(RetryLedger.tryConsume("b", key));
    }

    @Test
    void blankIds_doNotConsume() {
        assertFalse(RetryLedger.tryConsume(null, "source-query"));
        assertFalse(RetryLedger.tryConsume("inv", null));
        assertFalse(RetryLedger.tryConsume("  ", "source-query"));
    }
}
