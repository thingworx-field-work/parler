package com.thingworx.things.agent;

import java.util.List;
import java.util.function.Supplier;

import com.thingworx.things.agent.llm.ChatMessage;

/** Applies the leading stable system row — shared by {@link AgentThing} and offline lifecycle tests. */
final class LeadingStableSystemRowSupport {

    private LeadingStableSystemRowSupport() {
    }

    static void apply(List<ChatMessage> messages, Supplier<String> leadingStableSupplier) {
        String leading = leadingStableSupplier.get();
        if (messages.isEmpty()) {
            messages.add(ChatMessage.system(leading));
        } else if (messages.get(0).getRole() == ChatMessage.Role.SYSTEM) {
            messages.set(0, ChatMessage.system(leading));
        } else {
            messages.add(0, ChatMessage.system(leading));
        }
    }
}
