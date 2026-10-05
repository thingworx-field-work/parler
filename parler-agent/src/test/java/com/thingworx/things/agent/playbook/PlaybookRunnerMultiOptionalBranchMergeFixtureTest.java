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
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ToolRegistry;

/**
 * End-to-end {@link PlaybookRunner} for topic {@code playbook-engine-workshop-gaps} M3:
 * three optional branches → {@code merge_row_sets} → nested Filters payload.
 */
class PlaybookRunnerMultiOptionalBranchMergeFixtureTest {

    private static final String EXT_TOOL = "CallGetKPIs";
    private static final String FIXTURE = "/playbook-34-35-fixture/multi_optional_branch_merge/playbook.json";

    private static String readFixture() throws Exception {
        try (InputStream in = PlaybookRunnerMultiOptionalBranchMergeFixtureTest.class.getResourceAsStream(FIXTURE)) {
            assertTrue(in != null, "missing " + FIXTURE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class RunCapture {
        String filtersJson;
    }

    private static PlaybookToolExecutor stubExecutor(RunCapture cap) {
        return (tool, jsonArgs, infotableArgs) -> {
            if (EXT_TOOL.equals(tool)) {
                cap.filtersJson = jsonArgs.optString("Filters", "");
                return PlaybookToolExecutionResult.jsonOnly(new JSONObject()
                        .put("status", "success")
                        .put("resultKind", "INFOTABLE")
                        .put("rows", new JSONArray())
                        .toString());
            }
            return PlaybookToolExecutionResult.jsonOnly("{\"status\":\"success\"}");
        };
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

    private static JSONArray filterCriteriaNames(String filtersJson) {
        JSONObject payload = new JSONObject(filtersJson);
        return payload.getJSONArray("Filters").getJSONObject(0).getJSONArray("FilterCriterias");
    }

    @Test
    void multiOptionalBranchMerge_allOptionalAbsent_emptyFilterCriterias() throws Exception {
        RunCapture cap = new RunCapture();
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        assertTrue(PlaybookValidator.validateDocument(doc, playbookToolsForFixture(), extendedRegistry()).valid());

        PlaybookRunResult r = PlaybookRunner.run(doc, "multi_optional_branch_merge",
                new JSONObject().put("timeWindow", ""),
                "show merged filters", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(), stubExecutor(cap),
                new StubLlm(), 0.0, 800);

        assertEquals(PlaybookRunResult.Status.COMPLETED, r.status(), r.assistantText());
        assertEquals(0, filterCriteriaNames(cap.filtersJson).length());
    }

    @Test
    void multiOptionalBranchMerge_subsetPresent_orderMatchesMergeSources() throws Exception {
        RunCapture cap = new RunCapture();
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        assertTrue(PlaybookValidator.validateDocument(doc, playbookToolsForFixture(), extendedRegistry()).valid());

        PlaybookRunResult r = PlaybookRunner.run(doc, "multi_optional_branch_merge",
                new JSONObject()
                        .put("timeWindow", "")
                        .put("productFilter", "Widget")
                        .put("shiftFilter", "Day"),
                "show merged filters", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(), stubExecutor(cap),
                new StubLlm(), 0.0, 800);

        assertEquals(PlaybookRunResult.Status.COMPLETED, r.status(), r.assistantText());
        JSONArray criteria = filterCriteriaNames(cap.filtersJson);
        assertEquals(2, criteria.length());
        assertEquals("PRODUCT", criteria.getJSONObject(0).getString("FilterCriteria"));
        assertEquals("SHIFT", criteria.getJSONObject(1).getString("FilterCriteria"));
    }

    @Test
    void multiOptionalBranchMerge_allOptionalPresent_fullConcatenation() throws Exception {
        RunCapture cap = new RunCapture();
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        assertTrue(PlaybookValidator.validateDocument(doc, playbookToolsForFixture(), extendedRegistry()).valid());

        PlaybookRunResult r = PlaybookRunner.run(doc, "multi_optional_branch_merge",
                new JSONObject()
                        .put("timeWindow", "")
                        .put("productFilter", "Widget")
                        .put("jobFilter", "Run-A")
                        .put("shiftFilter", "Day"),
                "show merged filters", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(), stubExecutor(cap),
                new StubLlm(), 0.0, 800);

        assertEquals(PlaybookRunResult.Status.COMPLETED, r.status(), r.assistantText());
        JSONArray criteria = filterCriteriaNames(cap.filtersJson);
        assertEquals(3, criteria.length());
        assertEquals("PRODUCT", criteria.getJSONObject(0).getString("FilterCriteria"));
        assertEquals("JOB", criteria.getJSONObject(1).getString("FilterCriteria"));
        assertEquals("SHIFT", criteria.getJSONObject(2).getString("FilterCriteria"));
    }

    private static final class StubLlm implements LlmClient {
        @Override
        public LlmResponse chat(LlmChatRequest request) {
            return new LlmResponse(
                    "Merged filters summary.",
                    Collections.emptyList(),
                    LlmResponse.FinishReason.STOP,
                    1, 1, 1, 1, 0, 0, 0,
                    "req-multi-merge-1",
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
