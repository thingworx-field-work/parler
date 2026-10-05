package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

class PlaybookEvidenceFormatterTest {

    @Test
    void format_usesExplicitEvidenceLinesFromDerives() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-ev", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());

        JSONObject taxonomyOutput = new JSONObject()
                .put("EntityType", "ThingTemplate")
                .put("EntityName", "StackingRobotTemplate")
                .put("CriticalProperties", "PTCDisplayName;Speed;Voltage")
                .put("CriticalPropertiesList", new JSONArray().put("PTCDisplayName").put("Speed").put("Voltage"));
        JSONObject taxonomyNode = new JSONObject().put("status", "ok").put("output", taxonomyOutput);
        PlaybookNodeEvidence.attach(taxonomyNode,
                PlaybookNodeEvidence.singleLine(PlaybookNodeEvidence.taxonomyLine(taxonomyOutput)));
        ctx.putNodeOutput("taxonomy_row", taxonomyNode);

        JSONArray assets = new JSONArray()
                .put(new JSONObject().put("region", "USA").put("name", "USA-Robot-1"))
                .put(new JSONObject().put("region", "Germany").put("name", "DE-Robot-1"));
        JSONObject regionEntitiesNode = new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", assets));
        PlaybookNodeEvidence.attach(regionEntitiesNode, PlaybookNodeEvidence.regionEntitiesLines(assets, new JSONArray()));
        ctx.putNodeOutput("region_entities", regionEntitiesNode);

        JSONObject groupsByRegion = new JSONObject()
                .put("USA", new JSONArray().put(new JSONObject()
                        .put("property", "WristTemp").put("alertCount", 3)))
                .put("Germany", new JSONArray().put(new JSONObject()
                        .put("property", "Voltage").put("alertCount", 1)));
        JSONObject alertGroupsOutput = new JSONObject().put("groupsByRegion", groupsByRegion);
        JSONObject alertGroupsNode = new JSONObject().put("status", "ok").put("output", alertGroupsOutput);
        PlaybookNodeEvidence.attach(alertGroupsNode, PlaybookNodeEvidence.alertGroupsLines(alertGroupsOutput));
        ctx.putNodeOutput("alert_groups", alertGroupsNode);

        JSONObject usaRegion = new JSONObject()
                .put("name", "USA")
                .put("assetCount", 1)
                .put("alertGroups", new JSONArray().put(new JSONObject()
                        .put("property", "WristTemp").put("alertCount", 3)))
                .put("topProperties", new JSONArray().put(new JSONObject()
                        .put("name", "Speed").put("value", 12)));
        JSONObject deRegion = new JSONObject()
                .put("name", "Germany")
                .put("assetCount", 1)
                .put("alertGroups", new JSONArray().put(new JSONObject()
                        .put("property", "Voltage").put("alertCount", 1)))
                .put("topProperties", new JSONArray().put(new JSONObject()
                        .put("name", "Voltage").put("value", 220)));
        JSONObject summaryOutput = new JSONObject()
                .put("regions", new JSONArray().put(usaRegion).put(deRegion))
                .put("comparison", new JSONObject()
                        .put("higherAttentionRegion", "USA")
                        .put("reasons", new JSONArray().put("USA: 3 alert rows by source property")));
        JSONObject regionSummaryNode = new JSONObject().put("status", "ok").put("output", summaryOutput);
        PlaybookNodeEvidence.attach(regionSummaryNode, PlaybookNodeEvidence.regionSummaryLines(summaryOutput));
        ctx.putNodeOutput("region_summary", regionSummaryNode);

        JSONArray refs = new JSONArray()
                .put("taxonomy_row")
                .put("region_entities")
                .put("alert_groups")
                .put("region_summary");
        String text = PlaybookEvidenceFormatter.format(null, ctx, refs, 8000);

        assertTrue(text.contains("EntityType=ThingTemplate"), text);
        assertTrue(text.contains("CriticalProperties=[PTCDisplayName, Speed, Voltage]"), text);
        assertTrue(text.contains("USA-Robot-1"), text);
        assertTrue(text.contains("WristTemp=3"), text);
        assertTrue(text.contains("higherAttention=USA"), text);
        assertTrue(!text.contains("region summary ready"), text);
    }

    @Test
    void format_trendSummaryEvidence_includesAggregatesAndDirection() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext(
                "run-trend", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONArray assets = new JSONArray()
                .put(new JSONObject()
                        .put("slot", "A")
                        .put("thingName", "ORD JetDryer 02")
                        .put("propertyName", "WristTemp")
                        .put("status", "ok")
                        .put("first", 70)
                        .put("last", 88)
                        .put("min", 65)
                        .put("max", 93)
                        .put("direction", "rising")
                        .put("pointsReturned", 42));
        JSONObject trendNode = new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", assets).put("gaps", new JSONArray()));
        PlaybookNodeEvidence.attach(trendNode, PlaybookNodeEvidence.trendSummaryLines(assets, new JSONArray()));
        ctx.putNodeOutput("trend_summary", trendNode);

        String text = PlaybookEvidenceFormatter.format(null, ctx, new JSONArray().put("trend_summary"), 8000);
        assertTrue(text.contains("first=70"), text);
        assertTrue(text.contains("last=88"), text);
        assertTrue(text.contains("direction=rising"), text);
        assertTrue(!text.contains("unknown"), text);
    }

    @Test
    void projectTableEvidenceSummary_prefersRawRetainedInfotableOverToolJsonRows() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r-raw", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("UtilizationState", "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition("Percentage", "", BaseTypes.NUMBER));
        InfoTable it = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("UtilizationState", new StringPrimitive("Running"));
        row.put("Percentage", new NumberPrimitive(58.42));
        it.addRow(row);
        ctx.putRawTable("aggregate_by_state.result", it, null);

        JSONObject node = new JSONObject()
                .put("evidence", new JSONObject()
                        .put("table", new JSONObject()
                                .put("columns", new JSONArray().put("UtilizationState").put("Percentage"))
                                .put("maxRows", 10)));
        JSONObject toolOut = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject()
                        .put("UtilizationState", "Idle")
                        .put("Percentage", 1.0)));
        String s = PlaybookEvidenceFormatter.projectTableEvidenceSummary("aggregate_by_state", node, ctx, toolOut);
        assertTrue(s.contains("Running"), s);
        assertTrue(s.contains("58.42"), s);
        assertTrue(!s.contains("Idle"), s);
    }

    @Test
    void projectTableEvidenceSummary_notesMissingConfiguredColumns() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r-proj", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        JSONObject node = new JSONObject()
                .put("evidence", new JSONObject()
                        .put("table", new JSONObject()
                                .put("columns", new JSONArray().put("present").put("absent"))
                                .put("maxRows", 5)));
        JSONObject toolOut = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("present", "ok")));
        String s = PlaybookEvidenceFormatter.projectTableEvidenceSummary("nid", node, ctx, toolOut);
        assertTrue(s.contains("present=ok"), s);
        assertTrue(s.contains("no values shown for configured columns: absent"), s);
    }

    @Test
    void format_prependsScalarToolOutputRootFieldsBeforeProjectedRows() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"hist\",\"kind\":\"tool_call\",\"tool\":\"query_alert_history\",\"dependsOn\":[],\"args\":{},"
                + "\"evidence\":{\"includeToolOutputRootFields\":[\"appliedStartTime\",\"rowCount\"],"
                + "\"table\":{\"maxRows\":5,\"columns\":[\"sourceProperty\"]}}}"
                + "],\"finalNode\":\"hist\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunContext ctx = new PlaybookRunContext("run-root", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        JSONObject toolOut = new JSONObject()
                .put("appliedStartTime", "2024-05-31T00:00:00.000Z")
                .put("rowCount", 2)
                .put("rows", new JSONArray().put(new JSONObject().put("sourceProperty", "MotorTemp")));
        ctx.putNodeOutput("hist", new JSONObject().put("status", "ok").put("toolOutput", toolOut));
        String text = PlaybookEvidenceFormatter.format(doc, ctx, new JSONArray().put("hist"), 8000);
        assertTrue(text.contains("appliedStartTime=2024-05-31T00:00:00.000Z"), text);
        assertTrue(text.contains("rowCount=2"), text);
        assertTrue(text.contains("sourceProperty=MotorTemp"), text);
        int iRoot = text.indexOf("appliedStartTime=");
        int iRow = text.indexOf("row1:");
        assertTrue(iRoot >= 0 && iRow > iRoot, text);
    }

    @Test
    void format_zeroRowsWithRootMetadata_showsRootLinesAndNoRowsNote() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"hist\",\"kind\":\"tool_call\",\"tool\":\"query_alert_history\",\"dependsOn\":[],\"args\":{},"
                + "\"evidence\":{\"includeToolOutputRootFields\":[\"appliedStartTime\",\"rowCount\"],"
                + "\"table\":{\"maxRows\":5,\"columns\":[\"sourceProperty\"]}}}"
                + "],\"finalNode\":\"hist\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunContext ctx = new PlaybookRunContext("run-z", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        JSONObject toolOut = new JSONObject()
                .put("appliedStartTime", "2024-05-31T00:00:00.000Z")
                .put("rowCount", 0)
                .put("rows", new JSONArray());
        ctx.putNodeOutput("hist", new JSONObject().put("status", "ok").put("toolOutput", toolOut));
        String text = PlaybookEvidenceFormatter.format(doc, ctx, new JSONArray().put("hist"), 8000);
        assertTrue(text.contains("appliedStartTime=2024-05-31T00:00:00.000Z"), text);
        assertTrue(text.contains("rowCount=0"), text);
        assertTrue(text.contains("no table rows"), text);
        assertTrue(!text.contains("row1:"), text);
    }

    @Test
    void format_rootFieldsOnly_withoutTable() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"h\",\"kind\":\"tool_call\",\"tool\":\"query_alert_history\",\"dependsOn\":[],\"args\":{},"
                + "\"evidence\":{\"includeToolOutputRootFields\":[\"rowCount\",\"thingName\"]}}"
                + "],\"finalNode\":\"h\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunContext ctx = new PlaybookRunContext("run-ro", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        JSONObject toolOut = new JSONObject().put("rowCount", 0).put("thingName", "T1");
        ctx.putNodeOutput("h", new JSONObject().put("status", "ok").put("toolOutput", toolOut));
        String text = PlaybookEvidenceFormatter.format(doc, ctx, new JSONArray().put("h"), 8000);
        assertTrue(text.contains("rowCount=0"), text);
        assertTrue(text.contains("thingName=T1"), text);
    }

    @Test
    void format_fanOutChildToolEvidence_projectsRootsSamplesAndUiArtifactWithoutChartSeries() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],\"items\":[],\"maxItems\":4,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"query_property_history\",\"dependsOn\":[],\"args\":{},"
                + "\"evidence\":{\"includeToolOutputRootFields\":[\"chartEmitted\",\"chartBlockPointCount\","
                + "\"returnedRows\",\"totalRows\"],"
                + "\"table\":{\"maxRows\":3,\"columns\":[\"timestamp\",\"value\"]}}}}"
                + "],\"finalNode\":\"fo\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunContext ctx = new PlaybookRunContext("run-fo", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject toolA = new JSONObject()
                .put("status", "success")
                .put("resultKind", "NUMERIC_HISTORY_INLINE")
                .put("thingName", "Thing.A")
                .put("propertyName", "contactForce")
                .put("returnedRows", 20)
                .put("totalRows", 242)
                .put("chartEmitted", true)
                .put("chartBlockPersisted", true)
                .put("chartBlockPointCount", 242)
                .put("chart_kind", "line")
                .put("chartBlock", new JSONObject()
                        .put("series", new JSONArray().put(new JSONObject().put("x", 1).put("y", 2))));
        toolA.put("sampleRows", new JSONArray()
                .put(new JSONObject().put("timestamp", "2026-06-06T19:58:15.654Z").put("value", 187.01))
                .put(new JSONObject().put("timestamp", "2026-06-06T20:04:15.653Z").put("value", 92.65)));
        JSONObject childA = new JSONObject()
                .put("status", "ok")
                .put("item", new JSONObject().put("thingName", "Thing.A").put("propertyName", "contactForce"))
                .put("toolOutput", toolA);
        JSONObject toolB = new JSONObject()
                .put("status", "success")
                .put("resultKind", "NUMERIC_HISTORY_INLINE")
                .put("thingName", "Thing.B")
                .put("propertyName", "contactForce")
                .put("returnedRows", 20)
                .put("totalRows", 242)
                .put("chartEmitted", true)
                .put("chartBlockPersisted", true)
                .put("chartBlockPointCount", 242)
                .put("chart_kind", "line")
                .put("chartBlock", new JSONObject()
                        .put("series", new JSONArray().put(new JSONObject().put("x", 3).put("y", 4))));
        toolB.put("sampleRows", new JSONArray()
                .put(new JSONObject().put("timestamp", "2026-06-06T19:58:15.666Z").put("value", 114.26))
                .put(new JSONObject().put("timestamp", "2026-06-06T20:04:15.654Z").put("value", 91.49)));
        JSONObject childB = new JSONObject()
                .put("status", "ok")
                .put("item", new JSONObject().put("thingName", "Thing.B").put("propertyName", "contactForce"))
                .put("toolOutput", toolB);
        JSONObject fanOut = new JSONObject()
                .put("status", "ok")
                .put("children", new JSONArray().put(childA).put(childB));
        ctx.putNodeOutput("fo", fanOut);
        String text = PlaybookEvidenceFormatter.format(doc, ctx, new JSONArray().put("fo"), 16000);
        assertTrue(text.contains("child[0]"), text);
        assertTrue(text.contains("Thing.A/contactForce"), text);
        assertTrue(text.contains("chartEmitted=true"), text);
        assertTrue(text.contains("chartBlockPointCount=242"), text);
        assertTrue(text.contains("uiArtifact.chart emitted=true"), text);
        assertTrue(text.contains("row1:"), text);
        assertFalse(text.contains("\"series\""), text);
    }

    @Test
    void format_primaryPropertyEvidence_suppressesFalseHeuristicWhenNumericHistoryProven() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"pp\",\"kind\":\"derive\",\"op\":\"primary_property\",\"dependsOn\":[],\"args\":{}},"
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],\"items\":[],\"maxItems\":2,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"query_property_history\",\"dependsOn\":[],\"args\":{},"
                + "\"evidence\":{\"includeToolOutputRootFields\":[\"propertyName\"]}}}"
                + "],\"finalNode\":\"fo\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunContext ctx = new PlaybookRunContext("run-pp", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject primaryOut = new JSONObject()
                .put("primaryProperty", "contactForce")
                .put("isNumeric", false)
                .put("numericNameHeuristicMatched", false)
                .put("alertGroups", new JSONArray().put(new JSONObject()
                        .put("property", "contactForce")
                        .put("alertCount", 3)))
                .put("gaps", new JSONArray());
        JSONObject ppNode = new JSONObject().put("status", "ok").put("output", primaryOut);
        PlaybookNodeEvidence.attach(ppNode, PlaybookNodeEvidence.singleLine(PlaybookNodeEvidence.primaryPropertyLine(primaryOut)));
        ctx.putNodeOutput("pp", ppNode);
        JSONObject toolOut = new JSONObject()
                .put("status", "success")
                .put("resultKind", "NUMERIC_HISTORY_INLINE")
                .put("propertyName", "contactForce")
                .put("pointsReturned", 20)
                .put("sampleRows", new JSONArray().put(new JSONObject().put("timestamp", "t").put("value", 1.0)));
        JSONObject fanChild = new JSONObject()
                .put("status", "ok")
                .put("item", new JSONObject().put("thingName", "X").put("propertyName", "contactForce"))
                .put("toolOutput", toolOut);
        ctx.putNodeOutput("fo", new JSONObject().put("status", "ok").put("children", new JSONArray().put(fanChild)));
        JSONArray refs = new JSONArray().put("pp").put("fo");
        String text = PlaybookEvidenceFormatter.format(doc, ctx, refs, 16000);
        assertTrue(text.contains("selected from alert evidence"), text);
        assertTrue(text.contains("numeric history observed for this property"), text);
        assertFalse(text.contains("numericNameHeuristicMatched=false"), text);
        assertFalse(text.contains("name heuristic only; not schema authority"), text);
    }

    @Test
    void primaryPropertyLineForSummary_withoutProven_clarifiesAlertDrivenSelection() {
        JSONObject out = new JSONObject()
                .put("primaryProperty", "contactForce")
                .put("numericNameHeuristicMatched", false)
                .put("alertGroups", new JSONArray().put(new JSONObject()
                        .put("property", "contactForce")
                        .put("alertCount", 2)))
                .put("gaps", new JSONArray());
        String line = PlaybookNodeEvidence.primaryPropertyLineForSummary(out, java.util.Collections.emptySet());
        assertTrue(line.contains("selected from alert evidence"), line);
        assertTrue(line.contains("alert-driven"), line);
        assertTrue(line.contains("numeric name heuristic did not match"), line);
        assertFalse(line.contains("numericNameHeuristicMatched=false"), line);
    }

    @Test
    void format_fanOutChildArtifactOnly_emitsUiArtifactWithoutChildEvidenceConfig() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],\"items\":[],\"maxItems\":4,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"query_property_history\",\"dependsOn\":[],\"args\":{}}}"
                + "],\"finalNode\":\"fo\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunContext ctx = new PlaybookRunContext("run-foa", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONObject toolA = new JSONObject()
                .put("status", "success")
                .put("resultKind", "NUMERIC_HISTORY_INLINE")
                .put("thingName", "Thing.A")
                .put("propertyName", "contactForce")
                .put("chartEmitted", true)
                .put("chartBlockPersisted", true)
                .put("chartBlockPointCount", 242)
                .put("chart_kind", "line")
                .put("chartBlock", new JSONObject()
                        .put("series", new JSONArray().put(new JSONObject().put("x", 1).put("y", 2))));
        JSONObject childA = new JSONObject()
                .put("status", "ok")
                .put("item", new JSONObject().put("thingName", "Thing.A").put("propertyName", "contactForce"))
                .put("toolOutput", toolA);
        ctx.putNodeOutput("fo", new JSONObject().put("status", "ok").put("children", new JSONArray().put(childA)));
        String text = PlaybookEvidenceFormatter.format(doc, ctx, new JSONArray().put("fo"), 16000);
        assertTrue(text.contains("child[0]"), text);
        assertTrue(text.contains("Thing.A/contactForce"), text);
        assertTrue(text.contains("uiArtifact.chart emitted=true"), text);
        assertTrue(text.contains("pointCount=242"), text);
        assertFalse(text.contains("\"series\""), text);
        assertFalse(text.contains("Completed"), text);
    }

    @Test
    void format_fanOutChildEvidence_respectsMaxEvidenceBytes() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],\"items\":[],\"maxItems\":16,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"query_property_history\",\"dependsOn\":[],\"args\":{},"
                + "\"evidence\":{\"includeToolOutputRootFields\":[\"thingName\",\"propertyName\",\"returnedRows\"],"
                + "\"table\":{\"maxRows\":20,\"columns\":[\"timestamp\",\"value\"]}}}}"
                + "],\"finalNode\":\"fo\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunContext ctx = new PlaybookRunContext("run-fob", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONArray children = new JSONArray();
        for (int c = 0; c < 12; c++) {
            JSONArray sample = new JSONArray();
            for (int r = 0; r < 20; r++) {
                sample.put(new JSONObject().put("timestamp", "2026-06-06T19:58:15.000Z").put("value", 100 + r));
            }
            JSONObject tool = new JSONObject()
                    .put("status", "success")
                    .put("resultKind", "NUMERIC_HISTORY_INLINE")
                    .put("thingName", "Thing.Asset" + c)
                    .put("propertyName", "contactForce")
                    .put("returnedRows", 20)
                    .put("sampleRows", sample);
            children.put(new JSONObject()
                    .put("status", "ok")
                    .put("item", new JSONObject().put("thingName", "Thing.Asset" + c).put("propertyName", "contactForce"))
                    .put("toolOutput", tool));
        }
        ctx.putNodeOutput("fo", new JSONObject().put("status", "ok").put("children", children));
        int maxBytes = 400;
        String text = PlaybookEvidenceFormatter.format(doc, ctx, new JSONArray().put("fo"), maxBytes);
        int len = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(len <= maxBytes, "evidence exceeded maxBytes: len=" + len);
        assertTrue(text.contains("(truncated)"), text);
    }

    @Test
    void format_fanOutChildEvidence_respectsMaxEvidenceBytes_withMultibyteContent() throws Exception {
        String docJson = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"nodes\":["
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],\"items\":[],\"maxItems\":16,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"query_property_history\",\"dependsOn\":[],\"args\":{},"
                + "\"evidence\":{\"includeToolOutputRootFields\":[\"thingName\",\"propertyName\",\"returnedRows\"],"
                + "\"table\":{\"maxRows\":20,\"columns\":[\"timestamp\",\"value\"]}}}}"
                + "],\"finalNode\":\"fo\"}";
        PlaybookDocument doc = PlaybookDocument.parse(docJson);
        PlaybookRunContext ctx = new PlaybookRunContext("run-mb", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONArray children = new JSONArray();
        for (int c = 0; c < 8; c++) {
            JSONArray sample = new JSONArray();
            for (int r = 0; r < 20; r++) {
                sample.put(new JSONObject().put("timestamp", "時刻-記録-" + r).put("value", "値-" + r + "-℃"));
            }
            JSONObject tool = new JSONObject()
                    .put("status", "success")
                    .put("resultKind", "NUMERIC_HISTORY_INLINE")
                    .put("thingName", "设备-接触力-资产-工位-" + c)
                    .put("propertyName", "接触力-数値プロパティ-属性")
                    .put("returnedRows", 20)
                    .put("sampleRows", sample);
            children.put(new JSONObject()
                    .put("status", "ok")
                    .put("item", new JSONObject().put("thingName", "设备-" + c).put("propertyName", "接触力"))
                    .put("toolOutput", tool));
        }
        ctx.putNodeOutput("fo", new JSONObject().put("status", "ok").put("children", children));
        int maxBytes = 300;
        String text = PlaybookEvidenceFormatter.format(doc, ctx, new JSONArray().put("fo"), maxBytes);
        int len = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(len <= maxBytes, "multibyte evidence exceeded maxBytes: len=" + len);
        assertTrue(text.contains("(truncated)"), text);
    }

    @Test
    void truncateToUtf8ByteBudget_isByteSafeForMultibyteAndTinyBudgets() {
        String multibyte = "接触力接触力接触力接触力接触力";
        String out = PlaybookEvidenceFormatter.truncateToUtf8ByteBudget(multibyte, 20);
        assertTrue(out.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 20,
                "len=" + out.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        String tiny = PlaybookEvidenceFormatter.truncateToUtf8ByteBudget(multibyte, 6);
        assertTrue(tiny.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 6,
                "len=" + tiny.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void projectTableEvidenceSummary_nestedPathFromJsonResultObject() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r-nested", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        JSONObject node = new JSONObject()
                .put("evidence", new JSONObject()
                        .put("table", new JSONObject()
                                .put("path", "result.rows")
                                .put("columns", new JSONArray().put("utilizationState").put("percentage"))
                                .put("maxRows", 5)));
        JSONObject resultBody = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject()
                        .put("utilizationState", "Running")
                        .put("percentage", 58.42)));
        JSONObject toolOut = new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", resultBody);
        String s = PlaybookEvidenceFormatter.projectTableEvidenceSummary("n1", node, ctx, toolOut);
        assertTrue(s.contains("utilizationState=Running"), s);
        assertTrue(s.contains("percentage=58.42"), s);
    }

    @Test
    void projectTableEvidenceSummary_nestedPathFromJsonResultString() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r-str", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        JSONObject node = new JSONObject()
                .put("evidence", new JSONObject()
                        .put("table", new JSONObject()
                                .put("path", "result.rows")
                                .put("columns", new JSONArray().put("utilizationState"))
                                .put("maxRows", 5)));
        JSONObject resultBody = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("utilizationState", "Idle")));
        JSONObject toolOut = new JSONObject()
                .put("status", "success")
                .put("resultKind", "JSON")
                .put("result", resultBody.toString());
        String s = PlaybookEvidenceFormatter.projectTableEvidenceSummary("n1", node, ctx, toolOut);
        assertTrue(s.contains("utilizationState=Idle"), s);
    }

    @Test
    void formatIncludedToolOutputPaths_projectsNestedScalars() throws Exception {
        JSONObject node = new JSONObject()
                .put("evidence", new JSONObject()
                        .put("includeToolOutputPaths", new JSONArray()
                                .put("result.stats.utilizationPercent")
                                .put("result.stats.eventCount")));
        JSONObject toolOut = new JSONObject()
                .put("result", new JSONObject()
                        .put("stats", new JSONObject().put("utilizationPercent", 64.36).put("eventCount", 40931)));
        String lines = PlaybookEvidenceFormatter.formatIncludedToolOutputPaths(node, toolOut);
        assertNotNull(lines);
        assertTrue(lines.contains("result.stats.utilizationPercent=64.36"), lines);
        assertTrue(lines.contains("result.stats.eventCount=40931"), lines);
    }

    @Test
    void format_alertAttribution_distinguishesThingsInFinalEvidence() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-ev-attr", PlaybookIds.V1A_PLAYBOOK_ID, new JSONObject());
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", new JSONArray()
                        .put(new JSONObject().put("region", "USA").put("name", "Thing-A"))
                        .put(new JSONObject().put("region", "USA").put("name", "Thing-C"))
                        .put(new JSONObject().put("region", "Germany").put("name", "Thing-B")))));
        JSONObject multi = new JSONObject()
                .put("status", "success")
                .put("resultKind", "ALERT_SUMMARY_MULTI")
                .put("completeness", "complete")
                .put("byThing", new JSONArray()
                        .put(new JSONObject().put("thingName", "Thing-A").put("status", "success")
                                .put("totalAlerts", 1)
                                .put("topAlerts", new JSONArray().put(new JSONObject()
                                        .put("alertName", "Voltage_Low").put("sourceProperty", "operationalVoltage"))))
                        .put(new JSONObject().put("thingName", "Thing-C").put("status", "success")
                                .put("totalAlerts", 0).put("topAlerts", new JSONArray()))
                        .put(new JSONObject().put("thingName", "Thing-B").put("status", "success")
                                .put("totalAlerts", 1)
                                .put("topAlerts", new JSONArray().put(new JSONObject()
                                        .put("alertName", "Seal_Time_High").put("sourceProperty", "sealingTime")))));
        ctx.putNodeOutput("alerts_by_region", new JSONObject().put("status", "ok").put("toolOutput", multi));
        JSONObject grouped = PlaybookDeriveOps.execute("group_alerts_by_source_property",
                new JSONObject()
                        .put("alertSummaryNodeId", "alerts_by_region")
                        .put("assetsRef", "region_entities.output.assets"),
                ctx);
        ctx.putNodeOutput("alert_groups", grouped);

        String text = PlaybookEvidenceFormatter.format(null, ctx, new JSONArray().put("alert_groups"), 8000);
        assertTrue(text.contains("alerts on Thing-A [USA]: Voltage_Low(operationalVoltage)"), text);
        assertTrue(text.contains("alerts on Thing-B [Germany]: Seal_Time_High(sealingTime)"), text);
        assertFalse(text.contains("alerts on Thing-C"),
                "zero-alert Thing must not gain an attribution line in the final evidence");
    }

    @Test
    void format_alertAttributionTruncation_survivesToFinalEvidence() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("run-ev-attr-cap", PlaybookIds.V1A_PLAYBOOK_ID,
                new JSONObject());
        JSONArray assets = new JSONArray();
        JSONArray byThing = new JSONArray();
        for (int t = 0; t < 15; t++) {
            String name = "Thing-" + t;
            assets.put(new JSONObject().put("region", "USA").put("name", name));
            JSONArray top = new JSONArray();
            for (int a = 0; a < 3; a++) {
                top.put(new JSONObject().put("alertName", "A" + t + "_" + a).put("sourceProperty", "Speed"));
            }
            byThing.put(new JSONObject().put("thingName", name).put("status", "success")
                    .put("totalAlerts", 3).put("topAlerts", top));
        }
        ctx.putNodeOutput("region_entities", new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("assets", assets)));
        ctx.putNodeOutput("alerts_by_region", new JSONObject().put("status", "ok").put("toolOutput", new JSONObject()
                .put("status", "success")
                .put("resultKind", "ALERT_SUMMARY_MULTI")
                .put("completeness", "complete")
                .put("byThing", byThing)));
        JSONObject grouped = PlaybookDeriveOps.execute("group_alerts_by_source_property",
                new JSONObject()
                        .put("alertSummaryNodeId", "alerts_by_region")
                        .put("assetsRef", "region_entities.output.assets"),
                ctx);
        ctx.putNodeOutput("alert_groups", grouped);

        String text = PlaybookEvidenceFormatter.format(null, ctx, new JSONArray().put("alert_groups"), 8000);
        assertTrue(text.contains("alert attribution omits 5 additional attributed alert row(s) beyond the cap."),
                text);
        assertFalse(text.contains("cacheId"),
                "final evidence must not promise drill-in handles the rollup did not retain");
    }
}
