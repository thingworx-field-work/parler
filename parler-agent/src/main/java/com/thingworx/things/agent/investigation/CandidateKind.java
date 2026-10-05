package com.thingworx.things.agent.investigation;

/** Hypothesis ledger candidate kinds (fleet-rca §6.4). */
public enum CandidateKind {
    SIGNAL,
    EVENT,
    MAINTENANCE,
    BATCH,
    UPSTREAM_ASSET;

    public String wireName() {
        return name().toLowerCase();
    }
}
