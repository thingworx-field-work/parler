package com.thingworx.things.agent.compaction;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Shared §13 character envelope estimates for {@link ChatMessage} rows (outbound planning and storage-budget trim).
 */
public final class MessageCharEstimator {

    private MessageCharEstimator() {}

    /**
     * Character estimate per §13: plain {@code SYSTEM}/{@code USER} rows use content length only; tool-call and
     * tool-result rows add small constants for provider JSON envelope (Anthropic tool_use / OpenAI tool_calls blocks
     * and tool message framing).
     */
    public static int rowChars(ChatMessage m) {
        if (m == null) {
            return 0;
        }
        switch (m.getRole()) {
            case SYSTEM:
            case USER:
                return len(m.getContent());
            case ASSISTANT:
                if (!m.hasToolCalls()) {
                    return len(m.getContent());
                }
                int s = len(m.getContent());
                for (ToolCall tc : m.getToolCalls()) {
                    if (tc == null) {
                        continue;
                    }
                    s += len(tc.getId()) + len(tc.getFunctionName()) + len(tc.getArguments()) + 80;
                }
                return s;
            case TOOL:
                return len(m.getToolCallId()) + len(m.getContent()) + 40;
            default:
                return 0;
        }
    }

    private static int len(String s) {
        return s != null ? s.length() : 0;
    }
}
