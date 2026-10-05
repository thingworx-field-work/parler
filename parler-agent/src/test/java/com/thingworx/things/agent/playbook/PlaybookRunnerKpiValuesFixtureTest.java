package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * End-to-end {@link PlaybookRunner} for workshop #35 KPI values:
 * time window → {@code build_nested_object} → {@code json_stringify} → {@code CallGetKPIs}.
 */
class PlaybookRunnerKpiValuesFixtureTest {

    private static final String EXT_TOOL = "CallGetKPIs";

    private static String readFixture() throws Exception {
        String resource = "/playbook-34-35-fixture/kpi_values/playbook.json";
        try (InputStream in = PlaybookRunnerKpiValuesFixtureTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing " + resource);
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

    @Test
    void kpiValuesFixture_defaultTodayQuickInterval_buildsFiltersString() throws Exception {
        RunCapture cap = new RunCapture();
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        PlaybookValidator.Result v = PlaybookValidator.validateDocument(doc, playbookToolsForFixture(),
                extendedRegistry());
        assertTrue(v.valid(), String.join("; ", v.errors()));

        PlaybookRunResult r = PlaybookRunner.run(doc, "kpi_values",
                new JSONObject().put("timeWindow", "").put("productFilter", "Widget"),
                "show kpis", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(), stubExecutor(cap),
                new StubLlm(), 0.0, 800);

        assertEquals(PlaybookRunResult.Status.COMPLETED, r.status(), r.assistantText());
        assertFalse(cap.filtersJson.isBlank(), "Filters parameter missing");

        JSONObject payload = new JSONObject(cap.filtersJson);
        assertEquals(3, payload.getInt("QuickTimeIntervalUID"));
        assertFalse(payload.has("StartTime"));
        assertFalse(payload.has("EndTime"));
        JSONArray filters = payload.getJSONArray("Filters");
        assertEquals(1, filters.length());
        assertEquals(4, filters.getJSONObject(0).getInt("EquipmentUID"));
        assertEquals(1, filters.getJSONObject(0).getJSONArray("FilterCriterias").length());
        assertEquals("PRODUCT",
                filters.getJSONObject(0).getJSONArray("FilterCriterias").getJSONObject(0).getString("FilterCriteria"));
    }

    @Test
    void kpiValuesFixture_defaultToday_nonMatchingQuickRows_explicitRangeInPayload() throws Exception {
        String raw = readFixture().replace(
                "\"name\": \"Today\"",
                "\"name\": \"Yesterday\"");
        RunCapture cap = new RunCapture();
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        assertTrue(PlaybookValidator.validateDocument(doc, playbookToolsForFixture(), extendedRegistry()).valid());

        PlaybookRunResult r = PlaybookRunner.run(doc, "kpi_values",
                new JSONObject().put("timeWindow", "").put("productFilter", "Widget"),
                "show kpis", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(), stubExecutor(cap),
                new StubLlm(), 0.0, 800);

        assertEquals(PlaybookRunResult.Status.COMPLETED, r.status(), r.assistantText());
        JSONObject payload = new JSONObject(cap.filtersJson);
        assertFalse(payload.has("QuickTimeIntervalUID"));
        assertTrue(payload.has("StartTime"));
        assertTrue(payload.has("EndTime"));
    }

    @Test
    void kpiValuesFixture_skippedOptionalProductFilter_omitsFilterCriterias() throws Exception {
        RunCapture cap = new RunCapture();
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        assertTrue(PlaybookValidator.validateDocument(doc, playbookToolsForFixture(), extendedRegistry()).valid());

        PlaybookRunResult r = PlaybookRunner.run(doc, "kpi_values",
                new JSONObject().put("timeWindow", ""),
                "show kpis", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(), stubExecutor(cap),
                new StubLlm(), 0.0, 800);

        assertEquals(PlaybookRunResult.Status.COMPLETED, r.status(), r.assistantText());
        JSONObject payload = new JSONObject(cap.filtersJson);
        assertEquals(3, payload.getInt("QuickTimeIntervalUID"));
        JSONArray filters = payload.getJSONArray("Filters");
        assertEquals(1, filters.length());
        assertEquals(0, filters.getJSONObject(0).getJSONArray("FilterCriterias").length());
    }

    private static final class StubLlm implements LlmClient {
        @Override
        public LlmResponse chat(LlmChatRequest request) {
            return new LlmResponse(
                    "KPI summary.",
                    Collections.emptyList(),
                    LlmResponse.FinishReason.STOP,
                    1,
                    1,
                    1,
                    1,
                    0,
                    0,
                    0,
                    "req-kpi-values-1",
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
