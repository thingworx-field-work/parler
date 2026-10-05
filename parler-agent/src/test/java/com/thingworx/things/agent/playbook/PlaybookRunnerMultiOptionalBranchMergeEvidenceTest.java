package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.TaxonomyRow;
import com.thingworx.things.agent.configrepo.ExtendedToolDefinition;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ToolRegistry;

/**
 * C16 proof: orchestration-class fixture applies {@code includeToolOutputPaths} / {@code evidence.table.path}
 * on the final {@code tool_call}; projected text reaches {@code llm_summary}.
 */
class PlaybookRunnerMultiOptionalBranchMergeEvidenceTest {

    private static final String EXT_TOOL = "CallGetKPIs";
    private static final String FIXTURE = "/playbook-34-35-fixture/multi_optional_branch_merge/playbook.json";

    private static String readFixture() throws Exception {
        try (InputStream in = PlaybookRunnerMultiOptionalBranchMergeEvidenceTest.class.getResourceAsStream(FIXTURE)) {
            assertTrue(in != null, "missing " + FIXTURE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<ToolDefinition> playbookToolsForFixture() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        return PlaybookToolDefinitionsMerge.merge(reg, extendedRegistry());
    }

    private static ExtendedToolRegistrySnapshot extendedRegistry() {
        ToolDefinition td = new ToolDefinition(EXT_TOOL, "kpi values", Map.of("type", "object"), true);
        ExtendedToolDefinition ext = new ExtendedToolDefinition(EXT_TOOL, "t", "w", "KpiThing", EXT_TOOL, false,
                false, td);
        return ExtendedToolRegistrySnapshot.ok(List.of(ext));
    }

    @Test
    void multiOptionalBranchMerge_nestedResultEvidence_reachesLlmSummary() throws Exception {
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        assertTrue(PlaybookValidator.validateDocument(doc, playbookToolsForFixture(), extendedRegistry()).valid());

        CapturingLlmClient llm = new CapturingLlmClient();
        PlaybookToolExecutor exec = (tool, jsonArgs, infotableArgs) -> {
            if (EXT_TOOL.equals(tool)) {
                return PlaybookToolExecutionResult.jsonOnly(new JSONObject()
                        .put("status", "success")
                        .put("resultKind", "JSON")
                        .put("result", new JSONObject()
                                .put("stats", new JSONObject().put("rowCount", 2))
                                .put("rows", new JSONArray()
                                        .put(new JSONObject().put("FilterCriteria", "PRODUCT").put("value", 100))
                                        .put(new JSONObject().put("FilterCriteria", "SHIFT").put("value", 200))))
                        .toString());
            }
            return PlaybookToolExecutionResult.jsonOnly("{\"status\":\"success\"}");
        };

        PlaybookRunResult r = PlaybookRunner.run(doc, "multi_optional_branch_merge",
                new JSONObject()
                        .put("timeWindow", "")
                        .put("productFilter", "Widget")
                        .put("shiftFilter", "Day"),
                "summarize", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(), exec, llm, 0.0, 8000);

        assertEquals(PlaybookRunResult.Status.COMPLETED, r.status(), r.assistantText());
        String user = llm.lastUserContent;
        assertTrue(user.contains("Playbook evidence:"), user);
        assertTrue(user.contains("result.stats.rowCount=2"), user);
        assertTrue(user.contains("FilterCriteria=PRODUCT"), user);
        assertTrue(user.contains("value=100"), user);
        assertTrue(user.contains("FilterCriteria=SHIFT"), user);
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
                    "ok", Collections.emptyList(), LlmResponse.FinishReason.STOP,
                    1, 1, 1, 1, 0, 0, 0, "req-merge-ev-1", 0L);
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
