package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

class PendingApprovalStoreGatewayCancelFindTest {

    private static final String PID = "pid-gateway-cancel-find";

    @AfterEach
    void tearDown() {
        PendingApprovalStore.remove(PID);
    }

    @Test
    void findPendingForConversationRequestAndPrincipal_matchesNonExpired() {
        ToolCall gated = new ToolCall("tc1", "set_property_value", "{}");
        List<ChatMessage> snap = new ArrayList<>();
        snap.add(ChatMessage.user("u"));
        snap.add(ChatMessage.assistantWithToolCalls(List.of(gated)));
        long exp = System.currentTimeMillis() + 60_000;
        PendingApprovalRecord rec = new PendingApprovalRecord(
                PID, "rid-x", "conv-x", "alice", "AgentA", "RemoteGW", gated, snap, null, null, null, exp);
        PendingApprovalStore.put(rec);
        PendingApprovalRecord got = PendingApprovalStore.findPendingForConversationRequestAndPrincipal(
                "conv-x", "rid-x", "alice", System.currentTimeMillis());
        assertNotNull(got);
        assertEquals(PID, got.getPendingId());
    }

    @Test
    void findPending_returnsNullWhenExpired() {
        ToolCall gated = new ToolCall("tc2", "set_property_value", "{}");
        List<ChatMessage> snap = List.of(ChatMessage.user("u"));
        long exp = System.currentTimeMillis() - 1;
        PendingApprovalRecord rec = new PendingApprovalRecord(
                PID, "rid-y", "conv-y", "bob", "AgentB", "RemoteGW", gated, snap, null, null, null, exp);
        PendingApprovalStore.put(rec);
        assertNull(PendingApprovalStore.findPendingForConversationRequestAndPrincipal(
                "conv-y", "rid-y", "bob", System.currentTimeMillis()));
    }
}
