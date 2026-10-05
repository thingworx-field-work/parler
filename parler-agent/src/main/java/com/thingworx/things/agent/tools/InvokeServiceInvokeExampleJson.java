package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.things.agent.configrepo.ExtendedToolDefinition;
import com.thingworx.types.BaseTypes;

/**
 * Builds an {@code invokeExample} object for {@code get_service_definition} responses.
 * LLM-facing guidance only — not a UI wire contract.
 */
public final class InvokeServiceInvokeExampleJson {

    private static final String EXTENDED_TOOL_INVOKE_NOTE =
            "Prefer this named extended tool over invoke_service for this service. The named tool applies "
                    + "tool-specific preflight (e.g., THINGNAME identity resolution) and honors its extended-tool HITL "
                    + "policy. Parameter values are placeholders showing shape only — derive real values from the "
                    + "user request, never copy literals from this example as defaults.";

    private InvokeServiceInvokeExampleJson() {}

    public static ObjectNode build(ObjectMapper mapper, String effectiveEntityType, String entityName,
            String serviceName, FieldDefinitionCollection parameterDefs) {
        return build(mapper, effectiveEntityType, entityName, serviceName, parameterDefs, null);
    }

    /**
     * @param extendedMatchOrNull when non-null, {@code invokeExample.tool} is the extended tool name and arguments
     *                            are top-level service parameters (Bug 009)
     */
    public static ObjectNode build(ObjectMapper mapper, String effectiveEntityType, String entityName,
            String serviceName, FieldDefinitionCollection parameterDefs, ExtendedToolDefinition extendedMatchOrNull) {
        if (extendedMatchOrNull != null) {
            return buildForNamedExtendedTool(mapper, extendedMatchOrNull, parameterDefs);
        }
        ObjectNode invokeExample = mapper.createObjectNode();
        invokeExample.put("tool", "invoke_service");
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("entityType", effectiveEntityType != null ? effectiveEntityType : "Thing");
        arguments.put("entityName", entityName != null ? entityName : "");
        arguments.put("serviceName", serviceName != null ? serviceName : "");
        ObjectNode parameters = mapper.createObjectNode();
        addServiceParameterPlaceholders(mapper, parameters, parameterDefs);
        arguments.set("parameters", parameters);
        invokeExample.set("arguments", arguments);
        invokeExample.put("note",
                "Place all service inputs under arguments.parameters; never at invoke_service top level. "
                        + "Values in parameters are placeholders showing shape and key names only — derive real values "
                        + "from the user request, never copy numeric/boolean/date literals from this example as defaults.");
        return invokeExample;
    }

    private static ObjectNode buildForNamedExtendedTool(ObjectMapper mapper, ExtendedToolDefinition ext,
            FieldDefinitionCollection parameterDefs) {
        ObjectNode invokeExample = mapper.createObjectNode();
        invokeExample.put("tool", ext.llmName());
        ObjectNode arguments = mapper.createObjectNode();
        addServiceParameterPlaceholders(mapper, arguments, parameterDefs);
        invokeExample.set("arguments", arguments);
        invokeExample.put("note", EXTENDED_TOOL_INVOKE_NOTE);
        return invokeExample;
    }

    private static void addServiceParameterPlaceholders(ObjectMapper mapper, ObjectNode parametersRoot,
            FieldDefinitionCollection parameterDefs) {
        if (parameterDefs == null || parameterDefs.values() == null) {
            return;
        }
        List<FieldDefinition> requiredFirst = new ArrayList<>();
        List<FieldDefinition> optional = new ArrayList<>();
        for (FieldDefinition fd : parameterDefs.values()) {
            if (fd == null || fd.getName() == null || fd.getName().isEmpty()) {
                continue;
            }
            if (isRequiredParam(fd)) {
                requiredFirst.add(fd);
            } else {
                optional.add(fd);
            }
        }
        for (FieldDefinition fd : requiredFirst) {
            putPlaceholder(mapper, parametersRoot, fd);
        }
        int cap = 0;
        for (FieldDefinition fd : optional) {
            if (cap >= 4) {
                break;
            }
            putPlaceholder(mapper, parametersRoot, fd);
            cap++;
        }
    }

    /** Convenience when a {@link com.thingworx.metadata.ServiceDefinition} is already in hand. */
    public static ObjectNode build(ObjectMapper mapper, String effectiveEntityType, String entityName,
            com.thingworx.metadata.ServiceDefinition sd) {
        if (sd == null) {
            return build(mapper, effectiveEntityType, entityName, "", null);
        }
        return build(mapper, effectiveEntityType, entityName,
                sd.getName() != null ? sd.getName() : "",
                sd.getParameters());
    }

    private static void putPlaceholder(ObjectMapper mapper, ObjectNode parameters, FieldDefinition fd) {
        String name = fd.getName();
        BaseTypes bt = fd.getBaseType() != null ? fd.getBaseType() : BaseTypes.STRING;
        if (bt == BaseTypes.PASSWORD) {
            return;
        }
        switch (bt) {
            case NUMBER:
            case INTEGER:
            case LONG:
                parameters.put(name, 0);
                break;
            case BOOLEAN:
                parameters.put(name, true);
                break;
            case INFOTABLE:
                parameters.putArray(name);
                break;
            case QUERY:
            case JSON:
            case VARIANT:
                parameters.set(name, mapper.createObjectNode());
                break;
            case TAGS:
                var tags = mapper.createArrayNode();
                ObjectNode tagRow = mapper.createObjectNode();
                tagRow.put("vocabulary", "<VOCABULARY>");
                tagRow.put("vocabularyTerm", "<TERM>");
                tags.add(tagRow);
                parameters.set(name, tags);
                break;
            case DATETIME:
                parameters.put(name, "<ISO-8601_DATETIME>");
                break;
            case THINGNAME:
                parameters.put(name, "<canonical ThingWorx Thing name>");
                break;
            case PASSWORD:
            case HTML:
            case TEXT:
            case XML:
            case STRING:
            default:
                parameters.put(name, "<STRING>");
                break;
        }
    }

    private static boolean isRequiredParam(FieldDefinition fd) {
        try {
            if (fd.getAspects() == null) {
                return true;
            }
            Object v = fd.getAspects().get("isRequired");
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
            if (v != null && "false".equalsIgnoreCase(v.toString())) {
                return false;
            }
        } catch (Throwable ignored) {
            // default required
        }
        return true;
    }
}
