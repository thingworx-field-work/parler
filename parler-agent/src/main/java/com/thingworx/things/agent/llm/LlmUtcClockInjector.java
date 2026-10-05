package com.thingworx.things.agent.llm;

import java.time.Instant;
import java.util.List;

import com.thingworx.things.agent.ParlerTimeAnchor;

/** Inserts the single framed, ephemeral time-context row for each provider round. */
public final class LlmUtcClockInjector {

    private static final String ROW_UNIQUE_PREFIX = ParlerSuffixFraming.TIME_CONTEXT + "\n- now_utc: ";

    private LlmUtcClockInjector() {}

    public static String buildTimeBlockContent(String canonicalIanaId) {
        return buildTimeBlockContent(canonicalIanaId, Instant.now());
    }

    public static String buildTimeBlockContent(String canonicalIanaId, Instant nowUtc) {
        return ParlerSuffixFraming.TIME_CONTEXT + "\n"
                + ParlerTimeAnchor.formatTimeValues(canonicalIanaId, nowUtc);
    }

    /**
     * @return index of the inserted clock row, or {@code -1} when nothing was inserted
     */
    public static int insertForApiRound(List<ChatMessage> messages) {
        return insertForApiRound(messages, null);
    }

    public static int insertForApiRound(List<ChatMessage> messages, String canonicalIanaId) {
        if (messages == null || messages.isEmpty()) {
            return -1;
        }
        ChatMessage clock = ChatMessage.system(buildTimeBlockContent(canonicalIanaId));
        int last = messages.size() - 1;
        if (messages.get(last).getRole() == ChatMessage.Role.USER) {
            messages.add(last, clock);
            return last;
        }
        messages.add(clock);
        return messages.size() - 1;
    }

    public static void removeAtIndex(List<ChatMessage> messages, int insertedIdx) {
        if (messages == null || insertedIdx < 0 || insertedIdx >= messages.size()) {
            return;
        }
        ChatMessage m = messages.get(insertedIdx);
        if (m.getRole() == ChatMessage.Role.SYSTEM && m.getContent() != null
                && m.getContent().startsWith(ROW_UNIQUE_PREFIX)) {
            messages.remove(insertedIdx);
        }
    }

    /** @deprecated Use {@link #insertForApiRound} — prefix-cache friendly placement. */
    @Deprecated
    public static void prependForApiRound(List<ChatMessage> messages) {
        if (messages == null) {
            return;
        }
        messages.add(0, ChatMessage.system(buildTimeBlockContent(null)));
    }

    /** @deprecated Use {@link #removeAtIndex} with the index returned by {@link #insertForApiRound}. */
    @Deprecated
    public static void removeIfPrepended(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        ChatMessage first = messages.get(0);
        if (first.getRole() == ChatMessage.Role.SYSTEM && first.getContent() != null
                && first.getContent().startsWith(ROW_UNIQUE_PREFIX)) {
            messages.remove(0);
        }
    }
}
