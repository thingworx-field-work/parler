package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.ServiceDefinition;

/** Offline tests for {@link InvokeServiceNamedVtqProtection}. */
class InvokeServiceNamedVtqProtectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void blocked_nullEntity_returnsNull() {
        ObjectNode root = MAPPER.createObjectNode();
        ServiceDefinition sd = new ServiceDefinition("UpdatePropertyValues", "x");
        assertNull(InvokeServiceNamedVtqProtection.blockedMessageForPasswordNamedVtqWrite(null, "UpdatePropertyValues",
                sd, root));
    }

    @Test
    void redactNamedVtqRows_nullThing_noMask() {
        ArrayNode arr = MAPPER.createArrayNode();
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", "apiKey");
        row.put("value", "clear-secret");
        arr.add(row);
        InvokeServiceNamedVtqProtection.redactNamedVtqRowsInArray(arr, null);
        assertEquals("clear-secret", row.get("value").asText());
    }

    /**
     * Persistence path without resolved Thing does not apply PASSWORD masking — not PASSWORD-direct.
     */
    @Test
    void redactInvokeServiceValuesArrays_agentNull_leavesValuesUnchanged() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("entityType", "Thing");
        root.put("entityName", "SomeThing");
        root.put("serviceName", "UpdatePropertyValues");
        ArrayNode values = MAPPER.createArrayNode();
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", "apiKey");
        row.put("value", "clear-secret");
        values.add(row);
        ObjectNode params = MAPPER.createObjectNode();
        params.set("values", values);
        root.set("parameters", params);
        InvokeServiceNamedVtqProtection.redactInvokeServiceValuesArraysForPersistence(root, null);
        assertEquals("clear-secret", row.get("value").asText());
    }

    @Test
    void redactNamedVtqRows_missingName_noThing_noMask() {
        ArrayNode arr = MAPPER.createArrayNode();
        ObjectNode row = MAPPER.createObjectNode();
        row.put("value", "orphan");
        arr.add(row);
        /* Thing required for any mask; null thing => early return */
        InvokeServiceNamedVtqProtection.redactNamedVtqRowsInArray(arr, null);
        assertEquals("orphan", row.get("value").asText());
    }

    @Test
    void redactInvokeServiceValuesArrays_topLevel_agentNull_noMask() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode values = MAPPER.createArrayNode();
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", "x");
        row.put("value", "top-secret");
        values.add(row);
        root.set("values", values);
        InvokeServiceNamedVtqProtection.redactInvokeServiceValuesArraysForPersistence(root, null);
        assertEquals("top-secret", row.get("value").asText());
    }
}
