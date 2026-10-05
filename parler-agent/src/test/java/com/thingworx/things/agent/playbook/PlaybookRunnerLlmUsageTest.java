package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.StreamTokenUsage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolDefinition;

/** {@link PlaybookRunner} telemetry: internal {@code llm_summary} usage surfaces on {@link PlaybookRunResult}. */
class PlaybookRunnerLlmUsageTest {

    private static final String MINI_PLAYBOOK = "{"
            + "\"schema\":\"parler-playbook-v1\","
            + "\"title\":\"telemetry-mini\","
            + "\"nodes\":["
            + "{\"id\":\"x\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"noop_pb\",\"args\":{},"
            + "\"evidence\":{\"label\":\"L\"}},"
            + "{\"id\":\"sum\",\"kind\":\"llm_summary\",\"dependsOn\":[\"x\"],\"prompt\":\"Reply ok.\","
            + "\"evidenceRefs\":[\"x\"],\"maxEvidenceBytes\":8000}"
            + "],\"finalNode\":\"sum\"}";

    @Test
    void run_accumulatesLlmSummaryUsageOnResult() throws Exception {
        PlaybookDocument doc = PlaybookDocument.parse(MINI_PLAYBOOK);
        List<ToolDefinition> tools = List.of(new ToolDefinition("noop_pb", "n",
                Map.of("type", "object", "properties", Map.of()), true));
        PlaybookValidator.Result vr = PlaybookValidator.validateDocument(doc, tools);
        assertTrue(vr.valid(), String.valueOf(vr.errors()));

        LlmClient llm = new LlmClient() {
            @Override
            public LlmResponse chat(LlmChatRequest request) {
                return new LlmResponse(
                        "ok",
                        Collections.emptyList(),
                        LlmResponse.FinishReason.STOP,
                        12,
                        3,
                        12,
                        3,
                        0,
                        0,
                        0,
                        "req-pb-1",
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
        };

        PlaybookToolExecutor exec = (tool, jsonArgs, infotableArgs) -> PlaybookToolExecutionResult.jsonOnly(
                "{\"rowCount\":0}");

        String convKey = "junit-pb-usage-" + UUID.randomUUID();
        PlaybookRunResult result = PlaybookRunner.run(
                doc,
                "telemetry_mini",
                new JSONObject(),
                "goal",
                convKey,
                List.of(),
                exec,
                llm,
                0.0,
                100);

        assertEquals(PlaybookRunResult.Status.COMPLETED, result.status());
        StreamTokenUsage u = result.llmUsage();
        assertEquals(12, u.getPromptTokens());
        assertEquals(3, u.getCompletionTokens());
        assertTrue(u.getLlmUsageJson().contains("\"completionTokens\":3"), u.getLlmUsageJson());
        assertTrue(u.getLlmUsageJson().contains("\"requestId\":\"req-pb-1\""), u.getLlmUsageJson());
        assertTrue(u.getLlmUsageJson().contains("\"parlerRequestId\":\"\""), u.getLlmUsageJson());
    }
}
