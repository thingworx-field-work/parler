package com.thingworx.things.agent.semantics;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.tools.InfotableJsonCodec;
import com.thingworx.things.agent.tools.ProtectedValuePolicy;
import com.thingworx.types.BaseTypes;
import com.thingworx.entities.interfaces.IServiceProvider;

/**
 * Invocation-time Property/Service binding checks (SP7). Profile validity never grants permission;
 * callers must still execute under the current {@code SecurityContext}.
 */
public final class SemanticBindingPreflight {

    public enum Status {
        OK,
        TARGET_NOT_FOUND,
        PASSWORD_PROTECTED,
        TYPE_INCOMPATIBLE,
        UNAVAILABLE
    }

    public static final class Result {
        private final Status status;
        private final String message;

        Result(Status status, String message) {
            this.status = status != null ? status : Status.UNAVAILABLE;
            this.message = message != null ? message : "";
        }

        public Status status() {
            return status;
        }

        public String message() {
            return message;
        }

        public boolean ok() {
            return status == Status.OK;
        }
    }

    private SemanticBindingPreflight() {}

    public static Result check(Thing thing, SemanticRoleBinding binding) {
        if (binding == null) {
            return new Result(Status.UNAVAILABLE, "binding is required");
        }
        if (binding.kind() == SemanticBindingKind.PROPERTY) {
            return checkProperty(thing, binding.propertyName());
        }
        if (binding.kind() == SemanticBindingKind.SERVICE) {
            if (thing == null) {
                return new Result(Status.TARGET_NOT_FOUND, "Thing is not available");
            }
            Result nameCheck = checkServiceThingName(thing.getName(), binding.thingName());
            if (!nameCheck.ok()) {
                return nameCheck;
            }
            return checkService(thing, binding.serviceName(), binding.resultField());
        }
        return new Result(Status.UNAVAILABLE, "unsupported binding kind");
    }

    /**
     * SP3 SERVICE binding requires the live target to be the exact {@code thingName}. Offline-testable
     * without a platform {@link Thing} instance.
     */
    public static Result checkServiceThingName(String liveThingName, String bindingThingName) {
        if (bindingThingName == null || bindingThingName.isBlank()) {
            return new Result(Status.TARGET_NOT_FOUND, "binding thingName is required");
        }
        String expected = bindingThingName.trim();
        if (liveThingName == null || liveThingName.isBlank() || !expected.equals(liveThingName)) {
            String live = liveThingName != null ? liveThingName : "";
            return new Result(Status.TARGET_NOT_FOUND,
                    "live Thing \"" + live + "\" does not match binding thingName \"" + expected + "\"");
        }
        return new Result(Status.OK, "");
    }

    public static Result checkProperty(Thing thing, String propertyName) {
        if (thing == null) {
            return new Result(Status.TARGET_NOT_FOUND, "Thing is not available");
        }
        if (propertyName == null || propertyName.isBlank()) {
            return new Result(Status.TARGET_NOT_FOUND, "propertyName is required");
        }
        BaseTypes bt = ProtectedValuePolicy.propertyBaseType(thing, propertyName.trim());
        if (bt == null) {
            return new Result(Status.TARGET_NOT_FOUND,
                    "property \"" + propertyName.trim() + "\" not found on Thing " + thing.getName());
        }
        if (ProtectedValuePolicy.isProtectedBaseType(bt)) {
            return new Result(Status.PASSWORD_PROTECTED,
                    "property \"" + propertyName.trim() + "\" is PASSWORD-protected");
        }
        return new Result(Status.OK, "");
    }

    public static Result checkService(Thing thing, String serviceName, String resultField) {
        if (thing == null) {
            return new Result(Status.TARGET_NOT_FOUND, "Thing is not available");
        }
        if (serviceName == null || serviceName.isBlank()) {
            return new Result(Status.TARGET_NOT_FOUND, "serviceName is required");
        }
        if (resultField == null || resultField.isBlank()) {
            return new Result(Status.TARGET_NOT_FOUND, "resultField is required");
        }
        if (!(thing instanceof IServiceProvider)) {
            return new Result(Status.TARGET_NOT_FOUND,
                    "Thing " + thing.getName() + " does not expose services");
        }
        ServiceDefinition sd;
        try {
            sd = ((IServiceProvider) thing).getInstanceServiceDefinition(serviceName.trim());
        } catch (Exception e) {
            return new Result(Status.UNAVAILABLE,
                    "service metadata lookup failed: " + e.getMessage());
        }
        if (sd == null) {
            return new Result(Status.TARGET_NOT_FOUND,
                    "service \"" + serviceName.trim() + "\" not found on Thing " + thing.getName());
        }
        if (ProtectedValuePolicy.serviceDefinitionReturnsPassword(sd)
                || ProtectedValuePolicy.serviceDefinitionDeclaresPasswordParameter(sd)
                || ProtectedValuePolicy.serviceResultInfotableDeclaresPasswordColumn(sd)) {
            return new Result(Status.PASSWORD_PROTECTED,
                    "service \"" + serviceName.trim() + "\" involves PASSWORD-typed fields");
        }
        FieldDefinition rt = sd.getResultType();
        if (rt == null) {
            return new Result(Status.TYPE_INCOMPATIBLE,
                    "service \"" + serviceName.trim() + "\" has no result type");
        }
        if (rt.getBaseType() == BaseTypes.INFOTABLE) {
            DataShapeDefinition shape = InfotableJsonCodec.resolveDataShapeForParameter(rt);
            if (shape != null && shape.getFields() != null) {
                FieldDefinition field = shape.getFieldDefinition(resultField.trim());
                if (field == null) {
                    return new Result(Status.TARGET_NOT_FOUND,
                            "resultField \"" + resultField.trim() + "\" not found on service result DataShape");
                }
                if (field.getBaseType() == BaseTypes.PASSWORD) {
                    return new Result(Status.PASSWORD_PROTECTED,
                            "resultField \"" + resultField.trim() + "\" is PASSWORD-protected");
                }
            }
            // DataShape unavailable: accept structurally (invocation still rechecks under SecurityContext)
            return new Result(Status.OK, "");
        }
        // Scalar result: resultField is the logical name of the scalar (must be non-blank; already checked)
        if (rt.getBaseType() == BaseTypes.PASSWORD) {
            return new Result(Status.PASSWORD_PROTECTED,
                    "service \"" + serviceName.trim() + "\" returns PASSWORD");
        }
        return new Result(Status.OK, "");
    }
}
