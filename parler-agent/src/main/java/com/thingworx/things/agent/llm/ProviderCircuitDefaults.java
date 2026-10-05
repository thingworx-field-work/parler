package com.thingworx.things.agent.llm;

/**
 * Provisional per-Provider circuit numeric defaults (U7 / G16 D11).
 *
 * <p>Architecture is locked in SPR-0 (local JVM, no second token bucket). Numeric threshold /
 * cooldown values are <strong>provisional</strong> and MAY be tuned in SPR-4 without a design
 * redirect, provided the rate gate remains the sole token/request/concurrency owner.
 */
public final class ProviderCircuitDefaults {

    /** Marker for tests/docs: values below are not yet SPR-4-final. */
    public static final boolean PROVISIONAL = true;

    /** Transient failures in CLOSED before opening. */
    public static final int TRANSIENT_FAILURE_THRESHOLD = 5;

    /** Cooldown in OPEN before admitting one HALF_OPEN probe. */
    public static final long OPEN_COOLDOWN_MS = 30_000L;

    private ProviderCircuitDefaults() {}
}
