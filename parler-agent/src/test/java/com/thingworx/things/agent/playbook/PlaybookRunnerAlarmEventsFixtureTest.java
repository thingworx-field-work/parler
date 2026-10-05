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

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.common.interfaces.IDataShapeDefinitionProvider;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
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
import com.thingworx.things.agent.tools.InfotableJsonCodec;
import com.thingworx.things.agent.tools.ToolRegistry;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;

/**
 * End-to-end {@link PlaybookRunner} for workshop #34 alarm-events:
 * resolve → normalize → UID extract → {@code $infotable} → {@code GetAlarmEvents_AI}.
 */
class PlaybookRunnerAlarmEventsFixtureTest {

    private static final String EXT_TOOL = "GetAlarmEvents_AI";
    private static final String UID_SHAPE = "PTC.SCA.SCO.Utilities.UID";

    @AfterEach
    void tearDown() {
        InfotableJsonCodec.clearTestDataShapeProviderOverride();
    }

    private static String readFixture() throws Exception {
        String resource = "/playbook-34-35-fixture/alarm_events/playbook.json";
        try (InputStream in = PlaybookRunnerAlarmEventsFixtureTest.class.getResourceAsStream(resource)) {
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

    private static JSONObject uidProperties(double uid) {
        return new JSONObject()
                .put("status", "success")
                .put("properties", new JSONArray()
                        .put(new JSONObject().put("name", "UID").put("value", uid)));
    }

    private static final class RunCapture {
        final List<String> resolveTexts = new ArrayList<>();
        final List<String> propertyThingNames = new ArrayList<>();
        final List<Double> alarmUids = new ArrayList<>();
    }

    private static PlaybookToolExecutor stubExecutor(RunCapture cap) {
        return (tool, jsonArgs, infotableArgs) -> {
            switch (tool) {
                case "resolve_thing":
                    cap.resolveTexts.add(jsonArgs.optString("text", ""));
                    if ("AMU CNC Mill".equals(jsonArgs.optString("text", ""))) {
                        return PlaybookToolExecutionResult.jsonOnly(
                                resolveInline("TDD.FSU.CNCMill", "AMU CNC Mill").toString());
                    }
                    if ("Pack Line 2".equals(jsonArgs.optString("text", ""))) {
                        return PlaybookToolExecutionResult.jsonOnly(
                                resolveInline("TDD.FSU.PackLine2", "Pack Line 2").toString());
                    }
                    return PlaybookToolExecutionResult.jsonOnly(new JSONObject()
                            .put("status", "error")
                            .put("code", "IDENTITY_NOT_FOUND")
                            .put("message", "no match")
                            .toString());
                case "get_property_values":
                    cap.propertyThingNames.add(jsonArgs.optString("thingName", ""));
                    if ("TDD.FSU.CNCMill".equals(jsonArgs.optString("thingName", ""))) {
                        return PlaybookToolExecutionResult.jsonOnly(uidProperties(42).toString());
                    }
                    if ("TDD.FSU.PackLine2".equals(jsonArgs.optString("thingName", ""))) {
                        return PlaybookToolExecutionResult.jsonOnly(uidProperties(77).toString());
                    }
                    return PlaybookToolExecutionResult.jsonOnly(uidProperties(-1).toString());
                case EXT_TOOL:
                    InfoTable table = infotableArgs.get("EquipmentUIDs");
                    assertTrue(table != null, "EquipmentUIDs infotable missing");
                    assertTrue(table.getRowCount() > 0 && table.getRow(0).has("UID"),
                            "UID column missing from bound infotable");
                    for (int i = 0; i < table.getRowCount(); i++) {
                        ValueCollection row = table.getRow(i);
                        cap.alarmUids.add(((Number) row.getPrimitive("UID").getValue()).doubleValue());
                    }
                    return PlaybookToolExecutionResult.jsonOnly(new JSONObject()
                            .put("status", "success")
                            .put("resultKind", "INFOTABLE")
                            .put("rows", new JSONArray())
                            .toString());
                default:
                    return PlaybookToolExecutionResult.jsonOnly("{\"status\":\"success\"}");
            }
        };
    }

    private static List<ToolDefinition> playbookToolsForFixture() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        return PlaybookToolDefinitionsMerge.merge(reg, extendedRegistry());
    }

    private static ExtendedToolRegistrySnapshot extendedRegistry() {
        ToolDefinition td = new ToolDefinition(EXT_TOOL, "alarm events", Map.of("type", "object"), true);
        ExtendedToolDefinition ext = new ExtendedToolDefinition(EXT_TOOL, "t", "w", "AlarmThing", EXT_TOOL, false,
                false, td);
        return ExtendedToolRegistrySnapshot.ok(List.of(ext));
    }

    private static void stubUidShape() {
        IDataShapeDefinitionProvider provider = name -> {
            if (UID_SHAPE.equals(name)) {
                DataShapeDefinition dsd = new DataShapeDefinition();
                dsd.addFieldDefinition(new FieldDefinition("UID", "", BaseTypes.NUMBER));
                return dsd;
            }
            return null;
        };
        InfotableJsonCodec.setTestDataShapeProviderOverride(provider);
    }

    @Test
    void alarmEventsFixture_resolveExtractInfotable_bindsUidRows() throws Exception {
        stubUidShape();
        RunCapture cap = new RunCapture();
        PlaybookDocument doc = PlaybookDocument.parse(readFixture());
        PlaybookValidator.Result v = PlaybookValidator.validateDocument(doc, playbookToolsForFixture(),
                extendedRegistry());
        assertTrue(v.valid(), String.join("; ", v.errors()));

        JSONArray equipment = new JSONArray()
                .put(new JSONObject().put("input", "AMU CNC Mill"))
                .put(new JSONObject().put("input", "Pack Line 2"));

        PlaybookRunResult r = PlaybookRunner.run(doc, "alarm_events",
                new JSONObject().put("equipmentIdentifiers", equipment),
                "show alarm events", "conv-" + UUID.randomUUID(), List.<TaxonomyRow>of(), stubExecutor(cap),
                new StubLlm(), 0.0, 800);

        assertEquals(PlaybookRunResult.Status.COMPLETED, r.status(), r.assistantText());
        assertEquals(List.of("AMU CNC Mill", "Pack Line 2"), cap.resolveTexts);
        assertEquals(List.of("TDD.FSU.CNCMill", "TDD.FSU.PackLine2"), cap.propertyThingNames);
        assertEquals(List.of(42.0, 77.0), cap.alarmUids);
    }

    private static final class StubLlm implements LlmClient {
        @Override
        public LlmResponse chat(LlmChatRequest request) {
            return new LlmResponse(
                    "Alarm events summary.",
                    Collections.emptyList(),
                    LlmResponse.FinishReason.STOP,
                    1,
                    1,
                    1,
                    1,
                    0,
                    0,
                    0,
                    "req-alarm-events-1",
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
