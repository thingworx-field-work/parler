package com.thingworx.things.agent.taskstate;

import java.util.List;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ParlerSuffixFraming;

/**
 * Inserts / removes ephemeral task-state system rows around each LLM API call (v1a).
 */
public final class TaskStateLlmInjector {

    public static final String SECTION_HEADER = "## Recent Tool Evidence";
    public static final String FRAMING_PREFIX = ParlerSuffixFraming.SERVER_OBSERVATIONS + "\n";

    private TaskStateLlmInjector() {}

    /**
     * Same placement pattern as {@link com.thingworx.things.agent.llm.LlmUtcClockInjector}: before trailing {@code USER}
     * when present, else append.
     *
     * @return inserted index, or {@code -1} when nothing inserted
     */
    public static int insertForApiRound(List<ChatMessage> messages, String renderedBlock) {
        if (messages == null || messages.isEmpty() || renderedBlock == null || renderedBlock.isEmpty()) {
            return -1;
        }
        ChatMessage block = ChatMessage.system(FRAMING_PREFIX + renderedBlock);
        int last = messages.size() - 1;
        if (messages.get(last).getRole() == ChatMessage.Role.USER) {
            messages.add(last, block);
            return last;
        }
        messages.add(block);
        return messages.size() - 1;
    }

    public static void removeAtIndex(List<ChatMessage> messages, int insertedIdx) {
        if (messages == null || insertedIdx < 0 || insertedIdx >= messages.size()) {
            return;
        }
        ChatMessage m = messages.get(insertedIdx);
        if (m.getRole() == ChatMessage.Role.SYSTEM && m.getContent() != null
                && m.getContent().startsWith(FRAMING_PREFIX + SECTION_HEADER)) {
            messages.remove(insertedIdx);
        }
    }
}
