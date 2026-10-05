package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Slice 1 remainder drift / admission guards (S1/S2/S3/B12/B14 prose + Skills/playbook surface).
 */
class M3Slice1RemainderDriftTest {

    @Test
    void s1_listEntitiesDocumentsEnglishSubstringRepair() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition d = def(reg, "list_entities_by_type");
        assertTrue(d.getDescription().contains("Repair trigger"), d.getDescription());
        assertTrue(d.getDescription().contains("substring"), d.getDescription());
    }

    @Test
    void s2_propertyHistoryDocumentsValueStreamOldestFirst() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition d = def(reg, "query_property_history");
        assertTrue(d.getDescription().contains("oldestFirst=true"), d.getDescription());
        assertTrue(d.getDescription().contains("historyOrder"), d.getDescription());
    }

    @Test
    void s3_fetchCachedDocumentsClampEcho() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition d = def(reg, "fetch_cached_result");
        assertTrue(d.getDescription().contains("offsetRequested"), d.getDescription());
        assertTrue(d.getDescription().contains("limitEffective")
                || d.getDescription().contains("limitRequested"), d.getDescription());
    }

    @Test
    void b12_listAssetTypesPublishesMaxItemsAndDefaultCapProse() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition d = def(reg, "list_asset_types");
        @SuppressWarnings("unchecked")
        var props = (java.util.Map<String, Object>) d.getParametersSchema().get("properties");
        assertTrue(props.containsKey("maxItems"), "B12: maxItems must be published");
        String desc = d.getDescription();
        assertTrue(desc.contains("hasMore"), desc);
        assertTrue(desc.contains("default") && desc.contains("500"), desc);
        assertTrue(desc.contains("omitted") || desc.contains("Always bounded"), desc);
        @SuppressWarnings("unchecked")
        var maxItems = (java.util.Map<String, Object>) props.get("maxItems");
        String maxDesc = String.valueOf(maxItems.get("description"));
        assertFalse(maxDesc.contains("return all loaded"), maxDesc);
        assertTrue(maxDesc.contains("default 500"), maxDesc);
    }

    @Test
    void b14_streamThingNameUsesSharedPreflightProse() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition d = def(reg, "query_stream_data");
        assertTrue(d.getDescription().contains("IDENTITY_RESOLUTION_REQUIRED")
                || d.getDescription().contains("canonical"), d.getDescription());
        @SuppressWarnings("unchecked")
        var props = (java.util.Map<String, Object>) d.getParametersSchema().get("properties");
        @SuppressWarnings("unchecked")
        var thingName = (java.util.Map<String, Object>) props.get("thingName");
        assertTrue(String.valueOf(thingName.get("description")).contains("resolve_thing"),
                String.valueOf(thingName.get("description")));
    }

    @Test
    void routingGuideQualifiesLegacyDiscoveryAsExecutorOnlyWhenNamed() throws Exception {
        // Broader admission lint: when the routing guide names legacy discovery tools, it must
        // also qualify them as executor-only or advertised-only (extends B7/S17; does not ban the names).
        String guide = Files.readString(
                Path.of("src/main/resources/com/thingworx/things/agent/llm_tool_routing_guide.txt"),
                StandardCharsets.UTF_8);
        assertTrue(guide.contains("executor-only") || guide.contains("executor only")
                || guide.contains("advertised `discover_services` / `get_service_definition`"), guide);
        assertTrue(guide.contains("discover_thing_members"), guide);
        assertTrue(guide.contains("prefer `discover_thing_members`")
                || guide.contains("prefer discover_thing_members")
                || guide.contains("Prefer `discover_thing_members`")
                || guide.contains("prefer `discover_thing_members` for new model turns"), guide);

        Path playbookDir = Path.of("src/test/resources/playbook-builtin-capability-expansion");
        if (Files.isDirectory(playbookDir)) {
            try (Stream<Path> walk = Files.walk(playbookDir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".playbook.json")).forEach(p -> {
                    try {
                        String text = Files.readString(p, StandardCharsets.UTF_8);
                        assertFalse(text.contains("\"discover_properties\""), p.toString());
                        assertFalse(text.contains("\"discover_services\""), p.toString());
                        assertFalse(text.contains("\"get_service_definition\""), p.toString());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    private static ToolDefinition def(ToolRegistry reg, String name) {
        return reg.getAllDefinitions().stream()
                .filter(d -> name.equals(d.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + name));
    }
}
