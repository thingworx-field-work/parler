package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.TaxonomyRow;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ToolRegistry;

/**
 * Fixture execution for the generic reference DAG: proves the playbook runs to
 * {@code llm_summary} with canned tool bodies (no {@code dev_data/}).
 */
class PlaybookReferenceGenericAssetPairHealthExecutionTest {

    @Test
    void crossAssetPairHealthGenericReference_runsToCompletionWithFixtureTools() throws Exception {
        Path root = repoRoot();
        String raw = Files.readString(
                root.resolve("docs/agent/playbook-engine-cross-asset-pair-health-generic.json"), StandardCharsets.UTF_8);
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        var tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        PlaybookValidator.Result vr = PlaybookValidator.validateDocument(doc, tools);
        assertTrue(vr.valid(), String.join("; ", vr.errors()));

        List<TaxonomyRow> taxonomy = List.of(
                new TaxonomyRow("LineX", "Things", "WidgetProd", List.of(), "Temperature,Pressure"));

        JSONObject params = new JSONObject()
                .put("assetType", "LineX")
                .put("assetIdentifierA", "ORD Robot Alpha Unique")
                .put("assetIdentifierB", "ORD Robot Beta Unique");

        CapturingLlmClient llm = new CapturingLlmClient();
        PlaybookToolExecutor exec = (tool, jsonArgs, infotableArgs) -> {
            if ("query_entities_by_taxonomy".equals(tool)) {
                return PlaybookToolExecutionResult.jsonOnly("{\"status\":\"success\",\"resultKind\":\"INFOTABLE\","
                        + "\"rootEntityList\":["
                        + "{\"name\":\"Plant.LineX-RobotA\",\"PTCDisplayName\":\"ORD Robot Alpha Unique\"},"
                        + "{\"name\":\"Plant.LineX-RobotB\",\"PTCDisplayName\":\"ORD Robot Beta Unique\"}"
                        + "]}");
            }
            if ("query_alert_history".equals(tool)) {
                String tn = jsonArgs.optString("thingName", "");
                JSONArray rows = new JSONArray();
                if (tn.contains("RobotA")) {
                    for (int i = 0; i < 5; i++) {
                        rows.put(new JSONObject()
                                .put("sourceProperty", "Temperature")
                                .put("timestamp", "2024-06-01T12:00:00Z")
                                .put("severity", "warning"));
                    }
                    rows.put(new JSONObject()
                            .put("sourceProperty", "Pressure")
                            .put("timestamp", "2024-06-02T08:00:00Z")
                            .put("severity", "info"));
                } else {
                    for (int i = 0; i < 3; i++) {
                        rows.put(new JSONObject()
                                .put("sourceProperty", "Temperature")
                                .put("timestamp", "2024-06-03T10:00:00Z")
                                .put("severity", "warning"));
                    }
                    for (int i = 0; i < 2; i++) {
                        rows.put(new JSONObject()
                                .put("sourceProperty", "Voltage")
                                .put("timestamp", "2024-06-04T11:00:00Z")
                                .put("severity", "info"));
                    }
                }
                JSONObject body = new JSONObject()
                        .put("status", "success")
                        .put("resultKind", "INFOTABLE")
                        .put("rowCount", rows.length())
                        .put("thingName", tn)
                        .put("appliedStartTime", "2024-05-31T00:00:00.000Z")
                        .put("appliedEndTime", "2024-06-07T00:00:00.000Z")
                        .put("columns", new JSONArray()
                                .put(new JSONObject().put("name", "sourceProperty").put("baseType", "STRING"))
                                .put(new JSONObject().put("name", "timestamp").put("baseType", "DATETIME"))
                                .put(new JSONObject().put("name", "severity").put("baseType", "STRING")))
                        .put("rows", rows);
                return PlaybookToolExecutionResult.jsonOnly(body.toString());
            }
            if ("query_property_history".equals(tool)) {
                JSONArray phRows = new JSONArray();
                phRows.put(new JSONObject().put("value", 10.0).put("timestamp", "2024-06-01T00:00:00Z"));
                phRows.put(new JSONObject().put("value", 20.0).put("timestamp", "2024-06-02T00:00:00Z"));
                JSONObject body = new JSONObject()
                        .put("status", "success")
                        .put("resultKind", "INFOTABLE")
                        .put("rowCount", phRows.length())
                        .put("thingName", jsonArgs.optString("thingName", ""))
                        .put("propertyName", jsonArgs.optString("propertyName", ""))
                        .put("columns", new JSONArray()
                                .put(new JSONObject().put("name", "value").put("baseType", "NUMBER"))
                                .put(new JSONObject().put("name", "timestamp").put("baseType", "STRING")))
                        .put("rows", phRows);
                return PlaybookToolExecutionResult.jsonOnly(body.toString());
            }
            throw new IllegalStateException("unexpected tool in fixture: " + tool);
        };

        String convKey = "junit-generic-asset-ref-exec-" + UUID.randomUUID();
        PlaybookRunResult result = PlaybookRunner.run(
                doc,
                PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID,
                params,
                "compare two assets",
                convKey,
                taxonomy,
                exec,
                llm,
                0.0,
                200);

        assertEquals(PlaybookRunResult.Status.COMPLETED, result.status(),
                result.failureCode() + " " + result.assistantText());
        String user = llm.lastUserContent;
        assertTrue(user.contains("Playbook evidence:"), user);
        assertTrue(user.contains("Temperature") || user.contains("sourceProperty"), user);
        assertTrue(user.contains("meanValue") || user.contains("aggregate") || user.contains("10"), user);
    }

    private static Path repoRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (cwd.getFileName().toString().equals("parler-agent")) {
            return cwd.getParent();
        }
        return cwd;
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
                    "req-generic-ref-1",
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
