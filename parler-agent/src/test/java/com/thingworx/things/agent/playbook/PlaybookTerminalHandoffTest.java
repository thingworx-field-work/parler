package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.StreamTokenUsage;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

class PlaybookTerminalHandoffTest {

    @Test
    void appendSkippedSiblingToolResults_addsSyntheticRowsForUnexecutedSiblings() {
        List<ToolCall> batch = List.of(
                new ToolCall("tc-1", "start_playbook", "{\"playbookId\":\"cross_region_health\"}"),
                new ToolCall("tc-2", "query_alert_summary", "{\"thingName\":\"T1\"}"));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("compare regions"));
        messages.add(ChatMessage.assistantWithToolCalls(batch));
        messages.add(ChatMessage.toolResult("tc-1", "{\"status\":\"completed\"}"));

        List<ChatMessage> streamRows = new ArrayList<>();
        int skipped = PlaybookTerminalHandoff.appendSkippedSiblingToolResults(
                batch, 1, messages, (msg, usage) -> {
                    streamRows.add(msg);
                    assertEquals(StreamTokenUsage.ZERO, usage);
                });

        assertEquals(1, skipped);
        assertEquals(4, messages.size());
        ChatMessage synthetic = messages.get(3);
        assertEquals(ChatMessage.Role.TOOL, synthetic.getRole());
        assertEquals("tc-2", synthetic.getToolCallId());
        assertTrue(synthetic.getContent().contains("PLAYBOOK_TERMINAL_HANDOFF"), synthetic.getContent());
        assertTrue(streamRows.get(0).getContent().contains("PLAYBOOK_TERMINAL_HANDOFF"));

        assertEquals(1, streamRows.size());
        assertEquals("tc-2", streamRows.get(0).getToolCallId());

        assertAllToolCallsHaveMatchingToolResults(messages);
    }

    @Test
    void appendSkippedSiblingToolResults_noOpWhenNoSiblingsRemain() {
        List<ToolCall> batch = List.of(new ToolCall("tc-1", "start_playbook", "{}"));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(batch));
        messages.add(ChatMessage.toolResult("tc-1", "ok"));

        int skipped = PlaybookTerminalHandoff.appendSkippedSiblingToolResults(batch, 1, messages, null);
        assertEquals(0, skipped);
        assertEquals(2, messages.size());
    }

    private static void assertAllToolCallsHaveMatchingToolResults(List<ChatMessage> messages) {
        Set<String> toolCallIds = new HashSet<>();
        Set<String> toolResultIds = new HashSet<>();
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.ASSISTANT && message.hasToolCalls()) {
                for (ToolCall call : message.getToolCalls()) {
                    toolCallIds.add(call.getId());
                }
            }
            if (message.getRole() == ChatMessage.Role.TOOL) {
                toolResultIds.add(message.getToolCallId());
            }
        }
        assertEquals(toolCallIds, toolResultIds);
    }
}
