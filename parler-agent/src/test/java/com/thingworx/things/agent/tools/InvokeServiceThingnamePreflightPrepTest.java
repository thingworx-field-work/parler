package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Phase C: {@code invoke_service} Thing targets — {@link ServiceTargetEntityTypeResolver#prepareInvokeServiceToolCall}
 * and {@link InvokeServiceExecutor#executeInvokeService}.
 */
class InvokeServiceThingnamePreflightPrepTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
    }

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
        AgentToolContext.clear();
    }

    @Test
    void thing_target_non_visible_entityName_returns_identity_resolution_required() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"ORD Contacting 01\",\"serviceName\":\"GetPropertyValues\",\"parameters\":{}}");
        ServiceTargetEntityTypeResolver.InvokeServiceToolPrep prep =
                ServiceTargetEntityTypeResolver.prepareInvokeServiceToolCall(call, null);
        assertNotNull(prep.getEarlyErrorJson());
        JsonNode n = MAPPER.readTree(prep.getEarlyErrorJson());
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("entityName", n.path("parameterName").asText());
        assertEquals("ORD Contacting 01", n.path("suppliedValue").asText());
        assertEquals("resolve_thing", n.path("recoveryHint").path("tool").asText());
    }

    @Test
    void thing_target_whitespace_only_entityName_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"  \\t  \",\"serviceName\":\"GetPropertyValues\",\"parameters\":{}}");
        ServiceTargetEntityTypeResolver.InvokeServiceToolPrep prep =
                ServiceTargetEntityTypeResolver.prepareInvokeServiceToolCall(call, null);
        assertNotNull(prep.getEarlyErrorJson());
        JsonNode n = MAPPER.readTree(prep.getEarlyErrorJson());
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("entityName", n.path("parameterName").asText());
    }

    @Test
    void thing_target_identity_omits_recovery_hint_when_resolve_thing_not_recoverable() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = false;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"label\",\"serviceName\":\"GetPropertyValues\",\"parameters\":{}}");
        ServiceTargetEntityTypeResolver.InvokeServiceToolPrep prep =
                ServiceTargetEntityTypeResolver.prepareInvokeServiceToolCall(call, null);
        assertNotNull(prep.getEarlyErrorJson());
        JsonNode n = MAPPER.readTree(prep.getEarlyErrorJson());
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertFalse(n.has("recoveryHint"));
        assertTrue(n.path("message").asText().contains("resolve_thing is not available"));
    }

    @Test
    void thing_execute_empty_entityName_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"\",\"serviceName\":\"GetPropertyValues\",\"parameters\":{}}");
        JsonNode n = MAPPER.readTree(InvokeServiceExecutor.executeInvokeService(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("entityName", n.path("parameterName").asText());
    }

    @Test
    void thing_execute_missing_entityName_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "invoke_service",
                "{\"entityType\":\"Thing\",\"serviceName\":\"GetPropertyValues\",\"parameters\":{}}");
        JsonNode n = MAPPER.readTree(InvokeServiceExecutor.executeInvokeService(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("entityName", n.path("parameterName").asText());
    }

    @Test
    void thing_execute_non_visible_entityName_returns_identity_resolution_required() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"Ghost\",\"serviceName\":\"GetPropertyValues\",\"parameters\":{}}");
        JsonNode n = MAPPER.readTree(InvokeServiceExecutor.executeInvokeService(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("entityName", n.path("parameterName").asText());
    }
}
