package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Phase C: {@code set_property_value} — {@link SetPropertyValueExecutor#gateSetPropertyValueForHitl} and execute path.
 */
class SetPropertyValueExecutorThingnamePreflightTest {

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
    void gate_hitl_non_visible_thingName_returns_identity_resolution_required() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "set_property_value",
                "{\"thingName\":\"ORD Contacting 01\",\"propertyName\":\"Temperature\",\"baseType\":\"NUMBER\",\"value\":1}");
        SetPropertyValueExecutor.SetPropertyHitlGate g = SetPropertyValueExecutor.gateSetPropertyValueForHitl(call);
        assertNotNull(g.earlyErrorJson());
        assertNull(g.gatedToolCall());
        JsonNode n = MAPPER.readTree(g.earlyErrorJson());
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("thingName", n.path("parameterName").asText());
        assertEquals("resolve_thing", n.path("recoveryHint").path("tool").asText());
    }

    @Test
    void gate_hitl_whitespace_only_thingName_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "set_property_value",
                "{\"thing_name\":\"   \\t  \",\"property_name\":\"Temperature\",\"base_type\":\"NUMBER\",\"value\":1}");
        SetPropertyValueExecutor.SetPropertyHitlGate g = SetPropertyValueExecutor.gateSetPropertyValueForHitl(call);
        assertNotNull(g.earlyErrorJson());
        JsonNode n = MAPPER.readTree(g.earlyErrorJson());
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("thingName", n.path("parameterName").asText());
    }

    @Test
    void gate_hitl_identity_omits_recovery_hint_when_resolve_thing_not_recoverable() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = false;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "set_property_value",
                "{\"thingName\":\"label\",\"propertyName\":\"Temperature\",\"baseType\":\"NUMBER\",\"value\":1}");
        SetPropertyValueExecutor.SetPropertyHitlGate g = SetPropertyValueExecutor.gateSetPropertyValueForHitl(call);
        assertNotNull(g.earlyErrorJson());
        JsonNode n = MAPPER.readTree(g.earlyErrorJson());
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertFalse(n.has("recoveryHint"));
        assertTrue(n.path("message").asText().contains("resolve_thing is not available"));
    }

    @Test
    void execute_approved_write_non_visible_returns_identity_resolution_required() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "set_property_value",
                "{\"thingName\":\"x\",\"propertyName\":\"Temperature\",\"baseType\":\"NUMBER\",\"value\":1}");
        JsonNode n = MAPPER.readTree(SetPropertyValueExecutor.executeApprovedWrite(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("thingName", n.path("parameterName").asText());
    }
}
