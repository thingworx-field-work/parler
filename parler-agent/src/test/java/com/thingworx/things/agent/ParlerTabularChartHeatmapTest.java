package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** C2b-3 heatmap (chart-enhancement design §7.4): HM-1 and HM-3 on the server side, plus history replay. */
class ParlerTabularChartHeatmapTest {

    ParlerTabularChartHeatmapTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    /** A long table of (device, hour, value) readings. */
    private static InfoTable readings() {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (String[] f : new String[][] {{"device", "STRING"}, {"hour", "STRING"}, {"v", "NUMBER"}}) {
            FieldDefinition fd = new FieldDefinition();
            fd.setName(f[0]);
            fd.setBaseType("STRING".equals(f[1]) ? BaseTypes.STRING : BaseTypes.NUMBER);
            shape.addFieldDefinition(fd);
        }
        return new InfoTable(shape);
    }

    private static void add(InfoTable t, String device, String hour, Double v) {
        ValueCollection row = new ValueCollection();
        row.put("device", new StringPrimitive(device));
        row.put("hour", new StringPrimitive(hour));
        row.put("v", v == null ? null : new NumberPrimitive(v));
        t.addRow(row);
    }

    private static String store(String conversationId, InfoTable t) throws Exception {
        AgentToolContext.setConversationId(conversationId);
        return InvokeServiceExecutor.storeInfotableInConversationCache(t);
    }

    private static JsonNode chart(String args) throws Exception {
        return MAPPER.readTree(BuildChartFromTabularResultExecutor.execute(
                new ToolCall("bc", "build_chart_from_tabular_result", args)));
    }

    @Test
    void hm1_twoKeyGroupMetricBecomesAMatrixWithAnExplicitNullForTheMissingCombination() throws Exception {
        InfoTable src = readings();
        add(src, "Oven-01", "00", 63.0);
        add(src, "Oven-01", "00", 63.2);
        add(src, "Oven-01", "01", 63.4);
        add(src, "Oven-02", "00", 61.0);
        add(src, "Oven-02", "01", 60.8);
        add(src, "Oven-02", "02", 61.2);
        String cid = store("hm1", src);
        JsonNode gm = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("gm",
                "tabulate_cached_result", "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"device\",\"hour\"],"
                        + "\"measures\":[{\"name\":\"avg\",\"op\":\"avg\",\"column\":\"v\"}]}")));
        assertEquals("success", gm.get("status").asText(), gm.toString());
        String gmCache = gm.get("cacheId").asText();
        JsonNode out = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + gmCache + "\",\"kind\":\"heatmap\",\"xColumn\":\"hour\","
                + "\"seriesColumn\":\"device\",\"yColumn\":\"avg\",\"title\":\"Temp\",\"xLabel\":\"Hour\",\"yLabel\":\"Avg temperature (°C)\"}");
        assertEquals("success", out.get("status").asText(), out.toString());
        assertEquals("CHART_EMITTED", out.get("code").asText());
        assertEquals("heatmap", out.get("kind").asText());
        assertEquals(0, out.get("seriesCount").asInt());
        assertEquals(2, out.get("rowCount2d").asInt());
        assertEquals(3, out.get("colCount").asInt());
        assertEquals(1, out.get("missingCount").asInt());
        assertEquals(5, out.get("pointCount").asInt(), "pointCount is the number of cells with data");
        assertNull(out.get("filledMissingCombinations"), "nothing is zero-filled");
        JsonNode block = out.get("chartBlock");
        assertEquals("heatmap", block.get("kind").asText());
        assertNull(block.get("series"));
        JsonNode heat = block.get("heatmap");
        assertEquals("[\"Oven-01\",\"Oven-02\"]", heat.get("rows").toString(), "rows in first-appearance order");
        assertEquals("[\"00\",\"01\",\"02\"]", heat.get("cols").toString());
        assertEquals(63.1, heat.get("values").get(0).get(0).asDouble(), 1e-12);
        assertEquals(63.4, heat.get("values").get(0).get(1).asDouble(), 1e-12);
        assertTrue(heat.get("values").get(0).get(2).isNull(), "the missing combination is null");
        assertEquals(61.2, heat.get("values").get(1).get(2).asDouble(), 1e-12);
        assertEquals(1, heat.get("missingCount").asInt());
        assertEquals("Avg temperature (°C)", heat.get("valueLabel").asText());
        assertEquals("Hour", block.get("x_label").asText());
        assertEquals("Avg temperature (°C)", block.get("y_label").asText());
        assertEquals("heatmap(device × hour)", block.get("source").get("transformSummary").asText());
        assertEquals("[\"hour\",\"device\",\"avg\"]", block.get("source").get("sourceColumns").toString());
        // Without labels the value label falls back to the measure column and the axis labels to the binding names.
        JsonNode bare = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + gmCache + "\",\"kind\":\"heatmap\",\"xColumn\":\"hour\",\"seriesColumn\":\"device\",\"yColumn\":\"avg\"}");
        assertEquals("avg", bare.get("chartBlock").get("heatmap").get("valueLabel").asText());
        assertEquals("device", bare.get("chartBlock").get("y_label").asText());
    }

    @Test
    void hm3_limitsDuplicatesBindingsAndAllMissingAreRejected() throws Exception {
        InfoTable tall = readings();
        for (int r = 0; r < 25; r++) {
            add(tall, "D" + r, "00", 1.0);
        }
        String tallId = store("hm3-rows", tall);
        JsonNode rows = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + tallId + "\",\"kind\":\"heatmap\",\"xColumn\":\"hour\",\"seriesColumn\":\"device\",\"yColumn\":\"v\"}");
        assertEquals("TOO_MANY_CATEGORIES", rows.get("code").asText(), rows.toString());
        assertEquals("rows", rows.get("details").get("dimension").asText());
        InfoTable wide = readings();
        for (int c = 0; c < 49; c++) {
            add(wide, "D", "h" + c, 1.0);
        }
        String wideId = store("hm3-cols", wide);
        JsonNode cols = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + wideId + "\",\"kind\":\"heatmap\",\"xColumn\":\"hour\",\"seriesColumn\":\"device\",\"yColumn\":\"v\"}");
        assertEquals("TOO_MANY_CATEGORIES", cols.get("code").asText());
        assertEquals("cols", cols.get("details").get("dimension").asText());
        InfoTable dup = readings();
        add(dup, "D", "00", 1.0);
        add(dup, "D", "00", 2.0);
        String dupId = store("hm3-dup", dup);
        JsonNode duplicate = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + dupId + "\",\"kind\":\"heatmap\",\"xColumn\":\"hour\",\"seriesColumn\":\"device\",\"yColumn\":\"v\"}");
        assertEquals("DUPLICATE_CELL", duplicate.get("code").asText(), "no silent aggregation");
        InfoTable empty = readings();
        add(empty, "D", "00", null);
        add(empty, "E", "01", null);
        BuildException allMissing = assertThrows(BuildException.class, () -> ParlerTabularChartBuilder.buildChartBlock(
                empty, "heatmap", "hour", "v", null, null, null, null, null, null, false, "device", null, -1));
        assertEquals("HEATMAP_ALL_MISSING", allMissing.code);
        // A present row with a null value is a missing cell, not an error; 24 × 48 is the largest legal matrix.
        InfoTable max = readings();
        for (int r = 0; r < 24; r++) {
            for (int c = 0; c < 48; c++) {
                add(max, "D" + r, "h" + c, (r == 0 && c == 0) ? null : (double) (r + c));
            }
        }
        JSONObject maxBlock = ParlerTabularChartBuilder.buildChartBlock(max, "heatmap", "hour", "v", null, null, null, null,
                null, null, false, "device", null, -1);
        assertEquals(24, maxBlock.getJSONObject("heatmap").getJSONArray("rows").length());
        assertEquals(48, maxBlock.getJSONObject("heatmap").getJSONArray("cols").length());
        assertEquals(1, maxBlock.getJSONObject("heatmap").getInt("missingCount"));
        assertEquals(24 * 48 - 1, maxBlock.getJSONObject("source").getInt("pointCount"));
        // Bindings: seriesColumn and yColumn are required; reference lines, orientation and histogramMode are rejected.
        String ok = store("hm3-bind", dup);
        JsonNode noSeriesCol = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + ok + "\",\"kind\":\"heatmap\",\"xColumn\":\"hour\",\"yColumn\":\"v\"}");
        assertEquals("INVALID_MAPPING", noSeriesCol.get("code").asText());
        JsonNode seriesArray = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + ok + "\",\"kind\":\"heatmap\",\"xColumn\":\"hour\",\"seriesColumn\":\"device\",\"series\":[{\"name\":\"a\",\"yColumn\":\"v\"}]}");
        assertEquals("INVALID_MAPPING", seriesArray.get("code").asText());
        for (String extra : new String[] {"\"yReferenceLines\":[{\"y\":1}]", "\"orientation\":\"horizontal\"", "\"histogramMode\":\"count\"", "\"pieSliceMode\":\"all_nonzero\""}) {
            JsonNode r = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + ok + "\",\"kind\":\"heatmap\",\"xColumn\":\"hour\",\"seriesColumn\":\"device\",\"yColumn\":\"v\"," + extra + "}");
            assertEquals("INVALID_PARAMETERS", r.get("code").asText(), extra + ": " + r);
        }
        JsonNode textValue = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + ok + "\",\"kind\":\"heatmap\",\"xColumn\":\"hour\",\"seriesColumn\":\"device\",\"yColumn\":\"device\"}");
        assertEquals("Y_COLUMN_NOT_NUMERIC", textValue.get("code").asText());
        assertNotNull(textValue.get("message"));
        assertFalse("heatmap".equals(ParlerTabularChartIntentResolver.resolve("compare_groups", dup, "hour", "v", null).kind),
                "no intent resolves to heatmap; it is explicit only");
    }

    @Test
    void historyReplayKeepsAHeatmapBlockWithoutSeries() {
        String emitted = "{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"chartBlock\":{\"kind\":\"heatmap\",\"chartId\":\"c1\","
                + "\"heatmap\":{\"rows\":[\"A\"],\"cols\":[\"x\",\"y\"],\"values\":[[1,null]],\"valueLabel\":\"v\",\"missingCount\":1}}}";
        JSONObject replayed = ParlerChartWireSupport.chartBlockFromChartEmittedToolResult(emitted, false).orElse(null);
        assertNotNull(replayed);
        assertEquals("heatmap", replayed.getString("kind"));
        assertEquals("c1", replayed.getString("chartId"), "a chart-group member resolves its chart by this id on replay");
        assertTrue(replayed.getJSONObject("heatmap").getJSONArray("values").getJSONArray(0).isNull(1), "null cells survive replay");
        String noPayload = "{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"chartBlock\":{\"kind\":\"heatmap\"}}";
        assertTrue(ParlerChartWireSupport.chartBlockFromChartEmittedToolResult(noPayload, false).isEmpty());
    }
}
