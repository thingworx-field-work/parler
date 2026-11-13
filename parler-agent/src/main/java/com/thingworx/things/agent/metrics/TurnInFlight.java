package com.thingworx.things.agent.metrics;

import java.util.concurrent.atomic.AtomicLong;

/** Atomic in-flight segment count per entry point. All AgentThing instances on a node share {@link #SHARED}. */
public final class TurnInFlight {

    public static final TurnInFlight SHARED = new TurnInFlight();

    private final AtomicLong[] counts = new AtomicLong[TurnEntryPoint.values().length];

    public TurnInFlight() {
        for (int i = 0; i < counts.length; i++) {
            counts[i] = new AtomicLong();
        }
    }

    void increment(TurnEntryPoint ep) {
        counts[ep.ordinal()].incrementAndGet();
    }

    void decrement(TurnEntryPoint ep) {
        counts[ep.ordinal()].decrementAndGet();
    }

    public long current(TurnEntryPoint ep) {
        return counts[ep.ordinal()].get();
    }
}
