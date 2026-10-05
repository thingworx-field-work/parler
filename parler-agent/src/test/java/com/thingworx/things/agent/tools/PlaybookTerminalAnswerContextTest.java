package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PlaybookTerminalAnswerContextTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void setAndConsumePlaybookTerminalAnswer_oncePerTurn() {
        AgentToolContext.setPlaybookTerminalAnswer("Playbook final answer.");
        assertEquals("Playbook final answer.", AgentToolContext.consumePlaybookTerminalAnswer());
        assertNull(AgentToolContext.consumePlaybookTerminalAnswer());
    }

    @Test
    void clear_removesPlaybookTerminalAnswer() {
        AgentToolContext.setPlaybookTerminalAnswer("x");
        AgentToolContext.clear();
        assertNull(AgentToolContext.consumePlaybookTerminalAnswer());
    }

    @Test
    void blankTerminalAnswer_isNotStored() {
        AgentToolContext.setPlaybookTerminalAnswer("   ");
        assertNull(AgentToolContext.consumePlaybookTerminalAnswer());
    }
}
