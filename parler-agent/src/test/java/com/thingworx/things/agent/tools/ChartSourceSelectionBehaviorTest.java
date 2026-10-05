package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
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

import com.thingworx.things.agent.cache.ArtifactCacheTestFixtures;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Source-selection behavior behind the chart call guidance, through the real hook, resolver, and
 * builder rather than through description text:
 *
 * <ul>
 *   <li>an id that is not a cache handle fails with {@code CACHE_MISS} and is told to switch to
 *       {@code last_invoke}, instead of being retried;</li>
 *   <li>a cacheId the caller genuinely holds still charts that earlier table while a newer table is
 *       {@code last_invoke};</li>
 *   <li>query A → chart A → query B → chart B emits two charts in one round, the second carrying
 *       B's values;</li>
 *   <li>a failed build between two successes does not drop the chart already emitted.</li>
 * </ul>
 *
 * These are deterministic runtime facts. Whether a live model chooses these sequences is the
 * separate online acceptance step.
 */
class ChartSourceSelectionBehaviorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Two utilization states, as an extended tool would return them inline. */
    private static final String TABLE_A = "{\"status\":\"success\",\"resultKind\":\"JSON\",\"result\":"
            + "\"{\\\"status\\\":\\\"success\\\",\\\"rows\\\":["
            + "{\\\"utilizationState\\\":\\\"Down\\\",\\\"sumDurationSeconds\\\":51368},"
            + "{\\\"utilizationState\\\":\\\"Unavailable\\\",\\\"sumDurationSeconds\\\":34251}]}\"}";

    /** A later, different query in the same round. */
    private static final String TABLE_B = "{\"status\":\"success\",\"resultKind\":\"JSON\",\"result\":"
            + "\"{\\\"status\\\":\\\"success\\\",\\\"rows\\\":["
            + "{\\\"utilizationState\\\":\\\"Down\\\",\\\"sumDurationSeconds\\\":65768},"
            + "{\\\"utilizationState\\\":\\\"Unavailable\\\",\\\"sumDurationSeconds\\\":19852},"
            + "{\\\"utilizationState\\\":\\\"Running\\\",\\\"sumDurationSeconds\\\":780}]}\"}";

    ChartSourceSelectionBehaviorTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static void startRound(String conversationId) {
        AgentToolContext.setConversationId(conversationId);
        AgentToolContext.resetTabularChartRound();
        AgentToolContext.drainPendingParlerChartBlocks();
    }

    private static JsonNode chart(String callId, String args) throws Exception {
        return MAPPER.readTree(
                BuildChartFromTabularResultExecutor.execute(
                        new ToolCall(callId, "build_chart_from_tabular_result", args)));
    }

    private static String barArgs(String source, String cacheId) {
        String handle = cacheId == null ? "" : "\"cacheId\":\"" + cacheId + "\",";
        return "{\"source\":\"" + source + "\"," + handle
                + "\"kind\":\"bar\",\"xColumn\":\"utilizationState\",\"yColumn\":\"sumDurationSeconds\"}";
    }

    /** Category → value of the single series in one emitted ChartBlock. */
    private static Map<String, Double> seriesOf(JSONObject block) throws Exception {
        JsonNode chart = MAPPER.readTree(block.toString());
        JsonNode x = chart.path("series").get(0).path("x");
        JsonNode y = chart.path("series").get(0).path("y");
        assertEquals(x.size(), y.size(), chart.toString());
        Map<String, Double> values = new LinkedHashMap<>();
        for (int i = 0; i < x.size(); i++) {
            values.put(x.get(i).asText(), y.get(i).asDouble());
        }
        return values;
    }

    private static InfoTable twoColumnTable(String[] categories, double[] values) {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("utilizationState");
        fx.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fx);
        FieldDefinition fy = new FieldDefinition();
        fy.setName("sumDurationSeconds");
        fy.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fy);
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < categories.length; i++) {
            ValueCollection row = new ValueCollection();
            row.put("utilizationState", new StringPrimitive(categories[i]));
            row.put("sumDurationSeconds", new NumberPrimitive(values[i]));
            table.addRow(row);
        }
        return table;
    }

    /** (a) An evidence id is not a cache handle: CACHE_MISS, and the message names the recovery. */
    @Test
    void cacheId_thatIsNotACacheHandle_missesAndPointsAtLastInvoke() throws Exception {
        startRound("chart-source-invalid-handle");
        TabularChartRoundHooks.afterBuiltInToolResult("get_utilization_state_summary", TABLE_A);

        JsonNode result = chart("c1", barArgs("cache_id", "e1"));

        assertEquals("error", result.path("status").asText(), result.toString());
        assertEquals("CACHE_MISS", result.path("code").asText(), result.toString());
        String message = result.path("message").asText();
        assertTrue(message.contains("A cacheId only comes from a tabular result envelope"), message);
        assertTrue(message.contains("evidence ids, tool call ids and chart ids are not cache handles"), message);
        assertTrue(message.contains("use source \"last_invoke\" instead"), message);
        assertTrue(message.contains("Do not retry the same id."), message);
        assertTrue(AgentToolContext.drainPendingParlerChartBlocks().isEmpty(), "no chart on a miss");

        // The failed lookup leaves the round source intact, so the honest fallback works.
        JsonNode recovered = chart("c2", barArgs("last_invoke", null));
        assertEquals("CHART_EMITTED", recovered.path("code").asText(), recovered.toString());
        assertEquals(Map.of("Down", 51368.0, "Unavailable", 34251.0),
                seriesOf(AgentToolContext.drainPendingParlerChartBlocks().get(0)));
    }

    /** (b) A genuinely held cacheId still charts the earlier table while a newer one is last_invoke. */
    @Test
    void heldCacheId_chartsEarlierTable_whileLastInvokeIsTheNewerOne() throws Exception {
        startRound("chart-source-held-handle");
        String earlierHandle = InvokeServiceExecutor.storeInfotableInConversationCache(
                twoColumnTable(new String[] {"Down", "Unavailable"}, new double[] {51368, 34251}));

        // A newer qualifying table becomes last_invoke after that handle was issued.
        TabularChartRoundHooks.afterBuiltInToolResult("get_utilization_state_summary", TABLE_B);

        JsonNode earlier = chart("c1", barArgs("cache_id", earlierHandle));
        assertEquals("CHART_EMITTED", earlier.path("code").asText(), earlier.toString());
        assertEquals(Map.of("Down", 51368.0, "Unavailable", 34251.0),
                seriesOf(AgentToolContext.drainPendingParlerChartBlocks().get(0)));

        JsonNode latest = chart("c2", barArgs("last_invoke", null));
        assertEquals("CHART_EMITTED", latest.path("code").asText(), latest.toString());
        assertEquals(Map.of("Down", 65768.0, "Unavailable", 19852.0, "Running", 780.0),
                seriesOf(AgentToolContext.drainPendingParlerChartBlocks().get(0)));
    }

    /** (c) Query A → chart A → query B → chart B: two charts in one round, the second carrying B. */
    @Test
    void queryAChartAQueryBChartB_emitsTwoChartsWithTheirOwnValues() throws Exception {
        startRound("chart-source-two-sources");

        TabularChartRoundHooks.afterBuiltInToolResult("get_utilization_state_summary", TABLE_A);
        JsonNode chartA = chart("c1", barArgs("last_invoke", null));
        assertEquals("CHART_EMITTED", chartA.path("code").asText(), chartA.toString());
        assertEquals(2, chartA.path("pointCount").asInt());

        TabularChartRoundHooks.afterBuiltInToolResult("get_utilization_state_summary", TABLE_B);
        JsonNode chartB = chart("c2", barArgs("last_invoke", null));
        assertEquals("CHART_EMITTED", chartB.path("code").asText(), chartB.toString());
        assertEquals(3, chartB.path("pointCount").asInt());

        List<JSONObject> blocks = AgentToolContext.drainPendingParlerChartBlocks();
        assertEquals(2, blocks.size(), "one turn may emit several charts");
        assertEquals(Map.of("Down", 51368.0, "Unavailable", 34251.0), seriesOf(blocks.get(0)));
        assertEquals(Map.of("Down", 65768.0, "Unavailable", 19852.0, "Running", 780.0), seriesOf(blocks.get(1)));
        assertNotNull(chartA.path("chartId").asText(null));
        assertTrue(!chartA.path("chartId").asText().equals(chartB.path("chartId").asText()));
    }

    /** (d) A failed build between successes does not drop the chart already emitted. */
    @Test
    void failedBuildBetweenSuccesses_keepsTheAlreadyEmittedChart() throws Exception {
        startRound("chart-source-partial-failure");

        TabularChartRoundHooks.afterBuiltInToolResult("get_utilization_state_summary", TABLE_A);
        JsonNode first = chart("c1", barArgs("last_invoke", null));
        assertEquals("CHART_EMITTED", first.path("code").asText(), first.toString());

        // The model reaches for an id it never received; the build fails.
        JsonNode failed = chart("c2", barArgs("cache_id", "e2"));
        assertEquals("CACHE_MISS", failed.path("code").asText(), failed.toString());

        // The successful chart is still queued for the wire, and the round source is unchanged.
        List<JSONObject> blocks = AgentToolContext.drainPendingParlerChartBlocks();
        assertEquals(1, blocks.size(), "the emitted chart survives a later failure");
        assertEquals(Map.of("Down", 51368.0, "Unavailable", 34251.0), seriesOf(blocks.get(0)));

        JsonNode args = MAPPER.readTree(barArgs("last_invoke", null));
        String[] err = new String[1];
        TabularChartSourceResolver.Resolved resolved = TabularChartSourceResolver.resolveOrError(args, err);
        assertNull(err[0], err[0]);
        assertNotNull(resolved);
        assertEquals(2, resolved.table.getRowCount());
    }
}
