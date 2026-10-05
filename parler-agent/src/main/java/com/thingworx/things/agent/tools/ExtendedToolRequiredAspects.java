package com.thingworx.things.agent.tools;

import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.primitives.BooleanPrimitive;

/**
 * E5: single versioned helper for ThingWorx Service parameter {@code isRequired} aspect reading.
 * Used by extended-tool harvest and THINGNAME preflight so required semantics stay aligned.
 */
public final class ExtendedToolRequiredAspects {

    /** Bump when aspect-reading semantics change in a coordinated release. */
    public static final int VERSION = 1;

    private ExtendedToolRequiredAspects() {}

    /**
     * @return {@code true} when the field is required (missing/unreadable aspect defaults to required).
     */
    public static boolean isRequired(FieldDefinition fd) {
        try {
            if (fd == null || fd.getAspects() == null) {
                return true;
            }
            Object v = fd.getAspects().get("isRequired");
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
            if (v instanceof BooleanPrimitive) {
                return ((BooleanPrimitive) v).getValue();
            }
            if (v != null && "false".equalsIgnoreCase(v.toString())) {
                return false;
            }
            if (v != null && "true".equalsIgnoreCase(v.toString())) {
                return true;
            }
        } catch (Exception ignored) {
            // default required
        }
        return true;
    }

    /** Inverse of {@link #isRequired(FieldDefinition)}. */
    public static boolean isOptional(FieldDefinition fd) {
        return !isRequired(fd);
    }
}
