package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes {@link ChatMessage} rows for OpenAI-style Chat Completions.
 * <p>
 * Emits {@code messages.get(0)} alone as the first provider {@code system} row when it is a plain
 * {@code SYSTEM} message (no tool calls / tool id). Canonically framed non-leading {@code SYSTEM} rows are
 * stable-partitioned into the terminal suffix while retaining system role. Unclassified rows keep their source
 * position and authority.
 */
public final class ChatCompletionsApiMessages {

    private ChatCompletionsApiMessages() {}

    public static List<Map<String, Object>> toApiMessages(List<ChatMessage> messages) {
        return toApiMessagesWithDiagnostics(messages).getMessages();
    }

    public static MessagesResult toApiMessagesWithDiagnostics(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return new MessagesResult(out, SuffixClassificationDiagnostics.fromPlannedMessages(messages));
        }
        int i = 0;
        ChatMessage m0 = messages.get(0);
        if (LeadingSystemRow.isStableFirstSystemRow(messages)) {
            String c = m0.getContent();
            if (c != null && !c.isEmpty()) {
                Map<String, Object> row = new HashMap<>();
                row.put("role", "system");
                row.put("content", c);
                out.add(row);
            }
            i = 1;
        }
        List<ChatMessage> suffix = new ArrayList<>();
        for (; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() == ChatMessage.Role.SYSTEM
                    && ParlerSuffixFraming.isClassified(m.getContent())) {
                suffix.add(m);
            } else {
                out.add(toApiMessageRow(m));
            }
        }
        for (ParlerSuffixFraming.AuthorityClass authorityClass : ParlerSuffixFraming.AuthorityClass.values()) {
            for (ChatMessage m : suffix) {
                if (ParlerSuffixFraming.classify(m.getContent()) == authorityClass) {
                    out.add(toApiMessageRow(m));
                }
            }
        }
        return new MessagesResult(out, SuffixClassificationDiagnostics.fromPlannedMessages(messages));
    }

    public static final class MessagesResult {
        private final List<Map<String, Object>> messages;
        private final SuffixClassificationDiagnostics diagnostics;

        private MessagesResult(
                List<Map<String, Object>> messages,
                SuffixClassificationDiagnostics diagnostics) {
            this.messages = messages;
            this.diagnostics = diagnostics;
        }

        public List<Map<String, Object>> getMessages() {
            return messages;
        }

        public SuffixClassificationDiagnostics getDiagnostics() {
            return diagnostics;
        }
    }

    private static Map<String, Object> toApiMessageRow(ChatMessage m) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", m.getRole().name().toLowerCase());
        if (m.getContent() != null) {
            msg.put("content", m.getContent());
        }
        if (m.hasToolCalls()) {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (ToolCall tc : m.getToolCalls()) {
                Map<String, Object> tcMap = new HashMap<>();
                tcMap.put("id", tc.getId());
                tcMap.put("type", "function");
                Map<String, Object> fn = new HashMap<>();
                fn.put("name", tc.getFunctionName());
                fn.put("arguments", tc.getArguments() != null ? tc.getArguments() : "{}");
                tcMap.put("function", fn);
                toolCalls.add(tcMap);
            }
            msg.put("tool_calls", toolCalls);
        }
        if (m.getToolCallId() != null) {
            msg.put("tool_call_id", m.getToolCallId());
        }
        return msg;
    }
}
