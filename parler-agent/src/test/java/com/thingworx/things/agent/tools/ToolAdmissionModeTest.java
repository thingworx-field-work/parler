package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToolAdmissionModeTest {

    @Test
    void parsesKnownModesCaseInsensitively() {
        assertEquals(ToolAdmissionMode.OFF, ToolAdmissionMode.parse("off"));
        assertEquals(ToolAdmissionMode.NARROW, ToolAdmissionMode.parse(" Narrow "));
        assertEquals(ToolAdmissionMode.LAZY, ToolAdmissionMode.parse("LAZY"));
    }

    @Test
    void unknownNullAndBlankFallBackToOff() {
        assertEquals(ToolAdmissionMode.OFF, ToolAdmissionMode.parse(null));
        assertEquals(ToolAdmissionMode.OFF, ToolAdmissionMode.parse(""));
        assertEquals(ToolAdmissionMode.OFF, ToolAdmissionMode.parse("   "));
        assertEquals(ToolAdmissionMode.OFF, ToolAdmissionMode.parse("aggressive"));
    }
}
