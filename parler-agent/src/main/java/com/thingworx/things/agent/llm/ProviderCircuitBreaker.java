package com.thingworx.things.agent.llm;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-Provider Thing circuit breaker in the current JVM (U7 §7.4 / D11). Resets on process
 * restart. Does not create a second token bucket — rate limiting stays on
 * {@link com.thingworx.things.agent.llm.ratecontrol.LLMAPIProviderRateGate}.
 */
public final class ProviderCircuitBreaker {

    private final Clock clock;
    private final int transientFailureThreshold;
    private final long openCooldownMs;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public ProviderCircuitBreaker(Clock clock) {
        this(clock, ProviderCircuitDefaults.TRANSIENT_FAILURE_THRESHOLD, ProviderCircuitDefaults.OPEN_COOLDOWN_MS);
    }

    public ProviderCircuitBreaker(Clock clock, int transientFailureThreshold, long openCooldownMs) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transientFailureThreshold = Math.max(1, transientFailureThreshold);
        this.openCooldownMs = Math.max(1L, openCooldownMs);
    }

    /**
     * Whether a call may proceed. OPEN admits nothing until cooldown elapses, then one HALF_OPEN
     * probe; concurrent probes lose the race and are denied.
     */
    public boolean admit(String providerThingName) {
        Entry e = entry(providerThingName);
        synchronized (e) {
            long now = clock.millis();
            if (e.state == ProviderCircuitState.CLOSED) {
                return true;
            }
            if (e.state == ProviderCircuitState.OPEN) {
                if (now < e.openUntilEpochMs) {
                    return false;
                }
                e.state = ProviderCircuitState.HALF_OPEN;
                e.halfOpenProbeInFlight.set(false);
            }
            if (e.state == ProviderCircuitState.HALF_OPEN) {
                return e.halfOpenProbeInFlight.compareAndSet(false, true);
            }
            return false;
        }
    }

    public void recordSuccess(String providerThingName) {
        Entry e = entry(providerThingName);
        synchronized (e) {
            e.transientFailures = 0;
            e.state = ProviderCircuitState.CLOSED;
            e.openUntilEpochMs = 0L;
            e.configIneligible = false;
            e.halfOpenProbeInFlight.set(false);
        }
    }

    /**
     * Record a classified failure. Auth/config/invalid-request mark config-ineligible without
     * thrashing the transient counter. Transient classes advance CLOSED→OPEN at threshold.
     */
    public void recordFailure(String providerThingName, LlmProviderFailureClass failureClass) {
        Entry e = entry(providerThingName);
        synchronized (e) {
            e.halfOpenProbeInFlight.set(false);
            if (failureClass == LlmProviderFailureClass.AUTHENTICATION_OR_CONFIG
                    || failureClass == LlmProviderFailureClass.INVALID_REQUEST_OR_SCHEMA) {
                e.configIneligible = true;
                e.state = ProviderCircuitState.OPEN;
                e.openUntilEpochMs = Long.MAX_VALUE; // until operator/config refresh
                return;
            }
            if (failureClass == LlmProviderFailureClass.CANCELLED
                    || failureClass == LlmProviderFailureClass.PARTIAL_OR_UNKNOWN_OUTCOME
                    || failureClass == LlmProviderFailureClass.POLICY_OR_EGRESS_BLOCKED) {
                // Do not trip transient counters for these classes.
                if (e.state == ProviderCircuitState.HALF_OPEN) {
                    e.state = ProviderCircuitState.OPEN;
                    e.openUntilEpochMs = clock.millis() + openCooldownMs;
                }
                return;
            }
            if (e.state == ProviderCircuitState.HALF_OPEN) {
                e.state = ProviderCircuitState.OPEN;
                e.openUntilEpochMs = clock.millis() + openCooldownMs;
                return;
            }
            if (failureClass == LlmProviderFailureClass.RATE_LIMITED
                    || failureClass == LlmProviderFailureClass.TIMEOUT_BEFORE_RESPONSE
                    || failureClass == LlmProviderFailureClass.TRANSIENT_UPSTREAM
                    || failureClass == LlmProviderFailureClass.MODEL_UNAVAILABLE) {
                e.transientFailures++;
                if (e.transientFailures >= transientFailureThreshold) {
                    e.state = ProviderCircuitState.OPEN;
                    e.openUntilEpochMs = clock.millis() + openCooldownMs;
                    e.transientFailures = 0;
                }
            }
        }
    }

    public ProviderCircuitState state(String providerThingName) {
        Entry e = entry(providerThingName);
        synchronized (e) {
            maybeTransitionOpenToHalfOpenReady(e);
            return e.state;
        }
    }

    public boolean isConfigIneligible(String providerThingName) {
        return entry(providerThingName).configIneligible;
    }

    /** Sanitized operator view — no secrets. */
    public Map<String, Object> operatorView(String providerThingName) {
        Entry e = entry(providerThingName);
        synchronized (e) {
            maybeTransitionOpenToHalfOpenReady(e);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("providerThingName", providerThingName);
            m.put("state", e.state.name());
            m.put("transientFailures", e.transientFailures);
            m.put("configIneligible", e.configIneligible);
            m.put("openUntilEpochMs", e.openUntilEpochMs == Long.MAX_VALUE ? -1L : e.openUntilEpochMs);
            m.put("halfOpenProbeInFlight", e.halfOpenProbeInFlight.get());
            return Map.copyOf(m);
        }
    }

    private void maybeTransitionOpenToHalfOpenReady(Entry e) {
        // Visible state stays OPEN until admit() moves to HALF_OPEN; no auto-flip here.
    }

    private Entry entry(String providerThingName) {
        if (providerThingName == null || providerThingName.isBlank()) {
            throw new IllegalArgumentException("providerThingName required");
        }
        return entries.computeIfAbsent(providerThingName.trim(), n -> new Entry());
    }

    private static final class Entry {
        private ProviderCircuitState state = ProviderCircuitState.CLOSED;
        private int transientFailures;
        private long openUntilEpochMs;
        private boolean configIneligible;
        private final AtomicBoolean halfOpenProbeInFlight = new AtomicBoolean(false);
    }
}
