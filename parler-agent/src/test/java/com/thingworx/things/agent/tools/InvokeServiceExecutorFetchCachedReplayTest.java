package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.things.agent.llm.ToolCall;

class InvokeServiceExecutorFetchCachedReplayTest {

    InvokeServiceExecutorFetchCachedReplayTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void largePage_returnsCompactLlmJsonAndRegistersFullStreamBody() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("a");
        fd.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fd);
        InfoTable t = new InfoTable(shape);
        for (int i = 0; i < 25; i++) {
            ValueCollection row = new ValueCollection();
            row.put("a", new NumberPrimitive(i));
            t.addRow(row);
        }
        AgentToolContext.setConversationId("conv-replay-test");
        AgentToolContext.setParlerStreamIds("req-z", null);
        FetchCachedReplayGuard.beginTurn(FetchCachedReplayGuard.turnKey("conv-replay-test", "req-z"));
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(t);
        ToolCall call = new ToolCall("call-llm-1", "fetch_cached_result",
                "{\"cacheId\":\"" + cacheId + "\",\"offset\":0,\"limit\":30}");
        String llm = InvokeServiceExecutor.executeFetchCachedResult(call);
        JSONObject compact = new JSONObject(llm);
        assertEquals("success", compact.getString("status"));
        assertTrue(compact.getBoolean("sampleOnly"));
        assertTrue(compact.getBoolean("rowsOmitted"));
        assertEquals(25, compact.getInt("returnedRows"));
        assertTrue(compact.getJSONArray("rows").length() <= 3);
        assertFalse(compact.has("resultKind"));
        assertFalse(compact.has("sourceCacheId"));

        assertNotNull(AgentToolContext.peekFetchCachedStreamJsonForToolCall("call-llm-1"));
        String full = AgentToolContext.takeFetchCachedStreamJsonForToolCall("call-llm-1");
        assertNotNull(full);
        JSONObject fullObj = new JSONObject(full);
        assertEquals(25, fullObj.getJSONArray("rows").length());
    }

    @Test
    void thirdCall_setsRepeatedPageFetch() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("a");
        fd.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fd);
        InfoTable t = new InfoTable(shape);
        for (int i = 0; i < 10; i++) {
            ValueCollection row = new ValueCollection();
            row.put("a", new NumberPrimitive(i));
            t.addRow(row);
        }
        AgentToolContext.setConversationId("conv-replay-test-2");
        AgentToolContext.setParlerStreamIds("req-y", null);
        FetchCachedReplayGuard.beginTurn(FetchCachedReplayGuard.turnKey("conv-replay-test-2", "req-y"));
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(t);
        for (int p = 0; p < 3; p++) {
            ToolCall call = new ToolCall("c" + p, "fetch_cached_result",
                    "{\"cacheId\":\"" + cacheId + "\",\"offset\":" + (p * 4) + ",\"limit\":4}");
            String body = InvokeServiceExecutor.executeFetchCachedResult(call);
            JSONObject o = new JSONObject(body);
            if (p < 2) {
                assertFalse(o.optBoolean("repeatedPageFetch", false), "unexpected repeated flag on page " + p);
            } else {
                assertTrue(o.optBoolean("repeatedPageFetch", false));
            }
        }
    }
}
