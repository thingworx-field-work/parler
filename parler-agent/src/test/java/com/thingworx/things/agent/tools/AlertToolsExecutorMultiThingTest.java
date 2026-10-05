package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

class AlertToolsExecutorMultiThingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
        AlertToolsExecutor.clearAlertToolsExecutorTestHooks();
        AgentToolContext.clear();
    }

    @Test
    void missing_thingNames_returns_thingname_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "query_alert_summary", "{\"ackState\":\"all\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertSummary(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("thingNames", n.path("parameterName").asText());
    }

    @Test
    void over_limit_returns_thing_names_limit_exceeded() throws Exception {
        StringBuilder args = new StringBuilder("{\"thingNames\":[");
        for (int i = 0; i < 26; i++) {
            if (i > 0) {
                args.append(',');
            }
            args.append("\"T").append(i).append('"');
        }
        args.append("]}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertSummary(new ToolCall("1", "query_alert_summary",
                args.toString())));
        assertEquals("THING_NAMES_LIMIT_EXCEEDED", n.path("code").asText());
    }

    @Test
    void single_thing_returns_infotable_envelope() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> true;
        AlertToolsExecutor.queryAlertSummaryForThingOverrideForTests = name -> alertTable(2, 900, false);

        ToolCall call = new ToolCall("1", "query_alert_summary",
                "{\"thingNames\":[\"CanonicalThing\"],\"ackState\":\"all\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertSummary(call));
        assertEquals("success", n.path("status").asText(), () -> n.toString());
        assertEquals("INFOTABLE", n.path("resultKind").asText());
        assertTrue(n.has("rows"));
        assertEquals("CanonicalThing", n.path("thingName").asText());
    }

    @Test
    void one_resolved_one_identity_fail_uses_rollup_not_flat_infotable() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> "RobotA".equals(name);
        AlertToolsExecutor.queryAlertSummaryForThingOverrideForTests = name -> alertTable(2, 900, false);

        ToolCall call = new ToolCall("1", "query_alert_summary",
                "{\"thingNames\":[\"RobotA\",\"germany\"],\"ackState\":\"all\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertSummary(call));
        assertEquals("success", n.path("status").asText(), () -> n.toString());
        assertEquals("ALERT_SUMMARY_MULTI", n.path("resultKind").asText());
        assertEquals("partial", n.path("completeness").asText());
        assertEquals(2, n.path("thingsRequested").asInt());
        assertEquals(1, n.path("thingsSucceeded").asInt());
        assertEquals(1, n.path("thingsFailedIdentity").asInt());
        assertTrue(n.path("identityErrors").isArray());
        assertEquals(1, n.path("identityErrors").size());
        assertFalse(n.has("rows"), () -> n.toString());
    }

    @Test
    void non_textual_thingNames_element_returns_value_required() throws Exception {
        ToolCall call = new ToolCall("1", "query_alert_summary",
                "{\"thingNames\":[\"Thing-A\",123],\"ackState\":\"all\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertSummary(call));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
        assertEquals("thingNames", n.path("parameterName").asText());
    }

    @Test
    void multi_thing_returns_alert_summary_multi_with_partial_identity() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> !"bad-label".equals(name);
        Map<String, InfoTable> tables = new HashMap<>();
        tables.put("Thing-A", alertTable(3, 900, false));
        tables.put("Thing-B", alertTable(1, 500, true));
        AlertToolsExecutor.queryAlertSummaryForThingOverrideForTests = tables::get;

        ToolCall call = new ToolCall("1", "query_alert_summary",
                "{\"thingNames\":[\"Thing-A\",\"bad-label\",\"Thing-B\"],\"ackState\":\"all\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertSummary(call));
        assertEquals("success", n.path("status").asText());
        assertEquals("ALERT_SUMMARY_MULTI", n.path("resultKind").asText());
        assertEquals("partial", n.path("completeness").asText());
        assertEquals(3, n.path("thingsRequested").asInt());
        assertEquals(2, n.path("thingsSucceeded").asInt());
        assertEquals(1, n.path("thingsFailedIdentity").asInt());
        assertEquals(3, n.path("byThing").size());
        assertTrue(n.path("identityErrors").isArray());
    }

    @Test
    void all_identity_fail_returns_whole_call_error() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "query_alert_summary",
                "{\"thingNames\":[\"x\",\"y\"],\"ackState\":\"all\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertSummary(call));
        assertEquals("error", n.path("status").asText());
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("ALERT_SUMMARY_MULTI", n.path("resultKind").asText());
        assertTrue(n.path("identityErrors").isArray());
        assertEquals(2, n.path("identityErrors").size());
    }

    @Test
    void per_thing_service_error_continues_comparison() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> true;
        AtomicInteger calls = new AtomicInteger();
        AlertToolsExecutor.queryAlertSummaryForThingOverrideForTests = name -> {
            calls.incrementAndGet();
            if ("fail-thing".equals(name)) {
                throw new IllegalStateException("platform boom");
            }
            return alertTable(1, 700, false);
        };

        ToolCall call = new ToolCall("1", "query_alert_summary",
                "{\"thingNames\":[\"ok-thing\",\"fail-thing\"],\"ackState\":\"all\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeQueryAlertSummary(call));
        assertEquals("success", n.path("status").asText());
        assertEquals("partial", n.path("completeness").asText());
        assertEquals(1, n.path("thingsSucceeded").asInt());
        assertEquals(1, n.path("thingsFailedService").asInt());
        assertEquals(2, calls.get());
    }

    @Test
    void zero_resolve_does_not_call_alert_functions() throws Exception {
        AtomicBoolean entered = new AtomicBoolean();
        AlertToolsExecutor.onResolveAlertFunctionsEnteredForTests = () -> entered.set(true);
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ToolCall call = new ToolCall("1", "query_alert_summary", "{\"thingNames\":[\"bad\"],\"ackState\":\"all\"}");
        AlertToolsExecutor.executeQueryAlertSummary(call);
        assertFalse(entered.get());
    }

    private static InfoTable alertTable(int rows, int priority, boolean acked) {
        DataShapeDefinition dsd = new DataShapeDefinition();
        addField(dsd, "name", BaseTypes.STRING);
        addField(dsd, "sourceProperty", BaseTypes.STRING);
        addField(dsd, "priority", BaseTypes.NUMBER);
        addField(dsd, "timestamp", BaseTypes.STRING);
        addField(dsd, "acknowledged", BaseTypes.BOOLEAN);
        InfoTable it = new InfoTable(dsd);
        for (int i = 0; i < rows; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("name", new StringPrimitive("Alert-" + i));
            vc.put("sourceProperty", new StringPrimitive("Prop-" + i));
            vc.put("priority", new NumberPrimitive(priority - i));
            vc.put("timestamp", new StringPrimitive("2026-06-28T12:0" + i + ":00Z"));
            vc.put("acknowledged", new BooleanPrimitive(acked));
            it.addRow(vc);
        }
        return it;
    }

    private static void addField(DataShapeDefinition dsd, String name, BaseTypes type) {
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(type);
        dsd.addFieldDefinition(fd);
    }
}
