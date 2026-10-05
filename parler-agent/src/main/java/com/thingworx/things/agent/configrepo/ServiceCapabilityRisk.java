package com.thingworx.things.agent.configrepo;

/**
 * App-declared Service capability risk (U7 / G13). ThingWorx {@code ServiceDefinition} parity
 * proves shape, not mutation intent — missing/invalid risk must fail closed at advertisement.
 */
public enum ServiceCapabilityRisk {
    READ_ONLY,
    MUTATING,
    DESTRUCTIVE,
    ADMIN
}
