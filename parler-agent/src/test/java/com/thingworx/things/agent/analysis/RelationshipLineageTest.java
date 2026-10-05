package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.AnalyzeCachedResultExecutor;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;

/**
 * B1: a RELATIONSHIP envelope's {@code sourceCacheIds[]} records both operands (left, right, each once).
 * Lineage only — the fix does not decide whether the operands were the right assets.
 */
class RelationshipLineageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
        AgentToolContext.setConversationId("relationship-lineage");
        U5OperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        U5OperationAdmission.resetForTests();
    }

    /** ts DATETIME + the named NUMBER columns; row i has ts = T0 + i s (+ offsetMillis) and values i+1, 2i+1, … */
    private static String store(long offsetMillis, int rows, String... valueNames) throws Exception {
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
                row.put(valueNames[k], new NumberPrimitive((k + 1) * i + 1.0 + (k == 1 ? Math.sin(i) : 0)));
            }
            t.addRow(row);
        }
        return TabularArtifactHub.store(t, SourceDescriptor.builder().sourceRouteId("test").build());
    }

    private static JsonNode relationship(String left, String right, String leftCol, String rightCol, String alignment)
            throws Exception {
        String args = "{\"operation\":\"relationship\",\"cacheId\":\"" + left + "\",\"rightCacheId\":\"" + right
                + "\",\"timeColumn\":\"ts\",\"valueColumn\":\"" + leftCol + "\",\"rightValueColumn\":\"" + rightCol
                + "\",\"alignment\":\"" + alignment + "\",\"toleranceMillis\":1000}";
        return MAPPER.readTree(AnalyzeCachedResultExecutor.execute(
                new ToolCall("c1", AnalyzeCachedResultExecutor.TOOL_NAME, args)));
    }

    private static List<String> sources(JsonNode out) {
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode n : out.path("analysisEnvelope").path("sourceCacheIds")) {
            ids.add(n.asText());
        }
        return ids;
    }

    @Test
    void distinctCaches_listBothOperandsLeftThenRight() throws Exception {
        String left = store(0, 12, "v");
        String right = store(0, 12, "w");
        JsonNode out = relationship(left, right, "v", "w", "nearest");
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertEquals(List.of(left, right), sources(out));
    }

    @Test
    void sameCacheDifferentColumns_listsOneUniqueId() throws Exception {
        String one = store(0, 12, "v", "w");
        JsonNode out = relationship(one, one, "v", "w", "nearest");
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertEquals(List.of(one), sources(out), "one unique id; operand count is not the array length");
    }

    @Test
    void zeroMatches_stillRecordBothOperands() throws Exception {
        String left = store(0, 12, "v");
        String right = store(500, 12, "w"); // offset so exact alignment finds no pairs
        JsonNode out = relationship(left, right, "v", "w", "exact");
        assertEquals("INSUFFICIENT_EVIDENCE", out.path("reason").asText(), out.toString());
        assertEquals(List.of(left, right), sources(out));
    }

    @Test
    void singleInputOperation_unchanged() throws Exception {
        String one = store(0, 12, "v");
        JsonNode out = MAPPER.readTree(AnalyzeCachedResultExecutor.execute(new ToolCall("c1",
                AnalyzeCachedResultExecutor.TOOL_NAME,
                "{\"operation\":\"outlier\",\"cacheId\":\"" + one + "\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\"}")));
        assertTrue(out.path("status").asText().equals("OK"), out.toString());
        assertEquals(List.of(one), sources(out));
    }
}
