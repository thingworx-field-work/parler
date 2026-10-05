package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;

/** Full built-in registry sweep against {@link com.thingworx.things.agent.llm.LlmJsonSchemaCompat}. */
class BuiltInToolsLlmSchemaRegistryTest {

    @Test
    void allRegisteredBuiltInTools_matchStrictArraySchemaRule() {
        BuiltInTools.assertAllRegisteredToolSchemasPassLlmCompatCheck();
    }

    @Test
    void property_built_ins_thingName_schema_mentions_resolve_thing_recovery_path() {
        ToolRegistry registry = new ToolRegistry();
        BuiltInTools.registerAll(registry, false);
        List<ToolDefinition> defs = registry.getAllDefinitions();
        for (String tool : List.of("get_property_values", "query_property_history", "query_alert_history",
                "acknowledge_alerts")) {
            ToolDefinition def = defs.stream().filter(d -> tool.equals(d.getName())).findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) def.getParametersSchema().get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> thingName = (Map<String, Object>) props.get("thingName");
            String desc = (String) thingName.get("description");
            assertTrue(desc.contains("resolve_thing"), tool + " thingName description should steer to resolve_thing");
            assertTrue(desc.contains("UNIQUE"), tool + " thingName description should mention UNIQUE resolver result");
        }
        ToolDefinition summary = defs.stream().filter(d -> "query_alert_summary".equals(d.getName())).findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> summaryProps = (Map<String, Object>) summary.getParametersSchema().get("properties");
        assertTrue(summaryProps.containsKey("thingNames"), "query_alert_summary must use thingNames[]");
    }
}
