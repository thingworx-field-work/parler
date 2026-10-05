package com.thingworx.things.agent.configrepo;

/**
 * Descriptor admission hint for a governed Service capability (U7 / G13).
 * Registration alone never creates an always-advertised resident tool (D7).
 */
public enum ServiceCapabilityAdmission {
    OFF,
    NARROW,
    LAZY,
    DEFAULT
}
