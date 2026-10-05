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
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * M3 Slice 2 / BP6: public root {@code completeness}/{@code counts} on tabulate/summarize
 * success per {@code CONTRACTS/TABULAR_INSIGHT.md} §5. {@code totalAvailable} only when proven.
 */
class M3Slice2Bp6PublicCountsTest {

    M3Slice2Bp6PublicCountsTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void filterCountPublishesUnknownCompletenessWithoutTotalAvailable() throws Exception {
        InfoTable src = numericTable(3);
        AgentToolContext.setConversationId("bp6-filter-count");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\","
                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"a\",\"value\":0}}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertEquals("UNKNOWN", root.path("completeness").path("status").asText());
        assertTrue(root.path("completeness").path("reasons").isArray());
        assertEquals(3, root.path("counts").path("rowsRead").asInt());
        assertEquals(3, root.path("counts").path("rowsOutput").asInt());
        assertFalse(root.path("counts").has("totalAvailable"),
                "unproven rowsAvailable must omit totalAvailable");
    }

    @Test
    void filterRowsPublishesRowsOutputAsEmittedPage() throws Exception {
        InfoTable src = numericTable(5);
        AgentToolContext.setConversationId("bp6-filter-rows");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_rows\","
                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"a\",\"value\":0},"
                + "\"maxItems\":2,\"offset\":0}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertEquals("UNKNOWN", root.path("completeness").path("status").asText());
        assertEquals(5, root.path("counts").path("rowsRead").asInt());
        assertEquals(2, root.path("counts").path("rowsOutput").asInt());
        assertFalse(root.path("counts").has("totalAvailable"));
    }

    @Test
    void summarizePublishesCountsOverFullSource() throws Exception {
        InfoTable src = numericTable(4);
        AgentToolContext.setConversationId("bp6-summarize");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\"}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeSummarizeCachedResult(
                new ToolCall("t1", "summarize_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertEquals("UNKNOWN", root.path("completeness").path("status").asText());
        assertEquals(4, root.path("counts").path("rowsRead").asInt());
        assertEquals(4, root.path("counts").path("rowsOutput").asInt());
        assertFalse(root.path("counts").has("totalAvailable"));
    }

    @Test
    void provenParentRowsAvailableSurfacesAsTotalAvailable() throws Exception {
        InfoTable src = numericTable(2);
        AgentToolContext.setConversationId("bp6-proven-total");
        SourceDescriptor proven = SourceDescriptor.builder()
                .sourceRouteId("test.proven")
                .rowsExamined(2L)
                .rowsReturned(2L)
                .rowsAvailable(42L)
                .completenessStatus(SourceDescriptor.CompletenessStatus.COMPLETE)
                .build();
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src, proven);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\","
                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"a\",\"value\":0}}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertEquals("COMPLETE", root.path("completeness").path("status").asText());
        assertEquals(42, root.path("counts").path("totalAvailable").asInt());
        assertEquals(2, root.path("counts").path("rowsRead").asInt());
        assertEquals(2, root.path("counts").path("rowsOutput").asInt());
    }

    @Test
    void groupMetricPublishesMatchCardinalityAsRowsOutput() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition g = new FieldDefinition();
        g.setName("g");
        g.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(g);
        FieldDefinition a = new FieldDefinition();
        a.setName("a");
        a.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(a);
        InfoTable src = new InfoTable(shape);
        for (int i = 0; i < 3; i++) {
            ValueCollection row = new ValueCollection();
            row.put("g", new StringPrimitive(i % 2 == 0 ? "x" : "y"));
            row.put("a", new NumberPrimitive((double) (i + 1)));
            src.addRow(row);
        }
        AgentToolContext.setConversationId("bp6-group-metric");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\","
                + "\"groupBy\":[\"g\"],"
                + "\"measures\":[{\"name\":\"suma\",\"op\":\"sum\",\"column\":\"a\"}]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText(), root.toString());
        assertEquals("UNKNOWN", root.path("completeness").path("status").asText());
        assertEquals(3, root.path("counts").path("rowsRead").asInt());
        assertEquals(2, root.path("counts").path("rowsOutput").asInt());
        assertFalse(root.path("counts").has("totalAvailable"));
    }

    private static InfoTable numericTable(int rows) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("a");
        fd.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        for (int i = 0; i < rows; i++) {
            ValueCollection row = new ValueCollection();
            row.put("a", new NumberPrimitive((double) (i + 1)));
            src.addRow(row);
        }
        return src;
    }
}
