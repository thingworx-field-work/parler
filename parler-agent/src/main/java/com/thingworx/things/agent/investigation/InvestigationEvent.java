package com.thingworx.things.agent.investigation;

import java.time.Instant;
import java.util.Objects;

/** One governed event/maintenance/batch row for co-occurrence (association only). */
public final class InvestigationEvent {

    private final String eventId;
    private final String assetSemanticId;
    private final Instant occurredAt;
    private final CandidateKind kind;

    public InvestigationEvent(String eventId, String assetSemanticId, Instant occurredAt, CandidateKind kind) {
        this.eventId = requireNonBlank(eventId, "eventId");
        this.assetSemanticId = requireNonBlank(assetSemanticId, "assetSemanticId");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (kind != CandidateKind.EVENT && kind != CandidateKind.MAINTENANCE && kind != CandidateKind.BATCH) {
            throw new IllegalArgumentException("kind must be EVENT, MAINTENANCE, or BATCH");
        }
    }

    public String eventId() {
        return eventId;
    }

    public String assetSemanticId() {
        return assetSemanticId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public CandidateKind kind() {
        return kind;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }
}
