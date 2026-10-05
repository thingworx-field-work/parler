package com.thingworx.things.agent.llm;

import java.util.List;

/**
 * Shared predicate for the leading stable system row used by OpenAI/Azure chat completions
 * serialization and Anthropic Messages prompt-cache wiring ({@code docs/agent/llm-token-budget.md} Phase 1).
 */
public final class LeadingSystemRow {

    private LeadingSystemRow() {}

    /**
     * The stable first system row is exactly {@code messages.get(0)} when it is a plain {@code SYSTEM}
     * message (no tool calls / tool id). Matches {@link ChatCompletionsApiMessages} behavior.
     */
    public static boolean isStableFirstSystemRow(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        ChatMessage m0 = messages.get(0);
        return m0.getRole() == ChatMessage.Role.SYSTEM
                && !m0.hasToolCalls()
                && m0.getToolCallId() == null;
    }
}
