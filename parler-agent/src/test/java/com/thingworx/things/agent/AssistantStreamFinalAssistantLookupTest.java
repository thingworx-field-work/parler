package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class AssistantStreamFinalAssistantLookupTest {

    @Test
    void findsNewestAssistantWithMessageId_ignoresDifferentAgentThing() {
        List<AssistantStreamFinalAssistantLookup.AssistantStreamRow> rows = Arrays.asList(
                new AssistantStreamFinalAssistantLookup.AssistantStreamRow("assistant", "m1", "ProducerAgent"),
                new AssistantStreamFinalAssistantLookup.AssistantStreamRow("assistant", "m2", "OtherAgent"),
                new AssistantStreamFinalAssistantLookup.AssistantStreamRow("user", "x", ""));
        assertEquals("OtherAgent",
                AssistantStreamFinalAssistantLookup.findAgentThingForAssistantMessageId(rows, "m2"));
    }

    @Test
    void lastAssistantRowWinsWhenDuplicateIds() {
        List<AssistantStreamFinalAssistantLookup.AssistantStreamRow> rows = Arrays.asList(
                new AssistantStreamFinalAssistantLookup.AssistantStreamRow("assistant", "m1", "First"),
                new AssistantStreamFinalAssistantLookup.AssistantStreamRow("assistant", "m1", "Second"));
        assertEquals("Second", AssistantStreamFinalAssistantLookup.findAgentThingForAssistantMessageId(rows, "m1"));
    }

    @Test
    void returnsEmptyStringWhenAgentThingBlank() {
        List<AssistantStreamFinalAssistantLookup.AssistantStreamRow> rows =
                List.of(new AssistantStreamFinalAssistantLookup.AssistantStreamRow("assistant", "m1", "   "));
        assertEquals("", AssistantStreamFinalAssistantLookup.findAgentThingForAssistantMessageId(rows, "m1"));
    }

    @Test
    void notFound_returnsNull() {
        assertNull(AssistantStreamFinalAssistantLookup.findAgentThingForAssistantMessageId(
                List.of(new AssistantStreamFinalAssistantLookup.AssistantStreamRow("assistant", "x", "A")), "missing"));
    }
}
