package com.thingworx.things.agent.cache;

/** Bounded cache fault codes for U1A (internal; not public wire vocabulary). */
public enum ArtifactCacheFaultCode {
    CACHE_MISS,
    PASSWORD_REJECTED,
    PATH_OVERFLOW,
    IO_LIMIT_EXCEEDED,
    CREATE_COLLISION,
    REPOSITORY_UNAVAILABLE,
    PAYLOAD_FAULT,
    INVALID_REQUEST,
    INTERNAL
}
