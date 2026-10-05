package com.thingworx.things.agent.llm;

import java.util.Collections;
import java.util.List;

public class ChatMessage {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    private final Role role;
    private final String content;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;
    /** Persisted on stream rows for {@code role=tool}; not part of LLM tool JSON {@code content}. */
    private final String executedToolName;

    private ChatMessage(Role role, String content, String toolCallId, List<ToolCall> toolCalls, String executedToolName) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
        this.executedToolName = normalizeExecutedToolName(executedToolName);
    }

    private static String normalizeExecutedToolName(String executedToolName) {
        if (executedToolName == null) {
            return null;
        }
        String t = executedToolName.trim();
        return t.isEmpty() ? null : t;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null, null, null);
    }

    public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, null, null, toolCalls, null);
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, toolCallId, null, null);
    }

    /**
     * @param executedToolName LLM-facing tool id (built-in or extended); persisted on {@link AgentMessageStreamAppender}
     *                         stream rows and passed to table wire for {@code presentationTitle}; never written into
     *                         {@code content}.
     */
    public static ChatMessage toolResult(String toolCallId, String content, String executedToolName) {
        return new ChatMessage(Role.TOOL, content, toolCallId, null, executedToolName);
    }

    public Role getRole() { return role; }
    public String getContent() { return content; }
    public String getToolCallId() { return toolCallId; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public boolean hasToolCalls() { return !toolCalls.isEmpty(); }

    /** @return trimmed tool name when set on this tool-result message; {@code null} when absent */
    public String getExecutedToolName() {
        return executedToolName;
    }
}
