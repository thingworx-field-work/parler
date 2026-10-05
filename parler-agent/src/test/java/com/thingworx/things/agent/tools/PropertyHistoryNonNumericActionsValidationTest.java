package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Locks: non-numeric {@code query_property_history} must not accept malformed or
 * non-textual {@code actions} shapes.
 */
class PropertyHistoryNonNumericActionsValidationTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void absent_or_empty_actions_allowed() throws Exception {
        assertNull(PropertyToolsExecutor.nonNumericPropertyHistoryActionsError(M.readTree("{}")));
        assertNull(PropertyToolsExecutor.nonNumericPropertyHistoryActionsError(M.readTree("{\"actions\":[]}")));
    }

    @Test
    void null_or_non_array_actions_invalid_shape() throws Exception {
        String e1 = PropertyToolsExecutor.nonNumericPropertyHistoryActionsError(M.readTree("{\"actions\":null}"));
        assertNotNull(e1);
        assertTrue(e1.contains("INVALID_ACTIONS_SHAPE"), e1);
        String e2 = PropertyToolsExecutor.nonNumericPropertyHistoryActionsError(M.readTree("{\"actions\":\"avg\"}"));
        assertNotNull(e2);
        assertTrue(e2.contains("INVALID_ACTIONS_SHAPE"), e2);
    }

    @Test
    void non_empty_array_rejected_even_when_elements_not_textual() throws Exception {
        String e = PropertyToolsExecutor.nonNumericPropertyHistoryActionsError(M.readTree("{\"actions\":[1]}"));
        assertNotNull(e);
        assertTrue(e.contains("NUMERIC_ACTIONS_UNSUPPORTED_FOR_PROPERTY_TYPE"), e);
        String e2 = PropertyToolsExecutor.nonNumericPropertyHistoryActionsError(M.readTree("{\"actions\":[{}]}"));
        assertNotNull(e2);
        assertTrue(e2.contains("NUMERIC_ACTIONS_UNSUPPORTED_FOR_PROPERTY_TYPE"), e2);
        String e3 = PropertyToolsExecutor.nonNumericPropertyHistoryActionsError(M.readTree("{\"actions\":[null]}"));
        assertNotNull(e3);
        assertTrue(e3.contains("NUMERIC_ACTIONS_UNSUPPORTED_FOR_PROPERTY_TYPE"), e3);
        String e4 = PropertyToolsExecutor.nonNumericPropertyHistoryActionsError(M.readTree("{\"actions\":[\"\"]}"));
        assertNotNull(e4);
        assertTrue(e4.contains("NUMERIC_ACTIONS_UNSUPPORTED_FOR_PROPERTY_TYPE"), e4);
    }
}
