package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Bug 010 — parallel tool batch + HITL pause: siblings must bind to {@link PendingApprovalRecord} and yield synthetic
 * {@code role: tool} rows on continuation.
 */
class HitlInterruptedBatchSiblingBug010Test {

    @AfterEach
    void tearDown() {
        PendingApprovalStore.remove("pid-b010-a");
        PendingApprovalStore.remove("pid-b010-b");
    }

    @Test
    void attachInterruptedBatchSiblings_stores_gated_plus_sibling_on_record() {
        ToolCall gated = new ToolCall("call_gated", "invoke_service", "{\"x\":1}");
        ToolCall sibling = new ToolCall("call_sib", "tabulate_cached_result", "{\"y\":2}");
        List<ChatMessage> snap = new ArrayList<>();
        snap.add(ChatMessage.user("goal"));
        snap.add(ChatMessage.assistantWithToolCalls(List.of(gated, sibling)));
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "pid-b010-a",
                "rid",
                "conv",
                "u",
                "Agent",
                "remote",
                gated,
                snap,
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000);
        PendingApprovalStore.put(rec);
        assertTrue(PendingApprovalStore.attachInterruptedBatchSiblings("pid-b010-a", List.of(sibling)));
        PendingApprovalRecord loaded = PendingApprovalStore.get("pid-b010-a");
        assertEquals("call_gated", loaded.getGatedToolCall().getId());
        assertEquals(1, loaded.getInterruptedBatchSiblingToolCalls().size());
        assertEquals("call_sib", loaded.getInterruptedBatchSiblingToolCalls().get(0).getId());
    }

    @Test
    void continuation_message_reconstruction_has_one_tool_reply_per_tool_call_id() {
        ToolCall gated = new ToolCall("call_0", "invoke_service", "{}");
        ToolCall sibling = new ToolCall("call_1", "get_service_definition", "{}");
        List<ChatMessage> snap = new ArrayList<>();
        snap.add(ChatMessage.assistantWithToolCalls(List.of(gated, sibling)));
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "pid-b010-b",
                "rid2",
                "conv2",
                "u",
                "Agent",
                "remote",
                gated,
                snap,
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000);
        rec = rec.withInterruptedBatchSiblings(List.of(sibling));
        List<ChatMessage> msgs = new ArrayList<>(rec.getMessagesCopy());
        String gatedBody = "{\"status\":\"ok\",\"rows\":1}";
        msgs.add(ChatMessage.toolResult(gated.getId(), gatedBody));
        for (ToolCall tc : rec.getInterruptedBatchSiblingToolCalls()) {
            msgs.add(ChatMessage.toolResult(tc.getId(), HitlInterruptedBatchSiblingResult.JSON));
        }
        List<ToolCall> batch = null;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessage m = msgs.get(i);
            if (m.getRole() == ChatMessage.Role.ASSISTANT && !m.getToolCalls().isEmpty()) {
                batch = m.getToolCalls();
                break;
            }
        }
        assertEquals(2, batch.size());
        int toolRowsAfter = 0;
        boolean seenAssistant = false;
        for (ChatMessage m : msgs) {
            if (m.getRole() == ChatMessage.Role.ASSISTANT && !m.getToolCalls().isEmpty()) {
                seenAssistant = true;
                continue;
            }
            if (seenAssistant && m.getRole() == ChatMessage.Role.TOOL) {
                toolRowsAfter++;
            }
        }
        assertEquals(2, toolRowsAfter);
        ChatMessage sibRow = msgs.get(msgs.size() - 1);
        assertEquals("call_1", sibRow.getToolCallId());
        assertTrue(sibRow.getContent().contains("\"code\":\"HITL_PAUSE_INTERRUPTED_BATCH\""));
        assertTrue(sibRow.getContent().contains("\"status\":\"skipped\""));
    }
}
