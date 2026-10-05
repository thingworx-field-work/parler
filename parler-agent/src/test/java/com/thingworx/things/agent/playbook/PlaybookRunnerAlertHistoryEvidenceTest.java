package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

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

/**
 * Offline proof that {@code llm_summary} receives non-degenerate alert-history evidence
 * (table-projected rows + resolved window fields), not label-only fallback.
 */
class PlaybookRunnerAlertHistoryEvidenceTest {

    private static final String PLAYBOOK_JSON = "{"
            + "\"schema\":\"parler-playbook-v1\","
            + "\"title\":\"alert-history-evidence\","
            + "\"budgets\":{\"timeoutSeconds\":120,\"maxNodes\":8,\"maxToolCalls\":4,\"maxEvidenceBytes\":12000},"
            + "\"nodes\":["
            + "{\"id\":\"hist\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_alert_history\","
            + "\"args\":{\"thingName\":\"DemoThing\",\"relativeDuration\":\"24h\"},"
            + "\"evidence\":{\"label\":\"Alert history window\","
            + "\"includeToolOutputRootFields\":[\"appliedStartTime\",\"appliedEndTime\",\"rowCount\",\"thingName\"],"
            + "\"table\":{\"maxRows\":5,\"columns\":[\"sourceProperty\",\"timestamp\",\"severity\"]}}},"
            + "{\"id\":\"sum\",\"kind\":\"llm_summary\",\"dependsOn\":[\"hist\"],\"prompt\":\"Summarize evidence.\","
            + "\"evidenceRefs\":[\"hist\"],\"maxEvidenceBytes\":12000}"
            + "],\"finalNode\":\"sum\"}";

    @Test
    void run_llmSummaryUserMessage_containsProjectedRowsAndTimeWindow() throws Exception {
        PlaybookDocument doc = PlaybookDocument.parse(PLAYBOOK_JSON);
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        var tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        PlaybookValidator.Result vr = PlaybookValidator.validateDocument(doc, tools);
        assertTrue(vr.valid(), String.join("; ", vr.errors()));

        CapturingLlmClient llm = new CapturingLlmClient();
        PlaybookToolExecutor exec = (tool, jsonArgs, infotableArgs) -> {
            assertEquals("query_alert_history", tool);
            return PlaybookToolExecutionResult.jsonOnly(
                    "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rowCount\":2,"
                            + "\"appliedStartTime\":\"2024-05-31T00:00:00.000Z\","
                            + "\"appliedEndTime\":\"2024-06-07T00:00:00.000Z\","
                            + "\"thingName\":\"DemoThing\","
                            + "\"columns\":[{\"name\":\"sourceProperty\",\"baseType\":\"STRING\"}],"
                            + "\"rows\":["
                            + "{\"sourceProperty\":\"WristTemp\",\"timestamp\":\"2024-06-01T12:00:00Z\","
                            + "\"severity\":\"warning\"},"
                            + "{\"sourceProperty\":\"Voltage\",\"timestamp\":\"2024-06-02T08:00:00Z\","
                            + "\"severity\":\"info\"}"
                            + "]}");
        };

        String convKey = "junit-pb-alert-evidence-" + UUID.randomUUID();
        PlaybookRunResult result = PlaybookRunner.run(
                doc,
                "alert_history_evidence_mini",
                new JSONObject(),
                "compare assets",
                convKey,
                List.of(),
                exec,
                llm,
                0.0,
                200);

        assertEquals(PlaybookRunResult.Status.COMPLETED, result.status());
        String user = llm.lastUserContent;
        assertTrue(user.contains("Playbook evidence:"), user);
        assertTrue(user.contains("sourceProperty=WristTemp"), user);
        assertTrue(user.contains("timestamp=2024-06-01T12:00:00Z"), user);
        assertTrue(user.contains("severity=warning"), user);
        assertTrue(user.contains("appliedStartTime=2024-05-31T00:00:00.000Z"), user);
        assertTrue(user.contains("appliedEndTime=2024-06-07T00:00:00.000Z"), user);
        assertTrue(user.contains("rowCount=2"), user);
        assertTrue(user.contains("thingName=DemoThing"), user);
        int idxApplied = user.indexOf("appliedStartTime=");
        int idxRow1 = user.indexOf("row1:");
        assertTrue(idxApplied >= 0 && idxRow1 > idxApplied, user);
        assertTrue(user.contains("row2:"), user);
        assertTrue(user.contains("sourceProperty=Voltage"), user);
    }

    @Test
    void run_zeroRows_llmSummaryUserMessage_includesRootWindowAndNoRowsNote() throws Exception {
        PlaybookDocument doc = PlaybookDocument.parse(PLAYBOOK_JSON);
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        var tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        assertTrue(PlaybookValidator.validateDocument(doc, tools).valid());

        CapturingLlmClient llm = new CapturingLlmClient();
        PlaybookToolExecutor exec = (tool, jsonArgs, infotableArgs) -> PlaybookToolExecutionResult.jsonOnly(
                "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rowCount\":0,"
                        + "\"appliedStartTime\":\"2024-05-31T00:00:00.000Z\","
                        + "\"appliedEndTime\":\"2024-06-07T00:00:00.000Z\","
                        + "\"thingName\":\"DemoThing\","
                        + "\"columns\":[{\"name\":\"sourceProperty\",\"baseType\":\"STRING\"}],"
                        + "\"rows\":[]}");

        String convKey = "junit-pb-alert-evidence-zero-" + UUID.randomUUID();
        PlaybookRunResult result = PlaybookRunner.run(
                doc,
                "alert_history_evidence_zero",
                new JSONObject(),
                "compare assets",
                convKey,
                List.of(),
                exec,
                llm,
                0.0,
                200);

        assertEquals(PlaybookRunResult.Status.COMPLETED, result.status());
        String user = llm.lastUserContent;
        assertTrue(user.contains("appliedStartTime=2024-05-31T00:00:00.000Z"), user);
        assertTrue(user.contains("appliedEndTime=2024-06-07T00:00:00.000Z"), user);
        assertTrue(user.contains("rowCount=0"), user);
        assertTrue(user.contains("no table rows"), user);
        assertTrue(!user.contains("row1:"), user);
    }

    private static final class CapturingLlmClient implements LlmClient {
        String lastUserContent = "";

        @Override
        public LlmResponse chat(LlmChatRequest request) {
            for (ChatMessage m : request.getMessages()) {
                if (m.getRole() == ChatMessage.Role.USER && m.getContent() != null) {
                    lastUserContent = m.getContent();
                }
            }
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
                    "req-evidence-1",
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
