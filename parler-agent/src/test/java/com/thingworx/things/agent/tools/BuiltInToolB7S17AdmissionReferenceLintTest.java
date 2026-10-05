package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * B7 / S17: under the default admission snapshot ({@code advertiseLegacyServiceDiscoveryTools=false}),
 * advertised tool descriptions/schemas must not steer the model at executor-only discovery tool names.
 */
class BuiltInToolB7S17AdmissionReferenceLintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Always executor-only under default BuiltInTools registration. */
    private static final Set<String> DEFAULT_EXECUTOR_ONLY_DISCOVERY = Set.of(
            "discover_properties", "discover_services", "get_service_definition");

    private static final Pattern TOOL_NAME_TOKEN = Pattern.compile(
            "\\b(discover_properties|discover_services|get_service_definition)\\b");

    @Test
    void defaultAdvertisedSurfaceOmitsExecutorOnlyDiscoveryNames() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        Set<String> advertised = reg.getAllDefinitions().stream()
                .map(ToolDefinition::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String banned : DEFAULT_EXECUTOR_ONLY_DISCOVERY) {
            assertFalse(advertised.contains(banned), "default merge must not advertise " + banned);
            assertTrue(reg.getExecutorOnlyAliases().contains(banned), banned + " must remain executor-only");
        }

        for (ToolDefinition def : reg.getAllDefinitions()) {
            String surface = def.getDescription() + "\n" + MAPPER.writeValueAsString(def.getParametersSchema());
            Matcher m = TOOL_NAME_TOKEN.matcher(surface);
            if (m.find()) {
                throw new AssertionError("advertised tool \"" + def.getName()
                        + "\" steers at unadvertised discovery name \"" + m.group(1)
                        + "\" under default admission: " + surface);
            }
        }
    }

    @Test
    void getPropertyValuesSteersAtDiscoverThingMembers() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition gpv = reg.getAllDefinitions().stream()
                .filter(d -> "get_property_values".equals(d.getName()))
                .findFirst()
                .orElseThrow();
        assertTrue(gpv.getDescription().contains("discover_thing_members"), gpv.getDescription());
        assertFalse(gpv.getDescription().contains("discover_properties"), gpv.getDescription());
    }
}
