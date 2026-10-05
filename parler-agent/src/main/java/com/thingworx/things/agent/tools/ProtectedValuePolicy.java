package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.Thing;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.primitives.IPrimitiveType;

/**
 * Runtime policy for sensitive ThingWorx values ({@link BaseTypes#PASSWORD} in v1).
 * Must stay free of {@link com.thingworx.logging.LogUtilities} static initialization — offline JUnit friendly.
 *
 * @see docs/agent/protection.md
 */
public final class ProtectedValuePolicy {

    /** Fixed LLM-visible mask; never vary length with secret length. */
    public static final String MASK = "***";

    /**
     * Returned when persisted tool arguments cannot be safely parsed or serialized after redaction — never echo raw
     * input (fail-closed).
     */
    public static final String PERSIST_REDACTION_FAILED_JSON =
            "{\"redactionFailed\":true,\"argumentsMasked\":\"***\"}";

    public static final String CODE_READ_BLOCKED = "PROTECTED_VALUE_READ_BLOCKED";
    public static final String CODE_WRITE_BLOCKED = "PROTECTED_VALUE_WRITE_BLOCKED";
    public static final String CODE_INPUT_BLOCKED = "PROTECTED_VALUE_INPUT_BLOCKED";

    /**
     * Cached tabular / chart tools must not sort, group, aggregate, chart, or percentile-summarize PASSWORD-typed columns
     * (see {@code docs/agent/protection.md} §4.7).
     */
    public static final String CODE_TABULAR_PROTECTED_COLUMN = "PROTECTED_TABULAR_COLUMN_BLOCKED";

    /**
     * Lowercase tokens for pessimistic key-name redaction (logs / previews). Extend carefully — false positives are
     * acceptable; leaks are not.
     */
    public static final List<String> SENSITIVE_KEY_NAMES = Collections.unmodifiableList(Arrays.asList(
            "password", "passcode", "apikey", "api_key", "token", "secret", "credential",
            "accesskey", "access_key", "clientsecret", "client_secret", "privatekey", "private_key"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProtectedValuePolicy() {}

    public static boolean isProtectedBaseType(BaseTypes bt) {
        return bt == BaseTypes.PASSWORD;
    }

    public static String mask() {
        return MASK;
    }

    /**
     * Effective property base type from Thing metadata, or {@code null} when unknown / missing.
     */
    public static BaseTypes propertyBaseType(Thing thing, String propertyName) {
        if (thing == null || propertyName == null || propertyName.isEmpty()) {
            return null;
        }
        try {
            Object coll = thing.getClass().getMethod("getInstancePropertyDefinitions").invoke(thing);
            if (coll == null) {
                return null;
            }
            Object vals = coll.getClass().getMethod("values").invoke(coll);
            if (!(vals instanceof Iterable)) {
                return null;
            }
            for (Object pd : (Iterable<?>) vals) {
                try {
                    Object name = pd.getClass().getMethod("getName").invoke(pd);
                    if (!propertyName.equals(name)) {
                        continue;
                    }
                    Object bt = pd.getClass().getMethod("getBaseType").invoke(pd);
                    if (bt instanceof BaseTypes) {
                        return (BaseTypes) bt;
                    }
                } catch (Exception ignored) {
                    // next field
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /**
     * Conservative: unknown metadata ({@code null} base type) is treated as protected for agent reads/writes.
     */
    public static boolean isProtectedProperty(Thing thing, String propertyName) {
        BaseTypes bt = propertyBaseType(thing, propertyName);
        if (bt == null) {
            return true;
        }
        return isProtectedBaseType(bt);
    }

    public static boolean serviceDefinitionDeclaresPasswordParameter(ServiceDefinition sd) {
        if (sd == null) {
            return false;
        }
        FieldDefinitionCollection defs = sd.getParameters();
        if (defs == null || defs.values() == null) {
            return false;
        }
        for (FieldDefinition fd : defs.values()) {
            if (fd != null && fd.getBaseType() == BaseTypes.PASSWORD) {
                return true;
            }
        }
        return false;
    }

    /** {@code true} when the service result type is {@link BaseTypes#PASSWORD} (custom tools / invoke_service). */
    public static boolean serviceDefinitionReturnsPassword(ServiceDefinition sd) {
        if (sd == null) {
            return false;
        }
        FieldDefinition rt = sd.getResultType();
        return rt != null && rt.getBaseType() == BaseTypes.PASSWORD;
    }

    /**
     * {@code true} when the LLM supplied a key for a PASSWORD service parameter (key present in JSON, including
     * {@code null} or empty string — agent must not transport secrets).
     * <p>
     * <b>Precondition:</b> {@code sd} is non-null with loadable parameter metadata. Callers must have already failed
     * or short-circuited when {@link ServiceDefinition} cannot be resolved (e.g. {@code SERVICE_NOT_FOUND}); this
     * helper does not encode the “metadata unavailable → block whole call” policy by returning {@code true} for
     * {@code sd == null}.
     */
    public static boolean invokeServiceSuppliesPasswordParameter(ServiceDefinition sd, JsonNode parametersNode) {
        if (sd == null || parametersNode == null || !parametersNode.isObject()) {
            return false;
        }
        FieldDefinitionCollection defs = sd.getParameters();
        if (defs == null || defs.values() == null) {
            return false;
        }
        for (FieldDefinition fd : defs.values()) {
            if (fd == null || fd.getBaseType() != BaseTypes.PASSWORD) {
                continue;
            }
            String name = fd.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (parametersNode.has(name)) {
                return true;
            }
        }
        return false;
    }

    /** JSON tool error for {@link #CODE_WRITE_BLOCKED}. */
    public static String writeBlockedJson() {
        return "{\"status\":\"error\",\"code\":\"" + CODE_WRITE_BLOCKED
                + "\",\"message\":\"This property is protected and cannot be changed by the agent.\"}";
    }

    /** JSON tool error for {@link #CODE_INPUT_BLOCKED} (generic message — escape embedded quotes defensively). */
    public static String inputBlockedJson(String message) {
        String m = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"status\":\"error\",\"code\":\"" + CODE_INPUT_BLOCKED + "\",\"message\":\"" + m + "\"}";
    }

    /**
     * Whether to omit a {@code _tool_*} service from LLM discovery (PASSWORD input or PASSWORD result type).
     */
    public static boolean shouldOmitCustomToolFromDiscovery(ServiceDefinition sd) {
        return serviceDefinitionDeclaresPasswordParameter(sd) || serviceDefinitionReturnsPassword(sd);
    }

    /**
     * True when the service declares an INFOTABLE result whose resolvable {@link DataShapeDefinition} includes a
     * PASSWORD column (configuration-repository extended-tool registration; see {@code docs/agent/configuration-repository.md}).
     */
    public static boolean serviceResultInfotableDeclaresPasswordColumn(ServiceDefinition sd) {
        if (sd == null) {
            return false;
        }
        FieldDefinition rt = sd.getResultType();
        if (rt == null || rt.getBaseType() != BaseTypes.INFOTABLE) {
            return false;
        }
        DataShapeDefinition shape = InfotableJsonCodec.resolveDataShapeForParameter(rt);
        if (shape == null) {
            return false;
        }
        FieldDefinitionCollection cols = shape.getFields();
        if (cols == null || cols.values() == null) {
            return false;
        }
        for (FieldDefinition fd : cols.values()) {
            if (fd != null && fd.getBaseType() == BaseTypes.PASSWORD) {
                return true;
            }
        }
        return false;
    }

    /** Extended-tool discovery omission: PASSWORD inputs, scalar PASSWORD result, or PASSWORD column in declared INFOTABLE result shape. */
    public static boolean shouldOmitExtendedToolFromDiscovery(ServiceDefinition sd) {
        return shouldOmitCustomToolFromDiscovery(sd) || serviceResultInfotableDeclaresPasswordColumn(sd);
    }

    /** Mask custom-tool scalar result when metadata or runtime primitive says PASSWORD. */
    public static boolean shouldMaskCustomToolResult(ServiceDefinition sd, Object resultCell) {
        if (serviceDefinitionReturnsPassword(sd)) {
            return true;
        }
        return resultCell instanceof IPrimitiveType
                && ((IPrimitiveType) resultCell).getBaseType() == BaseTypes.PASSWORD;
    }

    /**
     * Pre-HITL: block {@code set_property_value} when metadata says PASSWORD (or unknown).
     * When invoked from {@link com.thingworx.things.agent.AgentThing} for the HITL path, {@code argumentsJson} MUST be
     * the **gated** tool arguments (after {@link SetPropertyValueExecutor#gateSetPropertyValueForHitl}) so the first
     * platform Thing lookup does not bypass the visibility-aware ThingName gate.
     *
     * @return non-null JSON to return to the LLM, or {@code null} to allow normal HITL flow
     */
    public static String setPropertyValuePreflightBlockedJson(String argumentsJson) {
        try {
            JsonNode root = MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
            String thingName = firstText(root, "thing_name", "thingName");
            String propertyName = firstText(root, "property_name", "propertyName");
            if (thingName == null || thingName.isEmpty() || propertyName == null || propertyName.isEmpty()) {
                return null;
            }
            RootEntity ent = PlatformAccess.findForPolicyCheck(thingName,
                    RelationshipTypes.ThingworxRelationshipTypes.Thing);
            if (!(ent instanceof Thing)) {
                return null;
            }
            if (isProtectedProperty((Thing) ent, propertyName)) {
                return writeBlockedJson();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /**
     * Best-effort redaction for approval previews: masks {@code parameters} entries whose names match
     * {@link #SENSITIVE_KEY_NAMES} / heuristic secret-like keys, and {@code value} on {@code set_property_value} when
     * the target property is PASSWORD (metadata).
     */
    public static String redactToolArgumentsForApprovalPreview(String argumentsJson, String functionName) {
        try {
            JsonNode root = MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
            if (!root.isObject()) {
                return argumentsJson;
            }
            ObjectNode obj = (ObjectNode) root;
            JsonNode params = obj.get("parameters");
            if (params != null && params.isObject()) {
                ObjectNode p = (ObjectNode) params;
                redactSensitiveKeysInObject(p);
            }
            if ("set_property_value".equals(functionName)) {
                String tn = firstText(obj, "thing_name", "thingName");
                String pn = firstText(obj, "property_name", "propertyName");
                if (tn != null && !tn.isEmpty() && pn != null && !pn.isEmpty()) {
                    try {
                        RootEntity ent = PlatformAccess.findForPolicyCheck(tn,
                                RelationshipTypes.ThingworxRelationshipTypes.Thing);
                        if (ent instanceof Thing && isProtectedProperty((Thing) ent, pn) && obj.has("value")) {
                            obj.put("value", MASK);
                        }
                    } catch (Exception ignored) {
                        obj.put("value", MASK);
                    }
                }
            }
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return argumentsJson;
        }
    }

    /**
     * Deep pessimistic redaction for persisted tool-call arguments (message stream, registry logs): masks sensitive key
     * names at every object depth; for {@code set_property_value} also masks {@code value} when
     * {@code base_type}/{@code baseType} is PASSWORD.
     */
    public static String redactPersistedToolArgumentsJson(String argumentsJson, String functionName) {
        try {
            JsonNode root = MAPPER.readTree(argumentsJson == null || argumentsJson.isEmpty() ? "{}" : argumentsJson);
            if (!root.isObject()) {
                return PERSIST_REDACTION_FAILED_JSON;
            }
            ObjectNode obj = (ObjectNode) root;
            deepRedactSensitiveKeysInObject(obj);
            applyMetadataDrivenPersistenceRedaction(obj, functionName);
            if ("set_property_value".equals(functionName)) {
                JsonNode bt = obj.get("base_type");
                if (bt == null || bt.isNull()) {
                    bt = obj.get("baseType");
                }
                if (bt != null && bt.isTextual() && "PASSWORD".equalsIgnoreCase(bt.asText().trim())) {
                    obj.put("value", MASK);
                }
            }
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return PERSIST_REDACTION_FAILED_JSON;
        }
    }

    /**
     * When {@link AgentToolContext#getAgentThing()} is set, masks PASSWORD-parameter values using platform metadata:
     * {@code set_property_value} target property, {@code invoke_service parameters},
     * configuration-repository extended tools, or legacy {@code _tool_*} top-level args.
     */
    private static void applyMetadataDrivenPersistenceRedaction(ObjectNode obj, String functionName) {
        try {
            AgentThing agent = AgentToolContext.getAgentThing();
            if (agent == null) {
                return;
            }
            if ("set_property_value".equals(functionName)) {
                String tn = firstText(obj, "thing_name", "thingName");
                String pn = firstText(obj, "property_name", "propertyName");
                if (tn != null && !tn.isEmpty() && pn != null && !pn.isEmpty()) {
                    try {
                        RootEntity ent = PlatformAccess.findForPolicyCheck(tn,
                                RelationshipTypes.ThingworxRelationshipTypes.Thing);
                        if (ent instanceof Thing && isProtectedProperty((Thing) ent, pn)) {
                            obj.put("value", MASK);
                        } else if (!(ent instanceof Thing)) {
                            if (obj.has("value")) {
                                obj.put("value", MASK);
                            }
                        }
                    } catch (Exception e) {
                        if (obj.has("value")) {
                            obj.put("value", MASK);
                        }
                    }
                } else if (obj.has("value")) {
                    obj.put("value", MASK);
                }
            } else if ("invoke_service".equals(functionName)) {
                JsonNode et = obj.get("entityType");
                JsonNode en = obj.get("entityName");
                JsonNode sn = obj.get("serviceName");
                boolean resolvedFull = false;
                ServiceDefinition sd = null;
                if (et != null && et.isTextual() && en != null && en.isTextual() && sn != null && sn.isTextual()) {
                    try {
                        PromptContextCacheSnapshot snap = agent.getPromptContextSnapshot();
                        ServiceTargetEntityTypeResolution typeRes =
                                ServiceTargetEntityTypeResolver.resolve(et.asText(), snap);
                        if (!typeRes.isError()) {
                            RootEntity ent = PlatformAccess.findForPolicyCheck(en.asText(), typeRes.getEffectiveRel());
                            if (ent instanceof IServiceProvider) {
                                sd = ((IServiceProvider) ent).getInstanceServiceDefinition(sn.asText());
                                resolvedFull = sd != null;
                            }
                        }
                    } catch (Exception ignored) {
                        // Fall through to unresolved persistence masking below.
                    }
                }
                JsonNode paramsNode = obj.get("parameters");
                ObjectNode paramsObj = paramsNode != null && paramsNode.isObject() ? (ObjectNode) paramsNode : null;
                if (resolvedFull) {
                    maskResolvedInvokePasswordParametersForPersistence(obj, paramsObj, sd);
                }
                /* Unresolved service metadata: do not blanket-mask parameters — not PASSWORD-direct. */
                InvokeServiceNamedVtqProtection.redactInvokeServiceValuesArraysForPersistence(obj, agent);
            } else {
                ServiceDefinition sd = null;
                PromptContextCacheSnapshot snap = agent.getPromptContextSnapshot();
                if (snap != null) {
                    java.util.Optional<com.thingworx.things.agent.configrepo.ExtendedToolDefinition> ext =
                            snap.getExtendedToolRegistry().find(functionName);
                    if (ext.isPresent()) {
                        RootEntity tent = PlatformAccess.findForPolicyCheck(ext.get().resolvedTargetThingName(),
                                RelationshipTypes.ThingworxRelationshipTypes.Thing);
                        if (tent instanceof Thing) {
                            sd = CustomToolHarvester.findServiceDefinition((Thing) tent, ext.get().serviceName());
                        }
                    }
                }
                if (sd == null && functionName != null && functionName.startsWith("_tool_")) {
                    sd = CustomToolHarvester.findServiceDefinition(agent, functionName);
                }
                if (sd == null) {
                    return;
                }
                FieldDefinitionCollection defs = sd.getParameters();
                if (defs != null && defs.values() != null) {
                    for (FieldDefinition fd : defs.values()) {
                        if (fd != null && fd.getBaseType() == BaseTypes.PASSWORD) {
                            String pname = fd.getName();
                            if (pname != null && obj.has(pname)) {
                                obj.put(pname, MASK);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Metadata layer is best-effort; heuristic deep redaction still applies.
        }
    }

    /**
     * Declared PASSWORD parameters must be masked both under {@code parameters} and at the tool-call root
     * — LLMs may emit service fields before {@link InvokeServiceParameterNormalizer} hoists them.
     */
    static void maskResolvedInvokePasswordParametersForPersistence(ObjectNode rootArgs, ObjectNode parameters,
            ServiceDefinition sd) {
        if (sd == null) {
            return;
        }
        FieldDefinitionCollection defs = sd.getParameters();
        if (defs == null || defs.values() == null) {
            return;
        }
        for (FieldDefinition fd : defs.values()) {
            if (fd != null && fd.getBaseType() == BaseTypes.PASSWORD) {
                String pname = fd.getName();
                if (pname == null || pname.isEmpty()) {
                    continue;
                }
                if (parameters != null && parameters.has(pname)) {
                    parameters.put(pname, MASK);
                }
                if (rootArgs.has(pname)) {
                    rootArgs.put(pname, MASK);
                }
            }
        }
    }

    private static void deepRedactSensitiveKeysInObject(ObjectNode obj) {
        List<String> keys = new ArrayList<>();
        obj.fieldNames().forEachRemaining(keys::add);
        for (String k : keys) {
            JsonNode v = obj.get(k);
            if (v != null && v.isObject()) {
                deepRedactSensitiveKeysInObject((ObjectNode) v);
            } else if (v != null && v.isArray()) {
                for (JsonNode item : v) {
                    if (item != null && item.isObject()) {
                        deepRedactSensitiveKeysInObject((ObjectNode) item);
                    }
                }
            }
        }
        keys.clear();
        obj.fieldNames().forEachRemaining(keys::add);
        for (String k : keys) {
            if (isSensitiveKeyName(k)) {
                obj.put(k, MASK);
            }
        }
    }

    private static void redactSensitiveKeysInObject(ObjectNode obj) {
        List<String> keys = new java.util.ArrayList<>();
        obj.fieldNames().forEachRemaining(keys::add);
        for (String k : keys) {
            if (isSensitiveKeyName(k)) {
                obj.put(k, MASK);
            }
        }
    }

    /**
     * Case-insensitive match against {@link #SENSITIVE_KEY_NAMES} plus coarse substring heuristics.
     * Intentionally over-eager: false-positive masks (e.g. {@code password_strength_hint}) are acceptable; missed
     * secrets are not.
     */
    public static boolean isSensitiveKeyName(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String s : SENSITIVE_KEY_NAMES) {
            if (lower.equals(s)) {
                return true;
            }
        }
        return lower.contains("password") || lower.contains("secret") || lower.contains("token")
                || lower.contains("credential");
    }

    private static String firstText(JsonNode root, String snake, String camel) {
        String a = text(root, snake);
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return text(root, camel);
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return s != null && !s.isEmpty() ? s : null;
    }
}
