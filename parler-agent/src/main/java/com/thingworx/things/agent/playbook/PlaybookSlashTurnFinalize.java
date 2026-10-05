package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.thingworx.things.agent.EphemeralSystemStripHelper;
import com.thingworx.things.agent.ParlerEphemeralSystemIndices;
import com.thingworx.things.agent.llm.ChatMessage;

/**
 * Shared persistence cleanup for direct Playbook slash turns (sync {@code Chat}, {@code ChatAsync}, AlwaysOn).
 */
public final class PlaybookSlashTurnFinalize {

    private PlaybookSlashTurnFinalize() {}

    /**
     * Result of {@link #applySlashTurnPersistence}: rows to append to {@code AgentMessageStream} and durable
     * conversation size after ephemeral strip (for regression tests mirroring {@code finishPlaybookSlashTurn}).
     */
    public static final class SlashTurnPersistence {
        private final List<ChatMessage> streamRows;
        private final int durableUserAssistantCount;

        SlashTurnPersistence(List<ChatMessage> streamRows, int durableUserAssistantCount) {
            this.streamRows = Collections.unmodifiableList(new ArrayList<>(streamRows));
            this.durableUserAssistantCount = durableUserAssistantCount;
        }

        public List<ChatMessage> streamRows() {
            return streamRows;
        }

        public int durableUserAssistantCount() {
            return durableUserAssistantCount;
        }
    }

    /**
     * Appends user/assistant to conversation history, builds the two stream rows (raw user + assistant),
     * then strips per-turn ephemeral system injections — same order as {@code AgentThing.finishPlaybookSlashTurn}.
     */
    public static SlashTurnPersistence applySlashTurnPersistence(
            List<ChatMessage> messages,
            ChatMessage userMessageForModel,
            String userMessageForStream,
            String assistantText,
            ParlerEphemeralSystemIndices ephemeral) {
        if (messages == null) {
            return new SlashTurnPersistence(List.of(), 0);
        }
        if (userMessageForModel != null) {
            messages.add(userMessageForModel);
        }
        ChatMessage assistant = ChatMessage.assistant(assistantText != null ? assistantText : "");
        messages.add(assistant);
        List<ChatMessage> streamRows = List.of(
                ChatMessage.user(userMessageForStream != null ? userMessageForStream : ""),
                assistant);
        stripEphemeralInjections(messages, ephemeral);
        return new SlashTurnPersistence(streamRows, countUserAndAssistantRows(messages));
    }

    /**
     * Removes per-turn ephemeral system injections; caller must have already appended user + assistant rows.
     */
    public static void stripEphemeralInjections(List<ChatMessage> messages, ParlerEphemeralSystemIndices ephemeral) {
        if (messages == null || ephemeral == null) {
            return;
        }
        EphemeralSystemStripHelper.stripEphemeralSystemInjectionsByIndices(messages, ephemeral);
    }

    /** Counts durable rows suitable for eval stream checks (user + assistant only). */
    public static int countUserAndAssistantRows(List<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int n = 0;
        for (ChatMessage m : messages) {
            if (m.getRole() == ChatMessage.Role.USER || m.getRole() == ChatMessage.Role.ASSISTANT) {
                n++;
            }
        }
        return n;
    }
}
