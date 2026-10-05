package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.things.agent.configrepo.ExtendedToolDefinition;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.types.BaseTypes;
import org.junit.jupiter.api.Test;

/** {@code invokeExample} shape on get_service_definition guidance. */
class InvokeServiceInvokeExampleJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nullServiceDefinitionStillEmitsWrapperShape() {
        JsonNode ex = InvokeServiceInvokeExampleJson.build(MAPPER, "Thing", "MyThing", null);
        assertEquals("invoke_service", ex.path("tool").asText());
        JsonNode args = ex.path("arguments");
        assertEquals("Thing", args.path("entityType").asText());
        assertEquals("MyThing", args.path("entityName").asText());
        assertTrue(args.path("parameters").isObject());
    }

    @Test
    void dateTimeParamEmitsIsoSentinelPlaceholder() {
        FieldDefinitionCollection defs = new FieldDefinitionCollection();
        defs.addFieldDefinition(new FieldDefinition("startTime", "", BaseTypes.DATETIME));

        JsonNode ex = InvokeServiceInvokeExampleJson.build(MAPPER, "Thing", "MyThing", "Query", defs);

        assertEquals("<ISO-8601_DATETIME>",
                ex.path("arguments").path("parameters").path("startTime").asText());
    }

    @Test
    void noteWarnsAgainstCopyingPlaceholdersAsDefaults() {
        JsonNode ex = InvokeServiceInvokeExampleJson.build(MAPPER, "Thing", "MyThing", "Query", null);
        String note = ex.path("note").asText();
        assertTrue(note.contains("placeholders"));
        assertTrue(note.contains("never copy"));
    }

    @Test
    void numberParamEmitsNumericZeroPlaceholder() {
        FieldDefinitionCollection defs = new FieldDefinitionCollection();
        defs.addFieldDefinition(new FieldDefinition("maxItems", "", BaseTypes.NUMBER));

        JsonNode ex = InvokeServiceInvokeExampleJson.build(MAPPER, "Thing", "MyThing", "Query", defs);

        assertTrue(ex.path("arguments").path("parameters").path("maxItems").isNumber());
        assertEquals(0, ex.path("arguments").path("parameters").path("maxItems").asInt());
    }

    @Test
    void extended_tool_match_steers_invoke_example_to_named_tool() {
        FieldDefinitionCollection defs = new FieldDefinitionCollection();
        defs.addFieldDefinition(new FieldDefinition("Machine", "", BaseTypes.THINGNAME));
        defs.addFieldDefinition(new FieldDefinition("StartDate", "", BaseTypes.DATETIME));
        ExtendedToolDefinition ext = new ExtendedToolDefinition(
                "utilization_records_by_machine",
                "title",
                "when",
                "SCPA_Utilization_helper",
                "GetUtilizationRecordsByMachine",
                true,
                false,
                new ToolDefinition("utilization_records_by_machine", "d", Map.of()));
        JsonNode ex = InvokeServiceInvokeExampleJson.build(MAPPER, "Thing", "SCPA_Utilization_helper",
                "GetUtilizationRecordsByMachine", defs, ext);
        assertEquals("utilization_records_by_machine", ex.path("tool").asText());
        assertEquals("<canonical ThingWorx Thing name>",
                ex.path("arguments").path("Machine").asText());
        assertEquals("<ISO-8601_DATETIME>", ex.path("arguments").path("StartDate").asText());
        String note = ex.path("note").asText();
        assertTrue(note.contains("named extended tool"));
        assertTrue(note.contains("invoke_service"));
    }

    @Test
    void no_extended_match_keeps_invoke_service_example() {
        FieldDefinitionCollection defs = new FieldDefinitionCollection();
        defs.addFieldDefinition(new FieldDefinition("p", "", BaseTypes.STRING));
        JsonNode ex = InvokeServiceInvokeExampleJson.build(MAPPER, "Thing", "OnlyThing", "OnlyService", defs, null);
        assertEquals("invoke_service", ex.path("tool").asText());
        assertTrue(ex.path("arguments").path("parameters").path("p").isTextual());
    }
}
