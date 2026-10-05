package com.thingworx.things.agent.configrepo;

/**
 * Runtime capability state on the active extended-tool snapshot (U7 / G13 §6.3).
 * Request-specific permission is never cached as globally active.
 */
public enum ServiceCapabilityRuntimeState {
    ACTIVE,
    DISABLED,
    INVALID,
    TARGET_MISSING,
    SCHEMA_DRIFT,
    POLICY_BLOCKED
}
