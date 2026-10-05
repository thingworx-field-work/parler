package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * M3 Slice 1 drift guards: B8/E17 unpublish, E13 routing-guide steering, E11 dialect prose.
 */
class M3Slice1SchemaProseDriftTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern USE_DISCOVER_PROPERTIES_FIRST =
            Pattern.compile("use\\s+discover_properties\\s+first", Pattern.CASE_INSENSITIVE);

    @Test
    void b8_alertHistorySchemaOmitsTimePresetAndOldestFirst() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition hist = def(reg, "query_alert_history");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) hist.getParametersSchema().get("properties");
        assertFalse(props.containsKey("timePreset"), "B8: timePreset must be unpublished");
        assertFalse(props.containsKey("oldestFirst"), "B8: oldestFirst must be unpublished");
        assertTrue(props.containsKey("order"));
        assertFalse(hist.getDescription().contains("timePreset"));
    }

    @Test
    void e17_spotlightSchemaOmitsEntityTypes() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition spot = def(reg, "spotlight_search");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) spot.getParametersSchema().get("properties");
        assertFalse(props.containsKey("entityTypes"), "E17: entityTypes must be unpublished");
        assertTrue(spot.getDescription().toLowerCase().contains("not supported"));
    }

    @Test
    void e13_routingGuideDoesNotSteerUseDiscoverPropertiesFirst() throws Exception {
        String guide = readResource("/com/thingworx/things/agent/llm_tool_routing_guide.txt");
        assertFalse(USE_DISCOVER_PROPERTIES_FIRST.matcher(guide).find(),
                "routing guide must not greenfield-steer to discover_properties");
        assertTrue(guide.contains("prefer `discover_thing_members` over service-only discovery"), guide);
    }

    @Test
    void e11_tabulateFilterBriefStatesQueryDialect() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) TabulateCachedResultToolSchema.parametersSchema()
                .get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) props.get("filters");
        String desc = String.valueOf(filters.get("description"));
        assertTrue(desc.contains("Query dialect"), desc);
        assertTrue(desc.contains("ThingWorx-shaped"), desc);
    }

    @Test
    void e11_queryEntitiesAndAlertAdvancedQueryStateDialect() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition qe = def(reg, "query_entities");
        @SuppressWarnings("unchecked")
        Map<String, Object> qeProps = (Map<String, Object>) qe.getParametersSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> query = (Map<String, Object>) qeProps.get("query");
        String qDesc = String.valueOf(query.get("description"));
        assertTrue(qDesc.contains("Query dialect"), qDesc);

        ToolDefinition hist = def(reg, "query_alert_history");
        @SuppressWarnings("unchecked")
        Map<String, Object> histProps = (Map<String, Object>) hist.getParametersSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> aq = (Map<String, Object>) histProps.get("advancedQuery");
        String aqDesc = String.valueOf(aq.get("description"));
        assertTrue(aqDesc.contains("Query dialect"), aqDesc);
    }

    @Test
    void e9_historySchemaPublishesMaxItemsNotMaxRows() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition hist = def(reg, "query_property_history");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) hist.getParametersSchema().get("properties");
        assertTrue(props.containsKey("maxItems"), "E9: maxItems must be published");
        assertFalse(props.containsKey("maxRows"), "E9: maxRows must be unpublished alias");
        assertFalse(props.containsKey("maxPoints"), "E9: maxPoints must be unpublished alias");
        String desc = String.valueOf(((Map<?, ?>) props.get("maxItems")).get("description"));
        assertTrue(desc.contains("maxRows"), desc);
        assertTrue(desc.contains("maxPoints"), desc);
    }

    @Test
    void e12_chartRequestedTimeRangeHasStartEnd() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) BuildChartFromTabularResultToolSchema.parametersSchema()
                .get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> rtr = (Map<String, Object>) props.get("requestedTimeRange");
        @SuppressWarnings("unchecked")
        Map<String, Object> rtrProps = (Map<String, Object>) rtr.get("properties");
        assertTrue(rtrProps.containsKey("start"), "E12: start required in typed object");
        assertTrue(rtrProps.containsKey("end"), "E12: end required in typed object");
    }

    @Test
    void e15_queryEntitiesXorIsProseAndExecutorSafeNotRootOneOf() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition qe = def(reg, "query_entities");
        Map<String, Object> schema = qe.getParametersSchema();
        assertFalse(schema.containsKey("oneOf"),
                "E15: root oneOf is provider-unsafe");
        assertFalse(schema.containsKey("anyOf"));
        assertFalse(schema.containsKey("allOf"));
        String desc = qe.getDescription();
        assertTrue(desc.contains("exactly one"), desc);
        assertTrue(desc.contains("thingTemplate") && desc.contains("thingShape"), desc);
        assertTrue(desc.contains("provider-safe") || desc.contains("not expressed as a root-level"), desc);
    }

    @Test
    void b15_routingGuideSteersTaxonomyBeforeQueryEntitiesGuesswork() throws Exception {
        String guide = readResource("/com/thingworx/things/agent/llm_tool_routing_guide.txt");
        assertTrue(guide.contains("resolve_asset_type"), guide);
        assertTrue(guide.contains("query_entities_by_taxonomy"), guide);
        assertTrue(guide.toLowerCase().contains("taxonomy"), guide);
        // Must not greenfield-steer generic template guesswork over taxonomy for asset-class lists.
        assertTrue(guide.contains("Phase −1") || guide.contains("Phase -1")
                        || guide.contains("Asset taxonomy") || guide.contains("## Asset identity and scope"),
                "routing guide must keep taxonomy-first block");
    }

    @Test
    void e5_requiredAspectsHelperVersionPinned() {
        assertEquals(1, ExtendedToolRequiredAspects.VERSION);
    }

    @Test
    void alertPromptDefaultsOmitUnpublishedTimePreset() {
        String joined = AlertPromptDefaults.DEFAULT_ALERT_PROMPT_MARKDOWN;
        assertFalse(joined.contains("timePreset"), joined);
        assertFalse(joined.contains("oldestFirst"), joined);
        assertTrue(joined.contains("query_alert_history"), joined);
    }

    private static ToolDefinition def(ToolRegistry reg, String name) {
        return reg.getAllDefinitions().stream()
                .filter(d -> name.equals(d.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + name));
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = M3Slice1SchemaProseDriftTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
