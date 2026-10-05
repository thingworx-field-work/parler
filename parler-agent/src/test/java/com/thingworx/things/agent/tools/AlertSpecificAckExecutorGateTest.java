package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * S7: executor-level permanent gate — {@code EXCEEDS_LIMIT} performs no ack write.
 */
class AlertSpecificAckExecutorGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> true;
    }

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
        AlertToolsExecutor.clearAlertToolsExecutorTestHooks();
        AgentToolContext.clear();
    }

    @Test
    void exceedsLimit_501_returnsErrorAndPerformsNoWrite() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicBoolean resolvedAlertFunctions = new AtomicBoolean();
        AlertToolsExecutor.specificAlertsAckWriteAttemptsForTests = writes;
        AlertToolsExecutor.onResolveAlertFunctionsEnteredForTests = () -> resolvedAlertFunctions.set(true);
        AlertToolsExecutor.ackSummaryProbeOverrideForTests = () -> new AlertSummaryAckProbe.SummaryProbeOutcome(
                alertTable(501), null);

        ToolCall call = new ToolCall("1", "acknowledge_alerts",
                "{\"thingName\":\"Pump-1\",\"mode\":\"specific_alerts\",\"propertyName\":\"OverTemp\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeAcknowledgeAlerts(call));

        assertEquals("error", n.path("status").asText());
        assertEquals("ACK_MATCHES_EXCEED_LIMIT", n.path("code").asText());
        assertEquals(0, writes.get(), "EXCEEDS_LIMIT must not invoke AcknowledgeAlert*");
        assertFalse(resolvedAlertFunctions.get(),
                "EXCEEDS_LIMIT with probe override must not resolve AlertFunctions for write");
    }

    @Test
    void emptyProbe_performsNoWrite() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AlertToolsExecutor.specificAlertsAckWriteAttemptsForTests = writes;
        AlertToolsExecutor.ackSummaryProbeOverrideForTests = () -> new AlertSummaryAckProbe.SummaryProbeOutcome(
                alertTable(0), null);

        ToolCall call = new ToolCall("1", "acknowledge_alerts",
                "{\"thingName\":\"Pump-1\",\"mode\":\"specific_alerts\",\"propertyName\":\"OverTemp\"}");
        JsonNode n = MAPPER.readTree(AlertToolsExecutor.executeAcknowledgeAlerts(call));

        assertEquals("success", n.path("status").asText());
        assertEquals(0, n.path("acknowledgedCount").asInt(-1));
        assertEquals(0, writes.get());
    }

    private static InfoTable alertTable(int rows) {
        DataShapeDefinition dsd = new DataShapeDefinition();
        addField(dsd, "name", BaseTypes.STRING);
        addField(dsd, "sourceProperty", BaseTypes.STRING);
        addField(dsd, "priority", BaseTypes.NUMBER);
        addField(dsd, "acknowledged", BaseTypes.BOOLEAN);
        InfoTable it = new InfoTable(dsd);
        for (int i = 0; i < rows; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("name", new StringPrimitive("Alert-" + i));
            vc.put("sourceProperty", new StringPrimitive("OverTemp"));
            vc.put("priority", new NumberPrimitive(1));
            vc.put("acknowledged", new BooleanPrimitive(false));
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
