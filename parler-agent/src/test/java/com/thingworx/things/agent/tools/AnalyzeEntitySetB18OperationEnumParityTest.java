package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * B18: {@code analyze_entity_set.operation} is a JSON-Schema enum with bidirectional
 * schema/executor/contract parity.
 */
class AnalyzeEntitySetB18OperationEnumParityTest {

    AnalyzeEntitySetB18OperationEnumParityTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void advertisedOperationIsJsonSchemaEnumMatchingSharedConstant() {
        Map<String, Object> schema = AnalyzeEntitySetToolSchema.parametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> operation = (Map<String, Object>) props.get("operation");
        assertEquals("string", operation.get("type"));
        @SuppressWarnings("unchecked")
        List<String> enumVals = (List<String>) operation.get("enum");
        assertEquals(AnalyzeEntitySetToolSchema.OPERATIONS, enumVals);
        assertEquals(new LinkedHashSet<>(AnalyzeEntitySetToolSchema.OPERATIONS),
                new LinkedHashSet<>(enumVals));
        assertEquals(4, enumVals.size());
    }

    @Test
    void everyAdvertisedOperationIsExecutorAccepted_andUnknownRejected() throws Exception {
        AgentToolContext.setConversationId("b18-parity");
        String leftId = storeNameTable("a", "b");
        String rightId = storeNameTable("b", "c");
        for (String op : AnalyzeEntitySetToolSchema.OPERATIONS) {
            assertTrue(AnalyzeEntitySetToolSchema.isSupportedOperation(op), op);
            String json = AnalyzeEntitySetExecutor.execute(new ToolCall("t-" + op, "analyze_entity_set",
                    "{\"operation\":\"" + op + "\",\"left\":{\"cacheId\":\"" + leftId
                            + "\"},\"right\":{\"cacheId\":\"" + rightId + "\"}}"));
            JsonNode root = MAPPER.readTree(json);
            assertEquals("success", root.path("status").asText(), op + ": " + json);
            assertEquals(op, root.path("operation").asText(), op);
        }
        assertFalse(AnalyzeEntitySetToolSchema.isSupportedOperation("bogus_set_op"));
        String bad = AnalyzeEntitySetExecutor.execute(new ToolCall("t-bad", "analyze_entity_set",
                "{\"operation\":\"bogus_set_op\",\"left\":{\"cacheId\":\"" + leftId
                        + "\"},\"right\":{\"cacheId\":\"" + rightId + "\"}}"));
        JsonNode err = MAPPER.readTree(bad);
        assertEquals("error", err.path("status").asText());
        assertEquals("UNSUPPORTED_OPERATION", err.path("code").asText());
    }

    private static String storeNameTable(String... names) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition name = new FieldDefinition();
        name.setName("name");
        name.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(name);
        InfoTable t = new InfoTable(shape);
        for (String n : names) {
            ValueCollection row = new ValueCollection();
            row.put("name", new StringPrimitive(n));
            t.addRow(row);
        }
        return InvokeServiceExecutor.storeInfotableInConversationCache(t);
    }
}
