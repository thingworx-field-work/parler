package com.thingworx.things.agent.playbook;

import java.util.List;
import java.util.function.BiConsumer;

import com.thingworx.things.agent.StreamTokenUsage;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Mid-batch {@code start_playbook} terminal handoff helpers. When the loop ends after a successful
 * playbook without executing later siblings, synthetic tool-result rows keep provider replay valid.
 */
public final class PlaybookTerminalHandoff {

    public static final String SKIPPED_TOOL_RESULT_JSON =
            "{\"status\":\"skipped\",\"code\":\"PLAYBOOK_TERMINAL_HANDOFF\","
                    + "\"message\":\"Skipped because start_playbook completed the turn.\"}";

    private PlaybookTerminalHandoff() {}

    /**
     * Appends synthetic {@code tool} rows for tool calls at indices {@code [fromIndex, batch.size())}.
     *
     * @return number of synthetic rows appended
     */
    public static int appendSkippedSiblingToolResults(
            List<ToolCall> batch,
            int fromIndex,
            List<ChatMessage> messages,
            BiConsumer<ChatMessage, StreamTokenUsage> streamAppender) {
        if (batch == null || fromIndex >= batch.size()) {
            return 0;
        }
        int appended = 0;
        for (int j = fromIndex; j < batch.size(); j++) {
            ToolCall skipped = batch.get(j);
            ChatMessage skipMsg = ChatMessage.toolResult(skipped.getId(), SKIPPED_TOOL_RESULT_JSON);
            messages.add(skipMsg);
            if (streamAppender != null) {
                streamAppender.accept(skipMsg, StreamTokenUsage.ZERO);
            }
            appended++;
        }
        return appended;
    }
}
