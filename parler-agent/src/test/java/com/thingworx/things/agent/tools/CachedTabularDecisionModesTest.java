package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import org.joda.time.DateTime;
import com.thingworx.things.agent.llm.ToolCall;

class CachedTabularDecisionModesTest {

    CachedTabularDecisionModesTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        System.clearProperty("parler.agent.answerSetComplete.enabled");
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void filter_count_numeric_lt() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-1");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"LT\",\"fieldName\":\"u\",\"value\":30}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t1", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("CACHED_FILTER_COUNT", root.get("resultKind").asText());
        assertEquals(4, root.get("rowCount").asInt());
        assertEquals(3, root.get("matchCount").asInt());
    }

    @Test
    void filter_count_between_exclusive() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-2");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"AND\",\"filters\":["
                + "{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":0},"
                + "{\"type\":\"LT\",\"fieldName\":\"u\",\"value\":30}]}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t2", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals(2, root.get("matchCount").asInt());
    }

    @Test
    void filter_rows_applies_limit_slice() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-3");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_rows\",\"filters\":{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":0},"
                + "\"maxItems\":1,\"offset\":0}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t3", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals(3, root.get("matchCount").asInt());
        assertEquals(1, root.get("totalRows").asInt());
        assertEquals(1, root.get("rows").size());
    }

    @Test
    void filter_rows_fields_projection_returns_only_listed_columns() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-3b");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_rows\",\"filters\":{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":0},"
                + "\"maxItems\":10,\"offset\":0,\"fields\":[\"m\"]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t3b", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        JsonNode row0 = root.get("rows").get(0);
        assertEquals("A", row0.get("m").asText());
        assertTrue(!row0.has("u"));
    }

    @Test
    void filter_count_rejects_root_fields() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-3c");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"LT\",\"fieldName\":\"u\",\"value\":99},"
                + "\"fields\":[\"m\"]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t3c", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void legacy_op_name_key_in_filter_rejected() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-3d");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"EQ\",\"fieldName\":\"u\",\"value\":10,"
                + "\"op_name\":\"bogus\"}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t3d", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PREDICATE", root.get("code").asText());
    }

    @Test
    void filter_sort_topn_nth_via_offset() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-4");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_sort_topn\","
                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":0},"
                + "\"sorts\":[{\"fieldName\":\"u\",\"isAscending\":true}],\"offset\":1,\"maxItems\":1}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t4", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(1, root.get("totalRows").asInt());
        assertEquals(25.0, root.get("rows").get(0).get("u").asDouble(), 1e-9);
    }

    @Test
    void sort_topn_omits_isAscending_defaults_to_ascending() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-4b");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"sort_topn\","
                + "\"sorts\":[{\"fieldName\":\"u\"}],\"maxItems\":1,\"offset\":0}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t4b", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(1, root.get("totalRows").asInt());
        assertEquals("C", root.get("rows").get(0).get("m").asText());
        assertEquals(0.0, root.get("rows").get(0).get("u").asDouble(), 1e-9);
    }

    @Test
    void filter_sort_topn_omits_isAscending_defaults_to_ascending() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-4f");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_sort_topn\","
                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":0},"
                + "\"sorts\":[{\"fieldName\":\"u\"}],\"maxItems\":1,\"offset\":0}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t4f", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(1, root.get("totalRows").asInt());
        assertEquals("A", root.get("rows").get(0).get("m").asText());
        assertEquals(10.0, root.get("rows").get(0).get("u").asDouble(), 1e-9);
    }

    @Test
    void sort_topn_rejects_duplicate_sort_fieldname() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-4c");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"sort_topn\","
                + "\"sorts\":[{\"fieldName\":\"u\",\"isAscending\":true},{\"fieldName\":\"u\",\"isAscending\":false}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t4c", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
        assertTrue(root.get("message").asText().contains("duplicate fieldName"));
        assertTrue(root.get("message").asText().contains("u"));
    }

    @Test
    void sort_topn_rejects_non_boolean_isAscending() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-4d");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"sort_topn\","
                + "\"sorts\":[{\"fieldName\":\"u\",\"isAscending\":\"yes\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t4d", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void filter_rows_sort_omits_isAscending_defaults_ascending() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-4e");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_rows\","
                + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":-1},"
                + "\"sorts\":[{\"fieldName\":\"u\"}],\"maxItems\":1,\"offset\":0}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t4e", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("C", root.get("rows").get(0).get("m").asText());
    }

    @Test
    void group_metric_rejects_duplicate_output_sort_fieldname() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-13b");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}],"
                + "\"sorts\":[{\"fieldName\":\"n\",\"isAscending\":true},{\"fieldName\":\"n\",\"isAscending\":false}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t13b", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
        assertTrue(root.get("message").asText().contains("duplicate fieldName"));
    }

    @Test
    void source_too_large_rejects() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("a");
        fd.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        for (int i = 0; i < CachedTabularToolsExecutor.MAX_SCANNED_ROWS_DECISION + 1; i++) {
            ValueCollection row = new ValueCollection();
            row.put("a", new NumberPrimitive(1.0));
            src.addRow(row);
        }
        AgentToolContext.setConversationId("decision-test-5");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"EQ\",\"fieldName\":\"a\",\"value\":1}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t5", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("SOURCE_TOO_LARGE", root.get("code").asText());
    }

    @Test
    void like_escape_percent() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("s");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("s", new StringPrimitive("100%"));
        src.addRow(r1);
        AgentToolContext.setConversationId("decision-test-6");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"LIKE\",\"fieldName\":\"s\",\"value\":\"100*%\"}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t6", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals(1, root.get("matchCount").asInt());
    }

    @Test
    void boolean_all_composition() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition f1 = new FieldDefinition();
        f1.setName("u");
        f1.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(f1);
        FieldDefinition f2 = new FieldDefinition();
        f2.setName("b");
        f2.setBaseType(BaseTypes.BOOLEAN);
        shape.addFieldDefinition(f2);
        InfoTable src = new InfoTable(shape);
        addRow(src, 10.0, true);
        addRow(src, 5.0, false);
        AgentToolContext.setConversationId("decision-test-7");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"AND\",\"filters\":["
                + "{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":0},"
                + "{\"type\":\"LT\",\"fieldName\":\"u\",\"value\":30},"
                + "{\"type\":\"EQ\",\"fieldName\":\"b\",\"value\":true}]}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t7", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals(1, root.get("matchCount").asInt());
    }

    private static void addRow(InfoTable src, double u, boolean b) {
        ValueCollection row = new ValueCollection();
        row.put("u", new NumberPrimitive(u));
        row.put("b", new com.thingworx.types.primitives.BooleanPrimitive(b));
        src.addRow(row);
    }

    private static InfoTable sampleTable() {
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
        addRow(src, "D", 40.0);
        return src;
    }

    private static void addRow(InfoTable src, String m, double u) {
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive(m));
        row.put("u", new NumberPrimitive(u));
        src.addRow(row);
    }

    @Test
    void string_eq_omit_isCaseSensitive_defaults_case_insensitive_per_query_spec() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("s");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("s", new StringPrimitive("X"));
        src.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("s", new StringPrimitive("x"));
        src.addRow(r2);
        AgentToolContext.setConversationId("decision-test-8");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"EQ\",\"fieldName\":\"s\",\"value\":\"X\"}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t8", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals(2, root.get("matchCount").asInt());
    }

    @Test
    void string_eq_case_sensitive_when_requested() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("s");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("s", new StringPrimitive("X"));
        src.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("s", new StringPrimitive("x"));
        src.addRow(r2);
        AgentToolContext.setConversationId("decision-test-8b");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"EQ\",\"fieldName\":\"s\",\"value\":\"X\",\"isCaseSensitive\":true}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t8b", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals(1, root.get("matchCount").asInt());
    }

    @Test
    void string_eq_case_insensitive_when_requested() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("s");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("s", new StringPrimitive("X"));
        src.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("s", new StringPrimitive("x"));
        src.addRow(r2);
        AgentToolContext.setConversationId("decision-test-9");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{"
                + "\"type\":\"EQ\",\"fieldName\":\"s\",\"value\":\"X\",\"isCaseSensitive\":false}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t9", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals(2, root.get("matchCount").asInt());
    }

    @Test
    void filter_sort_topn_rejects_json_sort_column() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("j");
        fd.setBaseType(BaseTypes.JSON);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("j", new StringPrimitive("{}"));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-10");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_sort_topn\","
                + "\"sorts\":[{\"fieldName\":\"j\",\"isAscending\":true}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t10", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("UNSORTABLE_COLUMN", root.get("code").asText());
    }

    @Test
    void string_column_all_numeric_strings_compare_as_numbers() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("amt");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        for (String v : new String[] {"5", "12", "20"}) {
            ValueCollection row = new ValueCollection();
            row.put("amt", new StringPrimitive(v));
            src.addRow(row);
        }
        AgentToolContext.setConversationId("decision-test-11");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"LT\",\"fieldName\":\"amt\",\"value\":15}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t11", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals(2, root.get("matchCount").asInt());
    }

    @Test
    void like_no_match_returns_zero() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("s");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("s", new StringPrimitive("aaa"));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-12");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"LIKE\",\"fieldName\":\"s\",\"value\":\"*b*\"}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t12", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals(0, root.get("matchCount").asInt());
    }

    @Test
    void group_metric_global_count() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-13");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t13", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("CACHED_GROUP_METRIC_INLINE", root.get("resultKind").asText());
        assertEquals(4, root.get("rowCount").asInt());
        assertEquals(1, root.get("groupCount").asInt());
        assertEquals(1, root.get("matchCount").asInt());
        assertTrue(root.get("rows").isArray());
        assertEquals(1, root.get("rows").size());
    }

    @Test
    void group_metric_percent_of_total_per_group_row() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-pct-total");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"m\"],"
                + "\"measures\":[{\"name\":\"tot\",\"column\":\"u\",\"op\":\"sum\"}],"
                + "\"derived\":[{\"name\":\"pct\",\"op\":\"percent_of_total\",\"input\":\"tot\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("pct-total", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        ArrayNode rows = (ArrayNode) root.get("rows");
        assertEquals(4, rows.size());
        double sumU = 10 + 25 + 0 + 40;
        for (JsonNode row : rows) {
            String m = row.get("m").asText();
            double expected;
            switch (m) {
                case "A":
                    expected = 100.0 * 10.0 / sumU;
                    break;
                case "B":
                    expected = 100.0 * 25.0 / sumU;
                    break;
                case "C":
                    expected = 0.0;
                    break;
                case "D":
                    expected = 100.0 * 40.0 / sumU;
                    break;
                default:
                    throw new AssertionError("unexpected group " + m);
            }
            assertEquals(expected, row.get("pct").asDouble(), 1e-9, m);
        }
    }

    @Test
    void group_metric_percent_of_group_partition() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("dev");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        FieldDefinition fs = new FieldDefinition();
        fs.setName("st");
        fs.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fs);
        FieldDefinition fv = new FieldDefinition();
        fv.setName("val");
        fv.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fv);
        InfoTable src = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("dev", new StringPrimitive("d1"));
        r1.put("st", new StringPrimitive("s1"));
        r1.put("val", new NumberPrimitive(10.0));
        src.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("dev", new StringPrimitive("d1"));
        r2.put("st", new StringPrimitive("s2"));
        r2.put("val", new NumberPrimitive(30.0));
        src.addRow(r2);
        ValueCollection r3 = new ValueCollection();
        r3.put("dev", new StringPrimitive("d2"));
        r3.put("st", new StringPrimitive("s1"));
        r3.put("val", new NumberPrimitive(25.0));
        src.addRow(r3);
        ValueCollection r4 = new ValueCollection();
        r4.put("dev", new StringPrimitive("d2"));
        r4.put("st", new StringPrimitive("s2"));
        r4.put("val", new NumberPrimitive(25.0));
        src.addRow(r4);
        AgentToolContext.setConversationId("decision-test-pct-grp");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"dev\",\"st\"],"
                + "\"measures\":[{\"name\":\"v\",\"column\":\"val\",\"op\":\"sum\"}],"
                + "\"derived\":[{\"name\":\"pg\",\"op\":\"percent_of_group\",\"input\":\"v\",\"groupBy\":[\"dev\"]}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("pct-grp", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        ArrayNode rows = (ArrayNode) root.get("rows");
        assertEquals(4, rows.size());
        java.util.Map<String, Double> map = new java.util.HashMap<>();
        for (JsonNode row : rows) {
            String k = row.get("dev").asText() + "|" + row.get("st").asText();
            map.put(k, row.get("pg").asDouble());
        }
        assertEquals(25.0, map.get("d1|s1"), 1e-9);
        assertEquals(75.0, map.get("d1|s2"), 1e-9);
        assertEquals(50.0, map.get("d2|s1"), 1e-9);
        assertEquals(50.0, map.get("d2|s2"), 1e-9);
    }

    @Test
    void group_metric_percent_of_group_zero_denominator_all_null_measure_in_partition() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("dev");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        FieldDefinition fs = new FieldDefinition();
        fs.setName("st");
        fs.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fs);
        FieldDefinition fv = new FieldDefinition();
        fv.setName("val");
        fv.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fv);
        InfoTable src = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("dev", new StringPrimitive("d1"));
        r1.put("st", new StringPrimitive("s1"));
        r1.put("val", null);
        src.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("dev", new StringPrimitive("d1"));
        r2.put("st", new StringPrimitive("s2"));
        r2.put("val", null);
        src.addRow(r2);
        AgentToolContext.setConversationId("decision-test-pct-grp-null-part");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"dev\",\"st\"],"
                + "\"measures\":[{\"name\":\"v\",\"column\":\"val\",\"op\":\"sum\"}],"
                + "\"derived\":[{\"name\":\"pg\",\"op\":\"percent_of_group\",\"input\":\"v\",\"groupBy\":[\"dev\"]}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("pct-grp-null", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("ZERO_DENOMINATOR", root.get("code").asText());
    }

    @Test
    void group_metric_percent_of_total_zero_denominator() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition f = new FieldDefinition();
        f.setName("u");
        f.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(f);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("u", new NumberPrimitive(0.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-pct-zero");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"tot\",\"column\":\"u\",\"op\":\"sum\"}],"
                + "\"derived\":[{\"name\":\"pct\",\"op\":\"percent_of_total\",\"input\":\"tot\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("pct-zero", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("ZERO_DENOMINATOR", root.get("code").asText());
    }

    @Test
    void group_metric_measure_filter_type_true_remains_invalid_predicate_with_recovery_hint() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-true-measure-filter");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\",\"filters\":{\"type\":\"TRUE\"}}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t-true-filter", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PREDICATE", root.get("code").asText());
        String msg = root.get("message").asText();
        assertTrue(msg.contains("omit the filters field entirely"), msg);
        assertTrue(msg.contains("TRUE"), msg);
    }

    @Test
    void group_metric_multiply_ratio_percent_rejects_scale_mismatch() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition f1 = new FieldDefinition();
        f1.setName("a");
        f1.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(f1);
        FieldDefinition f2 = new FieldDefinition();
        f2.setName("b");
        f2.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(f2);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("a", new NumberPrimitive(4.0));
        r.put("b", new NumberPrimitive(2.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-14");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"x\",\"op\":\"count\"},{\"name\":\"y\",\"op\":\"count\"}],"
                + "\"derived\":["
                + "{\"name\":\"rp\",\"op\":\"ratio_percent\",\"numerator\":\"x\",\"denominator\":\"y\"},"
                + "{\"name\":\"z\",\"op\":\"multiply\",\"inputs\":[\"rp\",\"rp\"]}"
                + "]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t14", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("DERIVED_SCALE_MISMATCH", root.get("code").asText());
    }

    @Test
    void group_metric_empty_table_global_count_zero() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("u");
        fd.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        AgentToolContext.setConversationId("decision-test-15");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t15", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(0, root.get("rowCount").asInt());
        assertEquals(1, root.get("groupCount").asInt());
        assertEquals(1, root.get("matchCount").asInt());
        assertEquals(0.0, root.get("rows").get(0).get("n").asDouble(), 1e-9);
    }

    @Test
    void group_metric_unknown_measure_column_invalid_column() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-16");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"sum\",\"column\":\"noSuchCol\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t16", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_COLUMN", root.get("code").asText());
    }

    @Test
    void group_metric_sum_values_two_ratio_percent_allowed() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fa = new FieldDefinition();
        fa.setName("a");
        fa.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fa);
        FieldDefinition fb = new FieldDefinition();
        fb.setName("b");
        fb.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fb);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("a", new NumberPrimitive(5.0));
        r.put("b", new NumberPrimitive(10.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-17");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"sa\",\"op\":\"sum\",\"column\":\"a\"},{\"name\":\"sb\",\"op\":\"sum\",\"column\":\"b\"}],"
                + "\"derived\":["
                + "{\"name\":\"rp\",\"op\":\"ratio_percent\",\"numerator\":\"sa\",\"denominator\":\"sb\"},"
                + "{\"name\":\"s\",\"op\":\"sum_values\",\"inputs\":[\"rp\",\"rp\"]}"
                + "]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t17", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(100.0, root.get("rows").get(0).get("s").asDouble(), 1e-6);
    }

    @Test
    void group_metric_multiply_after_sum_values_two_ratio_percent_rejects() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fa = new FieldDefinition();
        fa.setName("a");
        fa.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fa);
        FieldDefinition fb = new FieldDefinition();
        fb.setName("b");
        fb.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fb);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("a", new NumberPrimitive(5.0));
        r.put("b", new NumberPrimitive(10.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-17b");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"sa\",\"op\":\"sum\",\"column\":\"a\"},{\"name\":\"sb\",\"op\":\"sum\",\"column\":\"b\"}],"
                + "\"derived\":["
                + "{\"name\":\"rp\",\"op\":\"ratio_percent\",\"numerator\":\"sa\",\"denominator\":\"sb\"},"
                + "{\"name\":\"s\",\"op\":\"sum_values\",\"inputs\":[\"rp\",\"rp\"]},"
                + "{\"name\":\"z\",\"op\":\"multiply\",\"inputs\":[\"s\",\"s\"]}"
                + "]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t17b", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("DERIVED_SCALE_MISMATCH", root.get("code").asText());
    }

    @Test
    void group_metric_sum_values_mix_percent_and_plain_rejects() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fa = new FieldDefinition();
        fa.setName("a");
        fa.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fa);
        FieldDefinition fb = new FieldDefinition();
        fb.setName("b");
        fb.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fb);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("a", new NumberPrimitive(5.0));
        r.put("b", new NumberPrimitive(10.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-17c");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"sa\",\"op\":\"sum\",\"column\":\"a\"},{\"name\":\"sb\",\"op\":\"sum\",\"column\":\"b\"}],"
                + "\"derived\":["
                + "{\"name\":\"rp\",\"op\":\"ratio_percent\",\"numerator\":\"sa\",\"denominator\":\"sb\"},"
                + "{\"name\":\"s\",\"op\":\"sum_values\",\"inputs\":[\"rp\",\"sa\"]}"
                + "]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t17c", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("DERIVED_SCALE_MISMATCH", root.get("code").asText());
    }

    @Test
    void group_metric_difference_percent_vs_fraction_rejects() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fa = new FieldDefinition();
        fa.setName("a");
        fa.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fa);
        FieldDefinition fb = new FieldDefinition();
        fb.setName("b");
        fb.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fb);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("a", new NumberPrimitive(5.0));
        r.put("b", new NumberPrimitive(10.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-17d");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"sa\",\"op\":\"sum\",\"column\":\"a\"},{\"name\":\"sb\",\"op\":\"sum\",\"column\":\"b\"}],"
                + "\"derived\":["
                + "{\"name\":\"rp\",\"op\":\"ratio_percent\",\"numerator\":\"sa\",\"denominator\":\"sb\"},"
                + "{\"name\":\"rf\",\"op\":\"ratio\",\"numerator\":\"sa\",\"denominator\":\"sb\"},"
                + "{\"name\":\"d\",\"op\":\"difference\",\"left\":\"rp\",\"right\":\"rf\"}"
                + "]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t17d", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("DERIVED_SCALE_MISMATCH", root.get("code").asText());
    }

    @Test
    void group_metric_difference_two_ratio_percent_succeeds() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fa = new FieldDefinition();
        fa.setName("a");
        fa.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fa);
        FieldDefinition fb = new FieldDefinition();
        fb.setName("b");
        fb.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fb);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("a", new NumberPrimitive(4.0));
        r.put("b", new NumberPrimitive(2.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-17e");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"sa\",\"op\":\"sum\",\"column\":\"a\"},{\"name\":\"sb\",\"op\":\"sum\",\"column\":\"b\"}],"
                + "\"derived\":["
                + "{\"name\":\"rp1\",\"op\":\"ratio_percent\",\"numerator\":\"sa\",\"denominator\":\"sb\"},"
                + "{\"name\":\"rp2\",\"op\":\"ratio_percent\",\"numerator\":\"sb\",\"denominator\":\"sa\"},"
                + "{\"name\":\"d\",\"op\":\"difference\",\"left\":\"rp1\",\"right\":\"rp2\"}"
                + "]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t17e", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(150.0, root.get("rows").get(0).get("d").asDouble(), 1e-6);
    }

    @Test
    void group_metric_string_null_vs_empty_distinct_groups() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fg = new FieldDefinition();
        fg.setName("g");
        fg.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fg);
        FieldDefinition fu = new FieldDefinition();
        fu.setName("u");
        fu.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fu);
        InfoTable src = new InfoTable(shape);
        ValueCollection r0 = new ValueCollection();
        r0.put("u", new NumberPrimitive(1.0));
        src.addRow(r0);
        ValueCollection r1 = new ValueCollection();
        r1.put("g", new StringPrimitive(""));
        r1.put("u", new NumberPrimitive(2.0));
        src.addRow(r1);
        AgentToolContext.setConversationId("decision-test-17f");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"g\"],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t17f", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(2, root.get("groupCount").asInt());
        assertEquals(2, root.get("rows").size());
    }

    @Test
    void group_metric_number_groupby_default_sort_numeric() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fk);
        FieldDefinition fu = new FieldDefinition();
        fu.setName("u");
        fu.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fu);
        InfoTable src = new InfoTable(shape);
        ValueCollection r10 = new ValueCollection();
        r10.put("k", new NumberPrimitive(10.0));
        r10.put("u", new NumberPrimitive(1.0));
        src.addRow(r10);
        ValueCollection r2 = new ValueCollection();
        r2.put("k", new NumberPrimitive(2.0));
        r2.put("u", new NumberPrimitive(1.0));
        src.addRow(r2);
        AgentToolContext.setConversationId("decision-test-17g");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t17g", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(2.0, root.get("rows").get(0).get("k").asDouble(), 1e-9);
        assertEquals(10.0, root.get("rows").get(1).get("k").asDouble(), 1e-9);
    }

    @Test
    void group_metric_measure_name_collides_with_groupby_rejected() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-17i");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"m\"],"
                + "\"measures\":[{\"name\":\"m\",\"op\":\"count\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t17i", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void group_metric_location_groupby_rejected() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fl = new FieldDefinition();
        fl.setName("loc");
        fl.setBaseType(BaseTypes.LOCATION);
        shape.addFieldDefinition(fl);
        FieldDefinition fu = new FieldDefinition();
        fu.setName("u");
        fu.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fu);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("u", new NumberPrimitive(1.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-17j");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"loc\"],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t17j", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("UNSUPPORTED_COLUMN_TYPE", root.get("code").asText());
    }

    @Test
    void group_metric_multiply_after_scale_fraction_to_percent_rejects() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fa = new FieldDefinition();
        fa.setName("a");
        fa.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fa);
        FieldDefinition fb = new FieldDefinition();
        fb.setName("b");
        fb.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fb);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("a", new NumberPrimitive(4.0));
        r.put("b", new NumberPrimitive(2.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-18");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"sa\",\"op\":\"sum\",\"column\":\"a\"},{\"name\":\"sb\",\"op\":\"sum\",\"column\":\"b\"}],"
                + "\"derived\":["
                + "{\"name\":\"r\",\"op\":\"ratio\",\"numerator\":\"sa\",\"denominator\":\"sb\"},"
                + "{\"name\":\"p\",\"op\":\"scale\",\"input\":\"r\",\"factor\":100},"
                + "{\"name\":\"z\",\"op\":\"multiply\",\"inputs\":[\"p\",\"p\"]}"
                + "]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t18", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("DERIVED_SCALE_MISMATCH", root.get("code").asText());
    }

    @Test
    void group_metric_json_groupby_rejected() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fj = new FieldDefinition();
        fj.setName("j");
        fj.setBaseType(BaseTypes.JSON);
        shape.addFieldDefinition(fj);
        FieldDefinition fu = new FieldDefinition();
        fu.setName("u");
        fu.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fu);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("j", new StringPrimitive("{}"));
        r.put("u", new NumberPrimitive(1.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-19");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"j\"],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t19", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("UNSUPPORTED_COLUMN_TYPE", root.get("code").asText());
    }

    @Test
    void group_metric_derived_inputs_cap_enforced() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("x");
        fx.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fx);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("x", new NumberPrimitive(1.0));
        src.addRow(r);
        StringBuilder ins = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            if (i > 0) {
                ins.append(',');
            }
            ins.append("\"x\"");
        }
        AgentToolContext.setConversationId("decision-test-20");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"x\",\"op\":\"sum\",\"column\":\"x\"}],"
                + "\"derived\":[{\"name\":\"z\",\"op\":\"multiply\",\"inputs\":[" + ins + "]}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t20", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void group_metric_derived_depth_cap_enforced() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("x");
        fx.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fx);
        FieldDefinition fy = new FieldDefinition();
        fy.setName("y");
        fy.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fy);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("x", new NumberPrimitive(1.0));
        r.put("y", new NumberPrimitive(1.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-21");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"x\",\"op\":\"count\"},{\"name\":\"y\",\"op\":\"count\"}],"
                + "\"derived\":["
                + "{\"name\":\"d1\",\"op\":\"ratio\",\"numerator\":\"x\",\"denominator\":\"y\"},"
                + "{\"name\":\"d2\",\"op\":\"scale\",\"input\":\"d1\",\"factor\":1},"
                + "{\"name\":\"d3\",\"op\":\"scale\",\"input\":\"d2\",\"factor\":1},"
                + "{\"name\":\"d4\",\"op\":\"scale\",\"input\":\"d3\",\"factor\":1}"
                + "]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t21", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void group_metric_unsupported_measure_op_rejected() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-22");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"not_a_real_measure_op\",\"column\":\"u\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t22", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void group_metric_explicit_sort_tie_breaks_on_group_key_order() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fk);
        FieldDefinition fu = new FieldDefinition();
        fu.setName("u");
        fu.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fu);
        InfoTable src = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("k", new NumberPrimitive(10.0));
        r1.put("u", new NumberPrimitive(1.0));
        src.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("k", new NumberPrimitive(2.0));
        r2.put("u", new NumberPrimitive(1.0));
        src.addRow(r2);
        AgentToolContext.setConversationId("decision-test-24");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"sum\",\"column\":\"u\"}],"
                + "\"sorts\":[{\"fieldName\":\"n\",\"isAscending\":true}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t24", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(2.0, root.get("rows").get(0).get("k").asDouble(), 1e-9);
        assertEquals(10.0, root.get("rows").get(1).get("k").asDouble(), 1e-9);
    }

    @Test
    void group_metric_sum_values_inputs_rejects_non_string_element() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("x");
        fx.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fx);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("x", new NumberPrimitive(1.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-25");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"x\",\"op\":\"sum\",\"column\":\"x\"}],"
                + "\"derived\":[{\"name\":\"z\",\"op\":\"sum_values\",\"inputs\":[\"x\",5]}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t25", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void group_metric_sum_values_inputs_rejects_blank_string() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("x");
        fx.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fx);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("x", new NumberPrimitive(1.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-26");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"x\",\"op\":\"sum\",\"column\":\"x\"}],"
                + "\"derived\":[{\"name\":\"z\",\"op\":\"sum_values\",\"inputs\":[\"x\",\"\"]}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t26", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void group_metric_duplicate_groupby_columns_rejected() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-27");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"m\",\"m\"],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t27", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void group_metric_derived_name_collides_measure_rejected() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-28");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"},{\"name\":\"x\",\"op\":\"count\"}],"
                + "\"derived\":[{\"name\":\"n\",\"op\":\"ratio\",\"numerator\":\"n\",\"denominator\":\"x\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t28", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void group_metric_duplicate_derived_names_rejected() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("x");
        fx.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fx);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("x", new NumberPrimitive(1.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-29");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"s\",\"op\":\"sum\",\"column\":\"x\"}],"
                + "\"derived\":["
                + "{\"name\":\"d\",\"op\":\"scale\",\"input\":\"s\",\"factor\":1},"
                + "{\"name\":\"d\",\"op\":\"scale\",\"input\":\"s\",\"factor\":2}"
                + "]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t29", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void group_metric_derived_name_collides_groupby_rejected() throws Exception {
        InfoTable src = sampleTable();
        AgentToolContext.setConversationId("decision-test-30");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"m\"],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}],"
                + "\"derived\":[{\"name\":\"m\",\"op\":\"scale\",\"input\":\"n\",\"factor\":1}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t30", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void group_metric_median_two_values() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fk);
        FieldDefinition fu = new FieldDefinition();
        fu.setName("u");
        fu.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fu);
        InfoTable src = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("k", new NumberPrimitive(1.0));
        r1.put("u", new NumberPrimitive(10.0));
        src.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("k", new NumberPrimitive(1.0));
        r2.put("u", new NumberPrimitive(20.0));
        src.addRow(r2);
        AgentToolContext.setConversationId("decision-test-31");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"med\",\"op\":\"median\",\"column\":\"u\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t31", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(15.0, root.get("rows").get(0).get("med").asDouble(), 1e-9);
    }

    @Test
    void group_metric_sum_measure_filters_zero_matching_rows_returns_zero() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fk);
        FieldDefinition fst = new FieldDefinition();
        fst.setName("state");
        fst.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fst);
        FieldDefinition fdur = new FieldDefinition();
        fdur.setName("dur");
        fdur.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fdur);
        InfoTable src = new InfoTable(shape);
        for (String st : new String[] {"Idle", "Idle", "Down"}) {
            ValueCollection r = new ValueCollection();
            r.put("k", new NumberPrimitive(1.0));
            r.put("state", new StringPrimitive(st));
            r.put("dur", new NumberPrimitive(60.0));
            src.addRow(r);
        }
        AgentToolContext.setConversationId("decision-test-31b");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"runningDur\",\"op\":\"sum\",\"column\":\"dur\","
                + "\"filters\":{\"type\":\"EQ\",\"fieldName\":\"state\",\"value\":\"Running\"}}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t31b", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(0.0, root.get("rows").get(0).get("runningDur").asDouble(), 1e-9);
    }

    @Test
    void group_metric_avg_measure_filters_zero_matching_rows_stays_null() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fk);
        FieldDefinition fst = new FieldDefinition();
        fst.setName("state");
        fst.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fst);
        FieldDefinition fdur = new FieldDefinition();
        fdur.setName("dur");
        fdur.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fdur);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("k", new NumberPrimitive(1.0));
        r.put("state", new StringPrimitive("Idle"));
        r.put("dur", new NumberPrimitive(10.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-31c");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"runningAvg\",\"op\":\"avg\",\"column\":\"dur\","
                + "\"filters\":{\"type\":\"EQ\",\"fieldName\":\"state\",\"value\":\"Running\"}}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t31c", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertTrue(root.get("rows").get(0).get("runningAvg").isNull());
    }

    @Test
    void group_metric_sum_no_measure_filters_all_null_numeric_returns_null() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fk);
        FieldDefinition fu = new FieldDefinition();
        fu.setName("u");
        fu.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fu);
        InfoTable src = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("k", new NumberPrimitive(1.0));
        r1.put("u", null);
        src.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("k", new NumberPrimitive(1.0));
        r2.put("u", null);
        src.addRow(r2);
        AgentToolContext.setConversationId("decision-test-31d");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"s\",\"op\":\"sum\",\"column\":\"u\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t31d", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertTrue(root.get("rows").get(0).get("s").isNull());
    }

    @Test
    void group_metric_sum_measure_filters_matches_rows_all_null_numeric_returns_null() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fk);
        FieldDefinition fst = new FieldDefinition();
        fst.setName("state");
        fst.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fst);
        FieldDefinition fdur = new FieldDefinition();
        fdur.setName("dur");
        fdur.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fdur);
        InfoTable src = new InfoTable(shape);
        for (int i = 0; i < 2; i++) {
            ValueCollection r = new ValueCollection();
            r.put("k", new NumberPrimitive(1.0));
            r.put("state", new StringPrimitive("Running"));
            r.put("dur", null);
            src.addRow(r);
        }
        AgentToolContext.setConversationId("decision-test-31e");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"runningDur\",\"op\":\"sum\",\"column\":\"dur\","
                + "\"filters\":{\"type\":\"EQ\",\"fieldName\":\"state\",\"value\":\"Running\"}}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t31e", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertTrue(root.get("rows").get(0).get("runningDur").isNull());
    }

    @Test
    void group_metric_count_distinct_strings() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fk);
        FieldDefinition fs = new FieldDefinition();
        fs.setName("s");
        fs.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fs);
        InfoTable src = new InfoTable(shape);
        for (String s : new String[] {"a", "a", "b"}) {
            ValueCollection r = new ValueCollection();
            r.put("k", new NumberPrimitive(1.0));
            r.put("s", new StringPrimitive(s));
            src.addRow(r);
        }
        AgentToolContext.setConversationId("decision-test-32");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"d\",\"op\":\"count_distinct\",\"column\":\"s\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t32", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(2.0, root.get("rows").get(0).get("d").asDouble(), 1e-9);
    }

    @Test
    void group_metric_weighted_avg_negative_weight_invalid_weight() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fv = new FieldDefinition();
        fv.setName("v");
        fv.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fv);
        FieldDefinition fw = new FieldDefinition();
        fw.setName("w");
        fw.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fw);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        r.put("v", new NumberPrimitive(10.0));
        r.put("w", new NumberPrimitive(-1.0));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-33");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"wa\",\"op\":\"weighted_avg\",\"column\":\"v\",\"weightColumn\":\"w\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t33", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_WEIGHT", root.get("code").asText());
    }

    @Test
    void group_metric_first_last_null_orderby_always_last() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fk);
        FieldDefinition fu = new FieldDefinition();
        fu.setName("u");
        fu.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fu);
        FieldDefinition ft = new FieldDefinition();
        ft.setName("t");
        ft.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(ft);
        InfoTable src = new InfoTable(shape);
        ValueCollection r0 = new ValueCollection();
        r0.put("k", new NumberPrimitive(1.0));
        r0.put("u", new NumberPrimitive(100.0));
        src.addRow(r0);
        ValueCollection r1 = new ValueCollection();
        r1.put("k", new NumberPrimitive(1.0));
        r1.put("u", new NumberPrimitive(200.0));
        r1.put("t", new NumberPrimitive(1.0));
        src.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("k", new NumberPrimitive(1.0));
        r2.put("u", new NumberPrimitive(300.0));
        r2.put("t", new NumberPrimitive(2.0));
        src.addRow(r2);
        AgentToolContext.setConversationId("decision-test-34");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String argsFirst = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":["
                + "{\"name\":\"fv\",\"op\":\"first\",\"column\":\"u\",\"orderBy\":\"t\",\"direction\":\"asc\"},"
                + "{\"name\":\"lv\",\"op\":\"last\",\"column\":\"u\",\"orderBy\":\"t\",\"direction\":\"asc\"},"
                + "{\"name\":\"fd\",\"op\":\"first\",\"column\":\"u\",\"orderBy\":\"t\",\"direction\":\"desc\"},"
                + "{\"name\":\"ld\",\"op\":\"last\",\"column\":\"u\",\"orderBy\":\"t\",\"direction\":\"desc\"}"
                + "]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t34", "tabulate_cached_result", argsFirst));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        JsonNode row = root.get("rows").get(0);
        assertEquals(200.0, row.get("fv").asDouble(), 1e-9);
        assertEquals(100.0, row.get("lv").asDouble(), 1e-9);
        assertEquals(300.0, row.get("fd").asDouble(), 1e-9);
        assertEquals(100.0, row.get("ld").asDouble(), 1e-9);
    }

    @Test
    void group_metric_weighted_avg_happy_path_and_null_weights_values() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fv = new FieldDefinition();
        fv.setName("v");
        fv.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fv);
        FieldDefinition fw = new FieldDefinition();
        fw.setName("w");
        fw.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fw);
        InfoTable src = new InfoTable(shape);
        ValueCollection r0 = new ValueCollection();
        r0.put("v", new NumberPrimitive(10.0));
        r0.put("w", new NumberPrimitive(1.0));
        src.addRow(r0);
        ValueCollection r1 = new ValueCollection();
        r1.put("v", new NumberPrimitive(20.0));
        r1.put("w", new NumberPrimitive(3.0));
        src.addRow(r1);
        AgentToolContext.setConversationId("decision-test-35");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"wa\",\"op\":\"weighted_avg\",\"column\":\"v\",\"weightColumn\":\"w\"}]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t35", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertEquals(17.5, root.get("rows").get(0).get("wa").asDouble(), 1e-9);

        InfoTable src2 = new InfoTable(shape);
        ValueCollection a = new ValueCollection();
        a.put("v", new NumberPrimitive(10.0));
        src2.addRow(a);
        ValueCollection b = new ValueCollection();
        b.put("v", new NumberPrimitive(20.0));
        b.put("w", new NumberPrimitive(2.0));
        src2.addRow(b);
        AgentToolContext.setConversationId("decision-test-35b");
        String cid2 = InvokeServiceExecutor.storeInfotableInConversationCache(src2);
        String args2 = "{\"cacheId\":\"" + cid2 + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"wa\",\"op\":\"weighted_avg\",\"column\":\"v\",\"weightColumn\":\"w\"}]}";
        JsonNode root2 = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t35b", "tabulate_cached_result", args2)));
        assertEquals(20.0, root2.get("rows").get(0).get("wa").asDouble(), 1e-9);

        InfoTable src3 = new InfoTable(shape);
        ValueCollection c = new ValueCollection();
        c.put("v", null);
        c.put("w", new NumberPrimitive(1.0));
        src3.addRow(c);
        ValueCollection d = new ValueCollection();
        d.put("v", new NumberPrimitive(10.0));
        d.put("w", new NumberPrimitive(1.0));
        src3.addRow(d);
        AgentToolContext.setConversationId("decision-test-35c");
        String cid3 = InvokeServiceExecutor.storeInfotableInConversationCache(src3);
        String args3 = "{\"cacheId\":\"" + cid3 + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"wa\",\"op\":\"weighted_avg\",\"column\":\"v\",\"weightColumn\":\"w\"}]}";
        JsonNode root3 = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t35c", "tabulate_cached_result", args3)));
        assertEquals(10.0, root3.get("rows").get(0).get("wa").asDouble(), 1e-9);

        InfoTable src4 = new InfoTable(shape);
        ValueCollection e = new ValueCollection();
        e.put("v", new NumberPrimitive(1.0));
        e.put("w", new NumberPrimitive(0.0));
        src4.addRow(e);
        ValueCollection f = new ValueCollection();
        f.put("v", new NumberPrimitive(2.0));
        f.put("w", new NumberPrimitive(0.0));
        src4.addRow(f);
        AgentToolContext.setConversationId("decision-test-35d");
        String cid4 = InvokeServiceExecutor.storeInfotableInConversationCache(src4);
        String args4 = "{\"cacheId\":\"" + cid4 + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"wa\",\"op\":\"weighted_avg\",\"column\":\"v\",\"weightColumn\":\"w\"}]}";
        JsonNode root4 = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t35d", "tabulate_cached_result", args4)));
        assertTrue(root4.get("rows").get(0).get("wa").isNull());
    }

    @Test
    void group_metric_percentile_endpoints_and_midpoint() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("x");
        fx.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fx);
        InfoTable src = new InfoTable(shape);
        ValueCollection r0 = new ValueCollection();
        r0.put("x", new NumberPrimitive(10.0));
        src.addRow(r0);
        ValueCollection r1 = new ValueCollection();
        r1.put("x", new NumberPrimitive(30.0));
        src.addRow(r1);
        AgentToolContext.setConversationId("decision-test-36");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],\"measures\":["
                + "{\"name\":\"p0\",\"op\":\"percentile\",\"column\":\"x\",\"p\":0},"
                + "{\"name\":\"p100\",\"op\":\"percentile\",\"column\":\"x\",\"p\":100},"
                + "{\"name\":\"p50\",\"op\":\"percentile\",\"column\":\"x\",\"p\":50}"
                + "]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t36", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        JsonNode row = root.get("rows").get(0);
        assertEquals(10.0, row.get("p0").asDouble(), 1e-9);
        assertEquals(30.0, row.get("p100").asDouble(), 1e-9);
        assertEquals(20.0, row.get("p50").asDouble(), 1e-9);
    }

    @Test
    void group_metric_percentile_p_out_of_range_invalid_parameters() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("x");
        fx.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fx);
        InfoTable src = new InfoTable(shape);
        ValueCollection r0 = new ValueCollection();
        r0.put("x", new NumberPrimitive(1.0));
        src.addRow(r0);
        AgentToolContext.setConversationId("decision-test-36b");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],\"measures\":["
                + "{\"name\":\"bad\",\"op\":\"percentile\",\"column\":\"x\",\"p\":150.0}"
                + "]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t36b", "tabulate_cached_result", args)));
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void group_metric_variance_and_stddev_population() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("x");
        fx.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fx);
        InfoTable src = new InfoTable(shape);
        for (double v : new double[] {1.0, 2.0, 3.0}) {
            ValueCollection r = new ValueCollection();
            r.put("x", new NumberPrimitive(v));
            src.addRow(r);
        }
        AgentToolContext.setConversationId("decision-test-37");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],\"measures\":["
                + "{\"name\":\"var\",\"op\":\"variance\",\"column\":\"x\"},"
                + "{\"name\":\"sd\",\"op\":\"stddev\",\"column\":\"x\"}"
                + "]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t37", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        JsonNode row = root.get("rows").get(0);
        double expectedVar = 2.0 / 3.0;
        assertEquals(expectedVar, row.get("var").asDouble(), 1e-9);
        assertEquals(Math.sqrt(expectedVar), row.get("sd").asDouble(), 1e-9);
    }

    @Test
    void group_metric_mode_numeric_boolean_datetime_and_string_rejected() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fk);
        FieldDefinition fu = new FieldDefinition();
        fu.setName("u");
        fu.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fu);
        FieldDefinition fb = new FieldDefinition();
        fb.setName("b");
        fb.setBaseType(BaseTypes.BOOLEAN);
        shape.addFieldDefinition(fb);
        FieldDefinition fdt = new FieldDefinition();
        fdt.setName("dt");
        fdt.setBaseType(BaseTypes.DATETIME);
        shape.addFieldDefinition(fdt);
        FieldDefinition fs = new FieldDefinition();
        fs.setName("s");
        fs.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fs);
        InfoTable src = new InfoTable(shape);
        DateTime tA = new DateTime(2024, 6, 1, 12, 0, 0, 0);
        DateTime tB = new DateTime(2025, 1, 1, 0, 0, 0, 0);
        for (int i = 0; i < 4; i++) {
            ValueCollection r = new ValueCollection();
            r.put("k", new NumberPrimitive(1.0));
            r.put("u", new NumberPrimitive(i < 2 ? 1.0 : 2.0));
            r.put("b", new com.thingworx.types.primitives.BooleanPrimitive(i < 3));
            r.put("dt", new DatetimePrimitive(i < 3 ? tA : tB));
            r.put("s", new StringPrimitive("x"));
            src.addRow(r);
        }
        AgentToolContext.setConversationId("decision-test-38");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],\"measures\":["
                + "{\"name\":\"md\",\"op\":\"mode\",\"column\":\"u\"},"
                + "{\"name\":\"mb\",\"op\":\"mode\",\"column\":\"b\"},"
                + "{\"name\":\"mt\",\"op\":\"mode\",\"column\":\"dt\"}"
                + "]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t38", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        JsonNode row = root.get("rows").get(0);
        assertEquals(1.0, row.get("md").asDouble(), 1e-9);
        assertEquals(1.0, row.get("mb").asDouble(), 1e-9);
        assertEquals((double) tA.getMillis(), row.get("mt").asDouble(), 1e-9);

        DataShapeDefinition shape2 = new DataShapeDefinition();
        FieldDefinition fk2 = new FieldDefinition();
        fk2.setName("k");
        fk2.setBaseType(BaseTypes.NUMBER);
        shape2.addFieldDefinition(fk2);
        FieldDefinition fs2 = new FieldDefinition();
        fs2.setName("s");
        fs2.setBaseType(BaseTypes.STRING);
        shape2.addFieldDefinition(fs2);
        InfoTable src2 = new InfoTable(shape2);
        ValueCollection rs = new ValueCollection();
        rs.put("k", new NumberPrimitive(1.0));
        rs.put("s", new StringPrimitive("x"));
        src2.addRow(rs);
        AgentToolContext.setConversationId("decision-test-38b");
        String cid2 = InvokeServiceExecutor.storeInfotableInConversationCache(src2);
        String args2 = "{\"cacheId\":\"" + cid2 + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"ms\",\"op\":\"mode\",\"column\":\"s\"}]}";
        JsonNode root2 = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t38b", "tabulate_cached_result", args2)));
        assertEquals("error", root2.get("status").asText());
        assertEquals("TYPE_MISMATCH", root2.get("code").asText());
    }

    @Test
    void group_metric_count_distinct_cap_exceeded() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fk);
        FieldDefinition fs = new FieldDefinition();
        fs.setName("s");
        fs.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fs);
        InfoTable src = new InfoTable(shape);
        for (int i = 0; i < 10_001; i++) {
            ValueCollection r = new ValueCollection();
            r.put("k", new NumberPrimitive(1.0));
            r.put("s", new StringPrimitive("v" + i));
            src.addRow(r);
        }
        AgentToolContext.setConversationId("decision-test-39");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"d\",\"op\":\"count_distinct\",\"column\":\"s\"}]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t39", "tabulate_cached_result", args)));
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
    }

    @Test
    void like_many_percent_wildcards_no_match_completes() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("s");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        ValueCollection r = new ValueCollection();
        StringBuilder sb = new StringBuilder(400);
        for (int i = 0; i < 400; i++) {
            sb.append('a');
        }
        r.put("s", new StringPrimitive(sb.toString()));
        src.addRow(r);
        AgentToolContext.setConversationId("decision-test-23");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"filter_count\",\"filters\":{\"type\":\"LIKE\",\"fieldName\":\"s\",\"value\":\"*a*a*a*b\"}}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t23", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals(0, root.get("matchCount").asInt());
    }

    @Test
    void group_metric_answer_set_complete_marker_twenty_one_rows_extended_inline() throws Exception {
        InfoTable src = groupMetricManySingleRowGroups(21);
        AgentToolContext.setConversationId("decision-marker-21");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"c\",\"op\":\"count\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm-marker-21", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("CACHED_GROUP_METRIC_INLINE", root.get("resultKind").asText());
        assertEquals(21, root.get("totalRows").asInt());
        assertEquals(21, root.get("rows").size());
        assertTrue(root.get("answerSetComplete").asBoolean());
        assertEquals(false, root.get("sampleOnly").asBoolean());
        assertEquals(false, root.get("rowsOmitted").asBoolean());
        assertEquals(21, root.get("returnedRows").asInt());
        assertEquals(cid, root.get("sourceCacheId").asText());
        String outCacheId = root.get("cacheId").asText();
        assertFalse(outCacheId.isBlank());
        assertNotEquals(cid, outCacheId);
        InfoTable lookedUp = InvokeServiceExecutor.lookupCachedInfotable(outCacheId);
        assertNotNull(lookedUp);
        assertTrue(lookedUp.getRowCount() > 0);
    }

    @Test
    void group_metric_kill_switch_off_twenty_one_rows_large_sample_shape() throws Exception {
        System.setProperty("parler.agent.answerSetComplete.enabled", "false");
        InfoTable src = groupMetricManySingleRowGroups(21);
        AgentToolContext.setConversationId("decision-marker-21-off");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"c\",\"op\":\"count\"}]}";
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm-marker-21-off", "tabulate_cached_result", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("CACHED_GROUP_METRIC_LARGE", root.get("resultKind").asText());
        assertTrue(root.has("sampleRows"));
        assertTrue(!root.has("rows"));
        assertTrue(!root.has("answerSetComplete"));
    }

    @Test
    void group_metric_fifty_rows_boundary_marker() throws Exception {
        InfoTable src = groupMetricManySingleRowGroups(50);
        AgentToolContext.setConversationId("decision-marker-50");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"c\",\"op\":\"count\"}]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm-marker-50", "tabulate_cached_result", args)));
        assertEquals("CACHED_GROUP_METRIC_INLINE", root.get("resultKind").asText());
        assertEquals(50, root.get("totalRows").asInt());
        assertTrue(root.get("answerSetComplete").asBoolean());
    }

    @Test
    void group_metric_fifty_one_rows_stays_large() throws Exception {
        InfoTable src = groupMetricManySingleRowGroups(51);
        AgentToolContext.setConversationId("decision-marker-51");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"maxItems\":100,\"measures\":[{\"name\":\"c\",\"op\":\"count\"}]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm-marker-51", "tabulate_cached_result", args)));
        assertEquals("CACHED_GROUP_METRIC_LARGE", root.get("resultKind").asText());
        assertTrue(!root.has("answerSetComplete"));
    }

    /** T1: default maxItems (50) truncates 51 post-having groups — no marker, totalRows = matchCount. */
    @Test
    void group_metric_fifty_one_rows_default_max_items_truncation_large_no_marker() throws Exception {
        InfoTable src = groupMetricManySingleRowGroups(51);
        AgentToolContext.setConversationId("decision-marker-51-def");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"c\",\"op\":\"count\"}]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm-marker-51-def", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertEquals("CACHED_GROUP_METRIC_LARGE", root.get("resultKind").asText());
        assertEquals(51, root.get("matchCount").asInt());
        assertEquals(51, root.get("totalRows").asInt());
        assertFalse(root.has("answerSetComplete"));
        assertTrue(root.has("sampleRows"));
    }

    /** T2: same as T1 with having that keeps all 51 groups. */
    @Test
    void group_metric_fifty_one_rows_having_all_pass_default_limit_truncation_large_no_marker() throws Exception {
        InfoTable src = groupMetricManySingleRowGroups(51);
        AgentToolContext.setConversationId("decision-marker-51-having");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"c\",\"op\":\"count\"}],"
                + "\"having\":{\"type\":\"GT\",\"fieldName\":\"c\",\"value\":0}}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm-marker-51-having", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertEquals("CACHED_GROUP_METRIC_LARGE", root.get("resultKind").asText());
        assertEquals(51, root.get("matchCount").asInt());
        assertEquals(51, root.get("totalRows").asInt());
        assertFalse(root.has("answerSetComplete"));
    }

    /** T3: offset/limit slice — legacy inline rc=11 but matchCount=21; no marker; totalRows=matchCount. */
    @Test
    void group_metric_offset_limit_slice_legacy_inline_no_marker_total_rows_post_having() throws Exception {
        InfoTable src = groupMetricManySingleRowGroups(21);
        AgentToolContext.setConversationId("decision-marker-21-slice");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"offset\":10,\"maxItems\":20,\"measures\":[{\"name\":\"c\",\"op\":\"count\"}]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm-marker-21-slice", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertEquals("CACHED_GROUP_METRIC_INLINE", root.get("resultKind").asText());
        assertEquals(21, root.get("matchCount").asInt());
        assertEquals(21, root.get("totalRows").asInt());
        assertEquals(11, root.get("rows").size());
        assertFalse(root.has("answerSetComplete"));
    }

    /** Offset past end yields empty page but {@code totalRows} stays post-having ({@code matchCount}). */
    @Test
    void group_metric_offset_past_end_empty_page_total_rows_matches_match_count() throws Exception {
        InfoTable src = groupMetricManySingleRowGroups(21);
        AgentToolContext.setConversationId("decision-marker-21-past-end");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"offset\":21,\"maxItems\":20,\"measures\":[{\"name\":\"c\",\"op\":\"count\"}]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm-marker-21-past-end", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertEquals("CACHED_GROUP_METRIC_EMPTY", root.get("resultKind").asText());
        assertEquals(21, root.get("matchCount").asInt());
        assertEquals(21, root.get("totalRows").asInt());
        assertTrue(root.get("rows").isArray());
        assertEquals(0, root.get("rows").size());
        assertFalse(root.has("answerSetComplete"));
    }

    /** T4: full page under default maxItems — marker fires when eligible. */
    @Test
    void group_metric_twenty_five_rows_default_limit_marker_positive_control() throws Exception {
        InfoTable src = groupMetricManySingleRowGroups(25);
        AgentToolContext.setConversationId("decision-marker-25");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"k\"],"
                + "\"measures\":[{\"name\":\"c\",\"op\":\"count\"}]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm-marker-25", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertEquals("CACHED_GROUP_METRIC_INLINE", root.get("resultKind").asText());
        assertEquals(25, root.get("matchCount").asInt());
        assertEquals(25, root.get("totalRows").asInt());
        assertEquals(25, root.get("rows").size());
        assertTrue(root.get("answerSetComplete").asBoolean());
        assertEquals(25, root.get("returnedRows").asInt());
    }

    /** T5: 21-row extended band + 8 output columns + fat strings — UTF-8 cap fails; LARGE fallback. */
    @Test
    void group_metric_twenty_one_rows_eight_columns_byte_cap_falls_back_to_large() throws Exception {
        InfoTable src = groupMetricEightOutputColumnsTwentyOneRowsFatStrings();
        AgentToolContext.setConversationId("decision-marker-21-fat");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"a\",\"b\",\"c\",\"d\"],"
                + "\"measures\":["
                + "{\"name\":\"m1\",\"op\":\"sum\",\"column\":\"v\"},"
                + "{\"name\":\"m2\",\"op\":\"sum\",\"column\":\"v\"},"
                + "{\"name\":\"m3\",\"op\":\"sum\",\"column\":\"v\"},"
                + "{\"name\":\"m4\",\"op\":\"sum\",\"column\":\"v\"}"
                + "]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm-marker-21-fat", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText());
        assertEquals("CACHED_GROUP_METRIC_LARGE", root.get("resultKind").asText());
        assertEquals(21, root.get("matchCount").asInt());
        assertEquals(21, root.get("totalRows").asInt());
        assertFalse(root.has("answerSetComplete"));
    }

    /**
     * T6: PASSWORD in output shape makes {@code answerSetCompletePayloadEligible} false.
     * {@link TabularPasswordColumnGuard} blocks PASSWORD in live {@code groupBy}; this asserts the
     * eligibility helper still rejects PASSWORD output metadata (defense in depth).
     */
    @Test
    void group_metric_answer_set_payload_rejects_password_output_column_reflective() throws Exception {
        Method el = CachedTabularGroupMetricExecutor.class.getDeclaredMethod("answerSetCompletePayloadEligible",
                int.class, List.class, InfoTable.class, ArrayNode.class);
        el.setAccessible(true);
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fp = new FieldDefinition();
        fp.setName("k");
        fp.setBaseType(BaseTypes.PASSWORD);
        shape.addFieldDefinition(fp);
        InfoTable out = new InfoTable(shape);
        ValueCollection vc = new ValueCollection();
        vc.put("k", new StringPrimitive("z"));
        out.addRow(vc);
        ArrayNode rows = MAPPER.createArrayNode();
        ObjectNode row = MAPPER.createObjectNode();
        row.put("k", "****");
        rows.add(row);
        assertFalse((Boolean) el.invoke(null, 1, List.of("k"), out, rows));
    }

    @Test
    void group_metric_nine_output_columns_omits_answer_set_marker() throws Exception {
        InfoTable src = groupMetricNineOutputColumnsOneRow();
        AgentToolContext.setConversationId("decision-marker-9col");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[\"a\",\"b\",\"c\",\"d\"],"
                + "\"measures\":["
                + "{\"name\":\"m1\",\"op\":\"sum\",\"column\":\"v\"},"
                + "{\"name\":\"m2\",\"op\":\"sum\",\"column\":\"v\"},"
                + "{\"name\":\"m3\",\"op\":\"sum\",\"column\":\"v\"},"
                + "{\"name\":\"m4\",\"op\":\"sum\",\"column\":\"v\"},"
                + "{\"name\":\"m5\",\"op\":\"sum\",\"column\":\"v\"}"
                + "]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm-marker-9col", "tabulate_cached_result", args)));
        assertEquals("CACHED_GROUP_METRIC_INLINE", root.get("resultKind").asText());
        assertEquals(1, root.get("totalRows").asInt());
        assertTrue(!root.has("answerSetComplete"));
    }

    private static InfoTable groupMetricManySingleRowGroups(int n) {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fk = new FieldDefinition();
        fk.setName("k");
        fk.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fk);
        FieldDefinition fv = new FieldDefinition();
        fv.setName("v");
        fv.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fv);
        InfoTable src = new InfoTable(shape);
        for (int i = 0; i < n; i++) {
            ValueCollection row = new ValueCollection();
            row.put("k", new StringPrimitive("g" + i));
            row.put("v", new NumberPrimitive(1.0));
            src.addRow(row);
        }
        return src;
    }

    private static InfoTable groupMetricNineOutputColumnsOneRow() {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (String name : new String[] { "a", "b", "c", "d" }) {
            FieldDefinition f = new FieldDefinition();
            f.setName(name);
            f.setBaseType(BaseTypes.STRING);
            shape.addFieldDefinition(f);
        }
        FieldDefinition fv = new FieldDefinition();
        fv.setName("v");
        fv.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fv);
        InfoTable src = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("a", new StringPrimitive("a1"));
        row.put("b", new StringPrimitive("b1"));
        row.put("c", new StringPrimitive("c1"));
        row.put("d", new StringPrimitive("d1"));
        row.put("v", new NumberPrimitive(3.0));
        src.addRow(row);
        return src;
    }

    /** 21 distinct groups; four string keys + four sum measures ⇒ eight output columns; long {@code a} blows JSON cap. */
    private static InfoTable groupMetricEightOutputColumnsTwentyOneRowsFatStrings() {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (String name : new String[] { "a", "b", "c", "d" }) {
            FieldDefinition f = new FieldDefinition();
            f.setName(name);
            f.setBaseType(BaseTypes.STRING);
            shape.addFieldDefinition(f);
        }
        FieldDefinition fv = new FieldDefinition();
        fv.setName("v");
        fv.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fv);
        InfoTable src = new InfoTable(shape);
        StringBuilder fat = new StringBuilder(500);
        for (int j = 0; j < 500; j++) {
            fat.append('x');
        }
        String fatBase = fat.toString();
        for (int i = 0; i < 21; i++) {
            ValueCollection row = new ValueCollection();
            row.put("a", new StringPrimitive(fatBase + i));
            row.put("b", new StringPrimitive("b"));
            row.put("c", new StringPrimitive("c"));
            row.put("d", new StringPrimitive("d"));
            row.put("v", new NumberPrimitive(1.0));
            src.addRow(row);
        }
        return src;
    }
}
