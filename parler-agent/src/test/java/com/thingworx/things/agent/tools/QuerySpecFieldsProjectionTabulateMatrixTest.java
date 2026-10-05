package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/** Query-spec §5.3 / §9 — root {@code fields} projection validation and {@code group_metric} output-only names. */
class QuerySpecFieldsProjectionTabulateMatrixTest {

    QuerySpecFieldsProjectionTabulateMatrixTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static InfoTable sampleMuTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition f1 = new FieldDefinition();
        f1.setName("m");
        f1.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(f1);
        FieldDefinition f2 = new FieldDefinition();
        f2.setName("u");
        f2.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(f2);
        InfoTable src = new InfoTable(shape);
        addRow(src, "A", 10.0);
        addRow(src, "B", 25.0);
        addRow(src, "C", 0.0);
        return src;
    }

    private static void addRow(InfoTable src, String m, double u) {
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive(m));
        row.put("u", new NumberPrimitive(u));
        src.addRow(row);
    }

    @Test
    void filter_rows_fields_order_preserved_in_columns_metadata() throws Exception {
        InfoTable src = sampleMuTable();
        AgentToolContext.setConversationId("fields-matrix-1");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_rows\","
                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":-1},"
                + "\"fields\":[\"u\",\"m\"],\"maxItems\":10}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("f1", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("u", root.get("columns").get(0).get("name").asText());
        assertEquals("m", root.get("columns").get(1).get("name").asText());
    }

    @Test
    void filter_rows_fields_unknown_column_invalid_column() throws Exception {
        InfoTable src = sampleMuTable();
        AgentToolContext.setConversationId("fields-matrix-2");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_rows\","
                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":-1},"
                + "\"fields\":[\"noSuch\"],\"maxItems\":10}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("f2", "tabulate_cached_result", args)));
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_COLUMN", root.get("code").asText());
    }

    @Test
    void filter_rows_fields_empty_array_invalid_parameters() throws Exception {
        InfoTable src = sampleMuTable();
        AgentToolContext.setConversationId("fields-matrix-3");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_rows\","
                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":-1},"
                + "\"fields\":[],\"maxItems\":10}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("f3", "tabulate_cached_result", args)));
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
        assertTrue(root.get("message").asText().contains("empty"));
    }

    @Test
    void filter_rows_fields_duplicate_entry_invalid_parameters() throws Exception {
        InfoTable src = sampleMuTable();
        AgentToolContext.setConversationId("fields-matrix-4");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_rows\","
                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":-1},"
                + "\"fields\":[\"m\",\"m\"],\"maxItems\":10}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("f4", "tabulate_cached_result", args)));
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
        assertTrue(root.get("message").asText().contains("duplicate"));
    }

    @Test
    void group_metric_fields_rejects_source_only_column_name() throws Exception {
        InfoTable src = sampleMuTable();
        AgentToolContext.setConversationId("fields-matrix-5");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"m\"],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}],\"fields\":[\"u\"]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("f5", "tabulate_cached_result", args)));
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_COLUMN", root.get("code").asText());
    }

    @Test
    void filter_rows_fields_password_column_rejected_at_store() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fl = new FieldDefinition();
        fl.setName("label");
        fl.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fl);
        FieldDefinition fs = new FieldDefinition();
        fs.setName("secretCol");
        fs.setBaseType(BaseTypes.PASSWORD);
        shape.addFieldDefinition(fs);
        InfoTable src = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("label", new StringPrimitive("a"));
        row.put("secretCol", new StringPrimitive("x"));
        src.addRow(row);
        AgentToolContext.setConversationId("fields-matrix-6");
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> InvokeServiceExecutor.storeInfotableInConversationCache(src));
        assertEquals(ArtifactCacheFaultCode.PASSWORD_REJECTED, ex.code());
    }
}
