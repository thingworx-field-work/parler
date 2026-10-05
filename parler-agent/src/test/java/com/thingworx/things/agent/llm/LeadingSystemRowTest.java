package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LeadingSystemRowTest {

    @Test
    void stableFirstRow_onlyPlainSystemAtIndexZero() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable"));
        assertTrue(LeadingSystemRow.isStableFirstSystemRow(messages));
    }

    @Test
    void notStable_whenFirstRowIsUser() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("hi"));
        messages.add(ChatMessage.system("late"));
        assertFalse(LeadingSystemRow.isStableFirstSystemRow(messages));
    }

    @Test
    void notStable_whenEmpty() {
        assertFalse(LeadingSystemRow.isStableFirstSystemRow(new ArrayList<>()));
        assertFalse(LeadingSystemRow.isStableFirstSystemRow(null));
    }
}
