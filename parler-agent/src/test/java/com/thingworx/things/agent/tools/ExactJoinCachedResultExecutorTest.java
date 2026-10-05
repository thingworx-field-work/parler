package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.join.DemoExactJoinAppProfile;
import com.thingworx.things.agent.join.JoinType;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

class ExactJoinCachedResultExecutorTest {

    ExactJoinCachedResultExecutorTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("tqj5-exact-join-exec");
        U4OperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        U4OperationAdmission.resetForTests();
    }

    @Test
    void invalidJoinType_failsFast_noFindingCacheId() throws Exception {
        String leftId = storeLeft();
        String rightId = storeRight();
        for (String bad : new String[] {"banana", "RIGHT", "FULL", "LEFTT"}) {
            JsonNode out = MAPPER.readTree(ExactJoinCachedResultExecutor.execute(call(
                    "{\"leftCacheId\":\"" + leftId + "\",\"rightCacheId\":\"" + rightId
                            + "\",\"joinType\":\"" + bad + "\"}")));
            assertEquals("ERROR", out.path("status").asText(), bad);
            assertEquals(ExactJoinCachedResultExecutor.JOIN_TYPE_INVALID, out.path("reason").asText(), bad);
            assertFalse(out.has("findingCacheId"), bad);
            assertFalse(out.path("mayPublish").asBoolean(true), bad);
        }
        // Whitespace-padded INNER is accepted after trim.
        JsonNode okInner = MAPPER.readTree(ExactJoinCachedResultExecutor.execute(call(
                "{\"leftCacheId\":\"" + leftId + "\",\"rightCacheId\":\"" + rightId
                        + "\",\"joinType\":\"  INNER  \"}")));
        assertEquals("OK", okInner.path("status").asText());
        assertTrue(okInner.hasNonNull("findingCacheId"));
    }

    @Test
    void missingJoinType_defaultsToInner() throws Exception {
        assertEquals(JoinType.INNER,
                ExactJoinCachedResultExecutor.resolveConfig(MAPPER.readTree("{}")).joinType());
        assertEquals(DemoExactJoinAppProfile.PROFILE_DIGEST,
                ExactJoinCachedResultExecutor.resolveConfig(MAPPER.readTree("{}")).profileDigest());
    }

    @Test
    void explicitInnerAndLeft_accepted() throws Exception {
        assertEquals(JoinType.INNER,
                ExactJoinCachedResultExecutor.resolveConfig(MAPPER.readTree("{\"joinType\":\"inner\"}"))
                        .joinType());
        assertEquals(JoinType.LEFT,
                ExactJoinCachedResultExecutor.resolveConfig(MAPPER.readTree("{\"joinType\":\"Left\"}"))
                        .joinType());
        JsonNode banana = MAPPER.readTree("{\"joinType\":\"banana\"}");
        assertThrows(IllegalArgumentException.class,
                () -> ExactJoinCachedResultExecutor.resolveConfig(banana));
    }

    @Test
    void missingCacheIds_useArgumentMissingNotJoinKeyMissing() throws Exception {
        JsonNode out = MAPPER.readTree(ExactJoinCachedResultExecutor.execute(call("{}")));
        assertEquals("ERROR", out.path("status").asText());
        assertEquals(ExactJoinCachedResultExecutor.ARGUMENT_MISSING, out.path("reason").asText());
        assertFalse(out.has("findingCacheId"));
    }

    private static ToolCall call(String args) {
        return new ToolCall("1", ExactJoinCachedResultExecutor.TOOL_NAME, args);
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
