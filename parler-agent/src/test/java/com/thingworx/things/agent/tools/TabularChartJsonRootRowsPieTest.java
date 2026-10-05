package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.thingworx.things.agent.llm.ToolCall;

/**
 * Offline end-to-end: a {@code resultKind: JSON} extended-tool result whose business object carries a root
 * {@code rows} single table is registered by {@link TabularChartRoundHooks}, resolved through
 * {@code source: last_invoke}, and rendered by the real {@code build_chart_from_tabular_result} executor as a
 * three-slice pie whose labels and values equal the original rows. Both result wrappers (serialized string and
 * object) must behave the same, and unrelated sparse or nested columns must not block a valid x/y selection.
 */
class TabularChartJsonRootRowsPieTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SUMMARY_BUSINESS = "{\"status\":\"success\",\"rows\":["
            + "{\"utilizationState\":\"Down\",\"sumDurationSeconds\":51368,\"sumDurationMinutes\":856.13,\"percentage\":59.45,\"count\":3},"
            + "{\"utilizationState\":\"Unavailable\",\"sumDurationSeconds\":34251,\"sumDurationMinutes\":570.85,\"percentage\":39.64,\"count\":1},"
            + "{\"utilizationState\":\"Running\",\"sumDurationSeconds\":780,\"sumDurationMinutes\":13,\"percentage\":0.9,\"count\":2}],"
            + "\"stats\":{\"utilizationPercent\":1.5},\"evidenceGaps\":[]}";

    /** Same three states; unrelated columns are sparse (missing/null), nested (object/array), or appear late. */
    private static final String SPARSE_NESTED_BUSINESS = "{\"status\":\"success\",\"rows\":["
            + "{\"utilizationState\":\"Down\",\"sumDurationSeconds\":51368,\"note\":\"first\",\"detail\":{\"reason\":\"maintenance\"}},"
            + "{\"utilizationState\":\"Unavailable\",\"sumDurationSeconds\":34251,\"detail\":[1,2,3]},"
            + "{\"utilizationState\":\"Running\",\"sumDurationSeconds\":780,\"note\":null,\"lateOnlyKey\":true}]}";

    private static final String PIE_ARGS = "{\"source\":\"last_invoke\",\"kind\":\"pie\",\"xColumn\":\"utilizationState\","
            + "\"yColumn\":\"sumDurationSeconds\",\"pieSliceMode\":\"all_nonzero\"}";

    TabularChartJsonRootRowsPieTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static String jsonEnvelope(String businessJson, boolean stringResult) throws Exception {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("status", "success");
        env.put("resultKind", "JSON");
        if (stringResult) {
            env.put("result", businessJson);
        } else {
            env.set("result", MAPPER.readTree(businessJson));
        }
        return MAPPER.writeValueAsString(env);
    }

    private static void assertThreeStatePieEmitted(String conversationId, String envelope) throws Exception {
        AgentToolContext.setConversationId(conversationId);
        AgentToolContext.resetTabularChartRound();
        AgentToolContext.drainPendingParlerChartBlocks();

        TabularChartRoundHooks.afterBuiltInToolResult("get_some_state_summary", envelope);

        String json = BuildChartFromTabularResultExecutor.execute(
                new ToolCall("bc-json-rows", "build_chart_from_tabular_result", PIE_ARGS));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText(), json);
        assertEquals("CHART_EMITTED", root.get("code").asText(), json);
        assertEquals(3, root.get("pointCount").asInt(), json);
        assertTrue(root.get("sourceResolved").asText().contains("last_invoke"), json);

        List<JSONObject> blocks = AgentToolContext.drainPendingParlerChartBlocks();
        assertEquals(1, blocks.size());
        JsonNode chart = MAPPER.readTree(blocks.get(0).toString());
        assertEquals("pie", chart.get("kind").asText());
        assertEquals(1, chart.get("series").size());
        JsonNode x = chart.get("series").get(0).get("x");
        JsonNode y = chart.get("series").get(0).get("y");
        assertEquals(3, x.size());
        assertEquals(3, y.size());
        Map<String, Double> slices = new LinkedHashMap<>();
        for (int i = 0; i < x.size(); i++) {
            slices.put(x.get(i).asText(), y.get(i).asDouble());
        }
        assertEquals(3, slices.size());
        assertEquals(51368.0, slices.get("Down"), 0.0);
        assertEquals(34251.0, slices.get("Unavailable"), 0.0);
        assertEquals(780.0, slices.get("Running"), 0.0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void summaryRootRows_lastInvoke_emitsThreeSlicePieWithOriginalValues(boolean stringResult) throws Exception {
        String envelope = jsonEnvelope(SUMMARY_BUSINESS, stringResult);
        JsonNode env = MAPPER.readTree(envelope);
        JsonNode business = stringResult ? MAPPER.readTree(env.get("result").asText()) : env.get("result");
        assertEquals(1.5, business.get("stats").get("utilizationPercent").asDouble(), 0.0);
        assertEquals(0, business.get("evidenceGaps").size());

        assertThreeStatePieEmitted("json-root-rows-pie-" + stringResult, envelope);
    }

    @Test
    void sparseAndNestedUnrelatedColumns_doNotBlockValidXY() throws Exception {
        assertThreeStatePieEmitted("json-root-rows-sparse-nested", jsonEnvelope(SPARSE_NESTED_BUSINESS, true));
    }
}
