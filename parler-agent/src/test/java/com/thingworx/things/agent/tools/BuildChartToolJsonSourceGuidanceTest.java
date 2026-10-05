package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * The model sees two statements of what qualifies as a {@code last_invoke} chart source: the tool's own
 * definition text and the routing guide. Both must name the JSON single-table source with the same key phrase so
 * neither can drift back to "INFOTABLE only" alone.
 */
class BuildChartToolJsonSourceGuidanceTest {

    private static final String SHARED_JSON_SOURCE_PHRASE =
            "JSON result whose decoded `result` object is a complete small single table";

    private static String routingGuide() throws Exception {
        try (InputStream in = BuildChartToolJsonSourceGuidanceTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ToolDefinition registeredChartToolDefinition() {
        ToolRegistry registry = new ToolRegistry();
        BuiltInTools.registerAll(registry);
        return registry.getAllDefinitions().stream()
                .filter(d -> "build_chart_from_tabular_result".equals(d.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("build_chart_from_tabular_result not registered"));
    }

    @Test
    void chartToolDescription_namesJsonSingleTableSourceLikeRoutingGuide() throws Exception {
        String description = registeredChartToolDefinition().getDescription();
        assertTrue(description.contains(SHARED_JSON_SOURCE_PHRASE), description);
        assertTrue(description.contains("business `status` absent or `success`"), description);
        assertTrue(description.contains("no partial-page signal"), description);
        assertTrue(description.contains("valid `last_invoke` source"), description);
        assertTrue(description.contains("do not re-query the same service through another entry point"), description);
        assertTrue(routingGuide().contains(SHARED_JSON_SOURCE_PHRASE));
    }
}
