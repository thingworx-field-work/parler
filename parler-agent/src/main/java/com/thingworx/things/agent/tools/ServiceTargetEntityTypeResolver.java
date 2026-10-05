package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.things.agent.ParlerProtectionAudit;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Resolves {@code entityType} for service-target tools ({@code invoke_service}, metadata discovery) to a root
 * {@link RelationshipTypes.ThingworxRelationshipTypes}, optionally normalizing GenericThing-derived ThingTemplate
 * names (e.g. {@code DataTable}) to {@code Thing}.
 */
public final class ServiceTargetEntityTypeResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String REASON_GENERIC_THING_TEMPLATE_NAME = "GENERIC_THING_TEMPLATE_NAME";

    private ServiceTargetEntityTypeResolver() {}

    public static ServiceTargetEntityTypeResolution resolve(String rawEntityType, PromptContextCacheSnapshot snap) {
        if (rawEntityType == null) {
            return resolveWithGenericThingNames(rawEntityType, Collections.emptyList());
        }
        String trimmed = rawEntityType.trim();
        if (trimmed.isEmpty()) {
            return resolveWithGenericThingNames(rawEntityType, Collections.emptyList());
        }
        RelationshipTypes.ThingworxRelationshipTypes asRoot = ThingworxRootEntityTypes.parseRootEntityType(trimmed);
        if (asRoot != null) {
            return ServiceTargetEntityTypeResolution.ok(asRoot, trimmed, false, null, null);
        }
        List<String> names = genericThingTemplateNames(snap);
        return resolveWithGenericThingNames(rawEntityType, names);
    }

    /**
     * Exposed for unit tests — supply the GenericThing-derived template name list directly (sorted or unsorted).
     */
    public static ServiceTargetEntityTypeResolution resolveWithGenericThingNames(String rawEntityType,
            List<String> genericThingTemplateNames) {
        if (rawEntityType == null) {
            return ServiceTargetEntityTypeResolution.error("UNKNOWN_ENTITY_TYPE",
                    "Unknown entityType. Use one of: " + ThingworxRootEntityTypes.formatAllowedRootsForMessage()
                            + ". For concrete DataTable, Stream, or ValueStream Things, use entityType=\"Thing\".",
                    null);
        }
        String raw = rawEntityType.trim();
        if (raw.isEmpty()) {
            return ServiceTargetEntityTypeResolution.error("UNKNOWN_ENTITY_TYPE",
                    "Unknown entityType. Use one of: " + ThingworxRootEntityTypes.formatAllowedRootsForMessage()
                            + ". For concrete DataTable, Stream, or ValueStream Things, use entityType=\"Thing\".",
                    raw);
        }

        RelationshipTypes.ThingworxRelationshipTypes asRoot = ThingworxRootEntityTypes.parseRootEntityType(raw);
        if (asRoot != null) {
            return ServiceTargetEntityTypeResolution.ok(asRoot, raw, false, null, null);
        }

        List<String> gtn = genericThingTemplateNames != null ? genericThingTemplateNames : Collections.emptyList();

        for (String t : gtn) {
            if (t != null && t.equals(raw)) {
                return ServiceTargetEntityTypeResolution.ok(RelationshipTypes.ThingworxRelationshipTypes.Thing, raw,
                        true, REASON_GENERIC_THING_TEMPLATE_NAME, t);
            }
        }

        List<String> ciMatches = new ArrayList<>();
        for (String t : gtn) {
            if (t != null && t.equalsIgnoreCase(raw)) {
                ciMatches.add(t);
            }
        }
        if (ciMatches.size() == 1) {
            String canon = ciMatches.get(0);
            return ServiceTargetEntityTypeResolution.ok(RelationshipTypes.ThingworxRelationshipTypes.Thing, raw,
                    true, REASON_GENERIC_THING_TEMPLATE_NAME, canon);
        }
        if (ciMatches.size() > 1) {
            return ServiceTargetEntityTypeResolution.error("AMBIGUOUS_GENERIC_THING_TEMPLATE_MATCH",
                    "entityType \"" + raw + "\" matches multiple GenericThing-derived ThingTemplate names case-insensitively: "
                            + ciMatches + ". Use exact platform spelling.",
                    raw);
        }

        RelationshipTypes.ThingworxRelationshipTypes relEnum = parseAnyRelationshipEnum(raw);
        if (relEnum != null) {
            if (ThingworxRootEntityTypes.isRootEntityType(relEnum)) {
                return ServiceTargetEntityTypeResolution.ok(relEnum, raw, false, null, null);
            }
            return ServiceTargetEntityTypeResolution.error("UNSUPPORTED_ENTITY_TYPE_FOR_SERVICE_TARGET",
                    "entityType \"" + relEnum.name()
                            + "\" is a ThingWorx relationship/data type, not a root entity service target. "
                            + "Use one of: " + ThingworxRootEntityTypes.formatAllowedRootsForMessage()
                            + ". For concrete DataTable, Stream, or ValueStream instances, use entityType=\"Thing\" "
                            + "and the concrete entityName.",
                    raw);
        }

        return ServiceTargetEntityTypeResolution.error("UNKNOWN_ENTITY_TYPE",
                "Unknown entityType: \"" + raw + "\". Use one of: "
                        + ThingworxRootEntityTypes.formatAllowedRootsForMessage()
                        + ". For concrete DataTable, Stream, or ValueStream Things, use entityType=\"Thing\".",
                raw);
    }

    private static RelationshipTypes.ThingworxRelationshipTypes parseAnyRelationshipEnum(String raw) {
        for (RelationshipTypes.ThingworxRelationshipTypes e : RelationshipTypes.ThingworxRelationshipTypes.values()) {
            if (e.name().equalsIgnoreCase(raw.trim())) {
                return e;
            }
        }
        return null;
    }

    private static List<String> genericThingTemplateNames(PromptContextCacheSnapshot snap) {
        if (snap != null) {
            List<String> cached = snap.getGenericThingTemplateNames();
            return cached != null ? cached : Collections.emptyList();
        }
        try {
            List<String> live = GenericThingIncomingDependencyResolver.fetchSortedThingTemplateNames();
            return live != null ? live : Collections.emptyList();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    /**
     * Pre-HITL: normalize {@code invoke_service} arguments when resolution maps a template name to {@code Thing};
     * hoists service-defined top-level fields into {@code parameters}; returns early error JSON when resolution
     * fails or required service parameters are still missing after repair.
     */
    public static InvokeServiceToolPrep prepareInvokeServiceToolCall(ToolCall toolCall,
            PromptContextCacheSnapshot snap) {
        if (toolCall == null || !"invoke_service".equals(toolCall.getFunctionName())) {
            return new InvokeServiceToolPrep(toolCall, null, null,
                    InvokeServiceParameterNormalizer.Repair.none());
        }
        try {
            String origArgs = toolCall.getArguments() == null ? "{}" : toolCall.getArguments();
            JsonNode rootNode = MAPPER.readTree(origArgs);
            if (!rootNode.isObject()) {
                return new InvokeServiceToolPrep(toolCall, null, null,
                        InvokeServiceParameterNormalizer.Repair.none());
            }
            ObjectNode work = (ObjectNode) rootNode.deepCopy();
            String et = text(work, "entityType");
            if (et == null || et.isEmpty()) {
                return new InvokeServiceToolPrep(toolCall, null, null,
                        InvokeServiceParameterNormalizer.Repair.none());
            }
            ServiceTargetEntityTypeResolution res = resolve(et, snap);
            if (res.isError()) {
                String json = InvokeServiceExecutor.toolErrorJson(res.getErrorCode(), res.getErrorMessage(), null);
                return new InvokeServiceToolPrep(toolCall, json, res,
                        InvokeServiceParameterNormalizer.Repair.none());
            }
            boolean entityTypeRewritten = res.isNormalized();
            if (entityTypeRewritten) {
                work.put("entityType", res.getEffectiveEntityTypeName());
            }

            String entityNameStr = text(work, "entityName");
            String serviceNameStr = text(work, "serviceName");
            if (res.getEffectiveRel() == RelationshipTypes.ThingworxRelationshipTypes.Thing) {
                ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                        ScalarThingnamePreflight.gateApplicationThing("entityName", entityNameStr);
                if (thingGate.isError()) {
                    return prepEarlyInvokeError(toolCall, work, origArgs, res, entityTypeRewritten, thingGate.errorJson,
                            InvokeServiceParameterNormalizer.Repair.none());
                }
                work.put("entityName", thingGate.canonicalThingName);
                entityNameStr = thingGate.canonicalThingName;
            }
            if (entityNameStr == null || entityNameStr.isEmpty() || serviceNameStr == null || serviceNameStr.isEmpty()) {
                return finishPrepAfterEntityRewrite(toolCall, work, origArgs, res, entityTypeRewritten,
                        InvokeServiceParameterNormalizer.Repair.none());
            }

            try {
                RelationshipTypes.ThingworxRelationshipTypes rel = res.getEffectiveRel();
                RootEntity entity = PlatformAccess.findAsUser(entityNameStr, rel);
                if (!(entity instanceof IServiceProvider)) {
                    return finishPrepAfterEntityRewrite(toolCall, work, origArgs, res, entityTypeRewritten,
                            InvokeServiceParameterNormalizer.Repair.none());
                }
                ServiceDefinition sd = ((IServiceProvider) entity).getInstanceServiceDefinition(serviceNameStr);
                if (sd == null || InvokeServiceExecutor.isExplicitRemoteOnlyService(sd)) {
                    return finishPrepAfterEntityRewrite(toolCall, work, origArgs, res, entityTypeRewritten,
                            InvokeServiceParameterNormalizer.Repair.none());
                }
                InvokeServiceParameterNormalizer.Repair repair =
                        InvokeServiceParameterNormalizer.mergeTopLevelServiceFieldsIntoRoot(work, sd, MAPPER);
                if (ProtectedValuePolicy.invokeServiceSuppliesPasswordParameter(sd, work.get("parameters"))) {
                    String err = InvokeServiceExecutor.toolErrorJson(ProtectedValuePolicy.CODE_INPUT_BLOCKED,
                            "Cannot supply PASSWORD parameters via the agent.", res, repair);
                    return prepEarlyInvokeError(toolCall, work, origArgs, res, entityTypeRewritten, err, repair);
                }
                String nvq = InvokeServiceNamedVtqProtection.blockedMessageForPasswordNamedVtqWrite(entity,
                        serviceNameStr, sd, work);
                if (nvq != null) {
                    ParlerProtectionAudit.blocked(ProtectedValuePolicy.CODE_WRITE_BLOCKED, "invoke_service",
                            entityNameStr + "." + serviceNameStr);
                    String err = InvokeServiceExecutor.toolErrorJson(ProtectedValuePolicy.CODE_WRITE_BLOCKED, nvq, res,
                            repair);
                    return prepEarlyInvokeError(toolCall, work, origArgs, res, entityTypeRewritten, err, repair);
                }
                return finishPrepAfterEntityRewrite(toolCall, work, origArgs, res, entityTypeRewritten, repair);
            } catch (Exception ignored) {
                return finishPrepAfterEntityRewrite(toolCall, work, origArgs, res, entityTypeRewritten,
                        InvokeServiceParameterNormalizer.Repair.none());
            }
        } catch (Exception ignored) {
            return new InvokeServiceToolPrep(toolCall, null, null,
                    InvokeServiceParameterNormalizer.Repair.none());
        }
    }

    /**
     * Task-state and HITL consumers need the same merged {@link ToolCall} arguments on early errors as on success
     * whenever {@code work} differs from the original args (entity-type normalization, parameter hoisting).
     */
    private static InvokeServiceToolPrep prepEarlyInvokeError(ToolCall toolCall, ObjectNode work, String origArgs,
            ServiceTargetEntityTypeResolution res, boolean entityTypeRewritten, String earlyErrorJson,
            InvokeServiceParameterNormalizer.Repair prepRepair) {
        ToolCall tracked = mergedInvokeToolCallOrOriginal(toolCall, work, origArgs, entityTypeRewritten);
        return new InvokeServiceToolPrep(tracked, earlyErrorJson, res,
                prepRepair != null ? prepRepair : InvokeServiceParameterNormalizer.Repair.none());
    }

    private static ToolCall mergedInvokeToolCallOrOriginal(ToolCall toolCall, ObjectNode work, String origArgs,
            boolean entityTypeRewritten) {
        try {
            JsonNode origParsed = MAPPER.readTree(origArgs);
            if (!work.equals(origParsed)) {
                String newArgs = MAPPER.writeValueAsString(work);
                return new ToolCall(toolCall.getId(), toolCall.getFunctionName(), newArgs);
            }
        } catch (Exception ignored) {
            // fall through
        }
        if (entityTypeRewritten) {
            try {
                String newArgs = MAPPER.writeValueAsString(work);
                return new ToolCall(toolCall.getId(), toolCall.getFunctionName(), newArgs);
            } catch (Exception ignored) {
                return toolCall;
            }
        }
        return toolCall;
    }

    private static InvokeServiceToolPrep finishPrepAfterEntityRewrite(ToolCall toolCall, ObjectNode work,
            String origArgs, ServiceTargetEntityTypeResolution res, boolean entityTypeRewritten,
            InvokeServiceParameterNormalizer.Repair parameterRepair) {
        InvokeServiceParameterNormalizer.Repair pr =
                parameterRepair != null ? parameterRepair : InvokeServiceParameterNormalizer.Repair.none();
        ToolCall tracked = mergedInvokeToolCallOrOriginal(toolCall, work, origArgs, entityTypeRewritten);
        try {
            JsonNode origParsed = MAPPER.readTree(origArgs);
            if (!work.equals(origParsed)) {
                return new InvokeServiceToolPrep(tracked, null, entityTypeRewritten ? res : null, pr);
            }
        } catch (Exception ignored) {
            // fall through
        }
        if (entityTypeRewritten) {
            return new InvokeServiceToolPrep(tracked, null, res, pr);
        }
        return new InvokeServiceToolPrep(tracked, null, entityTypeRewritten ? res : null, pr);
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
    }

    /** Result of {@link #prepareInvokeServiceToolCall(ToolCall, PromptContextCacheSnapshot)}. */
    public static final class InvokeServiceToolPrep {
        private final ToolCall toolCall;
        private final String earlyErrorJson;
        private final ServiceTargetEntityTypeResolution resolution;
        /** Pre-HITL parameter hoisting repair for {@code parametersNormalized}; never null. */
        private final InvokeServiceParameterNormalizer.Repair parameterRepair;

        InvokeServiceToolPrep(ToolCall toolCall, String earlyErrorJson, ServiceTargetEntityTypeResolution resolution,
                InvokeServiceParameterNormalizer.Repair parameterRepair) {
            this.toolCall = toolCall;
            this.earlyErrorJson = earlyErrorJson;
            this.resolution = resolution;
            this.parameterRepair = parameterRepair != null ? parameterRepair
                    : InvokeServiceParameterNormalizer.Repair.none();
        }

        public ToolCall getToolCall() {
            return toolCall;
        }

        /** When non-null, return this JSON immediately (do not enqueue HITL). */
        public String getEarlyErrorJson() {
            return earlyErrorJson;
        }

        public ServiceTargetEntityTypeResolution getResolution() {
            return resolution;
        }

        public InvokeServiceParameterNormalizer.Repair getParameterRepair() {
            return parameterRepair;
        }
    }
}
