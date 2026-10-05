package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * TQJ-5 Option A: {@code tabulate_cached_result} {@code mode=exact_join} shares
 * {@link ExactJoinCachedResultExecutor} / runner / admission vocabulary.
 */
class TabulateExactJoinModeTest {

    TabulateExactJoinModeTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("tqj5-tabulate-exact-join");
        U4OperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        U4OperationAdmission.resetForTests();
    }

    @Test
    void advertisedModeEnumIncludesExactJoinWhenAdmitted() {
        assertTrue(TabulateCachedResultToolSchema.advertisedModes()
                .contains(TabulateCachedResultToolSchema.MODE_EXACT_JOIN));
        @SuppressWarnings("unchecked")
        Map<String, Object> mode = (Map<String, Object>) ((Map<String, Object>) TabulateCachedResultToolSchema
                .parametersSchema().get("properties")).get("mode");
        @SuppressWarnings("unchecked")
        List<String> enumVals = (List<String>) mode.get("enum");
        assertTrue(enumVals.contains("exact_join"), enumVals.toString());
    }

    @Test
    void admissionDisabled_withdrawsExactJoinFromAdvertisedEnum() throws Exception {
        U4OperationAdmission.setExactJoinEnabled(false);
        assertFalse(TabulateCachedResultToolSchema.advertisedModes()
                .contains(TabulateCachedResultToolSchema.MODE_EXACT_JOIN));
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition tabulate = reg.getAllDefinitions().stream()
                .filter(d -> "tabulate_cached_result".equals(d.getName()))
                .findFirst()
                .orElseThrow();
        String surface = MAPPER.writeValueAsString(tabulate.getParametersSchema());
        assertFalse(surface.contains("exact_join"), surface);
        assertFalse(tabulate.getDescription().contains("exact_join"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) tabulate.getParametersSchema().get("properties");
        assertFalse(props.containsKey("rightCacheId"));
        assertFalse(props.containsKey("joinType"));
    }

    @Test
    void modeExactJoin_successSharesExecutorVocabulary() throws Exception {
        String leftId = storeLeft();
        String rightId = storeRight();
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                call("{\"cacheId\":\"" + leftId + "\",\"mode\":\"exact_join\",\"rightCacheId\":\""
                        + rightId + "\",\"joinType\":\"INNER\"}")));
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertTrue(out.hasNonNull("findingCacheId"), out.toString());
        assertTrue(out.path("mayPublish").asBoolean(false));
        assertTrue(out.has("analysisEnvelope"));
    }

    @Test
    void modeExactJoin_invalidJoinType_failsFast() throws Exception {
        String leftId = storeLeft();
        String rightId = storeRight();
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                call("{\"cacheId\":\"" + leftId + "\",\"mode\":\"exact_join\",\"rightCacheId\":\""
                        + rightId + "\",\"joinType\":\"FULL\"}")));
        assertEquals("ERROR", out.path("status").asText());
        assertEquals(ExactJoinCachedResultExecutor.JOIN_TYPE_INVALID, out.path("reason").asText());
        assertFalse(out.has("findingCacheId"));
        assertFalse(out.path("mayPublish").asBoolean(true));
    }

    @Test
    void modeExactJoin_admissionDisabled_rejectsStableReason() throws Exception {
        String leftId = storeLeft();
        String rightId = storeRight();
        U4OperationAdmission.setExactJoinEnabled(false);
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                call("{\"cacheId\":\"" + leftId + "\",\"mode\":\"exact_join\",\"rightCacheId\":\""
                        + rightId + "\"}")));
        assertEquals("ERROR", out.path("status").asText());
        assertEquals(U4OperationAdmission.EXACT_JOIN_UNAVAILABLE, out.path("reason").asText());
        assertFalse(out.has("findingCacheId"));
    }

    @Test
    void modeExactJoin_missingRightCacheId_argumentMissing() throws Exception {
        String leftId = storeLeft();
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                call("{\"cacheId\":\"" + leftId + "\",\"mode\":\"exact_join\"}")));
        assertEquals("ERROR", out.path("status").asText());
        assertEquals(ExactJoinCachedResultExecutor.ARGUMENT_MISSING, out.path("reason").asText());
    }

    private static ToolCall call(String args) {
        return new ToolCall("1", "tabulate_cached_result", args);
    }

    private static String storeLeft() throws Exception {
        return TabularArtifactHub.store(table("id", "val", true), complete());
    }

    private static String storeRight() throws Exception {
        return TabularArtifactHub.store(table("id", "name", false), complete());
    }

    private static SourceDescriptor complete() {
        return SourceDescriptor.builder()
                .completenessStatus(CompletenessStatus.COMPLETE)
                .rowsExamined(1L)
                .rowsReturned(1L)
                .build();
    }

    private static InfoTable table(String c1, String c2, boolean numericSecond) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition a = new FieldDefinition();
        a.setName(c1);
        a.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(a);
        FieldDefinition b = new FieldDefinition();
        b.setName(c2);
        b.setBaseType(numericSecond ? BaseTypes.NUMBER : BaseTypes.STRING);
        shape.addFieldDefinition(b);
        InfoTable table = new InfoTable(shape);
        ValueCollection vc = new ValueCollection();
        vc.put(c1, new StringPrimitive("k0"));
        if (numericSecond) {
            vc.put(c2, new NumberPrimitive(1d));
        } else {
            vc.put(c2, new StringPrimitive("n0"));
        }
        table.addRow(vc);
        return table;
    }
}
