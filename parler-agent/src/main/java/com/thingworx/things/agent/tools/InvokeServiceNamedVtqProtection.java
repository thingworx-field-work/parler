package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PromptContextCacheSnapshot;

/**
 * Stage 3 — NamedVTQ-style writes via {@code invoke_service}: reject rows that target PASSWORD (or unknown-metadata)
 * properties on {@link Thing} instances when an INFOTABLE parameter carries {@code name}/{@code value} rows (e.g.
 * {@code UpdatePropertyValues} {@code values}).
 *
 * @see docs/agent/protection.md
 */
public final class InvokeServiceNamedVtqProtection {

    private InvokeServiceNamedVtqProtection() {}

    /**
     * @return human-readable block reason for the LLM, or {@code null} to allow the invocation
     */
    public static String blockedMessageForPasswordNamedVtqWrite(RootEntity entity, String serviceName,
            ServiceDefinition sd, ObjectNode root) {
        if (!(entity instanceof Thing) || sd == null || root == null) {
            return null;
        }
        Thing thing = (Thing) entity;
        JsonNode params = root.get("parameters");
        if (params == null || !params.isObject()) {
            return null;
        }
        FieldDefinition valuesFd = findInfotableParameterNamed(sd, "values");
        if (valuesFd == null) {
            return null;
        }
        JsonNode valuesJson = params.get(valuesFd.getName());
        if (valuesJson == null) {
            return null;
        }
        try {
            DataShapeDefinition shape = InfotableJsonCodec.resolveDataShapeForParameter(valuesFd);
            InfoTable it = InfotableJsonCodec.jsonToInfoTable(valuesJson, shape, valuesFd.getName());
            for (int i = 0; i < it.getRowCount(); i++) {
                ValueCollection row = it.getRow(i);
                if (row == null) {
                    continue;
                }
                Object nameCell = row.getValue("name");
                if (nameCell == null) {
                    continue;
                }
                String propName = String.valueOf(nameCell).trim();
                if (propName.isEmpty()) {
                    continue;
                }
                if (ProtectedValuePolicy.isProtectedProperty(thing, propName)) {
                    return "Cannot write to protected property \"" + propName + "\" via "
                            + (serviceName != null ? serviceName : "this service")
                            + " (NamedVTQ / values rows).";
                }
            }
        } catch (Exception ignored) {
            // Parsing failures are surfaced by the platform invoke path; do not block here.
            return null;
        }
        return null;
    }

    /**
     * MessageStream / registry persistence: mask NamedVTQ {@code value} cells only when the target {@link Thing}
     * resolves and the row {@code name} identifies a PASSWORD / protected property — PASSWORD-direct only.
     * Unresolved Thing or missing {@code name}: no PASSWORD protection masking (call cannot be
     * verified against metadata).
     */
    public static void redactInvokeServiceValuesArraysForPersistence(ObjectNode root, AgentThing agent) {
        Thing thing = tryResolveThingForInvokePersistence(root, agent);
        if (thing == null) {
            return;
        }
        JsonNode topValues = root.get("values");
        if (topValues != null && topValues.isArray()) {
            redactNamedVtqRowsInArray((ArrayNode) topValues, thing);
        }
        JsonNode params = root.get("parameters");
        if (params != null && params.isObject()) {
            JsonNode pv = params.get("values");
            if (pv != null && pv.isArray()) {
                redactNamedVtqRowsInArray((ArrayNode) pv, thing);
            }
        }
    }

    /** Masks {@code value} when {@code thing} is non-null, row has non-empty {@code name}, and property is protected. */
    static void redactNamedVtqRowsInArray(ArrayNode arr, Thing thing) {
        if (arr == null || thing == null) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonNode row = arr.get(i);
            if (row == null || !row.isObject()) {
                continue;
            }
            ObjectNode rowObj = (ObjectNode) row;
            if (!rowObj.has("value")) {
                continue;
            }
            JsonNode nameNode = rowObj.get("name");
            if (nameNode == null || nameNode.isNull()) {
                continue;
            }
            String propName = nameNode.asText("").trim();
            if (propName.isEmpty()) {
                continue;
            }
            if (ProtectedValuePolicy.isProtectedProperty(thing, propName)) {
                rowObj.put("value", ProtectedValuePolicy.MASK);
            }
        }
    }

    private static Thing tryResolveThingForInvokePersistence(ObjectNode root, AgentThing agent) {
        if (agent == null) {
            return null;
        }
        JsonNode et = root.get("entityType");
        JsonNode en = root.get("entityName");
        if (et == null || !et.isTextual() || en == null || !en.isTextual()) {
            return null;
        }
        try {
            PromptContextCacheSnapshot snap = agent.getPromptContextSnapshot();
            ServiceTargetEntityTypeResolution typeRes = ServiceTargetEntityTypeResolver.resolve(et.asText(), snap);
            if (typeRes.isError()) {
                return null;
            }
            RootEntity ent = PlatformAccess.findForPolicyCheck(en.asText(), typeRes.getEffectiveRel());
            return ent instanceof Thing ? (Thing) ent : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static FieldDefinition findInfotableParameterNamed(ServiceDefinition sd, String paramName) {
        FieldDefinitionCollection defs = sd.getParameters();
        if (defs == null || defs.values() == null) {
            return null;
        }
        for (FieldDefinition fd : defs.values()) {
            if (fd != null && paramName.equals(fd.getName()) && fd.getBaseType() == BaseTypes.INFOTABLE) {
                return fd;
            }
        }
        return null;
    }
}
