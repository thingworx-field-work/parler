package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

/** Slice D: fan_out {@code output.childResults[]} target envelope (§8.2). */
class PlaybookRunnerFanOutChildResultsTest {

    @Test
    void fanOut_continueOnChildGap_recordsChildResultsAndCompletes() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],\"continueOnChildGap\":true,"
                + "\"items\":[{\"name\":\"A\"},{\"name\":\"B\"}],\"maxItems\":2,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"stub_tool\",\"dependsOn\":[],\"args\":{}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"fo\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        AtomicInteger calls = new AtomicInteger();
        PlaybookRunResult result = PlaybookRunner.run(doc, "t", new JSONObject(), "", "conv-fo-1",
                Collections.emptyList(),
                (tool, args, infotables) -> {
                    if (calls.incrementAndGet() == 2) {
                        throw new PlaybookRunException("Asset B not found.", "RESOLVE_FAILED");
                    }
                    return PlaybookToolExecutionResult.jsonOnly(
                            new JSONObject().put("status", "success").put("rowCount", 20).toString());
                },
                stubLlm(),
                0.0, 400);
        assertEquals(PlaybookRunResult.Status.COMPLETED, result.status());
        assertTrue(result.runOutcome() != null);
        JSONObject foNode = null;
        JSONArray nodes = result.runOutcome().getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject row = nodes.getJSONObject(i);
            if ("fo".equals(row.optString("nodeId"))) {
                foNode = row;
                break;
            }
        }
        assertTrue(foNode != null);
        JSONArray childResults = foNode.getJSONArray("childResults");
        assertEquals(2, childResults.length());
        assertEquals("ok", childResults.getJSONObject(0).getString("status"));
        assertEquals("gap", childResults.getJSONObject(1).getString("status"));
        assertEquals("RESOLVE_FAILED", childResults.getJSONObject(1).getJSONObject("gap").getString("code"));
        assertTrue(foNode.getJSONArray("evidenceLines").getString(0).contains("1 of 2"));
    }

    @Test
    void fanOut_defaultFailsParentOnChildFailure() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],"
                + "\"items\":[{\"name\":\"A\"}],\"maxItems\":1,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"stub_tool\",\"dependsOn\":[],\"args\":{}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"fo\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunResult result = PlaybookRunner.run(doc, "t", new JSONObject(), "", "conv-fo-2",
                Collections.emptyList(),
                (tool, args, infotables) -> {
                    throw new PlaybookRunException("child failed", "CHILD_FAILED");
                },
                stubLlm(),
                0.0, 400);
        assertEquals(PlaybookRunResult.Status.FAILED, result.status());
        JSONObject foNode = findNode(result.runOutcome(), "fo");
        assertTrue(foNode != null);
        assertEquals("gap", foNode.getJSONArray("childResults").getJSONObject(0).getString("status"));
    }

    private static LlmClient stubLlm() {
        return new LlmClient() {
            @Override
            public LlmResponse chat(LlmChatRequest request) {
                return new LlmResponse("ok", Collections.emptyList(), LlmResponse.FinishReason.STOP,
                        1, 1, 1, 1, 0, 0, 0, "r1", 0L);
            }

            @Override
            public LlmUsageWireIds usageWireIds() {
                return LlmUsageWireIds.forProviderThing("t", "p", "m", "gpt");
            }

            @Override
            public boolean healthCheck() {
                return true;
            }
        };
    }

    private static JSONObject findNode(JSONObject outcome, String nodeId) {
        if (outcome == null) {
            return null;
        }
        JSONArray nodes = outcome.optJSONArray("nodes");
        if (nodes == null) {
            return null;
        }
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject row = nodes.getJSONObject(i);
            if (nodeId.equals(row.optString("nodeId"))) {
                return row;
            }
        }
        return null;
    }
}
