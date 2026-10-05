package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.analysis.U5OperationAdmission;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;

/** CM-4: analyze results echo the operands actually used; identity comes from the cache descriptor or is omitted. */
class AnalyzeSourcesEchoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
        AgentToolContext.setConversationId("analyze-sources-echo");
        U5OperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        U5OperationAdmission.resetForTests();
    }

    private static InfoTable table(long offsetMillis, int rows, String... valueNames) {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition ts = new FieldDefinition();
        ts.setName("ts");
        ts.setBaseType(BaseTypes.DATETIME);
        shape.addFieldDefinition(ts);
        for (String n : valueNames) {
            FieldDefinition f = new FieldDefinition();
            f.setName(n);
            f.setBaseType(BaseTypes.NUMBER);
            shape.addFieldDefinition(f);
        }
        InfoTable t = new InfoTable(shape);
        for (int i = 0; i < rows; i++) {
            ValueCollection row = new ValueCollection();
            row.put("ts", new DatetimePrimitive(new DateTime(T0.plusSeconds(i).toEpochMilli() + offsetMillis)));
            for (int k = 0; k < valueNames.length; k++) {
                row.put(valueNames[k], new NumberPrimitive(10 + Math.sin(i * (k + 1.0)) + 0.1 * i * (k + 1)));
            }
            t.addRow(row);
        }
        return t;
    }

    private static String storeIdentified(InfoTable t, String thing, String property) throws Exception {
        return NumericHistoryCacheWriter.storeWithRoles(t, "test.producer", "ts", firstNumber(t), thing, property, null)
                .cacheId();
    }

    private static String firstNumber(InfoTable t) {
        for (FieldDefinition f : t.getDataShape().getFields().getOrderedFieldsByOrdinal()) {
            if (f.getBaseType() == BaseTypes.NUMBER) {
                return f.getName();
            }
        }
        throw new IllegalStateException("no number column");
    }

    private static JsonNode run(String args) throws Exception {
        return MAPPER.readTree(AnalyzeCachedResultExecutor.execute(
                new ToolCall("c1", AnalyzeCachedResultExecutor.TOOL_NAME, args)));
    }

    private static String rel(String left, String right, String leftCol, String rightCol, String alignment) {
        return "{\"operation\":\"relationship\",\"cacheId\":\"" + left + "\",\"rightCacheId\":\"" + right
                + "\",\"timeColumn\":\"ts\",\"valueColumn\":\"" + leftCol
                + (rightCol == null ? "" : "\",\"rightValueColumn\":\"" + rightCol)
                + "\",\"alignment\":\"" + alignment + "\",\"toleranceMillis\":1000}";
    }

    @Test
    void relationship_echoesBothOperandsWithIdentity() throws Exception {
        String left = storeIdentified(table(0, 12, "v"), "SE.Thing.A", "voltage");
        String right = storeIdentified(table(0, 12, "w"), "SE.Thing.B", "force");
        JsonNode out = run(rel(left, right, "v", "w", "nearest"));
        assertEquals("OK", out.path("status").asText(), out.toString());
        JsonNode sources = out.path("sources");
        assertEquals(2, sources.size());
        assertEquals("left", sources.get(0).path("role").asText());
        assertEquals(left, sources.get(0).path("cacheId").asText());
        assertEquals("ts", sources.get(0).path("timeColumn").asText());
        assertEquals("v", sources.get(0).path("valueColumn").asText());
        assertEquals("SE.Thing.A", sources.get(0).path("thingName").asText());
        assertEquals("voltage", sources.get(0).path("propertyName").asText());
        assertEquals("right", sources.get(1).path("role").asText());
        assertEquals(right, sources.get(1).path("cacheId").asText());
        assertEquals("w", sources.get(1).path("valueColumn").asText());
        assertEquals("SE.Thing.B", sources.get(1).path("thingName").asText());
        assertEquals("force", sources.get(1).path("propertyName").asText());
        // sourceCacheIds stays the unique set (B1), sources[] the operand list (CM-4).
        assertEquals(2, out.path("analysisEnvelope").path("sourceCacheIds").size());
    }

    @Test
    void sameCacheBothSides_twoRowsSameIdDifferentColumns() throws Exception {
        String one = NumericHistoryCacheWriter.storeWithRoles(table(0, 12, "v", "w"), "test.producer", "ts", "v",
                "SE.Thing.A", "voltage", null).cacheId();
        JsonNode out = run(rel(one, one, "v", "w", "nearest"));
        JsonNode sources = out.path("sources");
        assertEquals(2, sources.size());
        assertEquals(one, sources.get(0).path("cacheId").asText());
        assertEquals(one, sources.get(1).path("cacheId").asText());
        assertEquals("v", sources.get(0).path("valueColumn").asText());
        assertEquals("w", sources.get(1).path("valueColumn").asText());
        assertEquals(1, out.path("analysisEnvelope").path("sourceCacheIds").size());
    }

    @Test
    void inheritedRightColumn_isEchoedAsUsed_andZeroMatchKeepsBothRows() throws Exception {
        String left = storeIdentified(table(0, 12, "v"), "SE.Thing.A", "voltage");
        String right = storeIdentified(table(500, 12, "v"), "SE.Thing.B", "force");
        JsonNode out = run(rel(left, right, "v", null, "exact"));
        assertEquals("INSUFFICIENT_EVIDENCE", out.path("reason").asText(), out.toString());
        JsonNode sources = out.path("sources");
        assertEquals(2, sources.size());
        assertEquals("v", sources.get(1).path("valueColumn").asText(), "inherited rightValueColumn as actually used");
        assertEquals("SE.Thing.B", sources.get(1).path("thingName").asText());
    }

    @Test
    void unaryOperations_emitOneLeftRow_includingNoFinding() throws Exception {
        String one = storeIdentified(table(0, 24, "v"), "SE.Thing.A", "voltage");
        JsonNode outlier = run("{\"operation\":\"outlier\",\"cacheId\":\"" + one
                + "\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\"}");
        assertEquals("OK", outlier.path("status").asText(), outlier.toString());
        assertTrue(List.of("NO_FINDING", "SUCCESS").contains(outlier.path("reason").asText()), outlier.toString());
        assertEquals(1, outlier.path("sources").size());
        assertEquals("left", outlier.path("sources").get(0).path("role").asText());
        assertEquals("SE.Thing.A", outlier.path("sources").get(0).path("thingName").asText());

        JsonNode trend = run("{\"operation\":\"trend\",\"cacheId\":\"" + one
                + "\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\"}");
        assertEquals("OK", trend.path("status").asText(), trend.toString());
        assertEquals(1, trend.path("sources").size());
        assertEquals("left", trend.path("sources").get(0).path("role").asText());
    }

    @Test
    void unidentifiedCache_rowPresentWithoutIdentityKeys() throws Exception {
        String plain = TabularArtifactHub.store(table(0, 12, "v"),
                com.thingworx.things.agent.source.SourceDescriptor.builder().sourceRouteId("test").build());
        JsonNode out = run("{\"operation\":\"outlier\",\"cacheId\":\"" + plain
                + "\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\"}");
        JsonNode row = out.path("sources").get(0);
        assertEquals(plain, row.path("cacheId").asText());
        assertFalse(row.has("thingName"));
        assertFalse(row.has("propertyName"));
    }

    @Test
    void projectionError_shapeUnchanged_noSources() throws Exception {
        String one = storeIdentified(table(0, 12, "v"), "SE.Thing.A", "voltage");
        JsonNode err = run("{\"operation\":\"outlier\",\"cacheId\":\"" + one
                + "\",\"timeColumn\":\"ts\",\"valueColumn\":\"nope\"}");
        assertEquals("ERROR", err.path("status").asText());
        assertFalse(err.has("sources"));
        assertTrue(err.has("schema"));
    }
}
