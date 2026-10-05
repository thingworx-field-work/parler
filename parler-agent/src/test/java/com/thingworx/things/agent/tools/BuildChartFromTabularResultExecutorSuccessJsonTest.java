package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import com.thingworx.things.agent.llm.ToolCall;

class BuildChartFromTabularResultExecutorSuccessJsonTest {

    BuildChartFromTabularResultExecutorSuccessJsonTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void chartEmitted_truncationMirrorsChartSource_forTruncatedPie() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("cat");
        fx.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fx);
        FieldDefinition fy = new FieldDefinition();
        fy.setName("val");
        fy.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fy);
        InfoTable src = new InfoTable(shape);
        for (int i = 0; i < 10; i++) {
            ValueCollection r = new ValueCollection();
            r.put("cat", new StringPrimitive("c" + i));
            r.put("val", new NumberPrimitive((double) (i + 1)));
            src.addRow(r);
        }
        AgentToolContext.setConversationId("build-chart-json-test-1");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"source\":\"cache_id\",\"cacheId\":\"" + cid + "\",\"kind\":\"pie\",\"xColumn\":\"cat\","
                + "\"yColumn\":\"val\",\"pieSliceMode\":\"top_with_other\",\"pieMaxSlices\":8}";
        String json = BuildChartFromTabularResultExecutor.execute(new ToolCall("bc1", "build_chart_from_tabular_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("CHART_EMITTED", root.get("code").asText());
        assertTrue(root.get("truncationApplied").asBoolean());
        assertTrue(root.get("truncated").asBoolean());
        assertTrue(root.has("source"));
        assertTrue(root.get("source").get("truncationApplied").asBoolean());
        assertTrue(root.get("sourceColumns").isArray());
        assertEquals(2, root.get("sourceColumns").size());
        assertEquals(10, root.get("rowCount").asInt());
        assertTrue(root.get("pointCount").asInt() >= 1);
    }

    @Test
    void duplicate_slice_label_error_includes_recovery_hint() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("cat");
        fx.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fx);
        FieldDefinition fy = new FieldDefinition();
        fy.setName("val");
        fy.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fy);
        InfoTable src = new InfoTable(shape);
        for (int i = 0; i < 2; i++) {
            ValueCollection r = new ValueCollection();
            r.put("cat", new StringPrimitive("dup"));
            r.put("val", new NumberPrimitive((double) (i + 1)));
            src.addRow(r);
        }
        AgentToolContext.setConversationId("build-chart-json-dup");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"source\":\"cache_id\",\"cacheId\":\"" + cid + "\",\"kind\":\"pie\",\"xColumn\":\"cat\","
                + "\"yColumn\":\"val\"}";
        String json = BuildChartFromTabularResultExecutor.execute(new ToolCall("bc-dup", "build_chart_from_tabular_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("DUPLICATE_SLICE_LABEL", root.get("code").asText());
        assertTrue(root.has("recoveryHint"));
        assertTrue(root.get("recoveryHint").asText().contains("aggregate"));
    }

    @Test
    void seriesColumn_line_noLongerInvalidParameters() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("Date");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        FieldDefinition fe = new FieldDefinition();
        fe.setName("EquipmentID");
        fe.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fe);
        FieldDefinition fy = new FieldDefinition();
        fy.setName("RunningPct");
        fy.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fy);
        InfoTable src = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("Date", new StringPrimitive("2025-09-03T00:00:00Z"));
        r1.put("EquipmentID", new StringPrimitive("A"));
        r1.put("RunningPct", new NumberPrimitive(1.0));
        src.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("Date", new StringPrimitive("2025-09-03T00:00:00Z"));
        r2.put("EquipmentID", new StringPrimitive("B"));
        r2.put("RunningPct", new NumberPrimitive(2.0));
        src.addRow(r2);
        AgentToolContext.setConversationId("build-chart-line-long");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"source\":\"cache_id\",\"cacheId\":\"" + cid + "\",\"kind\":\"line\",\"xColumn\":\"Date\","
                + "\"yColumn\":\"RunningPct\",\"seriesColumn\":\"EquipmentID\"}";
        String json = BuildChartFromTabularResultExecutor.execute(
                new ToolCall("bc-line-long", "build_chart_from_tabular_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("CHART_EMITTED", root.get("code").asText());
        assertEquals(2, root.get("seriesCount").asInt());
        assertEquals("long_table_pivot(seriesColumn)", root.get("transformSummary").asText());
    }

    private static InfoTable rankTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fc = new FieldDefinition();
        fc.setName("Device");
        fc.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fc);
        FieldDefinition fy = new FieldDefinition();
        fy.setName("Alarms");
        fy.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fy);
        InfoTable src = new InfoTable(shape);
        String[] names = {"gate-1", "gate-2", "gate-3"};
        double[] values = {2.0, 7.0, 7.0};
        for (int i = 0; i < names.length; i++) {
            ValueCollection r = new ValueCollection();
            r.put("Device", new StringPrimitive(names[i]));
            r.put("Alarms", new NumberPrimitive(values[i]));
            src.addRow(r);
        }
        return src;
    }

    private static String call(String id, String args) {
        return BuildChartFromTabularResultExecutor.execute(new ToolCall(id, "build_chart_from_tabular_result", args));
    }

    @Test
    void orientation_horizontal_explicitBar_mirrorsInSuccessJsonAndChartBlock() throws Exception {
        AgentToolContext.setConversationId("build-chart-hbar-explicit");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(rankTable());
        JsonNode root = MAPPER.readTree(call("bc-hbar-1", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"kind\":\"bar\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\",\"orientation\":\"horizontal\"}"));
        assertEquals("success", root.get("status").asText());
        assertEquals("CHART_EMITTED", root.get("code").asText());
        assertEquals("horizontal", root.get("orientation").asText());
        assertEquals("horizontal", root.get("chartBlock").get("orientation").asText());
        assertEquals("bar", root.get("chartBlock").get("kind").asText());
    }

    @Test
    void stackMode_mirrorsInSuccessJson_andIsRejectedOffBarOrOnASingleSeries() throws Exception {
        AgentToolContext.setConversationId("build-chart-stack");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(rankTable());
        JsonNode single = MAPPER.readTree(call("bc-stack-1", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"kind\":\"bar\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\",\"stackMode\":\"stacked\"}"));
        assertEquals("INVALID_PARAMETERS", single.get("code").asText(), "a single yColumn cannot stack");
        JsonNode grouped = MAPPER.readTree(call("bc-stack-2", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"kind\":\"bar\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\",\"stackMode\":\"grouped\"}"));
        assertEquals("CHART_EMITTED", grouped.get("code").asText());
        assertTrue(grouped.get("stackMode") == null, "grouped is the default and is not written");
        JsonNode stacked = MAPPER.readTree(call("bc-stack-3", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"kind\":\"bar\",\"xColumn\":\"Device\",\"series\":[{\"name\":\"Alarms\",\"yColumn\":\"Alarms\"},"
                + "{\"name\":\"Again\",\"yColumn\":\"Alarms\"}],\"stackMode\":\"percent\",\"orientation\":\"horizontal\"}"));
        assertEquals("CHART_EMITTED", stacked.get("code").asText(), stacked.toString());
        assertEquals("percent", stacked.get("stackMode").asText());
        assertEquals("percent", stacked.get("chartBlock").get("stackMode").asText());
        assertEquals("horizontal", stacked.get("chartBlock").get("orientation").asText());
        JsonNode onLine = MAPPER.readTree(call("bc-stack-4", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"kind\":\"line\",\"xColumn\":\"Alarms\",\"series\":[{\"name\":\"a\",\"yColumn\":\"Alarms\"},"
                + "{\"name\":\"b\",\"yColumn\":\"Alarms\"}],\"stackMode\":\"stacked\"}"));
        assertEquals("INVALID_PARAMETERS", onLine.get("code").asText());
        JsonNode badValue = MAPPER.readTree(call("bc-stack-5", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"kind\":\"bar\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\",\"stackMode\":\"Stacked\"}"));
        assertEquals("INVALID_PARAMETERS", badValue.get("code").asText());
        JsonNode viaIntent = MAPPER.readTree(call("bc-stack-6", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"intent\":\"composition\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\"}"));
        assertEquals("CHART_EMITTED", viaIntent.get("code").asText(), viaIntent.toString());
        assertTrue(viaIntent.get("stackMode") == null, "no intent stacks on its own");
    }

    @Test
    void orientation_absentOrVertical_omitsTheField() throws Exception {
        AgentToolContext.setConversationId("build-chart-hbar-vertical");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(rankTable());
        JsonNode vertical = MAPPER.readTree(call("bc-hbar-2", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"kind\":\"bar\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\",\"orientation\":\"vertical\"}"));
        assertEquals("CHART_EMITTED", vertical.get("code").asText());
        assertTrue(vertical.get("orientation") == null, "vertical is the default and is not written");
        assertTrue(vertical.get("chartBlock").get("orientation") == null);
    }

    @Test
    void orientation_intentResolvedBar_applies_andTieOrderIsStable() throws Exception {
        AgentToolContext.setConversationId("build-chart-hbar-intent");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(rankTable());
        JsonNode root = MAPPER.readTree(call("bc-hbar-3", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"intent\":\"rank\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\",\"orientation\":\"horizontal\"}"));
        assertEquals("CHART_EMITTED", root.get("code").asText());
        assertEquals("bar", root.get("selectedKind").asText());
        assertEquals("horizontal", root.get("orientation").asText());
        JsonNode xs = root.get("chartBlock").get("series").get(0).get("x");
        assertEquals("gate-2", xs.get(0).asText(), "rank sorts descending; the tie keeps source order (HB-4)");
        assertEquals("gate-3", xs.get(1).asText());
        assertEquals("gate-1", xs.get(2).asText());
    }

    @Test
    void orientation_onNonBarKindOrUnknownValue_isInvalidParameters() throws Exception {
        AgentToolContext.setConversationId("build-chart-hbar-invalid");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(rankTable());
        JsonNode line = MAPPER.readTree(call("bc-hbar-4", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"kind\":\"line\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\",\"orientation\":\"horizontal\"}"));
        assertEquals("error", line.get("status").asText());
        assertEquals("INVALID_PARAMETERS", line.get("code").asText());
        JsonNode bad = MAPPER.readTree(call("bc-hbar-5", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"kind\":\"bar\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\",\"orientation\":\"sideways\"}"));
        assertEquals("INVALID_PARAMETERS", bad.get("code").asText());
        String[] supplied = {"\"Horizontal\"", "\"\"", "\"  \"", "null", "1", "true", "[\"horizontal\"]"};
        for (int i = 0; i < supplied.length; i++) {
            JsonNode r = MAPPER.readTree(call("bc-hbar-s" + i, "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                    + "\",\"kind\":\"bar\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\",\"orientation\":" + supplied[i] + "}"));
            assertEquals("error", r.get("status").asText(), "supplied orientation " + supplied[i]);
            assertEquals("INVALID_PARAMETERS", r.get("code").asText(), "supplied orientation " + supplied[i]);
        }
        JsonNode lineInvalid = MAPPER.readTree(call("bc-hbar-7", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"kind\":\"line\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\",\"orientation\":\"\"}"));
        assertEquals("INVALID_PARAMETERS", lineInvalid.get("code").asText(), "a supplied invalid value on a non-bar request is still rejected");
        JsonNode timeTrend = MAPPER.readTree(call("bc-hbar-6", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                + "\",\"intent\":\"compare_groups\",\"xColumn\":\"Device\",\"yColumn\":\"Alarms\",\"orientation\":\"horizontal\"}"));
        assertEquals("CHART_EMITTED", timeTrend.get("code").asText(), "compare_groups resolves to bar here, so it applies");
        assertEquals("horizontal", timeTrend.get("orientation").asText());
    }
}
