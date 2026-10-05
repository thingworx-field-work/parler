package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;

/**
 * TR-3: {@code query_property_history} refuses a property that does not exist (orchestration via the
 * after-gate seam; FOUND rows prove branch entry only — see §11.1).
 */
class PropertyToolsExecutorHistoryPropertyGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String THING = "MUC BenchScale 02";

    @AfterEach
    void tearDown() {
        PropertyToolsExecutor.clearPropertyDefinitionLookupOverrideForTests();
    }

    private JsonNode runAfterGate(String arguments) throws Exception {
        ToolCall call = new ToolCall("1", "query_property_history", arguments);
        String json = PropertyToolsExecutor.wrapHistoryFailure(
                () -> PropertyToolsExecutor.queryPropertyHistoryAfterGate(call, null, THING));
        return MAPPER.readTree(json);
    }

    @Test
    void absent_property_returns_property_not_found_with_recovery_hint() throws Exception {
        PropertyToolsExecutor.propertyDefinitionLookupOverrideForTests =
                (thing, propertyName) -> PropertyToolsExecutor.PropertyDefinitionOutcome.absent();
        JsonNode n = runAfterGate("{\"thingName\":\"" + THING + "\",\"propertyName\":\"contactForce\"}");
        assertEquals(PropertyToolsExecutor.CODE_PROPERTY_NOT_FOUND, n.path("code").asText());
        assertEquals(THING, n.path("thingName").asText());
        assertEquals("contactForce", n.path("propertyName").asText());
        assertEquals("discover_thing_members", n.path("recoveryHint").path("tool").asText());
        assertEquals("namePrefix", n.path("recoveryHint").path("argument").asText());
        assertTrue(n.path("message").asText().contains("No history was queried"));
    }

    @Test
    void definition_read_failure_returns_query_property_history_error_not_not_found() throws Exception {
        PropertyToolsExecutor.propertyDefinitionLookupOverrideForTests =
                (thing, propertyName) -> {
                    throw new RuntimeException("definitions boom");
                };
        JsonNode n = runAfterGate("{\"thingName\":\"" + THING + "\",\"propertyName\":\"temperature\"}");
        assertEquals("QUERY_PROPERTY_HISTORY_ERROR", n.path("code").asText());
        assertEquals("definitions boom", n.path("message").asText());
    }

    @Test
    void definitions_unavailable_returns_query_property_history_error() throws Exception {
        PropertyToolsExecutor.propertyDefinitionLookupOverrideForTests =
                (thing, propertyName) -> {
                    throw new IllegalStateException("property definitions unavailable for " + THING);
                };
        JsonNode n = runAfterGate("{\"thingName\":\"" + THING + "\",\"propertyName\":\"temperature\"}");
        assertEquals("QUERY_PROPERTY_HISTORY_ERROR", n.path("code").asText());
        assertTrue(n.path("message").asText().contains("property definitions unavailable"));
    }

    @Test
    void found_numeric_branch_entered_via_not_numeric_property_with_null_thing() throws Exception {
        PropertyToolsExecutor.propertyDefinitionLookupOverrideForTests =
                (thing, propertyName) -> PropertyToolsExecutor.PropertyDefinitionOutcome.found(BaseTypes.NUMBER);
        JsonNode n = runAfterGate("{\"thingName\":\"" + THING + "\",\"propertyName\":\"temperature\"}");
        assertEquals("NOT_NUMERIC_PROPERTY", n.path("code").asText());
    }

    @Test
    void found_string_outcome_is_non_numeric_for_dispatch() throws Exception {
        PropertyToolsExecutor.propertyDefinitionLookupOverrideForTests =
                (thing, propertyName) -> PropertyToolsExecutor.PropertyDefinitionOutcome.found(BaseTypes.STRING);
        PropertyToolsExecutor.PropertyDefinitionOutcome outcome =
                PropertyToolsExecutor.resolvePropertyDefinitionOutcome(null, "status", THING);
        assertFalse(outcome.isAbsent());
        assertFalse(outcome.isNumeric());
    }

    @Test
    void found_non_numeric_with_actions_returns_numeric_actions_unsupported_before_service() throws Exception {
        PropertyToolsExecutor.propertyDefinitionLookupOverrideForTests =
                (thing, propertyName) -> PropertyToolsExecutor.PropertyDefinitionOutcome.found(BaseTypes.STRING);
        JsonNode n = runAfterGate("{\"thingName\":\"" + THING + "\",\"propertyName\":\"status\","
                + "\"actions\":[\"MEAN\"]}");
        assertEquals("NUMERIC_ACTIONS_UNSUPPORTED_FOR_PROPERTY_TYPE", n.path("code").asText());
    }

    @Test
    void found_with_unreadable_base_type_is_non_numeric_for_dispatch() throws Exception {
        PropertyToolsExecutor.propertyDefinitionLookupOverrideForTests =
                (thing, propertyName) -> PropertyToolsExecutor.PropertyDefinitionOutcome.found(null);
        PropertyToolsExecutor.PropertyDefinitionOutcome outcome =
                PropertyToolsExecutor.resolvePropertyDefinitionOutcome(null, "opaque", THING);
        assertFalse(outcome.isAbsent());
        assertFalse(outcome.isNumeric());
    }

    @Test
    void blank_property_name_returns_missing_before_lookup() throws Exception {
        PropertyToolsExecutor.propertyDefinitionLookupOverrideForTests =
                (thing, propertyName) -> {
                    throw new AssertionError("lookup must not run when propertyName is blank");
                };
        JsonNode n = runAfterGate("{\"thingName\":\"" + THING + "\",\"propertyName\":\"\"}");
        assertEquals("MISSING_PROPERTY_NAME", n.path("code").asText());
    }
}
