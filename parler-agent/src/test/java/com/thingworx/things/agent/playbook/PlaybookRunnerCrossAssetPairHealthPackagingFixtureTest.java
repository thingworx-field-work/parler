package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * End-to-end {@link PlaybookRunner} coverage for the packaged {@code cross_asset_pair_health}
 * resolver-first DAG (no {@code dev_data/}). Captures {@code resolve_thing} args and downstream
 * {@code thingName} wiring per design-review feedback.
 */
class PlaybookRunnerCrossAssetPairHealthPackagingFixtureTest {

    private static String readPackagingFixture() throws Exception {
        String resource = "/playbook-packaging-fixture/cross_asset_pair_health/playbook.json";
        try (InputStream in = PlaybookRunnerCrossAssetPairHealthPackagingFixtureTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JSONObject resolveInline(String thingName, String display) {
        return new JSONObject()
                .put("status", "success")
                .put("resultKind", "THING_RESOLVED_INLINE")
                .put("matches", new JSONArray().put(new JSONObject()
                        .put("name", thingName)
                        .put("displayName", display)));
    }

    /** Per-run capture of tool args. */
    private static final class RunCapture {
        final List<JSONObject> resolveThingCalls = new ArrayList<>();
        final List<String> alertSummaryThingNames = new ArrayList<>();
        final List<String> propertyValuesThingNames = new ArrayList<>();
        final List<String> propertyHistoryThingNames = new ArrayList<>();
        final CapturingLlmClient llm = new CapturingLlmClient();
    }

    private static PlaybookToolExecutor stubExecutor(RunCapture cap) {
        return (tool, jsonArgs, infotableArgs) -> {
            switch (tool) {
                case "resolve_thing": {
                    JSONObject meta = new JSONObject();
                    if (jsonArgs.has("assetTypeKey")) {
                        meta.put("hasKey", true);
                        meta.put("value", jsonArgs.optString("assetTypeKey", ""));
                    } else {
                        meta.put("hasKey", false);
                        meta.put("value", "");
                    }
                    cap.resolveThingCalls.add(meta);
                    String text = jsonArgs.optString("text", "");
                    if (text.contains("Alpha")) {
                        return PlaybookToolExecutionResult.jsonOnly(
                                resolveInline("Plant.LineX-RobotA", "ORD Robot Alpha Unique").toString());
                    }
                    if (text.contains("Beta")) {
                        return PlaybookToolExecutionResult.jsonOnly(
                                resolveInline("Plant.LineX-RobotB", "ORD Robot Beta Unique").toString());
                    }
                    return PlaybookToolExecutionResult.jsonOnly(new JSONObject()
                            .put("status", "error")
                            .put("code", "IDENTITY_NOT_FOUND")
                            .put("message", "no match")
                            .toString());
                }
                case "query_alert_summary": {
                    List<String> thisCall = new ArrayList<>();
                    if (jsonArgs.has("thingNames")) {
                        JSONArray names = jsonArgs.optJSONArray("thingNames");
                        if (names != null) {
                            for (int i = 0; i < names.length(); i++) {
                                String n = names.optString(i, "");
                                thisCall.add(n);
                                cap.alertSummaryThingNames.add(n);
                            }
                        }
                    } else {
                        String n = jsonArgs.optString("thingName", "");
                        thisCall.add(n);
                        cap.alertSummaryThingNames.add(n);
                    }
                    JSONArray byThing = new JSONArray();
                    for (String n : thisCall) {
                        if (n == null || n.isEmpty()) {
                            continue;
                        }
                        byThing.put(new JSONObject()
                                .put("thingName", n)
                                .put("status", "success")
                                .put("totalAlerts", 1)
                                .put("topAlerts", new JSONArray().put(new JSONObject()
                                        .put("sourceProperty", "Temperature"))));
                    }
                    if (byThing.length() >= 2) {
                        return PlaybookToolExecutionResult.jsonOnly(new JSONObject()
                                .put("status", "success")
                                .put("resultKind", "ALERT_SUMMARY_MULTI")
                                .put("completeness", "complete")
                                .put("byThing", byThing)
                                .toString());
                    }
                    return PlaybookToolExecutionResult.jsonOnly("{\"status\":\"success\",\"resultKind\":\"INFOTABLE\","
                            + "\"rows\":[{\"sourceProperty\":\"Temperature\"}]}");
                }
                case "get_property_values":
                    cap.propertyValuesThingNames.add(jsonArgs.optString("thingName", ""));
                    return PlaybookToolExecutionResult.jsonOnly(
                            "{\"status\":\"success\",\"properties\":[{\"name\":\"Temperature\",\"value\":1}]}");
                case "query_property_history":
                    cap.propertyHistoryThingNames.add(jsonArgs.optString("thingName", ""));
                    return PlaybookToolExecutionResult.jsonOnly("{\"status\":\"success\",\"pointsReturned\":3,"
                            + "\"totalRows\":3,\"aggregates\":{\"FIRST\":1,\"LAST\":2,\"MIN\":0,\"MAX\":3,\"MEAN\":1.5}}");
                default:
                    return PlaybookToolExecutionResult.jsonOnly("{\"status\":\"success\"}");
            }
        };
    }

    @Test
    void crossAssetPair_healthPackagingFixture_runsWithoutAssetType() throws Exception {
        RunCapture cap = runOnce(new JSONObject()
                .put("assetIdentifierA", "ORD Robot Alpha Unique")
                .put("assetIdentifierB", "ORD Robot Beta Unique")
                .put("timeWindow", "24h"),
                List.of());
        assertTrue(cap.llm.lastUserContent.contains("Plant.LineX-RobotA"), cap.llm.lastUserContent);
        assertTrue(cap.llm.lastUserContent.contains("Plant.LineX-RobotB"), cap.llm.lastUserContent);

        assertEquals(2, cap.resolveThingCalls.size(), cap.resolveThingCalls.toString());
        for (JSONObject meta : cap.resolveThingCalls) {
            boolean absentOrBlank = !meta.optBoolean("hasKey") || meta.optString("value", "").isBlank();
            assertTrue(absentOrBlank, "assetTypeKey must be absent or blank for global resolution: " + meta);
        }

        assertCanonicalNamesReachDownstreamTools(cap);
    }

    @Test
    void crossAssetPair_healthPackagingFixture_runsWithOptionalAssetType() throws Exception {
        RunCapture cap = runOnce(
                new JSONObject()
                        .put("assetType", "LineX")
                        .put("assetIdentifierA", "ORD Robot Alpha Unique")
                        .put("assetIdentifierB", "ORD Robot Beta Unique")
                        .put("timeWindow", "24h"),
                List.of(new TaxonomyRow("LineX", "Things", "LineXT", List.of(), "Temperature")));
        assertTrue(cap.llm.lastUserContent.contains("Plant.LineX-RobotA"), cap.llm.lastUserContent);
        assertTrue(cap.llm.lastUserContent.contains("Plant.LineX-RobotB"), cap.llm.lastUserContent);

        assertEquals(2, cap.resolveThingCalls.size(), cap.resolveThingCalls.toString());
        for (JSONObject meta : cap.resolveThingCalls) {
            assertTrue(meta.optBoolean("hasKey"), "assetTypeKey must be forwarded when hint supplied: " + meta);
            assertEquals("LineX", meta.optString("value"), meta.toString());
        }

        assertCanonicalNamesReachDownstreamTools(cap);
    }

    private static void assertCanonicalNamesReachDownstreamTools(RunCapture cap) {
        Set<String> expected = Set.of("Plant.LineX-RobotA", "Plant.LineX-RobotB");
        assertEquals(expected, new HashSet<>(cap.alertSummaryThingNames), cap.alertSummaryThingNames.toString());
        assertEquals(expected, new HashSet<>(cap.propertyValuesThingNames), cap.propertyValuesThingNames.toString());
        assertEquals(expected, new HashSet<>(cap.propertyHistoryThingNames), cap.propertyHistoryThingNames.toString());
    }

    private static RunCapture runOnce(JSONObject params, List<TaxonomyRow> taxonomy) throws Exception {
        PlaybookDocument doc = PlaybookDocument.parse(readPackagingFixture());
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        var tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        PlaybookValidator.Result vr = PlaybookValidator.validateDocument(doc, tools);
        assertTrue(vr.valid(), String.join("; ", vr.errors()));

        RunCapture cap = new RunCapture();
        String convKey = "junit-cross-asset-pack-" + UUID.randomUUID();
        PlaybookRunResult result = PlaybookRunner.run(
                doc,
                "cross_asset_pair_health",
                params,
                "",
                convKey,
                taxonomy,
                stubExecutor(cap),
                cap.llm,
                0.0,
                400);
        assertEquals(PlaybookRunResult.Status.COMPLETED, result.status());
        return cap;
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
                    "req-cross-asset-pack-1",
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
