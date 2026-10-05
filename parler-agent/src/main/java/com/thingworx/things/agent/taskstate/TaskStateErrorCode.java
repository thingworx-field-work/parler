package com.thingworx.things.agent.taskstate;

/**
 * Stable error codes for {@link AgentTaskEvidence}; adapters must not emit free-form strings.
 */
public enum TaskStateErrorCode {
    UNKNOWN,
    TOOL_EXECUTION_FAILED,
    TOOL_RESULT_INVALID,
    ENTITY_NOT_FOUND,
    /** Scalar {@code thingName} / {@code THINGNAME} required but missing or blank (preflight). */
    THINGNAME_VALUE_REQUIRED,
    IDENTITY_RESOLUTION_REQUIRED,
    SERVICE_NOT_FOUND,
    SERVICE_LOOKUP_FAILED,
    PERMISSION_DENIED,
    PARAMETER_INVALID,
    PROPERTY_METADATA_UNRESOLVED,
    CACHE_MISS,
    INVALID_TIME_RANGE,
    PROTECTED_VALUE_OMITTED,
    PROTECTED_VALUE_READ_BLOCKED,
    PROTECTED_VALUE_WRITE_BLOCKED,
    PROTECTED_VALUE_INPUT_BLOCKED,
    UPSTREAM_TIMEOUT,
    HITL_REJECTED,
    HITL_CANCELLED,
    HITL_EXPIRED
}
