package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link TabularChartSourceResolver}: {@code source:last_invoke} uses latest qualifying tabular success
 * (no {@code AMBIGUOUS_LAST_INVOKE}).
 */
class TabularChartSourceResolverLastInvokeLatestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void lastInvoke_afterTwoQualifyingInline_resolvesLatestRows() throws Exception {
        AgentToolContext.setConversationId("tcsr-last-invoke-latest");
        AgentToolContext.resetTabularChartRound();
        String first = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rows\":[{\"a\":1}]}";
        String second = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rows\":["
                + "{\"UtilizationState\":\"Down\",\"Percentage\":59.45},"
                + "{\"UtilizationState\":\"Unavailable\",\"Percentage\":39.64}]}";
        TabularChartRoundHooks.afterBuiltInToolResult("tool_one", first);
        TabularChartRoundHooks.afterBuiltInToolResult("tool_two", second);
        assertEquals(2, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());

        JsonNode args = MAPPER.readTree(
                "{\"source\":\"last_invoke\",\"xColumn\":\"UtilizationState\",\"yColumn\":\"Percentage\"}");
        String[] err = new String[1];
        TabularChartSourceResolver.Resolved r = TabularChartSourceResolver.resolveOrError(args, err);
        assertNull(err[0], err[0]);
        assertNotNull(r);
        assertEquals(2, r.table.getRowCount());
        assertTrue(r.sourceResolved.contains("last_invoke"));
    }
}
