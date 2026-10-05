package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

/** C17: {@code itemVar} names the wrapper key for primitive fan-out items. */
class PlaybookRunnerFanOutPrimitiveItemVarTest {

    @Test
    void fanOutPrimitiveItems_useItemVarAsWrapperKey() throws Exception {
        List<String> captured = new ArrayList<>();
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],"
                + "\"items\":{\"$input\":\"productNames\"},\"itemVar\":\"productName\","
                + "\"maxItems\":2,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"stub_tool\",\"dependsOn\":[],"
                + "\"args\":{\"name\":{\"$item\":\"productName\"}}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"fo\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunResult result = PlaybookRunner.run(doc, "t",
                new JSONObject().put("productNames", new JSONArray().put("P-1").put("P-2")),
                "", "conv-itemvar-1", Collections.emptyList(),
                (tool, args, infotables) -> {
                    captured.add(args.optString("name", ""));
                    return PlaybookToolExecutionResult.jsonOnly(
                            new JSONObject().put("status", "success").put("rows", new JSONArray()).toString());
                },
                stubLlm(), 0.0, 400);

        assertEquals(PlaybookRunResult.Status.COMPLETED, result.status());
        assertEquals(List.of("P-1", "P-2"), captured);
    }

    @Test
    void fanOutPrimitiveItems_defaultWrapperKeyIsValueWhenItemVarAbsent() throws Exception {
        List<String> captured = new ArrayList<>();
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],"
                + "\"items\":[\"alpha\"],\"maxItems\":1,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"stub_tool\",\"dependsOn\":[],"
                + "\"args\":{\"name\":{\"$item\":\"value\"}}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"fo\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunResult result = PlaybookRunner.run(doc, "t", new JSONObject(), "", "conv-itemvar-2",
                Collections.emptyList(),
                (tool, args, infotables) -> {
                    captured.add(args.optString("name", ""));
                    return PlaybookToolExecutionResult.jsonOnly(
                            new JSONObject().put("status", "success").toString());
                },
                stubLlm(), 0.0, 400);
        assertEquals(PlaybookRunResult.Status.COMPLETED, result.status());
        assertEquals(List.of("alpha"), captured);
    }

    @Test
    void fanOutPrimitiveItemKey_helperMatchesRuntime() {
        assertEquals("region",
                PlaybookRunner.fanOutPrimitiveItemKey(new JSONObject().put("itemVar", "region")));
        assertEquals("value", PlaybookRunner.fanOutPrimitiveItemKey(new JSONObject()));
    }

    @Test
    void fanOutInvalidItemVar_rejectedByValidator() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],\"items\":[],\"itemVar\":\"bad.key\","
                + "\"maxItems\":1,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"stub_tool\",\"dependsOn\":[],\"args\":{}}}"
                + "],\"finalNode\":\"fo\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookValidator.Result v = PlaybookValidator.validateDocument(doc, List.of());
        assertTrue(!v.valid());
        assertTrue(String.join("; ", v.errors()).contains("itemVar"));
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
}
