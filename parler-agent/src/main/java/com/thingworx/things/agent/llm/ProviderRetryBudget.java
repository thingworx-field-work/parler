package com.thingworx.things.agent.llm;

import java.time.Clock;
import java.util.Objects;

/**
 * Total same-route retry budget (U7 D10). Provider-local rate-gate waits count toward
 * {@code maxCumulativeWaitMs}. Wall time uses an injectable {@link Clock}.
 *
 * <p>Caps are always finite: non-positive wait/wall/attempt ceilings are rejected at construction.
 * There is no numeric "uncapped" sentinel.
 */
public final class ProviderRetryBudget {

    private final int maxSameProviderRetries;
    private final int maxTotalAttempts;
    private final long maxCumulativeWaitMs;
    private final long maxWallTimeMs;
    private final Clock clock;
    private final long startedAtEpochMs;

    private int attemptsUsed;
    private int sameProviderRetriesUsed;
    private long cumulativeWaitMs;

    public ProviderRetryBudget(
            int maxSameProviderRetries,
            int maxTotalAttempts,
            long maxCumulativeWaitMs,
            long maxWallTimeMs,
            Clock clock) {
        if (maxSameProviderRetries < 0) {
            throw new IllegalArgumentException("maxSameProviderRetries must be >= 0");
        }
        if (maxTotalAttempts < 1) {
            throw new IllegalArgumentException("maxTotalAttempts must be >= 1");
        }
        if (maxCumulativeWaitMs <= 0L) {
            throw new IllegalArgumentException("maxCumulativeWaitMs must be > 0");
        }
        if (maxWallTimeMs <= 0L) {
            throw new IllegalArgumentException("maxWallTimeMs must be > 0");
        }
        this.maxSameProviderRetries = maxSameProviderRetries;
        this.maxTotalAttempts = maxTotalAttempts;
        this.maxCumulativeWaitMs = maxCumulativeWaitMs;
        this.maxWallTimeMs = maxWallTimeMs;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAtEpochMs = clock.millis();
    }

    public static ProviderRetryBudget fromProfile(ProviderRouteProfile profile, Clock clock) {
        Objects.requireNonNull(profile, "profile");
        return new ProviderRetryBudget(
                profile.maxSameProviderRetries(),
                profile.maxTotalAttempts(),
                profile.maxCumulativeWaitMs(),
                profile.maxWallTimeMs(),
                clock);
    }

    public boolean canStartAttempt() {
        if (attemptsUsed >= maxTotalAttempts) {
            return false;
        }
        return remainingWallMs() > 0L;
    }

    public void recordAttemptStarted() {
        attemptsUsed++;
    }

    /** Rate-gate or backoff waits; both count toward the cumulative wait cap. */
    public void recordWaitMs(long waitMs) {
        if (waitMs > 0) {
            cumulativeWaitMs += waitMs;
        }
    }

    public void recordSameProviderRetry() {
        sameProviderRetriesUsed++;
    }

    public boolean canSameProviderRetry() {
        return sameProviderRetriesUsed < maxSameProviderRetries && canStartAttempt();
    }

    public boolean remainingWaitAllows(long proposedWaitMs) {
        if (proposedWaitMs < 0) {
            return false;
        }
        return proposedWaitMs <= remainingWaitMs() && proposedWaitMs <= remainingWallMs();
    }

    /** Remaining cumulative-wait budget (always finite). */
    public long remainingWaitMs() {
        return Math.max(0L, maxCumulativeWaitMs - cumulativeWaitMs);
    }

    /** Remaining wall budget (always finite). */
    public long remainingWallMs() {
        long elapsed = Math.max(0L, clock.millis() - startedAtEpochMs);
        return Math.max(0L, maxWallTimeMs - elapsed);
    }

    public int attemptsUsed() {
        return attemptsUsed;
    }

    public int sameProviderRetriesUsed() {
        return sameProviderRetriesUsed;
    }

    public long cumulativeWaitMs() {
        return cumulativeWaitMs;
    }

    public int maxSameProviderRetries() {
        return maxSameProviderRetries;
    }

    public int maxTotalAttempts() {
        return maxTotalAttempts;
    }
}
