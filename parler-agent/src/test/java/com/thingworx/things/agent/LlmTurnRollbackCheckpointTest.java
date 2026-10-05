package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;

/**
 * Regression: rollback index must be captured after the leading stable
 * system row is inserted (rehydrated transcripts have no system row until {@code applyLeadingStableSystemRow}).
 */
class LlmTurnRollbackCheckpointTest {

    /** Mirrors {@link AgentThing} branch when first message is not SYSTEM: insert system at index 0. */
    private static void applyLeadingStableSystemRowLikeRehydratedCase(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            messages.add(ChatMessage.system("leading"));
        } else if (messages.get(0).getRole() == ChatMessage.Role.SYSTEM) {
            messages.set(0, ChatMessage.system("leading"));
        } else {
            messages.add(0, ChatMessage.system("leading"));
        }
    }

    private static void revertConversationTail(List<ChatMessage> messages, int historyExclusiveEnd) {
        while (messages.size() > historyExclusiveEnd) {
            messages.remove(messages.size() - 1);
        }
    }

    @Test
    void wrongCheckpointBeforeLeadingSystem_truncatesRestoredTranscript_on_failed_turn() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistant("a1"));
        int wrongCheckpoint = messages.size(); // 2 — the original bug pattern
        applyLeadingStableSystemRowLikeRehydratedCase(messages);
        messages.add(ChatMessage.user("u2"));
        revertConversationTail(messages, wrongCheckpoint);
        assertEquals(2, messages.size());
        assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole());
        assertEquals(ChatMessage.Role.USER, messages.get(1).getRole());
        // Lost restored assistant "a1"
    }

    @Test
    void correctCheckpointAfterLeadingSystem_preservesRestoredTranscript_on_failed_turn() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistant("a1"));
        applyLeadingStableSystemRowLikeRehydratedCase(messages);
        int correctCheckpoint = messages.size(); // 3
        messages.add(ChatMessage.user("u2"));
        revertConversationTail(messages, correctCheckpoint);
        assertEquals(3, messages.size());
        assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole());
        assertEquals(ChatMessage.Role.USER, messages.get(1).getRole());
        assertEquals(ChatMessage.Role.ASSISTANT, messages.get(2).getRole());
        assertEquals("a1", messages.get(2).getContent());
    }
}
