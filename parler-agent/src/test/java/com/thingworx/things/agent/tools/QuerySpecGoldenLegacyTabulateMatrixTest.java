package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Query-spec §12 — legacy wrong-shape tool args echo stable guidance; canonical retry succeeds. */
class QuerySpecGoldenLegacyTabulateMatrixTest {

    QuerySpecGoldenLegacyTabulateMatrixTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static InfoTable tinyTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fm = new FieldDefinition();
        fm.setName("m");
        fm.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fm);
        FieldDefinition fu = new FieldDefinition();
        fu.setName("u");
        fu.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fu);
        InfoTable src = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive("z"));
        row.put("u", new NumberPrimitive(5.0));
        src.addRow(row);
        return src;
    }

    @Test
    void legacy_where_root_key_rejected_then_canonical_filters_succeeds() throws Exception {
        InfoTable src = tinyTable();
        AgentToolContext.setConversationId("golden-matrix-1");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String bad = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\","
                + "\"where\":{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"z\"}}";
        JsonNode err = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("g1a", "tabulate_cached_result", bad)));
        assertEquals("error", err.get("status").asText());
        assertEquals("INVALID_PREDICATE", err.get("code").asText());
        String errMsg = err.get("message").asText();
        assertTrue(errMsg.contains("rejected legacy"), errMsg);
        assertTrue(errMsg.contains("fieldName"), errMsg);
        assertTrue(errMsg.contains("Canonical"), errMsg);
        assertTrue(errMsg.contains("query-spec.md"), errMsg);
        String good = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\","
                + "\"filters\":{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"z\"}}";
        JsonNode ok = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("g1b", "tabulate_cached_result", good)));
        assertEquals("success", ok.get("status").asText());
        assertEquals(1, ok.get("matchCount").asInt());
    }
}
