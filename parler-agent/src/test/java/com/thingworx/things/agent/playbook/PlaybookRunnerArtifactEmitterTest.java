package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ToolRegistry;

class PlaybookRunnerArtifactEmitterTest {

    private static final String PLAYBOOK_JSON = "{"
            + "\"schema\":\"parler-playbook-v1\","
            + "\"title\":\"artifact-emitter\","
            + "\"budgets\":{\"timeoutSeconds\":120,\"maxNodes\":8,\"maxToolCalls\":4,\"maxEvidenceBytes\":12000},"
            + "\"nodes\":["
            + "{\"id\":\"hist\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_alert_history\","
            + "\"args\":{\"thingName\":\"DemoThing\",\"relativeDuration\":\"24h\"},"
            + "\"evidence\":{\"label\":\"Alert history window\","
            + "\"includeToolOutputRootFields\":[\"rowCount\"],"
            + "\"table\":{\"maxRows\":5,\"columns\":[\"sourceProperty\"]}}},"
            + "{\"id\":\"sum\",\"kind\":\"llm_summary\",\"dependsOn\":[\"hist\"],\"prompt\":\"Summarize.\","
            + "\"evidenceRefs\":[\"hist\"],\"maxEvidenceBytes\":12000}"
            + "],\"finalNode\":\"sum\"}";

    @Test
    void run_invokesArtifactEmitterOncePerSuccessfulToolCall() throws Exception {
        PlaybookDocument doc = PlaybookDocument.parse(PLAYBOOK_JSON);
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        var tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        PlaybookValidator.Result vr = PlaybookValidator.validateDocument(doc, tools);
        assertTrue(vr.valid(), String.join("; ", vr.errors()));

        CapturingLlmClient llm = new CapturingLlmClient();
        AtomicInteger hits = new AtomicInteger();
        PlaybookArtifactEmitter emitter = (playbookId, nodeId, toolName, toolCallId, toolResultJson) -> {
            hits.incrementAndGet();
            assertEquals("artifact_emitter_mini", playbookId);
            assertEquals("hist", nodeId);
            assertEquals("query_alert_history", toolName);
            assertFalse(toolCallId.isEmpty());
            assertTrue(toolResultJson.contains("\"status\":\"success\""));
        };
        PlaybookToolExecutor exec = (tool, jsonArgs, infotableArgs) -> PlaybookToolExecutionResult.jsonOnly(
                "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rowCount\":0,"
                        + "\"columns\":[{\"name\":\"sourceProperty\",\"baseType\":\"STRING\"}],\"rows\":[]}",
                "pb-test-" + UUID.randomUUID());

        String convKey = "junit-pb-artifact-emitter-" + UUID.randomUUID();
        PlaybookRunResult result = PlaybookRunner.run(
                doc,
                "artifact_emitter_mini",
                new JSONObject(),
                "goal",
                convKey,
                List.of(),
                exec,
                llm,
                0.0,
                200,
                emitter);

        assertEquals(PlaybookRunResult.Status.COMPLETED, result.status());
        assertEquals(1, hits.get());
    }

    private static final class CapturingLlmClient implements LlmClient {
        @Override
        public LlmResponse chat(LlmChatRequest request) {
            return new LlmResponse(
                    "ok",
                    Collections.emptyList(),
                    LlmResponse.FinishReason.STOP,
                    1,
                    1,
                    1,
                    1,
                    0,
                    0,
                    0,
                    "req-artifact-1",
                    0L);
        }

        @Override
        public LlmUsageWireIds usageWireIds() {
            return LlmUsageWireIds.forProviderThing(
                    "ParlerLlm", "OpenAIChatV5Provider", "openai-chat-completions-v5", "gpt-test");
        }

        @Override
        public boolean healthCheck() {
            return true;
        }
    }
}
