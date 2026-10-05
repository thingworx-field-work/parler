package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

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

class AnalyzeEntitySetExecutorTest {

    AnalyzeEntitySetExecutorTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void difference_basic() throws Exception {
        AgentToolContext.setConversationId("aes-test-1");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "a", "b", "c"));
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "b"));
        String args = "{\"operation\":\"difference\",\"left\":{\"cacheId\":\"" + leftId
                + "\"},\"right\":{\"cacheId\":\"" + rightId + "\"}}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t1", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("ENTITY_SET_INLINE", root.get("resultKind").asText());
        assertEquals(2, root.get("matchedKeys").asInt());
        assertTrue(root.has("cacheId") && !root.get("cacheId").asText().isEmpty());
        assertEquals(2, root.get("rows").size());
    }

    @Test
    void intersection_preserves_left_row_order_and_projection() throws Exception {
        AgentToolContext.setConversationId("aes-int-order");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "tag", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        addNameTagRow(left, "a", "La");
        addNameTagRow(left, "b", "Lb");
        addNameTagRow(left, "c", "Lc");
        InfoTable right = new InfoTable(shape);
        addNameTagRow(right, "c", "Rc");
        addNameTagRow(right, "d", "Rd");
        addNameTagRow(right, "b", "Rb");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"intersection\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"tag\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-int-ord", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("intersection", root.get("operation").asText());
        assertEquals(2, root.get("matchedKeys").asInt());
        JsonNode rows = root.get("rows");
        assertEquals("b", rows.get(0).get("name").asText());
        assertEquals("Lb", rows.get(0).get("tag").asText());
        assertEquals("c", rows.get(1).get("name").asText());
        assertEquals("Lc", rows.get(1).get("tag").asText());
    }

    @Test
    void intersection_empty_overlap() throws Exception {
        AgentToolContext.setConversationId("aes-int-empty");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "a"));
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "b"));
        String args = "{\"operation\":\"intersection\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"}}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-int-0", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("ENTITY_SET_EMPTY", root.get("resultKind").asText());
        assertEquals(0, root.get("matchedKeys").asInt());
        assertTrue(root.get("hint").asText().toLowerCase().contains("intersection"));
    }

    @Test
    void intersection_ignores_right_duplicate_non_key_variation() throws Exception {
        AgentToolContext.setConversationId("aes-right-dup-ignore");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "x", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        addNameXRow(left, "k", "L");
        InfoTable right = new InfoTable(shape);
        addNameXRow(right, "k", "v1");
        addNameXRow(right, "k", "v2");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"intersection\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"x\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-rdup-ok", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(1, root.get("matchedKeys").asInt());
        JsonNode row0 = root.get("rows").get(0);
        assertEquals("k", row0.get("name").asText());
        assertEquals("L", row0.get("x").asText());
    }

    @Test
    void difference_ignores_right_duplicate_non_key_variation() throws Exception {
        AgentToolContext.setConversationId("aes-diff-right-dup");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "x", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        addNameXRow(left, "m", "Lm");
        addNameXRow(left, "k", "Lk");
        InfoTable right = new InfoTable(shape);
        addNameXRow(right, "k", "v1");
        addNameXRow(right, "k", "v2");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"difference\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"x\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-diff-rdup", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(1, root.get("matchedKeys").asInt());
        JsonNode row0 = root.get("rows").get(0);
        assertEquals("m", row0.get("name").asText());
        assertEquals("Lm", row0.get("x").asText());
    }

    @Test
    void rejects_unsupported_operation_unknown() throws Exception {
        AgentToolContext.setConversationId("aes-bad-op");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "a"));
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "b"));
        String args = "{\"operation\":\"bogus_set_op\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"}}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-bad", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("UNSUPPORTED_OPERATION", root.get("code").asText());
        assertTrue(root.get("message").asText().contains("bogus_set_op"));
    }

    @Test
    void union_all_keys_sorted_and_merges_overlap() throws Exception {
        AgentToolContext.setConversationId("aes-union-1");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "tag", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        addNameTagRow(left, "b", "Lb");
        addNameTagRow(left, "a", "La");
        InfoTable right = new InfoTable(shape);
        addNameTagRow(right, "c", "Rc");
        addNameTagRow(right, "b", "Lb");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"union\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"tag\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-uni", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("union", root.get("operation").asText());
        assertEquals(3, root.get("matchedKeys").asInt());
        JsonNode rows = root.get("rows");
        assertEquals("a", rows.get(0).get("name").asText());
        assertEquals("La", rows.get(0).get("tag").asText());
        assertEquals("b", rows.get(1).get("name").asText());
        assertEquals("Lb", rows.get(1).get("tag").asText());
        assertEquals("c", rows.get(2).get("name").asText());
        assertEquals("Rc", rows.get(2).get("tag").asText());
    }

    @Test
    void union_overlap_value_mismatch_errors() throws Exception {
        AgentToolContext.setConversationId("aes-union-mis");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "tag", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        addNameTagRow(left, "k", "L");
        InfoTable right = new InfoTable(shape);
        addNameTagRow(right, "k", "R");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"union\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"tag\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-uni-m", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS", root.get("code").asText());
    }

    @Test
    void symmetric_difference_excludes_overlap() throws Exception {
        AgentToolContext.setConversationId("aes-sym-1");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "tag", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        addNameTagRow(left, "a", "La");
        addNameTagRow(left, "b", "Lb");
        InfoTable right = new InfoTable(shape);
        addNameTagRow(right, "b", "Lb");
        addNameTagRow(right, "c", "Lc");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"symmetric_difference\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"tag\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-sym", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("symmetric_difference", root.get("operation").asText());
        assertEquals(2, root.get("matchedKeys").asInt());
        JsonNode rows = root.get("rows");
        assertEquals("a", rows.get(0).get("name").asText());
        assertEquals("La", rows.get(0).get("tag").asText());
        assertEquals("c", rows.get(1).get("name").asText());
        assertEquals("Lc", rows.get(1).get("tag").asText());
    }

    @Test
    void union_preserves_distinct_string_keys_that_parse_as_equal_numbers() throws Exception {
        AgentToolContext.setConversationId("aes-union-str12");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "tag", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        addNameTagRow(left, "1", "L1");
        InfoTable right = new InfoTable(shape);
        addNameTagRow(right, "01", "R01");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"union\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"tag\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-uni-12", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(2, root.get("matchedKeys").asInt());
    }

    @Test
    void union_lexical_key_order_includes_mixed_numeric_looking_and_text_keys() throws Exception {
        AgentToolContext.setConversationId("aes-union-mix");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "tag", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        addNameTagRow(left, "Pump1", "p");
        addNameTagRow(left, "10", "t10");
        addNameTagRow(left, "2", "t2");
        InfoTable right = new InfoTable(shape);
        addNameTagRow(right, "z", "Z");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"union\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"tag\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-uni-mix", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(4, root.get("matchedKeys").asInt());
        JsonNode rows = root.get("rows");
        assertEquals("10", rows.get(0).get("name").asText());
        assertEquals("2", rows.get(1).get("name").asText());
        assertEquals("Pump1", rows.get(2).get("name").asText());
        assertEquals("z", rows.get(3).get("name").asText());
    }

    @Test
    void symmetric_difference_preserves_distinct_string_keys() throws Exception {
        AgentToolContext.setConversationId("aes-sym-str");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "tag", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        addNameTagRow(left, "1", "L");
        InfoTable right = new InfoTable(shape);
        addNameTagRow(right, "01", "R");
        addNameTagRow(right, "onlyR", "X");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"symmetric_difference\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"tag\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-sym-str", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(3, root.get("matchedKeys").asInt());
    }

    @Test
    void union_null_pads_column_missing_on_one_operand() throws Exception {
        AgentToolContext.setConversationId("aes-union-nullpad");
        DataShapeDefinition leftShape = new DataShapeDefinition();
        addField(leftShape, "name", BaseTypes.STRING);
        addField(leftShape, "extraL", BaseTypes.STRING);
        InfoTable left = new InfoTable(leftShape);
        ValueCollection r1 = new ValueCollection();
        r1.put("name", new StringPrimitive("a"));
        r1.put("extraL", new StringPrimitive("La"));
        left.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("name", new StringPrimitive("b"));
        r2.put("extraL", new StringPrimitive("Lb"));
        left.addRow(r2);

        DataShapeDefinition rightShape = new DataShapeDefinition();
        addField(rightShape, "name", BaseTypes.STRING);
        addField(rightShape, "extraR", BaseTypes.STRING);
        InfoTable right = new InfoTable(rightShape);
        ValueCollection s1 = new ValueCollection();
        s1.put("name", new StringPrimitive("b"));
        s1.put("extraR", new StringPrimitive("Rb"));
        right.addRow(s1);
        ValueCollection s2 = new ValueCollection();
        s2.put("name", new StringPrimitive("c"));
        s2.put("extraR", new StringPrimitive("Rc"));
        right.addRow(s2);

        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"union\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"extraL\",\"extraR\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-uni-null", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(3, root.get("matchedKeys").asInt());
        JsonNode rows = root.get("rows");
        JsonNode rowA = rows.get(0);
        assertEquals("a", rowA.get("name").asText());
        assertEquals("La", rowA.get("extraL").asText());
        assertTrue(!rowA.has("extraR") || rowA.get("extraR").isNull());
        JsonNode rowC = rows.get(2);
        assertEquals("c", rowC.get("name").asText());
        assertEquals("Rc", rowC.get("extraR").asText());
        assertTrue(!rowC.has("extraL") || rowC.get("extraL").isNull());
    }

    @Test
    void union_duplicate_key_ambiguous_on_left_operand() throws Exception {
        AgentToolContext.setConversationId("aes-union-ldup");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "tag", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        addNameTagRow(left, "k", "A");
        addNameTagRow(left, "k", "B");
        InfoTable right = new InfoTable(shape);
        addNameTagRow(right, "z", "Z");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"union\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"tag\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-uni-ldup", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS", root.get("code").asText());
        assertTrue(root.get("message").asText().contains("left"));
    }

    @Test
    void symmetric_difference_duplicate_key_ambiguous_on_right_operand() throws Exception {
        AgentToolContext.setConversationId("aes-sym-rdup");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "tag", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        addNameTagRow(left, "onlyL", "L");
        InfoTable right = new InfoTable(shape);
        addNameTagRow(right, "k", "R1");
        addNameTagRow(right, "k", "R2");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"symmetric_difference\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"},\"projectColumns\":[\"name\",\"tag\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-sym-rdup", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS", root.get("code").asText());
        assertTrue(root.get("message").asText().contains("right"));
    }

    @Test
    void parameter_schema_root_property_keys_match_executor_allowlist() {
        Map<String, Object> schema = AnalyzeEntitySetToolSchema.parametersSchema();
        @SuppressWarnings("unchecked")
        Set<String> fromSchema = ((Map<String, Object>) schema.get("properties")).keySet();
        assertEquals(fromSchema, AnalyzeEntitySetExecutor.rootKeysForDriftTest());
    }

    @Test
    void cache_miss_left() throws Exception {
        AgentToolContext.setConversationId("aes-test-2");
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "b"));
        String args = "{\"operation\":\"difference\",\"left\":{\"cacheId\":\"00000000-0000-0000-0000-000000000001\"},"
                + "\"right\":{\"cacheId\":\"" + rightId + "\"}}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t2", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("CACHE_MISS", root.get("code").asText());
    }

    @Test
    void rejects_last_tabular_token_operand() throws Exception {
        AgentToolContext.setConversationId("aes-test-3");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "a"));
        String args = "{\"operation\":\"difference\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + CachedTabularLastCacheHandle.TOKEN + "\"}}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t3", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
        assertTrue(root.get("message").asText().contains("sentinel"));
    }

    @Test
    void rejects_groupBy() throws Exception {
        AgentToolContext.setConversationId("aes-test-4");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "a"));
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "b"));
        String args = "{\"operation\":\"difference\",\"groupBy\":[\"x\"],\"left\":{\"cacheId\":\"" + leftId
                + "\"},\"right\":{\"cacheId\":\"" + rightId + "\"}}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t4", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
        assertTrue(root.get("message").asText().contains("groupBy"));
        assertTrue(root.get("message").asText().contains("Unexpected root key"));
    }

    @Test
    void rejects_sort_as_non_array_root_key() throws Exception {
        AgentToolContext.setConversationId("aes-test-sort-obj");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "a"));
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "b"));
        String args = "{\"operation\":\"difference\",\"sort\":{\"fieldName\":\"name\",\"isAscending\":true},"
                + "\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\"" + rightId + "\"}}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-sort-obj", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
        assertTrue(root.get("message").asText().contains("sort"));
    }

    @Test
    void rejects_query_shaped_root_key_thingTemplate() throws Exception {
        AgentToolContext.setConversationId("aes-test-tt");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "a"));
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "b"));
        String args = "{\"operation\":\"difference\",\"thingTemplate\":\"SomeTemplate\","
                + "\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\"" + rightId + "\"}}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-tt", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("INVALID_PARAMETERS", root.get("code").asText());
        assertTrue(root.get("message").asText().contains("thingTemplate"));
    }

    @Test
    void duplicate_key_ambiguous() throws Exception {
        AgentToolContext.setConversationId("aes-test-5");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "x", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("name", new StringPrimitive("dup"));
        r1.put("x", new StringPrimitive("v1"));
        left.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("name", new StringPrimitive("dup"));
        r2.put("x", new StringPrimitive("v2"));
        left.addRow(r2);
        InfoTable right = new InfoTable(shape);
        ValueCollection r3 = new ValueCollection();
        r3.put("name", new StringPrimitive("other"));
        r3.put("x", new StringPrimitive("z"));
        right.addRow(r3);
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"difference\",\"left\":{\"cacheId\":\"" + leftId
                + "\"},\"right\":{\"cacheId\":\"" + rightId + "\"},\"projectColumns\":[\"name\",\"x\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t5", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS", root.get("code").asText());
    }

    @Test
    void format_key_canonicalizes_integer_and_number() {
        assertEquals("1", AnalyzeEntitySetExecutor.formatEntitySetKeyForComparison(1, BaseTypes.INTEGER));
        assertEquals("1", AnalyzeEntitySetExecutor.formatEntitySetKeyForComparison(1L, BaseTypes.LONG));
        assertEquals("1", AnalyzeEntitySetExecutor.formatEntitySetKeyForComparison(1.0d, BaseTypes.NUMBER));
    }

    @Test
    void blank_key_unusable() throws Exception {
        AgentToolContext.setConversationId("aes-test-blank");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("name", new StringPrimitive(""));
        left.addRow(r1);
        InfoTable right = new InfoTable(shape);
        ValueCollection r2 = new ValueCollection();
        r2.put("name", new StringPrimitive("x"));
        right.addRow(r2);
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"difference\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"}}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-blank", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.get("status").asText());
        assertEquals("ENTITY_SET_KEY_UNUSABLE", root.get("code").asText());
    }

    @Test
    void identical_duplicate_keys_succeed_single_row() throws Exception {
        AgentToolContext.setConversationId("aes-test-dup-ok");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "x", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("name", new StringPrimitive("dup"));
        r1.put("x", new StringPrimitive("same"));
        left.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("name", new StringPrimitive("dup"));
        r2.put("x", new StringPrimitive("same"));
        left.addRow(r2);
        InfoTable right = new InfoTable(shape);
        ValueCollection r3 = new ValueCollection();
        r3.put("name", new StringPrimitive("other"));
        r3.put("x", new StringPrimitive("z"));
        right.addRow(r3);
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"difference\",\"left\":{\"cacheId\":\"" + leftId
                + "\"},\"right\":{\"cacheId\":\"" + rightId + "\"},\"projectColumns\":[\"name\",\"x\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-dup-ok", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(1, root.get("rows").size());
        assertEquals("dup", root.get("rows").get(0).get("name").asText());
    }

    @Test
    void numeric_key_integer_left_number_right_matches() throws Exception {
        AgentToolContext.setConversationId("aes-test-num-key");
        DataShapeDefinition leftShape = new DataShapeDefinition();
        addField(leftShape, "kid", BaseTypes.INTEGER);
        addField(leftShape, "label", BaseTypes.STRING);
        InfoTable left = new InfoTable(leftShape);
        ValueCollection l1 = new ValueCollection();
        l1.put("kid", new NumberPrimitive(1.0d));
        l1.put("label", new StringPrimitive("L"));
        left.addRow(l1);

        DataShapeDefinition rightShape = new DataShapeDefinition();
        addField(rightShape, "kid", BaseTypes.NUMBER);
        addField(rightShape, "label", BaseTypes.STRING);
        InfoTable right = new InfoTable(rightShape);
        ValueCollection r1 = new ValueCollection();
        r1.put("kid", new NumberPrimitive(1.0d));
        r1.put("label", new StringPrimitive("R"));
        right.addRow(r1);

        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"difference\",\"left\":{\"cacheId\":\"" + leftId
                + "\",\"keyColumn\":\"kid\"},\"right\":{\"cacheId\":\"" + rightId + "\",\"keyColumn\":\"kid\"},"
                + "\"projectColumns\":[\"kid\",\"label\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-num", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals(0, root.get("totalRows").asInt());
        assertEquals("ENTITY_SET_EMPTY", root.get("resultKind").asText());
    }

    @Test
    void empty_difference_output_cache_lookup_succeeds() throws Exception {
        AgentToolContext.setConversationId("aes-test-empty-cache");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "a"));
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "x", "a"));
        String args = "{\"operation\":\"difference\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"}}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-ec", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("ENTITY_SET_EMPTY", root.get("resultKind").asText());
        String outId = root.get("cacheId").asText();
        InfoTable cached = InvokeServiceExecutor.lookupCachedInfotable(outId);
        assertNotNull(cached);
        assertEquals(0, cached.getRowCount());
    }

    @Test
    void tabulate_sentinel_resolves_after_analyze_entity_set() throws Exception {
        AgentToolContext.setConversationId("aes-test-sent-tab");
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "tag", "a", "b"));
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(table("name", "tag", "b"));
        String aesArgs = "{\"operation\":\"difference\",\"left\":{\"cacheId\":\"" + leftId + "\"},\"right\":{\"cacheId\":\""
                + rightId + "\"}}";
        String aesJson = AnalyzeEntitySetExecutor.execute(new ToolCall("t-aes", "analyze_entity_set", aesArgs));
        JsonNode aes = MAPPER.readTree(aesJson);
        assertEquals("success", aes.get("status").asText());
        String tabArgs = "{\"cacheId\":\"" + CachedTabularLastCacheHandle.TOKEN + "\",\"mode\":\"filter_count\","
                + "\"filters\":{\"type\":\"EQ\",\"fieldName\":\"name\",\"value\":\"a\"}}";
        String tabJson = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t-tab", "tabulate_cached_result", tabArgs));
        JsonNode tab = MAPPER.readTree(tabJson);
        assertEquals("success", tab.get("status").asText());
        assertEquals("CACHED_FILTER_COUNT", tab.get("resultKind").asText());
        assertEquals(1, tab.get("matchCount").asInt());
    }

    @Test
    void password_key_column_rejected_at_store() {
        AgentToolContext.setConversationId("aes-test-pw-key");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "secret", BaseTypes.PASSWORD);
        InfoTable left = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("name", new StringPrimitive("n1"));
        r1.put("secret", new StringPrimitive("x"));
        left.addRow(r1);
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> InvokeServiceExecutor.storeInfotableInConversationCache(left));
        assertEquals(ArtifactCacheFaultCode.PASSWORD_REJECTED, ex.code());
    }

    @Test
    void password_project_column_rejected_at_store() {
        AgentToolContext.setConversationId("aes-test-pw-proj");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "secret", BaseTypes.PASSWORD);
        InfoTable left = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("name", new StringPrimitive("a"));
        r1.put("secret", new StringPrimitive("x"));
        left.addRow(r1);
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> InvokeServiceExecutor.storeInfotableInConversationCache(left));
        assertEquals(ArtifactCacheFaultCode.PASSWORD_REJECTED, ex.code());
    }

    @Test
    void large_result_kind() throws Exception {
        AgentToolContext.setConversationId("aes-test-6");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "name", BaseTypes.STRING);
        addField(shape, "n", BaseTypes.NUMBER);
        InfoTable left = new InfoTable(shape);
        for (int i = 0; i < 25; i++) {
            ValueCollection r = new ValueCollection();
            r.put("name", new StringPrimitive("L" + i));
            r.put("n", new NumberPrimitive((double) i));
            left.addRow(r);
        }
        InfoTable right = new InfoTable(shape);
        ValueCollection r0 = new ValueCollection();
        r0.put("name", new StringPrimitive("only-right"));
        r0.put("n", new NumberPrimitive(0.0));
        right.addRow(r0);
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        String args = "{\"operation\":\"difference\",\"left\":{\"cacheId\":\"" + leftId
                + "\"},\"right\":{\"cacheId\":\"" + rightId + "\"},\"projectColumns\":[\"name\",\"n\"]}";
        String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t6", "analyze_entity_set", args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.get("status").asText());
        assertEquals("ENTITY_SET_LARGE", root.get("resultKind").asText());
        assertTrue(root.has("sampleRows"));
        assertTrue(root.get("cacheId").asText().length() > 10);
    }

    private static void addNameTagRow(InfoTable it, String name, String tag) {
        ValueCollection r = new ValueCollection();
        r.put("name", new StringPrimitive(name));
        r.put("tag", new StringPrimitive(tag));
        it.addRow(r);
    }

    private static void addNameXRow(InfoTable it, String name, String x) {
        ValueCollection r = new ValueCollection();
        r.put("name", new StringPrimitive(name));
        r.put("x", new StringPrimitive(x));
        it.addRow(r);
    }

    private static InfoTable table(String keyCol, String extraCol, String... keys) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, keyCol, BaseTypes.STRING);
        addField(shape, extraCol, BaseTypes.STRING);
        InfoTable it = new InfoTable(shape);
        for (String k : keys) {
            ValueCollection r = new ValueCollection();
            r.put(keyCol, new StringPrimitive(k));
            r.put(extraCol, new StringPrimitive("v-" + k));
            it.addRow(r);
        }
        return it;
    }

    private static void addField(DataShapeDefinition shape, String name, BaseTypes bt) {
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(bt);
        fd.setOrdinal(shape.getFields() != null ? shape.getFields().size() : 0);
        shape.addFieldDefinition(fd);
    }
}
