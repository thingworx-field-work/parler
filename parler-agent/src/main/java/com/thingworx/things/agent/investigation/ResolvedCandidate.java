package com.thingworx.things.agent.investigation;

import java.util.Objects;

/** One bounded candidate admitted from the catalog under search caps. */
public final class ResolvedCandidate {

    private final String candidateId;
    private final CandidateKind kind;
    private final String statement;
    private final int relationDistance;
    private final String relatedAssetSemanticId;

    public ResolvedCandidate(
            String candidateId,
            CandidateKind kind,
            String statement,
            int relationDistance,
            String relatedAssetSemanticId) {
        this.candidateId = requireNonBlank(candidateId, "candidateId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.statement = requireNonBlank(statement, "statement");
        HypothesisLedgerEntry.rejectForbiddenWording(this.statement, "statement");
        if (relationDistance < 0) {
            throw new IllegalArgumentException("relationDistance must be >= 0");
        }
        this.relationDistance = relationDistance;
        this.relatedAssetSemanticId = blankToNull(relatedAssetSemanticId);
    }

    public String candidateId() {
        return candidateId;
    }

    public CandidateKind kind() {
        return kind;
    }

    public String statement() {
        return statement;
    }

    public int relationDistance() {
        return relationDistance;
    }

    public String relatedAssetSemanticId() {
        return relatedAssetSemanticId;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
