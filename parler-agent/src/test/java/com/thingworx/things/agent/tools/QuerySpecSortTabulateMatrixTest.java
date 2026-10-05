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
import com.thingworx.types.primitives.StringPrimitive;

/** Query-spec §8 — sort keys, {@code isCaseSensitive}, unsortable columns. */
class QuerySpecSortTabulateMatrixTest {

    QuerySpecSortTabulateMatrixTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void sort_topn_infotable_column_unsortable() throws Exception {
        DataShapeDefinition nested = new DataShapeDefinition();
        FieldDefinition nestedCol = new FieldDefinition();
        nestedCol.setName("x");
        nestedCol.setBaseType(BaseTypes.STRING);
        nested.addFieldDefinition(nestedCol);
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fi = new FieldDefinition();
        fi.setName("t");
        fi.setBaseType(BaseTypes.INFOTABLE);
        // Local shape required so store can prove PASSWORD absence (U2 M1 nested gate).
        fi.setLocalDataShape(nested);
        shape.addFieldDefinition(fi);
        InfoTable nestedTable = new InfoTable(nested);
        ValueCollection nestedRow = new ValueCollection();
        nestedRow.put("x", new StringPrimitive("v"));
        nestedTable.addRow(nestedRow);
        InfoTable src = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("t", new com.thingworx.types.primitives.InfoTablePrimitive(nestedTable));
        src.addRow(row);
        AgentToolContext.setConversationId("sort-matrix-1");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"sort_topn\","
                + "\"sorts\":[{\"fieldName\":\"t\",\"isAscending\":true}],\"maxItems\":1}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("s1", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("UNSORTABLE_COLUMN", root.get("code").asText());
    }

    @Test
    void sort_string_case_insensitive_changes_order_vs_default_sensitive() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fm = new FieldDefinition();
        fm.setName("m");
        fm.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fm);
        InfoTable src = new InfoTable(shape);
        addRow(src, "B");
        addRow(src, "a");
        addRow(src, "c");
        AgentToolContext.setConversationId("sort-matrix-2");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String argsSensitive = "{\"cacheId\":\"" + cid + "\",\"mode\":\"sort_topn\","
                + "\"sorts\":[{\"fieldName\":\"m\",\"isAscending\":true,\"isCaseSensitive\":true}],\"maxItems\":3}";
        JsonNode sens = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("s2a", "tabulate_cached_result", argsSensitive)));
        String argsInsensitive = "{\"cacheId\":\"" + cid + "\",\"mode\":\"sort_topn\","
                + "\"sorts\":[{\"fieldName\":\"m\",\"isAscending\":true,\"isCaseSensitive\":false}],\"maxItems\":3}";
        JsonNode insens = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("s2b", "tabulate_cached_result", argsInsensitive)));
        assertEquals("B", sens.get("rows").get(0).get("m").asText());
        assertEquals("a", insens.get("rows").get(0).get("m").asText());
    }

    @Test
    void filter_rows_sort_on_tags_column_unsortable() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition ft = new FieldDefinition();
        ft.setName("tags");
        ft.setBaseType(BaseTypes.TAGS);
        shape.addFieldDefinition(ft);
        FieldDefinition fm = new FieldDefinition();
        fm.setName("m");
        fm.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fm);
        InfoTable src = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive("x"));
        row.put("tags", TagJsonCodec.parseTagCollectionPrimitive(null));
        src.addRow(row);
        AgentToolContext.setConversationId("sort-matrix-3");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_rows\","
                + "\"filters\":{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"x\"},"
                + "\"sorts\":[{\"fieldName\":\"tags\",\"isAscending\":true}],\"maxItems\":5}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("s3", "tabulate_cached_result", args)));
        assertEquals("error", root.get("status").asText());
        assertEquals("UNSORTABLE_COLUMN", root.get("code").asText());
    }

    private static void addRow(InfoTable src, String m) {
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive(m));
        src.addRow(row);
    }
}
