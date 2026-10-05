package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.llm.ChatMessage;

import org.junit.jupiter.api.Test;

/** Regression: ephemeral strip helper must not load {@link AgentThing} (ThingWorx / ESAPI classpath). */
class EphemeralSystemStripTest {

    @Test
    void stripEphemeralSystemInjectionsByIndices_removes_in_descending_order() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("user"));
        messages.add(ChatMessage.system("catalog"));
        messages.add(ChatMessage.system("slash"));
        messages.add(ChatMessage.system("time"));
        messages.add(ChatMessage.system("taxonomy"));
        messages.add(ChatMessage.system("alert"));
        messages.add(ChatMessage.system("host"));
        int catalog = 1;
        int slash = 2;
        int time = 3;
        int tax = 4;
        int alert = 5;
        int host = 6;
        EphemeralSystemStripHelper.stripEphemeralSystemInjectionsByIndices(messages,
                catalog, slash, time, tax, alert, host, -1);
        assertEquals(1, messages.size());
        assertEquals(ChatMessage.Role.USER, messages.get(0).getRole());
    }

    @Test
    void strip_skips_negative_indices() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("keep"));
        messages.add(ChatMessage.user("u"));
        EphemeralSystemStripHelper.stripEphemeralSystemInjectionsByIndices(messages, -1, -1, -1, -1, -1, 0, -1);
        assertEquals(1, messages.size());
        assertEquals(ChatMessage.Role.USER, messages.get(0).getRole());
    }

    @Test
    void strip_accepts_parler_ephemeral_system_indices_carrier() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u"));
        messages.add(ChatMessage.system("a"));
        messages.add(ChatMessage.system("b"));
        ParlerEphemeralSystemIndices idx = new ParlerEphemeralSystemIndices(1, 2, -1, -1, -1, -1, -1);
        EphemeralSystemStripHelper.stripEphemeralSystemInjectionsByIndices(messages, idx);
        assertEquals(1, messages.size());
    }
}
