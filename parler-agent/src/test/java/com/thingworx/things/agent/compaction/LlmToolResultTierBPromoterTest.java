package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

class LlmToolResultTierBPromoterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void matrix_path_skips_promotion_when_summary_not_smaller() throws Exception {
        String matrix = CompactionTestFixtures.smallTwoRowMatrixToolJson();
        int summaryLen = summaryJsonLength(matrix, "tc-shrink-skip");
        assertTrue(summaryLen >= matrix.length(), "fixture must produce a non-shrinking summary");
        List<ChatMessage> messages = transcriptWithPriorTool("tc-shrink-skip", matrix);
        List<String> infoLines = new CopyOnWriteArrayList<>();
        int promoted = LlmToolResultTierBPromoter.apply(messages, capturingInfoLogger(infoLines));
        assertEquals(0, promoted);
        assertEquals(matrix, messages.get(2).getContent());
        assertTrue(infoLines.stream().anyMatch(s -> s.contains("LLM_TIER_B_SKIP") && s.contains("reason=no_shrink")));
        assertTrue(infoLines.stream().anyMatch(s -> s.contains("LLM_TIER_B_PROMOTED") && s.contains("skippedNoShrink=")));
    }

    @Test
    void cohort_bundle_skips_promotion_when_summary_not_smaller() throws Exception {
        String innerMatrix = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"thingName\",\"baseType\":\"THINGNAME\"},"
                + "{\"name\":\"v\",\"baseType\":\"NUMBER\"}],\"rows\":[[\"A\",1]]}";
        String bundleJson = "{\"$format\":\"" + LlmToolResultCohortMerger.FORMAT_BUNDLE
                + "\",\"cohortId\":\"c1\",\"toolName\":\"get_property_values\","
                + "\"cohortDimension\":\"thingName\",\"memberCount\":1,"
                + "\"members\":[{\"memberIndex\":0,\"toolCallId\":\"m0\",\"args\":{\"thingName\":\"A\"}}],"
                + "\"result\":" + innerMatrix + "}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("bundle1", "get_property_values", "{}"))));
        messages.add(ChatMessage.toolResult("bundle1", bundleJson));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        List<String> infoLines = new CopyOnWriteArrayList<>();
        int promoted = LlmToolResultTierBPromoter.apply(messages, capturingInfoLogger(infoLines));
        assertEquals(0, promoted);
        assertEquals(bundleJson, messages.get(2).getContent());
        assertTrue(infoLines.stream().anyMatch(s -> s.contains("LLM_TIER_B_SKIP") && s.contains("reason=no_shrink")));
        assertTrue(infoLines.stream().anyMatch(s -> s.contains("LLM_TIER_B_PROMOTED") && s.contains("skippedNoShrink=")));
    }

    @Test
    void shrinking_matrix_promotion_emits_promoted_telemetry() throws Exception {
        String matrix = CompactionTestFixtures.largeCacheSampleMatrixToolJson();
        List<ChatMessage> messages = transcriptWithPriorTool("tc-shrink-ok", matrix);
        List<String> infoLines = new CopyOnWriteArrayList<>();
        int promoted = LlmToolResultTierBPromoter.apply(messages, capturingInfoLogger(infoLines));
        assertTrue(promoted >= 1);
        assertTrue(infoLines.stream().anyMatch(s -> s.contains("LLM_TIER_B_PROMOTED") && s.contains("promoted=")));
        assertFalse(messages.get(2).getContent().contains(InfoTableMatrixCodec.FORMAT_MATRIX_V1));
    }

    @Test
    void promotes_get_entity_shaped_body_to_entity_metadata_summary() throws Exception {
        String entityJson = CompactionTestFixtures.largeGetEntityShapedToolJson();
        List<ChatMessage> messages = transcriptWithPriorTool("tc-entity", entityJson);
        List<String> infoLines = new CopyOnWriteArrayList<>();
        int promoted = LlmToolResultTierBPromoter.apply(messages, capturingInfoLogger(infoLines));
        assertTrue(promoted >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertEquals(EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_SUMMARY_V1, body.path("$format").asText());
        assertTrue(infoLines.stream().anyMatch(s -> s.contains("entityMetadataPromoted=")));
    }

    @Test
    void promotes_prior_turn_matrix_tool_body_to_summary() throws Exception {
        String matrix = CompactionTestFixtures.largeCacheSampleMatrixToolJson();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("first question"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tc1", "query_entities", "{\"thingTemplate\":\"T\"}"))));
        messages.add(ChatMessage.toolResult("tc1", matrix));
        messages.add(ChatMessage.assistant("first answer"));
        messages.add(ChatMessage.user("second question"));
        messages.add(ChatMessage.assistant("final"));
        int n = LlmToolResultTierBPromoter.apply(messages, null);
        assertTrue(n >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(body.path("$format").asText().contains("summary"));
    }

    @Test
    void promotes_matrix_using_sample_rows_key() throws Exception {
        String matrix = CompactionTestFixtures.largeCacheSampleMatrixToolJson();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("t1", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("t1", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(body.path("$format").asText().contains("summary"));
        assertEquals(20, body.path("rowCount").asInt());
        assertTrue(body.path("sampleOnly").asBoolean());
    }

    @Test
    void second_apply_is_idempotent_no_extra_promotions() throws Exception {
        String matrix = CompactionTestFixtures.largeCacheSampleMatrixToolJson();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tc1", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("tc1", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        int first = LlmToolResultTierBPromoter.apply(messages, null);
        assertTrue(first >= 1);
        int second = LlmToolResultTierBPromoter.apply(messages, null);
        assertEquals(0, second);
    }

    @Test
    void current_turn_tool_body_stays_raw_last_user_boundary() throws Exception {
        String priorMatrix = CompactionTestFixtures.largeCacheSampleMatrixToolJson();
        String currentMatrix = CompactionTestFixtures.smallTwoRowMatrixToolJson();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("t1", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("t1", priorMatrix));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("t2", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("t2", currentMatrix));
        LlmToolResultTierBPromoter.apply(messages, null);
        JsonNode prior = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(prior.path("$format").asText().contains("summary"));
        JsonNode current = MAPPER.readTree(messages.get(5).getContent());
        assertTrue(current.path("$format").asText().contains("matrix"));
    }

    @Test
    void summary_sets_protected_omissions_when_password_column_skipped() throws Exception {
        String[] users = { "alice", "bob", "carol", "dave" };
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("[\"").append(users[i % users.length]).append(BULK_FILLER).append(i)
                    .append("\",\"s").append(i).append("\"]");
        }
        String matrix = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":["
                + "{\"name\":\"user\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"secret\",\"baseType\":\"PASSWORD\"}],"
                + "\"rows\":[" + rows + "]}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tp", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("tp", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(body.path("protectedOmissions").asBoolean());
        boolean hasSecret = false;
        for (JsonNode c : body.withArray("columns")) {
            if ("secret".equals(c.path("name").asText())) {
                hasSecret = true;
            }
        }
        assertFalse(hasSecret);
    }

    @Test
    void cohort_bundle_inner_promoted_members_array_unchanged_member_summaries_aligned() throws Exception {
        String bundleJson = cohortBundleToolJson(3, 50, false);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("bundle1", "get_property_values", "{}"))));
        messages.add(ChatMessage.toolResult("bundle1", bundleJson));
        messages.add(ChatMessage.assistant("answered"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        JsonNode bundleBefore = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(bundleBefore.path("$format").asText().contains("cohort.bundle"));
        int memberCount = bundleBefore.path("members").size();
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode bundleAfter = MAPPER.readTree(messages.get(2).getContent());
        assertEquals(memberCount, bundleAfter.path("members").size());
        JsonNode inner = bundleAfter.path("result");
        assertTrue(inner.path("$format").asText().contains("summary"));
        JsonNode summaries = inner.path("memberSummaries");
        assertTrue(summaries.isArray());
        assertEquals(memberCount, summaries.size());
        for (int i = 0; i < memberCount; i++) {
            int expected = bundleAfter.path("members").get(i).path("memberIndex").asInt();
            assertEquals(expected, summaries.get(i).path("memberIndex").asInt());
            String expectedThing = bundleAfter.path("members").get(i).path("args").path("thingName").asText();
            assertEquals(expectedThing, summaries.get(i).path("dimensionValue").asText());
            assertTrue(summaries.get(i).path("rowCount").asInt() >= 1);
        }
        assertEquals("thingName", bundleAfter.path("cohortDimension").asText());
    }

    @Test
    void summary_preserves_matrix_constants() throws Exception {
        String matrix = manyRowStringMatrixWithConstantsJson(45);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("t1", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("t1", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertEquals("US", body.path("constants").path("region").asText());
    }

    @Test
    void summary_promotes_root_entity_list_and_preserves_constants() throws Exception {
        String pad = "E".repeat(250);
        StringBuilder entities = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            if (i > 0) {
                entities.append(',');
            }
            entities.append("[\"Robot").append(pad).append(i).append("\"]");
        }
        String matrix = "{\"status\":\"success\",\"resultKind\":\"ENTITY_TAXONOMY_QUERY_INLINE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"constants\":{\"hierarchyNode\":\"USA\"},"
                + "\"columns\":[{\"name\":\"EntityName\",\"baseType\":\"STRING\"}],"
                + "\"rootEntityList\":[" + entities + "]}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tx", "query_entities_by_taxonomy", "{}"))));
        messages.add(ChatMessage.toolResult("tx", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(body.path("$format").asText().contains("summary"));
        assertEquals(40, body.path("rowCount").asInt());
        assertEquals("USA", body.path("constants").path("hierarchyNode").asText());
        assertFalse(body.path("sampleOnly").asBoolean());
    }

    @Test
    void sample_root_entity_list_preserves_total_count_and_sets_sample_only() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("[\"E").append(i).append("\"]");
        }
        String matrix = "{\"status\":\"success\",\"resultKind\":\"ENTITY_TAXONOMY_QUERY_LARGE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"totalCount\":200,\"cacheId\":\"c1\","
                + "\"columns\":[{\"name\":\"EntityName\",\"baseType\":\"STRING\"}],"
                + "\"sampleRootEntityList\":[" + rows + "]}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tl", "query_entities_by_taxonomy", "{}"))));
        messages.add(ChatMessage.toolResult("tl", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertEquals(200, body.path("totalCount").asInt());
        assertEquals(20, body.path("rowCount").asInt());
        assertTrue(body.path("sampleOnly").asBoolean());
    }

    @Test
    void all_password_columns_produces_empty_columns_summary_with_protected_flag() throws Exception {
        String matrix = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":["
                + "{\"name\":\"a\",\"baseType\":\"PASSWORD\"},"
                + "{\"name\":\"b\",\"baseType\":\"PASSWORD\"}],"
                + "\"rows\":[[\"x\",\"y\"],[\"p\",\"q\"],[\"r\",\"s\"],[\"t\",\"u\"],[\"v\",\"w\"],"
                + "[\"x2\",\"y2\"],[\"p2\",\"q2\"],[\"r2\",\"s2\"],[\"t2\",\"u2\"],[\"v2\",\"w2\"],"
                + "[\"x3\",\"y3\"],[\"p3\",\"q3\"],[\"r3\",\"s3\"],[\"t3\",\"u3\"],[\"v3\",\"w3\"],"
                + "[\"x4\",\"y4\"],[\"p4\",\"q4\"],[\"r4\",\"s4\"],[\"t4\",\"u4\"],[\"v4\",\"w4\"]]}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("pw", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("pw", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertTrue(body.path("protectedOmissions").asBoolean());
        assertTrue(body.withArray("columns").isEmpty());
        assertTrue(body.path("$format").asText().contains("summary"));
    }

    @Test
    void boolean_column_top_buckets_use_true_false_labels() throws Exception {
        boolean[] vals = { true, false, true, false, true };
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append('[').append(vals[i]).append(',').append(bulkFillerCell(i)).append(']');
        }
        String matrix = matrixWithBulkFillerColumn("ok", "BOOLEAN", rows.toString());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tb", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("tb", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        JsonNode top = body.withArray("columns").get(0).withArray("top");
        assertEquals("true", top.get(0).get(0).asText());
        assertEquals(3, top.get(0).get(1).asInt());
        assertEquals("false", top.get(1).get(0).asText());
        assertEquals(2, top.get(1).get(1).asInt());
    }

    @Test
    void infotable_cell_bucket_uses_complex_marker() throws Exception {
        StringBuilder rows = new StringBuilder();
        String pad = "p".repeat(150);
        for (int i = 0; i < 30; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append(i == 0 ? "[{\"k\":1},\"plain\"]" : "[{\"k\":2},\"plain" + pad + i + "\"]");
        }
        String matrix = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":["
                + "{\"name\":\"nested\",\"baseType\":\"INFOTABLE\"},"
                + "{\"name\":\"label\",\"baseType\":\"STRING\"}],"
                + "\"rows\":[" + rows + "]}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("ti", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("ti", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        JsonNode top = body.withArray("columns").get(0).withArray("top");
        assertEquals("(complex)", top.get(0).get(0).asText());
        assertEquals("plain", body.withArray("columns").get(1).withArray("top").get(0).get(0).asText());
    }

    @Test
    void cache_id_only_summary_omits_column_stats() throws Exception {
        String matrix = CompactionTestFixtures.largeCacheSampleMatrixToolJson();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tc", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("tc", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        assertEquals("tabular-cache-abc", body.path("cacheId").asText());
        JsonNode col0 = body.withArray("columns").get(0);
        assertFalse(col0.has("top"));
        assertFalse(col0.has("numericStats"));
        assertFalse(col0.has("range"));
        assertEquals("name", col0.path("name").asText());
    }

    @Test
    void datetime_column_emits_iso_range() throws Exception {
        String[] dates = {
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", "2026-05-03T12:00:00Z",
                "2026-05-04T00:00:00Z", "2026-05-05T00:00:00Z", "2026-05-06T00:00:00Z",
                "2026-05-07T00:00:00Z", "2026-05-08T00:00:00Z", "2026-05-09T00:00:00Z",
                "2026-05-10T00:00:00Z", "2026-05-11T00:00:00Z", "2026-05-12T00:00:00Z"
        };
        String matrix = matrixWithBulkFillerColumn("ts", "DATETIME", datetimeRowsWithFiller(dates));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("td", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("td", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        JsonNode range = body.withArray("columns").get(0).withArray("range");
        assertEquals("2026-05-01T00:00:00Z", range.get(0).asText());
        assertEquals("2026-05-12T00:00:00Z", range.get(1).asText());
    }

    @Test
    void datetime_range_orders_mixed_offsets_chronologically() throws Exception {
        String[] dates = {
                "2026-05-01T05:00:00-08:00", "2026-05-01T12:00:00Z", "2026-05-02T00:00:00Z",
                "2026-05-03T00:00:00Z", "2026-05-04T00:00:00Z", "2026-05-05T00:00:00Z",
                "2026-05-06T00:00:00Z", "2026-05-07T00:00:00Z", "2026-05-08T00:00:00Z"
        };
        String matrix = matrixWithBulkFillerColumn("ts", "DATETIME", datetimeRowsWithFiller(dates));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tdo", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("tdo", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode range = MAPPER.readTree(messages.get(2).getContent()).withArray("columns").get(0).withArray("range");
        assertEquals("2026-05-01T12:00:00Z", range.get(0).asText());
        assertEquals("2026-05-08T00:00:00Z", range.get(1).asText());
    }

    @Test
    void datetime_range_omitted_when_any_cell_unparseable() throws Exception {
        String matrix = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"ts\",\"baseType\":\"DATETIME\"}],"
                + "\"rows\":[[\"not-a-date\"],[\"2026-05-01T12:00:00Z\"],[\"2026-05-02T00:00:00Z\"],"
                + "[\"2026-05-03T00:00:00Z\"],[\"2026-05-04T00:00:00Z\"],[\"2026-05-05T00:00:00Z\"],"
                + "[\"2026-05-06T00:00:00Z\"],[\"2026-05-07T00:00:00Z\"],[\"2026-05-08T00:00:00Z\"]]}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tdu", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("tdu", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode col0 = MAPPER.readTree(messages.get(2).getContent()).withArray("columns").get(0);
        assertFalse(col0.has("range"));
    }

    @Test
    void datetime_range_mixed_millisecond_precision() throws Exception {
        String[] dates = {
                "2026-05-01T12:00:00Z", "2026-05-01T12:00:00.500Z", "2026-05-02T00:00:00Z",
                "2026-05-03T00:00:00Z", "2026-05-04T00:00:00Z", "2026-05-05T00:00:00Z",
                "2026-05-06T00:00:00Z", "2026-05-07T00:00:00Z", "2026-05-08T00:00:00Z"
        };
        String matrix = matrixWithBulkFillerColumn("ts", "DATETIME", datetimeRowsWithFiller(dates));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tdm", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("tdm", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode range = MAPPER.readTree(messages.get(2).getContent()).withArray("columns").get(0).withArray("range");
        assertEquals("2026-05-01T12:00:00Z", range.get(0).asText());
        assertEquals("2026-05-08T00:00:00Z", range.get(1).asText());
    }

    @Test
    void cohort_bundle_inner_cache_id_omits_member_summaries() throws Exception {
        String bundleJson = cohortBundleToolJson(2, 40, true);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("bundle1", "get_property_values", "{}"))));
        messages.add(ChatMessage.toolResult("bundle1", bundleJson));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode bundleAfter = MAPPER.readTree(messages.get(2).getContent());
        JsonNode inner = bundleAfter.path("result");
        assertTrue(inner.path("$format").asText().contains("summary"));
        assertEquals("cache-inner", inner.path("cacheId").asText());
        assertFalse(inner.has("memberSummaries"));
        assertEquals("thingName", bundleAfter.path("cohortDimension").asText());
    }

    @Test
    void parseDateTimeToInstant_handles_offset_and_utc() {
        assertNotNull(LlmToolResultTierBPromoter.parseDateTimeToInstant("2026-05-01T05:00:00-08:00"));
        assertNotNull(LlmToolResultTierBPromoter.parseDateTimeToInstant("2026-05-01T12:00:00Z"));
    }

    @Test
    void parseDateTimeToInstant_handles_joda_rfc822_offset() {
        Instant expected = Instant.parse("2026-05-01T04:00:00Z");
        assertEquals(expected, LlmToolResultTierBPromoter.parseDateTimeToInstant("2026-05-01T12:00:00.000+0800"));
    }

    @Test
    void unknown_format_tool_body_passes_through_unchanged() throws Exception {
        String original = "{\"status\":\"success\",\"$format\":\"parler.tool.unknown.v1\",\"payload\":42}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("ux", "some_tool", "{}"))));
        messages.add(ChatMessage.toolResult("ux", original));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertEquals(0, LlmToolResultTierBPromoter.apply(messages, null));
        assertEquals(original, messages.get(2).getContent());
    }

    @Test
    void string_top_buckets_break_ties_by_key_lexicographic() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            if (i > 0) {
                rows.append(',');
            }
            String key = i % 2 == 0 ? "b" : "a";
            rows.append("[\"").append(key).append("\",").append(bulkFillerCell(i)).append(']');
        }
        String matrix = matrixWithBulkFillerColumn("tag", "STRING", rows.toString());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("tb", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("tb", matrix));
        messages.add(ChatMessage.assistant("a1"));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("final"));
        assertTrue(LlmToolResultTierBPromoter.apply(messages, null) >= 1);
        JsonNode body = MAPPER.readTree(messages.get(2).getContent());
        JsonNode top = body.withArray("columns").get(0).withArray("top");
        assertEquals("a", top.get(0).get(0).asText());
        assertEquals("b", top.get(1).get(0).asText());
    }

    private static final String BULK_FILLER = "f".repeat(400);

    private static String bulkFillerCell(int rowIdx) {
        return "\"" + BULK_FILLER + "\"";
    }

    private static String matrixWithBulkFillerColumn(String primaryName, String primaryBaseType, String rowsBody) {
        return "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":["
                + "{\"name\":\"" + primaryName + "\",\"baseType\":\"" + primaryBaseType + "\"},"
                + "{\"name\":\"_bulk\",\"baseType\":\"STRING\"}],"
                + "\"rows\":[" + rowsBody + "]}";
    }

    private static String datetimeRowsWithFiller(String[] isoDates) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < isoDates.length; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("[\"").append(isoDates[i]).append("\",").append(bulkFillerCell(i)).append(']');
        }
        return rows.toString();
    }

    private static String cohortBundleToolJson(int memberCount, int matrixRowCount, boolean withCacheId)
            throws Exception {
        String[] things = { "A", "B", "C", "D", "E" };
        ArrayNode members = MAPPER.createArrayNode();
        for (int m = 0; m < memberCount; m++) {
            ObjectNode mem = MAPPER.createObjectNode();
            mem.put("memberIndex", m);
            mem.put("toolCallId", "m" + m);
            ObjectNode args = MAPPER.createObjectNode();
            args.put("thingName", things[m % things.length]);
            mem.set("args", args);
            members.add(mem);
        }
        ObjectNode inner;
        if (withCacheId) {
            inner = (ObjectNode) MAPPER.readTree(manyRowWideStringMatrixToolJson(matrixRowCount));
            inner.put("resultKind", "INFOTABLE_LARGE");
            inner.put("cacheId", "cache-inner");
            inner.put("totalCount", 500);
        } else {
            inner = MAPPER.createObjectNode();
            inner.put("$format", InfoTableMatrixCodec.FORMAT_MATRIX_V1);
            inner.put("status", "success");
            inner.put("resultKind", "INFOTABLE");
            ArrayNode cols = MAPPER.createArrayNode();
            cols.add(MAPPER.createObjectNode().put("name", "thingName").put("baseType", "THINGNAME"));
            cols.add(MAPPER.createObjectNode().put("name", "_bulk").put("baseType", "STRING"));
            cols.add(MAPPER.createObjectNode().put("name", "v").put("baseType", "NUMBER"));
            inner.set("columns", cols);
            ArrayNode rows = MAPPER.createArrayNode();
            for (int i = 0; i < matrixRowCount; i++) {
                ArrayNode row = MAPPER.createArrayNode();
                row.add(things[i % memberCount]);
                row.add(BULK_FILLER);
                row.add(i + 1);
                rows.add(row);
            }
            inner.set("rows", rows);
        }
        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("$format", LlmToolResultCohortMerger.FORMAT_BUNDLE);
        bundle.put("cohortId", "cohort-test");
        bundle.put("toolName", "get_property_values");
        bundle.put("cohortDimension", "thingName");
        bundle.put("memberCount", memberCount);
        bundle.set("members", members);
        bundle.set("result", inner);
        return MAPPER.writeValueAsString(bundle);
    }

    private static String wideTwoSampleRowMatrixToolJson() {
        String pad = "z".repeat(1200);
        return "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"x\",\"baseType\":\"STRING\"}],"
                + "\"sampleRows\":[[\"" + pad + "\"],[\"" + pad + "2\"]]}";
    }

    private static String manyRowWideStringMatrixToolJson(int rowCount) {
        StringBuilder rows = new StringBuilder();
        String pad = "w".repeat(250);
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("[\"").append(pad).append(i).append("\"]");
        }
        return "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"x\",\"baseType\":\"STRING\"}],\"rows\":["
                + rows + "]}";
    }

    private static String manyRowStringMatrixToolJson(int rowCount) {
        return manyRowWideStringMatrixToolJson(rowCount);
    }

    private static String manyRowStringMatrixWithConstantsJson(int rowCount) {
        StringBuilder rows = new StringBuilder();
        String pad = "w".repeat(250);
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("[\"").append(pad).append(i).append("\"]");
        }
        return "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"constants\":{\"region\":\"US\"},"
                + "\"columns\":[{\"name\":\"x\",\"baseType\":\"STRING\"}],\"rows\":["
                + rows + "]}";
    }

    private static int summaryJsonLength(String matrixJson, String toolCallId) throws Exception {
        ObjectNode obj = (ObjectNode) MAPPER.readTree(matrixJson);
        Method m = LlmToolResultTierBPromoter.class.getDeclaredMethod(
                "matrixToSummaryJson", ObjectNode.class, String.class, String.class, Logger.class);
        m.setAccessible(true);
        String summary = (String) m.invoke(null, obj, "query_entities", toolCallId, null);
        return summary == null ? -1 : summary.length();
    }

    private static List<ChatMessage> transcriptWithPriorTool(String toolCallId, String toolJson) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("first question"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall(toolCallId, "query_entities", "{\"thingTemplate\":\"T\"}"))));
        messages.add(ChatMessage.toolResult(toolCallId, toolJson));
        messages.add(ChatMessage.assistant("first answer"));
        messages.add(ChatMessage.user("second question"));
        messages.add(ChatMessage.assistant("final"));
        return messages;
    }

    private static Logger capturingInfoLogger(List<String> infoLines) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) {
                    return "LlmToolResultTierBPromoterTestLogger";
                }
            }
            if ("isInfoEnabled".equals(method.getName())) {
                return true;
            }
            if ("info".equals(method.getName()) && args != null && args.length > 0) {
                infoLines.add(String.valueOf(args[0]));
                return null;
            }
            if (method.getReturnType() == void.class) {
                return null;
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType().isPrimitive()) {
                return 0;
            }
            return null;
        };
        @SuppressWarnings("unchecked")
        Logger log = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] { Logger.class },
                handler);
        return log;
    }

    private static String gpvsJson(String thing, String prop, String baseType, int value) {
        StringBuilder pad = new StringBuilder(2000);
        for (int i = 0; i < 2000; i++) {
            pad.append('z');
        }
        return "{\"status\":\"success\",\"thingName\":\"" + thing + "\",\"_pad\":\"" + pad + "\",\"properties\":["
                + "{\"name\":\"" + prop + "\",\"ok\":true,\"baseType\":\"" + baseType + "\",\"value\":" + value
                + "}]}";
    }
}
