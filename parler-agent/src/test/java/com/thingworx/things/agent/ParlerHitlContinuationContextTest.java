package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

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
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.CachedTabularToolsExecutor;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.things.agent.tools.PendingApprovalRecord;

/**
 * Regression for a live defect: approved HITL {@code invoke_service} cached INFOTABLE under
 * {@code __single_turn__} before post-approval continuation rebound.
 */
class ParlerHitlContinuationContextTest {

    ParlerHitlContinuationContextTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REAL_CONV = "Administrator-AssetMonitoring";

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void withoutBind_largeInfotableCacheMissesUnderRealConversationId() throws Exception {
        AgentToolContext.setConversationId(AgentToolContext.SINGLE_TURN_CONVERSATION_ID);
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(sampleTable());
        AgentToolContext.setConversationId(REAL_CONV);
        assertNull(InvokeServiceExecutor.lookupCachedInfotable(cacheId));
    }

    @Test
    void bindForGatedToolExecution_storesAndTabulatesUnderPendingConversationId() throws Exception {
        AgentToolContext.setConversationId(AgentToolContext.SINGLE_TURN_CONVERSATION_ID);
        PendingApprovalRecord rec = pendingRecord(REAL_CONV);
        ParlerHitlContinuationContext.bindForGatedToolExecution(null, rec, null, null, Collections.emptyList());
        assertEquals(REAL_CONV, AgentToolContext.getConversationId());

        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(sampleTable());
        assertNotNull(InvokeServiceExecutor.lookupCachedInfotable(cacheId));

        String args = "{\"cacheId\":\"" + cacheId
                + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"LT\",\"fieldName\":\"u\",\"value\":30}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("tab1", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertTrue(root.get("matchCount").asInt() >= 0);
    }

    private static PendingApprovalRecord pendingRecord(String conversationId) {
        ToolCall gated = new ToolCall("call_invoke", "invoke_service", "{\"entityName\":\"X\",\"serviceName\":\"Y\"}");
        return new PendingApprovalRecord(
                "pid-hitl-cache",
                "req-live-1",
                conversationId,
                "Administrator",
                "AIAgent",
                conversationId,
                gated,
                Collections.emptyList(),
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000);
    }

    private static InfoTable sampleTable() throws Exception {
        DataShapeDefinition ds = new DataShapeDefinition();
        ds.addFieldDefinition(new FieldDefinition("u", BaseTypes.NUMBER));
        ds.addFieldDefinition(new FieldDefinition("s", BaseTypes.STRING));
        InfoTable t = new InfoTable(ds);
        addRow(t, 10, "a");
        addRow(t, 20, "b");
        addRow(t, 40, "c");
        return t;
    }

    private static void addRow(InfoTable t, double u, String s) throws Exception {
        ValueCollection row = new ValueCollection();
        row.put("u", new NumberPrimitive(u));
        row.put("s", new StringPrimitive(s));
        t.addRow(row);
    }
}
