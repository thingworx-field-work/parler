package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code invoke_service} tool JSON Schema hardening. */
class InvokeServiceToolSchemaTest {

    @Test
    void invokeServiceSchemaHasAdditionalPropertiesFalseAndWrapperGuidance() {
        var def = InvokeServiceToolSchemaFragment.invokeServiceToolDefinition();
        assertEquals("invoke_service", def.getName());
        assertTrue(def.getDescription().contains("parameters"),
                "description should stress parameters nesting");
        Map<String, Object> schema = def.getParametersSchema();
        assertEquals("object", schema.get("type"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals(4, props.size());
        assertTrue(props.containsKey("entityType"));
        assertTrue(props.containsKey("entityName"));
        assertTrue(props.containsKey("serviceName"));
        assertTrue(props.containsKey("parameters"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pdesc = (Map<String, Object>) props.get("parameters");
        String d = (String) pdesc.get("description");
        assertTrue(d.contains("MUST be nested"));
    }

    @Test
    void invokeServiceDescriptionSteersFetchCachedVersusTabulate() {
        String d = InvokeServiceToolSchemaFragment.invokeServiceDescription();
        assertTrue(d.contains("tabulate_cached_result"), "should steer full-table work to tabulate/summarize");
        assertTrue(d.contains("fetch_cached_result"), "should mention fetch_cached_result paging");
    }
}
