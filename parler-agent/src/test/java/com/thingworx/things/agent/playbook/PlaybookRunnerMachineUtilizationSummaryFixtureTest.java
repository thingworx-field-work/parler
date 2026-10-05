package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.TaxonomyRow;
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
 * End-to-end {@link PlaybookRunner} for frozen {@code machine_utilization_summary}: resolver-first branch vs
 * {@code list_utilization_machines} + {@code match_identifier_in_rows} fallback, followed by
 * {@code get_utilization_state_summary}.
 */
class PlaybookRunnerMachineUtilizationSummaryFixtureTest {

    private static String readFixture() throws Exception {
        String resource = "/bug004-scpa-utilization-fixture/machine_utilization_summary/playbook.json";
        try (InputStream in = PlaybookRunnerMachineUtilizationSummaryFixtureTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JSONObject listingRowsTwoMachines() {
        JSONObject result = new JSONObject()
                .put("status", "success")
                .put("rows", new org.json.JSONArray()
                        .put(new JSONObject()
                                .put("machineName", "SE.CellFab.Model.Workunit.M-ONE")
                                .put("displayName", "ORD Jet Dryer")
                                .put("description", "ORD Jet Dryer Display"))
                        .put(new JSONObject()
                                .put("machineName", "SE.CellFab.Model.Workunit.M-TWO")
                                .put("displayName", "Other Cell")
                                .put("description", "Other Cell Line")));
        return jsonResultEnvelope(result);
    }

    private static JSONObject listingRowsAmbiguous() {
        JSONObject result = new JSONObject()
                .put("status", "success")
                .put("rows", new org.json.JSONArray()
                        .put(new JSONObject()
                                .put("machineName", "SE.CellFab.Model.Workunit.M-A")
                                .put("displayName", "Machine A")
                                .put("description", "Shared Display Label"))
                        .put(new JSONObject()
                                .put("machineName", "SE.CellFab.Model.Workunit.M-B")
                                .put("displayName", "Machine B")
                                .put("description", "Shared Display Label")));
        return jsonResultEnvelope(result);
    }

    private static JSONObject jsonResultEnvelope(JSONObject result) {
        return new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", result.toString());
    }

    private static List<ToolDefinition> playbookToolsForFixture() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> tools = new ArrayList<>(
                PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing()));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        for (String n : new String[] {
                "list_utilization_machines",
                "get_utilization_state_summary"
        }) {
            tools.add(new ToolDefinition(n, "fixture", schema, true));
        }
        return tools;
    }

    private static PlaybookToolExecutor stubExecutor(List<String> machineArgs, Scenario mode) {
        return (tool, jsonArgs, infotableArgs) -> {
            switch (tool) {
                case "resolve_thing":
                    if (mode == Scenario.RESOLVER_INLINE) {
                        return PlaybookToolExecutionResult.jsonOnly(new JSONObject()
                                .put("status", "success")
                                .put("resultKind", "THING_RESOLVED_INLINE")
                                .put("matches", new org.json.JSONArray().put(new JSONObject()
                                        .put("name", "SE.CellFab.Model.Workunit.CANON-INLINE")
                                        .put("displayName", "inline")))
                                .toString());
                    }
                    return PlaybookToolExecutionResult.jsonOnly(new JSONObject()
                            .put("status", "error")
                            .put("code", "IDENTITY_NOT_FOUND")
                            .put("message", "no resolver match")
                            .toString());
                case "list_utilization_machines":
                    return PlaybookToolExecutionResult.jsonOnly(
                            (mode == Scenario.LISTING_AMBIGUOUS ? listingRowsAmbiguous() : listingRowsTwoMachines())
                                    .toString());
                case "get_utilization_state_summary":
                    String machine = jsonArgs.optString("Machine", "");
                    machineArgs.add(machine);
                    return PlaybookToolExecutionResult.jsonOnly(jsonResultEnvelope(new JSONObject()
                            .put("status", "success")
                            .put("machine", machine)
                            .put("stats", new JSONObject()
                                    .put("utilizationPercent", 1)
                                    .put("eventCount", 1))
                            .put("rows", new org.json.JSONArray())).toString());
                default:
                    throw new AssertionError("unexpected tool: " + tool);
            }
        };
    }

    private enum Scenario {
        RESOLVER_INLINE,
        LISTING_MATCH,
        LISTING_AMBIGUOUS
    }

    @Test
    void machineUtilization_inlineResolve_passesCanonicalMachineToStateSummary() throws Exception {
        List<String> machines = new ArrayList<>();
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        PlaybookValidator.Result v = PlaybookValidator.validateDocument(doc, playbookToolsForFixture());
        assertTrue(v.valid(), String.join("; ", v.errors()));

        PlaybookRunResult r = PlaybookRunner.run(doc, "machine_utilization_summary",
                new JSONObject().put("startDate", "2025-01-01T00:00:00Z").put("endDate", "2025-01-02T00:00:00Z")
                        .put("machine", "anything"),
                "goal", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(), stubExecutor(machines, Scenario.RESOLVER_INLINE),
                new StubLlm(), 0.0, 800);
        assertEquals(PlaybookRunResult.Status.COMPLETED, r.status(), r.assistantText());
        assertEquals(1, machines.size());
        assertEquals("SE.CellFab.Model.Workunit.CANON-INLINE", machines.get(0));
    }

    @Test
    void machineUtilization_listingFallback_matchesDisplayLabel() throws Exception {
        List<String> machines = new ArrayList<>();
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        assertTrue(PlaybookValidator.validateDocument(doc, playbookToolsForFixture()).valid());

        PlaybookRunResult r = PlaybookRunner.run(doc, "machine_utilization_summary",
                new JSONObject().put("startDate", "2025-01-01T00:00:00Z").put("endDate", "2025-01-02T00:00:00Z")
                        .put("machine", "ORD Jet Dryer"),
                "goal", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(), stubExecutor(machines, Scenario.LISTING_MATCH),
                new StubLlm(), 0.0, 800);
        assertEquals(PlaybookRunResult.Status.COMPLETED, r.status(), r.assistantText());
        assertEquals(1, machines.size());
        assertEquals("SE.CellFab.Model.Workunit.M-ONE", machines.get(0));
    }

    @Test
    void machineUtilization_listingAmbiguous_needsClarification() throws Exception {
        List<String> machines = new ArrayList<>();
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        assertTrue(PlaybookValidator.validateDocument(doc, playbookToolsForFixture()).valid());

        PlaybookRunResult r = PlaybookRunner.run(doc, "machine_utilization_summary",
                new JSONObject().put("startDate", "2025-01-01T00:00:00Z").put("endDate", "2025-01-02T00:00:00Z")
                        .put("machine", "Shared Display Label"),
                "goal", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(),
                stubExecutor(machines, Scenario.LISTING_AMBIGUOUS),
                new StubLlm(), 0.0, 800);
        assertEquals(PlaybookRunResult.Status.NEEDS_CLARIFICATION, r.status(), r.assistantText());
        assertTrue(machines.isEmpty());
    }

    private static final class StubLlm implements LlmClient {
        @Override
        public LlmResponse chat(LlmChatRequest request) {
            for (ChatMessage m : request.getMessages()) {
                if (m.getRole() == ChatMessage.Role.USER && m.getContent() != null) {
                    // touch evidence path
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
                    "req-machine-util-1",
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
