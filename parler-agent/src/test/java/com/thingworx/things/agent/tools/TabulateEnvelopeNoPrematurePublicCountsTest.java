package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * BP6 (bundle 0.1.146): tabulate success MUST emit public {@code completeness}/{@code counts};
 * {@code totalAvailable} remains omitted while the source total is unproven.
 */
class TabulateEnvelopeNoPrematurePublicCountsTest {

    TabulateEnvelopeNoPrematurePublicCountsTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void tabulateSuccessEmitsRootCompletenessAndCountsWithoutUnprovenTotal() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("a");
        fd.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("a", new NumberPrimitive(1.0));
        src.addRow(row);
        AgentToolContext.setConversationId("bp6-public-counts");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\","
                + "\"filters\":{\"type\":\"EQ\",\"fieldName\":\"a\",\"value\":1}}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertTrue(root.has("completeness"), "BP6 requires public completeness");
        assertTrue(root.has("counts"), "BP6 requires public counts");
        assertEquals("UNKNOWN", root.path("completeness").path("status").asText());
        assertFalse(root.path("counts").has("totalAvailable"),
                "unproven totalAvailable must stay absent");
    }
}
