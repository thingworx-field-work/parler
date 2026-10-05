package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** C2b-1 histogram (chart-enhancement design §7.4): HG-1, HG-2 and the histogram half of IN-1. */
class ParlerTabularChartHistogramTest {

    ParlerTabularChartHistogramTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<Double> numbers(JsonNode array) {
        List<Double> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asDouble()));
        return out;
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    static InfoTable numericTable(double... values) {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("v");
        fd.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        for (double v : values) {
            ValueCollection row = new ValueCollection();
            row.put("v", new NumberPrimitive(v));
            src.addRow(row);
        }
        return src;
    }

    /** Runs bin_numeric through the real tool and returns the derived table's cache id. */
    static String binTable(String conversationId, InfoTable src, String binArgs) throws Exception {
        AgentToolContext.setConversationId(conversationId);
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("bn",
                "tabulate_cached_result", "{\"cacheId\":\"" + cid + "\",\"mode\":\"bin_numeric\",\"column\":\"v\"," + binArgs + "}")));
        assertEquals("CACHED_BIN_NUMERIC_INLINE", out.get("resultKind").asText(), out.toString());
        return out.get("cacheId").asText();
    }

    static JsonNode chart(String args) throws Exception {
        return MAPPER.readTree(BuildChartFromTabularResultExecutor.execute(
                new ToolCall("bc", "build_chart_from_tabular_result", args)));
    }

    /** A hand-built bin_numeric-shaped table so single rows can be corrupted (HG-2). */
    static InfoTable binShapedTable(double[][] rows, String method) {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (String name : ParlerTabularChartBuilder.HISTOGRAM_SOURCE_COLUMNS) {
            FieldDefinition fd = new FieldDefinition();
            fd.setName(name);
            fd.setBaseType("method".equals(name) ? BaseTypes.STRING : BaseTypes.NUMBER);
            shape.addFieldDefinition(fd);
        }
        InfoTable t = new InfoTable(shape);
        for (double[] r : rows) {
            ValueCollection row = new ValueCollection();
            String[] names = {"binIndex", "binStart", "binEnd", "count", "density", "validCount", "excludedCount",
                    "belowRangeCount", "aboveRangeCount"};
            for (int i = 0; i < names.length; i++) {
                row.put(names[i], new NumberPrimitive(r[i]));
            }
            row.put("method", new StringPrimitive(method));
            t.addRow(row);
        }
        return t;
    }

    @Test
    void hg1_edgesCountsAndDensitiesMirrorTheOperatorTable_modeOnlyChangesMode() throws Exception {
        String bn1 = binTable("hg1-a", numericTable(0, 1, 1, 2, 2, 2, 3), "\"binEdges\":[0,1,2,3]");
        JsonNode count = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + bn1 + "\",\"kind\":\"histogram\",\"title\":\"Temp\",\"xLabel\":\"°C\",\"yLabel\":\"Count\"}");
        assertEquals("success", count.get("status").asText(), count.toString());
        assertEquals("CHART_EMITTED", count.get("code").asText());
        assertEquals("histogram", count.get("kind").asText());
        assertEquals("count", count.get("histogramMode").asText());
        JsonNode block = count.get("chartBlock");
        assertEquals("histogram", block.get("kind").asText());
        assertNull(block.get("series"), "a histogram block carries no series");
        JsonNode h = block.get("histogram");
        assertEquals(List.of(0.0, 1.0, 2.0, 3.0), numbers(h.get("edges")));
        assertEquals(List.of(1, 2, 4), MAPPER.convertValue(h.get("counts"), List.class));
        assertEquals(7, h.get("validCount").asInt());
        assertEquals(0, h.get("excludedCount").asInt());
        assertEquals("explicit_edges_v1", h.get("method").asText());
        assertEquals("count", h.get("mode").asText());
        assertEquals(3, block.get("source").get("pointCount").asInt(), "pointCount is the bin count");
        assertEquals("histogram(bin_numeric)", block.get("source").get("transformSummary").asText());
        assertEquals(bn1, block.get("source").get("sourceCacheId").asText());
        assertEquals(0, count.get("seriesCount").asInt());
        String bn2 = binTable("hg1-b", numericTable(0.5, 0.7, 1.5, 2, 2.5, 4, 6, 8, 9, 9.9), "\"binEdges\":[0,1,3,10]");
        JsonNode density = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + bn2 + "\",\"kind\":\"histogram\",\"histogramMode\":\"density\"}");
        assertEquals("success", density.get("status").asText(), density.toString());
        JsonNode hd = density.get("chartBlock").get("histogram");
        assertEquals("density", hd.get("mode").asText(), "histogramMode only changes mode");
        assertEquals(List.of(0.0, 1.0, 3.0, 10.0), numbers(hd.get("edges")));
        double integral = 0;
        for (int i = 0; i < 3; i++) {
            integral += hd.get("densities").get(i).asDouble() * (hd.get("edges").get(i + 1).asDouble() - hd.get("edges").get(i).asDouble());
        }
        assertEquals(1.0, integral, 1e-12);
        assertEquals(10, hd.get("counts").get(0).asInt() + hd.get("counts").get(1).asInt() + hd.get("counts").get(2).asInt());
    }

    @Test
    void hg2_sourceShapeViolationsAreRejectedWithTheBinNumericHint_andBindingsAreInvalidParameters() throws Exception {
        String[] hints = new String[1];
        // A plain table is not a histogram source.
        AgentToolContext.setConversationId("hg2-plain");
        String plain = InvokeServiceExecutor.storeInfotableInConversationCache(numericTable(1, 2, 3));
        JsonNode r0 = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + plain + "\",\"kind\":\"histogram\"}");
        assertEquals("SOURCE_SHAPE_MISMATCH", r0.get("code").asText(), r0.toString());
        assertTrue(r0.get("recoveryHint").asText().contains("bin_numeric"));
        // Shape-legal but statistically contradictory tables.
        double[][] good = {{0, 0, 1, 1, 0.5, 2, 0, 0, 0}, {1, 1, 2, 1, 0.5, 2, 0, 0, 0}};
        assertNotNull(ParlerTabularChartBuilder.buildHistogramChartBlock(binShapedTable(good, "explicit_edges_v1"), null, null, null, null));
        double[][] brokenEnd = {{0, 0, 1.5, 1, 0.5, 2, 0, 0, 0}, {1, 1, 2, 1, 0.5, 2, 0, 0, 0}};
        double[][] sumMismatch = {{0, 0, 1, 1, 0.5, 3, 0, 0, 0}, {1, 1, 2, 1, 0.5, 3, 0, 0, 0}};
        double[][] density100 = {{0, 0, 1, 1, 100, 2, 0, 0, 0}, {1, 1, 2, 1, 100, 2, 0, 0, 0}};
        double[][] zeroTotal = {{0, 0, 1, 0, 0, 0, 0, 0, 0}, {1, 1, 2, 0, 0, 0, 0, 0, 0}};
        double[][] negativeCount = {{0, 0, 1, -1, 0.5, 0, 0, 0, 0}, {1, 1, 2, 1, 0.5, 0, 0, 0, 0}};
        double[][] fractionalCount = {{0, 0, 1, 0.5, 0.25, 2, 0, 0, 0}, {1, 1, 2, 1.5, 0.75, 2, 0, 0, 0}};
        double[][] scalarsDiffer = {{0, 0, 1, 1, 0.5, 2, 0, 0, 0}, {1, 1, 2, 1, 0.5, 2, 1, 0, 0}};
        Object[][] cases = {{"corrupted binEnd", brokenEnd, "explicit_edges_v1"}, {"Σcount mismatch", sumMismatch, "explicit_edges_v1"},
                {"density all 100", density100, "explicit_edges_v1"}, {"Σcount = 0", zeroTotal, "explicit_edges_v1"},
                {"negative count", negativeCount, "explicit_edges_v1"}, {"fractional count", fractionalCount, "explicit_edges_v1"},
                {"scalars differ", scalarsDiffer, "explicit_edges_v1"}, {"unregistered method", good, "sturges_v9"}};
        for (Object[] c : cases) {
            BuildException e = assertThrows(BuildException.class,
                    () -> ParlerTabularChartBuilder.buildHistogramChartBlock(binShapedTable((double[][]) c[1], (String) c[2]), "count", null, null, null),
                    (String) c[0]);
            assertEquals("SOURCE_SHAPE_MISMATCH", e.code, (String) c[0]);
        }
        BuildException zeroDensityOk = null;
        double[][] emptyBin = {{0, 0, 1, 0, 0, 2, 0, 0, 0}, {1, 1, 2, 2, 1, 2, 0, 0, 0}};
        assertNotNull(ParlerTabularChartBuilder.buildHistogramChartBlock(binShapedTable(emptyBin, "equal_width_v1"), "count", null, null, null),
                "an empty bin with density exactly 0 is legal (no relative error against zero)");
        assertNull(zeroDensityOk);
        // Bindings and the bar/pie parameters are INVALID_PARAMETERS on the histogram path.
        String bn = binTable("hg2-bind", numericTable(1, 2, 3), "\"binCount\":2");
        for (String extra : new String[] {"\"xColumn\":\"binStart\"", "\"yColumn\":\"count\"", "\"seriesColumn\":\"method\"",
                "\"series\":[{\"name\":\"a\",\"yColumn\":\"count\"}]", "\"yReferenceLines\":[{\"y\":1}]", "\"orientation\":\"horizontal\"",
                "\"pieSliceMode\":\"all_nonzero\"", "\"histogramMode\":\"Density\"", "\"histogramMode\":\"\""}) {
            JsonNode r = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + bn + "\",\"kind\":\"histogram\"," + extra + "}");
            assertEquals("INVALID_PARAMETERS", r.get("code").asText(), extra + ": " + r);
        }
        JsonNode modeOnBar = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + plain + "\",\"kind\":\"bar\",\"xColumn\":\"v\",\"yColumn\":\"v\",\"histogramMode\":\"count\"}");
        assertEquals("INVALID_PARAMETERS", modeOnBar.get("code").asText(), "histogramMode is histogram-only");
        assertNull(hints[0]);
    }

    @Test
    void in1_distributionIntentRoutesByShape_histogramOrRequiresBinnedSource() throws Exception {
        String bn = binTable("in1-bin", numericTable(1, 2, 3, 4, 5, 6), "\"binEdges\":[0,3,6]");
        JsonNode hist = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + bn + "\",\"intent\":\"distribution\"}");
        assertEquals("CHART_EMITTED", hist.get("code").asText(), hist.toString());
        assertEquals("histogram", hist.get("selectedKind").asText());
        assertEquals("distribution", hist.get("requestedIntent").asText());
        assertFalse(hist.get("fallback").asBoolean());
        AgentToolContext.setConversationId("in1-plain");
        String plain = InvokeServiceExecutor.storeInfotableInConversationCache(numericTable(1, 2, 3));
        JsonNode err = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + plain + "\",\"intent\":\"distribution\"}");
        assertEquals("error", err.get("status").asText(), err.toString());
        assertEquals("DISTRIBUTION_REQUIRES_BINNED_SOURCE", err.get("code").asText());
        assertTrue(err.get("recoveryHint").asText().contains("bin_numeric"));
        assertTrue(err.get("recoveryHint").asText().contains("box_summary"));
        // The resolver itself routes a one-bin table (a constant column) to histogram without the two-row guard.
        double[][] oneBin = {{0, 4.5, 5.5, 3, 1, 3, 0, 0, 0}};
        ParlerTabularChartIntentResolver.IntentOutcome o = ParlerTabularChartIntentResolver.resolve("distribution",
                binShapedTable(oneBin, "equal_width_v1"), null, null, null);
        assertFalse(o.fallback);
        assertEquals("histogram", o.kind);
        ParlerTabularChartIntentResolver.IntentOutcome bad = ParlerTabularChartIntentResolver.resolve("distribution",
                numericTable(1, 2, 3), null, null, null);
        assertEquals("DISTRIBUTION_REQUIRES_BINNED_SOURCE", bad.errorCode);
        assertFalse(bad.fallback, "a structured error, not a phase fallback");
    }

    @Test
    void historyReplayKeepsAHistogramBlockWithoutSeries() {
        String emitted = "{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"chartBlock\":{\"kind\":\"histogram\",\"chartId\":\"c1\","
                + "\"histogram\":{\"edges\":[0,1],\"counts\":[2],\"densities\":[2.0],\"mode\":\"count\",\"validCount\":2,"
                + "\"excludedCount\":0,\"belowRangeCount\":0,\"aboveRangeCount\":0,\"method\":\"explicit_edges_v1\"}}}";
        JSONObject replayed = ParlerChartWireSupport.chartBlockFromChartEmittedToolResult(emitted, false).orElse(null);
        assertNotNull(replayed, "a kind-specific payload replays like series[]");
        assertEquals("histogram", replayed.getString("kind"));
        assertEquals("c1", replayed.getString("chartId"), "a chart-group member resolves its chart by this id on replay");
        String noPayload = "{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"chartBlock\":{\"kind\":\"histogram\"}}";
        assertTrue(ParlerChartWireSupport.chartBlockFromChartEmittedToolResult(noPayload, false).isEmpty());
    }
}
