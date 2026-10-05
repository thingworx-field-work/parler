package com.thingworx.things.agent.join;

/** Topic-specific G8 failure reasons (map into G18 / envelope warnings). */
public enum ExactJoinReason {
    SUCCESS,
    JOIN_KEY_MISSING,
    JOIN_TYPE_MISMATCH,
    CARDINALITY_VIOLATION,
    COLUMN_COLLISION,
    OUTPUT_BUDGET_EXCEEDED,
    BUDGET_EXCEEDED,
    PASSWORD_COLUMN,
    INVALID_CONFIG
}
