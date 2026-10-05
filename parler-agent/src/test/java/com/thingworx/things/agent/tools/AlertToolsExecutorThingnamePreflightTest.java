package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Phase B: alert built-ins share {@link ScalarThingnamePreflight} scalar {@code thingName} envelopes.
 */
class AlertToolsExecutorThingnamePreflightTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
        AlertToolsExecutor.clearAlertToolsExecutorTestHooks();
        AgentToolContext.clear();
    }

    @Test
    void query_alert_summary_whitespace_only_thingNames_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "query_alert_summary",
                "{\"thingNames\":[\"  \\t  \"],\"ackState\":\"all\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertSummary(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("thingNames", n.path("parameterName").asText());
    }

    @Test
    void acknowledge_alerts_identity_reject_does_not_enter_resolve_alert_functions() throws Exception {
        AtomicBoolean entered = new AtomicBoolean();
        AlertToolsExecutor.onResolveAlertFunctionsEnteredForTests = () -> entered.set(true);
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "acknowledge_alerts",
                "{\"thingName\":\"not-a-thing\",\"mode\":\"specific_alerts\",\"propertyName\":\"p\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeAcknowledgeAlerts(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertFalse(entered.get());
    }

    @Test
    void query_alert_summary_empty_thingNames_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "query_alert_summary", "{\"thingNames\":[],\"ackState\":\"all\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertSummary(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("thingNames", n.path("parameterName").asText());
    }

    @Test
    void query_alert_summary_non_canonical_returns_identity_with_recovery_when_recoverable() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "query_alert_summary",
                "{\"thingNames\":[\"display label\"],\"ackState\":\"all\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertSummary(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("resolve_thing", n.path("recoveryHint").path("tool").asText());
    }

    @Test
    void query_alert_history_non_canonical_returns_identity_before_time_work() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = false;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "query_alert_history",
                "{\"thingName\":\"bad\",\"timePreset\":\"last_7d\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertHistory(call));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertFalse(n.has("recoveryHint"));
    }
}
