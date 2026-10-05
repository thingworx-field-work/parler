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
import com.thingworx.types.primitives.LocationPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;

/**
 * Query-spec §6 — NEAR / NOTNEAR on LOCATION columns via {@code tabulate_cached_result} (units semantics).
 */
class ParlerNearTabulateMatrixTest {

    ParlerNearTabulateMatrixTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    /** ~100 m north of origin — inside 0.15 km but outside 0.05 statute miles (~80 m). */
    private static InfoTable locationSampleTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fl = new FieldDefinition();
        fl.setName("loc");
        fl.setBaseType(BaseTypes.LOCATION);
        shape.addFieldDefinition(fl);
        FieldDefinition fn = new FieldDefinition();
        fn.setName("n");
        fn.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fn);
        InfoTable src = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("loc", new LocationPrimitive(0.0009, 0.0, 0.0));
        row.put("n", new NumberPrimitive(1.0));
        src.addRow(row);
        return src;
    }

    @Test
    void near_kilometers_vs_miles_changes_match_count() throws Exception {
        InfoTable src = locationSampleTable();
        AgentToolContext.setConversationId("near-matrix-1");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String argsMiles = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"NEAR\",\"fieldName\":\"loc\","
                + "\"location\":{\"latitude\":0,\"longitude\":0},\"distance\":0.05,\"units\":\"miles\"}}";
        String jsonMiles = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("n1", "tabulate_cached_result", argsMiles));
        assertEquals(0, MAPPER.readTree(jsonMiles).get("matchCount").asInt());
        String argsKm = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"NEAR\",\"fieldName\":\"loc\","
                + "\"location\":{\"latitude\":0,\"longitude\":0},\"distance\":0.15,\"units\":\"kilometers\"}}";
        String jsonKm = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("n2", "tabulate_cached_result", argsKm));
        assertEquals(1, MAPPER.readTree(jsonKm).get("matchCount").asInt());
    }

    @Test
    void near_rejects_missing_location() throws Exception {
        InfoTable src = locationSampleTable();
        AgentToolContext.setConversationId("near-matrix-2");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"NEAR\",\"fieldName\":\"loc\",\"distance\":1,\"units\":\"km\"}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("n3", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertTrue(root.get("message").asText().contains("location"));
    }

    @Test
    void near_on_non_location_type_mismatch() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fs = new FieldDefinition();
        fs.setName("s");
        fs.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fs);
        InfoTable src = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new com.thingworx.types.primitives.StringPrimitive("x"));
        src.addRow(row);
        AgentToolContext.setConversationId("near-matrix-3");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"NEAR\",\"fieldName\":\"s\","
                + "\"location\":{\"latitude\":0,\"longitude\":0},\"distance\":1,\"units\":\"km\"}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("n4", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("TYPE_MISMATCH", root.get("code").asText());
    }

    @Test
    void notnear_matches_when_row_outside_small_radius() throws Exception {
        InfoTable src = locationSampleTable();
        AgentToolContext.setConversationId("near-matrix-4");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"NOTNEAR\",\"fieldName\":\"loc\","
                + "\"location\":{\"latitude\":0,\"longitude\":0},\"distance\":0.05,\"units\":\"kilometers\"}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("n5", "tabulate_cached_result", args));
        assertEquals(1, MAPPER.readTree(json).get("matchCount").asInt());
    }

    @Test
    void near_null_location_cell_does_not_match_proximity() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fl = new FieldDefinition();
        fl.setName("loc");
        fl.setBaseType(BaseTypes.LOCATION);
        shape.addFieldDefinition(fl);
        InfoTable src = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("loc", new LocationPrimitive(null));
        src.addRow(row);
        AgentToolContext.setConversationId("near-matrix-5");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"NEAR\",\"fieldName\":\"loc\","
                + "\"location\":{\"latitude\":0,\"longitude\":0},\"distance\":500,\"units\":\"km\"}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("n6", "tabulate_cached_result", args));
        assertEquals(0, MAPPER.readTree(json).get("matchCount").asInt());
    }

    @Test
    void unknown_leaf_type_on_location_invalid_predicate_not_unsupported_column_type() throws Exception {
        InfoTable src = locationSampleTable();
        AgentToolContext.setConversationId("near-matrix-6");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"ZZZUNKNOWNFILTER\",\"fieldName\":\"loc\","
                + "\"location\":{\"latitude\":0,\"longitude\":0},\"distance\":1,\"units\":\"km\"}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("n7", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PREDICATE", root.get("code").asText());
    }

}
