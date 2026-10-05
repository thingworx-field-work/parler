package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

class PendingApprovalStoreConversationTest {

    @Test
    void hasPendingForConversationId_detects_matching_record() {
        List<ChatMessage> msgs = Collections.emptyList();
        ToolCall tc = new ToolCall("id1", "invoke_service", "{}");
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "pid-a",
                "rid-a",
                "conv-x",
                "user1",
                "AgentThing",
                "conv-x",
                tc,
                msgs,
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000);
        PendingApprovalStore.put(rec);
        assertTrue(PendingApprovalStore.hasPendingForConversationId("conv-x"));
        assertFalse(PendingApprovalStore.hasPendingForConversationId("conv-y"));
        PendingApprovalStore.remove("pid-a");
        assertFalse(PendingApprovalStore.hasPendingForConversationId("conv-x"));
    }

    @Test
    void hasPendingForConversationId_ignores_expired_records() {
        List<ChatMessage> msgs = Collections.emptyList();
        ToolCall tc = new ToolCall("id1", "invoke_service", "{}");
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "pid-exp",
                "rid-exp",
                "conv-exp",
                "user1",
                "AgentThing",
                "conv-exp",
                tc,
                msgs,
                null,
                null,
                null,
                System.currentTimeMillis() - 1);
        PendingApprovalStore.put(rec);
        assertFalse(PendingApprovalStore.hasPendingForConversationId("conv-exp"));
        PendingApprovalStore.remove("pid-exp");
    }
}
