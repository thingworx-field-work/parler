package com.thingworx.things.agent.investigation;

import java.util.Objects;

/**
 * One U5/analysis or event observation for a candidate. FRC-3 consumes pre-resolved observations
 * from an {@link InvestigationEvidenceSource} — it does not invoke U5 executors directly.
 */
public final class CandidateEvidenceObservation {

    public enum Polarity {
        SUPPORT,
        WEAKEN,
        UNKNOWN
    }

    private final String candidateId;
    private final String testId;
    private final Polarity polarity;
    private final String evidenceRef;
    private final String unknownReason;

    private CandidateEvidenceObservation(
            String candidateId, String testId, Polarity polarity, String evidenceRef, String unknownReason) {
        this.candidateId = requireNonBlank(candidateId, "candidateId");
        this.testId = requireNonBlank(testId, "testId");
        this.polarity = Objects.requireNonNull(polarity, "polarity");
        this.evidenceRef = blankToNull(evidenceRef);
        this.unknownReason = blankToNull(unknownReason);
        if (polarity == Polarity.UNKNOWN && this.unknownReason == null) {
            throw new IllegalArgumentException("UNKNOWN requires unknownReason");
        }
    }

    public static CandidateEvidenceObservation support(String candidateId, String testId, String evidenceRef) {
        return new CandidateEvidenceObservation(candidateId, testId, Polarity.SUPPORT, evidenceRef, null);
    }

    public static CandidateEvidenceObservation weaken(String candidateId, String testId, String evidenceRef) {
        return new CandidateEvidenceObservation(candidateId, testId, Polarity.WEAKEN, evidenceRef, null);
    }

    public static CandidateEvidenceObservation unknown(String candidateId, String testId, String reason) {
        return new CandidateEvidenceObservation(candidateId, testId, Polarity.UNKNOWN, null, reason);
    }

    public String candidateId() {
        return candidateId;
    }

    public String testId() {
        return testId;
    }

    public Polarity polarity() {
        return polarity;
    }

    public String evidenceRef() {
        return evidenceRef;
    }

    public String unknownReason() {
        return unknownReason;
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
