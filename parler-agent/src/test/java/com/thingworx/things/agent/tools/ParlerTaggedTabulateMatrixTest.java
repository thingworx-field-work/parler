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

/**
 * Query-spec §7 — {@code TAGGED} / {@code NOTTAGGED} on {@code TAGS} columns via {@code tabulate_cached_result}.
 */
class ParlerTaggedTabulateMatrixTest {

    ParlerTaggedTabulateMatrixTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static InfoTable tagsSampleTable() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition ft = new FieldDefinition();
        ft.setName("tags");
        ft.setBaseType(BaseTypes.TAGS);
        shape.addFieldDefinition(ft);
        FieldDefinition fn = new FieldDefinition();
        fn.setName("n");
        fn.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fn);
        InfoTable src = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("tags", TagJsonCodec.parseTagCollectionPrimitive(MAPPER.readTree(
                "[{\"vocabulary\":\"DeviceCategory\",\"vocabularyTerm\":\"Sensor\"}]")));
        r1.put("n", new com.thingworx.types.primitives.NumberPrimitive(1.0));
        src.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("tags", TagJsonCodec.parseTagCollectionPrimitive(MAPPER.readTree(
                "[{\"vocabulary\":\"DeviceCategory\",\"vocabularyTerm\":\"Actuator\"}]")));
        r2.put("n", new com.thingworx.types.primitives.NumberPrimitive(2.0));
        src.addRow(r2);
        ValueCollection r3 = new ValueCollection();
        r3.put("tags", TagJsonCodec.parseTagCollectionPrimitive(MAPPER.readTree("[]")));
        r3.put("n", new com.thingworx.types.primitives.NumberPrimitive(3.0));
        src.addRow(r3);
        return src;
    }

    @Test
    void tagged_matches_intersection() throws Exception {
        InfoTable src = tagsSampleTable();
        AgentToolContext.setConversationId("tag-matrix-1");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"TAGGED\",\"fieldName\":\"tags\","
                + "\"tags\":[{\"vocabulary\":\"DeviceCategory\",\"vocabularyTerm\":\"Sensor\"}]}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result", args));
        assertEquals(1, MAPPER.readTree(json).get("matchCount").asInt());
    }

    @Test
    void nottagged_disjoint_set_matches() throws Exception {
        InfoTable src = tagsSampleTable();
        AgentToolContext.setConversationId("tag-matrix-2");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"NOTTAGGED\",\"fieldName\":\"tags\","
                + "\"tags\":[{\"vocabulary\":\"Other\",\"vocabularyTerm\":\"X\"}]}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t2", "tabulate_cached_result", args));
        assertEquals(3, MAPPER.readTree(json).get("matchCount").asInt());
    }

    @Test
    void tagged_on_string_column_type_mismatch() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fs = new FieldDefinition();
        fs.setName("s");
        fs.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fs);
        InfoTable src = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new com.thingworx.types.primitives.StringPrimitive("x"));
        src.addRow(row);
        AgentToolContext.setConversationId("tag-matrix-3");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"TAGGED\",\"fieldName\":\"s\","
                + "\"tags\":[{\"vocabulary\":\"V\",\"vocabularyTerm\":\"T\"}]}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t3", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("TYPE_MISMATCH", root.get("code").asText());
    }

    @Test
    void tagged_with_empty_filter_tags_array_matches_rows_with_any_tags() throws Exception {
        InfoTable src = tagsSampleTable();
        AgentToolContext.setConversationId("tag-matrix-4");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"TAGGED\",\"fieldName\":\"tags\",\"tags\":[]}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t4", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertTrue(root.get("matchCount").asInt() >= 1);
    }
}
