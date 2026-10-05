package com.thingworx.things.agent.tools;

import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import org.joda.time.DateTime;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.PropertyDefinition;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.InfoTablePrimitive;
import com.thingworx.types.primitives.LongPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Executes {@code set_property_value} after human approval via {@code UpdatePropertyValues} (NamedVTQ).
 * Tool arguments are {@code thing_name}, {@code property_name},
 * {@code base_type} and {@code value}, with legacy camelCase aliases.
 *
 * <p>U1B E3: the HITL gate resolves property metadata, strictly canonicalizes the
 * <strong>requested</strong> value before any pending approval, and writes that canonical typed value
 * into the gated {@link ToolCall}. Approved execution consumes the same strict parser (no
 * {@code Boolean.parseBoolean} coercion).
 */
public final class SetPropertyValueExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(SetPropertyValueExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODE_FACTORY = MAPPER.getNodeFactory();

    /**
     * Offline JUnit seam: when non-null, supplies the authoritative property {@link BaseTypes} after a
     * model-visible Thing name check without requiring a platform {@link Thing} handle. Cleared by
     * {@link #clearHitlTestOverrides()}.
     */
    static volatile BiFunction<String, String, BaseTypes> hitlPropertyBaseTypeOverrideForTests;

    /** Offline JUnit seam paired with {@link #hitlPropertyBaseTypeOverrideForTests}: property read-only flag. */
    static volatile BiPredicate<String, String> hitlPropertyReadOnlyOverrideForTests;

    private SetPropertyValueExecutor() {}

    static void clearHitlTestOverrides() {
        hitlPropertyBaseTypeOverrideForTests = null;
        hitlPropertyReadOnlyOverrideForTests = null;
    }

    /**
     * Pre-HITL: resolve Thing + property metadata, strictly canonicalize the requested value, rewrite
     * {@code thing_name}/{@code property_name}/{@code base_type}/{@code value} on a new {@link ToolCall}
     * for approval enqueue and stale snapshot.
     */
    public static final class SetPropertyHitlGate {
        private final String earlyErrorJson;
        private final ToolCall gatedToolCall;

        public SetPropertyHitlGate(String earlyErrorJson, ToolCall gatedToolCall) {
            this.earlyErrorJson = earlyErrorJson;
            this.gatedToolCall = gatedToolCall;
        }

        /** Non-null UTF-8 tool error JSON when preflight fails; otherwise {@code null}. */
        public String earlyErrorJson() {
            return earlyErrorJson;
        }

        /** Non-null replacement tool call when preflight passes; otherwise {@code null}. */
        public ToolCall gatedToolCall() {
            return gatedToolCall;
        }
    }

    /** Result of strict requested-value canonicalization (package-visible for tests). */
    static final class RequestedValueCanonicalization {
        final String errorJson;
        final JsonNode canonicalValue;
        final BaseTypes baseType;

        private RequestedValueCanonicalization(String errorJson, JsonNode canonicalValue, BaseTypes baseType) {
            this.errorJson = errorJson;
            this.canonicalValue = canonicalValue;
            this.baseType = baseType;
        }

        static RequestedValueCanonicalization ok(BaseTypes baseType, JsonNode canonicalValue) {
            return new RequestedValueCanonicalization(null, canonicalValue, baseType);
        }

        static RequestedValueCanonicalization error(String code, String message) {
            return new RequestedValueCanonicalization(errorJson(code, message), null, null);
        }

        boolean isError() {
            return errorJson != null;
        }
    }

    /**
     * @return {@link SetPropertyHitlGate} — when {@link SetPropertyHitlGate#earlyErrorJson()} is non-null, return that
     * JSON to the model and do not enqueue HITL; otherwise use {@link SetPropertyHitlGate#gatedToolCall()} for
     * snapshot + pending approval.
     */
    public static SetPropertyHitlGate gateSetPropertyValueForHitl(ToolCall call) {
        try {
            JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
            String rawThing = firstText(root, "thing_name", "thingName");
            String propertyName = firstText(root, "property_name", "propertyName");
            String baseTypeHint = firstText(root, "base_type", "baseType");
            JsonNode valueNode = root.get("value");

            ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                    ScalarThingnamePreflight.gateApplicationThing("thingName", rawThing);
            String canonicalThingName;
            Thing thing;
            if (!thingGate.isError()) {
                canonicalThingName = thingGate.canonicalThingName;
                thing = thingGate.thing;
            } else if (hitlPropertyBaseTypeOverrideForTests != null && rawThing != null
                    && ScalarThingnamePreflight.isModelVisibleThing(rawThing)) {
                // Offline harness: visible name + BaseType override without a platform Thing handle.
                canonicalThingName = rawThing.trim();
                thing = null;
            } else {
                return new SetPropertyHitlGate(thingGate.errorJson, null);
            }

            if (propertyName == null || propertyName.isEmpty()) {
                return new SetPropertyHitlGate(errorJson("MISSING_PROPERTY_NAME", "property_name is required"), null);
            }
            if (valueNode == null || valueNode.isNull()) {
                return new SetPropertyHitlGate(errorJson("MISSING_VALUE", "value is required"), null);
            }

            BaseTypes actualType = resolveActualBaseType(thing, canonicalThingName, propertyName);
            if (actualType == null) {
                return new SetPropertyHitlGate(
                        errorJson("PROPERTY_NOT_FOUND", "Property \"" + propertyName + "\" not found on Thing"),
                        null);
            }
            if (ProtectedValuePolicy.isProtectedBaseType(actualType)) {
                return new SetPropertyHitlGate(ProtectedValuePolicy.writeBlockedJson(), null);
            }
            if (resolveReadOnly(thing, canonicalThingName, propertyName)) {
                return new SetPropertyHitlGate(readOnlyJson(propertyName), null);
            }

            BaseTypes hint = parseBaseTypeEnum(baseTypeHint);
            if (hint != null && hint != actualType) {
                return new SetPropertyHitlGate(errorJson("BASETYPE_HINT_MISMATCH",
                        "base_type hint " + hint.name() + " does not match property type " + actualType.name()),
                        null);
            }

            RequestedValueCanonicalization canon = canonicalizeRequestedValue(actualType, valueNode);
            if (canon.isError()) {
                return new SetPropertyHitlGate(canon.errorJson, null);
            }

            ObjectNode work = root.isObject() ? (ObjectNode) root.deepCopy() : MAPPER.createObjectNode();
            work.put("thing_name", canonicalThingName);
            work.put("thingName", canonicalThingName);
            work.put("property_name", propertyName);
            work.put("propertyName", propertyName);
            work.put("base_type", actualType.name());
            work.put("baseType", actualType.name());
            work.set("value", canon.canonicalValue);
            String newArgs = MAPPER.writeValueAsString(work);
            return new SetPropertyHitlGate(null, new ToolCall(call.getId(), call.getFunctionName(), newArgs));
        } catch (Exception e) {
            LOG.warn("set_property_value HITL gate failed: {}", e.getMessage());
            return new SetPropertyHitlGate(errorJson("SET_PROPERTY_ERROR", e.getMessage()), null);
        }
    }

    /**
     * Strict requested-value canonicalization (U1B E3). Package-visible for test matrices.
     * On success {@link RequestedValueCanonicalization#canonicalValue} is JSON-typed (e.g. boolean
     * {@code true}/{@code false}, not textual {@code "true"}).
     */
    static RequestedValueCanonicalization canonicalizeRequestedValue(BaseTypes baseType, JsonNode valueNode) {
        if (baseType == null) {
            return RequestedValueCanonicalization.error("PROPERTY_NOT_FOUND", "Property type is unknown");
        }
        if (valueNode == null || valueNode.isNull()) {
            return RequestedValueCanonicalization.error("MISSING_VALUE", "value is required");
        }
        try {
            switch (baseType) {
                case STRING:
                case HTML:
                case TEXT:
                case HYPERLINK:
                case GUID:
                case IMAGELINK:
                case JSON:
                case XML:
                case TAGS:
                    if (valueNode.isTextual()) {
                        return RequestedValueCanonicalization.ok(baseType, NODE_FACTORY.textNode(valueNode.asText()));
                    }
                    return RequestedValueCanonicalization.ok(baseType, NODE_FACTORY.textNode(valueNode.toString()));
                case PASSWORD:
                    return RequestedValueCanonicalization.error(ProtectedValuePolicy.CODE_WRITE_BLOCKED,
                            "This property is protected and cannot be changed by the agent.");
                case BOOLEAN:
                    return canonicalizeBoolean(valueNode);
                case NUMBER:
                    return canonicalizeNumber(valueNode);
                case INTEGER:
                    return canonicalizeInteger(valueNode);
                case LONG:
                    return canonicalizeLong(valueNode);
                case DATETIME:
                    return canonicalizeDatetime(valueNode);
                default:
                    return RequestedValueCanonicalization.error("UNSUPPORTED_BASE_TYPE",
                            "Unsupported base type for set_property_value: " + baseType.name()
                                    + " (use invoke_service or extend SetPropertyValueExecutor)");
            }
        } catch (Exception e) {
            return RequestedValueCanonicalization.error("INVALID_VALUE",
                    e.getMessage() != null ? e.getMessage() : "value could not be parsed");
        }
    }

    private static RequestedValueCanonicalization canonicalizeBoolean(JsonNode valueNode) {
        if (valueNode.isBoolean()) {
            return RequestedValueCanonicalization.ok(BaseTypes.BOOLEAN, NODE_FACTORY.booleanNode(valueNode.asBoolean()));
        }
        if (valueNode.isTextual()) {
            String t = valueNode.asText().trim();
            if ("true".equals(t)) {
                return RequestedValueCanonicalization.ok(BaseTypes.BOOLEAN, NODE_FACTORY.booleanNode(true));
            }
            if ("false".equals(t)) {
                return RequestedValueCanonicalization.ok(BaseTypes.BOOLEAN, NODE_FACTORY.booleanNode(false));
            }
        }
        return RequestedValueCanonicalization.error("INVALID_BOOLEAN_VALUE",
                "BOOLEAN value must be JSON true/false or the exact text true/false");
    }

    private static RequestedValueCanonicalization canonicalizeNumber(JsonNode valueNode) {
        try {
            double d;
            if (valueNode.isNumber()) {
                d = valueNode.asDouble();
            } else if (valueNode.isTextual()) {
                d = Double.parseDouble(valueNode.asText().trim());
            } else {
                return RequestedValueCanonicalization.error("INVALID_NUMBER_VALUE", "value is not a valid NUMBER");
            }
            if (!Double.isFinite(d)) {
                return RequestedValueCanonicalization.error("INVALID_NUMBER_VALUE", "value is not a finite NUMBER");
            }
            return RequestedValueCanonicalization.ok(BaseTypes.NUMBER, NODE_FACTORY.numberNode(d));
        } catch (Exception e) {
            return RequestedValueCanonicalization.error("INVALID_NUMBER_VALUE", "value is not a valid NUMBER");
        }
    }

    private static RequestedValueCanonicalization canonicalizeInteger(JsonNode valueNode) {
        try {
            if (valueNode.isIntegralNumber()) {
                return RequestedValueCanonicalization.ok(BaseTypes.INTEGER,
                        NODE_FACTORY.numberNode(valueNode.asInt()));
            }
            if (valueNode.isTextual()) {
                int v = Integer.parseInt(valueNode.asText().trim());
                return RequestedValueCanonicalization.ok(BaseTypes.INTEGER, NODE_FACTORY.numberNode(v));
            }
        } catch (Exception e) {
            return RequestedValueCanonicalization.error("INVALID_INTEGER_VALUE", "value is not a valid INTEGER");
        }
        return RequestedValueCanonicalization.error("INVALID_INTEGER_VALUE", "value is not a valid INTEGER");
    }

    private static RequestedValueCanonicalization canonicalizeLong(JsonNode valueNode) {
        try {
            if (valueNode.isIntegralNumber()) {
                return RequestedValueCanonicalization.ok(BaseTypes.LONG, NODE_FACTORY.numberNode(valueNode.asLong()));
            }
            if (valueNode.isTextual()) {
                long v = Long.parseLong(valueNode.asText().trim());
                return RequestedValueCanonicalization.ok(BaseTypes.LONG, NODE_FACTORY.numberNode(v));
            }
        } catch (Exception e) {
            return RequestedValueCanonicalization.error("INVALID_LONG_VALUE", "value is not a valid LONG");
        }
        return RequestedValueCanonicalization.error("INVALID_LONG_VALUE", "value is not a valid LONG");
    }

    private static RequestedValueCanonicalization canonicalizeDatetime(JsonNode valueNode) {
        try {
            String ts = valueNode.isTextual() ? valueNode.asText().trim() : valueNode.toString();
            DateTime.parse(ts); // validate
            return RequestedValueCanonicalization.ok(BaseTypes.DATETIME, NODE_FACTORY.textNode(ts));
        } catch (Exception e) {
            return RequestedValueCanonicalization.error("INVALID_DATETIME_VALUE", "value is not a valid DATETIME");
        }
    }

    private static BaseTypes resolveActualBaseType(Thing thing, String canonicalThingName, String propertyName) {
        BiFunction<String, String, BaseTypes> override = hitlPropertyBaseTypeOverrideForTests;
        if (override != null) {
            return override.apply(canonicalThingName, propertyName);
        }
        return resolvePropertyBaseType(thing, propertyName);
    }

    /**
     * @return JSON tool result for the LLM (success or error object)
     */
    public static String executeApprovedWrite(ToolCall call) {
        try {
            return doWrite(call);
        } catch (Exception e) {
            LOG.warn("set_property_value write failed: {}", e.getMessage(), e);
            return errorJson("SET_PROPERTY_ERROR", e.getMessage());
        }
    }

    /**
     * Canonical JSON text of the target property's current value for compare-and-stale ({@code data-operation-impl}
     * Phase D). {@code null} if the Thing/property cannot be read (caller should skip stale comparison).
     * Observation track — not the requested write value.
     */
    public static String snapshotPropertyValueForStaleCheck(ToolCall call) {
        try {
            JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
            String rawThing = firstText(root, "thing_name", "thingName");
            String propertyName = firstText(root, "property_name", "propertyName");
            if (propertyName == null || propertyName.isEmpty()) {
                return null;
            }
            ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                    ScalarThingnamePreflight.gateApplicationThing("thingName", rawThing);
            if (thingGate.isError()) {
                return null;
            }
            Thing t = thingGate.thing;
            if (ProtectedValuePolicy.isProtectedProperty(t, propertyName)) {
                return null;
            }
            IPrimitiveType pv = readPropertyPrimitive(t, propertyName);
            JsonNode j = primitiveToJsonNode(pv);
            return MAPPER.writeValueAsString(j);
        } catch (Exception e) {
            LOG.debug("snapshotPropertyValueForStaleCheck: {}", e.getMessage());
            return null;
        }
    }

    /** Tool result JSON when pre-approve snapshot differs from value at approve time (wire still {@code approved + executed:false + STALE_TARGET_VALUE}). */
    public static String staleCompareRejectedJson() {
        return errorJson("STALE_TARGET_VALUE",
                "Property value changed since the approval request was created; write was not performed. "
                        + "Re-read the property and submit a new request if the change is still intended.");
    }

    /**
     * When the model calls {@code set_property_value} without Parler AlwaysOn tool context (no in-flight approval gate).
     * Matches {@link com.thingworx.things.agent.AgentThing#executeToolCall} non-Parler path and registry fallback.
     */
    public static String blockedOutsideParlerContextJson() {
        return "{\"status\":\"blocked\",\"code\":\"PROPERTY_WRITE_REQUIRES_PARLER_CONTEXT\",\"message\":\""
                + escapeJson(
                        "Property writes require Parler AlwaysOn with human approval (HITL). "
                                + "Use a Parler client bound to a ParlerGateway; synchronous Chat without that context cannot perform set_property_value.")
                + "\"}";
    }

    private static String doWrite(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String rawThing = firstText(root, "thing_name", "thingName");
        ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                ScalarThingnamePreflight.gateApplicationThing("thingName", rawThing);
        if (thingGate.isError()) {
            return thingGate.errorJson;
        }
        Thing thing = thingGate.thing;
        String canonicalThingName = thingGate.canonicalThingName;
        String propertyName = firstText(root, "property_name", "propertyName");
        String baseTypeHint = firstText(root, "base_type", "baseType");
        JsonNode valueNode = root.get("value");

        if (propertyName == null || propertyName.isEmpty()) {
            return errorJson("MISSING_PROPERTY_NAME", "property_name is required");
        }
        if (valueNode == null || valueNode.isNull()) {
            return errorJson("MISSING_VALUE", "value is required");
        }

        if (ProtectedValuePolicy.isProtectedProperty(thing, propertyName)) {
            return ProtectedValuePolicy.writeBlockedJson();
        }

        BaseTypes baseType = resolvePropertyBaseType(thing, propertyName);
        if (baseType == null) {
            return errorJson("PROPERTY_NOT_FOUND", "Property \"" + propertyName + "\" not found on Thing");
        }
        if (isReadOnlyProperty(thing, propertyName)) {
            return readOnlyJson(propertyName);
        }
        BaseTypes hint = parseBaseTypeEnum(baseTypeHint);
        if (hint != null && hint != baseType) {
            return errorJson("BASETYPE_HINT_MISMATCH",
                    "base_type hint " + hint.name() + " does not match property type " + baseType.name());
        }

        RequestedValueCanonicalization canon = canonicalizeRequestedValue(baseType, valueNode);
        if (canon.isError()) {
            return canon.errorJson;
        }
        IPrimitiveType valuePrim = parseCanonicalValueToPrimitive(baseType, canon.canonicalValue);
        InfoTable values = buildNamedVtqRow(propertyName, valuePrim);
        ValueCollection vc = new ValueCollection();
        vc.put("values", new InfoTablePrimitive(values));

        PlatformAccess.invokeAsUser(thing, "UpdatePropertyValues", vc);

        String ok = "{\"status\":\"success\",\"thing_name\":\"" + escapeJson(canonicalThingName) + "\",\"property_name\":\""
                + escapeJson(propertyName) + "\",\"message\":\"Property updated.\"}";
        LOG.info("set_property_value: wrote {}.{}", canonicalThingName, propertyName);
        return ok;
    }

    /** Convert already-canonical JSON to a platform primitive (no permissive BOOLEAN coercion). */
    private static IPrimitiveType parseCanonicalValueToPrimitive(BaseTypes baseType, JsonNode canonical)
            throws Exception {
        switch (baseType) {
            case STRING:
            case HTML:
            case TEXT:
            case HYPERLINK:
            case GUID:
            case IMAGELINK:
            case JSON:
            case XML:
            case TAGS:
                return new StringPrimitive(canonical.isTextual() ? canonical.asText() : canonical.toString());
            case BOOLEAN:
                if (!canonical.isBoolean()) {
                    throw new Exception("canonical BOOLEAN value must be JSON boolean");
                }
                return new BooleanPrimitive(canonical.asBoolean());
            case NUMBER:
                return new NumberPrimitive(canonical.asDouble());
            case INTEGER:
                return new IntegerPrimitive(canonical.asInt());
            case LONG:
                return new LongPrimitive(canonical.asLong());
            case DATETIME:
                String ts = canonical.isTextual() ? canonical.asText().trim() : canonical.toString();
                return new com.thingworx.types.primitives.DatetimePrimitive(DateTime.parse(ts));
            default:
                throw new Exception("Unsupported base type for set_property_value: " + baseType.name());
        }
    }

    private static String firstText(JsonNode root, String snake, String camel) {
        String a = text(root, snake);
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return text(root, camel);
    }

    private static BaseTypes parseBaseTypeEnum(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        try {
            return BaseTypes.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean resolveReadOnly(Thing thing, String canonicalThingName, String propertyName) {
        BiPredicate<String, String> override = hitlPropertyReadOnlyOverrideForTests;
        if (override != null) {
            return override.test(canonicalThingName, propertyName);
        }
        return thing != null && isReadOnlyProperty(thing, propertyName);
    }

    /**
     * {@code UpdatePropertyValues} writes with the platform's read-only bypass, so the read-only flag is enforced
     * here, both before approval and again when the approved write runs.
     */
    private static boolean isReadOnlyProperty(Thing thing, String propertyName) {
        PropertyDefinition definition = thing.getInstancePropertyDefinition(propertyName);
        return definition != null && Boolean.TRUE.equals(definition.isReadOnly());
    }

    private static String readOnlyJson(String propertyName) {
        return errorJson("PROPERTY_READ_ONLY", "Property \"" + propertyName + "\" is read-only");
    }

    /** Uses {@link ProtectedValuePolicy#propertyBaseType(Thing, String)}. */
    private static BaseTypes resolvePropertyBaseType(Thing thing, String propertyName) {
        return ProtectedValuePolicy.propertyBaseType(thing, propertyName);
    }

    private static InfoTable buildNamedVtqRow(String propertyName, IPrimitiveType value) throws Exception {
        FieldDefinition fn = new FieldDefinition();
        fn.setName("name");
        fn.setBaseType(BaseTypes.STRING);
        FieldDefinition ft = new FieldDefinition();
        ft.setName("time");
        ft.setBaseType(BaseTypes.DATETIME);
        FieldDefinition fv = new FieldDefinition();
        fv.setName("value");
        fv.setBaseType(BaseTypes.VARIANT);
        FieldDefinition fq = new FieldDefinition();
        fq.setName("quality");
        fq.setBaseType(BaseTypes.STRING);

        com.thingworx.metadata.DataShapeDefinition shape = new com.thingworx.metadata.DataShapeDefinition();
        shape.addFieldDefinition(fn);
        shape.addFieldDefinition(ft);
        shape.addFieldDefinition(fv);
        shape.addFieldDefinition(fq);

        InfoTable table = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("name", new StringPrimitive(propertyName));
        row.put("time", new com.thingworx.types.primitives.DatetimePrimitive(DateTime.now()));
        row.put("value", value);
        row.put("quality", new StringPrimitive("GOOD"));
        table.addRow(row);
        return table;
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return s != null && !s.isEmpty() ? s : null;
    }

    private static String errorJson(String code, String message) {
        return "{\"status\":\"error\",\"code\":\"" + escapeJson(code) + "\",\"message\":\""
                + escapeJson(message != null ? message : "") + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static IPrimitiveType readPropertyPrimitive(Thing thing, String name) throws Exception {
        return PropertyValueReads.readCurrentValue(thing, name);
    }

    private static JsonNode primitiveToJsonNode(IPrimitiveType p) {
        if (p == null) {
            return NODE_FACTORY.nullNode();
        }
        BaseTypes bt = p.getBaseType();
        if (bt == BaseTypes.PASSWORD) {
            return NODE_FACTORY.textNode(ProtectedValuePolicy.mask());
        }
        try {
            if (bt == BaseTypes.BOOLEAN && p instanceof BooleanPrimitive) {
                return NODE_FACTORY.booleanNode(((BooleanPrimitive) p).getValue());
            }
            if (bt == BaseTypes.INTEGER && p instanceof IntegerPrimitive) {
                return NODE_FACTORY.numberNode(((IntegerPrimitive) p).getValue());
            }
            if (bt == BaseTypes.LONG && p instanceof LongPrimitive) {
                return NODE_FACTORY.numberNode(((LongPrimitive) p).getValue());
            }
            if (bt == BaseTypes.LONG && p instanceof IntegerPrimitive) {
                return NODE_FACTORY.numberNode((long) ((IntegerPrimitive) p).getValue());
            }
            if (bt == BaseTypes.NUMBER && p instanceof NumberPrimitive) {
                return NODE_FACTORY.numberNode(((NumberPrimitive) p).getValue());
            }
            if (p instanceof StringPrimitive) {
                return NODE_FACTORY.textNode(((StringPrimitive) p).getValue());
            }
            if (p instanceof com.thingworx.types.primitives.DatetimePrimitive) {
                return NODE_FACTORY.textNode(((com.thingworx.types.primitives.DatetimePrimitive) p).getValue()
                        .toString());
            }
        } catch (Exception ignored) {
            // fall through
        }
        return NODE_FACTORY.textNode(p.toString());
    }
}
