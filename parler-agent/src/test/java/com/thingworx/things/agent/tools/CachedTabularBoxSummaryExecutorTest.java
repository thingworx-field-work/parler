package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import com.thingworx.things.agent.llm.ToolCall;

/** D1 {@code box_summary} (chart-enhancement design §7.4): fixtures BX-1..BX-4. */
class CachedTabularBoxSummaryExecutorTest {

    CachedTabularBoxSummaryExecutorTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static InfoTable groupedTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition g = new FieldDefinition();
        g.setName("g");
        g.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(g);
        FieldDefinition v = new FieldDefinition();
        v.setName("v");
        v.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(v);
        return new InfoTable(shape);
    }

    private static void add(InfoTable t, String g, Double v) {
        ValueCollection row = new ValueCollection();
        row.put("g", new StringPrimitive(g));
        row.put("v", v == null ? null : new NumberPrimitive(v));
        t.addRow(row);
    }

    private static JsonNode run(String conversationId, InfoTable src, String argsWithoutCache) throws Exception {
        AgentToolContext.setConversationId(conversationId);
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\"," + argsWithoutCache + "}";
        return MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("bx", "tabulate_cached_result", args)));
    }

    private static JsonNode groupMetricPercentile(String conversationId, InfoTable src, String column, double p)
            throws Exception {
        AgentToolContext.setConversationId(conversationId);
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\",\"groupBy\":[],"
                + "\"measures\":[{\"name\":\"pv\",\"op\":\"percentile\",\"column\":\"" + column + "\",\"p\":" + p + "}]}";
        JsonNode root = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("gm", "tabulate_cached_result", args)));
        assertEquals("success", root.get("status").asText(), root.toString());
        return root.get("rows").get(0).get("pv");
    }

    @Test
    void bx1_quartilesEqualGroupMetricPercentilesForOddAndEvenSamples() throws Exception {
        for (double[] sample : new double[][] {{7, 1, 5, 3, 9}, {8, 2, 6, 4, 10, 12}}) {
            InfoTable t = groupedTable();
            for (double v : sample) {
                add(t, "A", v);
            }
            JsonNode box = run("bx-1-" + sample.length, t, "\"mode\":\"box_summary\",\"column\":\"v\"");
            assertEquals("success", box.get("status").asText(), box.toString());
            assertEquals("CACHED_BOX_SUMMARY_INLINE", box.get("resultKind").asText());
            JsonNode row = box.get("rows").get(0);
            assertEquals("All", row.get("groupKey").asText());
            assertEquals(sample.length, row.get("n").asInt());
            assertEquals(groupMetricPercentile("bx-1-gm25-" + sample.length, t, "v", 25).asDouble(),
                    row.get("q1").asDouble(), 0.0, "q1 = percentile 25");
            assertEquals(groupMetricPercentile("bx-1-gm50-" + sample.length, t, "v", 50).asDouble(),
                    row.get("median").asDouble(), 0.0, "median = percentile 50");
            assertEquals(groupMetricPercentile("bx-1-gm75-" + sample.length, t, "v", 75).asDouble(),
                    row.get("q3").asDouble(), 0.0, "q3 = percentile 75");
            assertEquals("tukey_1_5_iqr_linear_p_v1", row.get("method").asText());
            assertEquals(0, row.get("outlierCount").asInt());
            assertEquals("[]", row.get("outliers").asText());
            assertEquals(row.get("min").asDouble(), row.get("whiskerLow").asDouble(), 0.0, "no outliers: whiskers reach the extremes");
            assertEquals(row.get("max").asDouble(), row.get("whiskerHigh").asDouble(), 0.0);
        }
    }

    @Test
    void bx2_singleValueAndZeroIqrDegenerateCases() throws Exception {
        InfoTable one = groupedTable();
        add(one, "A", 4.2);
        JsonNode single = run("bx-2a", one, "\"mode\":\"box_summary\",\"column\":\"v\"");
        JsonNode r = single.get("rows").get(0);
        for (String k : List.of("min", "q1", "median", "q3", "max", "whiskerLow", "whiskerHigh")) {
            assertEquals(4.2, r.get(k).asDouble(), 0.0, k);
        }
        assertEquals(1, r.get("n").asInt());
        assertEquals(0, r.get("outlierCount").asInt());
        InfoTable flat = groupedTable();
        for (int i = 0; i < 9; i++) {
            add(flat, "A", 5.0);
        }
        add(flat, "A", 5.1);
        JsonNode zeroIqr = run("bx-2b", flat, "\"mode\":\"box_summary\",\"column\":\"v\"");
        JsonNode z = zeroIqr.get("rows").get(0);
        assertEquals(5.0, z.get("q1").asDouble(), 0.0);
        assertEquals(5.0, z.get("q3").asDouble(), 0.0);
        assertEquals(5.0, z.get("whiskerLow").asDouble(), 0.0, "IQR = 0: whiskers equal the quartiles");
        assertEquals(5.0, z.get("whiskerHigh").asDouble(), 0.0);
        assertEquals(1, z.get("outlierCount").asInt(), "the one different value is an outlier");
        assertEquals("[5.1]", z.get("outliers").asText());
        assertEquals(5.1, z.get("max").asDouble(), 0.0);
    }

    @Test
    void bx3_whiskersAreObservedValuesInsideTheFencesAndOutliersAreTruncatedButCounted() throws Exception {
        InfoTable t = groupedTable();
        for (double v : new double[] {10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 60}) {
            add(t, "A", v);
        }
        JsonNode root = run("bx-3a", t, "\"mode\":\"box_summary\",\"column\":\"v\"");
        JsonNode r = root.get("rows").get(0);
        double q1 = r.get("q1").asDouble();
        double q3 = r.get("q3").asDouble();
        double fenceHigh = q3 + 1.5 * (q3 - q1);
        assertTrue(fenceHigh < 60 && fenceHigh > 20, "fixture: the extreme value is beyond the upper fence");
        assertEquals(20.0, r.get("whiskerHigh").asDouble(), 0.0, "the whisker is the farthest observed value inside the fence, not the fence");
        assertEquals(10.0, r.get("whiskerLow").asDouble(), 0.0);
        assertEquals(1, r.get("outlierCount").asInt());
        assertEquals("[60.0]", r.get("outliers").asText());
        assertEquals(60.0, r.get("max").asDouble(), 0.0);
        InfoTable many = groupedTable();
        for (int i = 0; i < 100; i++) {
            add(many, "A", 50.0 + (i % 10) * 0.1);
        }
        for (int i = 1; i <= 30; i++) {
            add(many, "A", 100.0 + i);
        }
        JsonNode truncated = run("bx-3b", many, "\"mode\":\"box_summary\",\"column\":\"v\"");
        JsonNode m = truncated.get("rows").get(0);
        assertEquals(30, m.get("outlierCount").asInt(), "every outlier is counted");
        JsonNode shown = MAPPER.readTree(m.get("outliers").asText());
        assertEquals(20, shown.size(), "at most 20 are listed");
        assertEquals(130.0, shown.get(0).asDouble(), 0.0, "farthest from the fence first");
        assertEquals(111.0, shown.get(19).asDouble(), 0.0);
        for (int i = 1; i < shown.size(); i++) {
            assertTrue(shown.get(i - 1).asDouble() >= shown.get(i).asDouble(), "ordered by distance to the fence");
        }
    }

    @Test
    void bx4_groupsKeepSourceOrderSkipAllInvalidGroupsAndCapAtTwentyFour() throws Exception {
        InfoTable t = groupedTable();
        add(t, "B", 3.0);
        add(t, "B", 5.0);
        add(t, "A", 1.0);
        add(t, "A", 2.0);
        add(t, "C", null);
        add(t, "C", null);
        add(t, "A", 3.0);
        JsonNode root = run("bx-4a", t, "\"mode\":\"box_summary\",\"column\":\"v\",\"groupBy\":\"g\"");
        assertEquals("success", root.get("status").asText(), root.toString());
        List<String> keys = java.util.stream.StreamSupport.stream(root.get("rows").spliterator(), false)
                .map(x -> x.get("groupKey").asText()).toList();
        assertEquals(List.of("B", "A"), keys, "first-appearance order; the all-invalid group emits no row");
        assertEquals(2, root.get("groupCount").asInt());
        assertEquals(2, root.get("excludedCount").asInt(), "excluded values are still counted");
        assertEquals(3, root.get("rows").get(1).get("n").asInt());
        assertEquals("g", root.get("groupBy").asText());
        JsonNode arrayForm = run("bx-4b", t, "\"mode\":\"box_summary\",\"column\":\"v\",\"groupBy\":[\"g\"]");
        assertEquals(2, arrayForm.get("rows").size(), "a one-element array is the same request");
        InfoTable many = groupedTable();
        for (int i = 0; i < 25; i++) {
            add(many, "g" + i, (double) i);
        }
        JsonNode tooMany = run("bx-4c", many, "\"mode\":\"box_summary\",\"column\":\"v\",\"groupBy\":\"g\"");
        assertEquals("TOO_MANY_GROUPS", tooMany.get("code").asText(), "25 groups are rejected, not truncated");
        InfoTable allNull = groupedTable();
        add(allNull, "A", null);
        JsonNode empty = run("bx-4d", allNull, "\"mode\":\"box_summary\",\"column\":\"v\"");
        assertEquals("CACHED_BOX_SUMMARY_EMPTY", empty.get("resultKind").asText());
        assertEquals(0, empty.get("totalRows").asInt());
        assertEquals(1, empty.get("excludedCount").asInt());
        assertFalse(empty.has("cacheId"));
        JsonNode twoCols = run("bx-4e", t, "\"mode\":\"box_summary\",\"column\":\"v\",\"groupBy\":[\"g\",\"v\"]");
        assertEquals("INVALID_PARAMETERS", twoCols.get("code").asText());
        JsonNode sameCol = run("bx-4f", t, "\"mode\":\"box_summary\",\"column\":\"v\",\"groupBy\":\"v\"");
        assertEquals("INVALID_PARAMETERS", sameCol.get("code").asText());
        JsonNode inline = run("bx-4g", t, "\"mode\":\"box_summary\",\"column\":\"v\"");
        assertNotNull(inline.get("cacheId"), "the INLINE result is its own derived cache");
        assertTrue(inline.get("answerSetComplete").asBoolean());
        assertEquals(List.of("groupKey", "n", "excludedCount", "min", "q1", "median", "q3", "max", "whiskerLow",
                "whiskerHigh", "outlierCount", "outliers", "method"),
                java.util.stream.StreamSupport.stream(inline.get("columns").spliterator(), false)
                        .map(c -> c.get("name").asText()).toList());
    }
}
