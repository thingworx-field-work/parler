package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * TR-2 E2E: real cached tables → {@code union_rows} → {@link TabularChartRoundHooks} →
 * {@code build_chart_from_tabular_result} on {@code last_invoke}.
 */
class TabularChartUnionRowsBarTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    TabularChartUnionRowsBarTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static InfoTable dayTable(String state, double hours) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition stateFd = new FieldDefinition();
        stateFd.setName("state");
        stateFd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(stateFd);
        FieldDefinition hoursFd = new FieldDefinition();
        hoursFd.setName("hours");
        hoursFd.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(hoursFd);
        InfoTable it = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("state", new StringPrimitive(state));
        row.put("hours", new NumberPrimitive(hours));
        it.addRow(row);
        return it;
    }

    @Test
    void unionRowsThroughHook_lastInvoke_emitsBarChart() throws Exception {
        AgentToolContext.setConversationId("tr2-union-e2e");
        AgentToolContext.resetTabularChartRound();
        AgentToolContext.drainPendingParlerChartBlocks();

        String cidA = InvokeServiceExecutor.storeInfotableInConversationCache(dayTable("Down", 4.0));
        String cidB = InvokeServiceExecutor.storeInfotableInConversationCache(dayTable("Running", 6.0));
        String args = "{\"mode\":\"union_rows\",\"sourceCacheIds\":[\"" + cidA + "\",\"" + cidB + "\"],"
                + "\"labelColumn\":\"day\",\"labelValues\":[\"2026-09-01\",\"2026-09-02\"]}";
        String body = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("union", "tabulate_cached_result", args));
        JsonNode unionOut = MAPPER.readTree(body);
        assertEquals("success", unionOut.path("status").asText(), body);
        assertEquals(2, unionOut.path("totalRows").asInt(), body);
        assertTrue(unionOut.path("unionMeta").path("unionRowsNotDeduped").asBoolean(), body);

        TabularChartRoundHooks.afterBuiltInToolResult("tabulate_cached_result", body);

        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(1, st.getQualifyingTabularSuccessCount());
        assertTrue(st.isLastChartRescueDataCompleteEnough());

        String chartJson = BuildChartFromTabularResultExecutor.execute(
                new ToolCall("bc-union", "build_chart_from_tabular_result",
                        "{\"source\":\"last_invoke\",\"kind\":\"bar\",\"xColumn\":\"day\",\"yColumn\":\"hours\"}"));
        JsonNode chartRoot = MAPPER.readTree(chartJson);
        assertEquals("success", chartRoot.path("status").asText(), chartJson);
        assertEquals("CHART_EMITTED", chartRoot.path("code").asText(), chartJson);
        assertEquals(2, chartRoot.path("pointCount").asInt(), chartJson);
        assertTrue(chartRoot.path("sourceResolved").asText().contains("last_invoke"), chartJson);

        java.util.List<JSONObject> blocks = AgentToolContext.drainPendingParlerChartBlocks();
        assertEquals(1, blocks.size());
        JsonNode chart = MAPPER.readTree(blocks.get(0).toString());
        assertEquals("bar", chart.path("kind").asText());
        JsonNode x = chart.path("series").get(0).path("x");
        JsonNode y = chart.path("series").get(0).path("y");
        assertEquals(2, x.size());
        assertEquals(2, y.size());
        assertEquals("2026-09-01", x.get(0).asText());
        assertEquals(4.0, y.get(0).asDouble(), 0.0);
        assertEquals("2026-09-02", x.get(1).asText());
        assertEquals(6.0, y.get(1).asDouble(), 0.0);

        String[] err = new String[1];
        TabularChartSourceResolver.Resolved viaLast = TabularChartSourceResolver.resolveOrError(
                MAPPER.readTree("{\"source\":\"last_invoke\",\"xColumn\":\"day\",\"yColumn\":\"hours\",\"kind\":\"bar\"}"),
                err);
        assertNotNull(viaLast, err[0]);
        assertEquals(2, viaLast.table.getRowCount());
    }
}
