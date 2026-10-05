package com.thingworx.things.agent.llm;

import java.util.List;

/** Inserts the one-round, no-tools instruction used to recover a blank terminal LLM response. */
public final class EmptyFinalAnswerRetryInjector {

    /** Detects only this injector's row during same-round cleanup. */
    public static final String PREFIX = "Empty final response recovery: ";

    private EmptyFinalAnswerRetryInjector() {}

    public static String buildSystemContent() {
        return ParlerSuffixFraming.SERVER_INSTRUCTION + "\n" + PREFIX
                + "return a non-empty final answer now using only evidence already available in this conversation. "
                + "Do not call tools or request more data. Preserve the user's requested language and answer directly.";
    }

    /** @return the inserted row index, or {@code -1} when no row can be inserted */
    public static int insertForApiRound(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return -1;
        }
        ChatMessage guidance = ChatMessage.system(buildSystemContent());
        int last = messages.size() - 1;
        if (messages.get(last).getRole() == ChatMessage.Role.USER) {
            messages.add(last, guidance);
            return last;
        }
        messages.add(guidance);
        return messages.size() - 1;
    }

    public static void removeAtIndex(List<ChatMessage> messages, int insertedIdx) {
        if (messages == null || insertedIdx < 0 || insertedIdx >= messages.size()) {
            return;
        }
        ChatMessage message = messages.get(insertedIdx);
        if (message.getRole() == ChatMessage.Role.SYSTEM && message.getContent() != null
                && message.getContent().startsWith(ParlerSuffixFraming.SERVER_INSTRUCTION + "\n" + PREFIX)) {
            messages.remove(insertedIdx);
        }
    }
}
