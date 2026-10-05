package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ChatCompletionsApiMessagesTest {

    @Test
    void toApiMessages_firstSystemRowOnly_notMergedWithFollowingSystemRows() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-prefix"));
        messages.add(ChatMessage.system("skill-catalog"));
        messages.add(ChatMessage.user("hello"));

        List<Map<String, Object>> api = ChatCompletionsApiMessages.toApiMessages(messages);
        assertEquals(3, api.size());
        assertEquals("system", api.get(0).get("role"));
        assertEquals("stable-prefix", api.get(0).get("content"));
        assertEquals("system", api.get(1).get("role"));
        assertEquals("skill-catalog", api.get(1).get("content"));
        assertEquals("user", api.get(2).get("role"));
    }

    @Test
    void toApiMessages_whenFirstRowNotSystem_serializesFromStart() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("only"));

        List<Map<String, Object>> api = ChatCompletionsApiMessages.toApiMessages(messages);
        assertEquals(1, api.size());
        assertEquals("user", api.get(0).get("role"));
    }

    @Test
    void toApiMessages_relocatesFramedTimeAfterCurrentUser() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable"));
        String time = LlmUtcClockInjector.buildTimeBlockContent(
                null, java.time.Instant.parse("2026-05-06T18:00:00Z"));
        messages.add(ChatMessage.system(time));
        messages.add(ChatMessage.user("u"));

        List<Map<String, Object>> api = ChatCompletionsApiMessages.toApiMessages(messages);
        assertEquals(3, api.size());
        assertEquals("user", api.get(1).get("role"));
        assertEquals("system", api.get(2).get("role"));
        assertEquals(time, api.get(2).get("content"));
    }

    @Test
    void diagnosticsIdentifyUnknownSystemWithoutLoggingContent() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("stable"),
                ChatMessage.system("unclassified-secret"),
                ChatMessage.user("u"));

        ChatCompletionsApiMessages.MessagesResult result =
                ChatCompletionsApiMessages.toApiMessagesWithDiagnostics(messages);
        SuffixClassificationDiagnostics.Entry entry = result.getDiagnostics().getEntries().get(0);
        assertEquals(1, entry.getPlannedIndex());
        assertEquals(ChatMessage.Role.SYSTEM, entry.getRole());
        assertEquals(19, entry.getCharCount());
        assertEquals(SuffixClassificationDiagnostics.DIGEST_HEX_LENGTH, entry.getContentDigest().length());
        assertEquals("b645abfd0f5b985a", entry.getContentDigest());
        assertEquals("unclassified-secret", result.getMessages().get(1).get("content"),
                "the OpenAI fallback preserves source position and content");
    }
}
