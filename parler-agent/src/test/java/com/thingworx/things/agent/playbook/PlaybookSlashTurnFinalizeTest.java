package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.ParlerEphemeralSystemIndices;
import com.thingworx.things.agent.llm.ChatMessage;

/** Regression for direct Playbook slash persistence. */
class PlaybookSlashTurnFinalizeTest {

    @Test
    void stripEphemeralInjections_keepsUserAndAssistantOnly() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("leading stable"));
        messages.add(ChatMessage.system("ephemeral skill catalog"));
        messages.add(ChatMessage.user("/cross_region_health {\"assetType\":\"X\",\"regions\":[\"USA\"]}"));
        messages.add(ChatMessage.assistant("playbook answer"));
        ParlerEphemeralSystemIndices ephemeral = new ParlerEphemeralSystemIndices(1, -1, -1, -1, -1, -1, -1);
        PlaybookSlashTurnFinalize.stripEphemeralInjections(messages, ephemeral);
        assertEquals(3, messages.size());
        assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole());
        assertEquals(ChatMessage.Role.USER, messages.get(1).getRole());
        assertEquals(ChatMessage.Role.ASSISTANT, messages.get(2).getRole());
        assertEquals(2, PlaybookSlashTurnFinalize.countUserAndAssistantRows(messages));
    }

    @Test
    void applySlashTurnPersistence_matchesSyncChatStreamAndConversationContract() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("durable leading"));
        messages.add(ChatMessage.system("ephemeral skill catalog"));
        messages.add(ChatMessage.user("prior user"));
        messages.add(ChatMessage.assistant("prior assistant"));
        ParlerEphemeralSystemIndices ephemeral = new ParlerEphemeralSystemIndices(1, -1, -1, -1, -1, -1, -1);
        String slashUser = "/cross_region_health {\"assetType\":\"Stacking Robot\",\"regions\":[\"USA\",\"Germany\"]}";
        String slashAssistant = "Regional health summary.";

        PlaybookSlashTurnFinalize.SlashTurnPersistence persisted = PlaybookSlashTurnFinalize.applySlashTurnPersistence(
                messages,
                ChatMessage.user(slashUser),
                slashUser,
                slashAssistant,
                ephemeral);

        assertEquals(2, persisted.streamRows().size());
        assertEquals(ChatMessage.Role.USER, persisted.streamRows().get(0).getRole());
        assertEquals(slashUser, persisted.streamRows().get(0).getContent());
        assertEquals(ChatMessage.Role.ASSISTANT, persisted.streamRows().get(1).getRole());
        assertEquals(slashAssistant, persisted.streamRows().get(1).getContent());
        assertEquals(4, persisted.durableUserAssistantCount());
        assertFalse(messages.stream().anyMatch(m -> "ephemeral skill catalog".equals(m.getContent())));
        assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole());
        assertEquals(slashUser, messages.get(messages.size() - 2).getContent());
        assertEquals(slashAssistant, messages.get(messages.size() - 1).getContent());
    }
}
