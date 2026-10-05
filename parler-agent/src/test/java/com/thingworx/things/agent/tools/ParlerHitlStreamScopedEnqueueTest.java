package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Production HITL enqueue path is delegated to {@link ParlerHitlStreamScopedEnqueue}; JVM tests assert
 * {@link PendingApprovalStore} id matches {@link ApprovalPendingException#getPendingId()} (same invariant as
 * {@code AgentThing#dispatchExecuteToolCallWithoutRepetitionGuard} for non-bypass {@code invoke_service}).
 */
class ParlerHitlStreamScopedEnqueueTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void whenStreamContextMissing_returnsWithoutEnqueue() {
        ToolCall tc = new ToolCall("t1", "invoke_service", "{}");
        assertDoesNotThrow(() -> ParlerHitlStreamScopedEnqueue.enqueueOrThrow("p", "AgentJUnit", tc, null, null, null,
                null, null));
    }

    @Test
    void approvalRequiresParlerContextJson_isABlockedResultNamingTheTool() throws Exception {
        com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                ParlerHitlStreamScopedEnqueue.approvalRequiresParlerContextJson("restart_\"pump\""));
        assertEquals("blocked", n.path("status").asText());
        assertEquals(ParlerHitlStreamScopedEnqueue.CODE_APPROVAL_REQUIRES_PARLER_CONTEXT, n.path("code").asText());
        assertTrue(n.path("message").asText().startsWith("restart_\"pump\" requires human approval"));
    }

    @Test
    void whenStreamContextPrimed_putPendingIdMatchesThrownPendingId() {
        AgentToolContext.setParlerStreamIds("req-parler-hitl-jvm", "remote-conv-1");
        AgentToolContext.setParlerActiveMessages(List.of(ChatMessage.user("run invoke")));
        AgentToolContext.setConversationId("conv-parler-hitl-jvm");
        ToolCall tc = new ToolCall("tc-hitl", "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"DemoThing\",\"serviceName\":\"CustomWrite\",\"parameters\":{}}");
        ApprovalPendingException ex = assertThrows(ApprovalPendingException.class,
                () -> ParlerHitlStreamScopedEnqueue.enqueueOrThrow("principal-x", "AgentJUnit", tc, null, null, null,
                        null, null));
        String pid = ex.getPendingId();
        PendingApprovalRecord rec = PendingApprovalStore.get(pid);
        assertEquals(pid, rec.getPendingId());
        assertEquals("invoke_service", rec.getGatedToolCall().getFunctionName());
        assertEquals("tc-hitl", rec.getGatedToolCall().getId());
        PendingApprovalStore.remove(pid);
        assertNull(PendingApprovalStore.get(pid));
    }

    @Test
    void whenStreamContextPrimed_extendedToolFields_roundTripOnRecord() {
        AgentToolContext.setParlerStreamIds("req-ext", "remote-ext");
        AgentToolContext.setParlerActiveMessages(Collections.singletonList(ChatMessage.user("x")));
        AgentToolContext.setConversationId("c-ext");
        ToolCall tc = new ToolCall("e1", "my_ext_tool", "{}");
        ApprovalPendingException ex = assertThrows(ApprovalPendingException.class,
                () -> ParlerHitlStreamScopedEnqueue.enqueueOrThrow("p", "AgentJUnit", tc, null, null, null,
                        "TargetThing", "RunService"));
        PendingApprovalRecord rec = PendingApprovalStore.get(ex.getPendingId());
        assertEquals("TargetThing", rec.getExtendedToolTargetEntityName());
        assertEquals("RunService", rec.getExtendedToolTargetServiceName());
        PendingApprovalStore.remove(ex.getPendingId());
    }
}
