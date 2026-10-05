package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.types.BaseTypes;
import org.junit.jupiter.api.Test;

/**
 * Offline tests for {@code invoke_service} top-level → {@code parameters} repair.
 */
class InvokeServiceParameterNormalizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static FieldDefinitionCollection singleRequiredInt(String name) {
        FieldDefinitionCollection coll = new FieldDefinitionCollection();
        coll.addFieldDefinition(new FieldDefinition(name, "", BaseTypes.INTEGER));
        return coll;
    }

    @Test
    void movesDeclaredServiceParameterFromTopLevelIntoParameters() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"entityType\":\"Thing\",\"entityName\":\"X\",\"serviceName\":\"GetDataTableEntries\",\"maxItems\":3}");
        FieldDefinitionCollection defs = singleRequiredInt("maxItems");
        InvokeServiceParameterNormalizer.Repair r =
                InvokeServiceParameterNormalizer.mergeTopLevelFromParameterDefs(root, defs, MAPPER);
        assertTrue(r.isRepaired());
        assertEquals(1, r.getMovedFromTopLevel().size());
        assertEquals("maxItems", r.getMovedFromTopLevel().get(0));
        assertEquals(3, root.path("parameters").path("maxItems").asInt());
        assertFalse(root.has("maxItems"));
    }

    @Test
    void repairIsCaseSensitiveDoesNotMoveDifferentlyCasedTopLevelField() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"entityType\":\"Thing\",\"entityName\":\"X\",\"serviceName\":\"GetDataTableEntries\",\"MaxItems\":3}");
        FieldDefinitionCollection defs = singleRequiredInt("maxItems");
        InvokeServiceParameterNormalizer.Repair r =
                InvokeServiceParameterNormalizer.mergeTopLevelFromParameterDefs(root, defs, MAPPER);
        assertFalse(r.isRepaired());
        assertTrue(root.has("MaxItems"));
        assertFalse(root.has("parameters"));
    }

    @Test
    void doesNotMoveUnknownTopLevelFields() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"entityType\":\"Thing\",\"entityName\":\"X\",\"serviceName\":\"S\",\"foo\":1,\"maxItems\":2}");
        FieldDefinitionCollection defs = singleRequiredInt("maxItems");
        InvokeServiceParameterNormalizer.Repair r =
                InvokeServiceParameterNormalizer.mergeTopLevelFromParameterDefs(root, defs, MAPPER);
        assertTrue(r.isRepaired());
        assertTrue(root.has("foo"));
        assertEquals(2, root.path("parameters").path("maxItems").asInt());
    }

    @Test
    void nestedParametersValueWinsOverTopLevel() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                "{\"entityType\":\"Thing\",\"entityName\":\"X\",\"serviceName\":\"S\","
                        + "\"parameters\":{\"maxItems\":5},\"maxItems\":3}");
        FieldDefinitionCollection defs = singleRequiredInt("maxItems");
        InvokeServiceParameterNormalizer.Repair r =
                InvokeServiceParameterNormalizer.mergeTopLevelFromParameterDefs(root, defs, MAPPER);
        assertFalse(r.isRepaired());
        assertEquals(5, root.path("parameters").path("maxItems").asInt());
        assertFalse(root.has("maxItems"));
    }

    @Test
    void reservedKeysDetected() {
        assertTrue(InvokeServiceParameterNormalizer.isReservedInvokeArgumentKey("entityName"));
    }
}
