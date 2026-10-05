package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.configrepo.ExtendedToolDefinition;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.types.BaseTypes;

/**
 * End-to-end {@link MetadataDiscoveryExecutor} invokeExample path with prompt snapshot + extended
 * registry (no live ThingWorx entity lookup).
 */
class MetadataDiscoveryInvokeExampleWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void extended_registry_produces_named_tool_invoke_example() throws Exception {
        ExtendedToolRegistrySnapshot reg = ExtendedToolRegistrySnapshot.ok(List.of(
                new ExtendedToolDefinition(
                        "utilization_records_by_machine",
                        "t",
                        "w",
                        "SCPA_Utilization_helper",
                        "GetUtilizationRecordsByMachine",
                        true,
                        false,
                        new ToolDefinition("utilization_records_by_machine", "d", Map.of()))));
        PromptContextCacheSnapshot snap = snapshotWithExtendedTools(reg);
        ServiceDefinition sd = serviceWithParams();
        ObjectNode invoke = MetadataDiscoveryExecutor.buildInvokeExampleForResolvedService(
                snap, "Thing", "SCPA_Utilization_helper", sd);
        JsonNode tree = MAPPER.readTree(MAPPER.writeValueAsBytes(invoke));
        assertEquals("utilization_records_by_machine", tree.path("tool").asText());
        assertTrue(tree.path("arguments").path("Machine").isTextual());
        assertEquals("<canonical ThingWorx Thing name>", tree.path("arguments").path("Machine").asText());
        assertFalseNestedParametersWrapper(tree);
        String note = tree.path("note").asText();
        assertTrue(note.contains("named extended tool"), note);
        assertTrue(note.contains("invoke_service"), note);
    }

    @Test
    void missing_extended_registry_keeps_invoke_service_shape() throws Exception {
        PromptContextCacheSnapshot snap = snapshotWithExtendedTools(ExtendedToolRegistrySnapshot.missing());
        ServiceDefinition sd = serviceWithParams();
        ObjectNode invoke = MetadataDiscoveryExecutor.buildInvokeExampleForResolvedService(
                snap, "Thing", "SCPA_Utilization_helper", sd);
        JsonNode tree = MAPPER.readTree(MAPPER.writeValueAsBytes(invoke));
        assertEquals("invoke_service", tree.path("tool").asText());
        assertTrue(tree.path("arguments").path("parameters").path("Machine").isTextual());
        assertEquals("<canonical ThingWorx Thing name>",
                tree.path("arguments").path("parameters").path("Machine").asText());
    }

    private static void assertFalseNestedParametersWrapper(JsonNode tree) {
        assertFalse(
                tree.path("arguments").path("parameters").path("Machine").isTextual(),
                "matched extended-tool branch must not nest service params under arguments.parameters");
    }

    private static PromptContextCacheSnapshot snapshotWithExtendedTools(ExtendedToolRegistrySnapshot ext) {
        PromptContextCacheSnapshot.ConfigurationRepositoryState cfg =
                new PromptContextCacheSnapshot.ConfigurationRepositoryState(
                        ext, false, false, false, 0, "missing", 0);
        return new PromptContextCacheSnapshot(
                "",
                Collections.emptyList(),
                Collections.emptyList(),
                "",
                "",
                null,
                cfg,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static ServiceDefinition serviceWithParams() {
        ServiceDefinition sd = new ServiceDefinition("GetUtilizationRecordsByMachine", "test");
        FieldDefinition machine = new FieldDefinition("Machine", "", BaseTypes.THINGNAME);
        sd.getParameters().addFieldDefinition(machine);
        FieldDefinition start = new FieldDefinition("StartDate", "", BaseTypes.DATETIME);
        sd.getParameters().addFieldDefinition(start);
        return sd;
    }
}
