package com.thingworx.things.agent.recovery;

/**
 * EG2 closed v1 recovery action set. Handlers stay code-specific; there is no generic untrusted
 * JSON patch executor.
 */
public enum RecoveryActionType {
    PATCH_ARGUMENT,
    RESOLVE_IDENTITY,
    REEXECUTE_SOURCE,
    RETRY_SAME_CALL,
    ASK_USER,
    STOP_WITH_EVIDENCE
}
