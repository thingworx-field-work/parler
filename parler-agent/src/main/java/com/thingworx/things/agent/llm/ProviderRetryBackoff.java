package com.thingworx.things.agent.llm;

import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with bounded full jitter (U7 §7.3 / D10). Randomness affects delay only.
 * Tests inject a deterministic {@link DoubleSupplier} in {@code [0, 1)}.
 */
public final class ProviderRetryBackoff {

    public static final long DEFAULT_BASE_DELAY_MS = 200L;
    public static final long DEFAULT_MAX_DELAY_MS = 8_000L;

    private ProviderRetryBackoff() {}

    /**
     * @param sameProviderRetryIndex 0-based index of the upcoming same-provider retry
     * @param retryAfterHintMs upstream Retry-After (or rate-gate) hint; 0 when absent
     * @param remainingBudgetMs remaining cumulative-wait budget
     * @param unitRandom supplier of values in {@code [0.0, 1.0)}
     */
    public static long delayMs(
            int sameProviderRetryIndex,
            long retryAfterHintMs,
            long remainingBudgetMs,
            DoubleSupplier unitRandom) {
        return delayMs(
                sameProviderRetryIndex,
                retryAfterHintMs,
                remainingBudgetMs,
                unitRandom,
                DEFAULT_BASE_DELAY_MS,
                DEFAULT_MAX_DELAY_MS);
    }

    public static long delayMs(
            int sameProviderRetryIndex,
            long retryAfterHintMs,
            long remainingBudgetMs,
            DoubleSupplier unitRandom,
            long baseDelayMs,
            long maxDelayMs) {
        Objects.requireNonNull(unitRandom, "unitRandom");
        if (remainingBudgetMs <= 0) {
            return 0L;
        }
        int idx = Math.max(0, sameProviderRetryIndex);
        long exp = baseDelayMs;
        for (int i = 0; i < idx && exp < maxDelayMs; i++) {
            exp = Math.min(maxDelayMs, exp * 2L);
        }
        exp = Math.min(maxDelayMs, exp);
        double u = unitRandom.getAsDouble();
        if (u < 0.0) {
            u = 0.0;
        } else if (u >= 1.0) {
            u = 0.999999999;
        }
        // Bounded full jitter: delay ~ U(0, exp). Callers MUST NOT pass a Retry-After hint
        // larger than remainingBudgetMs (coordinator rejects that as non-RETRY_SAME).
        long jittered = (long) Math.floor(u * (exp + 1L));
        long hint = Math.max(0L, retryAfterHintMs);
        if (hint > remainingBudgetMs) {
            return 0L;
        }
        long delay = Math.max(jittered, hint);
        return Math.min(delay, remainingBudgetMs);
    }

    /** Parses {@code Retry-After} seconds (or HTTP-date) from allowlisted safe headers. */
    public static long retryAfterHintMs(Map<String, String> safeHeaders) {
        if (safeHeaders == null || safeHeaders.isEmpty()) {
            return 0L;
        }
        for (Map.Entry<String, String> e : safeHeaders.entrySet()) {
            if (e.getKey() == null || !"retry-after".equalsIgnoreCase(e.getKey())) {
                continue;
            }
            String v = e.getValue();
            if (v == null || v.isBlank()) {
                continue;
            }
            try {
                double sec = Double.parseDouble(v.trim());
                if (sec > 0) {
                    return (long) Math.ceil(sec * 1000.0d);
                }
            } catch (NumberFormatException ignored) {
                try {
                    long epoch = java.time.Instant.parse(v.trim()).toEpochMilli();
                    long delta = epoch - System.currentTimeMillis();
                    return delta > 0 ? delta : 0L;
                } catch (Exception ignoredDate) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    /** Stable token for telemetry / audit (no secrets). */
    public static String telemetryToken(LlmProviderFailureClass cls, long delayMs) {
        return "LLM_PROVIDER_RETRY class="
                + (cls != null ? cls.name() : "UNKNOWN")
                + " delayMs="
                + delayMs;
    }
}
