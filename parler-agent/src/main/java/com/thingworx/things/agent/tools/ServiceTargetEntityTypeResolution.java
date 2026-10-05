package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.relationships.RelationshipTypes;

/**
 * Outcome of {@link ServiceTargetEntityTypeResolver#resolve(String, com.thingworx.things.agent.PromptContextCacheSnapshot)}.
 */
public final class ServiceTargetEntityTypeResolution {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RelationshipTypes.ThingworxRelationshipTypes effectiveRel;
    private final String effectiveEntityTypeName;
    private final boolean normalized;
    private final String originalEntityTypeName;
    private final String normalizedReason;
    private final String matchedTemplateName;
    private final String errorCode;
    private final String errorMessage;

    private ServiceTargetEntityTypeResolution(RelationshipTypes.ThingworxRelationshipTypes effectiveRel,
            String effectiveEntityTypeName, boolean normalized, String originalEntityTypeName,
            String normalizedReason, String matchedTemplateName, String errorCode, String errorMessage) {
        this.effectiveRel = effectiveRel;
        this.effectiveEntityTypeName = effectiveEntityTypeName;
        this.normalized = normalized;
        this.originalEntityTypeName = originalEntityTypeName;
        this.normalizedReason = normalizedReason;
        this.matchedTemplateName = matchedTemplateName;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static ServiceTargetEntityTypeResolution ok(RelationshipTypes.ThingworxRelationshipTypes effectiveRel,
            String originalEntityTypeName, boolean normalized, String normalizedReason, String matchedTemplateName) {
        return new ServiceTargetEntityTypeResolution(effectiveRel, effectiveRel.name(), normalized,
                originalEntityTypeName, normalizedReason, matchedTemplateName, null, null);
    }

    public static ServiceTargetEntityTypeResolution error(String errorCode, String errorMessage,
            String originalEntityTypeName) {
        return new ServiceTargetEntityTypeResolution(null, null, false, originalEntityTypeName,
                null, null, errorCode, errorMessage);
    }

    public boolean isError() {
        return errorCode != null;
    }

    public RelationshipTypes.ThingworxRelationshipTypes getEffectiveRel() {
        return effectiveRel;
    }

    public String getEffectiveEntityTypeName() {
        return effectiveEntityTypeName;
    }

    public boolean isNormalized() {
        return normalized;
    }

    public String getOriginalEntityTypeName() {
        return originalEntityTypeName;
    }

    public String getNormalizedReason() {
        return normalizedReason;
    }

    public String getMatchedTemplateName() {
        return matchedTemplateName;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /** Non-null when {@link #isNormalized()}. */
    public ObjectNode toEntityTypeNormalizedNode() {
        if (!normalized || originalEntityTypeName == null || effectiveEntityTypeName == null) {
            return null;
        }
        ObjectNode o = MAPPER.createObjectNode();
        o.put("from", originalEntityTypeName);
        o.put("to", effectiveEntityTypeName);
        if (normalizedReason != null) {
            o.put("reason", normalizedReason);
        }
        if (matchedTemplateName != null) {
            o.put("matchedThingTemplate", matchedTemplateName);
        }
        return o;
    }

    public void putEntityTypeNormalizedIfPresent(ObjectNode target) {
        ObjectNode meta = toEntityTypeNormalizedNode();
        if (meta != null) {
            target.set("entityTypeNormalized", meta);
        }
    }

    /** Appends normalization hint when {@link #isNormalized()} — for {@code ENTITY_NOT_FOUND}. */
    public String augmentEntityNotFoundMessage(String entityName, RelationshipTypes.ThingworxRelationshipTypes rel) {
        String base = "No entity named \"" + entityName + "\" of type " + rel.name();
        if (!normalized) {
            return base;
        }
        return base + ". entityType was normalized from \"" + originalEntityTypeName + "\" to \""
                + effectiveEntityTypeName + "\" because \"" + matchedTemplateName
                + "\" is a GenericThing-derived ThingTemplate name.";
    }
}
