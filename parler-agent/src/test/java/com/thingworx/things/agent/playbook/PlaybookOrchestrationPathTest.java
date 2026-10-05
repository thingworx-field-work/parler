package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaybookOrchestrationPathTest {

    @Test
    void arrayPath_rejectsParentAndBrackets() {
        assertTrue(PlaybookOrchestrationPath.isValidArrayPath("properties"));
        assertTrue(PlaybookOrchestrationPath.isValidArrayPath("Table0.rows"));
        assertFalse(PlaybookOrchestrationPath.isValidArrayPath("$parent.item.input"));
        assertFalse(PlaybookOrchestrationPath.isValidArrayPath("properties[name=UID]"));
        assertFalse(PlaybookOrchestrationPath.isValidArrayPath("rows[0]"));
    }

    @Test
    void fieldFrom_parentAllowedOnlyInFanOutMode() {
        assertTrue(PlaybookOrchestrationPath.isValidFieldFrom("value", false));
        assertFalse(PlaybookOrchestrationPath.isValidFieldFrom("$parent.item.name", false));
        assertTrue(PlaybookOrchestrationPath.isValidFieldFrom("$parent.item.name", true));
        assertTrue(PlaybookOrchestrationPath.isValidFieldFrom("$parent.item.input", true));
    }
}
