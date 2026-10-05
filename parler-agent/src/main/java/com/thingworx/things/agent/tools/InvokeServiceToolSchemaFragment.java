package com.thingworx.things.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * JSON Schema + description for the {@code invoke_service} built-in tool. Lives outside
 * {@link BuiltInTools} so plain JUnit can assert the shape without triggering {@code LogUtilities} static init.
 */
public final class InvokeServiceToolSchemaFragment {

    private InvokeServiceToolSchemaFragment() {}

    private static Map<String, Object> rootServiceTargetEntityTypeProperty() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("enum", ThingworxRootEntityTypes.sortedRootEntityTypeNames());
        m.put("description",
                "Root ThingWorx entity type. DataTable/Stream/ValueStream **Things** use entityType \"Thing\" plus instance name; "
                        + "server may normalize a ThingTemplate name to Thing.");
        return m;
    }

    public static Map<String, Object> invokeServiceParametersSchemaMap() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entityType", rootServiceTargetEntityTypeProperty());
        props.put("entityName", Map.of("type", "string",
                "description", "Name of the entity"));
        props.put("serviceName", Map.of("type", "string",
                "description", "Name of the service to invoke"));
        props.put("parameters", Map.of("type", "object",
                "description",
                "All target service inputs MUST be nested inside **parameters** — never at invoke_service top level "
                        + "(e.g. {\"parameters\":{\"maxItems\":3}}). Optional when the service has no inputs. "
                        + "INFOTABLE: row object array. TAGS: [{\"vocabulary\":\"...\",\"vocabularyTerm\":\"...\"}]."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"entityType", "entityName", "serviceName"});
        schema.put("additionalProperties", Boolean.FALSE);
        return schema;
    }

    public static String invokeServiceDescription() {
        return "Generic ThingWorx service call when you have a concrete service name from discovery or the task points "
                + "to a specific service. Do NOT list metadata entities by collection type — use list_entities_by_type; "
                + "do not list all Things — use query_entities or spotlight_search. Edge-only Remote Service calls blocked. "
                + "All service arguments belong under **parameters**; never beside entityType / entityName / serviceName. "
                + "Knowing a service **name** is not knowing its **inputs**. Before calling a service whose parameter "
                + "definitions you have not seen, read them from the tool that covers that target: a concrete Thing → "
                + "**discover_thing_members** (thingName, facet \"service\", memberName); a ThingTemplate or ThingShape → "
                + "**describe_entity_schema** (entityType, entityName, facet \"service\", memberName). Those two are the "
                + "input-definition tools advertised by default; use whichever ones your current tool list actually offers, and "
                + "respect each one's target type: do not send a ThingTemplate or ThingShape to discover_thing_members, and do "
                + "not present some other entity type as a Thing to reach it. When none of the tools you were given covers the "
                + "target, say so and ask, rather than guessing parameter names, calling a tool that is not in your list, or "
                + "running a broader substitute query. Skip discovery "
                + "when you already hold sufficient definitions for that target's service; a service that genuinely takes no "
                + "arguments is still called with no parameters. "
                + "Keep the entityType / entityName / serviceName you were pointed at, and map the constraints the caller "
                + "already stated onto that service's real input names in **this** call. Omitting a scope or filter parameter "
                + "widens the query; other optional parameters follow their own definitions; "
                + "ask instead of inventing a key or dropping the constraint. Do not switch to a different entity because it exposes "
                + "a service with the same name, and do not split one constrained request into a separate query. "
                + "Large INFOTABLE results return sampleRows and **may** include **cacheId**; **fetch_cached_result** pages "
                + "**cacheId** for display only — use **tabulate_cached_result** or **summarize_cached_result** for full-table "
                + "work. When **cacheId** is absent, answer from **sampleRows** / **hint** only. "
                + "Avoid full-metadata dump services (GetMetadata, GetPropertyDefinitions, …) — use discover_thing_members / "
                + "describe_entity_schema, then get_property_values. Oversized non-tabular results cache as LARGE_JSON — "
                + "use inspect_cached_payload / extract_nested.";
    }

    public static ToolDefinition invokeServiceToolDefinition() {
        return new ToolDefinition("invoke_service", invokeServiceDescription(), invokeServiceParametersSchemaMap(), true);
    }
}
