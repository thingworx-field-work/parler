package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import com.thingworx.things.agent.cache.ArtifactCacheTestFixtures;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * A qualifying {@code resultKind: JSON} single table gets its own cache handle, so two such results in one
 * turn stay independently chartable. Before this, the second result overwrote {@code last_invoke} and the
 * first could only be re-queried.
 *
 * <p>The handle is additive: round state still records inline rows, so {@code last_invoke} keeps its
 * latest-wins meaning and keeps working when the cache entry is gone.
 */
class JsonChartSourceHandleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ORD-Contacting-01 style summary. */
    private static final String SUMMARY_A = envelope(
            "{\\\"status\\\":\\\"success\\\",\\\"rows\\\":["
                    + "{\\\"utilizationState\\\":\\\"Down\\\",\\\"sumDurationSeconds\\\":65768},"
                    + "{\\\"utilizationState\\\":\\\"Unavailable\\\",\\\"sumDurationSeconds\\\":19852}]}");

    /** ORD-Contacting-02 style summary: different machine, different values. */
    private static final String SUMMARY_B = envelope(
            "{\\\"status\\\":\\\"success\\\",\\\"rows\\\":["
                    + "{\\\"utilizationState\\\":\\\"Down\\\",\\\"sumDurationSeconds\\\":51368},"
                    + "{\\\"utilizationState\\\":\\\"Unavailable\\\",\\\"sumDurationSeconds\\\":34251},"
                    + "{\\\"utilizationState\\\":\\\"Running\\\",\\\"sumDurationSeconds\\\":780}]}");

    private static String envelope(String escapedBusinessJson) {
        return "{\"status\":\"success\",\"resultKind\":\"JSON\",\"result\":\"" + escapedBusinessJson + "\"}";
    }

    JsonChartSourceHandleTest() {
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

    /** Runs the hook the way dispatch does and returns the body the model would receive. */
    private static String toolResultSeenByModel(String toolName, String body) {
        return TabularChartRoundHooks.afterBuiltInToolResult(toolName, body);
    }

    private static String handleOf(String toolResult) throws Exception {
        JsonNode root = MAPPER.readTree(toolResult);
        return root.hasNonNull("cacheId") ? root.get("cacheId").asText() : null;
    }

    private static String barArgs(String source, String cacheId) {
        String handle = cacheId == null ? "" : "\"cacheId\":\"" + cacheId + "\",";
        return "{\"source\":\"" + source + "\"," + handle
                + "\"kind\":\"bar\",\"xColumn\":\"utilizationState\",\"yColumn\":\"sumDurationSeconds\"}";
    }

    private static JsonNode chart(String callId, String args) throws Exception {
        return MAPPER.readTree(BuildChartFromTabularResultExecutor.execute(
                new ToolCall(callId, "build_chart_from_tabular_result", args)));
    }

    private static Map<String, Double> seriesOf(JSONObject block) throws Exception {
        JsonNode c = MAPPER.readTree(block.toString());
        JsonNode x = c.path("series").get(0).path("x");
        JsonNode y = c.path("series").get(0).path("y");
        Map<String, Double> values = new LinkedHashMap<>();
        for (int i = 0; i < x.size(); i++) {
            values.put(x.get(i).asText(), y.get(i).asDouble());
        }
        return values;
    }

    @Test
    void twoQualifyingJsonResults_getDistinctUsableHandles_andChartIndependently() throws Exception {
        startRound("json-handle-two-sources");

        String a = toolResultSeenByModel("get_utilization_state_summary", SUMMARY_A);
        String b = toolResultSeenByModel("get_utilization_state_summary", SUMMARY_B);
        String handleA = handleOf(a);
        String handleB = handleOf(b);

        assertNotNull(handleA, a);
        assertNotNull(handleB, b);
        assertNotEquals(handleA, handleB, "each qualifying table needs its own handle");

        // Both queries first, then both charts — the order that previously forced a re-query.
        JsonNode chartA = chart("c1", barArgs("cache_id", handleA));
        JsonNode chartB = chart("c2", barArgs("cache_id", handleB));
        assertEquals("CHART_EMITTED", chartA.path("code").asText(), chartA.toString());
        assertEquals("CHART_EMITTED", chartB.path("code").asText(), chartB.toString());

        List<JSONObject> blocks = AgentToolContext.drainPendingParlerChartBlocks();
        assertEquals(2, blocks.size());
        assertEquals(Map.of("Down", 65768.0, "Unavailable", 19852.0), seriesOf(blocks.get(0)));
        assertEquals(Map.of("Down", 51368.0, "Unavailable", 34251.0, "Running", 780.0), seriesOf(blocks.get(1)));
    }

    @Test
    void lastInvokeStillMeansTheNewestTable_andStaysOnTheInlinePath() throws Exception {
        startRound("json-handle-last-invoke");

        toolResultSeenByModel("get_utilization_state_summary", SUMMARY_A);
        toolResultSeenByModel("get_utilization_state_summary", SUMMARY_B);

        // Round state keeps inline rows, not the new handle: last_invoke must not depend on the cache.
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(2, st.getQualifyingTabularSuccessCount());
        assertNull(st.getLastCacheId(), "recording a handle must not move round state onto the cache arm");
        assertNotNull(st.getLastInlineRows());
        assertEquals(3, st.getLastInlineRows().size(), "latest-wins: B is the newest table");

        JsonNode latest = chart("c1", barArgs("last_invoke", null));
        assertEquals("CHART_EMITTED", latest.path("code").asText(), latest.toString());
        assertEquals(Map.of("Down", 51368.0, "Unavailable", 34251.0, "Running", 780.0),
                seriesOf(AgentToolContext.drainPendingParlerChartBlocks().get(0)));
    }

    @Test
    void lastInvokeSurvivesCacheLoss_becauseItNeverMovedOntoTheHandle() throws Exception {
        startRound("json-handle-cache-loss");
        toolResultSeenByModel("get_utilization_state_summary", SUMMARY_A);

        // Whole cache goes away (eviction / TTL); the inline chart path must still work.
        TabularArtifactHub.clearTestState();

        JsonNode result = chart("c1", barArgs("last_invoke", null));
        assertEquals("CHART_EMITTED", result.path("code").asText(), result.toString());
        assertEquals(Map.of("Down", 65768.0, "Unavailable", 19852.0),
                seriesOf(AgentToolContext.drainPendingParlerChartBlocks().get(0)));
    }

    @Test
    void nonQualifyingJsonGetsNoHandleAndIsReturnedUnchanged() throws Exception {
        startRound("json-handle-non-qualifying");

        String errorBody = envelope("{\\\"status\\\":\\\"error\\\",\\\"rows\\\":[{\\\"a\\\":1}]}");
        String emptyBody = envelope("{\\\"status\\\":\\\"success\\\",\\\"rows\\\":[]}");
        String pagedBody = envelope("{\\\"status\\\":\\\"success\\\",\\\"hasMore\\\":true,"
                + "\\\"rows\\\":[{\\\"utilizationState\\\":\\\"Down\\\",\\\"sumDurationSeconds\\\":1}]}");

        for (String body : new String[] {errorBody, emptyBody, pagedBody}) {
            String seen = toolResultSeenByModel("get_utilization_state_summary", body);
            assertEquals(body, seen, "non-qualifying JSON must be returned byte-for-byte");
            assertNull(handleOf(seen));
        }
        assertEquals(0, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
    }

    @Test
    void nativeInfotableResultsAreUntouched() throws Exception {
        startRound("json-handle-infotable-unchanged");
        String infotable = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rows\":["
                + "{\"utilizationState\":\"Down\",\"sumDurationSeconds\":42}]}";

        String seen = toolResultSeenByModel("invoke_service", infotable);

        assertEquals(infotable, seen, "the INFOTABLE envelope must not gain a handle");
        assertEquals(1, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
    }

    @Test
    void handleIsOnTheEnvelopeOnly_businessResultIsUnchanged() throws Exception {
        startRound("json-handle-business-json-intact");

        String seen = toolResultSeenByModel("get_utilization_state_summary", SUMMARY_A);
        JsonNode augmented = MAPPER.readTree(seen);
        JsonNode original = MAPPER.readTree(SUMMARY_A);

        assertNotNull(handleOf(seen));
        assertEquals(original.get("result"), augmented.get("result"), "business JSON must be byte-identical");
        assertEquals(original.get("status"), augmented.get("status"));
        assertEquals(original.get("resultKind"), augmented.get("resultKind"));
        // The decoded business object itself never gains a cacheId key.
        JsonNode business = MAPPER.readTree(augmented.get("result").asText());
        assertFalse(business.has("cacheId"), business.toString());
    }

    @Test
    void anExistingEnvelopeCacheIdIsNeverOverwritten() throws Exception {
        startRound("json-handle-preexisting");
        String withHandle = "{\"status\":\"success\",\"resultKind\":\"JSON\",\"cacheId\":\"pre-existing\","
                + "\"result\":\"{\\\"status\\\":\\\"success\\\",\\\"rows\\\":[{\\\"utilizationState\\\":\\\"Down\\\","
                + "\\\"sumDurationSeconds\\\":7}]}\"}";

        String seen = toolResultSeenByModel("get_utilization_state_summary", withHandle);

        assertEquals("pre-existing", handleOf(seen));
    }

    @Test
    void aHandleFromAnotherConversationDoesNotResolve() throws Exception {
        startRound("json-handle-conversation-one");
        String handle = handleOf(toolResultSeenByModel("get_utilization_state_summary", SUMMARY_A));
        assertNotNull(handle);

        startRound("json-handle-conversation-two");
        JsonNode result = chart("c1", barArgs("cache_id", handle));

        assertEquals("error", result.path("status").asText(), result.toString());
        assertEquals("CACHE_MISS", result.path("code").asText(), result.toString());
        assertTrue(AgentToolContext.drainPendingParlerChartBlocks().isEmpty());
    }
}
