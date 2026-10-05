package com.thingworx.things.agent.recovery;

import java.util.List;
import java.util.Locale;

/**
 * Maps known outer tool {@code code} strings to additive {@link TypedToolError} descriptors (EG1).
 * Unknown codes become sanitized {@link ErrorCategory#INTERNAL}. Presentation messages are never
 * classifiers.
 */
public final class ErrorRecoveryMapper {

    /** Budget key for CACHE_MISS → {@link RecoveryActionType#REEXECUTE_SOURCE} (EG3 first consumer). */
    public static final String BUDGET_KEY_SOURCE_QUERY = "source-query";

    public static final String REASON_NOT_FOUND = "NOT_FOUND";

    private ErrorRecoveryMapper() {}

    /**
     * @param code outer tool error code (stable compatibility identity)
     * @param message bounded presentation text
     * @param liveMissProven when {@code true} and code is {@code CACHE_MISS}, emit reason
     *        {@code NOT_FOUND}; otherwise omit reason for miss-like codes
     */
    public static TypedToolError map(String code, String message, boolean liveMissProven) {
        String c = normalizeCode(code);
        if (c.isEmpty()) {
            return internal(message);
        }
        switch (c) {
            case "CACHE_MISS":
                return TypedToolError.of("CACHE_MISS", ErrorCategory.LIFECYCLE,
                        liveMissProven ? REASON_NOT_FOUND : null, true, BUDGET_KEY_SOURCE_QUERY,
                        List.of(RecoveryAction.of(RecoveryActionType.REEXECUTE_SOURCE)), false, message);
            case "LAST_TABULAR_CACHE_UNAVAILABLE":
                return TypedToolError.of("LAST_TABULAR_CACHE_UNAVAILABLE", ErrorCategory.LIFECYCLE, null, true,
                        BUDGET_KEY_SOURCE_QUERY,
                        List.of(RecoveryAction.of(RecoveryActionType.ASK_USER),
                                RecoveryAction.of(RecoveryActionType.REEXECUTE_SOURCE)),
                        false, message);
            case "UPSTREAM_TIMEOUT":
                // Mapped for future use; M1 does not invent a new timeout retry path.
                return TypedToolError.of("UPSTREAM_TIMEOUT", ErrorCategory.UPSTREAM, null, true, "upstream-timeout",
                        List.of(RecoveryAction.of(RecoveryActionType.RETRY_SAME_CALL)), false, message);
            case "PERMISSION_DENIED":
            case "PROTECTED_VALUE_OMITTED":
            case "PROTECTED_VALUE_READ_BLOCKED":
            case "PROTECTED_VALUE_WRITE_BLOCKED":
            case "PROTECTED_VALUE_INPUT_BLOCKED":
            case "TABULAR_PROTECTED_COLUMN":
                return TypedToolError.of(c, ErrorCategory.AUTHORIZATION, null, false, null,
                        List.of(RecoveryAction.of(RecoveryActionType.STOP_WITH_EVIDENCE)), true, message);
            case "PARAMETER_INVALID":
            case "INVALID_PARAMETERS":
                return TypedToolError.of(c.equals("INVALID_PARAMETERS") ? "INVALID_PARAMETERS" : "PARAMETER_INVALID",
                        ErrorCategory.ARGUMENT, null, false, null, List.of(), false, message);
            case "ENTITY_NOT_FOUND":
            case "IDENTITY_RESOLUTION_REQUIRED":
                return TypedToolError.of(c, ErrorCategory.IDENTITY, null, false, null,
                        List.of(RecoveryAction.of(RecoveryActionType.RESOLVE_IDENTITY)), false, message);
            case "THINGNAME_VALUE_REQUIRED":
                return TypedToolError.of("THINGNAME_VALUE_REQUIRED", ErrorCategory.ARGUMENT, null, false, null,
                        List.of(RecoveryAction.of(RecoveryActionType.ASK_USER)), false, message);
            default:
                return TypedToolError.of(c, ErrorCategory.INTERNAL, null, false, null, List.of(), false, message);
        }
    }

    public static TypedToolError map(String code, String message) {
        return map(code, message, false);
    }

    private static TypedToolError internal(String message) {
        return TypedToolError.of("INTERNAL", ErrorCategory.INTERNAL, null, false, null, List.of(), false,
                message != null ? message : "");
    }

    static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        return code.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
