package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.types.BaseTypes;

/**
 * Before invoking a configuration-repository extended tool (or playbook extended tool), validates
 * {@code THINGNAME} service parameters against the platform entity registry so short labels do not
 * reach ThingWorx as silent empty-result queries.
 *
 * <p>Scalar checks and JSON envelopes are delegated to {@link ScalarThingnamePreflight} (visibility-aware
 * {@code findEntity} gate; shared messages with first-party tools).
 */
public final class ExtendedToolThingnamePreflight {

    private ExtendedToolThingnamePreflight() {}

    /**
     * @deprecated Use {@link ScalarThingnamePreflight#clearModelGateOverrideForTests()}.
     */
    @Deprecated
    static void clearAccessibleThingOverrideForTests() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
    }

    /**
     * @return {@code null} when all {@code THINGNAME} inputs pass; otherwise UTF-8 JSON tool error text
     */
    public static String checkJsonArgs(ServiceDefinition sd, JsonNode root) {
        if (sd == null || root == null || !root.isObject()) {
            return null;
        }
        var params = sd.getParameters();
        if (params == null || params.values() == null) {
            return null;
        }
        for (FieldDefinition fd : params.values()) {
            BaseTypes bt = fd.getBaseType() != null ? fd.getBaseType() : BaseTypes.STRING;
            if (!isThingnameBaseType(bt)) {
                continue;
            }
            String pname = fd.getName();
            if (pname == null) {
                continue;
            }
            boolean required = isRequiredParameter(fd);
            JsonNode node = root.get(pname);
            boolean missing = !root.has(pname) || node == null || node.isNull();
            String value = missing ? "" : textFromJsonNode(node);
            if (required && value.isBlank()) {
                return ScalarThingnamePreflight.thingNameValueRequiredJson(pname);
            }
            if (!required && value.isBlank()) {
                continue;
            }
            if (!ScalarThingnamePreflight.isModelVisibleThing(value)) {
                String supplied = value.isBlank() ? "" : value.trim();
                return ScalarThingnamePreflight.identityResolutionRequiredJson(pname, supplied);
            }
        }
        return null;
    }

    private static boolean isThingnameBaseType(BaseTypes bt) {
        if (bt == null) {
            return false;
        }
        if (bt == BaseTypes.THINGNAME) {
            return true;
        }
        return "THINGNAME".equalsIgnoreCase(bt.name());
    }

    private static boolean isRequiredParameter(FieldDefinition fd) {
        return ExtendedToolRequiredAspects.isRequired(fd);
    }

    private static String textFromJsonNode(JsonNode node) {
        if (node.isTextual()) {
            return node.asText("").trim();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText().trim();
        }
        return node.toString().trim();
    }
}
