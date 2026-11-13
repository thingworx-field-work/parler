package com.thingworx.things.agent.metrics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * One admitted execution segment of a turn. Starting the scope increments the shared in-flight count; {@link #close()}
 * decrements it exactly once and records one outcome and duration. A scope closed without an assigned outcome records
 * {@code error} (for example while an exception unwinds to an outer worker catch).
 */
public final class ParlerTurnScope implements AutoCloseable {

    private final TurnEntryPoint entryPoint;
    private final ParlerMetricsSink sink;
    private final TurnInFlight inFlight;
    private final LongSupplier nanoClock;
    private final long startNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile TurnOutcome outcome;

    public ParlerTurnScope(TurnEntryPoint entryPoint, ParlerMetricsSink sink, TurnInFlight inFlight,
            LongSupplier nanoClock) {
        this.entryPoint = Objects.requireNonNull(entryPoint, "entryPoint");
        this.sink = sink != null ? sink : ParlerMetricsSink.NOOP;
        this.inFlight = Objects.requireNonNull(inFlight, "inFlight");
        this.nanoClock = nanoClock != null ? nanoClock : System::nanoTime;
        this.startNanos = this.nanoClock.getAsLong();
        inFlight.increment(entryPoint);
    }

    /** Assigns the terminal outcome; a later assignment before close replaces an earlier one. */
    public void outcome(TurnOutcome value) {
        if (value != null) {
            outcome = value;
        }
    }

    public TurnOutcome assignedOutcome() {
        return outcome;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            long durationMs = Math.max(0L, (nanoClock.getAsLong() - startNanos) / 1_000_000L);
            TurnOutcome o = outcome != null ? outcome : TurnOutcome.ERROR;
            sink.recordTurn(entryPoint, o, durationMs);
        } finally {
            inFlight.decrement(entryPoint);
        }
    }
}
