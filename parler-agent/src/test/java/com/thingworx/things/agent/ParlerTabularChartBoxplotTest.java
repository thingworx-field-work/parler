package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import com.thingworx.things.agent.ParlerTabularChartBuilder.BuildException;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.BuildChartFromTabularResultExecutor;
import com.thingworx.things.agent.tools.CachedTabularToolsExecutor;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;

/** C2b-2 boxplot (chart-enhancement design §7.4): BP-0, BP-1 and the boxplot half of IN-1. */
class ParlerTabularChartBoxplotTest {

    ParlerTabularChartBoxplotTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static InfoTable groupedTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition g = new FieldDefinition();
        g.setName("g");
        g.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(g);
        FieldDefinition v = new FieldDefinition();
        v.setName("v");
        v.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(v);
        return new InfoTable(shape);
    }

    private static void add(InfoTable t, String g, double v) {
        ValueCollection row = new ValueCollection();
        row.put("g", new StringPrimitive(g));
        row.put("v", new NumberPrimitive(v));
        t.addRow(row);
    }

    /** Runs box_summary through the real tool and returns the tool envelope. */
    private static JsonNode boxSummary(String conversationId, InfoTable src, String extraArgs) throws Exception {
        AgentToolContext.setConversationId(conversationId);
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("bx",
                "tabulate_cached_result", "{\"cacheId\":\"" + cid + "\",\"mode\":\"box_summary\",\"column\":\"v\"" + extraArgs + "}")));
        assertEquals("CACHED_BOX_SUMMARY_INLINE", out.get("resultKind").asText(), out.toString());
        return out;
    }

    private static JsonNode chart(String args) throws Exception {
        return MAPPER.readTree(BuildChartFromTabularResultExecutor.execute(
                new ToolCall("bc", "build_chart_from_tabular_result", args)));
    }

    /** One hand-built box_summary-shaped row: key, n, excluded, min, q1, median, q3, max, wLo, wHi, outlierCount, outliers JSON. */
    private static Object[] row(String key, double n, double excluded, double min, double q1, double median, double q3,
            double max, double wLo, double wHi, double outlierCount, String outliers) {
        return new Object[] {key, n, excluded, min, q1, median, q3, max, wLo, wHi, outlierCount, outliers};
    }

    private static InfoTable boxShapedTable(String method, Object[]... rows) {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (String name : ParlerTabularChartBuilder.BOXPLOT_SOURCE_COLUMNS) {
            FieldDefinition fd = new FieldDefinition();
            fd.setName(name);
            boolean text = "groupKey".equals(name) || "outliers".equals(name) || "method".equals(name);
            fd.setBaseType(text ? BaseTypes.STRING : BaseTypes.NUMBER);
            shape.addFieldDefinition(fd);
        }
        InfoTable t = new InfoTable(shape);
        String[] names = {"groupKey", "n", "excludedCount", "min", "q1", "median", "q3", "max", "whiskerLow",
                "whiskerHigh", "outlierCount", "outliers"};
        for (Object[] r : rows) {
            ValueCollection row = new ValueCollection();
            for (int i = 0; i < names.length; i++) {
                row.put(names[i], r[i] instanceof String ? new StringPrimitive((String) r[i]) : new NumberPrimitive((Double) r[i]));
            }
            row.put("method", new StringPrimitive(method));
            t.addRow(row);
        }
        return t;
    }

    private static final String METHOD = "tukey_1_5_iqr_linear_p_v1";

    @Test
    void bp1_boxplotCarriesTheOperatorStatisticsAndTheReferenceLine() throws Exception {
        // BX-1 odd sample 1..9 (no outliers) and a group with 30 outliers listed as 20 (BX-3).
        InfoTable src = groupedTable();
        for (int i = 1; i <= 9; i++) {
            add(src, "Oven-01", i);
        }
        for (int i = 0; i < 100; i++) {
            add(src, "Oven-02", 60 + (i % 10));
        }
        for (int i = 0; i < 30; i++) {
            add(src, "Oven-02", 200 + i);
        }
        JsonNode bx = boxSummary("bp1", src, ",\"groupBy\":\"g\"");
        String cacheId = bx.get("cacheId").asText();
        JsonNode out = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + cacheId + "\",\"kind\":\"boxplot\",\"title\":\"Temp\","
                + "\"xLabel\":\"Device\",\"yLabel\":\"°C\",\"yReferenceLines\":[{\"y\":70,\"label\":\"USL\",\"role\":\"usl\"}]}");
        assertEquals("success", out.get("status").asText(), out.toString());
        assertEquals("CHART_EMITTED", out.get("code").asText());
        assertEquals("boxplot", out.get("kind").asText());
        assertEquals(2, out.get("groupCount").asInt());
        assertEquals(0, out.get("seriesCount").asInt());
        assertNull(out.get("histogramMode"));
        JsonNode block = out.get("chartBlock");
        assertEquals("boxplot", block.get("kind").asText());
        assertNull(block.get("series"));
        assertNull(block.get("histogram"));
        assertEquals("Device", block.get("x_label").asText());
        JsonNode box = block.get("boxplot");
        assertEquals(METHOD, box.get("method").asText());
        JsonNode groups = box.get("groups");
        assertEquals(2, groups.size());
        // Every emitted statistic equals the operator row, value for value.
        for (int i = 0; i < 2; i++) {
            JsonNode g = groups.get(i);
            JsonNode r = bx.get("rows").get(i);
            assertEquals(r.get("groupKey").asText(), g.get("key").asText());
            for (String k : new String[] {"n", "excludedCount", "min", "q1", "median", "q3", "max", "whiskerLow", "whiskerHigh", "outlierCount"}) {
                assertEquals(r.get(k).asDouble(), g.get(k).asDouble(), 0.0, k);
            }
            JsonNode listed = MAPPER.readTree(r.get("outliers").asText());
            assertEquals(listed.size(), g.get("outliers").size(), "outliers list length");
            for (int k = 0; k < listed.size(); k++) {
                assertEquals(listed.get(k).asDouble(), g.get("outliers").get(k).asDouble(), 0.0, "outlier " + k);
            }
        }
        JsonNode first = groups.get(0);
        assertEquals("Oven-01", first.get("key").asText());
        assertEquals(9, first.get("n").asInt());
        assertEquals(3.0, first.get("q1").asDouble(), 0.0);
        assertEquals(5.0, first.get("median").asDouble(), 0.0);
        assertEquals(7.0, first.get("q3").asDouble(), 0.0);
        assertEquals(1.0, first.get("whiskerLow").asDouble(), 0.0);
        assertEquals(9.0, first.get("whiskerHigh").asDouble(), 0.0);
        assertEquals(0, first.get("outliers").size());
        JsonNode second = groups.get(1);
        assertEquals(30, second.get("outlierCount").asInt());
        assertEquals(20, second.get("outliers").size(), "at most 20 listed");
        assertEquals(229.0, second.get("outliers").get(0).asDouble(), 0.0, "farthest from the fence first");
        assertEquals(1, block.get("y_reference_lines").size());
        assertEquals(70.0, block.get("y_reference_lines").get(0).get("y").asDouble(), 0.0);
        assertEquals("usl", block.get("y_reference_lines").get(0).get("role").asText());
        assertEquals(2, block.get("source").get("pointCount").asInt(), "pointCount is the group count");
        assertEquals("boxplot(box_summary)", block.get("source").get("transformSummary").asText());
        assertEquals(cacheId, block.get("source").get("sourceCacheId").asText());
        assertEquals("boxplot(box_summary)", out.get("transformSummary").asText());
        // Without reference lines the block carries none (the last_invoke source link is proven by SC-1).
        JsonNode plain = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + cacheId + "\",\"kind\":\"boxplot\"}");
        assertEquals("CHART_EMITTED", plain.get("code").asText(), plain.toString());
        assertNull(plain.get("chartBlock").get("y_reference_lines"));
    }

    @Test
    void bp0_sourceShapeViolationsAreRejectedWithTheBoxSummaryHint_andParametersAreInvalidParameters() throws Exception {
        AgentToolContext.setConversationId("bp0-plain");
        InfoTable plainSrc = groupedTable();
        add(plainSrc, "a", 1);
        add(plainSrc, "a", 2);
        String plain = InvokeServiceExecutor.storeInfotableInConversationCache(plainSrc);
        JsonNode r0 = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + plain + "\",\"kind\":\"boxplot\"}");
        assertEquals("SOURCE_SHAPE_MISMATCH", r0.get("code").asText(), r0.toString());
        assertTrue(r0.get("recoveryHint").asText().contains("box_summary"));
        assertFalse(r0.get("recoveryHint").asText().contains("bin_numeric"));
        Object[] good = row("A", 9, 1, 1, 3, 5, 7, 9, 2, 8, 2, "[1,9]");
        assertNotNull(ParlerTabularChartBuilder.buildBoxplotChartBlock(boxShapedTable(METHOD, good), null, null, null, null));
        Object[][] cases = {
                {"outlierCount > n", row("A", 9, 1, 1, 3, 5, 7, 9, 2, 8, 10, "[1,9,1,9,1,9,1,9,1,9]"), METHOD},
                {"outliers length ≠ min(outlierCount, 20)", row("A", 9, 1, 1, 3, 5, 7, 9, 2, 8, 2, "[1]"), METHOD},
                {"outlier inside the whiskers", row("A", 9, 1, 1, 3, 5, 7, 9, 2, 8, 2, "[1,5]"), METHOD},
                {"outlier above max", row("A", 9, 1, 1, 3, 5, 7, 9, 2, 8, 2, "[1,10]"), METHOD},
                {"outlierCount = 0 but whiskerHigh < max", row("A", 9, 1, 1, 3, 5, 7, 9, 1, 8, 0, "[]"), METHOD},
                {"order broken (q1 > median)", row("A", 9, 1, 1, 6, 5, 7, 9, 2, 8, 2, "[1,9]"), METHOD},
                {"n = 0", row("A", 0, 1, 1, 3, 5, 7, 9, 1, 9, 0, "[]"), METHOD},
                {"fractional n", row("A", 9.5, 1, 1, 3, 5, 7, 9, 2, 8, 2, "[1,9]"), METHOD},
                {"outliers not JSON", row("A", 9, 1, 1, 3, 5, 7, 9, 2, 8, 2, "1;9"), METHOD},
                {"empty groupKey", row("", 9, 1, 1, 3, 5, 7, 9, 2, 8, 2, "[1,9]"), METHOD},
                {"unregistered method", good, "tukey_3_iqr_v1"},
        };
        for (Object[] c : cases) {
            BuildException e = assertThrows(BuildException.class,
                    () -> ParlerTabularChartBuilder.buildBoxplotChartBlock(boxShapedTable((String) c[2], (Object[]) c[1]), null, null, null, null),
                    (String) c[0]);
            assertEquals("SOURCE_SHAPE_MISMATCH", e.code, (String) c[0]);
        }
        BuildException dup = assertThrows(BuildException.class, () -> ParlerTabularChartBuilder.buildBoxplotChartBlock(
                boxShapedTable(METHOD, good, good), null, null, null, null));
        assertEquals("SOURCE_SHAPE_MISMATCH", dup.code, "duplicate groupKey");
        List<Object[]> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(row("G" + i, 9, 1, 1, 3, 5, 7, 9, 2, 8, 2, "[1,9]"));
        }
        BuildException tooMany = assertThrows(BuildException.class, () -> ParlerTabularChartBuilder.buildBoxplotChartBlock(
                boxShapedTable(METHOD, many.toArray(new Object[0][])), null, null, null, null));
        assertEquals("SOURCE_SHAPE_MISMATCH", tooMany.code, "25 groups");
        // Degenerate but legal: n = 1 with seven equal statistics; 30 outliers listed as 20.
        assertNotNull(ParlerTabularChartBuilder.buildBoxplotChartBlock(boxShapedTable(METHOD,
                row("A", 1, 0, 4, 4, 4, 4, 4, 4, 4, 0, "[]")), null, null, null, null));
        StringBuilder twenty = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            twenty.append(i > 0 ? "," : "").append(100 + i);
        }
        assertNotNull(ParlerTabularChartBuilder.buildBoxplotChartBlock(boxShapedTable(METHOD,
                row("A", 200, 0, 1, 3, 5, 7, 119, 2, 8, 30, twenty.append("]").toString())), null, null, null, null));
        // Bindings, histogramMode and the bar/pie parameters are INVALID_PARAMETERS on the boxplot path.
        InfoTable src = groupedTable();
        for (int i = 1; i <= 5; i++) {
            add(src, "a", i);
        }
        String bx = boxSummary("bp0-bind", src, "").get("cacheId").asText();
        for (String extra : new String[] {"\"xColumn\":\"groupKey\"", "\"yColumn\":\"median\"", "\"seriesColumn\":\"method\"",
                "\"series\":[{\"name\":\"a\",\"yColumn\":\"median\"}]", "\"histogramMode\":\"count\"", "\"orientation\":\"horizontal\"",
                "\"pieSliceMode\":\"all_nonzero\"", "\"requestedTimeRange\":{\"start\":\"2026-01-01T00:00:00Z\",\"end\":\"2026-01-02T00:00:00Z\"}"}) {
            JsonNode r = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + bx + "\",\"kind\":\"boxplot\"," + extra + "}");
            assertEquals("INVALID_PARAMETERS", r.get("code").asText(), extra + ": " + r);
        }
        StringBuilder refs = new StringBuilder("[");
        for (int i = 0; i < 13; i++) {
            refs.append(i > 0 ? "," : "").append("{\"y\":").append(i).append("}");
        }
        JsonNode tooManyRefs = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + bx + "\",\"kind\":\"boxplot\",\"yReferenceLines\":" + refs + "]}");
        assertEquals("TOO_MANY_REFERENCE_LINES", tooManyRefs.get("code").asText());
        // A box table under kind histogram (and vice versa) is a shape mismatch with the matching hint.
        JsonNode crossed = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + bx + "\",\"kind\":\"histogram\"}");
        assertEquals("SOURCE_SHAPE_MISMATCH", crossed.get("code").asText());
        assertTrue(crossed.get("recoveryHint").asText().contains("bin_numeric"));
    }

    @Test
    void in1_distributionIntentRoutesABoxSourceToBoxplot() throws Exception {
        InfoTable src = groupedTable();
        for (int i = 1; i <= 7; i++) {
            add(src, "a", i);
        }
        String bx = boxSummary("in1-box", src, "").get("cacheId").asText();
        JsonNode out = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + bx + "\",\"intent\":\"distribution\",\"yReferenceLines\":[{\"y\":6}]}");
        assertEquals("CHART_EMITTED", out.get("code").asText(), out.toString());
        assertEquals("boxplot", out.get("selectedKind").asText());
        assertEquals("distribution", out.get("requestedIntent").asText());
        assertFalse(out.get("fallback").asBoolean());
        assertEquals("All", out.get("chartBlock").get("boxplot").get("groups").get(0).get("key").asText());
        assertEquals(1, out.get("chartBlock").get("y_reference_lines").size());
        JsonNode modeOnBox = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + bx + "\",\"intent\":\"distribution\",\"histogramMode\":\"count\"}");
        assertEquals("INVALID_PARAMETERS", modeOnBox.get("code").asText(), "histogramMode on an intent-resolved boxplot");
        ParlerTabularChartIntentResolver.IntentOutcome o = ParlerTabularChartIntentResolver.resolve("distribution",
                boxShapedTable(METHOD, row("A", 1, 0, 4, 4, 4, 4, 4, 4, 4, 0, "[]")), null, null, null);
        assertFalse(o.fallback);
        assertEquals("boxplot", o.kind);
    }

    @Test
    void historyReplayKeepsABoxplotBlockWithoutSeries() {
        String emitted = "{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"chartBlock\":{\"kind\":\"boxplot\",\"chartId\":\"c1\","
                + "\"boxplot\":{\"method\":\"" + METHOD + "\",\"groups\":[{\"key\":\"A\",\"n\":1,\"excludedCount\":0,\"min\":4,"
                + "\"whiskerLow\":4,\"q1\":4,\"median\":4,\"q3\":4,\"whiskerHigh\":4,\"max\":4,\"outliers\":[],\"outlierCount\":0}]}}}";
        JSONObject replayed = ParlerChartWireSupport.chartBlockFromChartEmittedToolResult(emitted, false).orElse(null);
        assertNotNull(replayed);
        assertEquals("boxplot", replayed.getString("kind"));
        assertEquals("c1", replayed.getString("chartId"), "a chart-group member resolves its chart by this id on replay");
        String noPayload = "{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"chartBlock\":{\"kind\":\"boxplot\"}}";
        assertTrue(ParlerChartWireSupport.chartBlockFromChartEmittedToolResult(noPayload, false).isEmpty());
    }
}
