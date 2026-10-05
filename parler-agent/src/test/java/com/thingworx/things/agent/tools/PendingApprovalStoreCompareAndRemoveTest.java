package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

class PendingApprovalStoreCompareAndRemoveTest {

    @AfterEach
    void tearDown() {
        PendingApprovalStore.remove("pid-car-1");
        PendingApprovalStore.remove("pid-car-2");
    }

    @Test
    void compareAndRemove_succeedsOnlyForExactReference() {
        PendingApprovalRecord rec = minimalRecord("pid-car-1");
        PendingApprovalStore.put(rec);
        PendingApprovalRecord wrong = minimalRecord("pid-car-1");
        assertNull(PendingApprovalStore.compareAndRemove("pid-car-1", wrong));
        assertSame(rec, PendingApprovalStore.get("pid-car-1"));
        assertSame(rec, PendingApprovalStore.compareAndRemove("pid-car-1", rec));
        assertNull(PendingApprovalStore.get("pid-car-1"));
    }

    @Test
    void compareAndRemove_failsAfterRecordReplacedByAttachSiblings() {
        ToolCall gated = new ToolCall("call_gated", "invoke_service", "{\"x\":1}");
        ToolCall sibling = new ToolCall("call_sib", "tabulate_cached_result", "{\"y\":2}");
        List<ChatMessage> snap = new ArrayList<>();
        snap.add(ChatMessage.user("goal"));
        snap.add(ChatMessage.assistantWithToolCalls(List.of(gated, sibling)));
        PendingApprovalRecord v1 = new PendingApprovalRecord(
                "pid-car-2",
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
        PendingApprovalStore.put(v1);
        assertTrue(PendingApprovalStore.attachInterruptedBatchSiblings("pid-car-2", List.of(sibling)));
        PendingApprovalRecord v2 = PendingApprovalStore.get("pid-car-2");
        assertNull(PendingApprovalStore.compareAndRemove("pid-car-2", v1));
        assertSame(v2, PendingApprovalStore.get("pid-car-2"));
        assertSame(v2, PendingApprovalStore.compareAndRemove("pid-car-2", v2));
        assertNull(PendingApprovalStore.get("pid-car-2"));
    }

    private static PendingApprovalRecord minimalRecord(String pendingId) {
        ToolCall tc = new ToolCall("t1", "invoke_service", "{}");
        return new PendingApprovalRecord(
                pendingId,
                "rid",
                "conv",
                "u",
                "Agent",
                "conv",
                tc,
                Collections.emptyList(),
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000);
    }
}
