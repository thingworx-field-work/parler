package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ToolRegistry;

class PlaybookRuntimeSnapshotBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deriveOps_matchesValidatorAllowlist() throws Exception {
        JsonNode rt = snapshotNode();
        Set<String> fromSnapshot = new HashSet<>();
        rt.get("deriveOps").forEach(n -> fromSnapshot.add(n.asText()));
        assertEquals(PlaybookValidator.supportedDeriveOps(), fromSnapshot);
    }

    @Test
    void includesSchemaVersionAndLimits() throws Exception {
        JsonNode rt = snapshotNode();
        assertEquals(PlaybookIds.SCHEMA_V1, rt.get("schemaVersion").asText());
        assertEquals(PlaybookGenericOpsConstants.MAX_FAN_OUT_CONCURRENCY,
                rt.get("limits").get("maxFanOutConcurrency").asInt());
        assertEquals(PlaybookIds.MAX_PACKAGED_PLAYBOOKS, rt.get("limits").get("maxPackagedPlaybooks").asInt());
        assertNotNull(rt.get("bindings"));
        assertFalse(rt.get("bindings").get("infotableForInvokeService").asBoolean());
    }

    @Test
    void builtInTools_matchPlaybookSafeRegistryNames() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        BuiltInTools.registerAll(registry);
        JsonNode rt = MAPPER.readTree(
                PlaybookRuntimeSnapshotBuilder.build(registry, ExtendedToolRegistrySnapshot.missing(), "0.1.190")
                        .toString());
        Set<String> snapshotNames = new HashSet<>();
        rt.get("builtInTools").forEach(n -> snapshotNames.add(n.get("name").asText()));
        Set<String> expected = new HashSet<>();
        for (ToolDefinition td : registry.getAllDefinitions()) {
            if (td.isPlaybookSafe()) {
                expected.add(td.getName());
            }
        }
        assertEquals(expected, snapshotNames);
    }

    private static JsonNode snapshotNode() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        BuiltInTools.registerAll(registry);
        var node = PlaybookRuntimeSnapshotBuilder.build(registry, ExtendedToolRegistrySnapshot.missing(), "0.1.190");
        return MAPPER.readTree(node.toString());
    }
}
