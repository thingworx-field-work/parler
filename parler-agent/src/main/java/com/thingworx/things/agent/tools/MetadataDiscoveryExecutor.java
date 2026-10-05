package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.things.Thing;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.metadata.collections.ServiceDefinitionCollection;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.configrepo.ExtendedToolDefinition;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;

import org.slf4j.Logger;
import com.thingworx.logging.LogUtilities;

/**
 * Built-in metadata discovery: {@code discover_services}, {@code get_service_definition}, {@code discover_properties},
 * and Thing-targeted paths that delegate to {@link DiscoverThingMembersExecutor}.
 *
 * @see docs/agent/metadata_discovery.md
 * @see docs/agent/thing-member-discovery.md
 */
public final class MetadataDiscoveryExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(MetadataDiscoveryExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int DEFAULT_MAX_ITEMS = 80;
    private static final int MAX_MAX_ITEMS = 200;
    private static final int DESC_TRUNCATE = 400;

    private MetadataDiscoveryExecutor() {}

    private static PromptContextCacheSnapshot promptSnapshotOrNull() {
        AgentThing at = AgentToolContext.getAgentThing();
        return at != null ? at.getPromptContextSnapshot() : null;
    }

    /** Bug 009: prefer named extended tool in {@code invokeExample} when this service is wrapped. */
    static ObjectNode buildInvokeExampleForResolvedService(
            PromptContextCacheSnapshot promptSnapshotOrNull,
            String effectiveEntityType,
            String entityName,
            ServiceDefinition sd) {
        ExtendedToolDefinition extMatch = null;
        if (promptSnapshotOrNull != null) {
            extMatch = promptSnapshotOrNull.getExtendedToolRegistry()
                    .findByTargetService(entityName, sd.getName())
                    .orElse(null);
        }
        return InvokeServiceInvokeExampleJson.build(MAPPER, effectiveEntityType, entityName,
                sd.getName(), sd.getParameters(), extMatch);
    }

    public static String executeDiscoverServices(ToolCall call) {
        try {
            return doDiscoverServices(call);
        } catch (Exception e) {
            LOG.warn("discover_services: {}", e.getMessage());
            return errorJson("DISCOVER_SERVICES_ERROR", e.getMessage());
        }
    }

    public static String executeGetServiceDefinition(ToolCall call) {
        try {
            return doGetServiceDefinition(call);
        } catch (Exception e) {
            LOG.warn("get_service_definition: {}", e.getMessage());
            return errorJson("GET_SERVICE_DEFINITION_ERROR", e.getMessage());
        }
    }

    public static String executeDiscoverProperties(ToolCall call) {
        try {
            return doDiscoverProperties(call);
        } catch (Exception e) {
            LOG.warn("discover_properties: {}", e.getMessage());
            return errorJson("DISCOVER_PROPERTIES_ERROR", e.getMessage());
        }
    }

    private static String metaWarn(String tool, String code, String message) {
        LOG.warn("{} [{}]: {}", tool, code, message);
        return errorJson(code, message);
    }

    /**
     * Pure mapping: successful {@code discover_thing_members} (facet=properties) JSON → legacy
     * {@code discover_properties} success body.
     */
    static ObjectNode discoverPropertiesLegacySuccessFromInner(JsonNode innerBody, String thingNameFallback)
            throws Exception {
        String resolved = innerBody.path("entityName").asText(thingNameFallback);
        ObjectNode out = baseSuccessProps("Thing", resolved);
        out.set("properties", innerBody.get("items").deepCopy());
        out.put("hasMore", innerBody.path("hasMore").asBoolean());
        out.put("offset", innerBody.path("offset").asInt());
        out.put("totalMatched", innerBody.path("totalMatched").asInt());
        out.put("propertySource", "discover_thing_members");
        return out;
    }

    /**
     * Pure mapping: successful {@code discover_thing_members} (facet=services) JSON → legacy Thing-targeted
     * {@code discover_services} success body.
     */
    static ObjectNode discoverServicesThingLegacySuccessFromInner(JsonNode innerBody,
            ServiceTargetEntityTypeResolution tr, String entityName) throws Exception {
        ObjectNode out = baseSuccess(tr.getEffectiveEntityTypeName(), entityName);
        tr.putEntityTypeNormalizedIfPresent(out);
        out.set("services", innerBody.get("items").deepCopy());
        out.put("hasMore", innerBody.path("hasMore").asBoolean());
        out.put("offset", innerBody.path("offset").asInt());
        out.put("totalMatched", innerBody.path("totalMatched").asInt());
        return out;
    }

    /**
     * Pure mapping: successful {@code discover_thing_members} (facet=service) JSON → legacy Thing-targeted
     * {@code get_service_definition} success body.
     */
    static ObjectNode getServiceDefinitionThingLegacySuccessFromInner(JsonNode innerBody,
            ServiceTargetEntityTypeResolution tr, String entityName) throws Exception {
        JsonNode svc = innerBody.get("service");
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("entityType", tr.getEffectiveEntityTypeName());
        out.put("entityName", entityName);
        out.put("serviceName", svc.path("name").asText());
        out.put("description", svc.path("description").asText(""));
        out.set("parameters", svc.get("parameters").deepCopy());
        out.set("result", svc.get("result").deepCopy());
        if (innerBody.has("invokeExample")) {
            out.set("invokeExample", innerBody.get("invokeExample").deepCopy());
        }
        tr.putEntityTypeNormalizedIfPresent(out);
        return out;
    }

    /** Thing-path {@code discover_properties}: inner JSON from {@code discover_thing_members} → legacy envelope. */
    static String discoverPropertiesLegacyResponseFromInner(String innerJson, String thingName) throws Exception {
        JsonNode body = MAPPER.readTree(innerJson);
        if (!"success".equals(body.path("status").asText())) {
            String code = body.path("code").asText();
            if ("THING_NOT_FOUND_OR_NOT_VISIBLE".equals(code)) {
                return identityResolutionRequiredForDiscoverProperties(thingName);
            }
            if ("IDENTITY_RESOLUTION_REQUIRED".equals(code) || "THINGNAME_VALUE_REQUIRED".equals(code)) {
                return innerJson;
            }
            return innerJson;
        }
        return MAPPER.writeValueAsString(discoverPropertiesLegacySuccessFromInner(body, thingName));
    }

    /** Thing-path {@code discover_services}: inner JSON → legacy envelope or error remap. */
    static String discoverServicesThingResponseFromInner(String innerJson, ServiceTargetEntityTypeResolution tr,
            String entityName) throws Exception {
        JsonNode body = MAPPER.readTree(innerJson);
        if (!"success".equals(body.path("status").asText())) {
            return mapThingMemberErrorToDiscoverServices(innerJson, body, entityName);
        }
        return MAPPER.writeValueAsString(discoverServicesThingLegacySuccessFromInner(body, tr, entityName));
    }

    /** Thing-path {@code get_service_definition}: inner JSON → legacy envelope or error remap. */
    static String getServiceDefinitionThingResponseFromInner(String innerJson, ServiceTargetEntityTypeResolution tr,
            String entityName, String serviceName) throws Exception {
        JsonNode body = MAPPER.readTree(innerJson);
        if (!"success".equals(body.path("status").asText())) {
            String code = body.path("code").asText();
            if ("SERVICE_NOT_FOUND_OR_NOT_VISIBLE".equals(code)) {
                return metaWarn("get_service_definition", "SERVICE_NOT_FOUND",
                        "Service \"" + serviceName + "\" not on this instance. Use discover_services. Hint: check exact name spelling.");
            }
            if ("THING_NOT_FOUND_OR_NOT_VISIBLE".equals(code)) {
                return ScalarThingnamePreflight.identityResolutionRequiredJson("entityName",
                        suppliedValueForThingMemberRemap(body, entityName));
            }
            if ("IDENTITY_RESOLUTION_REQUIRED".equals(code) || "THINGNAME_VALUE_REQUIRED".equals(code)) {
                return innerJson;
            }
            return innerJson;
        }
        return MAPPER.writeValueAsString(getServiceDefinitionThingLegacySuccessFromInner(body, tr, entityName));
    }

    private static String discoverServicesForThingInstance(JsonNode root, ServiceTargetEntityTypeResolution tr,
            String entityName) throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("thingName", entityName);
        args.put("facet", "services");
        copyOptionalText(root, args, "namePrefix");
        copyOptionalText(root, args, "category");
        copyOptionalText(root, args, "baseType");
        if (root.has("offset")) {
            args.put("offset", root.get("offset").asInt(0));
        }
        if (root.has("maxItems")) {
            args.put("maxItems", root.get("maxItems").asInt(DEFAULT_MAX_ITEMS));
        }
        String inner = DiscoverThingMembersExecutor.executeJsonArguments(MAPPER.writeValueAsString(args));
        String mapped = discoverServicesThingResponseFromInner(inner, tr, entityName);
        if (MAPPER.readTree(mapped).path("status").asText().equals("success")) {
            JsonNode body = MAPPER.readTree(inner);
            LOG.info("discover_services done (thing-member): {} / {} totalMatched={} returned={} hasMore={}",
                    tr.getEffectiveEntityTypeName(), entityName, body.path("totalMatched").asInt(),
                    body.path("returned").asInt(), body.path("hasMore").asBoolean());
        }
        return mapped;
    }

    private static String getServiceDefinitionForThingInstance(JsonNode root, ServiceTargetEntityTypeResolution tr,
            String entityName, String serviceName) throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("thingName", entityName);
        args.put("facet", "service");
        args.put("memberName", serviceName);
        String inner = DiscoverThingMembersExecutor.executeJsonArguments(MAPPER.writeValueAsString(args));
        String out = getServiceDefinitionThingResponseFromInner(inner, tr, entityName, serviceName);
        if (MAPPER.readTree(out).path("status").asText().equals("success")) {
            JsonNode body = MAPPER.readTree(inner);
            JsonNode svc = body.get("service");
            int paramCount = svc != null && svc.has("parameters") && svc.get("parameters").isArray()
                    ? svc.get("parameters").size()
                    : 0;
            LOG.info("get_service_definition done (thing-member): {} params={}", serviceName, paramCount);
        }
        return out;
    }

    private static void copyOptionalText(JsonNode from, ObjectNode to, String field) {
        if (from.has(field) && !from.get(field).isNull()) {
            to.put(field, from.get(field).asText());
        }
    }

    private static String suppliedValueForThingMemberRemap(JsonNode body, String outerFallback) {
        String sv = body.has("suppliedValue") && !body.get("suppliedValue").isNull()
                ? body.path("suppliedValue").asText("")
                : "";
        if (sv.isBlank() && outerFallback != null) {
            sv = outerFallback.trim();
        }
        return sv;
    }

    private static String mapThingMemberErrorToDiscoverServices(String innerJson, JsonNode body, String entityName) {
        String code = body.path("code").asText();
        if ("IDENTITY_RESOLUTION_REQUIRED".equals(code) || "THINGNAME_VALUE_REQUIRED".equals(code)) {
            return innerJson;
        }
        if ("THING_NOT_FOUND_OR_NOT_VISIBLE".equals(code)) {
            return ScalarThingnamePreflight.identityResolutionRequiredJson("entityName",
                    suppliedValueForThingMemberRemap(body, entityName));
        }
        return innerJson;
    }

    /**
     * Shared entity resolution for {@code discover_services} / {@code get_service_definition}: Thing roots use
     * {@link ScalarThingnamePreflight#gateApplicationThing}; non-Thing roots keep legacy {@code MISSING_ENTITY_NAME} /
     * {@link com.thingworx.things.agent.PlatformAccess#findAsUser} lookup
     * (TAXONOMY_RESOLVER section 7.6 parity with {@code invoke_service}).
     */
    private static final class ServiceMetadataEntityResolution {
        final String errorJson;
        final RootEntity entity;
        /** Non-null when {@link #entity} is a gated {@link Thing} — use for Thing-path delegation to inner tool. */
        final String canonicalThingNameForDelegate;

        private ServiceMetadataEntityResolution(String errorJson, RootEntity entity, String canonicalThingNameForDelegate) {
            this.errorJson = errorJson;
            this.entity = entity;
            this.canonicalThingNameForDelegate = canonicalThingNameForDelegate;
        }
    }

    private static ServiceMetadataEntityResolution resolveServiceMetadataEntity(String tool,
            ServiceTargetEntityTypeResolution tr, String entityName) throws Exception {
        RelationshipTypes.ThingworxRelationshipTypes rel = tr.getEffectiveRel();
        if (rel == RelationshipTypes.ThingworxRelationshipTypes.Thing) {
            ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                    ScalarThingnamePreflight.gateApplicationThing("entityName", entityName);
            if (thingGate.isError()) {
                return new ServiceMetadataEntityResolution(thingGate.errorJson, null, null);
            }
            return new ServiceMetadataEntityResolution(null, thingGate.thing, thingGate.canonicalThingName);
        }
        if (entityName == null || entityName.isEmpty()) {
            return new ServiceMetadataEntityResolution(
                    metaWarn(tool, "MISSING_ENTITY_NAME", "entityName is required"), null, null);
        }
        RootEntity entity;
        try {
            entity = PlatformAccess.findAsUser(entityName, rel);
        } catch (Exception e) {
            return new ServiceMetadataEntityResolution(metaWarn(tool, "ENTITY_NOT_FOUND", e.getMessage()), null, null);
        }
        if (entity == null) {
            return new ServiceMetadataEntityResolution(
                    metaWarn(tool, "ENTITY_NOT_FOUND", tr.augmentEntityNotFoundMessage(entityName, rel)), null, null);
        }
        return new ServiceMetadataEntityResolution(null, entity, null);
    }

    private static String doDiscoverServices(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String et = text(root, "entityType");
        String en = text(root, "entityName");
        if (et == null || et.isEmpty()) {
            return metaWarn("discover_services", "MISSING_ENTITY_TYPE", "entityType is required");
        }
        LOG.info("discover_services start: {} / {}", et, en);
        ServiceTargetEntityTypeResolution tr =
                ServiceTargetEntityTypeResolver.resolve(et, promptSnapshotOrNull());
        if (tr.isError()) {
            return metaWarn("discover_services", tr.getErrorCode(), tr.getErrorMessage());
        }
        ServiceMetadataEntityResolution entityRes = resolveServiceMetadataEntity("discover_services", tr, en);
        if (entityRes.errorJson != null) {
            return entityRes.errorJson;
        }
        RootEntity entity = entityRes.entity;
        String delegateThingName = entityRes.canonicalThingNameForDelegate != null ? entityRes.canonicalThingNameForDelegate : en;
        if (entity instanceof Thing) {
            return discoverServicesForThingInstance(root, tr, delegateThingName);
        }
        if (!(entity instanceof IServiceProvider)) {
            return metaWarn("discover_services", "NOT_SERVICE_PROVIDER",
                    "This entity type does not expose instance services. Try Thing, ThingTemplate, ThingShape, etc.");
        }
        ServiceDefinitionCollection defs = ((IServiceProvider) entity).getInstanceServiceDefinitions();
        if (defs == null || defs.values() == null) {
            LOG.info("discover_services: no service definitions on {} / {}", et, en);
            ObjectNode out = baseSuccess(tr.getEffectiveEntityTypeName(), en);
            tr.putEntityTypeNormalizedIfPresent(out);
            out.putArray("services");
            out.put("hasMore", false);
            out.put("totalMatched", 0);
            return MAPPER.writeValueAsString(out);
        }
        String prefix = text(root, "namePrefix");
        List<ServiceDefinition> matched = new ArrayList<>();
        for (ServiceDefinition sd : defs.values()) {
            String n = sd.getName();
            if (n == null) {
                continue;
            }
            if (prefix != null && !prefix.isEmpty() && !n.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                continue;
            }
            matched.add(sd);
        }
        matched.sort(Comparator.comparing(sd -> sd.getName() != null ? sd.getName() : ""));
        int offset = root.has("offset") ? Math.max(0, root.get("offset").asInt(0)) : 0;
        int maxItems = root.has("maxItems") ? root.get("maxItems").asInt(DEFAULT_MAX_ITEMS) : DEFAULT_MAX_ITEMS;
        maxItems = Math.min(Math.max(1, maxItems), MAX_MAX_ITEMS);
        int total = matched.size();
        int end = Math.min(offset + maxItems, total);
        ArrayNode arr = MAPPER.createArrayNode();
        for (int i = offset; i < end; i++) {
            ServiceDefinition sd = matched.get(i);
            ObjectNode row = MAPPER.createObjectNode();
            row.put("name", sd.getName());
            String d = sd.getDescription();
            if (d != null && d.length() > DESC_TRUNCATE) {
                d = d.substring(0, DESC_TRUNCATE) + "...";
            }
            row.put("description", d != null ? d : "");
            arr.add(row);
        }
        ObjectNode out = baseSuccess(tr.getEffectiveEntityTypeName(), en);
        tr.putEntityTypeNormalizedIfPresent(out);
        out.set("services", arr);
        out.put("hasMore", end < total);
        out.put("offset", offset);
        out.put("totalMatched", total);
        LOG.info("discover_services done: {} / {} totalMatched={} returned={} hasMore={}",
                et, en, total, end - offset, end < total);
        return MAPPER.writeValueAsString(out);
    }

    private static String doGetServiceDefinition(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String et = text(root, "entityType");
        String en = text(root, "entityName");
        String serviceName = text(root, "serviceName");
        if (et == null || et.isEmpty()) {
            return metaWarn("get_service_definition", "MISSING_ENTITY_TYPE", "entityType is required");
        }
        if (serviceName == null || serviceName.isEmpty()) {
            return metaWarn("get_service_definition", "MISSING_SERVICE_NAME", "serviceName is required");
        }
        LOG.info("get_service_definition start: {} / {} / {}", et, en, serviceName);
        ServiceTargetEntityTypeResolution tr =
                ServiceTargetEntityTypeResolver.resolve(et, promptSnapshotOrNull());
        if (tr.isError()) {
            return metaWarn("get_service_definition", tr.getErrorCode(), tr.getErrorMessage());
        }
        ServiceMetadataEntityResolution entityRes = resolveServiceMetadataEntity("get_service_definition", tr, en);
        if (entityRes.errorJson != null) {
            return entityRes.errorJson;
        }
        RootEntity entity = entityRes.entity;
        String delegateThingName = entityRes.canonicalThingNameForDelegate != null ? entityRes.canonicalThingNameForDelegate : en;
        if (entity instanceof Thing) {
            return getServiceDefinitionForThingInstance(root, tr, delegateThingName, serviceName);
        }
        if (!(entity instanceof IServiceProvider)) {
            return metaWarn("get_service_definition", "NOT_SERVICE_PROVIDER", "Entity does not expose services.");
        }
        ServiceDefinition sd;
        try {
            sd = ((IServiceProvider) entity).getInstanceServiceDefinition(serviceName);
        } catch (Exception e) {
            return metaWarn("get_service_definition", "SERVICE_LOOKUP_FAILED", e.getMessage());
        }
        if (sd == null) {
            return metaWarn("get_service_definition", "SERVICE_NOT_FOUND",
                    "Service \"" + serviceName + "\" not on this instance. Use discover_services. Hint: check exact name spelling.");
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("entityType", tr.getEffectiveEntityTypeName());
        out.put("entityName", en);
        out.put("serviceName", sd.getName());
        String desc = sd.getDescription();
        if (desc != null && desc.length() > DESC_TRUNCATE) {
            desc = desc.substring(0, DESC_TRUNCATE) + "...";
        }
        out.put("description", desc != null ? desc : "");

        ArrayNode params = MAPPER.createArrayNode();
        FieldDefinitionCollection pdefs = sd.getParameters();
        if (pdefs != null && pdefs.values() != null) {
            for (FieldDefinition fd : pdefs.values()) {
                if (fd.getName() == null) {
                    continue;
                }
                ObjectNode p = MAPPER.createObjectNode();
                p.put("name", fd.getName());
                p.put("baseType", fd.getBaseType() != null ? fd.getBaseType().name() : "STRING");
                p.put("required", isRequiredParam(fd));
                if (fd.getBaseType() == BaseTypes.INFOTABLE) {
                    String ds = aspectString(fd, "dataShape");
                    if (ds != null && !ds.isEmpty()) {
                        p.put("dataShape", ds);
                    }
                }
                params.add(p);
            }
        }
        out.set("parameters", params);

        ObjectNode resultTypeNode = MAPPER.createObjectNode();
        FieldDefinition rt = sd.getResultType();
        if (rt != null && rt.getBaseType() != null) {
            resultTypeNode.put("baseType", rt.getBaseType().name());
            if (rt.getBaseType() == BaseTypes.INFOTABLE) {
                String ds = aspectString(rt, "dataShape");
                if (ds != null && !ds.isEmpty()) {
                    resultTypeNode.put("dataShape", ds);
                }
            }
        } else {
            resultTypeNode.put("baseType", "NOTHING");
        }
        out.set("result", resultTypeNode);
        out.set("invokeExample", buildInvokeExampleForResolvedService(promptSnapshotOrNull(),
                tr.getEffectiveEntityTypeName(), en, sd));
        tr.putEntityTypeNormalizedIfPresent(out);
        int paramCount = params != null ? params.size() : 0;
        LOG.info("get_service_definition done: {} params={}", serviceName, paramCount);
        return MAPPER.writeValueAsString(out);
    }

    private static String doDiscoverProperties(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String thingName = text(root, "thingName");
        if (thingName == null || thingName.isBlank()) {
            return ScalarThingnamePreflight.thingNameValueRequiredJson("thingName");
        }
        LOG.info("discover_properties start: Thing / {}", thingName);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("thingName", thingName);
        args.put("facet", "properties");
        copyOptionalText(root, args, "namePrefix");
        copyOptionalText(root, args, "category");
        copyOptionalText(root, args, "baseType");
        copyOptionalText(root, args, "dataShape");
        if (root.has("offset")) {
            args.put("offset", root.get("offset").asInt(0));
        }
        if (root.has("maxItems")) {
            args.put("maxItems", root.get("maxItems").asInt(DEFAULT_MAX_ITEMS));
        }
        String inner = DiscoverThingMembersExecutor.executeJsonArguments(MAPPER.writeValueAsString(args));
        String out = discoverPropertiesLegacyResponseFromInner(inner, thingName);
        if (MAPPER.readTree(out).path("status").asText().equals("success")) {
            JsonNode body = MAPPER.readTree(inner);
            String resolved = body.path("entityName").asText(thingName);
            LOG.info("discover_properties done: Thing / {} totalMatched={} returned={} hasMore={}",
                    resolved, body.path("totalMatched").asInt(), body.path("returned").asInt(),
                    body.path("hasMore").asBoolean());
        }
        return out;
    }

    static String identityResolutionRequiredForDiscoverProperties(String suppliedValue) {
        return ScalarThingnamePreflight.identityResolutionRequiredJson("thingName",
                suppliedValue != null ? suppliedValue : "");
    }

    private static ObjectNode baseSuccess(String entityType, String entityName) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("entityType", entityType);
        out.put("entityName", entityName);
        return out;
    }

    private static ObjectNode baseSuccessProps(String entityType, String entityName) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("entityType", entityType);
        out.put("entityName", entityName);
        return out;
    }

    private static boolean isRequiredParam(FieldDefinition fd) {
        try {
            if (fd.getAspects() == null) {
                return true;
            }
            Object v = fd.getAspects().getClass().getMethod("get", Object.class).invoke(fd.getAspects(), "isRequired");
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
            if (v != null && "false".equalsIgnoreCase(v.toString())) {
                return false;
            }
        } catch (Exception ignored) {
            // default required
        }
        return true;
    }

    private static String aspectString(FieldDefinition fd, String key) {
        try {
            Object aspects = fd.getAspects();
            if (aspects == null) {
                return null;
            }
            Object v = aspects.getClass().getMethod("get", Object.class).invoke(aspects, key);
            return v != null ? v.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
    }

    private static String errorJson(String code, String message) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message == null ? "" : message);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + code + "\"}";
        }
    }
}
