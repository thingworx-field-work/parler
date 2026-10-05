package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.ApprovalPendingException;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ParlerHitlStreamScopedEnqueue;
import com.thingworx.things.agent.tools.PendingApprovalStore;
import com.thingworx.things.agent.tools.ToolRegistry;

/**
 * {@link PlaybookRunner} clears {@link PendingApprovalStore} when HITL uses the production
 * {@link ParlerHitlStreamScopedEnqueue} path (same helper as {@code AgentThing}), not only synthetic
 * {@link ApprovalPendingException} ids.
 */
class PlaybookRunnerStreamHitlRollbackTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void playbookToolExecutor_usesStreamScopedEnqueue_playbookRunnerRemovesPendingRow() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"invoke_service\","
                + "\"args\":{\"entityType\":\"Thing\",\"entityName\":\"DemoThing\",\"serviceName\":\"CustomWrite\","
                + "\"parameters\":{}},\"evidence\":{\"label\":\"L\"}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        PlaybookValidator.Result vr = PlaybookValidator.validateDocument(doc, tools);
        if (!vr.valid()) {
            throw new AssertionError(String.join("; ", vr.errors()));
        }

        AgentToolContext.setParlerStreamIds("req-pb-rollback", "remote-pb-rollback");
        AgentToolContext.setParlerActiveMessages(List.of(ChatMessage.user("goal")));
        AgentToolContext.setConversationId("conv-pb-rollback");

        AtomicReference<String> pendingId = new AtomicReference<>();
        PlaybookToolExecutor exec = (tool, jsonArgs, infotableArgs) -> {
            if (!"invoke_service".equals(tool)) {
                return PlaybookToolExecutionResult.jsonOnly("{\"status\":\"success\"}");
            }
            try {
                ParlerHitlStreamScopedEnqueue.enqueueOrThrow("u", "AgentJUnit",
                        new ToolCall("pb-rollback-tc", tool, jsonArgs.toString()), null, null, null, null, null);
            } catch (ApprovalPendingException e) {
                pendingId.set(e.getPendingId());
                throw e;
            }
            return PlaybookToolExecutionResult.jsonOnly("{\"status\":\"success\"}");
        };

        LlmClient noopLlm = new LlmClient() {
            @Override
            public LlmResponse chat(LlmChatRequest request) {
                return new LlmResponse("x", List.of(), LlmResponse.FinishReason.STOP, 1, 1, 1, 1, 0, 0, 0, "r", 0L);
            }

            @Override
            public LlmUsageWireIds usageWireIds() {
                return LlmUsageWireIds.forProviderThing("ParlerLlm", "T", "openai-chat-completions-v5", "gpt-test");
            }

            @Override
            public boolean healthCheck() {
                return true;
            }
        };

        String convKey = "junit-pb-stream-hitl-" + UUID.randomUUID();
        PlaybookRunResult result = PlaybookRunner.run(
                doc,
                "hitl_rollback_mini",
                new JSONObject(),
                "g",
                convKey,
                List.of(),
                exec,
                noopLlm,
                0.0,
                100);

        assertEquals(PlaybookRunResult.Status.FAILED, result.status());
        assertEquals("PLAYBOOK_HITL_REQUIRED", result.failureCode());
        assertNull(PendingApprovalStore.get(pendingId.get()), "rollback must clear store for real enqueue id");
    }
}
