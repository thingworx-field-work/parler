package com.thingworx.things.agent.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

import org.joda.time.DateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.Thing;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.ServiceDefinitionCollection;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.LongPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import org.slf4j.Logger;
import com.thingworx.logging.LogUtilities;

/**
 * Extended-tool / custom-service argument and schema helpers for AgentThing.
 * Legacy {@code _tool_*} prefix discovery ({@code harvestCustomTools}) was removed in U1B S13;
 * configuration-repository extended tools use {@link #toToolDefinitionForExtendedTool}.
 */
public final class CustomToolHarvester {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(CustomToolHarvester.class);

    private CustomToolHarvester() {}

    /**
     * Builds {@link ValueCollection} for a custom/extended service from LLM JSON arguments, using the same
     * base-type rules as {@code invoke_service} (including {@link BaseTypes#INFOTABLE} via {@link InfotableJsonCodec}).
     *
     * @throws IllegalArgumentException if the service is missing, arguments are not a JSON object, or a value cannot be converted
     */
    public static ValueCollection buildValueCollectionForCustomTool(IServiceProvider agent, String serviceName, JsonNode root)
            throws Exception {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
        ServiceDefinition sd = findServiceDefinition(agent, serviceName);
        if (sd == null) {
            throw new IllegalArgumentException("Unknown custom tool service: " + serviceName);
        }
        // If the service exposes a recognized DATETIME pair
        // (startDate/endDate or startTime/endTime), resolve any synthetic calendarPhrase /
        // relativeDuration in place so the canonical pair fields carry ISO-8601 instants by the time
        // the parameter loop below converts them. Throws CustomToolNaturalTimeException for any
        // natural-time conflict — AgentThing.executeCustomTool catches that and emits the structured
        // BuiltInToolTimeErrorJson envelope (same shape as curated built-ins).
        CustomToolDateTimePairResolver.resolveInPlace((ObjectNode) root, sd, Instant.now());
        ValueCollection vc = new ValueCollection();
        var params = sd.getParameters();
        if (params == null || params.values() == null) {
            return vc;
        }
        for (FieldDefinition fd : params.values()) {
            String name = fd.getName();
            if (name == null || !root.has(name)) {
                continue;
            }
            JsonNode node = root.get(name);
            if (node == null || node.isNull()) {
                continue;
            }
            BaseTypes bt = fd.getBaseType() != null ? fd.getBaseType() : BaseTypes.STRING;
            if (bt == BaseTypes.INFOTABLE) {
                DataShapeDefinition shape = InfotableJsonCodec.resolveDataShapeForParameter(fd);
                if (shape == null) {
                    throw new IllegalArgumentException("Parameter \"" + name + "\": INFOTABLE has no resolvable DataShape."
                            + InfotableJsonCodec.CUSTOM_TOOL_HINT);
                }
                InfoTable it = InfotableJsonCodec.jsonToInfoTable(node, shape, name);
                vc.SetInfoTableValue(name, it);
            } else {
                vc.put(name, jsonNodeToPrimitive(name, node, bt));
            }
        }
        return vc;
    }

    /**
     * Same as {@link #buildValueCollectionForCustomTool} but allows pre-resolved {@link InfoTable} values for
     * INFOTABLE parameters (playbook {@code $table} handoff). Non-overridden INFOTABLE parameters still parse from
     * {@code root}.
     */
    public static ValueCollection buildValueCollectionForPlaybookExtendedTool(Thing target, String serviceName,
            JsonNode root, Map<String, InfoTable> infotableOverrides) throws Exception {
        Map<String, InfoTable> overrides = infotableOverrides != null ? infotableOverrides : Collections.emptyMap();
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
        ServiceDefinition sd = findServiceDefinition(target, serviceName);
        if (sd == null) {
            throw new IllegalArgumentException("Unknown custom tool service: " + serviceName);
        }
        CustomToolDateTimePairResolver.resolveInPlace((ObjectNode) root, sd, Instant.now());
        ValueCollection vc = new ValueCollection();
        var params = sd.getParameters();
        if (params == null || params.values() == null) {
            return vc;
        }
        for (FieldDefinition fd : params.values()) {
            String name = fd.getName();
            BaseTypes bt = fd.getBaseType() != null ? fd.getBaseType() : BaseTypes.STRING;
            if (bt == BaseTypes.INFOTABLE) {
                InfoTable fromPlaybook = overrides.get(name);
                if (fromPlaybook != null) {
                    vc.SetInfoTableValue(name, fromPlaybook);
                    continue;
                }
                if (!root.has(name)) {
                    continue;
                }
                JsonNode node = root.get(name);
                if (node == null || node.isNull()) {
                    continue;
                }
                DataShapeDefinition shape = InfotableJsonCodec.resolveDataShapeForParameter(fd);
                if (shape == null) {
                    throw new IllegalArgumentException("Parameter \"" + name + "\": INFOTABLE has no resolvable DataShape."
                            + InfotableJsonCodec.CUSTOM_TOOL_HINT);
                }
                InfoTable it = InfotableJsonCodec.jsonToInfoTable(node, shape, name);
                vc.SetInfoTableValue(name, it);
            } else {
                if (!root.has(name)) {
                    continue;
                }
                JsonNode node = root.get(name);
                if (node == null || node.isNull()) {
                    continue;
                }
                vc.put(name, jsonNodeToPrimitive(name, node, bt));
            }
        }
        return vc;
    }

    /**
     * Resolves a service definition by name on the agent (same lookup as custom-tool execution).
     * Visible for protection checks on {@code AgentThing#executeCustomTool}.
     */
    public static ServiceDefinition findServiceDefinition(IServiceProvider agent, String serviceName) {
        ServiceDefinitionCollection defs = agent.getInstanceServiceDefinitions();
        if (defs == null || defs.values() == null) {
            return null;
        }
        for (ServiceDefinition sd : defs.values()) {
            if (sd != null && serviceName.equals(sd.getName())) {
                return sd;
            }
        }
        return null;
    }

    private static IPrimitiveType jsonNodeToPrimitive(String paramName, JsonNode node, BaseTypes baseType) {
        if (baseType == null) {
            baseType = BaseTypes.STRING;
        }
        try {
            switch (baseType) {
                case INTEGER:
                    if (node.isInt() || node.isLong()) {
                        return new IntegerPrimitive(node.intValue());
                    }
                    if (node.isNumber()) {
                        return new IntegerPrimitive((int) node.longValue());
                    }
                    return new IntegerPrimitive(Integer.parseInt(node.asText().trim()));
                case LONG:
                    if (node.isInt() || node.isLong()) {
                        return new LongPrimitive(node.longValue());
                    }
                    if (node.isNumber()) {
                        return new LongPrimitive(node.longValue());
                    }
                    return new LongPrimitive(Long.parseLong(node.asText().trim()));
                case NUMBER:
                    return new NumberPrimitive(node.isNumber() ? node.doubleValue() : Double.parseDouble(node.asText().trim()));
                case BOOLEAN:
                    if (node.isBoolean()) {
                        return new BooleanPrimitive(node.booleanValue());
                    }
                    return new BooleanPrimitive(Boolean.parseBoolean(node.asText().trim()));
                case DATETIME:
                    // For every custom DATETIME parameter —
                    // even outside a recognized pair — run the same raw-relative defense as invoke_service,
                    // so a single lone DATETIME slot like _tool_exportLog(at:DATETIME) cannot accept "today"
                    // or "now-5m" silently. Typed exception bubbles up to AgentThing.executeCustomTool.
                    InvokeServiceDatetimeLiteralDefense.throwIfRejected(paramName,
                            node == null ? null : node.asText());
                    return new DatetimePrimitive(DateTime.parse(node.asText().trim()));
                case NOTHING:
                    return new StringPrimitive("");
                case TAGS:
                    return TagJsonCodec.parseTagCollectionPrimitive(node);
                case PASSWORD:
                case STRING:
                case TEXT:
                case JSON:
                case XML:
                case HYPERLINK:
                case IMAGELINK:
                case HTML:
                case GUID:
                case VARIANT:
                default:
                    if (node.isTextual()) {
                        return new StringPrimitive(node.asText());
                    }
                    if (node.isNumber() || node.isBoolean()) {
                        return new StringPrimitive(node.asText());
                    }
                    return new StringPrimitive(node.toString());
            }
        } catch (UnsupportedRelativeLiteralException e) {
            // Belt-and-suspenders re-throw: wrapping this generically would lose the wire envelope. Today the defense fires before the try; this guard keeps the
            // typed exception intact if a future inner case body adds a nested coercion that triggers it.
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Parameter \"" + paramName + "\": cannot convert to " + baseType + ": "
                    + e.getMessage());
        }
    }

    /**
     * LLM tool definition for a concrete service on a Thing when the LLM-visible name differs from the
     * {@link ServiceDefinition} name (configuration-repository {@code extended_tools.json}).
     */
    public static ToolDefinition toToolDefinitionForExtendedTool(ServiceDefinition sd, String llmFunctionName,
            String whenToUse, String titleFromManifest, boolean playbookSafe) {
        String description = whenToUse != null ? whenToUse : "";
        if (titleFromManifest != null && !titleFromManifest.isBlank()) {
            description = description + " Title: " + titleFromManifest.trim();
        }
        if (description.isEmpty()) {
            description = "Extended tool: " + llmFunctionName;
        }
        Map<String, Object> parametersSchema = buildParametersSchema(sd);
        CustomToolDateTimePairResolver.PairDetection pair = CustomToolDateTimePairResolver.detect(sd);
        if (pair.shouldAugmentSchema()) {
            // B17: emit the shared natural-time prose once on the tool description (not per field).
            description = description + CustomToolDateTimePairResolver.sharedNaturalTimeToolSuffix(pair);
        }
        return new ToolDefinition(llmFunctionName, description, parametersSchema, playbookSafe);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildParametersSchema(ServiceDefinition sd) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        try {
            var params = sd.getParameters();
            if (params != null && params.values() != null) {
                for (FieldDefinition fd : params.values()) {
                    String propName = fd.getName();
                    String baseTypeStr = fd.getBaseType() != null ? fd.getBaseType().name() : "STRING";
                    if ("VARIANT".equalsIgnoreCase(baseTypeStr)) {
                        throw new IllegalArgumentException("Custom tool \"" + sd.getName() + "\" parameter \"" + propName
                                + "\" is VARIANT; cannot build an LLM-fillable schema." + InfotableJsonCodec.CUSTOM_TOOL_HINT);
                    }
                    Map<String, Object> prop;
                    if ("INFOTABLE".equalsIgnoreCase(baseTypeStr)) {
                        prop = InfotableJsonCodec.parameterSchemaForLlm(fd);
                    } else {
                        String jsonType = baseTypeToJsonSchema(baseTypeStr);
                        prop = new LinkedHashMap<>();
                        prop.put("type", jsonType);
                        if (fd.getDescription() != null && !fd.getDescription().isEmpty()) {
                            prop.put("description", fd.getDescription());
                        }
                    }
                    properties.put(propName, prop);
                    if (!isOptional(fd)) {
                        required.add(propName);
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("CustomToolHarvester: could not read parameters for {}: {}", sd.getName(), e.getMessage());
        }

        // Augment the LLM-facing schema with synthetic
        // calendarPhrase / relativeDuration when a recognized DATETIME range pair exists and the service
        // does not itself declare those parameter names. Synthetic fields are NOT marked required —
        // they are alternatives to the explicit DATETIME pair.
        CustomToolDateTimePairResolver.PairDetection pair = CustomToolDateTimePairResolver.detect(sd);
        CustomToolDateTimePairResolver.augmentSchema(properties, pair);
        // When augmented, drop the recognized pair fields from
        // `required`. Logic is in CustomToolDateTimePairResolver.unhookPairFromRequired so it stays
        // offline-testable without booting LogUtilities.
        CustomToolDateTimePairResolver.unhookPairFromRequired(required, pair);

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static String baseTypeToJsonSchema(String baseType) {
        if (baseType == null) {
            return "string";
        }
        switch (baseType.toUpperCase()) {
            case "INTEGER":
            case "LONG":
                return "integer";
            case "NUMBER":
            case "DOUBLE":
                return "number";
            case "BOOLEAN":
                return "boolean";
            case "STRING":
            case "TEXT":
            case "PASSWORD":
            case "DATETIME":
            case "JSON":
            case "XML":
            default:
                return "string";
        }
    }

    private static boolean isOptional(FieldDefinition fd) {
        return ExtendedToolRequiredAspects.isOptional(fd);
    }
}
