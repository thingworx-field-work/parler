package com.thingworx.things.agent.configrepo;

/** Explicit idempotency mode for a governed Service capability (U7 / G13). */
public enum ServiceIdempotencyMode {
    NONE,
    CALLER_KEY,
    PLATFORM_NATIVE
}
