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
 * C8 proof: {@code extract_from_tool_output} with {@code arrayPath} {@code result.Table0} on nested lookup envelopes.
 */
class PlaybookRunnerNestedTable0ExtractFixtureTest {

    private static final String EXT_TOOL = "StubLookup";
    private static final String FIXTURE = "/playbook-34-35-fixture/nested_table0_extract/playbook.json";

    private static String readFixture() throws Exception {
        try (InputStream in = PlaybookRunnerNestedTable0ExtractFixtureTest.class.getResourceAsStream(FIXTURE)) {
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
        ToolDefinition td = new ToolDefinition(EXT_TOOL, "lookup stub", Map.of("type", "object"), true);
        ExtendedToolDefinition ext = new ExtendedToolDefinition(EXT_TOOL, "t", "w", "LookupThing", EXT_TOOL, false,
                false, td);
        return ExtendedToolRegistrySnapshot.ok(List.of(ext));
    }

    @Test
    void nestedTable0Extract_extractsCriteriaRowsFromResultTable0() throws Exception {
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        assertTrue(PlaybookValidator.validateDocument(doc, playbookToolsForFixture(), extendedRegistry()).valid());

        PlaybookRunContext ctx = new PlaybookRunContext("nt0", "nested_table0_extract", new JSONObject());
        JSONObject toolOut = new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", new JSONObject().put("Table0", new JSONArray()
                        .put(new JSONObject().put("FilterCriteria", "PRODUCT").put("UIDValue", 8))
                        .put(new JSONObject().put("FilterCriteria", "JOB").put("UIDValue", 9))));
        ctx.putNodeOutput("lookup", new JSONObject().put("status", "ok").put("toolOutput", toolOut));

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("extract_from_tool_output",
                doc.nodesById().get("criteria_rows").getJSONObject("args"), ctx);

        assertEquals("ok", out.getString("status"));
        JSONArray rows = out.getJSONObject("output").getJSONArray("rows");
        assertEquals(2, rows.length());
        assertEquals("PRODUCT", rows.getJSONObject(0).getString("FilterCriteria"));
        assertEquals(9, rows.getJSONObject(1).getInt("UIDValue"));
    }

    @Test
    void nestedTable0Extract_runnerCompletesWithExtractedRows() throws Exception {
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        assertTrue(PlaybookValidator.validateDocument(doc, playbookToolsForFixture(), extendedRegistry()).valid());

        PlaybookToolExecutor exec = (tool, jsonArgs, infotableArgs) -> {
            if (EXT_TOOL.equals(tool)) {
                return PlaybookToolExecutionResult.jsonOnly(new JSONObject()
                        .put("status", "success")
                        .put("resultKind", "JSON")
                        .put("result", new JSONObject().put("Table0", new JSONArray()
                                .put(new JSONObject().put("FilterCriteria", "SHIFT").put("UIDValue", 10))))
                        .toString());
            }
            return PlaybookToolExecutionResult.jsonOnly("{\"status\":\"success\"}");
        };

        PlaybookRunResult r = PlaybookRunner.run(doc, "nested_table0_extract",
                new JSONObject().put("lookupName", "Day"),
                "extract rows", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(), exec,
                new StubLlm(), 0.0, 800);

        assertEquals(PlaybookRunResult.Status.COMPLETED, r.status(), r.assistantText());
    }

    private static final class StubLlm implements LlmClient {
        @Override
        public LlmResponse chat(LlmChatRequest request) {
            return new LlmResponse(
                    "ok", Collections.emptyList(), LlmResponse.FinishReason.STOP,
                    1, 1, 1, 1, 0, 0, 0, "req-nt0-1", 0L);
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
