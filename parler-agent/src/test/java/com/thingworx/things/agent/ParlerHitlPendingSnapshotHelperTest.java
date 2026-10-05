package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.PendingApprovalRecord;

import org.junit.jupiter.api.Test;

/**
 * Regression: {@link com.thingworx.things.agent.AgentThing#tryEnqueueParlerHitlPending} message snapshot must strip
 * ephemerals before {@link PendingApprovalRecord} copies — exercised without {@link AgentThing}.
 */
class ParlerHitlPendingSnapshotHelperTest {

    @Test
    void null_ephemeral_returns_defensive_copy() {
        List<ChatMessage> active = new ArrayList<>();
        active.add(ChatMessage.user("u"));
        List<ChatMessage> snap = ParlerHitlPendingSnapshotHelper.messagesForPendingSnapshot(active, null);
        assertNotSame(active, snap);
        assertEquals(1, snap.size());
        assertEquals(ChatMessage.Role.USER, snap.get(0).getRole());
        active.add(ChatMessage.user("mutate"));
        assertEquals(1, snap.size());
    }

    @Test
    void pending_record_messages_copy_omits_ephemeral_rows() {
        List<ChatMessage> active = new ArrayList<>();
        active.add(ChatMessage.user("user"));
        active.add(ChatMessage.system("catalog"));
        active.add(ChatMessage.system("host"));
        ParlerEphemeralSystemIndices idx = new ParlerEphemeralSystemIndices(1, -1, -1, -1, -1, 2, -1);
        List<ChatMessage> forPending = ParlerHitlPendingSnapshotHelper.messagesForPendingSnapshot(active, idx);
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "pid",
                "rid",
                "conv",
                "p",
                "agent",
                "remote",
                new ToolCall("t", "invoke_service", "{}"),
                forPending,
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000);
        assertEquals(1, rec.getMessagesCopy().size());
        assertEquals(ChatMessage.Role.USER, rec.getMessagesCopy().get(0).getRole());
        assertEquals(3, active.size());
        assertTrue(forPending.size() < active.size());
        long now = System.currentTimeMillis();
        assertTrue(rec.getCreatedAtEpochMillis() > 0L);
        assertTrue(rec.getCreatedAtEpochMillis() <= now);
    }
}
