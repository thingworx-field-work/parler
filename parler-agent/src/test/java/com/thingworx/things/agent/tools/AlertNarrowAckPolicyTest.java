package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AlertNarrowAckPolicyTest {

    @Test
    void allowsNarrowAckOnlyForSingleRowWithoutAlertName() {
        assertTrue(AlertNarrowAckPolicy.useAcknowledgeAlertForSpecificAlerts(1, null));
        assertTrue(AlertNarrowAckPolicy.useAcknowledgeAlertForSpecificAlerts(1, ""));
        assertTrue(AlertNarrowAckPolicy.useAcknowledgeAlertForSpecificAlerts(1, "   "));
    }

    @Test
    void forbidsNarrowAckWhenAlertNameNarrows() {
        assertFalse(AlertNarrowAckPolicy.useAcknowledgeAlertForSpecificAlerts(1, "OverTemp"));
    }

    @Test
    void forbidsNarrowAckWhenMultipleRows() {
        assertFalse(AlertNarrowAckPolicy.useAcknowledgeAlertForSpecificAlerts(2, null));
        assertFalse(AlertNarrowAckPolicy.useAcknowledgeAlertForSpecificAlerts(0, null));
    }
}
