package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class LlmUtcClockInjectorTest {

    @Test
    void buildTimeBlockContent_isFramedAndContainsValuesOnly() {
        String s = LlmUtcClockInjector.buildTimeBlockContent(
                "America/New_York", Instant.parse("2026-05-06T18:00:00Z"));
        assertTrue(s.startsWith(ParlerSuffixFraming.TIME_CONTEXT + "\n- now_utc: "));
        assertTrue(s.contains("- now_local: 2026-05-06T14:00:00.000-04:00"));
        assertTrue(s.contains("- user_timezone: America/New_York"));
        assertTrue(!s.contains("calendarPhrase"), "stable guidance must not remain in the volatile row");
    }

    @Test
    void insertForApiRound_insertsImmediatelyBeforeTrailingUser() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("leading"));
        messages.add(ChatMessage.user("hi"));
        int idx = LlmUtcClockInjector.insertForApiRound(messages, "America/New_York");
        assertEquals(1, idx);
        assertEquals(ChatMessage.Role.SYSTEM, messages.get(1).getRole());
        assertTrue(messages.get(1).getContent().startsWith(ParlerSuffixFraming.TIME_CONTEXT));
        assertEquals(ChatMessage.Role.USER, messages.get(2).getRole());
    }

    @Test
    void insertForApiRound_appendsAfterToolTailWhenLastIsNotUser() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("leading"));
        messages.add(ChatMessage.user("hi"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of()));
        messages.add(ChatMessage.toolResult("id1", "{}"));
        int idx = LlmUtcClockInjector.insertForApiRound(messages);
        assertEquals(4, idx);
        assertEquals(ChatMessage.Role.TOOL, messages.get(3).getRole());
        assertEquals(ChatMessage.Role.SYSTEM, messages.get(4).getRole());
        assertTrue(messages.get(4).getContent().startsWith(ParlerSuffixFraming.TIME_CONTEXT));
    }

    @Test
    void removeAtIndex_stripsOnlyOurClockRow() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("hi"));
        int idx = LlmUtcClockInjector.insertForApiRound(messages);
        assertEquals(0, idx);
        LlmUtcClockInjector.removeAtIndex(messages, idx);
        assertEquals(1, messages.size());
        assertEquals(ChatMessage.Role.USER, messages.get(0).getRole());
    }

    @Test
    void removeAtIndex_doesNotStripAnotherTimeClassRowWithoutNowUtc() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(ParlerSuffixFraming.TIME_CONTEXT + "\nnot-the-clock-row"));
        LlmUtcClockInjector.removeAtIndex(messages, 0);
        assertEquals(1, messages.size());
    }
}
