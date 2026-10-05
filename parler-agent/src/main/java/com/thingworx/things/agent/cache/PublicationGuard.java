package com.thingworx.things.agent.cache;

/**
 * Deadline and cancellation of the operation that produces an artifact, carried into the store path so
 * that output creation is part of that operation instead of starting a fresh I/O budget. The guard
 * belongs to the caller; the cache only consults it.
 */
public interface PublicationGuard {

    /**
     * Throws when the operation is out of time or cancelled. Called between output stages and, last,
     * immediately before the staged artifact becomes visible.
     */
    void check();

    /** Wall time the operation still has, in milliseconds. */
    long remainingWallTimeMillis();
}
