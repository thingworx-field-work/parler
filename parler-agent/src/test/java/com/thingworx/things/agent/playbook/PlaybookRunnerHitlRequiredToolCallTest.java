package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.ApprovalPendingException;
import com.thingworx.things.agent.tools.PendingApprovalRecord;
import com.thingworx.things.agent.tools.PendingApprovalStore;

/**
 * Terminal {@code PLAYBOOK_HITL_REQUIRED} must not leave a live {@link PendingApprovalRecord}
 * behind (enqueue happens before {@link ApprovalPendingException} in {@code AgentThing}).
 */
class PlaybookRunnerHitlRequiredToolCallTest {

    private static final String PENDING_ID = "pb-hitl-terminal-test-pid";

    @AfterEach
    void tearDown() {
        PendingApprovalStore.remove(PENDING_ID);
    }

    @Test
    void approvalPendingException_removesMatchingPendingStoreEntry() throws Exception {
        ToolCall tc = new ToolCall("x", "invoke_service", "{}");
        PendingApprovalRecord rec = new PendingApprovalRecord(
                PENDING_ID,
                "rid",
                "conv",
                "u",
                "AgentThing",
                "conv",
                tc,
                Collections.emptyList(),
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000);
        PendingApprovalStore.put(rec);
        assertEquals(PENDING_ID, PendingApprovalStore.get(PENDING_ID).getPendingId());

        JSONObject node = new JSONObject()
                .put("kind", "tool_call")
                .put("tool", "invoke_service")
                .put("args", new JSONObject());
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"invoke_service\",\"args\":{}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunContext ctx = new PlaybookRunContext("run1", "pb1", new JSONObject(), new JSONObject());

        PlaybookToolExecutor exec = (tool, jsonArgs, infotableArgs) -> {
            throw new ApprovalPendingException(PENDING_ID);
        };

        JSONObject out = PlaybookRunner.executeToolCallNode("n1", node, doc, ctx, exec);
        assertEquals("failed", out.optString("status"));
        assertEquals("PLAYBOOK_HITL_REQUIRED", out.optString("errorCode"));
        assertNull(PendingApprovalStore.get(PENDING_ID), "pending row must be rolled back for terminal Playbook HITL");
    }

    @Test
    void approvalPendingException_removeIsNoOpWhenPendingIdAbsent() throws Exception {
        JSONObject node = new JSONObject()
                .put("kind", "tool_call")
                .put("tool", "invoke_service")
                .put("args", new JSONObject());
        PlaybookDocument doc = PlaybookDocument.parse(
                "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                        + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"invoke_service\",\"args\":{}},"
                        + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],\"prompt\":\"p\"}"
                        + "],\"finalNode\":\"end\"}");
        PlaybookRunContext ctx = new PlaybookRunContext("run2", "pb1", new JSONObject(), new JSONObject());
        String orphanId = "no-such-pending-in-store";
        PlaybookToolExecutor exec = (tool, jsonArgs, infotableArgs) -> {
            throw new ApprovalPendingException(orphanId);
        };
        JSONObject out = PlaybookRunner.executeToolCallNode("n1", node, doc, ctx, exec);
        assertEquals("failed", out.optString("status"));
        assertEquals("PLAYBOOK_HITL_REQUIRED", out.optString("errorCode"));
        assertNull(PendingApprovalStore.get(orphanId));
    }
}
