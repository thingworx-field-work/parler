package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.common.interfaces.IDataShapeDefinitionProvider;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.tools.InfotableJsonCodec;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

class PlaybookExpressionResolverTest {

    private static final String EXT_TOOL = "GetAlarmEvents_AI";
    private static final String UID_SHAPE = "PTC.SCA.SCO.Utilities.UID";

    @AfterEach
    void tearDown() {
        InfotableJsonCodec.clearTestDataShapeProviderOverride();
    }

    @Test
    void resolve_rejectsDollarTableOutsideToolArgs() {
        PlaybookRunContext ctx = new PlaybookRunContext("r1", "p", new JSONObject());
        JSONObject bad = new JSONObject().put("$table", "n.result");
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookExpressionResolver.resolve(bad, ctx));
        assertEquals("TABLE_REF_NOT_ALLOWED_HERE", ex.failureCode());
    }

    @Test
    void resolve_rejectsDollarInfotableOutsideToolArgs() {
        PlaybookRunContext ctx = new PlaybookRunContext("r1b", "p", new JSONObject());
        JSONObject bad = new JSONObject().put("$infotable", new JSONObject());
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookExpressionResolver.resolve(bad, ctx));
        assertEquals("INFOTABLE_REF_NOT_ALLOWED_HERE", ex.failureCode());
    }

    @Test
    void resolve_refTraversesJsonStringResult() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r1c", "p", new JSONObject());
        JSONObject result = new JSONObject().put("rows",
                new JSONArray().put(new JSONObject().put("machineName", "Machine-1")));
        JSONObject toolOutput = new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", result.toString());
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", toolOutput));

        Object resolved = PlaybookExpressionResolver.resolve(
                new JSONObject().put("$ref", "listing.toolOutput.result.rows"), ctx);

        assertTrue(resolved instanceof JSONArray);
        assertEquals("Machine-1", ((JSONArray) resolved).getJSONObject(0).getString("machineName"));
    }

    @Test
    void resolve_refTraversesJsonObjectResult() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r1d", "p", new JSONObject());
        JSONObject result = new JSONObject().put("rows",
                new JSONArray().put(new JSONObject().put("machineName", "Machine-2")));
        JSONObject toolOutput = new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", result);
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", toolOutput));

        Object resolved = PlaybookExpressionResolver.resolve(
                new JSONObject().put("$ref", "listing.toolOutput.result.rows"), ctx);

        assertTrue(resolved instanceof JSONArray);
        assertEquals("Machine-2", ((JSONArray) resolved).getJSONObject(0).getString("machineName"));
    }

    @Test
    void resolve_refMalformedJsonStringResult_failsClosed() {
        PlaybookRunContext ctx = new PlaybookRunContext("r1e", "p", new JSONObject());
        JSONObject toolOutput = new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", "{not-json}");
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", toolOutput));

        assertThrows(PlaybookRunException.class, () -> PlaybookExpressionResolver.resolve(
                new JSONObject().put("$ref", "listing.toolOutput.result.rows"), ctx));
    }

    @Test
    void resolve_refDoesNotParseResultOutsideToolOutput() {
        PlaybookRunContext ctx = new PlaybookRunContext("r1g", "p", new JSONObject());
        String result = new JSONObject().put("rows", new JSONArray()).toString();
        ctx.putNodeOutput("derive", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("result", result)));

        assertThrows(PlaybookRunException.class, () -> PlaybookExpressionResolver.resolve(
                new JSONObject().put("$ref", "derive.output.result.rows"), ctx));
    }

    @Test
    void resolve_inputDoesNotParseToolOutputResult() {
        String result = new JSONObject().put("rows", new JSONArray()).toString();
        PlaybookRunContext ctx = new PlaybookRunContext("r1h", "p", new JSONObject()
                .put("toolOutput", new JSONObject().put("result", result)));

        assertThrows(PlaybookRunException.class, () -> PlaybookExpressionResolver.resolve(
                new JSONObject().put("$input", "toolOutput.result.rows"), ctx));
    }

    @Test
    void resolve_refTerminalJsonStringResult_remainsOpaque() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r1f", "p", new JSONObject());
        String result = new JSONObject().put("rows", new JSONArray()).toString();
        JSONObject toolOutput = new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", result);
        ctx.putNodeOutput("listing", new JSONObject().put("status", "ok").put("toolOutput", toolOutput));

        Object resolved = PlaybookExpressionResolver.resolve(
                new JSONObject().put("$ref", "listing.toolOutput.result"), ctx);

        assertEquals(result, resolved);
    }

    @Test
    void resolveTableRef_invalidSuffix_usesInvalidFormat() {
        PlaybookRunContext ctx = new PlaybookRunContext("r3", "p", new JSONObject());
        PlaybookRunException ex = assertThrows(PlaybookRunException.class,
                () -> PlaybookExpressionResolver.resolvePlaybookToolCallArgs(
                        new JSONObject().put("T", new JSONObject().put("$table", "n.rows")), EXT_TOOL, ctx));
        assertEquals("TABLE_REF_INVALID_FORMAT", ex.failureCode());
    }

    @Test
    void resolveTableRef_missingTable_usesNotFound() {
        PlaybookRunContext ctx = new PlaybookRunContext("r4", "p", new JSONObject());
        PlaybookRunException ex = assertThrows(PlaybookRunException.class,
                () -> PlaybookExpressionResolver.resolvePlaybookToolCallArgs(
                        new JSONObject().put("T", new JSONObject().put("$table", "missing.result")), EXT_TOOL, ctx));
        assertEquals("TABLE_REF_NOT_FOUND", ex.failureCode());
    }

    @Test
    void resolvePlaybookToolCallArgs_tableObjectWithExtraKeys_invalidFormat() {
        PlaybookRunContext ctx = new PlaybookRunContext("r5", "p", new JSONObject());
        JSONObject badTable = new JSONObject().put("$table", "n.result").put("extra", true);
        PlaybookRunException ex = assertThrows(PlaybookRunException.class,
                () -> PlaybookExpressionResolver.resolvePlaybookToolCallArgs(
                        new JSONObject().put("T", badTable), EXT_TOOL, ctx));
        assertEquals("TABLE_REF_INVALID_FORMAT", ex.failureCode());
    }

    @Test
    void resolvePlaybookToolCallArgs_nestedDollarTable_rejected() {
        PlaybookRunContext ctx = new PlaybookRunContext("r6", "p", new JSONObject());
        JSONObject args = new JSONObject().put("payload",
                new JSONObject().put("rows", new JSONObject().put("$table", "n.result")));
        PlaybookRunException ex = assertThrows(PlaybookRunException.class,
                () -> PlaybookExpressionResolver.resolvePlaybookToolCallArgs(args, EXT_TOOL, ctx));
        assertEquals("TABLE_REF_NOT_ALLOWED_HERE", ex.failureCode());
    }

    @Test
    void resolvePlaybookToolCallArgs_topLevelInfotable_buildsInfoTable() throws Exception {
        stubUidShape();
        PlaybookRunContext ctx = new PlaybookRunContext("r7", "p", new JSONObject());
        JSONArray rows = new JSONArray().put(new JSONObject().put("UID", 42));
        ctx.putNodeOutput("uid_only", new JSONObject().put("output", new JSONObject().put("rows", rows)));

        JSONObject binding = new JSONObject().put("$infotable", new JSONObject()
                .put("rows", new JSONObject().put("$ref", "uid_only.output.rows"))
                .put("dataShapeName", UID_SHAPE));
        PlaybookResolvedToolArgs resolved = PlaybookExpressionResolver.resolvePlaybookToolCallArgs(
                new JSONObject().put("EquipmentUIDs", binding), EXT_TOOL, ctx);

        assertTrue(resolved.jsonArgs().isEmpty());
        InfoTable it = resolved.infotableArgs().get("EquipmentUIDs");
        assertNotNull(it);
        assertEquals(1, it.getRowCount());
    }

    @Test
    void resolvePlaybookToolCallArgs_infotableEmptyRows_rejectedUnlessAllowEmpty() throws Exception {
        stubUidShape();
        PlaybookRunContext ctx = new PlaybookRunContext("r8", "p", new JSONObject());
        ctx.putNodeOutput("uid_only", new JSONObject().put("output", new JSONObject().put("rows", new JSONArray())));

        JSONObject binding = new JSONObject().put("$infotable", new JSONObject()
                .put("rows", new JSONObject().put("$ref", "uid_only.output.rows"))
                .put("dataShapeName", UID_SHAPE));
        PlaybookRunException ex = assertThrows(PlaybookRunException.class,
                () -> PlaybookExpressionResolver.resolvePlaybookToolCallArgs(
                        new JSONObject().put("EquipmentUIDs", binding), EXT_TOOL, ctx));
        assertEquals("INFOTABLE_EMPTY", ex.failureCode());

        JSONObject allowEmpty = new JSONObject().put("$infotable", new JSONObject()
                .put("rows", new JSONObject().put("$ref", "uid_only.output.rows"))
                .put("dataShapeName", UID_SHAPE)
                .put("allowEmpty", true));
        PlaybookResolvedToolArgs ok = PlaybookExpressionResolver.resolvePlaybookToolCallArgs(
                new JSONObject().put("EquipmentUIDs", allowEmpty), EXT_TOOL, ctx);
        assertEquals(0, ok.infotableArgs().get("EquipmentUIDs").getRowCount());
    }

    @Test
    void resolvePlaybookToolCallArgs_invokeServiceParametersInfotable_deferred() {
        PlaybookRunContext ctx = new PlaybookRunContext("r9", "p", new JSONObject());
        JSONObject args = new JSONObject()
                .put("entityName", "E")
                .put("serviceName", "GetAlarmEvents_AI")
                .put("parameters", new JSONObject().put("EquipmentUIDs", new JSONObject().put("$infotable",
                        new JSONObject()
                                .put("rows", new JSONObject().put("$ref", "uid_only.output.rows"))
                                .put("dataShapeName", UID_SHAPE))));
        PlaybookRunException ex = assertThrows(PlaybookRunException.class,
                () -> PlaybookExpressionResolver.resolvePlaybookToolCallArgs(args, "invoke_service", ctx));
        assertEquals("INFOTABLE_REF_NOT_ALLOWED_HERE", ex.failureCode());
    }

    @Test
    void resolvePlaybookToolCallArgs_nestedDollarInfotable_rejected() {
        PlaybookRunContext ctx = new PlaybookRunContext("r11", "p", new JSONObject());
        JSONObject args = new JSONObject().put("payload",
                new JSONObject().put("rows", new JSONObject().put("$infotable", new JSONObject())));
        PlaybookRunException ex = assertThrows(PlaybookRunException.class,
                () -> PlaybookExpressionResolver.resolvePlaybookToolCallArgs(args, EXT_TOOL, ctx));
        assertEquals("INFOTABLE_REF_NOT_ALLOWED_HERE", ex.failureCode());
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
