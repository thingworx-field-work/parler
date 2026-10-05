package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

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

/** D1 {@code bin_numeric} (chart-enhancement design §7.4): fixtures BN-1..BN-5 and SC-3. */
class CachedTabularBinNumericExecutorTest {

    CachedTabularBinNumericExecutorTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    static InfoTable numericTable(String column, double... values) {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName(column);
        fd.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        for (double v : values) {
            ValueCollection row = new ValueCollection();
            row.put(column, new NumberPrimitive(v));
            src.addRow(row);
        }
        return src;
    }

    static JsonNode run(String conversationId, InfoTable src, String argsWithoutCache) throws Exception {
        AgentToolContext.setConversationId(conversationId);
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(src);
        String args = "{\"cacheId\":\"" + cid + "\"," + argsWithoutCache + "}";
        return MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("bn", "tabulate_cached_result", args)));
    }

    private static List<Integer> counts(JsonNode root) {
        return java.util.stream.StreamSupport.stream(root.get("rows").spliterator(), false)
                .map(r -> r.get("count").asInt()).toList();
    }

    @Test
    void bn1_boundaryValuesCountOnceAndLastBinIncludesRightEndpoint() throws Exception {
        JsonNode root = run("bn-1", numericTable("v", 0, 1, 1, 2, 2, 2, 3),
                "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0,1,2,3]");
        assertEquals("success", root.get("status").asText(), root.toString());
        assertEquals("CACHED_BIN_NUMERIC_INLINE", root.get("resultKind").asText());
        assertEquals(List.of(1, 2, 4), counts(root));
        assertEquals(7, root.get("validCount").asInt());
        assertEquals(7, root.get("inRangeCount").asInt());
        assertEquals("explicit_edges_v1", root.get("method").asText());
        assertEquals(List.of("binIndex", "binStart", "binEnd", "count", "density", "validCount", "excludedCount",
                "belowRangeCount", "aboveRangeCount", "method"),
                java.util.stream.StreamSupport.stream(root.get("columns").spliterator(), false)
                        .map(c -> c.get("name").asText()).toList());
        JsonNode last = root.get("rows").get(2);
        assertEquals(2, last.get("binIndex").asInt());
        assertEquals(2.0, last.get("binStart").asDouble(), 0.0);
        assertEquals(3.0, last.get("binEnd").asDouble(), 0.0);
        assertEquals("explicit_edges_v1", last.get("method").asText());
        assertEquals(7, last.get("validCount").asInt(), "scalars repeat per row");
        assertNotNull(root.get("cacheId"), "the INLINE result is its own derived cache");
        assertTrue(root.get("answerSetComplete").asBoolean());
        assertEquals(3, root.get("totalRows").asInt());
        assertEquals(3, root.get("returnedRows").asInt());
        assertFalse(root.get("sampleOnly").asBoolean());
        assertFalse(root.get("rowsOmitted").asBoolean());
        assertEquals(7, root.path("counts").path("rowsRead").asInt());
        assertEquals(3, root.path("counts").path("rowsOutput").asInt());
        assertEquals("UNKNOWN", root.path("completeness").path("status").asText(),
                "the small output never upgrades the input's completeness");
    }

    @Test
    void bn2_unequalWidthDensityIntegratesToOne() throws Exception {
        JsonNode root = run("bn-2", numericTable("v", 0.5, 0.7, 1.5, 2, 2.5, 4, 6, 8, 9, 9.9),
                "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0,1,3,10]");
        assertEquals("success", root.get("status").asText(), root.toString());
        double integral = 0;
        int sum = 0;
        for (JsonNode r : root.get("rows")) {
            double width = r.get("binEnd").asDouble() - r.get("binStart").asDouble();
            integral += r.get("density").asDouble() * width;
            sum += r.get("count").asInt();
            assertEquals(r.get("count").asDouble() / (10.0 * width), r.get("density").asDouble(), 1e-12);
        }
        assertEquals(1.0, integral, 1e-12);
        assertEquals(10, sum);
    }

    @Test
    void bn3_equalWidthEdgesEndExactlyAtMaxAndConstantColumnGetsOneBin() throws Exception {
        JsonNode root = run("bn-3a", numericTable("v", 10, 12, 13, 17, 20),
                "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binCount\":4");
        assertEquals("success", root.get("status").asText(), root.toString());
        assertEquals("equal_width_v1", root.get("method").asText());
        assertEquals(4, root.get("rows").size());
        double[] expected = {10, 12.5, 15, 17.5, 20};
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i], root.get("rows").get(i).get("binStart").asDouble(), 0.0);
            assertEquals(expected[i + 1], root.get("rows").get(i).get("binEnd").asDouble(), 0.0);
        }
        assertEquals(20.0, root.get("rows").get(3).get("binEnd").asDouble(), 0.0, "the last edge is the maximum itself");
        assertEquals(List.of(2, 1, 1, 1), counts(root), "the maximum falls into the last bin");
        JsonNode constant = run("bn-3b", numericTable("v", 5, 5, 5),
                "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binCount\":4");
        assertEquals(1, constant.get("rows").size());
        assertEquals(4.5, constant.get("rows").get(0).get("binStart").asDouble(), 0.0);
        assertEquals(5.5, constant.get("rows").get(0).get("binEnd").asDouble(), 0.0);
        assertEquals(3, constant.get("rows").get(0).get("count").asInt());
        assertEquals(1.0, constant.get("rows").get(0).get("density").asDouble(), 1e-12);
        JsonNode ranged = run("bn-3c", numericTable("v", 1, 2, 3),
                "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binCount\":2,\"rangeMin\":0,\"rangeMax\":4");
        assertEquals(0.0, ranged.get("rows").get(0).get("binStart").asDouble(), 0.0);
        assertEquals(4.0, ranged.get("rows").get(1).get("binEnd").asDouble(), 0.0);
        assertEquals(List.of(1, 2), counts(ranged), "2 sits on the middle edge and counts once in the upper bin");
    }

    @Test
    void bn4_excludedAndOutOfRangeValuesAreCountedButNeverBinned() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("v");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        InfoTable src = new InfoTable(shape);
        for (String s : new String[] {"1", "2", "-5", "40", "7"}) {
            ValueCollection row = new ValueCollection();
            row.put("v", new StringPrimitive(s));
            src.addRow(row);
        }
        JsonNode typeMismatch = run("bn-4a", withText(src, "abc"), "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0,5,10]");
        assertEquals("TYPE_MISMATCH", typeMismatch.get("code").asText(),
                "a STRING column that is not uniformly numeric is rejected like group_metric measures");
        InfoTable numeric = numericTable("v", 1, 2, -5, 40, 7, Double.NaN, Double.POSITIVE_INFINITY);
        ValueCollection nullRow = new ValueCollection();
        nullRow.put("v", null);
        numeric.addRow(nullRow);
        JsonNode root = run("bn-4b", numeric, "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0,5,10]");
        assertEquals("success", root.get("status").asText(), root.toString());
        assertEquals(5, root.get("validCount").asInt(), "finite values, including out-of-range ones");
        assertEquals(3, root.get("excludedCount").asInt(), "null, NaN and infinity are excluded");
        assertEquals(1, root.get("belowRangeCount").asInt());
        assertEquals(1, root.get("aboveRangeCount").asInt());
        assertEquals(List.of(2, 1), counts(root));
        assertEquals(3, root.get("inRangeCount").asInt(), "Σcount = validCount − below − above");
        JsonNode allOutside = run("bn-4c", numericTable("v", 100, 200), "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0,5,10]");
        assertEquals("CACHED_BIN_NUMERIC_EMPTY", allOutside.get("resultKind").asText());
        assertEquals(0, allOutside.get("totalRows").asInt());
        assertEquals(2, allOutside.get("aboveRangeCount").asInt());
        assertEquals(2, allOutside.get("validCount").asInt());
        assertFalse(allOutside.has("cacheId"), "an EMPTY result has no derived cache");
        JsonNode noValues = run("bn-4d", numericTable("v"), "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binCount\":3");
        assertEquals("CACHED_BIN_NUMERIC_EMPTY", noValues.get("resultKind").asText());
    }

    private static InfoTable withText(InfoTable src, String extra) {
        ValueCollection row = new ValueCollection();
        row.put("v", new StringPrimitive(extra));
        src.addRow(row);
        return src;
    }

    @Test
    void bn5_parameterErrorsAreInvalidParameters() throws Exception {
        InfoTable src = numericTable("v", 1, 2, 3);
        Map<String, String> cases = Map.of(
                "both", "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0,1],\"binCount\":2",
                "neither", "\"mode\":\"bin_numeric\",\"column\":\"v\"",
                "nonIncreasing", "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0,2,2,3]",
                "tooManyBins", "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binCount\":51",
                "zeroBins", "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binCount\":0",
                "rangeInverted", "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binCount\":2,\"rangeMin\":5,\"rangeMax\":5",
                "rangeWithEdges", "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0,1],\"rangeMin\":0",
                "groupBy", "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binCount\":2,\"groupBy\":[\"v\"]",
                "oneEdge", "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0]",
                "fields", "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binCount\":2,\"fields\":[\"count\"]");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            JsonNode root = run("bn-5-" + c.getKey(), src, c.getValue());
            assertEquals("error", root.get("status").asText(), c.getKey() + ": " + root);
            assertEquals("INVALID_PARAMETERS", root.get("code").asText(), c.getKey());
        }
        JsonNode missing = run("bn-5-missing", src, "\"mode\":\"bin_numeric\",\"binCount\":2");
        assertEquals("INVALID_PARAMETERS", missing.get("code").asText());
        JsonNode unknown = run("bn-5-unknown", src, "\"mode\":\"bin_numeric\",\"column\":\"nope\",\"binCount\":2");
        assertEquals("INVALID_COLUMN", unknown.get("code").asText());
    }

    @Test
    void filtersApplyBeforeBinning() throws Exception {
        JsonNode root = run("bn-f", numericTable("v", 1, 2, 3, 4, 5, 6),
                "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binEdges\":[0,3,6],"
                        + "\"filters\":{\"type\":\"GT\",\"fieldName\":\"v\",\"value\":2}");
        assertEquals("success", root.get("status").asText(), root.toString());
        assertEquals(4, root.get("validCount").asInt(), "rows failing the filter are neither valid nor excluded");
        assertEquals(List.of(0, 4), counts(root));
    }

    @Test
    void sc3_oneDayAtOneHertzSucceedsAndTheDecisionBudgetsApply() throws Exception {
        InfoTable day = numericTable("v");
        for (int i = 0; i < 86_400; i++) {
            ValueCollection row = new ValueCollection();
            row.put("v", new NumberPrimitive(Math.sin(i / 1000.0) * 10));
            day.addRow(row);
        }
        JsonNode ok = run("sc-3a", day, "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binCount\":50");
        assertEquals("success", ok.get("status").asText(), ok.toString());
        assertEquals(50, ok.get("rows").size());
        assertEquals(86_400, ok.get("validCount").asInt());
        assertEquals(86_400, ok.get("inRangeCount").asInt());
        InfoTable tooManyRows = numericTable("v");
        for (int i = 0; i < CachedTabularToolsExecutor.MAX_SCANNED_ROWS_DECISION + 1; i++) {
            ValueCollection row = new ValueCollection();
            row.put("v", new NumberPrimitive(1.0));
            tooManyRows.addRow(row);
        }
        JsonNode tooLarge = run("sc-3b", tooManyRows, "\"mode\":\"bin_numeric\",\"column\":\"v\",\"binCount\":5");
        assertEquals("SOURCE_TOO_LARGE", tooLarge.get("code").asText());
        DataShapeDefinition wide = new DataShapeDefinition();
        for (int c = 0; c < 2_001; c++) {
            FieldDefinition fd = new FieldDefinition();
            fd.setName("c" + c);
            fd.setBaseType(BaseTypes.NUMBER);
            wide.addFieldDefinition(fd);
        }
        InfoTable tooManyCells = new InfoTable(wide);
        for (int i = 0; i < 1_000; i++) {
            ValueCollection row = new ValueCollection();
            row.put("c0", new NumberPrimitive(1.0));
            tooManyCells.addRow(row);
        }
        // The artifact store's own IO limit refuses to cache a table this wide, so the executor's cell
        // budget, which every decision mode including the two D1 modes passes through, is asserted directly.
        CachedTabularDecisionToolException cells = org.junit.jupiter.api.Assertions.assertThrows(
                CachedTabularDecisionToolException.class,
                () -> CachedTabularToolsExecutor.assertDecisionInputSize(tooManyCells));
        assertEquals("TABLE_TOO_LARGE_FOR_TRANSFORM", cells.code);
        assertTrue(tooManyCells.getRowCount() * 2_001L > CachedTabularToolsExecutor.MAX_CELLS_FOR_TABULAR_TRANSFORM);
    }

    @Test
    void schemaAdvertisesTheDistributionModesAndTheirParameters() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) TabulateCachedResultToolSchema.parametersSchema()
                .get("properties");
        @SuppressWarnings("unchecked")
        List<String> modes = (List<String>) ((Map<String, Object>) props.get("mode")).get("enum");
        assertTrue(modes.contains("bin_numeric"));
        assertTrue(modes.contains("box_summary"));
        for (String p : List.of("column", "binEdges", "binCount", "rangeMin", "rangeMax")) {
            assertTrue(props.containsKey(p), p);
        }
        String modeDesc = String.valueOf(((Map<?, ?>) props.get("mode")).get("description"));
        assertTrue(modeDesc.contains("bin_numeric:") && modeDesc.contains("box_summary:"));
        assertTrue(TabulateCachedResultToolSchema.BASE_MODES.contains("bin_numeric"));
    }
}
