package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.common.interfaces.IDataShapeDefinitionProvider;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.things.agent.configrepo.ExtendedToolDefinition;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.InfotableJsonCodec;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

class PlaybookInfotableBindingExecutorTest {

    private static final String EXT_TOOL = "GetAlarmEvents_AI";
    private static final String UID_SHAPE = "PTC.SCA.SCO.Utilities.UID";

    @AfterEach
    void tearDown() {
        InfotableJsonCodec.clearTestDataShapeProviderOverride();
    }

    @Test
    void policy_rejectsBuiltInWithInfotableArgs() {
        Map<String, InfoTable> tables = Map.of("EquipmentUIDs", new InfoTable());
        PlaybookRunException ex = assertThrows(PlaybookRunException.class,
                () -> PlaybookInfotableBindingPolicy.rejectInfotableArgsUnlessExtendedTool(
                        "query_entities_by_taxonomy", ExtendedToolRegistrySnapshot.missing(), tables));
        assertEquals("PLAYBOOK_TOOL_NOT_EXTENDED", ex.failureCode());
    }

    @Test
    void policy_allowsRepositoryExtendedTool() throws Exception {
        ExtendedToolRegistrySnapshot ext = extendedRegistry();
        PlaybookRunContext ctx = contextWithUidRows();
        PlaybookResolvedToolArgs resolved = resolveExtendedToolBinding(ctx);
        assertEquals(1, resolved.infotableArgs().get("EquipmentUIDs").getRowCount());
        PlaybookInfotableBindingPolicy.rejectInfotableArgsUnlessExtendedTool(EXT_TOOL, ext, resolved.infotableArgs());
    }

    @Test
    void validateInfotableParameterShapes_rejectsNonInfotableParam() throws Exception {
        stubUidShape();
        ServiceDefinition sd = new ServiceDefinition("GetAlarmEvents_AI", "fixture");
        sd.getParameters().addFieldDefinition(new FieldDefinition("StartDate", "", BaseTypes.DATETIME));
        Map<String, InfoTable> tables = resolveExtendedToolBinding(contextWithUidRows()).infotableArgs();
        PlaybookRunException ex = assertThrows(PlaybookRunException.class,
                () -> PlaybookInfotableBindingPolicy.validateInfotableParameterShapes(sd, tables));
        assertEquals("TABLE_REF_NOT_INFOTABLE_PARAM", ex.failureCode());
    }

    @Test
    void validateInfotableParameterShapes_acceptsInfotableParam() throws Exception {
        stubUidShape();
        ServiceDefinition sd = new ServiceDefinition("GetAlarmEvents_AI", "fixture");
        sd.getParameters().addFieldDefinition(
                FieldDefinition.createInfoTableFieldDefinition("EquipmentUIDs", "", UID_SHAPE));
        Map<String, InfoTable> tables = resolveExtendedToolBinding(contextWithUidRows()).infotableArgs();
        PlaybookInfotableBindingPolicy.validateInfotableParameterShapes(sd, tables);
    }

    private static PlaybookRunContext contextWithUidRows() {
        PlaybookRunContext ctx = new PlaybookRunContext("exec1", "p", new JSONObject());
        JSONArray rows = new JSONArray().put(new JSONObject().put("UID", 42));
        ctx.putNodeOutput("uid_only", new JSONObject().put("output", new JSONObject().put("rows", rows)));
        return ctx;
    }

    private static PlaybookResolvedToolArgs resolveExtendedToolBinding(PlaybookRunContext ctx) throws Exception {
        JSONObject binding = new JSONObject().put("$infotable", new JSONObject()
                .put("rows", new JSONObject().put("$ref", "uid_only.output.rows"))
                .put("dataShapeName", UID_SHAPE));
        stubUidShape();
        return PlaybookExpressionResolver.resolvePlaybookToolCallArgs(
                new JSONObject().put("EquipmentUIDs", binding), EXT_TOOL, ctx);
    }

    private static ExtendedToolRegistrySnapshot extendedRegistry() {
        ToolDefinition td = new ToolDefinition(EXT_TOOL, "alarm events", Map.of("type", "object"), true);
        ExtendedToolDefinition ext = new ExtendedToolDefinition(EXT_TOOL, "t", "w", "AlarmThing", "GetAlarmEvents_AI",
                false, false, td);
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
}
